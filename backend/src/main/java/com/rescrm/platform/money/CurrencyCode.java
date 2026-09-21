package com.rescrm.platform.money;

/**
 * Currencies supported by the platform.
 *
 * <p>MVP supports EGP only (doc 17, non-functional rule N9). The type exists so that
 * multi-currency (Future phase) is a matter of adding constants and relaxing the database
 * CHECK constraint (doc 22, constraint C8), not of reworking every money-bearing signature.
 *
 * <p>Deliberately not {@code java.util.Currency}: that type is locale-driven, permits any
 * ISO code at runtime, and would let an unsupported currency enter the domain silently.
 */
public enum CurrencyCode {

    /** Egyptian Pound — the only currency supported in MVP. */
    EGP("EGP", 2);

    private final String isoCode;
    private final int minorUnitDigits;

    CurrencyCode(String isoCode, int minorUnitDigits) {
        this.isoCode = isoCode;
        this.minorUnitDigits = minorUnitDigits;
    }

    public String isoCode() {
        return isoCode;
    }

    /** Number of decimal places this currency is stored and displayed with. */
    public int minorUnitDigits() {
        return minorUnitDigits;
    }

    public static CurrencyCode fromIsoCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Currency code must not be null");
        }
        for (CurrencyCode candidate : values()) {
            if (candidate.isoCode.equalsIgnoreCase(code)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unsupported currency code: " + code);
    }

    @Override
    public String toString() {
        return isoCode;
    }
}
