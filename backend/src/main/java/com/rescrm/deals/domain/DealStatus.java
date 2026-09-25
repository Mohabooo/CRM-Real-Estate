package com.rescrm.deals.domain;

import com.rescrm.platform.api.CodedEnum;

import java.util.Set;

/**
 * A deal's lifecycle, transcribed from doc 18 section 4's transition table.
 *
 * <p>States: {@code draft → active → completed}, with {@code cancelled} reachable from
 * draft and — in P2 — from active.
 *
 * <p>The table in doc 18 is the specification, and nothing here is inferred from what a
 * state is called. Each transition below appears in that table with an actor, a
 * precondition and a result; a transition that does not appear is not permitted, and two
 * that do appear are deliberately unimplemented for reasons recorded in
 * {@code DealService} rather than silently allowed.
 */
public enum DealStatus implements CodedEnum {

    /** A shell: unit, customer, frozen gross value, and whatever discounts have been agreed. */
    DRAFT("draft"),

    /** Signed. The unit is sold, the schedule is live, entitlements exist. */
    ACTIVE("active"),

    /**
     * Closed out. Mechanical under own-inventory once every installment is paid; a manual
     * act under brokered inventory, because the tenant never learns when the customer
     * finished paying the developer (doc 18 section 4, rev 2).
     */
    COMPLETED("completed"),

    /** Abandoned before signing, or unwound after it. */
    CANCELLED("cancelled");

    private final String code;

    DealStatus(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    public static DealStatus fromCode(String code) {
        for (DealStatus status : values()) {
            if (status.code.equals(code) || status.name().equalsIgnoreCase(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown deal status '" + code + "'");
    }

    /**
     * Exactly the moves doc 18 section 4 lists, and no others.
     *
     * <p>{@code completed → cancelled} is absent because the table does not contain it: a
     * deal that closed out is history, and unwinding it is a different act from cancelling
     * one in flight.
     */
    private Set<DealStatus> allowedNext() {
        return switch (this) {
            case DRAFT -> Set.of(ACTIVE, CANCELLED);
            case ACTIVE -> Set.of(COMPLETED, CANCELLED);
            case COMPLETED, CANCELLED -> Set.of();
        };
    }

    public boolean canTransitionTo(DealStatus next) {
        return allowedNext().contains(next);
    }

    /** Whether this deal still holds its unit — the states C1 treats as live. */
    public boolean holdsTheUnit() {
        return this == DRAFT || this == ACTIVE;
    }

    /** Whether the deal's terms may still be edited (doc 18: "draft → draft (edit)"). */
    public boolean isEditable() {
        return this == DRAFT;
    }

    /** Whether financial aggregates count this deal (R-SCOPE-1 excludes cancelled). */
    public boolean countsTowardsFinancials() {
        return this != CANCELLED;
    }
}
