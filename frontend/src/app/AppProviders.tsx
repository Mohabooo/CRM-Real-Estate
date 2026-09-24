import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { CacheProvider } from '@emotion/react';
import { ThemeProvider } from '@mui/material/styles';
import CssBaseline from '@mui/material/CssBaseline';
import { useTranslation } from 'react-i18next';
import {
  applyDocumentDirection,
  directionForLanguage,
  emotionCacheFor,
  type Direction,
} from '@/theme/direction';
import { buildTheme } from '@/theme/theme';
import { persistLanguage, type SupportedLanguage } from '@/i18n';
import { AuthProvider } from '@/features/auth/AuthProvider';
import { LanguageContext } from './LanguageContext';

/**
 * Composition root for cross-cutting providers: direction-aware styling, theme, language and
 * the session.
 *
 * Direction is derived from the active language rather than toggled independently, so the two
 * can never disagree — an Arabic interface laid out left-to-right is a bug that is tedious to
 * find once the two settings have drifted apart.
 */
export function AppProviders({ children }: { children: ReactNode }) {
  const { i18n } = useTranslation();
  const [language, setLanguageState] = useState<SupportedLanguage>(
    (i18n.language as SupportedLanguage) ?? 'en',
  );

  const direction: Direction = useMemo(() => directionForLanguage(language), [language]);
  const theme = useMemo(() => buildTheme(direction), [direction]);
  const cache = useMemo(() => emotionCacheFor(direction), [direction]);

  useEffect(() => {
    applyDocumentDirection(direction, language);
  }, [direction, language]);

  const setLanguage = useCallback(
    (next: SupportedLanguage) => {
      setLanguageState(next);
      persistLanguage(next);
      void i18n.changeLanguage(next);
    },
    [i18n],
  );

  const contextValue = useMemo(
    () => ({ language, direction, setLanguage }),
    [language, direction, setLanguage],
  );

  return (
    <LanguageContext.Provider value={contextValue}>
      <CacheProvider value={cache}>
        <ThemeProvider theme={theme}>
          <CssBaseline />
          <AuthProvider>{children}</AuthProvider>
        </ThemeProvider>
      </CacheProvider>
    </LanguageContext.Provider>
  );
}
