import { useCallback, useEffect, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogContentText from '@mui/material/DialogContentText';
import DialogTitle from '@mui/material/DialogTitle';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { useTranslation } from 'react-i18next';
import { isApiError } from '@/api/errors';
import { inventoryApi, type Unit } from '@/api/inventory';
import {
  ACTIVE_STATUSES,
  reservationsApi,
  type Reservation,
  type ReservationStatus,
} from '@/api/reservations';
import { useLanguage } from '@/app/LanguageContext';
import { formatAmount } from '@/format/money';
import { formatDate, timeRemaining } from '@/format/time';

const STATUS_COLOURS: Record<ReservationStatus, 'warning' | 'success' | 'default'> = {
  pending: 'warning',
  confirmed: 'success',
  converted: 'success',
  released: 'default',
  expired: 'default',
  cancelled: 'default',
};

type Pending = { kind: 'release'; hold: Reservation } | { kind: 'extend'; hold: Reservation };

/**
 * The holds this caller can see, and what can still be done to them.
 *
 * The list is already narrowed by the server to the caller's branch scope — there is no
 * parameter here to widen it, because doc 23 does not offer one. An agent sees their own
 * holds, a branch manager sees the branch's.
 *
 * Releasing requires a reason and the form enforces it, because the API does and because a
 * unit going back on the market without an explanation is the kind of thing somebody has to
 * reconstruct from memory three weeks later.
 */
export function HoldsPage() {
  const { t } = useTranslation();
  const { language } = useLanguage();

  const [holds, setHolds] = useState<Reservation[]>([]);
  const [units, setUnits] = useState<Map<string, Unit>>(new Map());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState<Pending | null>(null);
  const [reason, setReason] = useState('');
  const [newExpiry, setNewExpiry] = useState('');
  const [working, setWorking] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  // Captured when the list loads rather than ticking: a hold measured in days does not need
  // a second hand, and a timer would redraw the table for nobody's benefit. Refreshing the
  // page is what moves it forward.
  const [now, setNow] = useState(() => new Date());

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await reservationsApi.list();
      setHolds(result.items);
      setNow(new Date());

      // Unit codes are what an agent recognises; the hold only carries an id. One extra
      // request for the whole page beats one per row.
      const page = await inventoryApi.units({
        status: ['available', 'reserved', 'sold', 'blocked'],
        size: 200,
      });
      setUnits(new Map(page.items.map((unit) => [unit.id, unit])));
    } catch (cause) {
      setError(isApiError(cause) ? cause.message : t('holds.loadFailed'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  function begin(next: Pending) {
    setPending(next);
    setReason('');
    setActionError(null);
    if (next.kind === 'extend') {
      // Seeded a week out from the current expiry, which is the extension an agent almost
      // always wants and which they can still change.
      const suggested = new Date(next.hold.expiresAt);
      suggested.setDate(suggested.getDate() + 7);
      setNewExpiry(suggested.toISOString().slice(0, 16));
    }
  }

  async function confirmAction() {
    if (!pending) {
      return;
    }
    setWorking(true);
    setActionError(null);
    try {
      if (pending.kind === 'release') {
        await reservationsApi.release(pending.hold.id, reason.trim());
      } else {
        await reservationsApi.extend(
          pending.hold.id,
          new Date(newExpiry).toISOString(),
          reason.trim() === '' ? undefined : reason.trim(),
        );
      }
      setPending(null);
      await load();
    } catch (cause) {
      setActionError(isApiError(cause) ? cause.message : t('holds.actionFailed'));
    } finally {
      setWorking(false);
    }
  }

  const canConfirm =
    pending?.kind === 'release'
      ? reason.trim() !== ''
      : newExpiry !== '' && !Number.isNaN(new Date(newExpiry).getTime());

  return (
    <Box>
      <Typography variant="h5" component="h1" gutterBottom>
        {t('holds.heading')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        {t('holds.intro')}
      </Typography>

      {error ? (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      ) : null}

      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>{t('holds.unit')}</TableCell>
              <TableCell>{t('holds.status')}</TableCell>
              <TableCell>{t('holds.expires')}</TableCell>
              <TableCell sx={{ textAlign: 'end' }}>{t('holds.deposit')}</TableCell>
              <TableCell sx={{ textAlign: 'end' }}>{t('inventory.action')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {loading ? (
              <TableRow>
                <TableCell colSpan={5} align="center" sx={{ py: 6 }}>
                  <CircularProgress size={28} />
                </TableCell>
              </TableRow>
            ) : holds.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5} align="center" sx={{ py: 6 }}>
                  <Typography color="text.secondary">{t('holds.empty')}</Typography>
                </TableCell>
              </TableRow>
            ) : (
              holds.map((hold) => {
                const active = ACTIVE_STATUSES.includes(hold.status);
                return (
                  <TableRow key={hold.id} hover>
                    <TableCell>{units.get(hold.unitId)?.code ?? '—'}</TableCell>
                    <TableCell>
                      <Chip
                        size="small"
                        color={STATUS_COLOURS[hold.status]}
                        label={t(`reservationStatus.${hold.status}`)}
                      />
                    </TableCell>
                    <TableCell>
                      <Tooltip title={formatDate(hold.expiresAt, language)}>
                        <span>{active ? timeRemaining(hold.expiresAt, now, t) : '—'}</span>
                      </Tooltip>
                    </TableCell>
                    <TableCell sx={{ textAlign: 'end' }}>
                      {hold.depositAmount ? (
                        <Tooltip title={t(`depositMeaning.${hold.depositMeaning}`)}>
                          <span>
                            {formatAmount(hold.depositAmount, language)}
                            {hold.depositReceived ? '' : ` (${t('holds.depositPending')})`}
                          </span>
                        </Tooltip>
                      ) : (
                        '—'
                      )}
                    </TableCell>
                    <TableCell sx={{ textAlign: 'end' }}>
                      <Stack direction="row" spacing={1} justifyContent="flex-end">
                        <Button
                          size="small"
                          disabled={!active}
                          onClick={() => begin({ kind: 'extend', hold })}
                        >
                          {t('holds.extend')}
                        </Button>
                        <Button
                          size="small"
                          color="warning"
                          disabled={!active}
                          onClick={() => begin({ kind: 'release', hold })}
                        >
                          {t('holds.release')}
                        </Button>
                      </Stack>
                    </TableCell>
                  </TableRow>
                );
              })
            )}
          </TableBody>
        </Table>
      </TableContainer>

      <Dialog open={pending !== null} onClose={() => setPending(null)} fullWidth maxWidth="sm">
        <DialogTitle>
          {pending?.kind === 'release' ? t('holds.releaseTitle') : t('holds.extendTitle')}
        </DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {actionError ? <Alert severity="error">{actionError}</Alert> : null}

            {pending?.kind === 'release' ? (
              <DialogContentText>{t('holds.releaseExplain')}</DialogContentText>
            ) : (
              <TextField
                label={t('holds.newExpiry')}
                type="datetime-local"
                value={newExpiry}
                onChange={(event) => setNewExpiry(event.target.value)}
                InputLabelProps={{ shrink: true }}
                fullWidth
              />
            )}

            <TextField
              label={t('holds.reason')}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              required={pending?.kind === 'release'}
              helperText={
                pending?.kind === 'release' ? t('holds.reasonRequired') : t('holds.reasonOptional')
              }
              multiline
              minRows={2}
              fullWidth
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPending(null)}>{t('common.cancel')}</Button>
          <Button
            variant="contained"
            color={pending?.kind === 'release' ? 'warning' : 'primary'}
            onClick={() => void confirmAction()}
            disabled={working || !canConfirm}
          >
            {pending?.kind === 'release' ? t('holds.release') : t('holds.extend')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
