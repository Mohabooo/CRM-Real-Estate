package com.rescrm.inventory.service;

import com.rescrm.inventory.domain.Unit;
import com.rescrm.inventory.domain.UnitStatus;
import com.rescrm.inventory.repository.PhaseRepository;
import com.rescrm.inventory.repository.ProjectRepository;
import com.rescrm.inventory.repository.UnitRepository;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditEvent;
import com.rescrm.platform.audit.AuditEventRepository;
import com.rescrm.platform.audit.AuditWriter;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.security.AuthorizationService;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.security.SystemActor;
import com.rescrm.platform.tenancy.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Units: the inventory browse (E3-S4) and the guard against selling one twice (E3-S5).
 *
 * <p>Unit status is never an argument to anything public here. Doc 23 requires that a client
 * cannot write it, and the strongest way to honour that is to have no method that accepts a
 * status — {@link #block}, {@link #unblock}, {@link #claimForReservation},
 * {@link #claimForSale} and {@link #returnToInventory} are named after events, and each one
 * knows the only status it is allowed to produce.
 */
@Service
public class UnitService {

    /** E3-S4: the default view excludes sold and blocked units. */
    private static final Set<String> DEFAULT_BROWSE_STATUSES =
            Set.of(UnitStatus.AVAILABLE.code(), UnitStatus.RESERVED.code());

    private final UnitRepository units;
    private final ProjectRepository projects;
    private final PhaseRepository phases;
    private final AuditEventRepository auditEvents;
    private final AuthorizationService authorization;
    private final AuditWriter audit;

    public UnitService(UnitRepository units, ProjectRepository projects, PhaseRepository phases,
                       AuditEventRepository auditEvents, AuthorizationService authorization,
                       AuditWriter audit) {
        this.units = units;
        this.projects = projects;
        this.phases = phases;
        this.auditEvents = auditEvents;
        this.authorization = authorization;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ creation

    /** E3-S3, one at a time. The CSV path is {@link UnitImportService}. */
    @Transactional
    public Unit create(UUID projectId, UUID phaseId, String code, String type, BigDecimal areaSqm,
                       String floor, String view, Money listPrice) {
        requireInventoryAdministration();
        Unit saved = createWithin(projectId, phaseId, code, type, areaSqm, floor, view, listPrice);
        audit.recordCreation(AuditAction.UNIT_CREATED, "Unit", saved.id(), Map.of(
                "code", saved.code(),
                "projectId", projectId.toString(),
                "listPrice", saved.listPrice().toPlainString(),
                "status", saved.status().code()));
        return saved;
    }

    /**
     * The creation itself, without the authorization check or the audit entry.
     *
     * <p>Package-private and used by the CSV import, which checks permission once for the
     * file and writes one audit entry for the batch rather than a hundred identical ones.
     */
    Unit createWithin(UUID projectId, UUID phaseId, String code, String type, BigDecimal areaSqm,
                      String floor, String view, Money listPrice) {
        UUID tenantId = TenantContext.require();
        requireProjectInTenant(tenantId, projectId);
        if (phaseId != null) {
            requirePhaseInProject(tenantId, projectId, phaseId);
        }

        Unit unit;
        try {
            unit = Unit.create(tenantId, projectId, phaseId, code, type, areaSqm, floor, view,
                    listPrice);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        unit.recordActor(SecurityContext.require().userId(), true);

        try {
            // saveAndFlush, not save: the insert has to reach the database inside this try
            // block. A plain save only stages it, and the violation would then surface
            // during commit — past this catch, and out to the client as a 500.
            return units.saveAndFlush(unit);
        } catch (DataIntegrityViolationException e) {
            // uniq_unit_code_per_project (C9). Reported rather than silently reused, so a
            // re-run of an import tells the operator which rows were already there.
            throw new ApiException(ErrorCode.CONFLICT,
                    "This project already has a unit with code '" + code.trim() + "'", e);
        }
    }

    // ------------------------------------------------------------------ attributes

    /**
     * Doc 23: list price and attributes, never status. There is no status parameter to
     * reject — the method signature is the rejection.
     */
    @Transactional
    public Unit updateAttributes(UUID unitId, String type, BigDecimal areaSqm, String floor,
                                 String view, Money listPrice, UUID phaseId) {
        requireInventoryAdministration();
        UUID tenantId = TenantContext.require();
        Unit unit = get(unitId);

        if (phaseId != null) {
            requirePhaseInProject(tenantId, unit.projectId(), phaseId);
        }
        try {
            unit.updateAttributes(type, areaSqm, floor, view, listPrice, phaseId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        unit.recordActor(SecurityContext.require().userId(), false);
        Unit saved = units.save(unit);

        audit.record(AuditAction.UNIT_UPDATED, "Unit", saved.id(), null,
                Map.of("listPrice", saved.listPrice().toPlainString(),
                        "type", String.valueOf(saved.type())), null);
        return saved;
    }

    // ------------------------------------------------------------------ blocking

    /** Doc 18 section 2: operations or a branch manager, and a reason is required. */
    @Transactional
    public Unit block(UUID unitId, String reason) {
        authorization.requireAnyRole(Role.OWNER, Role.OPERATIONS, Role.BRANCH_MANAGER,
                Role.PLATFORM_ADMIN);
        Unit unit = get(unitId);
        UnitStatus before = unit.status();
        try {
            unit.block(reason);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION, e.getMessage());
        }
        unit.recordActor(SecurityContext.require().userId(), false);
        Unit saved = units.save(unit);

        audit.record(AuditAction.UNIT_BLOCKED, "Unit", saved.id(),
                Map.of("status", before.code()),
                Map.of("status", saved.status().code()), reason);
        return saved;
    }

    @Transactional
    public Unit unblock(UUID unitId) {
        authorization.requireAnyRole(Role.OWNER, Role.OPERATIONS, Role.BRANCH_MANAGER,
                Role.PLATFORM_ADMIN);
        Unit unit = get(unitId);
        String previousReason = unit.blockedReason();
        try {
            unit.unblock();
        } catch (IllegalStateException e) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION, e.getMessage());
        }
        unit.recordActor(SecurityContext.require().userId(), false);
        Unit saved = units.save(unit);

        audit.record(AuditAction.UNIT_UNBLOCKED, "Unit", saved.id(),
                Map.of("status", UnitStatus.BLOCKED.code(),
                        "blockedReason", String.valueOf(previousReason)),
                Map.of("status", saved.status().code()), null);
        return saved;
    }

    // ------------------------------------------------------------------ the guard

    /**
     * E3-S5. Takes the unit out of contention, or reports that somebody else already has.
     *
     * <p>The entire mechanism is {@link UnitRepository#transitionIfInStatus}: one statement
     * that names the status the caller believes the unit to be in. Two concurrent callers
     * serialise on the row, the loser's predicate no longer matches, and exactly one gets a
     * row count of 1. There is deliberately no read-then-check before it — that gap is
     * precisely where a second claim would slip through, and a check that runs in the gap
     * reads as safety while providing none.
     *
     * <p>{@code REQUIRES_NEW} is not used: the claim must live or die with the reservation or
     * deal that asked for it, or a crash between the two would leave a unit marked sold with
     * nothing sold on it.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public UnitClaim claimForReservation(UUID unitId) {
        return claim(unitId, UnitStatus.AVAILABLE, UnitStatus.RESERVED,
                AuditAction.UNIT_RESERVED);
    }

    /**
     * E3-S5. Sells a unit that is in open inventory.
     *
     * <p>Available only, deliberately. This method used to fall back to
     * {@code reserved -> sold} on the grounds that doc 18 section 2 permits that transition —
     * which it does, but only for the reservation that is holding the unit converting into a
     * deal. Falling back unconditionally meant a second buyer's sale could take a unit
     * actively reserved for a first, which is precisely the double-sell doc 16 says the
     * tenant's reputation depends on preventing. A concurrency test caught it: a
     * confirmation and a sale both won.
     *
     * <p>To sell a unit somebody is holding, release the hold first. That is a decision with
     * a reason attached and an audit entry behind it, which is what taking a unit off a
     * colleague's customer should be.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public UnitClaim claimForSale(UUID unitId) {
        return claim(unitId, UnitStatus.AVAILABLE, UnitStatus.SOLD, AuditAction.UNIT_SOLD);
    }

    /**
     * {@code reserved -> sold}: the hold on this unit converting into a deal.
     *
     * <p>The other half of doc 18 section 2's "reserved / available -> sold". A conversion
     * never passes back through available, because that would open a window for somebody
     * else to take the unit between the release and the sale.
     *
     * <p>Named for the event rather than offered as a flag, and not exposed over HTTP: the
     * caller has to be the module that knows a hold is converting. Epic 5's deal activation
     * reaches it through the reservation named by {@code source_reservation_id}, which is
     * what makes "this buyer's own hold" true rather than assumed.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public UnitClaim claimForSaleOnConversion(UUID unitId) {
        return claim(unitId, UnitStatus.RESERVED, UnitStatus.SOLD, AuditAction.UNIT_SOLD);
    }

    /** Release, expiry or cancellation — the unit re-enters inventory. */
    @Transactional
    public UnitClaim returnToInventory(UUID unitId, UnitStatus from, String reason) {
        requireReturnable(from);
        return claim(unitId, from, UnitStatus.AVAILABLE,
                AuditAction.UNIT_RETURNED_TO_INVENTORY, reason);
    }

    /**
     * The same release, performed by the system rather than by a person (E4-S2).
     *
     * <p>A separate method rather than a flag on the one above, because the difference is
     * not a parameter: this path asks for no authorization and records no actor, and both
     * of those should be visible to whoever is reading it. It expects {@link SystemActor}
     * to have established the tenant — without one, the row-level security policies match
     * nothing and the sweep silently does nothing at all.
     */
    @Transactional
    public UnitClaim releaseOnExpiry(UUID unitId, String reason) {
        return claim(unitId, UnitStatus.RESERVED, UnitStatus.AVAILABLE,
                AuditAction.UNIT_RETURNED_TO_INVENTORY, reason, null);
    }

    private static void requireReturnable(UnitStatus from) {
        if (from != UnitStatus.RESERVED && from != UnitStatus.SOLD) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Only a reserved or sold unit returns to inventory; a blocked one is "
                            + "unblocked instead");
        }
    }

    private UnitClaim claim(UUID unitId, UnitStatus expected, UnitStatus target,
                            AuditAction action) {
        return claim(unitId, expected, target, action, null);
    }

    private UnitClaim claim(UUID unitId, UnitStatus expected, UnitStatus target,
                            AuditAction action, String reason) {
        return claim(unitId, expected, target, action, reason,
                SecurityContext.require().userId());
    }

    /**
     * The transition itself, with the actor passed in rather than read off the thread.
     *
     * <p>Explicit because not every transition has a requester: doc 18 makes SYS the actor
     * when a reservation expires and the unit it held comes back. Those pass null, which is
     * precisely what V2 documents a null {@code actor_user_id} to mean.
     */
    private UnitClaim claim(UUID unitId, UnitStatus expected, UnitStatus target,
                            AuditAction action, String reason, UUID actor) {
        UUID tenantId = TenantContext.require();

        // Confirms the unit is this tenant's before anything else, so a cross-tenant id
        // answers 404 rather than a silent "you lost the race".
        Unit unit = get(unitId);

        int changed = units.transitionIfInStatus(tenantId, unitId, expected.code(),
                target.code(), actor);
        if (changed == 0) {
            return UnitClaim.lost(unitId, get(unitId).status());
        }

        audit.record(action, "Unit", unitId,
                Map.of("status", expected.code()),
                Map.of("status", target.code(), "code", unit.code()), reason);
        return UnitClaim.won(unitId, target);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Unit get(UUID unitId) {
        return units.findByTenantIdAndId(TenantContext.require(), unitId)
                .orElseThrow(() -> ApiException.notFound("Unit"));
    }

    /**
     * E3-S4. Sold and blocked units are excluded unless the caller asks for them explicitly,
     * and "explicitly" means naming the statuses — there is no {@code all=true} shortcut,
     * because the point of the default is that an agent never quotes a unit that is gone.
     */
    @Transactional(readOnly = true)
    public Page<Unit> browse(Collection<UnitStatus> statuses, UUID projectId, UUID phaseId,
                             String type, Money minPrice, Money maxPrice, BigDecimal minArea,
                             BigDecimal maxArea, Pageable pageable) {
        Collection<String> statusCodes = statuses == null || statuses.isEmpty()
                ? DEFAULT_BROWSE_STATUSES
                : statuses.stream().map(UnitStatus::code).toList();

        // Lower-cased here rather than in the query: the comparison is case-insensitive
        // because nobody types "Apartment" the same way twice, and doing the folding on the
        // parameter leaves one function call in the SQL instead of two.
        String typeFilter = blankToNull(type);
        return units.findFiltered(TenantContext.require(), statusCodes, projectId, phaseId,
                typeFilter == null ? null : typeFilter.toLowerCase(Locale.ROOT),
                minPrice, maxPrice, minArea, maxArea, pageable);
    }

    /**
     * Doc 23's {@code GET /units/{id}/history}.
     *
     * <p>Served from the audit trail rather than from a table of its own. Doc 18 marks every
     * unit transition as audited, so a second history table would be the same facts written
     * twice — and the two would disagree the first time a transition was added in one place
     * and not the other.
     */
    @Transactional(readOnly = true)
    public List<AuditEvent> history(UUID unitId) {
        UUID tenantId = TenantContext.require();
        get(unitId);
        return auditEvents.findTrail(tenantId, "Unit", unitId);
    }

    @Transactional(readOnly = true)
    public long countInProject(UUID projectId, UnitStatus status) {
        UUID tenantId = TenantContext.require();
        requireProjectInTenant(tenantId, projectId);
        return status == null
                ? units.countByTenantIdAndProjectId(tenantId, projectId)
                : units.countByTenantIdAndProjectIdAndStatus(tenantId, projectId, status.code());
    }

    // ------------------------------------------------------------------ internals

    void requireProjectInTenant(UUID tenantId, UUID projectId) {
        if (projectId == null || !projects.existsByTenantIdAndId(tenantId, projectId)) {
            throw ApiException.notFound("Project");
        }
    }

    private void requirePhaseInProject(UUID tenantId, UUID projectId, UUID phaseId) {
        var phase = phases.findByTenantIdAndId(tenantId, phaseId)
                .orElseThrow(() -> ApiException.notFound("Phase"));
        if (!phase.projectId().equals(projectId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "That phase belongs to a different project");
        }
    }

    private void requireInventoryAdministration() {
        authorization.requireAnyRole(Role.OWNER, Role.OPERATIONS, Role.PLATFORM_ADMIN);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
