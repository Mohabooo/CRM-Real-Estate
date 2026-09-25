package com.rescrm.deals.domain;

import com.rescrm.platform.api.CodedEnum;

import java.util.Set;

/**
 * A customer payment plan's lifecycle, from doc 18 section 5.
 *
 * <p>States: {@code draft → active → superseded}, with {@code closed} and {@code cancelled}
 * as terminal outcomes. The plan is model-agnostic, as that section's heading says: a
 * brokered plan moves through the same states as an own-inventory one, it just does not
 * drive collection (doc 25 section 4 — "generated, reference only").
 */
public enum PlanStatus implements CodedEnum {

    /** Generated and revisable. Regeneration replaces it (TPL-006). */
    DRAFT("draft"),

    /** Live, on an activated deal. Its installments are what the customer owes. */
    ACTIVE("active"),

    /** Replaced by a later version. P2; nothing in Epic 5 produces this. */
    SUPERSEDED("superseded"),

    /** Finished. Mechanical under own-inventory, manual under brokered. */
    CLOSED("closed"),

    /** The deal it belonged to was cancelled. */
    CANCELLED("cancelled");

    private final String code;

    PlanStatus(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    public static PlanStatus fromCode(String code) {
        for (PlanStatus status : values()) {
            if (status.code.equals(code) || status.name().equalsIgnoreCase(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown plan status '" + code + "'");
    }

    private Set<PlanStatus> allowedNext() {
        return switch (this) {
            case DRAFT -> Set.of(ACTIVE, CANCELLED);
            case ACTIVE -> Set.of(SUPERSEDED, CLOSED, CANCELLED);
            case SUPERSEDED, CLOSED, CANCELLED -> Set.of();
        };
    }

    public boolean canTransitionTo(PlanStatus next) {
        return allowedNext().contains(next);
    }

    /**
     * Whether the schedule may still be regenerated.
     *
     * <p>Doc 18 section 5 allows {@code draft → draft (regenerate)} only while there are no
     * allocations. There are none in Epic 5 — payments arrive with Epic 6 — so the condition
     * that can be evaluated now is the plan's own state. R-INST-9 tightens this once
     * allocations exist, and the tightening belongs with them rather than being guessed at
     * here.
     */
    public boolean isRevisable() {
        return this == DRAFT;
    }
}
