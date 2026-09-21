import { createTheme, type Theme } from '@mui/material/styles';
import type { Direction } from './direction';

/**
 * The application theme.
 *
 * Intentionally restrained for Epic 0: this establishes typography, spacing and direction
 * handling, not a visual identity. A design system is a later concern and deciding one now
 * would be inventing a requirement.
 */
export function buildTheme(direction: Direction): Theme {
  return createTheme({
    direction,
    palette: {
      mode: 'light',
      primary: { main: '#1f4e79' },
      secondary: { main: '#5b6770' },
      background: { default: '#f7f8fa' },
    },
    typography: {
      // Arabic glyphs need a font stack that actually contains them; falling back to a
      // Latin-only stack renders tofu boxes for half the target market.
      fontFamily: [
        'system-ui',
        '-apple-system',
        'Segoe UI',
        'Roboto',
        'Noto Sans Arabic',
        'Arial',
        'sans-serif',
      ].join(','),
      h1: { fontSize: '1.75rem', fontWeight: 600 },
      h2: { fontSize: '1.375rem', fontWeight: 600 },
    },
    shape: { borderRadius: 8 },
    components: {
      MuiButton: {
        defaultProps: { disableElevation: true },
      },
    },
  });
}
