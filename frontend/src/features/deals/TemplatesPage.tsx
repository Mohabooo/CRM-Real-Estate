import { useCallback, useEffect, useMemo, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import FormControlLabel from '@mui/material/FormControlLabel';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import { useTranslation } from 'react-i18next';
import { dealsApi, describeTemplate, type PaymentPlanTemplate } from '@/api/deals';
import { isApiError } from '@/api/errors';
import { inventoryApi, type Project } from '@/api/inventory';
import { useAuth } from '@/features/auth/AuthContext';
import { TemplateDialog } from './TemplateDialog';

/** Doc 18 and doc 20 give template administration to operations. */
const ADMINISTRATORS = ['OPERATIONS', 'OWNER', 'PLATFORM_ADMIN'];

/**
 * E5-S3 — "As ops, I can define reusable payment plan templates with market shorthand
 * labels."
 *
 * <p>Shows every template including archived ones, because this is the administration list
 * rather than the offer list an agent sees on a deal. The two are deliberately different
 * queries: retiring a template must remove it from what can be sold without hiding it from
 * the person who retired it.
 *
 * <p>Nothing here computes a schedule, and there is no preview. A template has no net value
 * to apply itself to — the same shape produces different money on every deal — so the only
 * place a schedule can honestly be shown is a deal, where the backend generates and stores
 * it. A "sample schedule" on this screen would be arithmetic invented in the browser against
 * a price nobody has agreed.
 *
 * <p>Archiving rather than deleting, throughout. Plans made from a template copied its terms
 * (TPL-001), so a retired template's remaining job is to explain where a live customer's
 * schedule came from.
 */
export function TemplatesPage() {
  const { t } = useTranslation();
  const { me } = useAuth();

  const [templates, setTemplates] = useState<PaymentPlanTemplate[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [showArchived, setShowArchived] = useState(false);
  const [editing, setEditing] = useState<PaymentPlanTemplate | 'new' | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const mayAdminister = me !== null && ADMINISTRATORS.includes(me.role);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setTemplates(await dealsApi.templates());
    } catch (cause) {
      setError(isApiError(cause) ? cause.message : t('templates.loadFailed'));
      setTemplates([]);
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const loaded = await inventoryApi.projects();
        if (!cancelled) {
          setProjects(loaded.items);
        }
      } catch {
        // Only used to name a template's project; the list works without it.
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

  const visible = showArchived ? templates : templates.filter((one) => one.active);

  async function run(action: () => Promise<unknown>, successKey: string) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await action();
      setNotice(t(successKey));
      await load();
    } catch (cause) {
      setError(isApiError(cause) ? cause.message : t('templates.actionFailed'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Box>
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 1 }}>
        <Typography variant="h5" component="h1">
          {t('templates.heading')}
        </Typography>
        <Box sx={{ flexGrow: 1 }} />
        <FormControlLabel
          control={
            <Switch
              checked={showArchived}
              onChange={(event) => setShowArchived(event.target.checked)}
            />
          }
          label={t('templates.showArchived')}
        />
        <Button
          variant="contained"
          disabled={!mayAdminister || busy}
          onClick={() => setEditing('new')}
        >
          {t('templates.create')}
        </Button>
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        {t('templates.intro')}
      </Typography>

      {!mayAdminister ? (
        <Alert severity="info" sx={{ mb: 2 }}>
          {t('templates.readOnly')}
        </Alert>
      ) : null}
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

      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>{t('templates.name')}</TableCell>
              <TableCell>{t('templates.terms')}</TableCell>
              <TableCell>{t('templates.project')}</TableCell>
              <TableCell>{t('templates.status')}</TableCell>
              <TableCell sx={{ textAlign: 'end' }}>{t('templates.actions')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {loading ? (
              <TableRow>
                <TableCell colSpan={5} align="center" sx={{ py: 6 }}>
                  <CircularProgress size={28} />
                </TableCell>
              </TableRow>
            ) : visible.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5} align="center" sx={{ py: 6 }}>
                  <Typography color="text.secondary">{t('templates.empty')}</Typography>
                </TableCell>
              </TableRow>
            ) : (
              visible.map((template) => (
                <TableRow key={template.id} hover>
                  <TableCell>
                    {template.name}
                    {template.shorthandLabel ? (
                      <Typography variant="caption" color="text.secondary" display="block">
                        {template.shorthandLabel}
                      </Typography>
                    ) : null}
                  </TableCell>
                  <TableCell>{describeTemplate(template, t)}</TableCell>
                  <TableCell>
                    {template.projectId
                      ? (projectsById.get(template.projectId)?.displayName ??
                        t('templates.oneProject'))
                      : t('templates.everyProject')}
                  </TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      color={template.active ? 'success' : 'default'}
                      label={
                        template.active ? t('templates.active') : t('templates.archived')
                      }
                    />
                  </TableCell>
                  <TableCell sx={{ textAlign: 'end' }}>
                    <Stack direction="row" spacing={1} justifyContent="flex-end">
                      <Button
                        size="small"
                        disabled={!mayAdminister || busy}
                        onClick={() => setEditing(template)}
                      >
                        {t('templates.edit')}
                      </Button>
                      {template.active ? (
                        <Button
                          size="small"
                          color="inherit"
                          disabled={!mayAdminister || busy}
                          onClick={() => {
                            void run(
                              () => dealsApi.archiveTemplate(template.id),
                              'templates.archiveDone',
                            );
                          }}
                        >
                          {t('templates.archive')}
                        </Button>
                      ) : (
                        <Button
                          size="small"
                          disabled={!mayAdminister || busy}
                          onClick={() => {
                            void run(
                              () => dealsApi.restoreTemplate(template.id),
                              'templates.restoreDone',
                            );
                          }}
                        >
                          {t('templates.restore')}
                        </Button>
                      )}
                    </Stack>
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </TableContainer>

      <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
        {t('templates.archiveNote')}
      </Typography>

      <TemplateDialog
        editing={editing}
        onClose={() => setEditing(null)}
        onSaved={() => {
          setEditing(null);
          setNotice(t('templates.saved'));
          void load();
        }}
      />
    </Box>
  );
}
