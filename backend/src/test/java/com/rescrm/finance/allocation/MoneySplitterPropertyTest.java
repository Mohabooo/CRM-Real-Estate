package com.rescrm.finance.allocation;

import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.Percentage;
import com.rescrm.finance.schedule.ScheduleInvariant;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Assume;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-018 — the schedule conservation property (doc 29, section 2).
 *
 * <p>Release gate 2 of nine. Doc 28 decision A21 requires these properties to be written
 * before the generator they constrain, which is why they live in Epic 0 against
 * {@link MoneySplitter} rather than waiting for Epic 4's schedule generator.
 *
 * <p>Property-based rather than example-based because the input space — every combination of
 * net value, down payment, delivery payment, installment count and frequency — cannot be
 * covered by examples. A single unconsidered combination that loses a cent is exactly the
 * defect this suite exists to prevent.
 */
class MoneySplitterPropertyTest {

    private static final CurrencyCode EGP = CurrencyCode.EGP;

    @Property(tries = 2000)
    void split_always_conserves_the_total(
            @ForAll @LongRange(min = 1, max = 100_000_000_00L) long totalCents,
            @ForAll @IntRange(min = 1, max = 400) int parts) {

        Money total = cents(totalCents);
        List<Money> split = MoneySplitter.split(total, parts);

        assertThat(split).hasSize(parts);
        assertThat(Money.sum(EGP, split))
                .as("sum of %d parts of %s", parts, total)
                .isEqualTo(total);
    }

    @Property(tries = 2000)
    void remainder_is_never_negative(
            @ForAll @LongRange(min = 1, max = 100_000_000_00L) long totalCents,
            @ForAll @IntRange(min = 1, max = 400) int parts) {

        // The consequence of flooring the base amount rather than rounding half-up
        // (rules R-INST-1 and R-INST-2).
        assertThat(MoneySplitter.remainder(cents(totalCents), parts).isNegative()).isFalse();
    }

    @Property(tries = 2000)
    void remainder_is_smaller_than_the_part_count(
            @ForAll @LongRange(min = 1, max = 100_000_000_00L) long totalCents,
            @ForAll @IntRange(min = 1, max = 400) int parts) {

        // A remainder of at most (parts - 1) cents follows from flooring; anything larger
        // would mean the base amount was computed wrongly.
        Money remainder = MoneySplitter.remainder(cents(totalCents), parts);
        BigDecimal maximumCents = BigDecimal.valueOf(parts - 1L).movePointLeft(2);
        assertThat(remainder.amount()).isLessThanOrEqualTo(maximumCents);
    }

    @Property(tries = 2000)
    void every_part_except_the_last_is_identical(
            @ForAll @LongRange(min = 1, max = 100_000_000_00L) long totalCents,
            @ForAll @IntRange(min = 2, max = 400) int parts) {

        List<Money> split = MoneySplitter.split(cents(totalCents), parts);
        Money base = split.get(0);
        assertThat(split.subList(0, parts - 1)).containsOnly(base);
        assertThat(split.get(parts - 1)).isGreaterThanOrEqualTo(base);
    }

    /**
     * The full schedule form of the invariant:
     * {@code down + delivery + sum(installments) == net}.
     */
    @Property(tries = 2000)
    void full_schedule_invariant_holds(
            @ForAll @LongRange(min = 100_00L, max = 500_000_000_00L) long netCents,
            @ForAll @IntRange(min = 0, max = 40) int downPaymentPercent,
            @ForAll @IntRange(min = 0, max = 30) int deliveryPercent,
            @ForAll @IntRange(min = 1, max = 400) int installmentCount) {

        Assume.that(downPaymentPercent + deliveryPercent < 100);

        Money net = cents(netCents);
        Money down = Percentage.of(String.valueOf(downPaymentPercent)).applyTo(net);
        Money delivery = Percentage.of(String.valueOf(deliveryPercent)).applyTo(net);
        Money financed = net.minus(down).minus(delivery);

        Assume.that(financed.isPositive());

        List<Money> installments = MoneySplitter.split(financed, installmentCount);

        assertThat(ScheduleInvariant.holds(net, down, delivery, installments))
                .as("net=%s down=%s delivery=%s count=%d", net, down, delivery, installmentCount)
                .isTrue();
    }

    private static Money cents(long cents) {
        return Money.of(BigDecimal.valueOf(cents).movePointLeft(2), EGP);
    }
}
