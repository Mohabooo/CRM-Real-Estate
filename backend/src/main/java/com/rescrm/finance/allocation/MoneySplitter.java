package com.rescrm.finance.allocation;

import com.rescrm.platform.money.Money;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Splits a monetary amount into equal parts without losing or inventing a cent.
 *
 * <p>This is the arithmetic underneath rules R-INST-1 and R-INST-2 (doc 17), extracted from
 * the payment-plan domain deliberately: it knows nothing about installments, plans or deals.
 * Epic 4's schedule generator will call it; Epic 0 ships and proves the arithmetic.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>{@code base = floor(total / parts)} to the currency scale — floor, not half-up, so the
 *       remainder is always non-negative.</li>
 *   <li>{@code remainder = total - (base * parts)}.</li>
 *   <li>The remainder is added to the <em>last</em> part.</li>
 * </ol>
 *
 * <h2>The invariant</h2>
 * {@code sum(split(total, n)) == total}, exactly, for every valid input. This is the
 * conservation half of the schedule invariant R-PLAN-4 and is verified by a property-based
 * test over the full input space (doc 29, FIN-018), not merely by examples.
 *
 * <p>Worked example from the canonical fixture: 2,422,500.00 split 32 ways gives 31 parts of
 * 75,703.12 and a final part of 75,703.28, because the remainder of 0.16 lands on the last.
 */
public final class MoneySplitter {

    private MoneySplitter() {
    }

    /**
     * Splits {@code total} into {@code parts} amounts that sum exactly back to {@code total}.
     *
     * @throws IllegalArgumentException if {@code parts} is not positive, or {@code total} is negative
     */
    public static List<Money> split(Money total, int parts) {
        Objects.requireNonNull(total, "total must not be null");
        if (parts <= 0) {
            throw new IllegalArgumentException("parts must be greater than zero, was " + parts);
        }
        if (total.isNegative()) {
            throw new IllegalArgumentException("Cannot split a negative amount: " + total);
        }

        Money base = total.divideFloor(parts);
        Money remainder = total.minus(base.times(parts));

        List<Money> result = new ArrayList<>(parts);
        for (int i = 0; i < parts - 1; i++) {
            result.add(base);
        }
        result.add(base.plus(remainder));
        return Collections.unmodifiableList(result);
    }

    /**
     * The base part produced by {@link #split(Money, int)} — the amount every part except the
     * last carries. Exposed so a quoting screen can show "X per quarter" without materialising
     * the whole schedule.
     */
    public static Money baseAmount(Money total, int parts) {
        Objects.requireNonNull(total, "total must not be null");
        if (parts <= 0) {
            throw new IllegalArgumentException("parts must be greater than zero, was " + parts);
        }
        return total.divideFloor(parts);
    }

    /**
     * The rounding remainder that {@link #split(Money, int)} adds to the final part.
     * Always non-negative, which is the reason step 1 floors rather than rounding half-up.
     */
    public static Money remainder(Money total, int parts) {
        Money base = baseAmount(total, parts);
        return total.minus(base.times(parts));
    }
}
