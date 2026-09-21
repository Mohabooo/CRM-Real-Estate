package com.rescrm.crm.domain;

import java.util.Locale;
import java.util.Set;

/**
 * Normalises Egyptian phone numbers to one canonical form (doc 20, E2-S1).
 *
 * <p>The problem this solves is that the same person is entered six different ways —
 * {@code 0100 123 4567}, {@code 01001234567}, {@code +20 100 123 4567}, {@code 00201001234567},
 * {@code 201001234567}, sometimes without the leading zero — and duplicate detection is
 * worthless unless all six collapse to one string. The canonical form is E.164:
 * {@code +201001234567}.
 *
 * <p>An unrecognised number is normalised rather than rejected. Landlines, business numbers
 * and foreign buyers are all legitimate, and a validator that blocks them would push agents
 * into typing the number into the name field — which is worse than an imperfect match key.
 * {@link #isEgyptianMobile(String)} says whether the canonical form is one this class
 * actually understood.
 *
 * <p>Pure and framework-free, so every rule below is unit-testable with no infrastructure.
 */
public final class PhoneNumbers {

    /** Egypt's country calling code. */
    private static final String EGYPT = "20";

    /**
     * The mobile network prefixes in use: Vodafone, Etisalat, Orange and WE.
     *
     * <p>Held as data rather than a regex so that a new prefix is a one-line change with an
     * obvious meaning. Stored without the national leading zero.
     */
    private static final Set<String> MOBILE_PREFIXES = Set.of("10", "11", "12", "15");

    /** An Egyptian mobile is 1X plus eight digits, in national form. */
    private static final int NATIONAL_MOBILE_LENGTH = 10;

    private PhoneNumbers() {
    }

    /**
     * The canonical form of a number, for storage and matching.
     *
     * @throws IllegalArgumentException when there is nothing usable to normalise
     */
    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("phone must not be blank");
        }

        String trimmed = input.trim();
        boolean explicitlyInternational = trimmed.startsWith("+");
        String digits = digitsOnly(trimmed);

        if (digits.isEmpty()) {
            throw new IllegalArgumentException("phone contains no digits: '" + input + "'");
        }

        // 00 is the other way of writing +, used throughout the region.
        if (!explicitlyInternational && digits.startsWith("00")) {
            digits = digits.substring(2);
            explicitlyInternational = true;
        }

        String national = toEgyptianNational(digits, explicitlyInternational);
        if (national != null) {
            return "+" + EGYPT + national;
        }

        // Not recognised as Egyptian. Keep it, marked as international if it said so, rather
        // than refuse a number that may be perfectly real.
        return explicitlyInternational ? "+" + digits : digits;
    }

    /**
     * The Egyptian national part ({@code 1001234567}), or null if this is not an Egyptian
     * mobile in any of the spellings above.
     */
    private static String toEgyptianNational(String digits, boolean international) {
        // +20 1001234567  /  00 20 1001234567
        if (digits.startsWith(EGYPT) && isNationalMobile(digits.substring(EGYPT.length()))) {
            return digits.substring(EGYPT.length());
        }

        // A number that announced itself as international and is not Egyptian stays foreign;
        // treating +44... as a local number would be worse than leaving it alone.
        if (international) {
            return null;
        }

        // 01001234567 — the national form with its trunk prefix.
        if (digits.startsWith("0") && isNationalMobile(digits.substring(1))) {
            return digits.substring(1);
        }

        // 1001234567 — the leading zero omitted, which people do constantly.
        if (isNationalMobile(digits)) {
            return digits;
        }

        return null;
    }

    private static boolean isNationalMobile(String candidate) {
        return candidate.length() == NATIONAL_MOBILE_LENGTH
                && candidate.startsWith("1")
                && MOBILE_PREFIXES.contains(candidate.substring(0, 2));
    }

    /** Whether a canonical value is a number this class recognised as an Egyptian mobile. */
    public static boolean isEgyptianMobile(String canonical) {
        if (canonical == null || !canonical.startsWith("+" + EGYPT)) {
            return false;
        }
        return isNationalMobile(canonical.substring(1 + EGYPT.length()));
    }

    /**
     * Normalises without throwing, for matching against data that is already stored.
     *
     * @return the canonical form, or null when the input cannot be normalised at all
     */
    public static String normalizeOrNull(String input) {
        try {
            return normalize(input);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String digitsOnly(String value) {
        StringBuilder digits = new StringBuilder(value.length());
        for (char character : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if (character >= '0' && character <= '9') {
                digits.append(character);
            }
        }
        return digits.toString();
    }
}
