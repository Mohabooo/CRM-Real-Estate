import createCache from '@emotion/cache';
import type { EmotionCache } from '@emotion/cache';
import { prefixer } from 'stylis';
import rtlPlugin from 'stylis-plugin-rtl';

export type Direction = 'ltr' | 'rtl';

/**
 * Language-to-direction mapping.
 *
 * <p>Arabic is the primary RTL language for this market. The map exists rather than a
 * hard-coded check so adding a language is a one-line change.
 */
const RTL_LANGUAGES = new Set(['ar', 'he', 'fa', 'ur']);

export function directionForLanguage(language: string): Direction {
  const base = language.split('-')[0] ?? language;
  return RTL_LANGUAGES.has(base) ? 'rtl' : 'ltr';
}

/**
 * Emotion caches for each direction.
 *
 * Right-to-left support is not a stylesheet that gets mirrored at the end of a project — it
 * has to be wired into the style pipeline from the start, which is why it is part of Epic 0.
 * The RTL cache runs `stylis-plugin-rtl`, which flips physical properties (margin-left,
 * padding-right, text-align) as styles are generated.
 */
const cacheCache = new Map<Direction, EmotionCache>();

export function emotionCacheFor(direction: Direction): EmotionCache {
  const existing = cacheCache.get(direction);
  if (existing) {
    return existing;
  }
  const cache = createCache({
    key: direction === 'rtl' ? 'mui-rtl' : 'mui',
    stylisPlugins: direction === 'rtl' ? [prefixer, rtlPlugin] : [prefixer],
  });
  cacheCache.set(direction, cache);
  return cache;
}

/** Applies the direction to the document so native elements and scrollbars follow it too. */
export function applyDocumentDirection(direction: Direction, language: string): void {
  if (typeof document === 'undefined') {
    return;
  }
  document.documentElement.setAttribute('dir', direction);
  document.documentElement.setAttribute('lang', language);
}
