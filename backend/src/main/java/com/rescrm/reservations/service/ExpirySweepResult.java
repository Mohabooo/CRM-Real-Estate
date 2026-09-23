package com.rescrm.reservations.service;

import java.util.List;
import java.util.UUID;

/**
 * What one pass of the expiry sweep did (E4-S2).
 *
 * <p>Returned rather than merely logged so the sweep is testable without reading log output,
 * and so a future operator endpoint can report it. {@code failedTenants} exists because one
 * tenant's bad data must not stop the others: a sweep that aborts halfway leaves units held
 * by reservations that ended days ago.
 */
public record ExpirySweepResult(int tenantsSwept, List<UUID> expired, List<UUID> unitsReleased,
                                List<UUID> failedTenants) {

    public int expiredCount() {
        return expired.size();
    }

    public boolean isClean() {
        return failedTenants.isEmpty();
    }
}
