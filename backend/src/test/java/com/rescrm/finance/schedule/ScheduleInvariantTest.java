package com.rescrm.finance.schedule;

import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScheduleInvariant")
class ScheduleInvariantTest {

    private static final CurrencyCode EGP = CurrencyCode.EGP;

    @Test
    @DisplayName("a correct schedule passes")
    void correct_schedule_passes() {
        Money net = Money.of("1000.00", EGP);
        Money down = Money.of("100.00", EGP);
        Money delivery = Money.of("50.00", EGP);
        List<Money> installments = List.of(Money.of("425.00", EGP), Money.of("425.00", EGP));

        assertThat(ScheduleInvariant.holds(net, down, delivery, installments)).isTrue();
        ScheduleInvariant.check(net, down, delivery, installments);
    }

    @Test
    @DisplayName("a one-cent shortfall is rejected with the exact discrepancy")
    void one_cent_short_is_rejected() {
        Money net = Money.of("1000.00", EGP);
        Money down = Money.of("100.00", EGP);
        List<Money> installments = List.of(Money.of("899.99", EGP));

        assertThatThrownBy(() ->
                ScheduleInvariant.check(net, down, Money.zero(EGP), installments))
                .isInstanceOf(ScheduleInvariant.ScheduleInvariantViolationException.class)
                .satisfies(thrown -> {
                    var violation = (ScheduleInvariant.ScheduleInvariantViolationException) thrown;
                    assertThat(violation.difference()).isEqualTo(Money.of("0.01", EGP));
                    assertThat(violation.netValue()).isEqualTo(net);
                    assertThat(violation.scheduleTotal()).isEqualTo(Money.of("999.99", EGP));
                });
    }

    @Test
    @DisplayName("a one-cent excess is rejected too")
    void one_cent_over_is_rejected() {
        Money net = Money.of("1000.00", EGP);
        List<Money> installments = List.of(Money.of("1000.01", EGP));

        assertThatThrownBy(() ->
                ScheduleInvariant.check(net, Money.zero(EGP), Money.zero(EGP), installments))
                .isInstanceOf(ScheduleInvariant.ScheduleInvariantViolationException.class);
    }

    @Test
    @DisplayName("the violation carries the figures into the API error envelope")
    void violation_carries_api_details() {
        Money net = Money.of("1000.00", EGP);
        List<Money> installments = List.of(Money.of("999.99", EGP));

        try {
            ScheduleInvariant.check(net, Money.zero(EGP), Money.zero(EGP), installments);
            throw new AssertionError("expected a violation");
        } catch (ScheduleInvariant.ScheduleInvariantViolationException violation) {
            // Doc 28 section 7: a financial error reports the numbers, not just "invalid".
            assertThat(violation.code()).isEqualTo(ErrorCode.PLAN_INVARIANT_VIOLATION);
            assertThat(violation.details())
                    .containsEntry("netValue", "1000.00")
                    .containsEntry("scheduleTotal", "999.99")
                    .containsEntry("difference", "0.01");
        }
    }

    @Test
    @DisplayName("an empty installment list is valid when the other components cover the net value")
    void cash_plan_shape() {
        // A cash plan: the whole value sits in the down payment (rule R-PLAN-5).
        Money net = Money.of("500.00", EGP);

        assertThat(ScheduleInvariant.holds(net, net, Money.zero(EGP), List.of())).isTrue();
    }

    @Test
    @DisplayName("rejects nulls")
    void rejects_nulls() {
        Money net = Money.of("1.00", EGP);

        assertThatThrownBy(() ->
                ScheduleInvariant.check(null, net, net, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                ScheduleInvariant.check(net, net, net, null))
                .isInstanceOf(NullPointerException.class);
    }
}
