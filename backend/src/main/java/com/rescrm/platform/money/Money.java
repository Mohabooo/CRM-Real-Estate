package com.rescrm.platform.money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An immutable, currency-aware monetary amount held at a fixed scale of 2.
 *
 * <p>This is the only money representation permitted in domain signatures. Raw
 * {@link BigDecimal} is banned from domain APIs by an architecture test
 * ({@code ArchitectureTest.money_type_is_used_instead_of_raw_bigdecimal}) so that the
 * rounding policy in doc 17 cannot be bypassed by accident, and {@code double}/{@code float}
 * are banned outright from financial packages.
 *
 * <h2>Rounding policy (doc 17 conventions, as patched at the implementation gate)</h2>
 * Half-up at every point <em>except</em> the base-installment division, which floors
 * deliberately so the rounding remainder is always non-negative and lands on the final
 * installment (rules R-INST-1 and R-INST-2). {@link #divideFloor(int)} exists for that one
 * case and is used by {@code MoneySplitter}; every other operation here is half-up.
 *
 * <h2>Equality</h2>
 * Because the scale is normalised to 2 on construction, {@link #equals(Object)} is both
 * value-correct and consistent with {@link #compareTo(Money)}. {@code BigDecimal}'s own
 * scale-sensitive equality — where {@code 1.5} does not equal {@code 1.50} — is a classic
 * source of defects and does not leak through this type.
 */
public final class Money implements Comparable<Money>, Serializable {

    private static final long serialVersionUID = 1L;

    /** Scale for all stored amounts, matching the NUMERIC(18,2) convention in doc 22, §8. */
    public static final int SCALE = 2;

    /** Precision guard matching NUMERIC(18,2): at most 16 digits before the decimal point. */
    private static final BigDecimal MAX_MAGNITUDE = new BigDecimal("9999999999999999.99");

    private final BigDecimal amount;
    private final CurrencyCode currency;

    private Money(BigDecimal amount, CurrencyCode currency) {
        this.amount = amount;
        this.currency = currency;
    }

    // ---------------------------------------------------------------- factories

    /**
     * Creates an amount from a decimal string such as {@code "2850000.00"}.
     *
     * <p>Rejects inputs with more than two decimal places rather than silently rounding
     * them: a caller supplying more precision than the currency has is expressing an
     * intent the type cannot honour, and guessing which way to round it is how cents
     * disappear. Use {@link #of(BigDecimal, RoundingMode)} when rounding is intended.
     */
    public static Money of(String amount, CurrencyCode currency) {
        Objects.requireNonNull(amount, "amount must not be null");
        return of(new BigDecimal(amount.trim()), currency);
    }

    /** Creates an amount, rejecting any value that carries more precision than the scale. */
    public static Money of(BigDecimal amount, CurrencyCode currency) {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (amount.scale() > SCALE) {
            BigDecimal stripped = amount.stripTrailingZeros();
            if (stripped.scale() > SCALE) {
                throw new IllegalArgumentException(
                        "Amount " + amount.toPlainString() + " has more than " + SCALE
                                + " decimal places; use of(BigDecimal, RoundingMode) to round explicitly");
            }
        }
        return create(amount.setScale(SCALE, RoundingMode.UNNECESSARY), currency);
    }

    /** Creates an amount, rounding the input with the supplied mode. */
    public static Money of(BigDecimal amount, CurrencyCode currency, RoundingMode rounding) {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(rounding, "rounding must not be null");
        return create(amount.setScale(SCALE, rounding), currency);
    }

    /** Creates an amount from a whole number of major units (e.g. {@code 3_000_000} EGP). */
    public static Money ofMajor(long majorUnits, CurrencyCode currency) {
        return create(BigDecimal.valueOf(majorUnits).setScale(SCALE, RoundingMode.UNNECESSARY), currency);
    }

    public static Money zero(CurrencyCode currency) {
        Objects.requireNonNull(currency, "currency must not be null");
        return create(BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY), currency);
    }

    private static Money create(BigDecimal scaled, CurrencyCode currency) {
        if (scaled.abs().compareTo(MAX_MAGNITUDE) > 0) {
            throw new IllegalArgumentException(
                    "Amount " + scaled.toPlainString() + " exceeds the supported NUMERIC(18,2) range");
        }
        return new Money(scaled, currency);
    }

    // ---------------------------------------------------------------- accessors

    /**
     * The underlying amount, always at scale 2.
     *
     * <p>Intended for persistence and serialization adapters only. Domain code uses the
     * arithmetic methods on this type; the architecture test forbids exposing BigDecimal
     * from domain APIs.
     */
    public BigDecimal amount() {
        return amount;
    }

    public CurrencyCode currency() {
        return currency;
    }

    // ---------------------------------------------------------------- arithmetic

    public Money plus(Money other) {
        requireSameCurrency(other);
        return create(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return create(amount.subtract(other.amount), currency);
    }

    /** Multiplies by a whole factor; exact, no rounding required. */
    public Money times(int factor) {
        return create(amount.multiply(BigDecimal.valueOf(factor)), currency);
    }

    /** Multiplies by an arbitrary factor, rounding half-up to the currency scale. */
    public Money times(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor must not be null");
        return create(amount.multiply(factor).setScale(SCALE, RoundingMode.HALF_UP), currency);
    }

    /**
     * Divides into {@code parts}, rounding <em>down</em> to the currency scale.
     *
     * <p>This is rule R-INST-1 and exists specifically so the remainder left over is
     * non-negative (R-INST-2). It is not a general-purpose division: callers that want a
     * conserving split should use {@code MoneySplitter}, which returns the remainder too.
     */
    public Money divideFloor(int parts) {
        if (parts <= 0) {
            throw new IllegalArgumentException("parts must be greater than zero, was " + parts);
        }
        return create(amount.divide(BigDecimal.valueOf(parts), SCALE, RoundingMode.FLOOR), currency);
    }

    public Money negated() {
        return create(amount.negate(), currency);
    }

    public Money abs() {
        return create(amount.abs(), currency);
    }

    // ---------------------------------------------------------------- predicates

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) >= 0;
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) < 0;
    }

    public boolean isLessThanOrEqualTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) <= 0;
    }

    /** Sums a sequence of amounts; all must share a currency. */
    public static Money sum(CurrencyCode currency, Iterable<Money> amounts) {
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(amounts, "amounts must not be null");
        Money total = zero(currency);
        for (Money each : amounts) {
            total = total.plus(each);
        }
        return total;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other amount must not be null");
        if (currency != other.currency) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    // ---------------------------------------------------------------- identity

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Money)) {
            return false;
        }
        Money that = (Money) other;
        return currency == that.currency && amount.compareTo(that.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(currency, amount.stripTrailingZeros());
    }

    /**
     * The plain decimal representation, e.g. {@code "2850000.00"}.
     *
     * <p>This is the wire format required by doc 23: money crosses the API as a JSON
     * string, never as a JSON number, so no consumer can parse it into a float.
     */
    public String toPlainString() {
        return amount.toPlainString();
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.isoCode();
    }
}
