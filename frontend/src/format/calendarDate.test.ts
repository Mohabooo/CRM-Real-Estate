import { describe, expect, it } from 'vitest';
import { formatCalendarDate, formatDate } from './time';

/**
 * A due date is a calendar date (doc 22 section 9) and must not acquire a time zone on the
 * way to the screen.
 */
describe('formatCalendarDate', () => {
  it('renders the day that was written, not the day a UTC instant lands on', () => {
    expect(formatCalendarDate('2026-04-30', 'en')).toBe('Apr 30, 2026');
    expect(formatCalendarDate('2026-01-01', 'en')).toBe('Jan 1, 2026');
    expect(formatCalendarDate('2026-12-31', 'en')).toBe('Dec 31, 2026');
  });

  it('keeps month-end dates intact across a leap February', () => {
    expect(formatCalendarDate('2028-02-29', 'en')).toBe('Feb 29, 2028');
  });

  it('shows nothing for a missing date rather than an invalid one', () => {
    expect(formatCalendarDate(null, 'en')).toBe('');
    expect(formatCalendarDate(undefined, 'en')).toBe('');
    expect(formatCalendarDate('', 'en')).toBe('');
  });

  it('returns an unrecognised value unchanged instead of inventing a date', () => {
    expect(formatCalendarDate('not a date', 'en')).toBe('not a date');
  });

  it('is not the same function as formatDate, which carries a time', () => {
    // formatDate is right for an instant — when a hold expires, when a session started —
    // and wrong for a due date. Asserted so the two do not quietly converge.
    expect(formatDate('2026-04-30T00:00:00Z', 'en')).toContain('2026');
    expect(formatCalendarDate('2026-04-30', 'en')).not.toMatch(/\d:\d/);
  });
});
