package com.rescrm.integration;

import com.rescrm.crm.service.CustomerService;
import com.rescrm.deals.domain.Concession;
import com.rescrm.deals.domain.Deal;
import com.rescrm.deals.domain.DealStatus;
import com.rescrm.deals.domain.PaymentPlanTemplate;
import com.rescrm.deals.domain.PlanStatus;
import com.rescrm.deals.service.DealService;
import com.rescrm.deals.service.PaymentPlanService;
import com.rescrm.deals.service.PlanWithSchedule;
import com.rescrm.finance.schedule.DownPayment;
import com.rescrm.finance.schedule.InstallmentKind;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.inventory.domain.ProjectStatus;
import com.rescrm.inventory.domain.UnitStatus;
import com.rescrm.inventory.service.DeveloperService;
import com.rescrm.inventory.service.ProjectService;
import com.rescrm.inventory.service.UnitService;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditEventRepository;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.Percentage;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.tenancy.TenantContext;
import com.rescrm.reservations.domain.Reservation;
import com.rescrm.reservations.domain.ReservationStatus;
import com.rescrm.reservations.service.ReservationService;
import com.rescrm.support.CanonicalDeal;
import com.rescrm.support.TestIdentity;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Doc 18 section 4's transition table, exercised against a real PostgreSQL.
 *
 * <p>Every row of that table gets a test, and so does every move the table does NOT contain
 * — because a lifecycle is defined as much by what it refuses as by what it allows, and a
 * state machine that only ever gets valid input is untested.
 *
 * <p>The thread running through all of it is that a deal, its unit, its schedule and the
 * hold it came from are four facts that must agree. Activation moves all four at once or
 * none of them; the tests check every side, because a deal that says one thing while the
 * unit says another is the defect this epic exists to prevent.
 */
@DisplayName("Deal lifecycle")
class DealLifecycleIT extends AbstractPostgresIT {

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private DeveloperService developerService;
    @Autowired private ProjectService projectService;
    @Autowired private UnitService unitService;
    @Autowired private CustomerService customerService;
    @Autowired private ReservationService reservationService;
    @Autowired private DealService dealService;
    @Autowired private PaymentPlanService planService;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private JdbcTemplate jdbc;

    private TenantProvisioningService.ProvisionedTenant tenant;
    private UUID projectId;
    private UUID unitId;
    private UUID customerId;
    private UUID templateId;

