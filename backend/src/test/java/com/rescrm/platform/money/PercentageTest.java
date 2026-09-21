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
}
