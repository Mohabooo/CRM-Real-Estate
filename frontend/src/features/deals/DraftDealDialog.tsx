import { useEffect, useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useTranslation } from 'react-i18next';
import { crmApi, type Customer } from '@/api/crm';
import { dealsApi } from '@/api/deals';
import { isApiError } from '@/api/errors';
import type { Unit } from '@/api/inventory';
import { ACTIVE_STATUSES, reservationsApi, type Reservation } from '@/api/reservations';
import { useLanguage } from '@/app/LanguageContext';
import { formatAmount } from '@/format/money';

interface Props {
  unit: Unit | null;
  onClose: () => void;
  onDrafted: (dealId: string) => void;
}

/**
 * Drafts a deal from a unit and a customer (E5-S1).
 *
 * Customers only, not leads. A reservation may be held for either, but doc 18's precondition
 * for a deal is "customer exists" and the deal's own column is `primary_customer_id`: a lead
 * is somebody who has not yet agreed to buy anything, and converting one is a deliberate act
 * with its own screen rather than a side effect of starting a deal.
 *
 * The gross value is not asked for. It is the unit's list price, frozen onto the deal at
 * creation (R-VAL-1), and it is shown here so the agent sees what they are committing to —
 * but an editable field would be a second source of truth for a number the price list
 * already fixed.
 *
 * Where the unit is held, the hold is named rather than assumed. Doc 18 allows a draft
 * against a unit "held by the same party", and the server checks that against the
 * reservation this dialog passes — not against the unit merely being reserved, which would
 * let one agent draft over a colleague's customer.
 */
export function DraftDealDialog({ unit, onClose, onDrafted }: Props) {
  const { t } = useTranslation();
  const { language } = useLanguage();

  const [customers, setCustomers] = useState<Customer[]>([]);
  const [customerId, setCustomerId] = useState('');
  const [hold, setHold] = useState<Reservation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!unit) {
      return;
    }
    setCustomerId('');
    setHold(null);
    setError(null);

    let cancelled = false;
    void (async () => {
      try {
        const [loaded, holds] = await Promise.all([crmApi.customers(), reservationsApi.list()]);
        if (cancelled) {
          return;
        }
        setCustomers(loaded.items);

        // The live hold on this unit, if any. A confirmed hold is what a deal converts; a
        // pending one has not withheld the unit yet and the deal claims it as open stock.
        const live = holds.items.find(
          (reservation) =>
            reservation.unitId === unit.id && ACTIVE_STATUSES.includes(reservation.status),
        );
        setHold(live ?? null);
        if (live?.customerId) {
          setCustomerId(live.customerId);
        }
      } catch {
        if (!cancelled) {
          setError(t('deals.draft.contextUnavailable'));
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [unit, t]);

  async function draft() {
    if (!unit || customerId === '') {
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const deal = await dealsApi.draft({
        unitId: unit.id,
        customerId,
        ...(hold ? { sourceReservationId: hold.id } : {}),
      });
      onDrafted(deal.id);
    } catch (cause) {
      setError(isApiError(cause) ? cause.message : t('deals.draft.failed'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={unit !== null} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t('deals.draft.title', { code: unit?.code ?? '' })}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error ? <Alert severity="error">{error}</Alert> : null}

          <Typography variant="body2" color="text.secondary">
            {t('deals.draft.grossFromListPrice', {
              amount: formatAmount(unit?.listPrice ?? '', language),
            })}
          </Typography>

          <TextField
            select
            label={t('deals.draft.customer')}
            value={customerId}
            onChange={(event) => setCustomerId(event.target.value)}
            helperText={t('deals.draft.customersOnly')}
            fullWidth
          >
            {customers.map((customer) => (
              <MenuItem key={customer.id} value={customer.id}>
                {customer.displayName}
                {customer.phone ? ` · ${customer.phone}` : ''}
              </MenuItem>
            ))}
          </TextField>

          {hold ? (
            <Alert severity="info">
              {t('deals.draft.convertsHold', {
                status: t(`reservationStatus.${hold.status}`),
              })}
            </Alert>
          ) : null}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={submitting}>
          {t('common.cancel')}
        </Button>
        <Button
          variant="contained"
          onClick={() => void draft()}
          disabled={submitting || customerId === ''}
        >
          {t('deals.draft.create')}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
