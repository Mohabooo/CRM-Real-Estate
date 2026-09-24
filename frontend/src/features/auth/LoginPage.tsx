import { useState, type FormEvent } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import CircularProgress from '@mui/material/CircularProgress';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useTranslation } from 'react-i18next';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { isApiError } from '@/api/errors';
import { useAuth } from './AuthContext';

interface From {
  from?: string;
}

/**
 * The sign-in form.
 *
 * Three fields, because an address and a password do not identify an account: `users.email`
 * is unique per tenant rather than globally (C9), so the same person may hold accounts in
 * two companies. The company key is what tells them apart.
 *
 * The error shown is whatever the server said, and the server says the same thing for a
 * wrong password, an unknown address and an unknown company — deliberately, so this form
 * cannot be used to find out which companies exist or who works there. Nothing here tries to
 * be more helpful than that.
 */
export function LoginPage() {
  const { t } = useTranslation();
  const { status, signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [company, setCompany] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (status === 'signedIn') {
    const from = (location.state as From | null)?.from;
    return <Navigate to={from ?? '/inventory'} replace />;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await signIn({ company: company.trim(), email: email.trim(), password });
      const from = (location.state as From | null)?.from;
      navigate(from ?? '/inventory', { replace: true });
    } catch (cause) {
      setError(
        isApiError(cause) && cause.isNetworkError
          ? t('login.unreachable')
          : isApiError(cause)
            ? cause.message
            : t('login.failed'),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Box sx={{ display: 'flex', justifyContent: 'center', pt: { xs: 4, sm: 8 }, px: 2 }}>
      <Card sx={{ width: '100%', maxWidth: 420 }}>
        <CardContent sx={{ p: { xs: 3, sm: 4 } }}>
          <Typography variant="h5" component="h1" gutterBottom>
            {t('login.heading')}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
            {t('login.intro')}
          </Typography>

          <Box component="form" onSubmit={submit} noValidate>
            <Stack spacing={2}>
              {error ? <Alert severity="error">{error}</Alert> : null}

              <TextField
                label={t('login.company')}
                helperText={t('login.companyHint')}
                value={company}
                onChange={(event) => setCompany(event.target.value)}
                autoComplete="organization"
                autoFocus
                required
                fullWidth
              />
              <TextField
                label={t('login.email')}
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                autoComplete="username"
                required
                fullWidth
              />
              <TextField
                label={t('login.password')}
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoComplete="current-password"
                required
                fullWidth
              />

              <Button
                type="submit"
                variant="contained"
                size="large"
                disabled={submitting || !company.trim() || !email.trim() || !password}
                startIcon={submitting ? <CircularProgress size={16} color="inherit" /> : undefined}
              >
                {submitting ? t('login.signingIn') : t('login.signIn')}
              </Button>
            </Stack>
          </Box>
        </CardContent>
      </Card>
    </Box>
  );
}
