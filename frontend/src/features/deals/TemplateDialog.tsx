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
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import Typography from '@mui/material/Typography';
import { useTranslation } from 'react-i18next';
import {
  dealsApi,
  type Frequency,
  type PaymentPlanTemplate,
  type TemplateInput,
} from '@/api/deals';
import { isApiError } from '@/api/errors';
import { inventoryApi, type Project } from '@/api/inventory';
import { isValidAmount } from '@/format/money';

const FREQUENCIES: Frequency[] = ['monthly', 'quarterly', 'semi_annual', 'annual'];

interface Props {
  /** null closes the dialog; a template edits it; 'new' creates one. */
  editing: PaymentPlanTemplate | 'new' | null;
  onClose: () => void;
  onSaved: () => void;
}

/**
 * Defines or corrects one payment plan template (E5-S3).
 *
 * <p>The fields are exactly the shape doc 22 stores and nothing more, because a template is
 * a shape rather than a schedule: a down payment, a delivery percentage, a count and a
 * cadence. There is no preview here and that is deliberate — a template has no net value to
 * apply itself to, so the only honest place to see what it produces is on a deal, where the
 * backend generates and stores the schedule.
 *
 * The down payment is a percentage or a fixed amount and never both, matching the domain's
 * sealed type and the API's contract. The form enforces it by disabling the other field
 * rather than validating afterwards, so the contradictory request cannot be composed.
 */
