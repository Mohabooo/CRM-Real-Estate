import type { TFunction } from 'i18next';

/**
 * How long is left on a hold, in words.
 *
 * Deliberately coarse. An agent looking at a list of holds needs to know which ones need
 * attention today, not that one expires in 4 hours 12 minutes — and a minute-accurate label
 * in a table that does not re-render is wrong more often than it is right.
 */
export function timeRemaining(expiresAt: string, now: Date, t: TFunction): string {
  const millis = new Date(expiresAt).getTime() - now.getTime();
  if (Number.isNaN(millis)) {
    return '';
  }
  if (millis <= 0) {
    return t('holds.expired');
  }

  const hours = Math.floor(millis / 3_600_000);
  if (hours >= 48) {
    return t('holds.daysLeft', { count: Math.floor(hours / 24) });
  }
  if (hours >= 1) {
    return t('holds.hoursLeft', { count: hours });
  }
  return t('holds.minutesLeft', { count: Math.max(1, Math.floor(millis / 60_000)) });
}

/** A date for display, in the active locale. */
export function formatDate(iso: string | null | undefined, locale: string): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? ''
    : new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

/**
 * A calendar date for display — a due date, a deal date — with no time zone involved.
 *
 * Distinct from {@link formatDate}, and the difference is not cosmetic. An installment's due
 * date is "30 April 2026" and nothing more; doc 22 section 9 is explicit that a calendar
 * date must not silently acquire an instant. `new Date("2026-04-30")` parses as midnight
 * UTC, so west of Greenwich that date renders as the 29th — a payment book a day out from
 * the contract.
 *
 * So the parts are read off the string and handed to Intl as a local date, which is the one
 * construction that cannot drift.
 */
export function formatCalendarDate(iso: string | null | undefined, locale: string): string {
  if (!iso) {
    return '';
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso.trim());
  if (!match) {
    return iso;
  }
  const [, year, month, day] = match;
  const date = new Date(Number(year), Number(month) - 1, Number(day));
  return Number.isNaN(date.getTime())
    ? iso
    : new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(date);
}