    @BeforeEach
    void provision() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        tenant = provisioning.provision("Deals " + unique, "own_inventory", "Main", "Owner",
                "deals-" + unique + "@example.com", "a-long-enough-password");
        TestIdentity.signOut();
        TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(), Role.OWNER, null);

        projectId = projectService.create("own_inventory", null, null, "Deal Project", null,
                CanonicalDeal.PROJECT_DELIVERY_DATE).id();
        projectService.changeStatus(projectId, ProjectStatus.ACTIVE, "launch");

        // The canonical fixture's list price, so the doc 17 section 5 figures are reachable
        // through the whole stack rather than only in the generator's unit tests.
        unitId = unitService.create(projectId, null, "D-101", "apartment", null, null, null,
                CanonicalDeal.LIST_PRICE).id();
        customerId = customerService.create(null, "Buyer", "+201000000002", null, null, null,
                false).id();
        templateId = canonicalTemplate();
    }

    @AfterEach
    void signOut() {
        TestIdentity.signOut();
    }

    // ========================================================================= E5-S1

    @Nested
    @DisplayName("— to draft")
    class Drafting {

        @Test
        @DisplayName("freezes the unit's list price onto the deal (R-VAL-1)")
        void gross_value_is_frozen() {
            Deal deal = draft();
            assertThat(deal.grossValue()).isEqualTo(CanonicalDeal.LIST_PRICE);

            unitService.updateAttributes(unitId, "apartment", null, null, null,
                    egp("9999999.00"), null);

            assertThat(dealService.get(deal.id()).grossValue())
                    .as("a later price change does not reach a struck deal")
                    .isEqualTo(CanonicalDeal.LIST_PRICE);
        }

        @Test
        @DisplayName("copies the project's commercial model and never changes it")
        void commercial_model_is_copied() {
            assertThat(draft().commercialModel()).isEqualTo("own_inventory");
        }

        @Test
        @DisplayName("does not withhold the unit: a draft is not a sale")
        void drafting_leaves_the_unit_available() {
            draft();
            assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.AVAILABLE);
        }

        @Test
        @DisplayName("C1 — a second live deal on the same unit is refused")
        void one_live_deal_per_unit() {
            draft();
            assertCode(DealLifecycleIT.this::draft, ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("and the refusal names the deal that already has it")
        void the_refusal_names_the_winner() {
            Deal first = draft();
            assertThatThrownBy(DealLifecycleIT.this::draft)
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).details())
                            .containsEntry("dealId", first.id().toString()));
        }

        @Test
        @DisplayName("a unit somebody else is holding cannot be drafted against blindly")
        void a_held_unit_needs_its_hold_named() {
            Reservation hold = reservationService.place(unitId, null, customerId, null, null,
                    false);
            reservationService.confirm(hold.id());

            // Doc 18's precondition is "available OR held by the same party". Reserved with
            // no hold named is the other agent's customer.
            assertCode(() -> dealService.draft(unitId, customerId, null, null),
                    ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("but may be drafted against through the hold that is on it")
        void a_held_unit_can_be_drafted_through_its_own_hold() {
            Reservation hold = reservationService.place(unitId, null, customerId, null, null,
                    false);
            reservationService.confirm(hold.id());

            Deal deal = dealService.draft(unitId, customerId, hold.id(), null);
            assertThat(deal.sourceReservationId()).isEqualTo(hold.id());
        }

        @Test
        @DisplayName("a project that is not selling yet refuses a deal")
        void a_draft_project_cannot_be_sold_from() {
            UUID sleeping = projectService.create("own_inventory", null, null, "Not launched",
                    null, null).id();
            UUID sleepingUnit = unitService.create(sleeping, null, "Z-1", "apartment", null,
                    null, null, egp("1000000.00")).id();

            assertCode(() -> dealService.draft(sleepingUnit, customerId, null, null),
                    ErrorCode.BUSINESS_RULE_VIOLATION);
        }
    }

    // ========================================================================= E5-S2

    @Nested
    @DisplayName("draft to draft (edit)")
    class Discounts {

        @Test
        @DisplayName("R-DISC-2 — percentages are taken against gross and added, not compounded")
        void discounts_are_additive_against_gross() {
            Deal deal = draft();
            dealService.addDiscount(deal.id(), Concession.ofPercent(Percentage.of("5")), null);
            Deal after = dealService.addDiscount(deal.id(),
                    Concession.ofPercent(Percentage.of("10")), null);

            // 15% of 3,000,000 = 450,000. Compounding would give 435,000 — a plausible
            // number that is 15,000 wrong, which is the whole reason the rule is written down.
            assertThat(after.totalDiscount()).isEqualTo(egp("450000.00"));
            assertThat(after.netValue()).isEqualTo(egp("2550000.00"));
        }

        @Test
        @DisplayName("the canonical 5% produces the documented net value")
        void the_canonical_discount() {
            Deal after = dealService.addDiscount(draft().id(),
                    Concession.ofPercent(CanonicalDeal.DISCOUNT_PERCENT), "Launch");

            assertThat(after.totalDiscount()).isEqualTo(CanonicalDeal.TOTAL_DISCOUNT);
            assertThat(after.netValue()).isEqualTo(CanonicalDeal.NET_VALUE);
        }

        @Test
        @DisplayName("a fixed amount works alongside a percentage")
        void mixed_discounts() {
            Deal deal = draft();
            dealService.addDiscount(deal.id(), Concession.ofPercent(Percentage.of("5")), null);
            Deal after = dealService.addDiscount(deal.id(),
                    Concession.ofAmount(egp("50000.00")), null);

            assertThat(after.totalDiscount()).isEqualTo(egp("200000.00"));
        }

        @Test
        @DisplayName("removing one restates the total from what is left")
        void removing_a_discount_restates_the_total() {
            Deal deal = draft();
            dealService.addDiscount(deal.id(), Concession.ofPercent(Percentage.of("5")), null);
            UUID onlyOne = dealService.discountsOn(deal.id()).get(0).id();
            Deal after = dealService.removeDiscount(deal.id(), onlyOne);

            assertThat(after.totalDiscount()).isEqualTo(Money.zero(CurrencyCode.EGP));
            assertThat(after.netValue()).isEqualTo(CanonicalDeal.LIST_PRICE);
        }

        @Test
        @DisplayName("R-DISC-4 — a discount cannot reach the gross value")
        void net_value_must_stay_above_zero() {
            Deal deal = draft();
            assertCode(() -> dealService.addDiscount(deal.id(),
                    Concession.ofPercent(Percentage.of("100")), null),
                    ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("a discount on an active deal is refused: it is part of the agreed price")
        void an_active_deal_cannot_be_repriced() {
            Deal active = activateCanonicalDeal();
            assertCode(() -> dealService.addDiscount(active.id(),
                    Concession.ofPercent(Percentage.of("1")), null),
                    ErrorCode.ILLEGAL_STATE_TRANSITION);
        }
    }

    // ================================================================= R-DISC-5

    @Nested
    @DisplayName("discount approval")
    class Approval {

        @Test
        @DisplayName("no threshold configured means nothing needs approval")
        void no_threshold_means_no_approval() {
            Deal deal = dealService.addDiscount(draft().id(),
                    Concession.ofPercent(Percentage.of("40")), null);

            // Doc 17 section 12 gives the default as "none — set per tenant". Inventing one
            // would gate concessions at a number nobody chose.
            assertThat(dealService.approvalRequiredFor(deal)).isFalse();
        }

        @Test
        @DisplayName("a discount above the tenant's threshold requires it")
        void above_the_threshold() {
            setDiscountThreshold("10");
            Deal deal = dealService.addDiscount(draft().id(),
                    Concession.ofPercent(Percentage.of("15")), null);

            assertThat(dealService.approvalRequiredFor(deal)).isTrue();
            assertThat(deal.isDiscountApproved()).isFalse();
        }

        @Test
        @DisplayName("one at the threshold does not — the rule says above")
        void at_the_threshold_is_not_above_it() {
            setDiscountThreshold("10");
            Deal deal = dealService.addDiscount(draft().id(),
                    Concession.ofPercent(Percentage.of("10")), null);

            assertThat(dealService.approvalRequiredFor(deal)).isFalse();
        }

        @Test
        @DisplayName("activation is refused until it is given")
        void activation_needs_the_approval() {
            setDiscountThreshold("1");
            Deal deal = dealService.addDiscount(draft().id(),
                    Concession.ofPercent(CanonicalDeal.DISCOUNT_PERCENT), null);
            planService.applyTemplate(deal.id(), templateId);

            assertCode(() -> dealService.activate(deal.id()),
                    ErrorCode.ILLEGAL_STATE_TRANSITION);
            assertThat(unitService.get(unitId).status())
                    .as("a refused activation leaves the unit in inventory")
                    .isEqualTo(UnitStatus.AVAILABLE);
        }

        @Test
        @DisplayName("and allowed once it is")
        void approval_unblocks_activation() {
            setDiscountThreshold("1");
            Deal deal = dealService.addDiscount(draft().id(),
                    Concession.ofPercent(CanonicalDeal.DISCOUNT_PERCENT), null);
            planService.applyTemplate(deal.id(), templateId);
            dealService.approveDiscount(deal.id());

            assertThat(dealService.activate(deal.id()).status()).isEqualTo(DealStatus.ACTIVE);
        }

        @Test
        @DisplayName("changing the discount afterwards withdraws the approval")
        void editing_clears_the_approval() {
            setDiscountThreshold("1");
            Deal deal = dealService.addDiscount(draft().id(),
                    Concession.ofPercent(Percentage.of("5")), null);
            dealService.approveDiscount(deal.id());
            assertThat(dealService.get(deal.id()).isDiscountApproved()).isTrue();

            // What was approved was a number. A manager's signature must not survive onto a
            // larger concession they never saw.
            Deal enlarged = dealService.addDiscount(deal.id(),
                    Concession.ofPercent(Percentage.of("5")), null);
            assertThat(enlarged.isDiscountApproved()).isFalse();
        }
    }

    // ========================================================================= E5-S4

    @Nested
    @DisplayName("the generated schedule")
    class Schedule {

        @Test
        @DisplayName("FIN-001 reproduces to the cent through the whole stack")
        void the_canonical_schedule() {
            Deal deal = dealService.addDiscount(draft().id(),
                    Concession.ofPercent(CanonicalDeal.DISCOUNT_PERCENT), null);
            PlanWithSchedule plan = planService.applyTemplate(deal.id(), templateId);

            assertThat(plan.plan().netValue()).isEqualTo(CanonicalDeal.NET_VALUE);
            assertThat(plan.plan().downPaymentAmount()).isEqualTo(CanonicalDeal.DOWN_PAYMENT);
            assertThat(plan.plan().deliveryPaymentAmount())
                    .isEqualTo(CanonicalDeal.DELIVERY_PAYMENT);
            assertThat(plan.plan().financedAmount()).isEqualTo(CanonicalDeal.FINANCED);
            assertThat(plan.installments()).hasSize(CanonicalDeal.EXPECTED_ROW_COUNT);

            // R-PLAN-4, over the rows as they came back out of the database.
            assertThat(plan.expectedTotal()).isEqualTo(CanonicalDeal.NET_VALUE);
        }

        @Test
        @DisplayName("the rows are stored, not recomputed — the preview IS the schedule")
        void the_preview_is_what_is_stored() {
            Deal deal = dealService.addDiscount(draft().id(),
                    Concession.ofPercent(CanonicalDeal.DISCOUNT_PERCENT), null);
            PlanWithSchedule generated = planService.applyTemplate(deal.id(), templateId);
            PlanWithSchedule readBack = planService.planFor(deal.id()).orElseThrow();

            assertThat(readBack.installments().stream().map(i -> i.expectedAmount().toPlainString()))
                    .containsExactlyElementsOf(generated.installments().stream()
                            .map(i -> i.expectedAmount().toPlainString()).toList());
        }

        @Test
        @DisplayName("R-INST-1/2 — the base floors and the remainder lands on the last row")
        void rounding() {
            Deal deal = dealService.addDiscount(draft().id(),
                    Concession.ofPercent(CanonicalDeal.DISCOUNT_PERCENT), null);
            List<Money> installments = planService.applyTemplate(deal.id(), templateId)
                    .installments().stream()
                    .filter(row -> row.kind() == InstallmentKind.INSTALLMENT)
                    .map(row -> row.expectedAmount())
                    .toList();

            assertThat(installments).hasSize(CanonicalDeal.INSTALLMENT_COUNT);
            assertThat(installments.subList(0, CanonicalDeal.INSTALLMENT_COUNT - 1))
                    .containsOnly(CanonicalDeal.BASE_INSTALLMENT);
            assertThat(installments.get(CanonicalDeal.INSTALLMENT_COUNT - 1))
                    .isEqualTo(CanonicalDeal.FINAL_INSTALLMENT);
        }

        @Test
        @DisplayName("FIN-025 — the delivery row carries the project's delivery date")
        void delivery_date() {
            Deal deal = draft();
            assertThat(planService.applyTemplate(deal.id(), templateId).installments().stream()
                    .filter(row -> row.kind() == InstallmentKind.DELIVERY)
                    .map(row -> row.dueDate())
                    .toList())
                    .containsExactly(CanonicalDeal.PROJECT_DELIVERY_DATE);
        }

        @Test
        @DisplayName("TPL-006 — regenerating replaces the draft rather than adding a second")
        void regenerating_replaces() {
            Deal deal = draft();
            planService.applyTemplate(deal.id(), templateId);
            PlanWithSchedule again = planService.applyTemplate(deal.id(), templateId);

            assertThat(again.plan().version()).isEqualTo(2);
            assertThat(again.installments()).hasSize(CanonicalDeal.EXPECTED_ROW_COUNT);
            assertThat(planService.planFor(deal.id()).orElseThrow().installments())
                    .as("the previous run's rows are gone, not accumulated")
                    .hasSize(CanonicalDeal.EXPECTED_ROW_COUNT);
        }

        @Test
        @DisplayName("TPL-002 — archiving a template leaves a generated schedule untouched")
        void archiving_does_not_reach_a_live_plan() {
            Deal deal = draft();
            PlanWithSchedule before = planService.applyTemplate(deal.id(), templateId);
            planService.archiveTemplate(templateId);

            assertThat(planService.planFor(deal.id()).orElseThrow().plan().netValue())
                    .isEqualTo(before.plan().netValue());
            assertCode(() -> planService.applyTemplate(deal.id(), templateId),
                    ErrorCode.BUSINESS_RULE_VIOLATION);
        }

        @Test
        @DisplayName("a schedule cannot be generated for a deal that is no longer a draft")
        void active_deals_keep_their_schedule() {
            Deal active = activateCanonicalDeal();
            assertCode(() -> planService.applyTemplate(active.id(), templateId),
                    ErrorCode.ILLEGAL_STATE_TRANSITION);
        }
    }

    // ========================================================================= E5-S5

    @Nested
    @DisplayName("draft to active")
    class Activation {

        @Test
        @DisplayName("sells the unit, activates the plan and records the moment, together")
        void activation_moves_everything() {
            Deal active = activateCanonicalDeal();

            assertThat(active.status()).isEqualTo(DealStatus.ACTIVE);
            assertThat(active.activatedAt()).isNotNull();
            assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.SOLD);
            assertThat(planService.planFor(active.id()).orElseThrow().plan().status())
                    .isEqualTo(PlanStatus.ACTIVE);
        }

        @Test
        @DisplayName("precondition 1 — a deal with no schedule cannot activate")
        void no_schedule_no_activation() {
            Deal deal = draft();
            assertCode(() -> dealService.activate(deal.id()),
                    ErrorCode.BUSINESS_RULE_VIOLATION);

            assertThat(unitService.get(unitId).status())
                    .as("and the unit stays in inventory")
                    .isEqualTo(UnitStatus.AVAILABLE);
        }

        @Test
        @DisplayName("precondition 2 — a schedule that no longer matches the deal is refused")
        void r_plan_4_is_reasserted_over_stored_rows() {
            Deal deal = draft();
            planService.applyTemplate(deal.id(), templateId);

            // The schedule was generated against the undiscounted gross. Adding a concession
            // afterwards leaves rows that no longer sum to the deal's net value, which is
            // exactly the drift doc 18 lists as precondition 2.
            dealService.addDiscount(deal.id(), Concession.ofPercent(Percentage.of("5")), null);

            assertCode(() -> dealService.activate(deal.id()),
                    ErrorCode.PLAN_INVARIANT_VIOLATION);
            assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.AVAILABLE);
        }

        @Test
        @DisplayName("and regenerating the schedule makes it activatable again")
        void regenerating_clears_the_drift() {
            Deal deal = draft();
            planService.applyTemplate(deal.id(), templateId);
            dealService.addDiscount(deal.id(), Concession.ofPercent(Percentage.of("5")), null);
            planService.applyTemplate(deal.id(), templateId);

            assertThat(dealService.activate(deal.id()).status()).isEqualTo(DealStatus.ACTIVE);
        }

        @Test
        @DisplayName("CON-003 — a failed activation leaves no partial state behind")
        void activation_is_all_or_nothing() {
            Deal deal = draft();
            planService.applyTemplate(deal.id(), templateId);

            // Block the unit out from under the draft. The claim will find it is not
            // available and the whole activation must roll back.
            unitService.block(unitId, "Taken off the market mid-negotiation");

            assertCode(() -> dealService.activate(deal.id()), ErrorCode.CONFLICT);

            assertThat(dealService.get(deal.id()).status())
                    .as("the deal is still a draft")
                    .isEqualTo(DealStatus.DRAFT);
            assertThat(planService.planFor(deal.id()).orElseThrow().plan().status())
                    .as("and its schedule is still a draft, not half-activated")
                    .isEqualTo(PlanStatus.DRAFT);
            assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.BLOCKED);
        }

        @Test
        @DisplayName("activating twice is refused; the second attempt changes nothing")
        void activation_is_not_idempotent_by_accident() {
            Deal active = activateCanonicalDeal();
            assertCode(() -> dealService.activate(active.id()),
                    ErrorCode.ILLEGAL_STATE_TRANSITION);
            assertThat(dealService.get(active.id()).status()).isEqualTo(DealStatus.ACTIVE);
        }

        @Test
        @DisplayName("converts the hold the deal came from, and sells its unit directly")
        void activation_converts_its_reservation() {
            Reservation hold = reservationService.place(unitId, null, customerId, null, null,
                    false);
            reservationService.confirm(hold.id());
            assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.RESERVED);

            Deal deal = dealService.draft(unitId, customerId, hold.id(), null);
            dealService.addDiscount(deal.id(),
                    Concession.ofPercent(CanonicalDeal.DISCOUNT_PERCENT), null);
            planService.applyTemplate(deal.id(), templateId);
            dealService.activate(deal.id());

            assertThat(reservationService.get(hold.id()).status())
                    .isEqualTo(ReservationStatus.CONVERTED);
            assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.SOLD);
        }

        @Test
        @DisplayName("the activation is audited, and says commissions were not created")
        void activation_is_audited() {
            Deal active = activateCanonicalDeal();

            assertThat(auditEvents.findTrail(tenant.tenant().id(), "Deal", active.id()))
                    .anySatisfy(event -> assertThat(event.action())
                            .isEqualTo(AuditAction.DEAL_ACTIVATED.name()));
        }
    }

    // ================================================================ cancellation

    @Nested
    @DisplayName("cancellation")
    class Cancellation {

        @Test
        @DisplayName("draft to cancelled has no side effects, as the table says")
        void cancelling_a_draft() {
            Deal deal = draft();
            planService.applyTemplate(deal.id(), templateId);
            Deal cancelled = dealService.cancel(deal.id(), "Customer changed their mind");

            assertThat(cancelled.status()).isEqualTo(DealStatus.CANCELLED);
            assertThat(cancelled.cancelledReason()).isEqualTo("Customer changed their mind");
            assertThat(unitService.get(unitId).status())
                    .as("no unit was ever claimed, so none returns")
                    .isEqualTo(UnitStatus.AVAILABLE);
        }

        @Test
        @DisplayName("and frees the unit for a new deal, because C1 no longer counts it")
        void cancelling_releases_the_constraint() {
            dealService.cancel(draft().id(), "Changed their mind");
            assertThat(draft().status()).isEqualTo(DealStatus.DRAFT);
        }

        @Test
        @DisplayName("active to cancelled is refused, and says why rather than half-doing it")
        void cancelling_an_active_deal_is_p2() {
            Deal active = activateCanonicalDeal();

            // The table marks it P2 and its result column requires commissions clawed back.
            // Commissions do not exist until Epic 8; unwinding two thirds would leave a
            // cancelled deal still carrying entitlements somebody gets paid on.
            assertThatThrownBy(() -> dealService.cancel(active.id(), "Buyer withdrew"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).details())
                            .containsEntry("availableFrom", "P2"));

            assertThat(dealService.get(active.id()).status()).isEqualTo(DealStatus.ACTIVE);
            assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.SOLD);
        }

        @Test
        @DisplayName("a cancellation with no reason is refused")
        void a_reason_is_required() {
            assertCode(() -> dealService.cancel(draft().id(), "  "),
                    ErrorCode.VALIDATION_FAILED);
        }
    }

    // ============================================================ model-dependent

    @Nested
    @DisplayName("what the commercial model decides")
    class ByModel {

        @Test
        @DisplayName("own inventory has no developer down-payment confirmation")
        void own_inventory_has_no_confirmation_step() {
            Deal active = activateCanonicalDeal();

            assertThat(dealService.downPaymentConfirmationApplies(active)).isFalse();
            assertCode(() -> dealService.confirmDownPaymentReceivedByDeveloper(active.id(), null),
                    ErrorCode.NOT_APPLICABLE_FOR_COMMERCIAL_MODEL);
        }

        @Test
        @DisplayName("own-inventory completion is not a decision somebody can make by hand")
        void own_inventory_completion_waits_for_payments() {
            Deal active = activateCanonicalDeal();

            // Doc 18's precondition is "all installments paid", and payments are Epic 6. A
            // manual override here would declare a debt settled that is not.
            assertCode(() -> dealService.complete(active.id()),
                    ErrorCode.BUSINESS_RULE_VIOLATION);
        }

        @Test
        @DisplayName("a brokered deal records the confirmation and creates no payment")
        void brokered_confirmation() {
            Brokered brokered = brokeredFixture();
            Deal confirmed = dealService.confirmDownPaymentReceivedByDeveloper(
                    brokered.dealId(), LocalDate.of(2026, 2, 10));

            assertThat(confirmed.downPaymentConfirmedAt()).isEqualTo(LocalDate.of(2026, 2, 10));
            assertThat(confirmed.downPaymentConfirmedByUserId()).isEqualTo(tenant.owner().id());
        }

        @Test
        @DisplayName("and can then be completed by hand, which own inventory cannot")
        void brokered_completion_is_manual() {
            Brokered brokered = brokeredFixture();
            assertThat(dealService.complete(brokered.dealId()).status())
                    .isEqualTo(DealStatus.COMPLETED);
        }

        @Test
        @DisplayName("FIN-001d — the two models produce byte-identical schedules")
        void identical_schedules_across_models() {
            Deal own = draft();
            dealService.addDiscount(own.id(),
                    Concession.ofPercent(CanonicalDeal.DISCOUNT_PERCENT), null);
            List<String> ownRows = rowsOf(planService.applyTemplate(own.id(), templateId));

            Brokered brokered = brokeredFixture();
            List<String> brokeredRows =
                    rowsOf(planService.planFor(brokered.dealId()).orElseThrow());

            assertThat(brokeredRows)
                    .as("the commercial model governs who collects, never the arithmetic")
                    .containsExactlyElementsOf(ownRows);
        }

        private List<String> rowsOf(PlanWithSchedule plan) {
            return plan.installments().stream()
                    .map(row -> row.sequenceNo() + ":" + row.kind().code() + ":"
                            + row.dueDate() + ":" + row.expectedAmount().toPlainString())
                    .toList();
        }
    }

    // ==================================================================== helpers

    private record Brokered(UUID dealId, UUID unitId) { }

    /**
     * A brokered deal on its own project, activated, with the canonical numbers.
     *
     * <p>Its own project because a project's commercial model is fixed at creation and a
     * deal copies it; there is no way to make an own-inventory deal brokered, which is the
     * point.
     */
    private Brokered brokeredFixture() {
        UUID developerId = developerService.register("Developer", null, null).id();
        UUID brokeredProject = projectService.create("brokered_inventory", developerId, null,
                "Brokered Project", null, CanonicalDeal.PROJECT_DELIVERY_DATE).id();
        projectService.changeStatus(brokeredProject, ProjectStatus.ACTIVE, "launch");

        UUID brokeredUnit = unitService.create(brokeredProject, null, "B-101", "apartment",
                null, null, null, CanonicalDeal.LIST_PRICE).id();

        Deal deal = dealService.draft(brokeredUnit, customerId, null, CanonicalDeal.DEAL_DATE);
        dealService.addDiscount(deal.id(), Concession.ofPercent(CanonicalDeal.DISCOUNT_PERCENT),
                null);
        planService.applyTemplate(deal.id(), templateId);
        dealService.activate(deal.id());
        return new Brokered(deal.id(), brokeredUnit);
    }

    private Deal draft() {
        return dealService.draft(unitId, customerId, null, CanonicalDeal.DEAL_DATE);
    }

    /** A draft carrying the canonical 5% discount and schedule, activated. */
    private Deal activateCanonicalDeal() {
        Deal deal = draft();
        dealService.addDiscount(deal.id(), Concession.ofPercent(CanonicalDeal.DISCOUNT_PERCENT),
                null);
        planService.applyTemplate(deal.id(), templateId);
        return dealService.activate(deal.id());
    }

    /** The doc 17 section 5 template: 10% down, 32 quarterly, 5% on delivery. */
    private UUID canonicalTemplate() {
        return planService.createTemplate(null, "Canonical " + UUID.randomUUID(), null,
                new PaymentPlanTemplate.Shape(
                        DownPayment.percent(CanonicalDeal.DOWN_PAYMENT_PERCENT),
                        CanonicalDeal.DELIVERY_PERCENT, CanonicalDeal.INSTALLMENT_COUNT,
                        CanonicalDeal.FREQUENCY, null)).id();
    }

    /**
     * Writes the tenant's discount threshold straight into {@code tenants.settings}.
     *
     * <p>There is no service for it yet, and inventing one to make a test pass would be the
     * tail wagging the dog. Wrapped in {@code callAs} because the row-level policies apply
     * to this connection like any other: without a tenant established the UPDATE quietly
     * matches nothing and the test would pass for the wrong reason.
     */
    private void setDiscountThreshold(String percent) {
        UUID tenantId = tenant.tenant().id();
        TenantContext.callAs(tenantId, () -> jdbc.update(
                "UPDATE tenants SET settings = jsonb_set(coalesce(settings, '{}'::jsonb), "
                        + "'{deals}', ?::jsonb, true) WHERE id = ?",
                "{\"discountApprovalThresholdPercent\": " + percent + "}", tenantId));
    }

    private static Money egp(String amount) {
        return Money.of(amount, CurrencyCode.EGP);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                        .isEqualTo(expected));
    }
}
