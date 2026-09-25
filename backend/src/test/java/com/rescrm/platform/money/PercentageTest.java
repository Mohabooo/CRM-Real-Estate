package com.rescrm.platform.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Percentage")
class PercentageTest {

    private static final CurrencyCode EGP = CurrencyCode.EGP;

    @Test
    @DisplayName("FIN-015 applies with half-up rounding")
    void applies_half_up() {
        assertThat(Percentage.of("5").applyTo(Money.of("3000000.00", EGP)))
                .isEqualTo(Money.of("150000.00", EGP));
        assertThat(Percentage.of("10").applyTo(Money.of("2850000.00", EGP)))
                .isEqualTo(Money.of("285000.00", EGP));
        assertThat(Percentage.of("5").applyTo(Money.of("2850000.00", EGP)))
                .isEqualTo(Money.of("142500.00", EGP));
    }

    @Test
    @DisplayName("gross and net bases produce materially different results")
    void gross_versus_net() {
        // Doc 26 section 4 uses this difference to argue that commission basis must be
        // configured rather than assumed: 4,500 EGP on a single mid-sized deal.
        Money gross = Money.of("3000000.00", EGP);
        Money net = Money.of("2850000.00", EGP);
        Percentage threePercent = Percentage.of("3");

        assertThat(threePercent.applyTo(gross)).isEqualTo(Money.of("90000.00", EGP));
        assertThat(threePercent.applyTo(net)).isEqualTo(Money.of("85500.00", EGP));
        assertThat(threePercent.applyTo(gross).minus(threePercent.applyTo(net)))
                .isEqualTo(Money.of("4500.00", EGP));
    }

    @Test
    @DisplayName("holds four decimal places")
    void four_decimal_places() {
        assertThat(Percentage.of("2.5").value().toPlainString()).isEqualTo("2.5000");
        assertThat(Percentage.of("0.0125").value().toPlainString()).isEqualTo("0.0125");
        assertThatThrownBy(() -> Percentage.of("0.00001"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("zero percent yields zero")
    void zero() {
        assertThat(Percentage.zero().applyTo(Money.of("123.45", EGP))).isEqualTo(Money.zero(EGP));
        assertThat(Percentage.zero().isZero()).isTrue();
    }

    @Test
    @DisplayName("rejects negative percentages")
    void rejects_negative() {
        assertThatThrownBy(() -> Percentage.of("-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("percentages above 100 are allowed but flagged")
    void above_one_hundred() {
        // Legitimate in some contexts (a multiplier), so not rejected outright; callers that
        // need to refuse it can ask.
        assertThat(Percentage.of("150").exceedsWhole()).isTrue();
        assertThat(Percentage.of("100").exceedsWhole()).isFalse();
    }

    @Test
    @DisplayName("equality and comparison are value-based")
    void equality() {
        assertThat(Percentage.of("5")).isEqualTo(Percentage.of("5.0000"));
        assertThat(Percentage.of("5").compareTo(Percentage.of("10"))).isNegative();
    }

    @Test
    @DisplayName("exposes a fractional form")
    void fraction() {
        assertThat(Percentage.of("5").asFraction()).isEqualByComparingTo("0.05");
    }

    @Test
    @DisplayName("FIN-016 rounds the true value once, not a rounded intermediate")
    void no_intermediate_pre_rounding() {
        // 7.3333% of 1,000,000.75 is exactly 73,333.05499975. Half-up at two places is
        // 73,333.05, because .05499975 is below .055 — but only if nothing rounded first.
        // Rounding the quotient to six places produced .055000 and then 73,333.06, which is
        // the classic double rounding and was a real defect here.
        assertThat(Percentage.of("7.3333").applyTo(Money.of("1000000.75", EGP)))
                .isEqualTo(Money.of("73333.05", EGP));

        // One ten-thousandth more, so the test cannot pass by always rounding down:
        // 7.3334% of the same base is 73,334.05500050, genuinely above the half, and the
        // cent goes up.
        assertThat(Percentage.of("7.3334").applyTo(Money.of("1000000.75", EGP)))
                .isEqualTo(Money.of("73334.06", EGP));
    }

    @Test
    @DisplayName("FIN-016 a percentage of a percentage takes each from the stated figure")
    void chained_percentages_use_the_rounded_figure() {
        // R-DISC-1 rounds the discount, R-DISC-3 makes the net gross minus that rounded
        // figure, and R-PLAN-2 takes the down payment from the net. The order is visible
        // here: 5% of 1,000,000.05 is 50,000.0025, which rounds to 50,000.00, so the net is
        // 950,000.05 and 10% of it is 95,000.005 — a cent up at half-up.
        Money gross = Money.of("1000000.05", EGP);
        Money discount = Percentage.of("5").applyTo(gross);
        assertThat(discount).isEqualTo(Money.of("50000.00", EGP));

        Money net = gross.minus(discount);
        assertThat(net).isEqualTo(Money.of("950000.05", EGP));

        // Ten per cent of the UNROUNDED net (950,000.0475) would be 95,000.00 — a cent
        // less. That is the pre-rounding FIN-016 forbids, seen from the other side: the
        // rounded net is the figure of record, and every later percentage comes off it.
        assertThat(Percentage.of("10").applyTo(net)).isEqualTo(Money.of("95000.01", EGP));
    }
}
