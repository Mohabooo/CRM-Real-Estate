package com.rescrm.finance.schedule;

import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.Percentage;
import com.rescrm.support.CanonicalDeal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The generator against doc 29's example cases.
 *
 * <p>{@link PaymentScheduleGeneratorPropertyTest} covers the space; this covers the cases the
 * documents name, so a regression reports which documented rule it broke rather than only
 * that some random input stopped conserving.
 */
@DisplayName("The payment schedule generator")
class PaymentScheduleGeneratorTest {

    private static final CurrencyCode EGP = CurrencyCode.EGP;

    private static Money egp(String amount) {
        return Money.of(amount, EGP);
    }

    @Nested
    @DisplayName("FIN-001 — the canonical fixture")
    class CanonicalFixture {

        private final PaymentSchedule schedule =
                PaymentScheduleGenerator.generate(CanonicalDeal.terms());

        @Test
        @DisplayName("produces the documented figures, to the cent")
        void figures() {
            assertThat(schedule.downPaymentAmount())
                    .isEqualByComparingTo(CanonicalDeal.DOWN_PAYMENT);
            assertThat(schedule.deliveryPaymentAmount())
                    .isEqualByComparingTo(CanonicalDeal.DELIVERY_PAYMENT);
            assertThat(schedule.financedAmount()).isEqualByComparingTo(CanonicalDeal.FINANCED);
        }

        @Test
        @DisplayName("FIN-001a — the invariant holds exactly, never within a tolerance")
        void the_invariant_holds_exactly() {
            assertThat(schedule.total().compareTo(CanonicalDeal.NET_VALUE))
                    .as("285,000.00 + 142,500.00 + 2,346,796.72 + 75,703.28 = 2,850,000.00")
                    .isZero();
        }

        @Test
        @DisplayName("FIN-001b — 34 rows: one down payment, 32 installments, one delivery")
        void row_count() {
            assertThat(schedule.rows()).hasSize(CanonicalDeal.EXPECTED_ROW_COUNT);
            assertThat(schedule.rowsOfKind(InstallmentKind.DOWN_PAYMENT)).hasSize(1);
            assertThat(schedule.rowsOfKind(InstallmentKind.INSTALLMENT))
                    .hasSize(CanonicalDeal.INSTALLMENT_COUNT);
            assertThat(schedule.rowsOfKind(InstallmentKind.DELIVERY)).hasSize(1);
        }

        @Test
        @DisplayName("FIN-001c — only the final installment differs from the base")
        void only_the_last_differs() {
            List<PaymentSchedule.Row> installments =
                    schedule.rowsOfKind(InstallmentKind.INSTALLMENT);

            assertThat(installments.subList(0, 31)).allSatisfy(row ->
                    assertThat(row.expectedAmount())
                            .isEqualByComparingTo(CanonicalDeal.BASE_INSTALLMENT));
            assertThat(installments.get(31).expectedAmount())
                    .isEqualByComparingTo(CanonicalDeal.FINAL_INSTALLMENT);
        }

        @Test
        @DisplayName("FIN-001d — a brokered deal gets a byte-identical schedule")
        void the_model_makes_no_difference() {
            // There is nothing to vary: PlanTerms carries no commercial model, so the two
            // cases are the same call. That is the assertion — doc 27 section 2 requires the
            // engine to be model-agnostic, and the way to guarantee it is to give the
            // generator nothing to branch on.
            PaymentSchedule again = PaymentScheduleGenerator.generate(CanonicalDeal.terms());

            assertThat(again.rows()).isEqualTo(schedule.rows());
        }
    }

    @Nested
    @DisplayName("FIN-010 to FIN-014 — rounding")
    class Rounding {

        @Test
        @DisplayName("FIN-010 — the base installment floors rather than rounding half-up")
        void base_installment_floors() {
            // 2,422,500 / 32 is 75,703.125 exactly. Half-up would give 75,703.13, and the
            // remainder would then have to be negative to make the total work.
            assertThat(schedule(egp("2422500.00"), 32).rowsOfKind(InstallmentKind.INSTALLMENT)
                    .get(0).expectedAmount())
                    .isEqualByComparingTo(egp("75703.12"));
        }

        @Test
        @DisplayName("FIN-012 — the remainder lands on the final installment only")
        void remainder_on_the_last() {
            List<PaymentSchedule.Row> rows =
                    schedule(egp("100.00"), 3).rowsOfKind(InstallmentKind.INSTALLMENT);

            assertThat(rows.get(0).expectedAmount()).isEqualByComparingTo(egp("33.33"));
            assertThat(rows.get(1).expectedAmount()).isEqualByComparingTo(egp("33.33"));
            assertThat(rows.get(2).expectedAmount()).isEqualByComparingTo(egp("33.34"));
        }

        @Test
        @DisplayName("FIN-013 — exact division leaves no remainder")
        void exact_division() {
            assertThat(schedule(egp("1200000.00"), 12).rowsOfKind(InstallmentKind.INSTALLMENT))
                    .allSatisfy(row -> assertThat(row.expectedAmount())
                            .isEqualByComparingTo(egp("100000.00")));
        }

        @Test
        @DisplayName("FIN-014 — a single-installment plan takes the whole financed amount")
        void single_installment() {
            PaymentSchedule single = schedule(egp("777777.77"), 1);

            assertThat(single.rowsOfKind(InstallmentKind.INSTALLMENT)).hasSize(1);
            assertThat(single.rowsOfKind(InstallmentKind.INSTALLMENT).get(0).expectedAmount())
                    .isEqualByComparingTo(egp("777777.77"));
        }

