package com.rescrm.reservations.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rescrm.commercialmodel.policy.CollectionPolicy;
import com.rescrm.commercialmodel.policy.CommercialModelPolicies;
import com.rescrm.commercialmodel.policy.DepositMeaning;
import com.rescrm.identity.service.IdentityDirectory;
import com.rescrm.inventory.domain.Project;
import com.rescrm.inventory.domain.Unit;
import com.rescrm.inventory.domain.UnitStatus;
import com.rescrm.inventory.service.ProjectService;
import com.rescrm.inventory.service.UnitClaim;
import com.rescrm.inventory.service.UnitService;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditWriter;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.security.AuthorizationService;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.TenantContext;
import com.rescrm.reservations.domain.Reservation;
import com.rescrm.reservations.domain.ReservationStatus;
import com.rescrm.reservations.repository.ReservationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Time-bound holds on units (Epic 4).
 *
 * <p>The division of labour with {@link UnitService} is the interesting part. A reservation
 * row says who is holding a unit and until when; the unit's own status says whether it is
 * withheld from everyone else. Both have to move together on confirmation, and the unit's
 * side is the one that can be lost to a competitor — so confirmation claims the unit first
 * and only writes the reservation if the claim succeeded. Doing it the other way round would
 * leave a confirmed hold on a unit somebody else had already sold.
 */
@Service
public class ReservationService {

