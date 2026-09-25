package com.rescrm.deals.domain;

import com.rescrm.platform.api.CodedEnum;

/**
 * What an expected obligation is, before any money exists.
 *
 * <p>Two states, matching V7's check constraint. The paid states belong with the allocation
 * machinery that produces them, in Epic 6: an installment here has no way to become paid,
 * and offering the word would invite somebody to set it by hand.
 */
public enum InstallmentStatus implements CodedEnum {

    /** Due, or not yet due. Everything Epic 5 generates starts here. */
    PENDING("pending"),

    /** Cancelled out, with a reason. Excluded from every aggregate (R-SCOPE-1). */
    VOID("void");

    private final String code;

    InstallmentStatus(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    public static InstallmentStatus fromCode(String code) {
        for (InstallmentStatus status : values()) {
            if (status.code.equals(code) || status.name().equalsIgnoreCase(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown installment status '" + code + "'");
    }

    /** Whether aggregates count this row. R-SCOPE-1 excludes voided ones. */
    public boolean countsTowardsFinancials() {
        return this != VOID;
    }
}
