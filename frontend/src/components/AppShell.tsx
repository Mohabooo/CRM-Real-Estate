import AppBar from '@mui/material/AppBar';
import Toolbar from '@mui/material/Toolbar';
import Typography from '@mui/material/Typography';
import Box from '@mui/material/Box';
import Container from '@mui/material/Container';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import { Link as RouterLink, Outlet, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import { useNavigate } from 'react-router-dom';
import { useLanguage } from '@/app/LanguageContext';
import { useAuth } from '@/features/auth/AuthContext';
import type { SupportedLanguage } from '@/i18n';

const NAV_ITEMS = [
  { path: '/inventory', labelKey: 'nav.inventory' },
  { path: '/holds', labelKey: 'nav.holds' },
  { path: '/deals', labelKey: 'nav.deals' },
  { path: '/payment-plans', labelKey: 'nav.paymentPlans' },
  { path: '/system', labelKey: 'nav.system' },
] as const;

/**
 * The application frame: title bar, navigation and the routed content area.
 *
 * Navigation covers the first vertical slice: browse inventory, and the holds placed from
 * it. The real information architecture still follows doc 21 section 4's role-driven
 * layouts, which need more of the business modules than exist yet.
 *
 * The header names the company and the signed-in role. Both matter in a multi-tenant system
 * where the same person may hold an account in two companies: without it, the only way to
 * tell which one you are looking at is to recognise the data.
 */
export function AppShell() {
  const { t } = useTranslation();
  const location = useLocation();
  const { language, setLanguage } = useLanguage();
  const { me, tenant, signOut } = useAuth();
  const navigate = useNavigate();

  // A deal's own page keeps the Deals tab lit. Matching the exact path would leave no tab
  // selected there, which reads as having navigated out of the application.
  const activeTab =
    NAV_ITEMS.find(
      (item) =>
        item.path === location.pathname || location.pathname.startsWith(`${item.path}/`),
    )?.path ?? false;

  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      <AppBar position="static" color="primary">
        <Toolbar sx={{ gap: 2 }}>
          <Typography variant="h6" component="h1" sx={{ fontSize: '1.05rem' }}>
            {tenant?.name ?? t('app.title')}
          </Typography>
          {me ? (
            <Chip
              size="small"
              label={t(`role.${me.role}`)}
              sx={{ bgcolor: 'rgba(255,255,255,0.16)', color: 'common.white' }}
            />
          ) : null}
          <Box sx={{ flexGrow: 1 }} />
          <ToggleButtonGroup
            size="small"
            exclusive
            value={language}
            aria-label={t('language.label')}
            onChange={(_event, next: SupportedLanguage | null) => {
              if (next) {
                setLanguage(next);
              }
            }}
            sx={{
              bgcolor: 'rgba(255,255,255,0.12)',
              '& .MuiToggleButton-root': { color: 'common.white', border: 'none', px: 1.5 },
              '& .Mui-selected': { bgcolor: 'rgba(255,255,255,0.24) !important' },
            }}
          >
            <ToggleButton value="en">{t('language.english')}</ToggleButton>
            <ToggleButton value="ar">{t('language.arabic')}</ToggleButton>
          </ToggleButtonGroup>
          <Button
            color="inherit"
            size="small"
            onClick={() => {
              void signOut().then(() => navigate('/login', { replace: true }));
            }}
          >
            {t('nav.signOut')}
          </Button>
        </Toolbar>
        <Tabs
          value={activeTab}
          textColor="inherit"
          indicatorColor="secondary"
          sx={{ px: 2, bgcolor: 'primary.dark' }}
        >
          {NAV_ITEMS.map((item) => (
            <Tab
              key={item.path}
              value={item.path}
              label={t(item.labelKey)}
              component={RouterLink}
              to={item.path}
            />
          ))}
        </Tabs>
      </AppBar>

      <Container component="main" maxWidth="lg" sx={{ py: 4, flexGrow: 1 }}>
        <Outlet />
      </Container>
    </Box>
  );
}