    private final ReservationRepository reservations;
    private final UnitService units;
    private final ProjectService projects;
    private final IdentityDirectory directory;
    private final CommercialModelPolicies policies;
    private final AuthorizationService authorization;
    private final AuditWriter audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ReservationService(ReservationRepository reservations, UnitService units,
                              ProjectService projects, IdentityDirectory directory,
                              CommercialModelPolicies policies,
                              AuthorizationService authorization, AuditWriter audit,
                              ObjectMapper objectMapper, Clock clock) {
        this.reservations = reservations;
        this.units = units;
        this.projects = projects;
        this.directory = directory;
        this.policies = policies;
        this.authorization = authorization;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ E4-S1 place

    /**
     * Places a hold. The unit is spoken for but not yet withheld — that happens on
     * confirmation, which is what E4-S1's acceptance criterion asks for.
     *
     * <p>{@code expiresAt} is optional and defaults from the tenant's settings, so the
     * common case is one fewer decision for the agent.
     */
    @Transactional
    public Reservation place(UUID unitId, UUID leadId, UUID customerId,
                             OffsetDateTime expiresAt, Money depositAmount,
                             boolean depositReceived) {
        authorization.requireAnyRole(Role.SALES_AGENT, Role.TEAM_LEADER, Role.BRANCH_MANAGER,
                Role.OPERATIONS, Role.OWNER, Role.PLATFORM_ADMIN);
        UUID tenantId = TenantContext.require();
        var caller = SecurityContext.require();

        Unit unit = units.get(unitId);
        requireSellable(unit);

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime expiry = expiresAt != null
                ? expiresAt
                : now.plus(settings(tenantId).defaultHold());

        Reservation reservation;
        try {
            reservation = Reservation.place(tenantId, unitId, leadId, customerId,
                    caller.userId(), caller.branchId(), now, expiry);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        recordDepositOn(reservation, tenantId, unit, depositAmount, depositReceived);
        reservation.recordActor(caller.userId(), true);

        Reservation saved = saveExclusive(tenantId, reservation, unitId);
        audit.recordCreation(AuditAction.RESERVATION_PLACED, "Reservation", saved.id(), Map.of(
                "unitId", unitId.toString(),
                "expiresAt", saved.expiresAt().toString(),
                "status", saved.status().code()));
        return saved;
    }

    // --------------------------------------------------------------- E4-S1 confirm

    /**
     * Confirms the hold and withholds the unit.
     *
     * <p>The unit is claimed first. If somebody else took it in the meantime the claim is
     * lost, the reservation is not confirmed, and the caller gets a 409 naming the unit's
     * current status — which is doc 23's answer for a unit that is already held.
     */
    @Transactional
    public Reservation confirm(UUID reservationId) {
        authorization.requireAnyRole(Role.BRANCH_MANAGER, Role.TEAM_LEADER, Role.OPERATIONS,
                Role.OWNER, Role.PLATFORM_ADMIN);
        UUID tenantId = TenantContext.require();
        Reservation reservation = require(tenantId, reservationId);
        requireVisible(reservation);

        UnitClaim claim = units.claimForReservation(reservation.unitId());
        if (!claim.won()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "That unit is no longer available to reserve", Map.of(
                            "unitId", reservation.unitId().toString(),
                            "unitStatus", claim.statusNow().code()));
        }

        apply(() -> reservation.confirm(OffsetDateTime.now(clock)));
        reservation.recordActor(SecurityContext.require().userId(), false);
        Reservation saved = reservations.save(reservation);

        audit.record(AuditAction.RESERVATION_CONFIRMED, "Reservation", saved.id(),
                Map.of("status", ReservationStatus.PENDING.code()),
                Map.of("status", saved.status().code(),
                        "unitId", saved.unitId().toString()), null);
        return saved;
    }

    // --------------------------------------------------------------- E4-S1 release

    /** Doc 18: a reason is required, and the unit goes back to inventory. */
    @Transactional
    public Reservation release(UUID reservationId, String reason) {
        UUID tenantId = TenantContext.require();
        Reservation reservation = require(tenantId, reservationId);
        requireVisible(reservation);
        authorization.requireAnyRole(Role.SALES_AGENT, Role.TEAM_LEADER, Role.BRANCH_MANAGER,
                Role.OPERATIONS, Role.OWNER, Role.PLATFORM_ADMIN);

        boolean heldTheUnit = reservation.status().holdsTheUnit();
        apply(() -> reservation.release(reason, OffsetDateTime.now(clock)));
        reservation.recordActor(SecurityContext.require().userId(), false);
        Reservation saved = reservations.save(reservation);

        if (heldTheUnit) {
            units.returnToInventory(saved.unitId(), UnitStatus.RESERVED, reason);
        }

        audit.record(AuditAction.RESERVATION_RELEASED, "Reservation", saved.id(),
                Map.of("status", ReservationStatus.CONFIRMED.code()),
                Map.of("status", saved.status().code()), reason);
        return saved;
    }

    /** Withdrawn before it was ever confirmed, so no unit was ever withheld. */
    @Transactional
    public Reservation cancel(UUID reservationId, String reason) {
        UUID tenantId = TenantContext.require();
        Reservation reservation = require(tenantId, reservationId);
        requireVisible(reservation);

        apply(() -> reservation.cancel(reason, OffsetDateTime.now(clock)));
        reservation.recordActor(SecurityContext.require().userId(), false);
        Reservation saved = reservations.save(reservation);

        audit.record(AuditAction.RESERVATION_CANCELLED, "Reservation", saved.id(),
                Map.of("status", ReservationStatus.PENDING.code()),
                Map.of("status", saved.status().code()), reason);
        return saved;
    }

    // ---------------------------------------------------------------- E4-S3 extend

    /**
     * E4-S3. A branch manager may extend within the tenant's limit; beyond it takes an
     * owner or operations, which is the "requires owner approval" the story asks for
     * expressed as who may make the call rather than as a separate approval workflow.
     */
    @Transactional
    public Reservation extend(UUID reservationId, OffsetDateTime newExpiry, String reason) {
        authorization.requireAnyRole(Role.BRANCH_MANAGER, Role.OPERATIONS, Role.OWNER,
                Role.PLATFORM_ADMIN);
        UUID tenantId = TenantContext.require();
        Reservation reservation = require(tenantId, reservationId);
        requireVisible(reservation);

        OffsetDateTime previousExpiry = reservation.expiresAt();
        Duration limit = settings(tenantId).maxExtension();
        Duration requested = Duration.between(reservation.originalExpiresAt(), newExpiry);

        if (requested.compareTo(limit) > 0) {
            authorization.requireAnyRole(Role.OPERATIONS, Role.OWNER, Role.PLATFORM_ADMIN);
        }

        apply(() -> reservation.extendTo(newExpiry, OffsetDateTime.now(clock)));
        reservation.recordActor(SecurityContext.require().userId(), false);
        Reservation saved = reservations.save(reservation);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("expiresAt", saved.expiresAt().toString());
        after.put("extensionCount", saved.extensionCount());
        after.put("beyondTenantLimit", requested.compareTo(limit) > 0);
        audit.record(AuditAction.RESERVATION_EXTENDED, "Reservation", saved.id(),
                Map.of("expiresAt", previousExpiry.toString()), after, reason);
        return saved;
    }

    // --------------------------------------------------------------- E4-S4 deposit

    /** E4-S4. The amount is recorded here; the commercial model decides what it means. */
    @Transactional
    public Reservation recordDeposit(UUID reservationId, Money amount, boolean received) {
        authorization.requireAnyRole(Role.SALES_AGENT, Role.TEAM_LEADER, Role.BRANCH_MANAGER,
                Role.OPERATIONS, Role.FINANCE, Role.OWNER, Role.PLATFORM_ADMIN);
        UUID tenantId = TenantContext.require();
        Reservation reservation = require(tenantId, reservationId);
        requireVisible(reservation);

        Unit unit = units.get(reservation.unitId());
        recordDepositOn(reservation, tenantId, unit, amount, received);
        reservation.recordActor(SecurityContext.require().userId(), false);
        Reservation saved = reservations.save(reservation);

        audit.record(AuditAction.RESERVATION_DEPOSIT_RECORDED, "Reservation", saved.id(), null,
                Map.of("amount", amount == null ? "none" : amount.toPlainString(),
                        "meaning", depositMeaning(tenantId, unit).name(),
                        "received", saved.isDepositReceived()), null);
        return saved;
    }

    /** What a deposit on this reservation means, for a client that has to label it. */
    @Transactional(readOnly = true)
    public DepositMeaning depositMeaningFor(UUID reservationId) {
        UUID tenantId = TenantContext.require();
        Reservation reservation = require(tenantId, reservationId);
        return depositMeaning(tenantId, units.get(reservation.unitId()));
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Reservation get(UUID reservationId) {
        Reservation reservation = require(TenantContext.require(), reservationId);
        requireVisible(reservation);
        return reservation;
    }

    /** Already narrowed to the caller's scope; there is no widening parameter. */
    @Transactional(readOnly = true)
    public Page<Reservation> listVisibleToCaller(Pageable pageable) {
        UUID tenantId = TenantContext.require();
        var caller = SecurityContext.require();
        return switch (caller.role().branchScope()) {
            case TENANT_WIDE -> reservations.findAllByTenantIdOrderByReservedAtDesc(
                    tenantId, pageable);
            case OWN_BRANCH -> reservations.findAllByTenantIdAndBranchIdOrderByReservedAtDesc(
                    tenantId, caller.branchId(), pageable);
            case OWN_RECORDS -> reservations.findAllByTenantIdAndAgentUserIdOrderByReservedAtDesc(
                    tenantId, caller.userId(), pageable);
        };
    }

    @Transactional(readOnly = true)
    public Optional<Reservation> activeHoldOn(UUID unitId) {
        return reservations.findActiveForUnit(TenantContext.require(), unitId);
    }

    // ------------------------------------------------------------------ internals

    Reservation require(UUID tenantId, UUID reservationId) {
        return reservations.findByTenantIdAndId(tenantId, reservationId)
                .orElseThrow(() -> ApiException.notFound("Reservation"));
    }

    private void requireVisible(Reservation reservation) {
        authorization.requireRecordVisible(reservation.agentUserId(), reservation.branchId(),
                "Reservation");
    }

    /**
     * Epic 3 left this gate open deliberately: a unit in a draft project could be claimed,
     * and the right place to stop it is where somebody first tries to sell it rather than
     * on the unit itself. This is that place.
     */
    private void requireSellable(Unit unit) {
        if (!unit.status().isSellable()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "That unit is not available to reserve",
                    Map.of("unitStatus", unit.status().code()));
        }
        Project project = projects.get(unit.projectId());
        if (!project.status().isSelling()) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "That unit's project is not selling yet",
                    Map.of("projectStatus", project.status().code()));
        }
    }

    private void recordDepositOn(Reservation reservation, UUID tenantId, Unit unit,
                                 Money amount, boolean received) {
        try {
            reservation.recordDeposit(amount, received);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        // Read for its side effect of validating the model resolves; the meaning itself is
        // reported to the caller rather than changing what is stored. Doc 25: the amount is
        // the same number in both models, and only its interpretation differs.
        depositMeaning(tenantId, unit);
    }

    private DepositMeaning depositMeaning(UUID tenantId, Unit unit) {
        Project project = projects.get(unit.projectId());
        CollectionPolicy policy = policies.collection(project.commercialModel());
        return policy.depositMeaning();
    }

    private ReservationSettings settings(UUID tenantId) {
        return ReservationSettings.from(directory.tenantSettings(tenantId), objectMapper);
    }

    /**
     * Writes the hold, turning C2 into an answer rather than a stack trace.
     *
     * <p>The unique index is what actually prevents two live holds on one unit; this only
     * decides what the loser is told. Checking first and inserting second would be the bug —
     * the gap between the two is exactly where the second hold gets in.
     */
    private Reservation saveExclusive(UUID tenantId, Reservation reservation, UUID unitId) {
        // Read BEFORE the insert, and only so the refusal can name the holder as doc 23
        // requires. It decides nothing — C2 decides. Between this read and the insert
        // another hold can appear, and then the index refuses ours and the caller is told
        // the unit is held without a name: correct, and rarer than the case this serves.
        reservations.findActiveForUnit(tenantId, unitId)
                .ifPresent(held -> {
                    throw new ApiException(ErrorCode.CONFLICT, "That unit is already on hold",
                            Map.of("unitId", unitId.toString(),
                                    "heldByReservationId", held.id().toString(),
                                    "heldUntil", held.expiresAt().toString()));
                });

        try {
            return reservations.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            // Nothing may be queried here. The failed insert has aborted the transaction,
            // and PostgreSQL rejects every further command with "current transaction is
            // aborted" until it ends — so looking up the winner to name it, which is the
            // obvious thing to reach for, replaces a useful 409 with an unhelpful 500.
            throw new ApiException(ErrorCode.CONFLICT,
                    "That unit was taken while this hold was being placed",
                    Map.of("unitId", unitId.toString()));
        }
    }

    private void apply(Runnable change) {
        try {
            change.run();
        } catch (IllegalStateException e) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }
}
