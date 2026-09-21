import Typography from '@mui/material/Typography';
import Paper from '@mui/material/Paper';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import List from '@mui/material/List';
import ListItem from '@mui/material/ListItem';
import ListItemText from '@mui/material/ListItemText';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import RadioButtonUncheckedIcon from '@mui/icons-material/RadioButtonUnchecked';
import ListItemIcon from '@mui/material/ListItemIcon';
import { useTranslation } from 'react-i18next';

const FOUNDATION_KEYS = [
  'money',
  'schedule',
  'invariant',
  'errors',
  'correlation',
  'migrations',
  'architecture',
] as const;

const NOT_BUILT_KEYS = [
  'leads',
  'inventory',
  'deals',
  'collections',
  'commissions',
  'dashboards',
] as const;

/**
 * Placeholder landing page for Epic 0.
 *
 * It states plainly what exists and what does not, so a reviewer opening the application is
 * not left guessing whether a missing feature is broken or simply not built yet.
 */
export function HomePage() {
  const { t } = useTranslation();

  return (
    <Box sx={{ display: 'grid', gap: 3 }}>
      <Box>
        <Chip label={t('home.epic')} color="primary" size="small" sx={{ mb: 1 }} />
        <Typography variant="h1" gutterBottom>
          {t('home.heading')}
        </Typography>
        <Typography color="text.secondary">{t('home.intro')}</Typography>
      </Box>

      <Box
        sx={{
          display: 'grid',
          gap: 3,
          gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' },
        }}
      >
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="h2" gutterBottom>
            {t('home.foundationTitle')}
          </Typography>
          <List dense disablePadding>
            {FOUNDATION_KEYS.map((key) => (
              <ListItem key={key} disableGutters>
                <ListItemIcon sx={{ minWidth: 36 }}>
                  <CheckCircleOutlineIcon color="success" fontSize="small" />
                </ListItemIcon>
                <ListItemText primary={t(`home.foundation.${key}`)} />
              </ListItem>
            ))}
          </List>
        </Paper>

        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="h2" gutterBottom>
            {t('home.notBuiltTitle')}
          </Typography>
          <List dense disablePadding>
            {NOT_BUILT_KEYS.map((key) => (
              <ListItem key={key} disableGutters>
                <ListItemIcon sx={{ minWidth: 36 }}>
                  <RadioButtonUncheckedIcon color="disabled" fontSize="small" />
                </ListItemIcon>
                <ListItemText
                  primary={t(`home.notBuilt.${key}`)}
                  primaryTypographyProps={{ color: 'text.secondary' }}
                />
              </ListItem>
            ))}
          </List>
        </Paper>
      </Box>
    </Box>
  );
}
