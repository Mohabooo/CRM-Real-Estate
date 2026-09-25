package com.rescrm.integration;

import com.rescrm.crm.service.CustomerService;
import com.rescrm.deals.domain.Concession;
import com.rescrm.deals.domain.Deal;
import com.rescrm.deals.domain.DealStatus;
import com.rescrm.deals.domain.PaymentPlanTemplate;
import com.rescrm.deals.service.DealService;
import com.rescrm.deals.service.PaymentPlanService;
import com.rescrm.finance.schedule.DownPayment;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.inventory.domain.ProjectStatus;
import com.rescrm.inventory.service.DeveloperService;
import com.rescrm.inventory.service.ProjectService;
import com.rescrm.inventory.service.UnitService;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditEvent;
import com.rescrm.platform.audit.AuditEventRepository;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.money.Percentage;
import com.rescrm.platform.security.Role;
import com.rescrm.reservations.domain.Reservation;
import com.rescrm.reservations.service.ReservationService;
import com.rescrm.support.CanonicalDeal;
import com.rescrm.support.TestIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AUD-001 — "every transition marked Audit: Yes in doc 18 writes an audit record; one test
 * per transition, generated from the transition table".
 *
 * <p>The ninth of doc 29 section 14's release gates. What it guards against is not a missing
 * logger but a missing history: doc 18 marks every deal transition audited because a sale is
 * the thing somebody will later be asked to justify, and a transition that moved money or
 * inventory without leaving a record is unreconcilable afterwards.
 *
 * <p>Each test asserts the record's CONTENT, not its existence — the action, the tenant it
 * belongs to, the actor who caused it, the entity it is about, and the before and after of
 * what changed (AUD-005). An entry that exists but names the wrong actor is worse than none,
 * because it answers the question wrongly rather than leaving it open.
 *
 * <p>{@link Completeness} is the part that is "generated from the transition table": it
 * enumerates the audit vocabulary itself and fails when a value goes unexercised. A
 * transition added in a later epic without a trail entry fails this build rather than
 * becoming a gap nobody notices until an audit.
 */
@DisplayName("Deal audit coverage (AUD-001)")
class DealAuditCoverageIT extends AbstractPostgresIT {

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private DeveloperService developerService;
    @Autowired private ProjectService projectService;
    @Autowired private UnitService unitService;
    @Autowired private CustomerService customerService;
    @Autowired private ReservationService reservationService;
    @Autowired private DealService dealService;
    @Autowired private PaymentPlanService planService;
    @Autowired private AuditEventRepository auditEvents;

    private TenantProvisioningService.ProvisionedTenant tenant;
    private UUID projectId;
    private UUID unitId;
    private UUID customerId;
    private UUID templateId;

