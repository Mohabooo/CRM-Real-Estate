package com.rescrm.finance.allocation;

import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FIN-010 through FIN-014 — rounding behaviour (doc 29, section 2). */
@DisplayName("MoneySplitter rounding")
class MoneySplitterTest {

    private static final CurrencyCode EGP = CurrencyCode.EGP;

    @Test
    @DisplayName("FIN-010 the base amount floors rather than rounding half-up")
    void base_amount_floors() {
        Money financed = Money.of("2422500.00", EGP);

        // 2,422,500 / 32 = 75,703.125 exactly. Half-up would give 75,703.13 and produce a
        // negative remainder; flooring gives 75,703.12 and a remainder of 0.16.
        assertThat(MoneySplitter.baseAmount(financed, 32)).isEqualTo(Money.of("75703.12", EGP));
        assertThat(MoneySplitter.remainder(financed, 32)).isEqualTo(Money.of("0.16", EGP));
    }

    @Test
    @DisplayName("FIN-012 the remainder lands on the final part only")
    void remainder_lands_on_the_last_part() {
        List<Money> parts = MoneySplitter.split(Money.of("100.00", EGP), 3);

        assertThat(parts).containsExactly(
                Money.of("33.33", EGP),
                Money.of("33.33", EGP),
                Money.of("33.34", EGP));
        assertThat(Money.sum(EGP, parts)).isEqualTo(Money.of("100.00", EGP));
    }

    @Test
    @DisplayName("FIN-013 exact division leaves no remainder")
    void exact_division() {
        Money total = Money.of("1200000.00", EGP);

        assertThat(MoneySplitter.remainder(total, 12)).isEqualTo(Money.zero(EGP));
        assertThat(MoneySplitter.split(total, 12)).containsOnly(Money.of("100000.00", EGP));
    }

    @Test
    @DisplayName("FIN-014 a single-part split returns the whole amount")
    void single_part() {
        Money total = Money.of("1234.57", EGP);

        assertThat(MoneySplitter.split(total, 1)).containsExactly(total);
        assertThat(MoneySplitter.remainder(total, 1)).isEqualTo(Money.zero(EGP));
    }

    @Test
    @DisplayName("splitting zero yields zero parts that still conserve")
    void zero_total() {
        List<Money> parts = MoneySplitter.split(Money.zero(EGP), 4);

        assertThat(parts).containsOnly(Money.zero(EGP));
        assertThat(Money.sum(EGP, parts)).isEqualTo(Money.zero(EGP));
    }

    @Test
    @DisplayName("a smaller amount than the part count still conserves")
    void amount_smaller_than_part_count() {
        // 0.03 split five ways: four parts of zero and one of 0.03. Ugly, but exact —
        // and preferable to inventing a cent to make it look tidy.
        List<Money> parts = MoneySplitter.split(Money.of("0.03", EGP), 5);

        assertThat(parts).hasSize(5);
        assertThat(Money.sum(EGP, parts)).isEqualTo(Money.of("0.03", EGP));
        assertThat(parts.get(4)).isEqualTo(Money.of("0.03", EGP));
    }

    @Test
    @DisplayName("rejects a non-positive part count")
    void rejects_invalid_part_count() {
        Money total = Money.of("10.00", EGP);

        assertThatThrownBy(() -> MoneySplitter.split(total, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parts");
        assertThatThrownBy(() -> MoneySplitter.split(total, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects splitting a negative amount")
    void rejects_negative_total() {
        Money negative = Money.of("-10.00", EGP);

        assertThatThrownBy(() -> MoneySplitter.split(negative, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("the returned list is immutable")
    void result_is_immutable() {
        List<Money> parts = MoneySplitter.split(Money.of("10.00", EGP), 2);

        assertThatThrownBy(() -> parts.add(Money.zero(EGP)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
