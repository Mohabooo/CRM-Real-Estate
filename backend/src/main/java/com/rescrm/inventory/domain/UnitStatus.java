package com.rescrm.inventory.domain;

import java.util.Set;

/**
 * Where a unit sits in its sellable life (doc 18, section 2).
 *
 * <p>{@code available → reserved → sold}, with {@code blocked} off to the side and a return to
 * {@code available} on release, expiry or cancellation.
 *
 * <p>No transition here is performed by a client. Doc 23 is explicit that unit status is a
 * consequence of reservation and deal actions, so every move is made by the system on behalf
 * of one of those, or by operations blocking a unit with a reason.
 */
public enum UnitStatus {

    /** In inventory and sellable. */
    AVAILABLE("available"),

    /** Held by an active reservation. Set by the reservations epic, released back on expiry. */
    RESERVED("reserved"),

    /** Left sellable inventory on deal activation. Returns to available only on cancellation. */
    SOLD("sold"),

    /** Withheld by operations for a stated reason — a survey, a dispute, a show flat. */
    BLOCKED("blocked");

    private final String code;

    UnitStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static UnitStatus fromCode(String code) {
        for (UnitStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown unit status '" + code + "'");
    }

    private Set<UnitStatus> allowedNext() {
        return switch (this) {
            case AVAILABLE -> Set.of(RESERVED, SOLD, BLOCKED);
            // A reserved unit can go straight to sold: doc 18 lists "reserved / available →
            // sold", because a reservation converting to a deal never passes back through
            // available, and requiring it to would open a window for someone else to take it.
            case RESERVED -> Set.of(AVAILABLE, SOLD, BLOCKED);
            case SOLD -> Set.of(AVAILABLE);
            case BLOCKED -> Set.of(AVAILABLE);
        };
    }

    public boolean canTransitionTo(UnitStatus next) {
        return allowedNext().contains(next);
    }

    /** Whether a unit in this state can still be sold to somebody. */
    public boolean isSellable() {
        return this == AVAILABLE || this == RESERVED;
    }

    /** Whether E3-S4's default inventory view shows a unit in this state. */
    public boolean isInDefaultBrowse() {
        return this == AVAILABLE || this == RESERVED;
    }
}
