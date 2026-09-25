package com.rescrm.finance.schedule;

import com.rescrm.platform.api.CodedEnum;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * What a row in a schedule is for (doc 22, {@code installments.kind}).
 *
 * <p>All three are installments in the same table and the same sequence, which is what makes
 * R-PLAN-4 expressible as one sum. A down payment held somewhere else would have to be added
 * back by every caller that wanted to check the total, and one of them would forget.
 */
public enum InstallmentKind implements CodedEnum {

    /** Due at the deal date. Exactly one per plan, and zero-valued plans still carry it. */
    DOWN_PAYMENT("down_payment"),

    /** The financed amount, divided by the plan's count at its frequency. */
    INSTALLMENT("installment"),

    /** A percentage of net held back to delivery. Its date is the project's, not a cadence. */
    DELIVERY("delivery");

    private final String code;

    InstallmentKind(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    @JsonCreator
    public static InstallmentKind fromCode(String code) {
        for (InstallmentKind kind : values()) {
            if (kind.code.equals(code) || kind.name().equalsIgnoreCase(code)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown installment kind '" + code + "'");
    }
}
