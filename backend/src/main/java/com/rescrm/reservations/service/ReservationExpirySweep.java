package com.rescrm.reservations.service;

import com.rescrm.inventory.service.UnitClaim;
import com.rescrm.inventory.service.UnitService;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditWriter;
import com.rescrm.platform.security.SystemActor;
import com.rescrm.platform.tenancy.PlatformTenantRegistry;
import com.rescrm.platform.tenancy.TenantContext;
import com.rescrm.reservations.domain.Reservation;
import com.rescrm.reservations.domain.ReservationStatus;
import com.rescrm.reservations.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Expires stale holds and frees the units they were withholding (E4-S2).
 *
 * <p>Three things about this are deliberate.
 *
 * <p>It runs per tenant, under the ordinary row-level security policy, having asked
 * {@link PlatformTenantRegistry} which tenants exist. The alternative — one cross-tenant
 * query — would return nothing in production, because an unset tenant matches no rows, while
 * passing its tests, because the test connection is a superuser and bypasses RLS entirely.
 *
 * <p>It runs as nobody. {@link SystemActor} establishes the tenant and guarantees no
 * principal, so the audit entries record a null actor — which V2 already documents as
 * meaning a SYS transition, and doc 18 names SYS as the actor for exactly this.
 *
 * <p>Each reservation is its own transaction, and a failing tenant is caught and recorded
 * rather than allowed to abort the pass. A sweep that stops at the first bad row leaves every
 * later unit held by a hold that ended days ago, which is the failure this exists to prevent.
 *
 * <p>Notifying the agent and their manager is the other half of E4-S2 and is not implemented:
 * notifications are Epic 10. The seam is {@link #onExpired}, which is called for every
 * expiry with everything a notification would need.
 */
@Service
public class ReservationExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirySweep.class);

    private final PlatformTenantRegistry tenants;
    private final ReservationRepository reservations;
    private final ReservationExpiry expiry;
    private final Clock clock;

    public ReservationExpirySweep(PlatformTenantRegistry tenants,
                                  ReservationRepository reservations,
                                  ReservationExpiry expiry, Clock clock) {
        this.tenants = tenants;
        this.reservations = reservations;
        this.expiry = expiry;
        this.clock = clock;
    }

    /** One pass over every active tenant. */
    public ExpirySweepResult sweep() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<UUID> expired = new ArrayList<>();
        List<UUID> released = new ArrayList<>();
        List<UUID> failed = new ArrayList<>();

        List<UUID> tenantIds = tenants.activeTenantIds();
        for (UUID tenantId : tenantIds) {
            try {
                SystemActor.runFor(tenantId, () -> sweepOneTenant(tenantId, now, expired,
                        released, failed));
            } catch (RuntimeException e) {
                // One tenant's problem is not the others'.
                failed.add(tenantId);
                log.warn("Reservation expiry sweep failed for tenant {}", tenantId, e);
            }
        }
        return new ExpirySweepResult(tenantIds.size(), List.copyOf(expired),
                List.copyOf(released), List.copyOf(failed));
    }

    private void sweepOneTenant(UUID tenantId, OffsetDateTime now, List<UUID> expired,
                                List<UUID> released, List<UUID> failed) {
        for (Reservation due : reservations.findExpired(tenantId, now)) {
            try {
                boolean unitFreed = expiry.expireOne(due.id(), now);
                expired.add(due.id());
                if (unitFreed) {
                    released.add(due.unitId());
                }
                onExpired(due);
            } catch (RuntimeException e) {
                failed.add(tenantId);
                log.warn("Could not expire reservation {} for tenant {}", due.id(), tenantId, e);
            }
        }
    }

    /**
     * The seam where Epic 10's notification hooks in.
     *
     * <p>E4-S2 asks for the agent and their manager to be told. That needs a notifications
     * module, which arrives in Epic 10; until then this is called for every expiry with the
     * reservation in hand, and does nothing. An empty method is an honest placeholder — a
     * half-written email sender would not be.
     */
    protected void onExpired(Reservation reservation) {
        log.info("Reservation {} on unit {} expired at {}; agent {} would be notified",
                reservation.id(), reservation.unitId(), reservation.expiresAt(),
                reservation.agentUserId());
    }

    /**
     * One reservation, one transaction.
     *
     * <p>A separate bean for the same reason the CSV import has one: {@code REQUIRES_NEW} is
     * applied by a proxy, and a self-invoked method would quietly share the caller's
     * transaction, so one failure would roll back every expiry that preceded it.
     */
    @Service
    public static class ReservationExpiry {

        private final ReservationRepository reservations;
        private final UnitService units;
        private final AuditWriter audit;

        public ReservationExpiry(ReservationRepository reservations, UnitService units,
                                 AuditWriter audit) {
            this.reservations = reservations;
            this.units = units;
            this.audit = audit;
        }

        /** @return whether a unit was actually freed */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public boolean expireOne(UUID reservationId, OffsetDateTime now) {
            Reservation reservation = reservations
                    .findByTenantIdAndId(TenantContext.require(), reservationId)
                    .orElse(null);
            if (reservation == null || !reservation.status().isActive()) {
                // Released or confirmed away between the scan and now. Not an error.
                return false;
            }

            boolean heldTheUnit = reservation.status().holdsTheUnit();
            ReservationStatus before = reservation.status();
            reservation.expire(now);
            Reservation saved = reservations.save(reservation);

            boolean freed = false;
            if (heldTheUnit) {
                UnitClaim claim = units.releaseOnExpiry(saved.unitId(),
                        "Reservation expired at " + saved.expiresAt());
                freed = claim.won();
            }

            audit.record(AuditAction.RESERVATION_EXPIRED, "Reservation", saved.id(),
                    Map.of("status", before.code()),
                    Map.of("status", saved.status().code(),
                            "unitId", saved.unitId().toString(),
                            "unitReleased", freed),
                    "Expiry sweep");
            return freed;
        }
    }
}
