import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Divider from '@mui/material/Divider';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import { useTranslation } from 'react-i18next';
import type { Schedule } from '@/api/deals';
import { useLanguage } from '@/app/LanguageContext';
import { formatAmount } from '@/format/money';
import { formatCalendarDate } from '@/format/time';

/**
 * The generated schedule, displayed exactly as the server produced it.
 *
 * Nothing here is calculated. Every figure below — the decomposition, each row's amount, and
 * the total the rows come to — arrives from the API, because the backend generated this
 * schedule, stored it, and will make these same rows live on activation. The one arithmetic
 * statement on the screen, that the parts sum to the net value, is shown as the server's
 * answer rather than re-derived in the browser: a client that computed its own total could
 * agree with itself and disagree with the contract.
 *
 * There is no outstanding, paid or overdue column. Those need actual payments, which belong
 * to Epic 6 — and a zero in a column called "paid" is read as a fact about a customer.
 */
export function ScheduleView({ schedule }: { schedule: Schedule }) {
  const { t } = useTranslation();
  const { language } = useLanguage();

  const balances = schedule.total === schedule.netValue;

  return (
    <Box>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        sx={{ mb: 2 }}
        divider={<Divider orientation="vertical" flexItem />}
      >
        <Figure label={t('deals.schedule.downPayment')} value={schedule.downPaymentAmount} />
        <Figure label={t('deals.schedule.financed')} value={schedule.financedAmount} />
        <Figure label={t('deals.schedule.delivery')} value={schedule.deliveryPaymentAmount} />
        <Figure label={t('deals.schedule.netValue')} value={schedule.netValue} strong />
      </Stack>

      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('deals.schedule.terms', {
          count: schedule.installmentCount,
          frequency: t(`frequency.${schedule.frequency}`),
          first: formatCalendarDate(schedule.firstDueDate, language),
        })}
      </Typography>

      {balances ? null : (
        <Alert severity="error" sx={{ mb: 2 }}>
          {t('deals.schedule.doesNotBalance', {
            total: formatAmount(schedule.total, language),
            net: formatAmount(schedule.netValue, language),
          })}
        </Alert>
      )}

      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell sx={{ width: 64 }}>{t('deals.schedule.number')}</TableCell>
              <TableCell>{t('deals.schedule.kind')}</TableCell>
              <TableCell>{t('deals.schedule.dueDate')}</TableCell>
              <TableCell sx={{ textAlign: 'end' }}>{t('deals.schedule.expected')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {schedule.rows.map((row) => (
              <TableRow key={row.id} hover>
                <TableCell>{row.sequenceNo}</TableCell>
                <TableCell>
                  <Chip
                    size="small"
                    variant={row.kind === 'installment' ? 'outlined' : 'filled'}
                    color={row.kind === 'installment' ? 'default' : 'primary'}
                    label={t(`installmentKind.${row.kind}`)}
                  />
                </TableCell>
                <TableCell>{formatCalendarDate(row.dueDate, language)}</TableCell>
                <TableCell sx={{ textAlign: 'end', fontVariantNumeric: 'tabular-nums' }}>
                  {formatAmount(row.expectedAmount, language)}
                </TableCell>
              </TableRow>
            ))}
            <TableRow>
              <TableCell colSpan={3} sx={{ fontWeight: 600 }}>
                {t('deals.schedule.total')}
              </TableCell>
              <TableCell
                sx={{ textAlign: 'end', fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}
              >
                {formatAmount(schedule.total, language)}
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </TableContainer>

      <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
        {t('deals.schedule.expectedOnly')}
      </Typography>
    </Box>
  );
}

function Figure({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  const { language } = useLanguage();
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" display="block">
        {label}
      </Typography>
      <Typography
        variant={strong ? 'h6' : 'body1'}
        sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: strong ? 600 : 400 }}
      >
        {formatAmount(value, language)}
      </Typography>
    </Box>
  );
}
