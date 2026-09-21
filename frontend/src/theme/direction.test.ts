import { describe, expect, it } from 'vitest';
import { directionForLanguage, emotionCacheFor } from './direction';

describe('direction', () => {
  it('maps Arabic to right-to-left', () => {
    expect(directionForLanguage('ar')).toBe('rtl');
    expect(directionForLanguage('ar-EG')).toBe('rtl');
  });

  it('maps English to left-to-right', () => {
    expect(directionForLanguage('en')).toBe('ltr');
    expect(directionForLanguage('en-GB')).toBe('ltr');
  });

  it('defaults an unknown language to left-to-right', () => {
    expect(directionForLanguage('xx')).toBe('ltr');
  });

  it('uses a distinct emotion cache per direction', () => {
    const ltr = emotionCacheFor('ltr');
    const rtl = emotionCacheFor('rtl');

    expect(ltr.key).toBe('mui');
    expect(rtl.key).toBe('mui-rtl');
    expect(ltr).not.toBe(rtl);
  });

  it('reuses the cache for a direction rather than rebuilding it', () => {
    // A new cache per render would re-inject every style rule on each language toggle.
    expect(emotionCacheFor('rtl')).toBe(emotionCacheFor('rtl'));
  });
});
