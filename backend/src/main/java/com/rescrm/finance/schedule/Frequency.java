package com.rescrm.finance.schedule;

/**
 * Installment frequencies supported by the payment-plan engine (doc 17, rule R-INST-5).
 *
 * <p>Each constant carries the number of months between consecutive due dates. Expressing
 * every frequency in months is what lets {@link ScheduleDateCalculator} use one date rule
 * for all of them, rather than a branch per frequency.
 *
 * <p>The set matches the market conventions documented in doc 02 §11: Egyptian off-plan
 * plans are predominantly monthly or quarterly, with semi-annual and annual also in use.
 * Custom/irregular frequencies are a Phase 2 concern and are deliberately absent.
 */
public enum Frequency {

    MONTHLY(1),
    QUARTERLY(3),
    SEMI_ANNUAL(6),
    ANNUAL(12);

    private final int monthsBetweenDueDates;

    Frequency(int monthsBetweenDueDates) {
        this.monthsBetweenDueDates = monthsBetweenDueDates;
    }

    public int monthsBetweenDueDates() {
        return monthsBetweenDueDates;
    }

    /** Number of due dates this frequency produces in a year. */
    public int occurrencesPerYear() {
        return 12 / monthsBetweenDueDates;
    }
}
