package com.rescrm.deals.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rescrm.commercialmodel.policy.CollectionPolicy;
import com.rescrm.commercialmodel.policy.CommercialModelPolicies;
import com.rescrm.crm.domain.Customer;
import com.rescrm.crm.service.CustomerService;
import com.rescrm.deals.domain.Concession;
import com.rescrm.deals.domain.Deal;
import com.rescrm.deals.domain.DealDiscount;
import com.rescrm.deals.domain.DealStatus;
import com.rescrm.deals.repository.DealDiscountRepository;
import com.rescrm.deals.repository.DealRepository;
import com.rescrm.identity.service.IdentityDirectory;
import com.rescrm.inventory.domain.Project;
import com.rescrm.inventory.domain.Unit;
import com.rescrm.inventory.domain.UnitStatus;
import com.rescrm.inventory.service.ProjectService;
import com.rescrm.inventory.service.UnitClaim;
import com.rescrm.inventory.service.UnitService;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditEvent;
import com.rescrm.platform.audit.AuditEventRepository;
import com.rescrm.platform.audit.AuditWriter;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.security.AuthenticatedPrincipal;
import com.rescrm.platform.security.AuthorizationService;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.TenantContext;
import com.rescrm.reservations.domain.Reservation;
import com.rescrm.reservations.domain.ReservationStatus;
import com.rescrm.reservations.service.ReservationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The deal lifecycle (Epic 5), transcribed from doc 18 section 4 rather than inferred.
 *
 * <p>Every method below is one row of that table. Two rows it lists are deliberately not
 * implemented, and both refusals are explicit rather than silent:
 *
 * <ul>
 *   <li>{@code active → cancelled} is marked "P2 via Cancellation" and its result column
 *       requires installments voided, the unit returned, and commissions clawed back. The
 *       first two are within reach; the third is not, because commissions do not exist until
 *       Epic 8. Implementing two thirds of an unwind would leave a cancelled deal still
 *       carrying entitlements somebody gets paid on. {@link #cancel} refuses it by name.
 *   <li>{@code active → completed} under own inventory requires every installment paid, and
 *       payments arrive in Epic 6. Under brokered inventory completion is a human's decision
 *       and is reachable now (E5-S8). The commercial model decides, through a policy.
 * </ul>
 *
 * <p>Commissions are absent throughout. Doc 18 lists them in activation's result column and
 * doc 20 puts them in Epic 8; creating none is the honest state, and the alternative —
 * creating placeholders — would put numbers in front of people who would read them.
 *
 * <p>The commercial model never touches the money. It decides who collects, whether a
 * down-payment confirmation exists and how completion is reached; the schedule arithmetic is
 * identical either way, which is why nothing here branches on the model and everything asks
 * a policy instead.
 */
@Service
public class DealService {

    private final DealRepository deals;
    private final DealDiscountRepository discounts;
    private final PaymentPlanService plans;
    private final UnitService units;
    private final ProjectService projects;
    private final CustomerService customers;
    private final ReservationService reservations;
    private final IdentityDirectory directory;
    private final CommercialModelPolicies policies;
    private final AuditEventRepository auditEvents;
    private final AuthorizationService authorization;
    private final AuditWriter audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DealService(DealRepository deals, DealDiscountRepository discounts,
                       PaymentPlanService plans, UnitService units, ProjectService projects,
                       CustomerService customers, ReservationService reservations,
                       IdentityDirectory directory, CommercialModelPolicies policies,
                       AuditEventRepository auditEvents, AuthorizationService authorization,
                       AuditWriter audit, ObjectMapper objectMapper, Clock clock) {
        this.deals = deals;
        this.discounts = discounts;
        this.plans = plans;
        this.units = units;
        this.projects = projects;
        this.customers = customers;
        this.reservations = reservations;
        this.directory = directory;
        this.policies = policies;
        this.auditEvents = auditEvents;
        this.authorization = authorization;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ E5-S1 draft

    /**
     * Doc 18: "— → draft". Preconditions: the unit is available or held by the same party,
     * and the customer exists.
     *
     * <p>"Held by the same party" is checked against the reservation the caller names, not
     * assumed from the unit being reserved. A unit somebody else is holding is not available
     * to draft against, and the difference between those two readings is a colleague's
     * customer losing their unit.
     *
     * <p>The gross value is the unit's list price, copied (R-VAL-1). Not referenced — copied.
     * A price change on the unit tomorrow does not reach a deal struck today, which is the
     * whole reason the column exists on this table.
     */
    @Transactional
    public Deal draft(UUID unitId, UUID customerId, UUID sourceReservationId, LocalDate dealDate) {
        requireSellingRole();
        UUID tenantId = TenantContext.require();
        AuthenticatedPrincipal caller = SecurityContext.require();

        Unit unit = units.get(unitId);
        Customer customer = customers.get(customerId);
        Project project = projects.get(unit.projectId());

        requireProjectSelling(project);
        Optional<Reservation> hold = requireUnitDraftable(unit, sourceReservationId);

        Deal deal;
        try {
            deal = Deal.draft(tenantId, unitId, customer.id(), caller.userId(), caller.branchId(),
                    hold.map(Reservation::id).orElse(null),
                    dealDate == null ? LocalDate.now(clock) : dealDate,
                    unit.listPrice(), project.commercialModel());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        deal.recordActor(caller.userId(), true);

        Deal saved = saveExclusive(deal, unitId);
        audit.recordCreation(AuditAction.DEAL_DRAFTED, "Deal", saved.id(), Map.of(
                "unitId", unitId.toString(),
                "customerId", customerId.toString(),
                "grossValue", saved.grossValue().toPlainString(),
                "commercialModel", saved.commercialModel(),
                "status", saved.status().code()));
        return saved;
    }

    // ------------------------------------------------------------------ E5-S2 discounts

    /**
     * Doc 18: "draft → draft (edit)", audited.
     *
     * <p>Each concession is stored individually and the deal's total is their sum against
     * GROSS (R-DISC-2). The total is recomputed from every row rather than added to what the
     * deal last held, because adding would compound: 5% then 10% would come to 14.5% of
     * gross instead of 15%, and nobody would notice until a reconciliation.
     */
    @Transactional
    public Deal addDiscount(UUID dealId, Concession concession, String reason) {
        requireSellingRole();
        UUID tenantId = TenantContext.require();
        Deal deal = requireVisibleDeal(tenantId, dealId);
        requireEditable(deal, "Discounts");

        DealDiscount discount;
        try {
            discount = DealDiscount.of(tenantId, dealId, concession, deal.grossValue(), reason);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        discount.recordActor(SecurityContext.require().userId());
        discounts.save(discount);

        Deal saved = restateTotalDiscount(tenantId, deal);
        audit.record(AuditAction.DEAL_DISCOUNT_ADDED, "Deal", saved.id(),
                Map.of("totalDiscount", deal.totalDiscount().toPlainString()),
                Map.of("kind", discount.kind().code(),
                        "amount", discount.amount().toPlainString(),
                        "totalDiscount", saved.totalDiscount().toPlainString(),
                        "netValue", saved.netValue().toPlainString()), reason);
        return saved;
    }

    /** Doc 18: "draft → draft (edit)". A concession on a signed deal is part of the price. */
    @Transactional
    public Deal removeDiscount(UUID dealId, UUID discountId) {
        requireSellingRole();
        UUID tenantId = TenantContext.require();
        Deal deal = requireVisibleDeal(tenantId, dealId);
        requireEditable(deal, "Discounts");

        Money before = deal.totalDiscount();
        if (discounts.deleteFromDraft(tenantId, dealId, discountId) == 0) {
            throw ApiException.notFound("Discount");
        }
        Deal saved = restateTotalDiscount(tenantId, deal);

        audit.record(AuditAction.DEAL_DISCOUNT_REMOVED, "Deal", saved.id(),
                Map.of("totalDiscount", before.toPlainString()),
                Map.of("discountId", discountId.toString(),
                        "totalDiscount", saved.totalDiscount().toPlainString(),
                        "netValue", saved.netValue().toPlainString()), null);
        return saved;
    }

    /**
     * R-DISC-5. A manager approves the concession that stands on the deal right now.
     *
     * <p>Approval is of a number, not of a deal, so changing the discount clears it — which
     * {@code Deal.restateDiscount} does rather than this method remembering to. An approval
     * that survived an edit would be a manager's signature on a figure they never saw.
     */
    @Transactional
    public Deal approveDiscount(UUID dealId) {
        authorization.requireAnyRole(Role.BRANCH_MANAGER, Role.OPERATIONS, Role.OWNER,
                Role.PLATFORM_ADMIN);
        UUID tenantId = TenantContext.require();
        Deal deal = requireVisibleDeal(tenantId, dealId);
        requireEditable(deal, "Discount approval");

        if (deal.totalDiscount().isZero()) {
            throw ApiException.businessRule(
                    "There is no discount on this deal to approve", Map.of());
        }
        UUID approver = SecurityContext.require().userId();
        apply(() -> deal.approveDiscount(approver, OffsetDateTime.now(clock)));
        deal.recordActor(approver, false);
        Deal saved = deals.save(deal);

        audit.record(AuditAction.DEAL_DISCOUNT_APPROVED, "Deal", saved.id(),
                Map.of("approved", false),
                Map.of("approved", true,
                        "grossValue", saved.grossValue().toPlainString(),
                        "totalDiscount", saved.totalDiscount().toPlainString(),
                        "netValue", saved.netValue().toPlainString()), null);
        return saved;
    }

    /**
     * Whether the discount standing on this deal needs a manager's approval before it can be
     * activated, per the tenant's configured threshold.
     */
    @Transactional(readOnly = true)
    public boolean approvalRequiredFor(Deal deal) {
        return settings(deal.tenantId()).approvalRequiredFor(deal.grossValue(),
                deal.totalDiscount());
    }

    // ------------------------------------------------------------------ E5-S5 activation

    /**
     * Doc 18: "draft → active". One transaction, four preconditions, three side effects.
     *
     * <p>The preconditions, in the table's own order: ① a plan has been generated, ② R-PLAN-4
     * holds over it, ③ the discount is approved where approval was required, and ④ the unit
     * has not been sold elsewhere. The first two are asserted inside
     * {@link PaymentPlanService#activateFor}, over the rows as they are stored now rather
     * than as they were when generated. The third is the deal's own to check. The fourth is
     * the unit claim, and it is not a check at all — it is a conditional update whose row
     * count is the answer.
     *
     * <p>Order matters and is chosen, not incidental. The unit is claimed BEFORE the deal
     * and plan are marked active, because the claim is the step that can be lost to a
     * competitor: two activations racing for one unit serialise on that row, and exactly one
     * gets a count of one. Marking the deal active first and then discovering the unit was
     * gone would mean rolling back a state the other transaction may already have read.
     *
     * <p>Everything here lives or dies together. A crash after the unit is sold and before
     * the plan goes live would leave a unit off the market with no schedule behind it; the
     * shared transaction is what makes that impossible, and it is why nothing in this path
     * uses {@code REQUIRES_NEW}.
     */
    @Transactional
    public Deal activate(UUID dealId) {
        UUID tenantId = TenantContext.require();
        Deal deal = requireVisibleDeal(tenantId, dealId);
        if (deal.status() != DealStatus.DRAFT) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "Only a draft deal can be activated; this one is " + deal.status().code(),
                    Map.of("dealStatus", deal.status().code()));
        }

        boolean approvalRequired = approvalRequiredFor(deal);
        requireActivationAuthority(approvalRequired);

        // ① and ②. Runs first so a deal with no schedule, or one that no longer adds up, is
        // refused before a unit is taken off the market on its behalf.
        PlanWithSchedule plan = plans.activateFor(deal);

        // ④. The claim, and the reservation it converts if this deal grew out of a hold.
        Optional<Reservation> hold = liveHoldFor(deal);
        claimUnitFor(deal, hold);
        hold.ifPresent(reservation ->
                reservations.convertOnDealActivation(reservation.id(), deal.id()));

        // ③, and the transition itself.
        OffsetDateTime now = OffsetDateTime.now(clock);
        apply(() -> deal.activate(approvalRequired, now));
        deal.recordActor(SecurityContext.require().userId(), false);
        Deal saved = deals.save(deal);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", saved.status().code());
        after.put("netValue", saved.netValue().toPlainString());
        after.put("planId", plan.plan().id().toString());
        after.put("scheduleTotal", plan.expectedTotal().toPlainString());
        after.put("installments", plan.installments().size());
        after.put("unitStatus", UnitStatus.SOLD.code());
        hold.ifPresent(reservation ->
                after.put("convertedReservationId", reservation.id().toString()));
        // Doc 18's result column also lists commissions created. They are Epic 8, and their
        // absence is recorded here rather than left to be inferred from a missing field.
        after.put("commissions", "deferred to Epic 8");

        audit.record(AuditAction.DEAL_ACTIVATED, "Deal", saved.id(),
                Map.of("status", DealStatus.DRAFT.code()), after, null);
        return saved;
    }

    /**
     * Takes the unit out of contention, by the route the deal's own history justifies.
     *
     * <p>{@code reserved → sold} is only used when this deal converts the hold it names, and
     * that hold is the one currently on the unit. Doc 18 section 2 permits the transition
     * for "a reservation converting into a deal" — for any other deal it would be a second
     * buyer taking a unit actively held for a first, which is the double-sell the whole
     * guard exists to prevent. Everything else goes through {@code available → sold}.
     */
    private void claimUnitFor(Deal deal, Optional<Reservation> convertingHold) {
        UnitClaim claim = convertingHold.isPresent()
                ? units.claimForSaleOnConversion(deal.unitId())
                : units.claimForSale(deal.unitId());

        if (!claim.won()) {
            throw ApiException.conflict(
                    "That unit is no longer available to sell",
                    Map.of("unitId", deal.unitId().toString(),
                            "unitStatus", claim.statusNow().code()));
        }
    }

    /**
     * The hold this deal grew out of, if it is still live and still on this unit.
     *
     * <p>Empty when the deal names no reservation, and also when it names one that has since
     * expired or been released — E4-S2 is explicit that an expired reservation cannot
     * convert. In that case the unit went back to inventory and activation claims it as open
     * stock, or fails to claim it because somebody else got there. Both are true accounts of
     * what happened; pretending the hold was still good would not be.
     */
    private Optional<Reservation> liveHoldFor(Deal deal) {
        if (deal.sourceReservationId() == null) {
            return Optional.empty();
        }
        return reservations.activeHoldOn(deal.unitId())
                .filter(hold -> hold.id().equals(deal.sourceReservationId()))
                .filter(hold -> hold.status() == ReservationStatus.CONFIRMED);
    }

    /**
     * Doc 18: the actor is "BM (or AG under threshold)".
     *
     * <p>So an agent may activate their own deal when the discount does not need approval,
     * and a manager is required when it does. Distinct from R-DISC-5, which is about the
     * approval existing; this is about who may press the button afterwards. Both apply.
     */
    private void requireActivationAuthority(boolean approvalRequired) {
        if (approvalRequired) {
            authorization.requireAnyRole(Role.BRANCH_MANAGER, Role.OPERATIONS, Role.OWNER,
                    Role.PLATFORM_ADMIN);
        } else {
            requireSellingRole();
        }
    }

    // ------------------------------------------------------------------ E5-S7 down payment

    /**
     * Doc 18: "active → down payment confirmed", brokered only (E5-S7, R-DP-1, R-DP-2).
     *
     * <p>A date and a confirming user, and no amount. It records something the tenant was
     * told — the developer received the customer's down payment — and creates no Payment,
     * because under this model the tenant never held the money. R-PAY-0 and decision A13 are
     * the same point from two directions: a ledger entry for cash that never arrived is
     * worse than no entry at all.
     *
     * <p>Refused where the plan's down payment is zero. R-DP-3 makes such a deal satisfied
     * from activation, so there is nothing to confirm and the action is not offered.
     */
    @Transactional
    public Deal confirmDownPaymentReceivedByDeveloper(UUID dealId, LocalDate receivedOn) {
        authorization.requireAnyRole(Role.FINANCE, Role.OPERATIONS, Role.OWNER,
                Role.PLATFORM_ADMIN);
        UUID tenantId = TenantContext.require();
        Deal deal = requireVisibleDeal(tenantId, dealId);
        requireDownPaymentConfirmationApplies(deal);

        UUID actor = SecurityContext.require().userId();
        LocalDate on = receivedOn == null ? LocalDate.now(clock) : receivedOn;
        apply(() -> deal.confirmDownPaymentReceivedByDeveloper(on, actor));
        deal.recordActor(actor, false);
        Deal saved = deals.save(deal);

        audit.record(AuditAction.DEAL_DOWN_PAYMENT_CONFIRMED, "Deal", saved.id(),
                Map.of("downPaymentConfirmed", false),
                Map.of("downPaymentConfirmed", true,
                        "confirmedOn", on.toString(),
                        "paymentCreated", false), null);
        return saved;
    }

    /** Whether E5-S7's action applies to this deal at all — the UI asks before offering it. */
    @Transactional(readOnly = true)
    public boolean downPaymentConfirmationApplies(Deal deal) {
        if (!collection(deal).hasDeveloperDownPaymentConfirmation()) {
            return false;
        }
        // R-DP-3: nothing to confirm on a zero-down plan, in either model.
        return plans.planFor(deal.id())
                .map(plan -> plan.plan().downPaymentAmount().isPositive())
                .orElse(false);
    }

    private void requireDownPaymentConfirmationApplies(Deal deal) {
        if (!collection(deal).hasDeveloperDownPaymentConfirmation()) {
            throw ApiException.notApplicableForCommercialModel(
                    "Confirming a developer down-payment receipt", deal.commercialModel());
        }
        if (deal.status() != DealStatus.ACTIVE) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "A down payment can only be confirmed on an active deal; this one is "
                            + deal.status().code(),
                    Map.of("dealStatus", deal.status().code()));
        }
        boolean hasDownPayment = plans.planFor(deal.id())
                .map(plan -> plan.plan().downPaymentAmount().isPositive())
                .orElse(false);
        if (!hasDownPayment) {
            throw ApiException.businessRule(
                    "This deal's plan has no down payment, so there is nothing to confirm "
                            + "(R-DP-3)",
                    Map.of("dealId", deal.id().toString()));
        }
    }

    // ------------------------------------------------------------------ E5-S8 completion

    /**
     * Doc 18: "active → completed".
     *
     * <p>Reachable now only under brokered inventory, where completion is a person's
     * decision about the tenant's own part of the sale (E5-S8). Under own inventory the
     * table's precondition is "all installments paid", and payments are Epic 6 — so this
     * refuses rather than offering a manual override, because a manual completion in a model
     * whose completion is meant to be computed is a way to declare a debt settled that is
     * not.
     */
    @Transactional
    public Deal complete(UUID dealId) {
        authorization.requireAnyRole(Role.FINANCE, Role.OPERATIONS, Role.OWNER,
                Role.PLATFORM_ADMIN);
        UUID tenantId = TenantContext.require();
        Deal deal = requireVisibleDeal(tenantId, dealId);

        if (!collection(deal).completionIsManual()) {
            throw ApiException.businessRule(
                    "Under own inventory a deal completes when its schedule is fully paid, "
                            + "which the payments module decides; it cannot be set by hand",
                    Map.of("commercialModel", deal.commercialModel(),
                            "availableFrom", "Epic 6"));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        apply(() -> deal.complete(now));
        deal.recordActor(SecurityContext.require().userId(), false);
        Deal saved = deals.save(deal);

        audit.record(AuditAction.DEAL_COMPLETED, "Deal", saved.id(),
                Map.of("status", DealStatus.ACTIVE.code()),
                Map.of("status", saved.status().code(),
                        "downPaymentConfirmed", saved.isDownPaymentConfirmedByDeveloper()), null);
        return saved;
    }

    // ------------------------------------------------------------------ cancellation

    /**
     * Doc 18: "draft → cancelled", which the table gives no side effects.
     *
     * <p>None, literally: the unit was never claimed by a draft, so nothing returns to
     * inventory, and no schedule was ever live. The draft plan is left as it is — a record of
     * what was being negotiated, on a deal that says plainly it was abandoned.
     *
     * <p>{@code active → cancelled} is refused. It is marked P2 in the table and its result
     * column requires installments voided, the unit returned AND commissions clawed back.
     * The last of those cannot be done, because commissions do not exist yet. A cancellation
     * that unwound the inventory and left the entitlements standing would be worse than no
     * cancellation at all, so this says so instead of doing two thirds of it.
     */
    @Transactional
    public Deal cancel(UUID dealId, String reason) {
        requireSellingRole();
        UUID tenantId = TenantContext.require();
        Deal deal = requireVisibleDeal(tenantId, dealId);

        if (deal.status() == DealStatus.ACTIVE) {
            throw ApiException.businessRule(
                    "Cancelling an active deal unwinds inventory, schedule and commissions "
                            + "together; that is the Cancellation flow and it is not built yet",
                    Map.of("dealStatus", deal.status().code(), "availableFrom", "P2"));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        apply(() -> deal.cancel(now, reason));
        deal.recordActor(SecurityContext.require().userId(), false);
        Deal saved = deals.save(deal);

        audit.record(AuditAction.DEAL_CANCELLED, "Deal", saved.id(),
                Map.of("status", DealStatus.DRAFT.code()),
                Map.of("status", saved.status().code(), "unitReturned", false), reason);
        return saved;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Deal get(UUID dealId) {
        return requireVisibleDeal(TenantContext.require(), dealId);
    }

    @Transactional(readOnly = true)
    public List<DealDiscount> discountsOn(UUID dealId) {
        UUID tenantId = TenantContext.require();
        requireVisibleDeal(tenantId, dealId);
        return discounts.findAllForDeal(tenantId, dealId);
    }

    /** Already narrowed to what the caller may see; there is no widening parameter. */
    @Transactional(readOnly = true)
    public Page<Deal> listVisibleToCaller(Pageable pageable) {
        UUID tenantId = TenantContext.require();
        AuthenticatedPrincipal caller = SecurityContext.require();

        return switch (caller.role().branchScope()) {
            case TENANT_WIDE -> deals.findAllByTenantIdOrderByDealDateDesc(tenantId, pageable);
            case OWN_BRANCH -> deals.findAllByTenantIdAndBranchIdOrderByDealDateDesc(
                    tenantId, caller.branchId(), pageable);
            case OWN_RECORDS -> deals.findAllByTenantIdAndAgentUserIdOrderByDealDateDesc(
                    tenantId, caller.userId(), pageable);
        };
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> history(UUID dealId) {
        UUID tenantId = TenantContext.require();
        requireVisibleDeal(tenantId, dealId);
        return auditEvents.findTrail(tenantId, "Deal", dealId);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Recomputes the deal's total from every discount row and restates its net value.
     *
     * <p>A sum over the rows, never an increment. R-DISC-2 makes the total the sum of
     * concessions each taken against gross, and an incremental total would compound them —
     * the one arithmetic mistake in this area that produces a plausible-looking number.
     */
    private Deal restateTotalDiscount(UUID tenantId, Deal deal) {
        Money total = Money.sum(deal.grossValue().currency(),
                discounts.findAllForDeal(tenantId, deal.id()).stream()
                        .map(DealDiscount::amount)
                        .toList());
        apply(() -> deal.restateDiscount(total));
        deal.recordActor(SecurityContext.require().userId(), false);
        return deals.save(deal);
    }

    /**
     * Doc 18's "unit available/held by same party".
     *
     * <p>A reserved unit is draftable only through the hold that is on it, and only when the
     * caller names that hold. Anything else — a reserved unit with no reservation named, or
     * one named that is not the live hold — is somebody else's customer.
     */
    private Optional<Reservation> requireUnitDraftable(Unit unit, UUID sourceReservationId) {
        Optional<Reservation> hold = reservations.activeHoldOn(unit.id());

        if (sourceReservationId != null) {
            Reservation named = reservations.get(sourceReservationId);
            if (!named.unitId().equals(unit.id())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "That reservation is for a different unit");
            }
            if (hold.isEmpty() || !hold.get().id().equals(sourceReservationId)) {
                throw ApiException.conflict(
                        "That reservation is no longer the live hold on this unit",
                        Map.of("reservationStatus", named.status().code()));
            }
            return hold;
        }

        if (unit.status() == UnitStatus.AVAILABLE) {
            return Optional.empty();
        }
        if (unit.status() == UnitStatus.RESERVED) {
            throw ApiException.conflict(
                    "That unit is held by an existing reservation; convert that hold instead "
                            + "of drafting against it",
                    Map.of("unitStatus", unit.status().code()));
        }
        throw ApiException.conflict("That unit is not available to sell",
                Map.of("unitStatus", unit.status().code()));
    }

    /**
     * The same gate the reservations module applies, for the same reason: a unit in a project
     * that is not selling yet should not be sold, and the place to stop it is where somebody
     * tries.
     */
    private static void requireProjectSelling(Project project) {
        if (!project.status().isSelling()) {
            throw ApiException.businessRule("That unit's project is not selling yet",
                    Map.of("projectStatus", project.status().code()));
        }
    }

    /**
     * Writes the draft, turning C1 into an answer rather than a stack trace.
     *
     * <p>The partial unique index is what actually prevents two live deals on one unit; this
     * only decides what the loser is told. The read below decides nothing and is not a
     * check — C1 is the check. It exists so the common refusal can name the deal that
     * already has the unit, which is what an agent needs in order to go and ask about it.
     *
     * <p>The read has to come BEFORE the insert, and that is not a style preference. A
     * failed insert aborts the PostgreSQL transaction, and every command after it is
     * rejected with "current transaction is aborted" until the transaction ends — so
     * looking up the winner inside the catch block, which is the obvious thing to reach
     * for, turns a useful 409 into a 500. Between this read and the insert another draft
     * can still appear; then the index refuses ours and the caller is told the unit is
     * taken without a name. Correct, and rarer than the case this serves.
     */
    private Deal saveExclusive(Deal deal, UUID unitId) {
        deals.findLiveForUnit(deal.tenantId(), unitId).ifPresent(existing -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("unitId", unitId.toString());
            details.put("dealId", existing.id().toString());
            details.put("dealStatus", existing.status().code());
            throw ApiException.conflict("That unit already has a live deal on it", details);
        });

        try {
            return deals.saveAndFlush(deal);
        } catch (DataIntegrityViolationException tookItFirst) {
            throw ApiException.conflict(
                    "That unit was taken while this deal was being drafted",
                    Map.of("unitId", unitId.toString()));
        }
    }

    private Deal requireVisibleDeal(UUID tenantId, UUID dealId) {
        Deal deal = deals.findByTenantIdAndId(tenantId, dealId)
                .orElseThrow(() -> ApiException.notFound("Deal"));
        authorization.requireRecordVisible(deal.agentUserId(), deal.branchId(), "Deal");
        return deal;
    }

    private static void requireEditable(Deal deal, String what) {
        if (!deal.status().isEditable()) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    what + " can only be changed while the deal is a draft; this one is "
                            + deal.status().code(),
                    Map.of("dealStatus", deal.status().code()));
        }
    }

    private CollectionPolicy collection(Deal deal) {
        return policies.collection(deal.commercialModel());
    }

    private DealSettings settings(UUID tenantId) {
        return DealSettings.from(directory.tenantSettings(tenantId), objectMapper);
    }

    private void requireSellingRole() {
        authorization.requireAnyRole(Role.SALES_AGENT, Role.TEAM_LEADER, Role.BRANCH_MANAGER,
                Role.OPERATIONS, Role.OWNER, Role.PLATFORM_ADMIN);
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
