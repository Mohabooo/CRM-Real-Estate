package com.rescrm.finance.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FIN-020 through FIN-027 — schedule dates (doc 29, section 3). */
@DisplayName("ScheduleDateCalculator")
class ScheduleDateCalculatorTest {

    @Test
    @DisplayName("FIN-020 a monthly schedule from 31 January clamps but does not drift")
    void month_end_clamping_retains_the_original_day() {
        List<LocalDate> dates =
                ScheduleDateCalculator.dueDates(LocalDate.of(2026, 1, 31), Frequency.MONTHLY, 5);

        // The point of rule R-INST-6: February clamps to the 28th, but March returns to the
        // 31st. Stepping from each computed date instead of from the anchor would leave the
        // whole schedule stuck on the 28th — the classic defect this class exists to prevent.
        assertThat(dates).containsExactly(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30),
                LocalDate.of(2026, 5, 31));
    }

    @Test
    @DisplayName("FIN-021 a leap year clamps to 29 February and still returns to the 31st")
    void leap_year() {
        List<LocalDate> dates =
                ScheduleDateCalculator.dueDates(LocalDate.of(2024, 1, 31), Frequency.MONTHLY, 3);

        assertThat(dates).containsExactly(
                LocalDate.of(2024, 1, 31),
                LocalDate.of(2024, 2, 29),
                LocalDate.of(2024, 3, 31));
    }

    @Test
    @DisplayName("FIN-022 quarterly steps advance three months at a time")
    void quarterly() {
        List<LocalDate> dates =
                ScheduleDateCalculator.dueDates(LocalDate.of(2026, 3, 15), Frequency.QUARTERLY, 4);

        assertThat(dates).containsExactly(
                LocalDate.of(2026, 3, 15),
                LocalDate.of(2026, 6, 15),
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 12, 15));
    }

    @Test
    @DisplayName("FIN-023 semi-annual and annual frequencies")
    void semi_annual_and_annual() {
        assertThat(ScheduleDateCalculator.dueDateAt(
                LocalDate.of(2026, 1, 1), Frequency.SEMI_ANNUAL, 1))
                .isEqualTo(LocalDate.of(2026, 7, 1));

        assertThat(ScheduleDateCalculator.dueDateAt(
                LocalDate.of(2026, 1, 1), Frequency.ANNUAL, 2))
                .isEqualTo(LocalDate.of(2028, 1, 1));

        assertThat(Frequency.QUARTERLY.occurrencesPerYear()).isEqualTo(4);
        assertThat(Frequency.SEMI_ANNUAL.occurrencesPerYear()).isEqualTo(2);
    }

    @Test
    @DisplayName("FIN-024 the first due date applies the configured offset")
    void first_due_date_offset() {
        assertThat(ScheduleDateCalculator.firstDueDate(LocalDate.of(2026, 9, 20), 30))
                .isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(ScheduleDateCalculator.firstDueDate(LocalDate.of(2026, 9, 20), 0))
                .isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    @DisplayName("FIN-026 no weekend or holiday shifting is applied")
    void no_weekend_shifting() {
        // 2026-09-19 is a Saturday. The contractual due date is the obligation; whether a
        // bank is open that day is a settlement concern the platform does not model.
        LocalDate saturday = LocalDate.of(2026, 9, 19);
        assertThat(ScheduleDateCalculator.dueDates(saturday, Frequency.MONTHLY, 1))
                .containsExactly(saturday);
    }

    @Test
    @DisplayName("FIN-027 dates are strictly ascending across a long schedule")
    void strictly_ascending() {
        List<LocalDate> dates =
                ScheduleDateCalculator.dueDates(LocalDate.of(2026, 1, 31), Frequency.QUARTERLY, 32);

        assertThat(dates).hasSize(32);
        assertThat(dates).isSorted();
        for (int i = 1; i < dates.size(); i++) {
            assertThat(dates.get(i)).isAfter(dates.get(i - 1));
        }
        assertThat(dates.get(31)).isEqualTo(LocalDate.of(2033, 10, 31));
    }

    @Test
    @DisplayName("helper methods agree with the generated list")
    void helpers_agree() {
        LocalDate first = LocalDate.of(2026, 1, 31);
        List<LocalDate> dates = ScheduleDateCalculator.dueDates(first, Frequency.QUARTERLY, 32);

        assertThat(ScheduleDateCalculator.lastDueDate(first, Frequency.QUARTERLY, 32))
                .isEqualTo(dates.get(31));
        assertThat(ScheduleDateCalculator.dueDateAt(first, Frequency.QUARTERLY, 5))
                .isEqualTo(dates.get(5));
        assertThat(ScheduleDateCalculator.spanInMonths(Frequency.QUARTERLY, 32)).isEqualTo(93);
    }

    @Test
    @DisplayName("the returned list is immutable")
    void immutable_result() {
        List<LocalDate> dates =
                ScheduleDateCalculator.dueDates(LocalDate.of(2026, 1, 1), Frequency.MONTHLY, 2);

        assertThatThrownBy(() -> dates.add(LocalDate.of(2026, 3, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("rejects invalid inputs")
    void rejects_invalid_input() {
        LocalDate date = LocalDate.of(2026, 1, 1);

        assertThatThrownBy(() -> ScheduleDateCalculator.dueDates(date, Frequency.MONTHLY, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("count");
        assertThatThrownBy(() -> ScheduleDateCalculator.dueDates(date, Frequency.MONTHLY, 5000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleDateCalculator.firstDueDate(date, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleDateCalculator.dueDates(null, Frequency.MONTHLY, 1))
                .isInstanceOf(NullPointerException.class);
    }
}
