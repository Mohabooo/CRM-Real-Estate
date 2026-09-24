/**
 * Formatting for amounts that arrive from the API as decimal strings.
 *
 * The one rule that matters: an amount is never passed through `Number`. Doc 22 section 8
 * sends money as a string precisely so a client cannot turn "2500000.10" into a double, and
 * a formatter that parses it first would undo that in the last ten metres.
 *
 * So the string is split into its integer and fraction parts, the integer part is grouped as
 * a `BigInt` — exact at any magnitude — and the fraction is re-attached verbatim.
 */

const GROUPED = new Map<string, Intl.NumberFormat>();

function grouping(locale: string): Intl.NumberFormat {
  let formatter = GROUPED.get(locale);
  if (!formatter) {
    formatter = new Intl.NumberFormat(locale);
    GROUPED.set(locale, formatter);
  }
  return formatter;
}

/** The locale's decimal separator, asked of Intl rather than assumed to be a dot. */
function decimalSeparator(locale: string): string {
  const parts = grouping(locale).formatToParts(1.1);
  return parts.find((part) => part.type === 'decimal')?.value ?? '.';
}

/**
 * Formats a decimal string for display, keeping exactly two fraction digits.
 *
 * Returns an empty string for a missing amount, so a caller can render "—" without first
 * testing for null in three places.
 */
export function formatAmount(amount: string | null | undefined, locale: string): string {
  if (amount === null || amount === undefined || amount.trim() === '') {
    return '';
  }

  const trimmed = amount.trim();
  const negative = trimmed.startsWith('-');
  const unsigned = negative ? trimmed.slice(1) : trimmed;

  // Split into at most two parts and check there were no more. Destructuring the first two
  // would render "1.2.3" as "1.20", quietly discarding the rest — which is the opposite of
  // what the fallback below is for.
  const parts = unsigned.split('.');
  const [whole = '0', fraction = ''] = parts;
  if (parts.length > 2 || !/^\d+$/.test(whole) || (fraction !== '' && !/^\d+$/.test(fraction))) {
    // Not a decimal string. Show it as it arrived rather than inventing a number: a
    // malformed amount is a bug to notice, not one to round away.
    return trimmed;
  }

  const grouped = grouping(locale).format(BigInt(whole));
  const twoPlaces = (fraction + '00').slice(0, 2);
  return `${negative ? '-' : ''}${grouped}${decimalSeparator(locale)}${twoPlaces}`;
}

/** Whether a user-entered amount is something the API will accept as a decimal string. */
export function isValidAmount(input: string): boolean {
  return /^\d+(\.\d{1,2})?$/.test(input.trim());
}
