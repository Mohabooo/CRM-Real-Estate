package com.rescrm.inventory.domain;

import com.rescrm.platform.api.CodedEnum;

import java.util.Set;

/**
 * A project's own lifecycle (doc 16, section 7): {@code draft → active → sold_out | inactive}.
 *
 * <p>Distinct from the commercial model, which is fixed at creation and is not a lifecycle at
 * all. A project moves through these states; it never changes model.
 */
public enum ProjectStatus implements CodedEnum {

    /** Being set up. Units may be loaded; nothing is sellable yet. */
    DRAFT("draft"),

    /** Selling. */
    ACTIVE("active"),

    /** Every unit is gone. A reporting state, not a lock. */
    SOLD_OUT("sold_out"),

    /** Withdrawn, paused, or the developer relationship ended. */
    INACTIVE("inactive");

    private final String code;

    ProjectStatus(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    /**
     * Accepts the wire code, and the Java name as a fallback.
     *
     * <p>A client that echoes back a value it was given should not get a 400 for it, so the
     * code is what a request carries and the name is only a fallback. Deserialization goes
     * through {@code CodedEnumModule}, which does this for every {@code CodedEnum} rather
     * than one annotation at a time.
     */
    public static ProjectStatus fromCode(String code) {
        for (ProjectStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        for (ProjectStatus status : values()) {
            if (status.name().equalsIgnoreCase(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown project status '" + code + "'");
    }

    private Set<ProjectStatus> allowedNext() {
        return switch (this) {
            case DRAFT -> Set.of(ACTIVE, INACTIVE);
            case ACTIVE -> Set.of(SOLD_OUT, INACTIVE);
            // Reopened when a cancellation returns a unit to inventory, or when a new phase
            // is released into an existing project.
            case SOLD_OUT -> Set.of(ACTIVE, INACTIVE);
            case INACTIVE -> Set.of(ACTIVE);
        };
    }

    public boolean canTransitionTo(ProjectStatus next) {
        return allowedNext().contains(next);
    }

    /** Whether units in this project may be reserved or sold. */
    public boolean isSelling() {
        return this == ACTIVE;
    }
}
