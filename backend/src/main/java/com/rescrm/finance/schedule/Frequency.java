package com.rescrm.finance.schedule;

import com.rescrm.platform.api.CodedEnum;

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
public enum Frequency implements CodedEnum {

    MONTHLY(1, "monthly"),
    QUARTERLY(3, "quarterly"),
    SEMI_ANNUAL(6, "semi_annual"),
    ANNUAL(12, "annual");

    private final int monthsBetweenDueDates;
    private final String code;

    Frequency(int monthsBetweenDueDates, String code) {
        this.monthsBetweenDueDates = monthsBetweenDueDates;
        this.code = code;
    }

    /**
     * The spelling this frequency takes in {@code customer_payment_plans.frequency} and on
     * the wire. {@link CodedEnum} is a {@code platform.api} interface with no framework
     * dependency of its own, so implementing it here costs this package none of the
     * independence the architecture rule protects — and it is what lets a request body carry
     * {@code "quarterly"} rather than {@code "QUARTERLY"}.
     */
    @Override
    public String code() {
        return code;
    }

    public int monthsBetweenDueDates() {
        return monthsBetweenDueDates;
    }

    /** Number of due dates this frequency produces in a year. */
    public int occurrencesPerYear() {
        return 12 / monthsBetweenDueDates;
    }
}
