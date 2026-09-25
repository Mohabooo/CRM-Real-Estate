import { useCallback, useEffect, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Divider from '@mui/material/Divider';
import IconButton from '@mui/material/IconButton';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router-dom';
import {
  dealsApi,
  describeTemplate,
  type Deal,
  type DiscountInput,
  type PaymentPlanTemplate,
} from '@/api/deals';
import { isApiError } from '@/api/errors';
import { inventoryApi, type Unit } from '@/api/inventory';
import { useLanguage } from '@/app/LanguageContext';
import { formatAmount, isValidAmount } from '@/format/money';
import { formatCalendarDate } from '@/format/time';
import { ScheduleView } from './ScheduleView';

const DEAL_STATUS_COLOURS: Record<Deal['status'], 'default' | 'success' | 'info' | 'error'> = {
  draft: 'default',
  active: 'success',
  completed: 'info',
  cancelled: 'error',
};

/**
 * One deal, from draft to activation (E5-S1, S2, S4, S5, S7, S8).
 *
 * The screen is arranged as the sale actually proceeds: what the unit costs, what was taken
 * off it, what that leaves, the schedule that produces, and only then the button that makes
 * it real. Activation sits last and is disabled with its reason shown, because an agent who
 * cannot activate needs to know which of doc 18's four preconditions is missing rather than
 * to press a button and read a 422.
 *
 * Not one figure on this page is computed here. The net value, the approval requirement, the
 * schedule and its total all arrive from the API. Even "does this need a manager" is the
 * server's answer, because the threshold is tenant configuration and a client that guessed
 * at it would be wrong for every tenant but one.
 */
export function DealPage() {
  const { t } = useTranslation();
  const { language } = useLanguage();
  const { id = '' } = useParams();
  const navigate = useNavigate();

  const [deal, setDeal] = useState<Deal | null>(null);
  const [unit, setUnit] = useState<Unit | null>(null);
  const [templates, setTemplates] = useState<PaymentPlanTemplate[]>([]);
  const [templatesFailed, setTemplatesFailed] = useState(false);
  const [templateId, setTemplateId] = useState('');

  const [discountPercent, setDiscountPercent] = useState('');
  const [discountAmount, setDiscountAmount] = useState('');
  const [discountReason, setDiscountReason] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const loaded = await dealsApi.get(id);
      setDeal(loaded);
      setTemplateId(loaded.schedule?.sourceTemplateId ?? '');

      // Both are context around the deal itself, so a failure in either leaves the page
      // usable rather than replacing it with an error.
      const [offered, itsUnit] = await Promise.allSettled([
        dealsApi.offeredTemplates(id),
        inventoryApi.unit(loaded.unitId),
      ]);
      // Distinguished, because "none are offered" and "we could not ask" look identical on
      // screen and mean entirely different things: the first is a fact about the tenant's
      // setup, the second is a fault. Reporting the fault as the fact sends somebody off to
      // configure templates that already exist.
      setTemplatesFailed(offered.status === 'rejected');
      if (offered.status === 'fulfilled') {
        setTemplates(offered.value);
      }
      if (itsUnit.status === 'fulfilled') {
        setUnit(itsUnit.value);
      }
    } catch (cause) {
      setError(isApiError(cause) ? cause.message : t('deals.loadFailed'));
    } finally {
      setLoading(false);
    }
  }, [id, t]);

  useEffect(() => {
    void load();
  }, [load]);

  /** Runs one action, keeping the returned deal rather than refetching it. */
  const run = useCallback(
    async (action: () => Promise<Deal>, successKey?: string) => {
      setBusy(true);
      setError(null);
      setNotice(null);
      try {
        const updated = await action();
        setDeal(updated);
        if (successKey) {
          setNotice(t(successKey));
        }
        return true;
      } catch (cause) {
        setError(isApiError(cause) ? cause.message : t('deals.actionFailed'));
        return false;
      } finally {
        setBusy(false);
      }
    },
    [t],
  );

  if (loading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (!deal) {
    return <Alert severity="error">{error ?? t('deals.loadFailed')}</Alert>;
  }

  const editable = deal.status === 'draft';
  const percentInvalid = discountPercent.trim() !== '' && !isValidAmount(discountPercent);
  const amountInvalid = discountAmount.trim() !== '' && !isValidAmount(discountAmount);
  const bothGiven = discountPercent.trim() !== '' && discountAmount.trim() !== '';
  const canAddDiscount =
    editable &&
    !busy &&
    !bothGiven &&
    !percentInvalid &&
    !amountInvalid &&
    (discountPercent.trim() !== '' || discountAmount.trim() !== '');

  const blockingReason = activationBlocker(deal);

  return (
    <Box>
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 1 }}>
        <Typography variant="h5" component="h1">
          {t('deals.heading')}
        </Typography>
        <Chip
          color={DEAL_STATUS_COLOURS[deal.status]}
          label={t(`dealStatus.${deal.status}`)}
          data-testid="deal-status"
        />
        <Chip
          variant="outlined"
          size="small"
          label={t(`commercialModel.${deal.commercialModel}`)}
        />
        <Box sx={{ flexGrow: 1 }} />
        <Button size="small" onClick={() => navigate('/deals')}>
          {t('deals.backToList')}
        </Button>
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        {unit
          ? t('deals.subtitleWithUnit', {
              unit: unit.code,
              date: formatCalendarDate(deal.dealDate, language),
              status: t(`unitStatus.${unit.status}`),
            })
          : t('deals.dealDate', { date: formatCalendarDate(deal.dealDate, language) })}
      </Typography>

      {notice ? (
        <Alert severity="success" sx={{ mb: 2 }} onClose={() => setNotice(null)}>
          {notice}
        </Alert>
      ) : null}
      {error ? (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      ) : null}

      {/* ---------------------------------------------------------- value */}
      <Paper variant="outlined" sx={{ p: 2, mb: 3 }}>
        <Typography variant="subtitle1" sx={{ mb: 1 }}>
          {t('deals.value.heading')}
        </Typography>
        <Table size="small">
          <TableBody>
            <ValueRow label={t('deals.value.gross')} amount={deal.grossValue} />
            <ValueRow
              label={t('deals.value.discount')}
              amount={deal.totalDiscount}
              negative={deal.totalDiscount !== '0.00'}
            />
            <ValueRow label={t('deals.value.net')} amount={deal.netValue} strong />
          </TableBody>
        </Table>
        <Typography variant="caption" color="text.secondary">
          {t('deals.value.grossIsFrozen')}
        </Typography>
      </Paper>

      {/* ------------------------------------------------------- discounts */}
      <Paper variant="outlined" sx={{ p: 2, mb: 3 }}>
        <Typography variant="subtitle1" sx={{ mb: 1 }}>
          {t('deals.discounts.heading')}
        </Typography>

        {deal.discounts.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {t('deals.discounts.none')}
          </Typography>
        ) : (
          <Table size="small" sx={{ mb: 2 }}>
            <TableBody>
              {deal.discounts.map((discount) => (
                <TableRow key={discount.id}>
                  <TableCell>
                    {discount.kind === 'percent'
                      ? t('deals.discounts.percentOf', { value: discount.value })
                      : t('deals.discounts.fixed')}
                  </TableCell>
                  <TableCell>{discount.reason ?? '—'}</TableCell>
                  <TableCell sx={{ textAlign: 'end', fontVariantNumeric: 'tabular-nums' }}>
                    −{formatAmount(discount.amount, language)}
                  </TableCell>
                  <TableCell sx={{ width: 48, textAlign: 'end' }}>
                    {editable ? (
                      <IconButton
                        size="small"
                        aria-label={t('deals.discounts.remove')}
                        disabled={busy}
                        onClick={() => {
                          void run(() => dealsApi.removeDiscount(deal.id, discount.id));
                        }}
                      >
                        ×
                      </IconButton>
                    ) : null}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}

        {editable ? (
          <>
            <Divider sx={{ my: 2 }} />
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems="flex-start">
              <TextField
                size="small"
                label={t('deals.discounts.percent')}
                value={discountPercent}
                onChange={(event) => setDiscountPercent(event.target.value)}
                error={percentInvalid || bothGiven}
                disabled={discountAmount.trim() !== ''}
                inputProps={{ inputMode: 'decimal' }}
                sx={{ width: 160 }}
              />
              <TextField
                size="small"
                label={t('deals.discounts.amount')}
                value={discountAmount}
                onChange={(event) => setDiscountAmount(event.target.value)}
                error={amountInvalid || bothGiven}
                disabled={discountPercent.trim() !== ''}
                inputProps={{ inputMode: 'decimal' }}
                sx={{ width: 200 }}
              />
              <TextField
                size="small"
                label={t('deals.discounts.reason')}
                value={discountReason}
                onChange={(event) => setDiscountReason(event.target.value)}
                sx={{ flexGrow: 1, minWidth: 200 }}
              />
              <Button
                variant="outlined"
                disabled={!canAddDiscount}
                onClick={() => {
                  const reason = discountReason.trim() === '' ? undefined : discountReason.trim();
                  const input: DiscountInput =
                    discountPercent.trim() !== ''
                      ? { percent: discountPercent.trim(), reason }
                      : { amount: discountAmount.trim(), reason };
                  void run(() => dealsApi.addDiscount(deal.id, input)).then((ok) => {
                    if (ok) {
                      setDiscountPercent('');
                      setDiscountAmount('');
                      setDiscountReason('');
                    }
                  });
                }}
              >
                {t('deals.discounts.add')}
              </Button>
            </Stack>
            <Typography variant="caption" color="text.secondary">
              {t('deals.discounts.eitherOr')}
            </Typography>
          </>
        ) : null}

        {deal.approvalRequired ? (
          <Alert
            severity={deal.discountApproved ? 'success' : 'warning'}
            sx={{ mt: 2 }}
            action={
              editable && !deal.discountApproved ? (
                <Button
                  size="small"
                  disabled={busy}
                  onClick={() => {
                    void run(() => dealsApi.approveDiscount(deal.id), 'deals.discounts.approved');
                  }}
                >
                  {t('deals.discounts.approve')}
                </Button>
              ) : null
            }
          >
            {deal.discountApproved
              ? t('deals.discounts.approvalRecorded')
              : t('deals.discounts.approvalRequired')}
          </Alert>
        ) : null}
      </Paper>

      {/* -------------------------------------------------------- schedule */}
      <Paper variant="outlined" sx={{ p: 2, mb: 3 }}>
        <Typography variant="subtitle1" sx={{ mb: 1 }}>
          {t('deals.plan.heading')}
        </Typography>

        {editable ? (
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mb: 3 }}>
            <TextField
              select
              size="small"
              label={t('deals.plan.template')}
              value={templateId}
              onChange={(event) => setTemplateId(event.target.value)}
              sx={{ minWidth: 340 }}
              error={templatesFailed}
              helperText={
                templatesFailed
                  ? t('deals.plan.templatesUnavailable')
                  : templates.length === 0
                    ? t('deals.plan.noTemplates')
                    : ' '
              }
            >
              {templates.map((template) => (
                <MenuItem key={template.id} value={template.id}>
                  {template.name} — {describeTemplate(template, t)}
                </MenuItem>
              ))}
            </TextField>
            <Box>
              <Button
                variant="contained"
                disabled={busy || templateId === ''}
                onClick={() => {
                  void run(
                    () => dealsApi.applyTemplate(deal.id, templateId),
                    'deals.plan.generated',
                  );
                }}
              >
                {deal.schedule ? t('deals.plan.regenerate') : t('deals.plan.generate')}
              </Button>
            </Box>
          </Stack>
        ) : null}

        {deal.schedule ? (
          <ScheduleView schedule={deal.schedule} />
        ) : (
          <Typography variant="body2" color="text.secondary">
            {t('deals.plan.none')}
          </Typography>
        )}
      </Paper>

      {/* ------------------------------------------------------ activation */}
      <Paper variant="outlined" sx={{ p: 2 }}>
        <Typography variant="subtitle1" sx={{ mb: 1 }}>
          {t('deals.activation.heading')}
        </Typography>

        {deal.status === 'draft' ? (
          <>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              {blockingReason ? t(blockingReason) : t('deals.activation.ready')}
            </Typography>
            <Stack direction="row" spacing={2}>
              <Button
                variant="contained"
                color="primary"
                disabled={busy || blockingReason !== null}
                onClick={() => {
                  void run(() => dealsApi.activate(deal.id), 'deals.activation.activated');
                }}
              >
                {t('deals.activation.activate')}
              </Button>
              <Button
                color="inherit"
                disabled={busy}
                onClick={() => {
                  void run(
                    () => dealsApi.cancel(deal.id, t('deals.activation.cancelledByAgent')),
                    'deals.activation.cancelled',
                  );
                }}
              >
                {t('deals.activation.cancel')}
              </Button>
            </Stack>
          </>
        ) : (
          <ActiveDealActions deal={deal} busy={busy} run={run} />
        )}
      </Paper>
    </Box>
  );
}

