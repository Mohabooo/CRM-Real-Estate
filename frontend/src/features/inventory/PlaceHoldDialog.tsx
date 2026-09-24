import { useEffect, useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import FormControlLabel from '@mui/material/FormControlLabel';
import ListSubheader from '@mui/material/ListSubheader';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useTranslation } from 'react-i18next';
import { crmApi, partiesOf, type Party } from '@/api/crm';
import { isApiError } from '@/api/errors';
import type { Unit } from '@/api/inventory';
import { reservationsApi } from '@/api/reservations';
import { isValidAmount } from '@/format/money';

interface Props {
  unit: Unit | null;
  onClose: () => void;
  onPlaced: () => void;
}

/**
 * Places a hold on one unit.
 *
 * The expiry field is deliberately absent. E4-S1 says a hold's expiry defaults from tenant
 * settings, and asking an agent with a customer in front of them to pick a date is one
 * decision too many for the common case. Extending one later is a separate, audited action.
 *
 * Exactly one party is chosen, because the database allows exactly one: a reservation is
 * held for a lead or a customer, never both and never neither.
 */
export function PlaceHoldDialog({ unit, onClose, onPlaced }: Props) {
  const { t } = useTranslation();

  const [parties, setParties] = useState<Party[]>([]);
  const [partyKey, setPartyKey] = useState('');
  const [deposit, setDeposit] = useState('');
  const [depositReceived, setDepositReceived] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!unit) {
      return;
    }
    setPartyKey('');
    setDeposit('');
    setDepositReceived(false);
    setError(null);

    let cancelled = false;
    void (async () => {
      try {
        const [customers, leads] = await Promise.all([crmApi.customers(), crmApi.leads()]);
        if (!cancelled) {
          setParties(partiesOf(customers.items, leads.items));
        }
      } catch {
        if (!cancelled) {
          setError(t('holds.partiesUnavailable'));
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [unit, t]);

  const depositInvalid = deposit.trim() !== '' && !isValidAmount(deposit);

  async function place() {
    if (!unit) {
      return;
    }
    const party = parties.find((candidate) => `${candidate.kind}:${candidate.id}` === partyKey);
    if (!party) {
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      await reservationsApi.place({
        unitId: unit.id,
        leadId: party.kind === 'lead' ? party.id : undefined,
        customerId: party.kind === 'customer' ? party.id : undefined,
        depositAmount: deposit.trim() === '' ? undefined : deposit.trim(),
        depositReceived,
      });
      onPlaced();
    } catch (cause) {
      // A 409 is the interesting one: somebody else took the unit between the list being
      // drawn and this button being pressed. The server's message names the conflict, so it
      // is shown rather than replaced with something vaguer.
      setError(isApiError(cause) ? cause.message : t('holds.placeFailed'));
    } finally {
      setSubmitting(false);
    }
  }

  const customers = parties.filter((party) => party.kind === 'customer');
  const leads = parties.filter((party) => party.kind === 'lead');

  return (
    <Dialog open={unit !== null} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t('holds.placeTitle', { code: unit?.code ?? '' })}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {error ? <Alert severity="error">{error}</Alert> : null}

          <TextField
            select
            label={t('holds.party')}
            helperText={t('holds.partyHint')}
            value={partyKey}
            onChange={(event) => setPartyKey(event.target.value)}
            required
            fullWidth
          >
            {customers.length > 0 ? (
              <ListSubheader>{t('holds.customers')}</ListSubheader>
            ) : null}
            {customers.map((party) => (
              <MenuItem key={`customer:${party.id}`} value={`customer:${party.id}`}>
                {party.name}
                {party.phone ? ` — ${party.phone}` : ''}
              </MenuItem>
            ))}
            {leads.length > 0 ? <ListSubheader>{t('holds.leads')}</ListSubheader> : null}
            {leads.map((party) => (
              <MenuItem key={`lead:${party.id}`} value={`lead:${party.id}`}>
                {party.name}
                {party.phone ? ` — ${party.phone}` : ''}
              </MenuItem>
            ))}
          </TextField>

          <TextField
            label={t('holds.deposit')}
            value={deposit}
            onChange={(event) => setDeposit(event.target.value)}
            error={depositInvalid}
            helperText={depositInvalid ? t('holds.depositInvalid') : t('holds.depositOptional')}
            inputProps={{ inputMode: 'decimal' }}
            fullWidth
          />

          <FormControlLabel
            control={
              <Switch
                checked={depositReceived}
                onChange={(event) => setDepositReceived(event.target.checked)}
              />
            }
            label={t('holds.depositReceived')}
          />

          <Typography variant="caption" color="text.secondary">
            {t('holds.expiryNote')}
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t('common.cancel')}</Button>
        <Button
          variant="contained"
          onClick={() => void place()}
          disabled={submitting || partyKey === '' || depositInvalid}
        >
          {t('holds.place')}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
