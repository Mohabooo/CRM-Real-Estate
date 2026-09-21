package com.rescrm.finance.schedule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Computes installment due dates. Pure, stateless and free of any domain dependency — it
 * knows about dates and frequencies, not about plans, deals or money.
 *
 * <h2>Month-end behaviour (doc 17, rule R-INST-6)</h2>
 * Due dates clamp to the last valid day of the target month, but the <em>original</em>
 * day-of-month is retained for every subsequent step. A monthly schedule anchored on
 * 31 January therefore runs 31 Jan → 28 Feb → 31 Mar → 30 Apr → 31 May, never drifting to
 * the 28th for the rest of its life.
 *
 * <p>This is achieved by computing every date from the anchor — {@code anchor.plusMonths(n)} —
 * rather than by stepping from the previously computed date. Iterating would compound the
 * February clamp into every later month, which is the single most common defect in
 * hand-rolled schedule code and the reason this class exists separately from the generator
 * that will use it.
 *
 * <h2>Weekends and holidays (rule R-INST-7)</h2>
 * No shifting is applied. The contractual due date is the obligation; whether a bank is open
 * that day is a settlement concern the platform does not model.
 */
public final class ScheduleDateCalculator {

    /** Guard against absurd schedules; the longest plans observed in-market run ~16 years. */
    private static final int MAX_INSTALLMENTS = 1200;

    private ScheduleDateCalculator() {
    }

    /**
     * Resolves the first installment due date from the deal date and a configured offset
     * (rule R-INST-4).
     *
     * @param dealDate   the date the deal was struck
     * @param offsetDays whole days between the deal date and the first due date; may be zero
     */
    public static LocalDate firstDueDate(LocalDate dealDate, int offsetDays) {
        Objects.requireNonNull(dealDate, "dealDate must not be null");
        if (offsetDays < 0) {
            throw new IllegalArgumentException("offsetDays must not be negative, was " + offsetDays);
        }
        return dealDate.plusDays(offsetDays);
    }

    /**
     * Produces {@code count} due dates beginning at {@code firstDueDate}, spaced by
     * {@code frequency}.
     *
     * @return an immutable, strictly ascending list of dates
     */
    public static List<LocalDate> dueDates(LocalDate firstDueDate, Frequency frequency, int count) {
        Objects.requireNonNull(firstDueDate, "firstDueDate must not be null");
        Objects.requireNonNull(frequency, "frequency must not be null");
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1, was " + count);
        }
        if (count > MAX_INSTALLMENTS) {
            throw new IllegalArgumentException(
                    "count must not exceed " + MAX_INSTALLMENTS + ", was " + count);
        }

        int step = frequency.monthsBetweenDueDates();
        List<LocalDate> dates = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            // Always from the anchor, never from the previous date — see the class comment.
            dates.add(firstDueDate.plusMonths((long) index * step));
        }
        return Collections.unmodifiableList(dates);
    }

    /**
     * The due date at a given zero-based position, without materialising the whole schedule.
     */
    public static LocalDate dueDateAt(LocalDate firstDueDate, Frequency frequency, int index) {
        Objects.requireNonNull(firstDueDate, "firstDueDate must not be null");
        Objects.requireNonNull(frequency, "frequency must not be null");
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative, was " + index);
        }
        return firstDueDate.plusMonths((long) index * frequency.monthsBetweenDueDates());
    }

    /**
     * The final due date of a schedule — useful for quoting a plan's end without building it.
     */
    public static LocalDate lastDueDate(LocalDate firstDueDate, Frequency frequency, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1, was " + count);
        }
        return dueDateAt(firstDueDate, frequency, count - 1);
    }

    /**
     * Whole months spanned by a schedule, from its first due date to its last.
     */
    public static int spanInMonths(Frequency frequency, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1, was " + count);
        }
        return (count - 1) * frequency.monthsBetweenDueDates();
    }
}
