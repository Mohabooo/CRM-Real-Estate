package com.rescrm.crm.domain;

import java.util.Set;

/**
 * Where a lead sits in the funnel (doc 18, section 1).
 *
 * <p>The chain {@code new → assigned → contacted → qualified → reserved} is the stage; leaving
 * the funnel is {@link LeadStatus}. Splitting them is what lets a converted lead keep the
 * stage it reached, and what makes doc 22's {@code WHERE status = 'active'} index meaningful.
 */
public enum LeadStage {

    NEW("new"),
    ASSIGNED("assigned"),
    CONTACTED("contacted"),
    QUALIFIED("qualified"),

    /**
     * Set by the reservations epic, not from here. Doc 18 requires a reservation to exist
     * before a lead reaches it, and reservations do not exist yet.
     */
    RESERVED("reserved");

    private final String code;

    LeadStage(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static LeadStage fromCode(String code) {
        for (LeadStage stage : values()) {
            if (stage.code.equals(code)) {
                return stage;
            }
        }
        throw new IllegalArgumentException("Unknown lead stage '" + code + "'");
    }

    private Set<LeadStage> allowedNext() {
        return switch (this) {
            case NEW -> Set.of(ASSIGNED);
            case ASSIGNED -> Set.of(CONTACTED);
            case CONTACTED -> Set.of(QUALIFIED);
            case QUALIFIED -> Set.of(RESERVED);
            case RESERVED -> Set.of();
        };
    }

    public boolean canTransitionTo(LeadStage next) {
        return allowedNext().contains(next);
    }

    /** Whether a lead at this stage must have an owner, matching the database check. */
    public boolean requiresOwner() {
        return this != NEW;
    }
}
