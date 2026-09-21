import { createContext, useContext } from 'react';
import type { Direction } from '@/theme/direction';
import type { SupportedLanguage } from '@/i18n';

export interface LanguageContextValue {
  language: SupportedLanguage;
  direction: Direction;
  setLanguage: (language: SupportedLanguage) => void;
}

export const LanguageContext = createContext<LanguageContextValue | undefined>(undefined);

export function useLanguage(): LanguageContextValue {
  const context = useContext(LanguageContext);
  if (!context) {
    throw new Error('useLanguage must be used inside AppProviders');
  }
  return context;
}
