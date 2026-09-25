package com.rescrm.platform.money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An immutable percentage held at a fixed scale of 4, matching the NUMERIC(18,4) rate
 * convention in doc 22, §8.
 *
 * <p>Rates carry more precision than amounts because a rate is an input to a calculation
 * rather than a stored result — pre-rounding a rate to two places would bias every amount
 * derived from it. Rounding happens once, when the percentage is applied to an amount.
 *
 * <p>Applying a percentage rounds half-up, per rule R-DISC-1 and the general convention in
 * doc 17. The one deliberate exception to half-up in the whole system is the base-installment
 * division, which lives on {@link Money#divideFloor(int)}.
 */
public final class Percentage implements Comparable<Percentage>, Serializable {

    private static final long serialVersionUID = 1L;

    /** Scale for rates, matching NUMERIC(18,4). */
    public static final int SCALE = 4;

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final BigDecimal value;

    private Percentage(BigDecimal value) {
        this.value = value;
    }

    /** Creates a percentage from a literal such as {@code "5"} or {@code "2.5"} meaning 5% / 2.5%. */
    public static Percentage of(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return of(new BigDecimal(value.trim()));
    }

    public static Percentage of(BigDecimal value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.scale() > SCALE) {
            BigDecimal stripped = value.stripTrailingZeros();
            if (stripped.scale() > SCALE) {
                throw new IllegalArgumentException(
                        "Percentage " + value.toPlainString() + " has more than " + SCALE + " decimal places");
            }
        }
        BigDecimal scaled = value.setScale(SCALE, RoundingMode.UNNECESSARY);
        if (scaled.signum() < 0) {
            throw new IllegalArgumentException("Percentage must not be negative, was " + scaled.toPlainString());
        }
        return new Percentage(scaled);
    }

    public static Percentage zero() {
        return new Percentage(BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY));
    }

    /**
     * Applies this percentage to an amount, rounding half-up to the currency scale ONCE.
     *
     * <p>Example from the canonical fixture (doc 17, §5): 5% of 3,000,000.00 is 150,000.00.
     *
     * <p>The product and the division are both exact, and that is the point (FIN-016, "no
     * intermediate pre-rounding"). An amount carries two decimals and a rate carries four,
     * so their product carries six and needs no rounding; dividing by a power of ten shifts
     * the point rather than dividing, so the eight-decimal quotient is exact too. Only the
     * last step rounds, and it rounds the true value.
     *
     * <p>This used to round the quotient to six decimals first and then to two, which is
     * double rounding and gives the wrong cent whenever the exact value sits just below a
     * half: 7.3333% of 1,000,000.75 is 73,333.05499975, which is 73,333.05 rounded half-up,
     * but rounding to 73,333.055000 first turned it into 73,333.06. A rate with four
     * decimals is ordinary — a discount negotiated as a third of a percent, a down payment
     * quoted to reach a round total — and the resulting cent lands in the net value, which
     * the whole schedule is then built from. Nothing downstream could catch it: every
     * invariant reconciles happily against a net value that is one cent wrong.
     */
    public Money applyTo(Money base) {
        Objects.requireNonNull(base, "base must not be null");
        return Money.of(base.amount().multiply(value).movePointLeft(2), base.currency(),
                RoundingMode.HALF_UP);
    }

    /** The percentage as written, e.g. {@code 5.0000} for 5%. */
    public BigDecimal value() {
        return value;
    }

    /** The percentage as a fraction, e.g. {@code 0.05} for 5%. */
    public BigDecimal asFraction() {
        return value.divide(ONE_HUNDRED, SCALE + 2, RoundingMode.HALF_UP);
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    /** True when the percentage exceeds 100%. Callers decide whether that is legal in context. */
    public boolean exceedsWhole() {
        return value.compareTo(ONE_HUNDRED) > 0;
    }

    @Override
    public int compareTo(Percentage other) {
        Objects.requireNonNull(other, "other must not be null");
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Percentage)) {
            return false;
        }
        return value.compareTo(((Percentage) other).value) == 0;
    }

    @Override
    public int hashCode() {
        return value.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return value.toPlainString() + "%";
    }
}
