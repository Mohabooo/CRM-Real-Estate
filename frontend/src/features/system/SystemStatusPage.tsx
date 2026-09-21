import { useCallback, useEffect, useState } from 'react';
import Typography from '@mui/material/Typography';
import Paper from '@mui/material/Paper';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Alert from '@mui/material/Alert';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import { useTranslation } from 'react-i18next';
import { platformApi, type HealthResponse, type PlatformInfo } from '@/api/platform';
import { isApiError } from '@/api/errors';

type LoadState =
  | { kind: 'loading' }
  | { kind: 'loaded'; health: HealthResponse; info: PlatformInfo }
  | { kind: 'error'; message: string; correlationId?: string };

/**
 * Exercises the API client against the real backend.
 *
 * Its purpose in Epic 0 is to prove the whole path works end to end — dev proxy, correlation
 * header, error normalisation — rather than to be a feature. A page that only ever succeeds
 * would not prove the error path, so the failure case is rendered deliberately.
 */
export function SystemStatusPage() {
  const { t } = useTranslation();
  const [state, setState] = useState<LoadState>({ kind: 'loading' });

  const load = useCallback(async () => {
    setState({ kind: 'loading' });
    try {
      const [health, info] = await Promise.all([platformApi.health(), platformApi.info()]);
      setState({ kind: 'loaded', health, info });
    } catch (error) {
      const message = isApiError(error) && error.isNetworkError
        ? t('system.unreachable')
        : error instanceof Error
          ? error.message
          : t('error.unexpected');
      setState({
        kind: 'error',
        message,
        ...(isApiError(error) && error.correlationId
          ? { correlationId: error.correlationId }
          : {}),
      });
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <Box sx={{ display: 'grid', gap: 3 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
        <Typography variant="h1" sx={{ flexGrow: 1 }}>
          {t('system.heading')}
        </Typography>
        <Button variant="outlined" onClick={() => void load()}>
          {t('system.refresh')}
        </Button>
      </Box>

      {state.kind === 'loading' ? (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
          <CircularProgress size={20} />
          <Typography>{t('system.checking')}</Typography>
        </Box>
      ) : null}

      {state.kind === 'error' ? (
        <Alert severity="warning">
          {state.message}
          {state.correlationId ? (
            <Box component="span" sx={{ display: 'block', mt: 1, fontFamily: 'monospace' }}>
              {t('error.correlationId')}: {state.correlationId}
            </Box>
          ) : null}
        </Alert>
      ) : null}

      {state.kind === 'loaded' ? (
        <Box sx={{ display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="h2" gutterBottom>
              {t('system.backendHealth')}
            </Typography>
            <Chip
              label={state.health.status}
              color={state.health.status === 'UP' ? 'success' : 'error'}
              size="small"
            />
          </Paper>

          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="h2" gutterBottom>
              {t('system.platformInfo')}
            </Typography>
            <Typography variant="body2">
              {t('system.application')}: {state.info.application}
            </Typography>
            <Typography variant="body2">
              {t('system.version')}: {state.info.version}
            </Typography>
            <Typography variant="body2">
              {t('system.epic')}: {state.info.epic}
            </Typography>
          </Paper>
        </Box>
      ) : null}
    </Box>
  );
}
