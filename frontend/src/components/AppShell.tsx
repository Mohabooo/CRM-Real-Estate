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
import { useLanguage } from '@/app/LanguageContext';
import type { SupportedLanguage } from '@/i18n';

const NAV_ITEMS = [
  { path: '/', labelKey: 'nav.home' },
  { path: '/system', labelKey: 'nav.system' },
] as const;

/**
 * The application frame: title bar, navigation and the routed content area.
 *
 * Navigation is a placeholder for Epic 0 — the real information architecture follows the
 * role-driven layouts in doc 21 section 4, which need the business modules to exist first.
 */
export function AppShell() {
  const { t } = useTranslation();
  const location = useLocation();
  const { language, setLanguage } = useLanguage();

  const activeTab = NAV_ITEMS.some((item) => item.path === location.pathname)
    ? location.pathname
    : false;

  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      <AppBar position="static" color="primary">
        <Toolbar sx={{ gap: 2 }}>
          <Typography variant="h6" component="h1" sx={{ flexGrow: 1, fontSize: '1.05rem' }}>
            {t('app.title')}
          </Typography>
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
