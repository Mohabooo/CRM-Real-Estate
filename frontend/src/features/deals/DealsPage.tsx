import { useCallback, useEffect, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Paper from '@mui/material/Paper';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TablePagination from '@mui/material/TablePagination';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { dealsApi, type Deal } from '@/api/deals';
import { isApiError } from '@/api/errors';
import { useLanguage } from '@/app/LanguageContext';
import { formatAmount } from '@/format/money';
import { formatCalendarDate } from '@/format/time';

const STATUS_COLOURS: Record<Deal['status'], 'default' | 'success' | 'info' | 'error'> = {
  draft: 'default',
  active: 'success',
  completed: 'info',
  cancelled: 'error',
};

/**
 * Deals visible to the signed-in user.
 *
 * The list is already narrowed server-side to what the caller's role allows — an agent sees
 * their own, a branch manager their branch, operations everything — and there is no
 * parameter here to widen it. A client-side filter would be a suggestion; the server's
 * scoping is the rule.
 */
export function DealsPage() {
  const { t } = useTranslation();
  const { language } = useLanguage();
  const navigate = useNavigate();

  const [deals, setDeals] = useState<Deal[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await dealsApi.list(page, size);
      setDeals(result.items);
      setTotal(result.totalElements);
    } catch (cause) {
      setError(isApiError(cause) ? cause.message : t('deals.loadFailed'));
      setDeals([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [page, size, t]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <Box>
      <Typography variant="h5" component="h1" gutterBottom>
        {t('deals.listHeading')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        {t('deals.listIntro')}
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
              <TableCell>{t('deals.list.date')}</TableCell>
              <TableCell sx={{ textAlign: 'end' }}>{t('deals.value.gross')}</TableCell>
              <TableCell sx={{ textAlign: 'end' }}>{t('deals.value.discount')}</TableCell>
              <TableCell sx={{ textAlign: 'end' }}>{t('deals.value.net')}</TableCell>
              <TableCell>{t('deals.list.schedule')}</TableCell>
              <TableCell>{t('deals.list.status')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {loading ? (
              <TableRow>
                <TableCell colSpan={6} align="center" sx={{ py: 6 }}>
                  <CircularProgress size={28} />
                </TableCell>
              </TableRow>
            ) : deals.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} align="center" sx={{ py: 6 }}>
                  <Typography color="text.secondary">{t('deals.listEmpty')}</Typography>
                </TableCell>
              </TableRow>
            ) : (
              deals.map((deal) => (
                <TableRow
                  key={deal.id}
                  hover
                  sx={{ cursor: 'pointer' }}
                  onClick={() => navigate(`/deals/${deal.id}`)}
                >
                  <TableCell>{formatCalendarDate(deal.dealDate, language)}</TableCell>
                  <TableCell sx={{ textAlign: 'end', fontVariantNumeric: 'tabular-nums' }}>
                    {formatAmount(deal.grossValue, language)}
                  </TableCell>
                  <TableCell sx={{ textAlign: 'end', fontVariantNumeric: 'tabular-nums' }}>
                    {formatAmount(deal.totalDiscount, language)}
                  </TableCell>
                  <TableCell sx={{ textAlign: 'end', fontVariantNumeric: 'tabular-nums' }}>
                    {formatAmount(deal.netValue, language)}
                  </TableCell>
                  <TableCell>
                    {deal.schedule
                      ? t('deals.list.scheduleOf', { count: deal.schedule.rows.length })
                      : t('deals.list.noSchedule')}
                  </TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      color={STATUS_COLOURS[deal.status]}
                      label={t(`dealStatus.${deal.status}`)}
                    />
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
    </Box>
  );
}
