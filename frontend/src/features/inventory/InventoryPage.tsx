import { useCallback, useEffect, useMemo, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TablePagination from '@mui/material/TablePagination';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useTranslation } from 'react-i18next';
import { isApiError } from '@/api/errors';
import { inventoryApi, type Project, type Unit, type UnitStatus } from '@/api/inventory';
import { useLanguage } from '@/app/LanguageContext';
import { formatAmount, isValidAmount } from '@/format/money';
import { PlaceHoldDialog } from './PlaceHoldDialog';

const STATUS_COLOURS: Record<UnitStatus, 'success' | 'warning' | 'default'> = {
  available: 'success',
  reserved: 'warning',
  sold: 'default',
  blocked: 'default',
};

/**
 * Browsing inventory across every project and both commercial models (E3-S4).
 *
 * The status filter sends the same lowercase codes the API returns, so a value taken from a
 * row can be fed straight back as a filter. That only became true when the API learned to
 * accept its own vocabulary — writing this screen is what surfaced that it did not.
 *
 * Prices are decimal strings from the server to the formatter, never parsed into a number
 * along the way (doc 22 section 8).
 */
export function InventoryPage() {
  const { t } = useTranslation();
  const { language } = useLanguage();

  const [projects, setProjects] = useState<Project[]>([]);
  const [units, setUnits] = useState<Unit[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);

  const [projectId, setProjectId] = useState('');
  const [status, setStatus] = useState<UnitStatus | ''>('');
  const [maxPrice, setMaxPrice] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [holding, setHolding] = useState<Unit | null>(null);
  const [placed, setPlaced] = useState<string | null>(null);

  const priceInvalid = maxPrice.trim() !== '' && !isValidAmount(maxPrice);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await inventoryApi.units({
        projectId: projectId === '' ? undefined : projectId,
        status: status === '' ? undefined : [status],
        maxPrice: priceInvalid || maxPrice.trim() === '' ? undefined : maxPrice.trim(),
        page,
        size,
      });
      setUnits(result.items);
      setTotal(result.totalElements);
    } catch (cause) {
      setError(isApiError(cause) ? cause.message : t('inventory.loadFailed'));
      setUnits([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [projectId, status, maxPrice, priceInvalid, page, size, t]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const result = await inventoryApi.projects();
        if (!cancelled) {
          setProjects(result.items);
        }
      } catch {
        // The project filter is a convenience; without it the list still works unfiltered.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const projectsById = useMemo(
    () => new Map(projects.map((project) => [project.id, project])),
    [projects],
  );

  return (
    <Box>
      <Typography variant="h5" component="h1" gutterBottom>
        {t('inventory.heading')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        {t('inventory.intro')}
      </Typography>

      {placed ? (
        <Alert severity="success" sx={{ mb: 2 }} onClose={() => setPlaced(null)}>
          {t('holds.placed', { code: placed })}
        </Alert>
      ) : null}

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mb: 3 }}>
        <TextField
          select
          label={t('inventory.project')}
          value={projectId}
          onChange={(event) => {
            setProjectId(event.target.value);
            setPage(0);
          }}
          sx={{ minWidth: 220 }}
        >
          <MenuItem value="">{t('inventory.allProjects')}</MenuItem>
          {projects.map((project) => (
            <MenuItem key={project.id} value={project.id}>
              {project.displayName}
            </MenuItem>
          ))}
        </TextField>

        <TextField
          select
          label={t('inventory.status')}
          value={status}
          onChange={(event) => {
            setStatus(event.target.value as UnitStatus | '');
            setPage(0);
          }}
          helperText={t('inventory.statusHint')}
          sx={{ minWidth: 200 }}
        >
          <MenuItem value="">{t('inventory.defaultView')}</MenuItem>
          <MenuItem value="available">{t('unitStatus.available')}</MenuItem>
          <MenuItem value="reserved">{t('unitStatus.reserved')}</MenuItem>
          <MenuItem value="sold">{t('unitStatus.sold')}</MenuItem>
          <MenuItem value="blocked">{t('unitStatus.blocked')}</MenuItem>
        </TextField>

        <TextField
          label={t('inventory.maxPrice')}
          value={maxPrice}
          onChange={(event) => {
            setMaxPrice(event.target.value);
            setPage(0);
          }}
          error={priceInvalid}
          helperText={priceInvalid ? t('inventory.priceInvalid') : ' '}
          inputProps={{ inputMode: 'decimal' }}
          sx={{ minWidth: 180 }}
        />
      </Stack>

      {error ? (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      ) : null}

      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>{t('inventory.code')}</TableCell>
              <TableCell>{t('inventory.project')}</TableCell>
              <TableCell>{t('inventory.type')}</TableCell>
              <TableCell sx={{ textAlign: 'end' }}>{t('inventory.area')}</TableCell>
              <TableCell sx={{ textAlign: 'end' }}>{t('inventory.listPrice')}</TableCell>
              <TableCell>{t('inventory.status')}</TableCell>
              <TableCell sx={{ textAlign: 'end' }}>{t('inventory.action')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {loading ? (
              <TableRow>
                <TableCell colSpan={7} align="center" sx={{ py: 6 }}>
                  <CircularProgress size={28} />
                </TableCell>
              </TableRow>
            ) : units.length === 0 ? (
              <TableRow>
                <TableCell colSpan={7} align="center" sx={{ py: 6 }}>
                  <Typography color="text.secondary">{t('inventory.empty')}</Typography>
                </TableCell>
              </TableRow>
            ) : (
              units.map((unit) => (
                <TableRow key={unit.id} hover>
                  <TableCell>{unit.code}</TableCell>
                  <TableCell>{projectsById.get(unit.projectId)?.displayName ?? '—'}</TableCell>
                  <TableCell>{unit.type ?? '—'}</TableCell>
                  <TableCell sx={{ textAlign: 'end' }}>{unit.areaSqm ?? '—'}</TableCell>
                  <TableCell sx={{ textAlign: 'end' }}>{formatAmount(unit.listPrice, language)}</TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      color={STATUS_COLOURS[unit.status]}
                      label={t(`unitStatus.${unit.status}`)}
                    />
                  </TableCell>
                  <TableCell sx={{ textAlign: 'end' }}>
                    <Button
                      size="small"
                      variant="outlined"
                      disabled={unit.status !== 'available'}
                      onClick={() => setHolding(unit)}
                    >
                      {t('holds.place')}
                    </Button>
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
        <TablePagination
          component="div"
          count={total}
          page={page}
          rowsPerPage={size}
          rowsPerPageOptions={[10, 25, 50]}
          onPageChange={(_event, next) => setPage(next)}
          onRowsPerPageChange={(event) => {
            setSize(Number(event.target.value));
            setPage(0);
          }}
          labelRowsPerPage={t('common.rowsPerPage')}
        />
      </TableContainer>

      <PlaceHoldDialog
        unit={holding}
        onClose={() => setHolding(null)}
        onPlaced={() => {
          setPlaced(holding?.code ?? null);
          setHolding(null);
          void load();
        }}
      />
    </Box>
  );
}