        /** A schedule with no down payment and no delivery, so financed == net. */
        private PaymentSchedule schedule(Money net, int count) {
            return PaymentScheduleGenerator.generate(new PlanTerms(net,
                    DownPayment.none(Money.zero(EGP)), Percentage.zero(), count,
                    Frequency.MONTHLY, LocalDate.of(2026, 3, 15), 30, Optional.empty()));
        }
    }

    @Nested
    @DisplayName("FIN-020 to FIN-026 — dates")
    class Dates {

        @Test
        @DisplayName("FIN-020 — monthly from 31 January clamps, then recovers the original day")
        void month_end_clamping() {
            assertThat(dueDates(LocalDate.of(2026, 1, 31), Frequency.MONTHLY, 5))
                    .containsExactly(
                            LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
                            LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30),
                            LocalDate.of(2026, 5, 31));
        }

        @Test
        @DisplayName("FIN-021 — and clamps to the 29th in a leap year")
        void leap_year() {
            assertThat(dueDates(LocalDate.of(2028, 1, 31), Frequency.MONTHLY, 3))
                    .containsExactly(LocalDate.of(2028, 1, 31), LocalDate.of(2028, 2, 29),
                            LocalDate.of(2028, 3, 31));
        }

        @Test
        @DisplayName("FIN-022/023 — quarterly, semi-annual and annual step by 3, 6 and 12 months")
        void intervals() {
            assertThat(dueDates(LocalDate.of(2026, 1, 15), Frequency.QUARTERLY, 3))
                    .containsExactly(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 4, 15),
                            LocalDate.of(2026, 7, 15));
            assertThat(dueDates(LocalDate.of(2026, 1, 15), Frequency.SEMI_ANNUAL, 3))
                    .containsExactly(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 7, 15),
                            LocalDate.of(2027, 1, 15));
            assertThat(dueDates(LocalDate.of(2026, 1, 15), Frequency.ANNUAL, 3))
                    .containsExactly(LocalDate.of(2026, 1, 15), LocalDate.of(2027, 1, 15),
                            LocalDate.of(2028, 1, 15));
        }

        @Test
        @DisplayName("the down payment is due on the deal date, not after the offset")
        void down_payment_is_due_at_once() {
            PaymentSchedule schedule =
                    PaymentScheduleGenerator.generate(CanonicalDeal.terms());

            assertThat(schedule.rowsOfKind(InstallmentKind.DOWN_PAYMENT).get(0).dueDate())
                    .isEqualTo(CanonicalDeal.DEAL_DATE);
        }

        @Test
        @DisplayName("FIN-025 — the delivery row carries the project's delivery date")
        void delivery_date_is_the_projects() {
            PaymentSchedule schedule =
                    PaymentScheduleGenerator.generate(CanonicalDeal.terms());

            assertThat(schedule.rowsOfKind(InstallmentKind.DELIVERY).get(0).dueDate())
                    .isEqualTo(CanonicalDeal.PROJECT_DELIVERY_DATE);
        }

        @Test
        @DisplayName("and falls on the last installment when the project has no delivery date")
        void delivery_date_falls_back_visibly() {
            PaymentSchedule schedule = PaymentScheduleGenerator.generate(new PlanTerms(
                    egp("120000.00"), DownPayment.none(Money.zero(EGP)), Percentage.of("10"),
                    3, Frequency.MONTHLY, LocalDate.of(2026, 3, 15), 30, Optional.empty()));

            // Visibly last rather than invented. A guessed delivery date would be quoted to
            // a customer as though somebody had decided it.
            assertThat(schedule.rowsOfKind(InstallmentKind.DELIVERY).get(0).dueDate())
                    .isEqualTo(schedule.rowsOfKind(InstallmentKind.INSTALLMENT).get(2).dueDate());
        }

        private List<LocalDate> dueDates(LocalDate first, Frequency frequency, int count) {
            return PaymentScheduleGenerator.generate(new PlanTerms(
                            egp("1200000.00"), DownPayment.none(Money.zero(EGP)),
                            Percentage.zero(), count, frequency, first, 0, Optional.empty()))
                    .rowsOfKind(InstallmentKind.INSTALLMENT).stream()
                    .map(PaymentSchedule.Row::dueDate)
                    .toList();
        }
    }

    @Nested
    @DisplayName("terms it refuses")
    class Refusals {

        @Test
        @DisplayName("a down payment larger than the net value")
        void down_payment_cannot_exceed_net() {
            assertThatThrownBy(() -> PaymentScheduleGenerator.generate(new PlanTerms(
                    egp("100000.00"), DownPayment.fixed(egp("200000.00")), Percentage.zero(),
                    12, Frequency.MONTHLY, LocalDate.of(2026, 3, 15), 30, Optional.empty())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds the net value");
        }

        @Test
        @DisplayName("a down payment and delivery that together exceed the net value")
        void down_and_delivery_cannot_exceed_net() {
            assertThatThrownBy(() -> PaymentScheduleGenerator.generate(new PlanTerms(
                    egp("100000.00"), DownPayment.fixed(egp("80000.00")), Percentage.of("50"),
                    12, Frequency.MONTHLY, LocalDate.of(2026, 3, 15), 30, Optional.empty())))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a net value of zero, which R-DISC-4 forbids")
        void net_value_must_be_positive() {
            assertThatThrownBy(() -> new PlanTerms(Money.zero(EGP),
                    DownPayment.none(Money.zero(EGP)), Percentage.zero(), 12,
                    Frequency.MONTHLY, LocalDate.of(2026, 3, 15), 30, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        @DisplayName("a plan with no installments at all")
        void at_least_one_installment() {
            assertThatThrownBy(() -> new PlanTerms(egp("100.00"),
                    DownPayment.none(Money.zero(EGP)), Percentage.zero(), 0,
                    Frequency.MONTHLY, LocalDate.of(2026, 3, 15), 30, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