/**
 * Which of doc 18's activation preconditions is not met yet, as a message key.
 *
 * The client checks only the two it can see without guessing — a schedule exists, and an
 * approval that the SERVER said was required has been given. It deliberately does not
 * re-derive the threshold, re-sum the schedule, or predict whether the unit is still
 * available: all three are the server's to decide at the moment of activation, and a client
 * that answered them would be answering with stale information.
 */
function activationBlocker(deal: Deal): string | null {
  if (!deal.schedule) {
    return 'deals.activation.needsSchedule';
  }
  if (deal.schedule.total !== deal.netValue) {
    return 'deals.activation.scheduleStale';
  }
  if (deal.approvalRequired && !deal.discountApproved) {
    return 'deals.activation.needsApproval';
  }
  return null;
}

/** What is left to do once a deal is live: E5-S7 and E5-S8, where the model allows them. */
function ActiveDealActions({
  deal,
  busy,
  run,
}: {
  deal: Deal;
  busy: boolean;
  run: (action: () => Promise<Deal>, successKey?: string) => Promise<boolean>;
}) {
  const { t } = useTranslation();
  const { language } = useLanguage();

  if (deal.status !== 'active') {
    return (
      <Typography variant="body2" color="text.secondary">
        {deal.status === 'completed'
          ? t('deals.activation.completedOn', {
              date: formatCalendarDate(deal.completedAt, language),
            })
          : t('deals.activation.cancelledBecause', { reason: deal.cancelledReason ?? '—' })}
      </Typography>
    );
  }

  return (
    <Stack spacing={2}>
      <Typography variant="body2" color="text.secondary">
        {t('deals.activation.liveUnitSold')}
      </Typography>

      {deal.downPaymentConfirmationApplies ? (
        <Box>
          {deal.downPaymentConfirmedAt ? (
            <Alert severity="success">
              {t('deals.downPayment.confirmedOn', {
                date: formatCalendarDate(deal.downPaymentConfirmedAt, language),
              })}
            </Alert>
          ) : (
            <Stack direction="row" spacing={2} alignItems="center">
              <Typography variant="body2">{t('deals.downPayment.prompt')}</Typography>
              <Button
                size="small"
                variant="outlined"
                disabled={busy}
                onClick={() => {
                  void run(
                    () => dealsApi.confirmDownPayment(deal.id),
                    'deals.downPayment.confirmed',
                  );
                }}
              >
                {t('deals.downPayment.confirm')}
              </Button>
            </Stack>
          )}
          <Typography variant="caption" color="text.secondary">
            {t('deals.downPayment.noPaymentCreated')}
          </Typography>
        </Box>
      ) : null}

      <Box>
        <Button
          variant="outlined"
          disabled={busy}
          onClick={() => {
            void run(() => dealsApi.complete(deal.id), 'deals.activation.completed');
          }}
        >
          {t('deals.activation.complete')}
        </Button>
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.5 }}>
          {t('deals.activation.completionNote')}
        </Typography>
      </Box>
    </Stack>
  );
}

function ValueRow({
  label,
  amount,
  strong,
  negative,
}: {
  label: string;
  amount: string;
  strong?: boolean;
  negative?: boolean;
}) {
  const { language } = useLanguage();
  return (
    <TableRow>
      <TableCell sx={{ border: 0, fontWeight: strong ? 600 : 400 }}>{label}</TableCell>
      <TableCell
        sx={{
          border: 0,
          textAlign: 'end',
          fontVariantNumeric: 'tabular-nums',
          fontWeight: strong ? 600 : 400,
          fontSize: strong ? '1.1rem' : undefined,
        }}
      >
        {negative ? '−' : ''}
        {formatAmount(amount, language)}
      </TableCell>
    </TableRow>
  );
}