    @BeforeEach
    void provision() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        tenant = provisioning.provision("Audit " + unique, "own_inventory", "Main", "Owner",
                "audit-" + unique + "@example.com", "a-long-enough-password");
        TestIdentity.signOut();
        TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(), Role.OWNER, null);

        projectId = projectService.create("own_inventory", null, null, "Audit Project", null,
                CanonicalDeal.PROJECT_DELIVERY_DATE).id();
        projectService.changeStatus(projectId, ProjectStatus.ACTIVE, "launch");
        unitId = unitService.create(projectId, null, "AUD-101", "apartment", null, null, null,
                CanonicalDeal.LIST_PRICE).id();
        customerId = customerService.create(null, "Buyer", "+201000000004", null, null, null,
                false).id();
        templateId = canonicalTemplate();
    }

    @AfterEach
    void signOut() {
        TestIdentity.signOut();
    }

    // ============================================================ doc 18 section 4

    @Nested
    @DisplayName("every deal transition in doc 18 section 4")
    class DealTransitions {

        @Test
        @DisplayName("— to draft records the unit, customer, frozen price and model")
        void drafting() {
            Deal deal = draft();
            AuditEvent entry = only(AuditAction.DEAL_DRAFTED, "Deal", deal.id());

            assertWellFormed(entry, "Deal", deal.id());
            assertThat(entry.before())
                    .as("a creation has no prior state to record")
                    .isNull();
            assertThat(entry.after())
                    .contains(unitId.toString())
                    .contains(customerId.toString())
                    .contains(CanonicalDeal.LIST_PRICE.toPlainString())
                    .contains("own_inventory")
                    .contains("draft");
        }

        @Test
        @DisplayName("draft to draft (edit) records the concession and both totals")
        void adding_a_discount() {
            Deal deal = draft();
            dealService.addDiscount(deal.id(), Concession.ofPercent(Percentage.of("5")),
                    "Launch offer");
            AuditEvent entry = only(AuditAction.DEAL_DISCOUNT_ADDED, "Deal", deal.id());

            assertWellFormed(entry, "Deal", deal.id());
            // AUD-005: before and after, for a row that changed a price.
            assertThat(entry.before()).contains("0.00");
            assertThat(entry.after())
                    .contains("percent")
                    .contains(CanonicalDeal.TOTAL_DISCOUNT.toPlainString())
                    .contains(CanonicalDeal.NET_VALUE.toPlainString());
            assertThat(entry.reason())
                    .as("why a concession was granted is the point of auditing it")
                    .isEqualTo("Launch offer");
        }

        @Test
        @DisplayName("removing a concession is audited too, with the total it left behind")
        void removing_a_discount() {
            Deal deal = draft();
            dealService.addDiscount(deal.id(), Concession.ofPercent(Percentage.of("5")), null);
            UUID discountId = dealService.discountsOn(deal.id()).get(0).id();
            dealService.removeDiscount(deal.id(), discountId);

            AuditEvent entry = only(AuditAction.DEAL_DISCOUNT_REMOVED, "Deal", deal.id());
            assertWellFormed(entry, "Deal", deal.id());
            assertThat(entry.before()).contains(CanonicalDeal.TOTAL_DISCOUNT.toPlainString());
            assertThat(entry.after())
                    .contains(discountId.toString())
                    .contains(CanonicalDeal.LIST_PRICE.toPlainString());
        }

        @Test
        @DisplayName("R-DISC-5 approval records who approved which figure")
        void approving_a_discount() {
            Deal deal = draft();
            dealService.addDiscount(deal.id(), Concession.ofPercent(Percentage.of("5")), null);
            dealService.approveDiscount(deal.id());

            AuditEvent entry = only(AuditAction.DEAL_DISCOUNT_APPROVED, "Deal", deal.id());
            assertWellFormed(entry, "Deal", deal.id());
            assertThat(entry.actorUserId())
                    .as("an approval whose actor is unknown approves nothing")
                    .isEqualTo(tenant.owner().id());
            assertThat(entry.before()).contains("false");
            assertThat(entry.after())
                    .contains("true")
                    .contains(CanonicalDeal.TOTAL_DISCOUNT.toPlainString());
        }

        @Test
        @DisplayName("draft to active records the schedule, the unit and the deferred commissions")
        void activating() {
            Deal active = activate();
            AuditEvent entry = only(AuditAction.DEAL_ACTIVATED, "Deal", active.id());

            assertWellFormed(entry, "Deal", active.id());
            assertThat(entry.before()).contains(DealStatus.DRAFT.code());
            assertThat(entry.after())
                    .contains(DealStatus.ACTIVE.code())
                    .contains(CanonicalDeal.NET_VALUE.toPlainString())
                    .contains("sold")
                    .contains("installments");

            // Doc 18's result column lists commissions created. They are Epic 8, and the
            // entry says so rather than leaving their absence to be inferred from a field
            // that is not there.
            assertThat(entry.after()).contains("Epic 8");
        }

        @Test
        @DisplayName("the unit's own sale is audited against the unit, as doc 18 section 2 requires")
        void activation_audits_the_unit_too() {
            Deal active = activate();
            AuditEvent entry = only(AuditAction.UNIT_SOLD, "Unit", unitId);

            assertThat(entry.tenantId()).isEqualTo(tenant.tenant().id());
            assertThat(entry.actorUserId())
                    .as("the sale was caused by the person who activated, not by the system")
                    .isEqualTo(tenant.owner().id());
            assertThat(entry.before()).contains("available");
            assertThat(entry.after()).contains("sold");
            assertThat(active.status()).isEqualTo(DealStatus.ACTIVE);
        }

        @Test
        @DisplayName("active to down payment confirmed records a date and creates no payment")
        void confirming_the_down_payment() {
            UUID brokeredDeal = brokeredActiveDeal();
            dealService.confirmDownPaymentReceivedByDeveloper(brokeredDeal,
                    LocalDate.of(2026, 3, 1));

            AuditEvent entry =
                    only(AuditAction.DEAL_DOWN_PAYMENT_CONFIRMED, "Deal", brokeredDeal);
            assertWellFormed(entry, "Deal", brokeredDeal);
            assertThat(entry.before()).contains("false");
            assertThat(entry.after())
                    .contains("2026-03-01")
                    // R-DP-2 and R-PAY-0: a confirmation, never a ledger entry. Recorded in
                    // the trail so the absence is a stated fact rather than an omission.
                    .contains("paymentCreated")
                    .contains("false");
        }

        @Test
        @DisplayName("active to completed is audited with the confirmation state it closed on")
        void completing() {
            UUID brokeredDeal = brokeredActiveDeal();
            dealService.complete(brokeredDeal);

            AuditEvent entry = only(AuditAction.DEAL_COMPLETED, "Deal", brokeredDeal);
            assertWellFormed(entry, "Deal", brokeredDeal);
            assertThat(entry.before()).contains(DealStatus.ACTIVE.code());
            assertThat(entry.after()).contains(DealStatus.COMPLETED.code());
        }

        @Test
        @DisplayName("draft to cancelled records the reason and that no unit came back")
        void cancelling_a_draft() {
            Deal deal = draft();
            dealService.cancel(deal.id(), "Customer withdrew");

            AuditEvent entry = only(AuditAction.DEAL_CANCELLED, "Deal", deal.id());
            assertWellFormed(entry, "Deal", deal.id());
            assertThat(entry.reason()).isEqualTo("Customer withdrew");
            assertThat(entry.before()).contains(DealStatus.DRAFT.code());
            assertThat(entry.after())
                    .contains(DealStatus.CANCELLED.code())
                    .as("doc 18 gives this transition no side effects, and the trail says so")
                    .contains("unitReturned");
        }

        @Test
        @DisplayName("a refused transition writes nothing at all")
        void refusals_leave_no_trail() {
            Deal active = activate();
            long before = countOf(AuditAction.DEAL_CANCELLED, "Deal", active.id());

            assertThatThrownBy(() -> dealService.cancel(active.id(), "Buyer withdrew"))
                    .isInstanceOf(ApiException.class);

            // An audit trail that recorded attempts as though they were transitions would
            // show a cancelled deal that is still active.
            assertThat(countOf(AuditAction.DEAL_CANCELLED, "Deal", active.id()))
                    .isEqualTo(before);
        }
    }

    // ============================================================ doc 18 section 5

    @Nested
    @DisplayName("every plan transition in doc 18 section 5")
    class PlanTransitions {

        @Test
        @DisplayName("generating a schedule is audited against the plan")
        void generating() {
            Deal deal = draft();
            UUID planId = planService.applyTemplate(deal.id(), templateId).plan().id();

            AuditEvent entry =
                    only(AuditAction.PAYMENT_PLAN_GENERATED, "CustomerPaymentPlan", planId);
            assertWellFormed(entry, "CustomerPaymentPlan", planId);
            assertThat(entry.after())
                    .contains(deal.id().toString())
                    .contains(CanonicalDeal.DOWN_PAYMENT.toPlainString())
                    .contains(CanonicalDeal.DELIVERY_PAYMENT.toPlainString());
        }

        @Test
        @DisplayName("regenerating records the version it replaced")
        void regenerating() {
            Deal deal = draft();
            planService.applyTemplate(deal.id(), templateId);
            UUID planId = planService.applyTemplate(deal.id(), templateId).plan().id();

            AuditEvent entry =
                    only(AuditAction.PAYMENT_PLAN_REGENERATED, "CustomerPaymentPlan", planId);
            assertWellFormed(entry, "CustomerPaymentPlan", planId);
            assertThat(entry.before()).contains("\"version\":1");
            assertThat(entry.after()).contains("\"version\":2");
        }

        @Test
        @DisplayName("draft to active on the plan is audited beside the deal's own entry")
        void activating_the_plan() {
            Deal active = activate();
            UUID planId = planService.planFor(active.id()).orElseThrow().plan().id();

            AuditEvent entry =
                    only(AuditAction.PAYMENT_PLAN_ACTIVATED, "CustomerPaymentPlan", planId);
            assertWellFormed(entry, "CustomerPaymentPlan", planId);
            assertThat(entry.before()).contains("draft");
            assertThat(entry.after())
                    .contains("active")
                    .contains(active.id().toString())
                    // R-PLAN-4 as it stood at the moment of activation, so a later dispute
                    // can be answered from the trail alone.
                    .contains(CanonicalDeal.NET_VALUE.toPlainString());
        }

        @Test
        @DisplayName("active to closed is audited when the deal completes")
        void closing_the_plan() {
            UUID brokeredDeal = brokeredActiveDeal();
            UUID planId = planService.planFor(brokeredDeal).orElseThrow().plan().id();
            dealService.complete(brokeredDeal);

            AuditEvent entry =
                    only(AuditAction.PAYMENT_PLAN_CLOSED, "CustomerPaymentPlan", planId);
            assertWellFormed(entry, "CustomerPaymentPlan", planId);
            assertThat(entry.before()).contains("active");
            assertThat(entry.after())
                    .contains("closed")
                    .contains(brokeredDeal.toString());
        }

        @Test
        @DisplayName("template administration is audited")
        void templates() {
            UUID id = planService.createTemplate(null, "Audited template", "10% down",
                    shape()).id();
            assertWellFormed(only(AuditAction.PAYMENT_PLAN_TEMPLATE_CREATED,
                    "PaymentPlanTemplate", id), "PaymentPlanTemplate", id);

            planService.updateTemplate(id, "Audited template", "12% down", shape());
            AuditEvent updated = only(AuditAction.PAYMENT_PLAN_TEMPLATE_UPDATED,
                    "PaymentPlanTemplate", id);
            assertThat(updated.before()).contains("10% down");

            planService.archiveTemplate(id);
            assertThat(only(AuditAction.PAYMENT_PLAN_TEMPLATE_ARCHIVED,
                    "PaymentPlanTemplate", id).after()).contains("false");

            planService.restoreTemplate(id);
            assertThat(only(AuditAction.PAYMENT_PLAN_TEMPLATE_RESTORED,
                    "PaymentPlanTemplate", id).after()).contains("true");
        }
    }

    // ============================================================ doc 18 section 3

    @Test
    @DisplayName("confirmed to converted is audited against the hold, naming its deal")
    void converting_a_reservation() {
        Reservation hold = reservationService.place(unitId, null, customerId, null, null, false);
        reservationService.confirm(hold.id());

        Deal deal = dealService.draft(unitId, customerId, hold.id(), CanonicalDeal.DEAL_DATE);
        dealService.addDiscount(deal.id(), Concession.ofPercent(CanonicalDeal.DISCOUNT_PERCENT),
                null);
        planService.applyTemplate(deal.id(), templateId);
        dealService.activate(deal.id());

        AuditEvent entry =
                only(AuditAction.RESERVATION_CONVERTED, "Reservation", hold.id());
        assertWellFormed(entry, "Reservation", hold.id());
        assertThat(entry.before()).contains("confirmed");
        assertThat(entry.after())
                .contains("converted")
                .as("doc 18's result column is 'deal linked'; the trail is where the link "
                        + "is recorded")
                .contains(deal.id().toString());
    }

    // ============================================================ the gate itself

    @Nested
    @DisplayName("the audit vocabulary is fully exercised")
    class Completeness {

        @Test
        @DisplayName("every deal and plan audit action is reachable and written")
        void no_declared_action_goes_unwritten() {
            // Walks one deal through every transition Epic 5 can reach, then asserts that
            // the actions observed cover the declared vocabulary. This is AUD-001's "one
            // test per transition, generated from the transition table": adding a value to
            // AuditAction without a path that writes it fails here.
            UUID brokeredDeal = brokeredActiveDeal();
            dealService.confirmDownPaymentReceivedByDeveloper(brokeredDeal, null);
            dealService.complete(brokeredDeal);  // also closes the plan

            Deal cancelled = draft();
            dealService.addDiscount(cancelled.id(), Concession.ofPercent(Percentage.of("5")),
                    null);
            dealService.approveDiscount(cancelled.id());
            UUID discountId = dealService.discountsOn(cancelled.id()).get(0).id();
            dealService.removeDiscount(cancelled.id(), discountId);
            planService.applyTemplate(cancelled.id(), templateId);
            planService.applyTemplate(cancelled.id(), templateId);
            dealService.cancel(cancelled.id(), "Exercising the vocabulary");

            UUID template = planService.createTemplate(null, "Vocabulary", null, shape()).id();
            planService.updateTemplate(template, "Vocabulary", "edited", shape());
            planService.archiveTemplate(template);
            planService.restoreTemplate(template);

            Set<String> written = auditEvents.findForTenant(tenant.tenant().id()).stream()
                    .map(AuditEvent::action)
                    .collect(Collectors.toSet());

            List<String> declared = Arrays.stream(AuditAction.values())
                    .map(Enum::name)
                    .filter(name -> name.startsWith("DEAL_") || name.startsWith("PAYMENT_PLAN_"))
                    .toList();

            assertThat(written)
                    .as("every declared deal and plan audit action must have a path that "
                            + "writes it; an unreachable one is a transition somebody "
                            + "recorded in the vocabulary and nowhere else")
                    .containsAll(declared);
        }
    }

    // ==================================================================== helpers

    /**
     * Asserts what every audit entry must carry, whatever transition it records.
     *
     * <p>The tenant, because an entry filed under the wrong one is invisible to the people
     * it concerns and visible to people it does not. The actor, because a transition nobody
     * is recorded as having caused cannot be questioned. The entity, because a trail that
     * does not say what it is about is not a trail.
     */
    private void assertWellFormed(AuditEvent entry, String entityType, UUID entityId) {
        assertThat(entry.tenantId()).isEqualTo(tenant.tenant().id());
        assertThat(entry.actorUserId()).isEqualTo(tenant.owner().id());
        assertThat(entry.entityType()).isEqualTo(entityType);
        assertThat(entry.entityId()).isEqualTo(entityId);
        assertThat(entry.createdAt()).isNotNull();
        assertThat(entry.after())
                .as("a transition with nothing recorded about its result is a log line")
                .isNotBlank();
    }

    /** The single entry for this action on this entity, failing loudly if there is not one. */
    private AuditEvent only(AuditAction action, String entityType, UUID entityId) {
        List<AuditEvent> matching =
                auditEvents.findTrail(tenant.tenant().id(), entityType, entityId).stream()
                        .filter(event -> action.name().equals(event.action()))
                        .toList();

        assertThat(matching)
                .as("expected exactly one %s entry on %s %s", action, entityType, entityId)
                .hasSize(1);
        return matching.get(0);
    }

    private long countOf(AuditAction action, String entityType, UUID entityId) {
        return auditEvents.findTrail(tenant.tenant().id(), entityType, entityId).stream()
                .filter(event -> action.name().equals(event.action()))
                .count();
    }

    private Deal draft() {
        return dealService.draft(unitId, customerId, null, CanonicalDeal.DEAL_DATE);
    }

    private Deal activate() {
        Deal deal = draft();
        dealService.addDiscount(deal.id(), Concession.ofPercent(CanonicalDeal.DISCOUNT_PERCENT),
                null);
        planService.applyTemplate(deal.id(), templateId);
        return dealService.activate(deal.id());
    }

    /** A brokered deal on its own project, active — the only model that reaches E5-S7/S8. */
    private UUID brokeredActiveDeal() {
        UUID developerId = developerService.register("Developer", null, null).id();
        UUID brokeredProject = projectService.create("brokered_inventory", developerId, null,
                "Brokered Audit", null, CanonicalDeal.PROJECT_DELIVERY_DATE).id();
        projectService.changeStatus(brokeredProject, ProjectStatus.ACTIVE, "launch");
        UUID brokeredUnit = unitService.create(brokeredProject, null, "AUD-B1", "apartment",
                null, null, null, CanonicalDeal.LIST_PRICE).id();

        Deal deal = dealService.draft(brokeredUnit, customerId, null, CanonicalDeal.DEAL_DATE);
        dealService.addDiscount(deal.id(), Concession.ofPercent(CanonicalDeal.DISCOUNT_PERCENT),
                null);
        planService.applyTemplate(deal.id(), templateId);
        dealService.activate(deal.id());
        return deal.id();
    }

    private UUID canonicalTemplate() {
        return planService.createTemplate(null, "Canonical " + UUID.randomUUID(), null,
                shape()).id();
    }

    private static PaymentPlanTemplate.Shape shape() {
        return new PaymentPlanTemplate.Shape(
                DownPayment.percent(CanonicalDeal.DOWN_PAYMENT_PERCENT),
                CanonicalDeal.DELIVERY_PERCENT, CanonicalDeal.INSTALLMENT_COUNT,
                CanonicalDeal.FREQUENCY, null);
    }
}
