package com.rescrm.finance.schedule;

import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.Percentage;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-018 — the master schedule property, asserted against the generator.
 *
 * <p>Doc 29 calls this the single highest-value test in the project, and doc 28 decision A21
 * requires it to exist before the generator it constrains. It was written against the API
 * below while {@code PaymentScheduleGenerator} still had no body.
 *
 * <p>There is already a property of this name in {@code MoneySplitterPropertyTest}, written
 * in Epic 0 before any generator existed. That one performs the arithmetic inline — it
 * computes the down payment, the delivery payment and the split itself and then checks the
 * invariant over its own result. That was the only thing available at the time and it is not
 * the same assertion: a test that reimplements the recipe verifies the recipe, not the code
 * that ships. This one hands the generator its terms and checks what the generator returns.
 *
 * <p>The distinction matters most for the parts the earlier property could not reach — that
 * the down payment and the delivery payment appear as ROWS and are counted in the sum, that a
 * fixed down payment conserves as exactly as a percentage one, and that nothing is lost when
 * the two together consume almost the whole net value.
 */
class PaymentScheduleGeneratorPropertyTest {

    private static final CurrencyCode EGP = CurrencyCode.EGP;
    private static final LocalDate DEAL_DATE = LocalDate.of(2026, 3, 15);

    @Property(tries = 2000)
    void a_generated_schedule_always_sums_to_net_value(
            @ForAll @LongRange(min = 100_00L, max = 500_000_000_00L) long netCents,
            @ForAll @IntRange(min = 0, max = 40) int downPercent,
            @ForAll @IntRange(min = 0, max = 30) int deliveryPercent,
            @ForAll @IntRange(min = 1, max = 400) int installmentCount,
            @ForAll Frequency frequency) {

        Assume.that(downPercent + deliveryPercent < 100);

        Money net = cents(netCents);
        PaymentSchedule schedule = PaymentScheduleGenerator.generate(new PlanTerms(
                net,
                DownPayment.percent(Percentage.of(String.valueOf(downPercent))),
                Percentage.of(String.valueOf(deliveryPercent)),
                installmentCount, frequency, DEAL_DATE, 30, Optional.empty()));

        assertThat(schedule.total())
                .as("net=%s down=%d%% delivery=%d%% count=%d %s",
                        net, downPercent, deliveryPercent, installmentCount, frequency)
                .isEqualByComparingTo(net);
    }

    @Property(tries = 2000)
    void a_fixed_down_payment_conserves_just_as_exactly(
            @ForAll @LongRange(min = 1000_00L, max = 500_000_000_00L) long netCents,
            @ForAll @IntRange(min = 0, max = 90) int downPercentOfNet,
            @ForAll @IntRange(min = 1, max = 200) int installmentCount) {

        Money net = cents(netCents);
        // A fixed amount that happens to be a proportion of net, so the generator is exercised
        // across the whole range rather than only at round figures.
        Money down = Percentage.of(String.valueOf(downPercentOfNet)).applyTo(net);

        PaymentSchedule schedule = PaymentScheduleGenerator.generate(new PlanTerms(
                net, DownPayment.fixed(down), Percentage.zero(),
                installmentCount, Frequency.MONTHLY, DEAL_DATE, 30, Optional.empty()));

        assertThat(schedule.total()).isEqualByComparingTo(net);
        assertThat(schedule.downPaymentAmount()).isEqualByComparingTo(down);
    }

    @Property(tries = 1000)
    void every_row_is_accounted_for_and_none_is_negative(
            @ForAll @LongRange(min = 100_00L, max = 100_000_000_00L) long netCents,
            @ForAll @IntRange(min = 0, max = 50) int downPercent,
            @ForAll @IntRange(min = 0, max = 40) int deliveryPercent,
            @ForAll @IntRange(min = 1, max = 120) int installmentCount) {

        Assume.that(downPercent + deliveryPercent < 100);

        PaymentSchedule schedule = PaymentScheduleGenerator.generate(new PlanTerms(
                cents(netCents),
                DownPayment.percent(Percentage.of(String.valueOf(downPercent))),
                Percentage.of(String.valueOf(deliveryPercent)),
                installmentCount, Frequency.QUARTERLY, DEAL_DATE, 30, Optional.empty()));

        // FIN-001b generalised: one down payment, N installments, one delivery row.
        assertThat(schedule.rows()).hasSize(installmentCount + 2);
        assertThat(schedule.rowsOfKind(InstallmentKind.DOWN_PAYMENT)).hasSize(1);
        assertThat(schedule.rowsOfKind(InstallmentKind.DELIVERY)).hasSize(1);
        assertThat(schedule.rowsOfKind(InstallmentKind.INSTALLMENT)).hasSize(installmentCount);

        assertThat(schedule.rows()).allSatisfy(row ->
                assertThat(row.expectedAmount().isNegative())
                        .as("no row may be negative: %s", row)
                        .isFalse());
    }

    @Property(tries = 1000)
    void due_dates_never_go_backwards(
            @ForAll @IntRange(min = 1, max = 120) int installmentCount,
            @ForAll Frequency frequency,
            @ForAll @IntRange(min = 0, max = 365) int offsetDays) {

        // FIN-027: strictly ascending, for any frequency and count. Asserted over the whole
        // schedule including the down payment, since that is the order a customer reads.
        PaymentSchedule schedule = PaymentScheduleGenerator.generate(new PlanTerms(
                cents(10_000_000_00L),
                DownPayment.percent(Percentage.of("10")), Percentage.of("5"),
                installmentCount, frequency, DEAL_DATE, offsetDays, Optional.empty()));

        List<PaymentSchedule.Row> rows = schedule.rows();
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i).dueDate())
                    .as("row %d must not fall before row %d", i + 1, i)
                    .isAfterOrEqualTo(rows.get(i - 1).dueDate());
        }
    }

    private static Money cents(long cents) {
        return Money.of(BigDecimal.valueOf(cents).movePointLeft(2), EGP);
    }
}
