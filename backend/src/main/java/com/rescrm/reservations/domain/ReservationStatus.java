package com.rescrm.reservations.domain;

import java.util.Set;

/**
 * Where a hold stands (doc 18, section 3).
 *
 * <p>{@code pending → confirmed → converted}, with {@code released}, {@code expired} and
 * {@code cancelled} as the ways it ends instead. Doc 16 is explicit that ending is the
 * normal case: a reservation is informal and frequently abandoned, and it is supposed to
 * expire harmlessly. Only the two live states withhold the unit from everybody else, which
 * is why {@link #isActive()} exists and why the database's C2 index is partial on exactly
 * that pair.
 */
public enum ReservationStatus {

    /** Requested. The unit is spoken for but not yet withheld. */
    PENDING("pending"),

    /** Confirmed, deposit recorded if one was required. The unit is now reserved. */
    CONFIRMED("confirmed"),

    /**
     * A deal was created from it.
     *
     * <p>Declared but unreachable in Epic 4. Doc 18's precondition for this transition is
     * "deal created from it", and deals arrive in Epic 5 along with the column that links
     * the two. A reservation marked converted with no deal behind it would be a lie, so
     * nothing here can reach this state yet.
     */
    CONVERTED("converted"),

    /** Given up deliberately, with a reason. The unit returns to inventory. */
    RELEASED("released"),

    /** Ran out of time. The system's doing, not anybody's decision. */
    EXPIRED("expired"),

    /** Withdrawn before it was ever confirmed, so no unit was ever withheld. */
    CANCELLED("cancelled");

    private final String code;

    ReservationStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ReservationStatus fromCode(String code) {
        for (ReservationStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown reservation status '" + code + "'");
    }

    private Set<ReservationStatus> allowedNext() {
        return switch (this) {
            case PENDING -> Set.of(CONFIRMED, CANCELLED, EXPIRED);
            case CONFIRMED -> Set.of(CONVERTED, RELEASED, EXPIRED);
            case CONVERTED, RELEASED, EXPIRED, CANCELLED -> Set.of();
        };
    }

    public boolean canTransitionTo(ReservationStatus next) {
        return allowedNext().contains(next);
    }

    /** Whether this hold is live, and therefore withholds its unit from everyone else. */
    public boolean isActive() {
        return this == PENDING || this == CONFIRMED;
    }

    /** Whether the unit is currently withheld on this hold's account. */
    public boolean holdsTheUnit() {
        return this == CONFIRMED;
    }
}