export function TemplateDialog({ editing, onClose, onSaved }: Props) {
  const { t } = useTranslation();

  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState('');
  const [name, setName] = useState('');
  const [shorthandLabel, setShorthandLabel] = useState('');
  const [downKind, setDownKind] = useState<'percent' | 'fixed'>('percent');
  const [downValue, setDownValue] = useState('');
  const [deliveryPercent, setDeliveryPercent] = useState('');
  const [installmentCount, setInstallmentCount] = useState('');
  const [frequency, setFrequency] = useState<Frequency>('quarterly');
  const [offsetDays, setOffsetDays] = useState('');

  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (editing === null) {
      return;
    }
    setError(null);

    if (editing === 'new') {
      setProjectId('');
      setName('');
      setShorthandLabel('');
      setDownKind('percent');
      setDownValue('');
      setDeliveryPercent('0');
      setInstallmentCount('');
      setFrequency('quarterly');
      setOffsetDays('');
    } else {
      setProjectId(editing.projectId ?? '');
      setName(editing.name);
      setShorthandLabel(editing.shorthandLabel ?? '');
      setDownKind(editing.downPaymentType);
      setDownValue(editing.downPaymentValue);
      setDeliveryPercent(editing.deliveryPaymentPercent);
      setInstallmentCount(String(editing.installmentCount));
      setFrequency(editing.frequency);
      setOffsetDays(
        editing.firstInstallmentOffsetDays === null
          ? ''
          : String(editing.firstInstallmentOffsetDays),
      );
    }

    let cancelled = false;
    void (async () => {
      try {
        const loaded = await inventoryApi.projects();
        if (!cancelled) {
          setProjects(loaded.items);
        }
      } catch {
        // The project scope is optional; without the list a template is tenant-wide, which
        // is the commoner case anyway.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [editing]);

  const countNumber = Number(installmentCount);
  const countInvalid =
    installmentCount.trim() === '' || !Number.isInteger(countNumber) || countNumber < 1;
  const downInvalid = downValue.trim() === '' || !isValidAmount(downValue);
  const deliveryInvalid = deliveryPercent.trim() === '' || !isValidAmount(deliveryPercent);
  const offsetInvalid =
    offsetDays.trim() !== '' &&
    (!Number.isInteger(Number(offsetDays)) || Number(offsetDays) < 0);

  const canSave =
    !submitting &&
    name.trim() !== '' &&
    !countInvalid &&
    !downInvalid &&
    !deliveryInvalid &&
    !offsetInvalid;

  async function save() {
    if (editing === null) {
      return;
    }
    setSubmitting(true);
    setError(null);

    const input: TemplateInput = {
      projectId: projectId === '' ? undefined : projectId,
      name: name.trim(),
      shorthandLabel: shorthandLabel.trim() === '' ? undefined : shorthandLabel.trim(),
      ...(downKind === 'percent'
        ? { downPaymentPercent: downValue.trim() }
        : { downPaymentAmount: downValue.trim() }),
      deliveryPaymentPercent: deliveryPercent.trim(),
      installmentCount: countNumber,
      frequency,
      firstInstallmentOffsetDays: offsetDays.trim() === '' ? undefined : Number(offsetDays),
    };

    try {
      if (editing === 'new') {
        await dealsApi.createTemplate(input);
      } else {
        await dealsApi.updateTemplate(editing.id, input);
      }
      onSaved();
    } catch (cause) {
      setError(isApiError(cause) ? cause.message : t('templates.saveFailed'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={editing !== null} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>
        {editing === 'new' ? t('templates.createTitle') : t('templates.editTitle')}
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error ? <Alert severity="error">{error}</Alert> : null}

          <TextField
            label={t('templates.name')}
            value={name}
            onChange={(event) => setName(event.target.value)}
            required
            fullWidth
          />
          <TextField
            label={t('templates.shorthand')}
            value={shorthandLabel}
            onChange={(event) => setShorthandLabel(event.target.value)}
            helperText={t('templates.shorthandHint')}
            fullWidth
          />
          <TextField
            select
            label={t('templates.project')}
            value={projectId}
            onChange={(event) => setProjectId(event.target.value)}
            helperText={t('templates.projectHint')}
            fullWidth
          >
            <MenuItem value="">{t('templates.everyProject')}</MenuItem>
            {projects.map((project) => (
              <MenuItem key={project.id} value={project.id}>
                {project.displayName}
              </MenuItem>
            ))}
          </TextField>

          <Stack direction="row" spacing={2} alignItems="flex-start">
            <ToggleButtonGroup
              exclusive
              size="small"
              value={downKind}
              onChange={(_event, next: 'percent' | 'fixed' | null) => {
                if (next) {
                  setDownKind(next);
                  setDownValue('');
                }
              }}
              aria-label={t('templates.downPaymentKind')}
              sx={{ mt: 1 }}
            >
              <ToggleButton value="percent">{t('templates.percent')}</ToggleButton>
              <ToggleButton value="fixed">{t('templates.fixedAmount')}</ToggleButton>
            </ToggleButtonGroup>
            <TextField
              label={
                downKind === 'percent'
                  ? t('templates.downPercent')
                  : t('templates.downAmount')
              }
              value={downValue}
              onChange={(event) => setDownValue(event.target.value)}
              error={downValue.trim() !== '' && downInvalid}
              inputProps={{ inputMode: 'decimal' }}
              sx={{ flexGrow: 1 }}
            />
          </Stack>

          <TextField
            label={t('templates.deliveryPercent')}
            value={deliveryPercent}
            onChange={(event) => setDeliveryPercent(event.target.value)}
            error={deliveryPercent.trim() !== '' && deliveryInvalid}
            inputProps={{ inputMode: 'decimal' }}
            fullWidth
          />

          <Stack direction="row" spacing={2}>
            <TextField
              label={t('templates.installmentCount')}
              value={installmentCount}
              onChange={(event) => setInstallmentCount(event.target.value)}
              error={installmentCount.trim() !== '' && countInvalid}
              inputProps={{ inputMode: 'numeric' }}
              sx={{ width: 180 }}
            />
            <TextField
              select
              label={t('templates.frequency')}
              value={frequency}
              onChange={(event) => setFrequency(event.target.value as Frequency)}
              sx={{ flexGrow: 1 }}
            >
              {FREQUENCIES.map((value) => (
                <MenuItem key={value} value={value}>
                  {t(`frequency.${value}`)}
                </MenuItem>
              ))}
            </TextField>
          </Stack>

          <TextField
            label={t('templates.offsetDays')}
            value={offsetDays}
            onChange={(event) => setOffsetDays(event.target.value)}
            error={offsetInvalid}
            helperText={t('templates.offsetHint')}
            inputProps={{ inputMode: 'numeric' }}
            fullWidth
          />

          <Typography variant="caption" color="text.secondary">
            {t('templates.noPreviewHere')}
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={submitting}>
          {t('common.cancel')}
        </Button>
        <Button variant="contained" onClick={() => void save()} disabled={!canSave}>
          {t('templates.save')}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
