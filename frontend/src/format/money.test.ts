import { describe, expect, it } from 'vitest';
import { formatAmount, isValidAmount } from './money';

describe('formatAmount', () => {
  it('keeps every digit of an amount too large for a double', () => {
    // 2^53 is where a JavaScript number stops being able to count. An amount beyond it must
    // still format digit for digit, which is only possible because nothing here parses.
    const huge = '9007199254740993.99';

    expect(formatAmount(huge, 'en')).toBe('9,007,199,254,740,993.99');
  });

  it('does not round a value a float would round', () => {
    // Number("2500000.10") is 2500000.1, and toFixed(2) hides that by luck rather than
    // design. The string is carried through instead.
    expect(formatAmount('2500000.10', 'en')).toBe('2,500,000.10');
  });

  it('pads and truncates to exactly two places', () => {
    expect(formatAmount('1000', 'en')).toBe('1,000.00');
    expect(formatAmount('1000.5', 'en')).toBe('1,000.50');
    expect(formatAmount('1000.499', 'en')).toBe('1,000.49');
  });

  it('groups and separates the way the active language does', () => {
    const english = formatAmount('1234567.89', 'en');
    const arabic = formatAmount('1234567.89', 'ar-EG');

    expect(english).toBe('1,234,567.89');
    expect(arabic).not.toBe('');
    // Arabic-Egyptian uses its own digits and separators; the point is that the formatter
    // asks Intl rather than assuming a comma and a dot.
    expect(arabic).not.toBe(english);
  });

  it('handles a negative amount', () => {
    expect(formatAmount('-450.5', 'en')).toBe('-450.50');
  });

  it('renders nothing for a missing amount, so callers need not test three times', () => {
    expect(formatAmount(null, 'en')).toBe('');
    expect(formatAmount(undefined, 'en')).toBe('');
    expect(formatAmount('   ', 'en')).toBe('');
  });

  it('shows a malformed amount as it arrived rather than inventing a number', () => {
    expect(formatAmount('not-a-number', 'en')).toBe('not-a-number');
    expect(formatAmount('1.2.3', 'en')).toBe('1.2.3');
  });
});

describe('isValidAmount', () => {
  it('accepts what the API accepts', () => {
    expect(isValidAmount('0')).toBe(true);
    expect(isValidAmount('2500000')).toBe(true);
    expect(isValidAmount('2500000.5')).toBe(true);
    expect(isValidAmount(' 2500000.00 ')).toBe(true);
  });

  it('refuses what the API would reject', () => {
    expect(isValidAmount('')).toBe(false);
    expect(isValidAmount('1.234')).toBe(false);
    expect(isValidAmount('1,000')).toBe(false);
    expect(isValidAmount('-1')).toBe(false);
    expect(isValidAmount('1e6')).toBe(false);
  });
});
