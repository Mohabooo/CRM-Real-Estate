package com.rescrm.finance;

import com.rescrm.finance.allocation.MoneySplitter;
import com.rescrm.finance.schedule.ScheduleDateCalculator;
import com.rescrm.finance.schedule.ScheduleInvariant;
import com.rescrm.fixtures.CanonicalFixture;
import com.rescrm.platform.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-001 — the canonical fixture (doc 29, section 1).
 *
 * <p>Release gate 1 of the nine in doc 29 section 14. If this test fails, no build ships.
 */
@DisplayName("FIN-001 canonical fixture")
class CanonicalFixtureTest {

    @Test
    @DisplayName("FIN-001 reproduces every figure in doc 17 section 5 exactly")
    void reproduces_the_documented_figures() {
        Money discount = CanonicalFixture.DISCOUNT_RATE.applyTo(CanonicalFixture.GROSS_VALUE);
        assertThat(discount).isEqualTo(CanonicalFixture.DISCOUNT_AMOUNT);

        Money net = CanonicalFixture.GROSS_VALUE.minus(discount);
        assertThat(net).isEqualTo(CanonicalFixture.NET_VALUE);

        Money down = CanonicalFixture.DOWN_PAYMENT_RATE.applyTo(net);
        assertThat(down).isEqualTo(CanonicalFixture.DOWN_PAYMENT);

        Money delivery = CanonicalFixture.DELIVERY_RATE.applyTo(net);
        assertThat(delivery).isEqualTo(CanonicalFixture.DELIVERY_PAYMENT);

        Money financed = net.minus(down).minus(delivery);
        assertThat(financed).isEqualTo(CanonicalFixture.FINANCED_AMOUNT);

        assertThat(MoneySplitter.baseAmount(financed, CanonicalFixture.INSTALLMENT_COUNT))
                .isEqualTo(CanonicalFixture.BASE_INSTALLMENT);
        assertThat(MoneySplitter.remainder(financed, CanonicalFixture.INSTALLMENT_COUNT))
                .isEqualTo(CanonicalFixture.REMAINDER);
    }

    @Test
    @DisplayName("FIN-001a the schedule invariant holds exactly, with no tolerance")
    void invariant_holds_to_the_cent() {
        List<Money> installments = CanonicalFixture.installments();

        // Asserted with equality, never with a tolerance: R-PLAN-4 demands exactness.
        assertThat(ScheduleInvariant.holds(
                CanonicalFixture.NET_VALUE,
                CanonicalFixture.DOWN_PAYMENT,
                CanonicalFixture.DELIVERY_PAYMENT,
                installments)).isTrue();

        assertThat(ScheduleInvariant.scheduleTotal(
                CanonicalFixture.DOWN_PAYMENT,
                CanonicalFixture.DELIVERY_PAYMENT,
                installments)).isEqualTo(CanonicalFixture.NET_VALUE);
    }

    @Test
    @DisplayName("FIN-001b the schedule has 32 installments")
    void installment_count_is_correct() {
        assertThat(CanonicalFixture.installments()).hasSize(CanonicalFixture.INSTALLMENT_COUNT);
    }

    @Test
    @DisplayName("FIN-001c only the final installment differs from the base amount")
    void only_the_last_installment_carries_the_remainder() {
        List<Money> installments = CanonicalFixture.installments();

        assertThat(installments.subList(0, 31))
                .as("installments 1 through 31")
                .containsOnly(CanonicalFixture.BASE_INSTALLMENT);

        assertThat(installments.get(31))
                .as("installment 32 carries the 0.16 remainder")
                .isEqualTo(CanonicalFixture.FINAL_INSTALLMENT);
    }

    @Test
    @DisplayName("installments 1 through 31 sum to 2,346,796.72")
    void sum_of_all_but_the_last_installment() {
        List<Money> installments = CanonicalFixture.installments();
        Money sum = Money.sum(CanonicalFixture.CURRENCY, installments.subList(0, 31));
        assertThat(sum).isEqualTo(CanonicalFixture.SUM_OF_ALL_BUT_LAST);
    }

    @Test
    @DisplayName("the fixture's quarterly dates run from 31 Jan 2026 to 31 Oct 2033")
    void fixture_dates() {
        List<LocalDate> dates = ScheduleDateCalculator.dueDates(
                CanonicalFixture.FIRST_DUE_DATE,
                CanonicalFixture.FREQUENCY,
                CanonicalFixture.INSTALLMENT_COUNT);

        assertThat(dates).hasSize(32);
        assertThat(dates.get(0)).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(dates.get(31)).isEqualTo(LocalDate.of(2033, 10, 31));
    }
}
