package com.rescrm.inventory.service;

import com.rescrm.inventory.domain.UnitStatus;

import java.util.UUID;

/**
 * The outcome of an attempt to take a unit out of contention (E3-S5).
 *
 * <p>A value rather than a thrown exception, because losing a race is an ordinary outcome
 * that a reservation or deal flow has to handle, not an error condition. The caller turns it
 * into a 409 with the current status, which is what doc 23 specifies for a unit that is
 * already held.
 */
public record UnitClaim(UUID unitId, boolean won, UnitStatus statusNow) {

    public static UnitClaim won(UUID unitId, UnitStatus statusNow) {
        return new UnitClaim(unitId, true, statusNow);
    }

    public static UnitClaim lost(UUID unitId, UnitStatus statusNow) {
        return new UnitClaim(unitId, false, statusNow);
    }
}
