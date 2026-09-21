package com.rescrm.platform.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Money")
class MoneyTest {

    private static final CurrencyCode EGP = CurrencyCode.EGP;

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("normalises scale to 2 so equality is value-based")
        void normalises_scale() {
            assertThat(Money.of(new BigDecimal("1.5"), EGP).toPlainString()).isEqualTo("1.50");
            assertThat(Money.ofMajor(100, EGP).toPlainString()).isEqualTo("100.00");
            assertThat(Money.zero(EGP).toPlainString()).isEqualTo("0.00");
        }

        @Test
        @DisplayName("rejects more precision than the currency has, rather than guessing")
        void rejects_excess_precision() {
            // Silently rounding here is how cents disappear: the caller expressed an intent
            // the type cannot honour, so it must say so.
            assertThatThrownBy(() -> Money.of("1.005", EGP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("decimal places");
        }

        @Test
        @DisplayName("accepts excess precision when rounding is requested explicitly")
        void accepts_explicit_rounding() {
            assertThat(Money.of(new BigDecimal("1.005"), EGP, RoundingMode.HALF_UP).toPlainString())
                    .isEqualTo("1.01");
            assertThat(Money.of(new BigDecimal("1.005"), EGP, RoundingMode.DOWN).toPlainString())
                    .isEqualTo("1.00");
        }

        @Test
        @DisplayName("accepts trailing zeros beyond the scale")
        void accepts_trailing_zeros() {
            assertThat(Money.of(new BigDecimal("1.5000"), EGP)).isEqualTo(Money.of("1.50", EGP));
        }

        @Test
        @DisplayName("rejects amounts beyond the NUMERIC(18,2) range")
        void rejects_out_of_range() {
            assertThatThrownBy(() -> Money.of("99999999999999999.00", EGP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("range");
        }

        @Test
        @DisplayName("rejects nulls")
        void rejects_nulls() {
            assertThatThrownBy(() -> Money.of((String) null, EGP))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> Money.of("1.00", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("adds and subtracts exactly")
        void adds_and_subtracts() {
            Money hundred = Money.of("100.00", EGP);
            Money cent = Money.of("0.01", EGP);

            assertThat(hundred.plus(cent)).isEqualTo(Money.of("100.01", EGP));
            assertThat(hundred.plus(cent).minus(cent)).isEqualTo(hundred);
        }

        @Test
        @DisplayName("the classic 0.1 + 0.2 case is exact")
        void no_floating_point_error() {
            // In binary floating point this is 0.30000000000000004. Here it is 0.30.
            assertThat(Money.of("0.10", EGP).plus(Money.of("0.20", EGP)))
                    .isEqualTo(Money.of("0.30", EGP));
        }

        @Test
        @DisplayName("multiplies by a whole factor exactly")
        void multiplies_by_int() {
            assertThat(Money.of("75703.12", EGP).times(32)).isEqualTo(Money.of("2422499.84", EGP));
        }

        @Test
        @DisplayName("multiplies by a decimal factor with half-up rounding")
        void multiplies_by_decimal() {
            assertThat(Money.of("100.00", EGP).times(new BigDecimal("0.035")))
                    .isEqualTo(Money.of("3.50", EGP));
            assertThat(Money.of("1.00", EGP).times(new BigDecimal("0.005")))
                    .isEqualTo(Money.of("0.01", EGP));
        }

        @Test
        @DisplayName("divideFloor rounds down, never up")
        void divide_floor() {
            assertThat(Money.of("100.00", EGP).divideFloor(3)).isEqualTo(Money.of("33.33", EGP));
            assertThat(Money.of("2422500.00", EGP).divideFloor(32))
                    .isEqualTo(Money.of("75703.12", EGP));
        }

        @Test
        @DisplayName("rejects division by zero or a negative count")
        void rejects_invalid_division() {
            assertThatThrownBy(() -> Money.of("10.00", EGP).divideFloor(0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negates and absolutes")
        void negate_and_abs() {
            Money value = Money.of("5.00", EGP);
            assertThat(value.negated()).isEqualTo(Money.of("-5.00", EGP));
            assertThat(value.negated().abs()).isEqualTo(value);
        }

        @Test
        @DisplayName("sums a sequence")
        void sums() {
            assertThat(Money.sum(EGP, java.util.List.of(
                    Money.of("1.10", EGP), Money.of("2.20", EGP), Money.of("3.30", EGP))))
                    .isEqualTo(Money.of("6.60", EGP));
            assertThat(Money.sum(EGP, java.util.List.of())).isEqualTo(Money.zero(EGP));
        }
    }

    @Nested
    @DisplayName("currency safety")
    class CurrencySafety {

        @Test
        @DisplayName("same-currency arithmetic succeeds")
        void same_currency_ok() {
            assertThat(Money.of("1.00", EGP).plus(Money.of("2.00", EGP)))
                    .isEqualTo(Money.of("3.00", EGP));
        }

        @Test
        @DisplayName("an unsupported currency code is rejected at the boundary")
        void unsupported_currency_rejected() {
            assertThatThrownBy(() -> CurrencyCode.fromIsoCode("USD"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported currency");
            assertThat(CurrencyCode.fromIsoCode("egp")).isEqualTo(EGP);
        }
    }

    @Nested
    @DisplayName("identity and comparison")
    class Identity {

        @Test
        @DisplayName("equality is value-based, unlike BigDecimal's")
        void equality_is_value_based() {
            Money a = Money.of("1.50", EGP);
            Money b = Money.of(new BigDecimal("1.5"), EGP);

            // new BigDecimal("1.50").equals(new BigDecimal("1.5")) is false; Money's is true.
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("compareTo is consistent with equals")
        void compare_consistent_with_equals() {
            Money a = Money.of("1.50", EGP);
            Money b = Money.of(new BigDecimal("1.5"), EGP);

            assertThat(a.compareTo(b)).isZero();
            assertThat(a.isGreaterThan(Money.of("1.49", EGP))).isTrue();
            assertThat(a.isLessThan(Money.of("1.51", EGP))).isTrue();
            assertThat(a.isGreaterThanOrEqualTo(b)).isTrue();
            assertThat(a.isLessThanOrEqualTo(b)).isTrue();
        }

        @Test
        @DisplayName("predicates report sign correctly")
        void sign_predicates() {
            assertThat(Money.zero(EGP).isZero()).isTrue();
            assertThat(Money.of("0.01", EGP).isPositive()).isTrue();
            assertThat(Money.of("-0.01", EGP).isNegative()).isTrue();
        }

        @Test
        @DisplayName("FIN-017 the wire format is a plain decimal string")
        void wire_format() {
            assertThat(Money.of("2850000.00", EGP).toPlainString()).isEqualTo("2850000.00");
            assertThat(Money.of("0.05", EGP).toPlainString()).isEqualTo("0.05");
            // No scientific notation, ever — a consumer parsing "2.85E+6" would be lost.
            assertThat(Money.of("2850000.00", EGP).toPlainString()).doesNotContain("E");
        }
    }
}
