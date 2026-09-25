package com.rescrm.integration;

import com.rescrm.crm.service.CustomerService;
import com.rescrm.deals.domain.Concession;
import com.rescrm.deals.domain.Deal;
import com.rescrm.deals.domain.DealStatus;
import com.rescrm.deals.domain.PaymentPlanTemplate;
import com.rescrm.deals.domain.PlanStatus;
import com.rescrm.deals.repository.CustomerPaymentPlanRepository;
import com.rescrm.deals.repository.DealDiscountRepository;
import com.rescrm.deals.repository.DealRepository;
import com.rescrm.deals.repository.InstallmentRepository;
import com.rescrm.deals.repository.PaymentPlanTemplateRepository;
import com.rescrm.deals.service.DealService;
import com.rescrm.deals.service.PaymentPlanService;
import com.rescrm.finance.schedule.DownPayment;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.inventory.domain.ProjectStatus;
import com.rescrm.inventory.domain.UnitStatus;
import com.rescrm.inventory.service.ProjectService;
import com.rescrm.inventory.service.UnitService;
import com.rescrm.platform.audit.AuditEventRepository;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.Percentage;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.tenancy.TenantContext;
import com.rescrm.support.CanonicalDeal;
import com.rescrm.support.TestIdentity;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CON-006 and CON-007 for the deal module: the tests that must fail if Epic 5's tenant
 * isolation is ever weakened.
 *
 * <p>Same shape as the identity, CRM, inventory and reservation suites. What raises the
 * stakes here is what a deal is: the one record that moves both money and inventory. A
 * cross-tenant leak in reservations shows somebody a hold; the same leak here shows them a
 * competitor's prices, their customers, and the schedule those customers are paying — and a
 * cross-tenant WRITE could sell one tenant's unit out from under them.
 *
 * <p>Both layers are asserted, because the design depends on both and either alone would be
 * a single point of failure. The service layer answers 404 for another tenant's id, and the
 * database refuses the row regardless of what the service believes: doc 21's two-layer
 * isolation is only real if neither is load-bearing on its own.
 *
 * <p>Every "cannot read" assertion also checks that A sees its OWN equivalent record. A test
 * that only proves an empty result passes just as happily against a query that returns
 * nothing for anybody.
 */
@DisplayName("Tenant isolation — deals")
class DealTenantIsolationIT extends AbstractPostgresIT {

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private ProjectService projectService;
    @Autowired private UnitService unitService;
    @Autowired private CustomerService customerService;
    @Autowired private DealService dealService;
    @Autowired private PaymentPlanService planService;
    @Autowired private DealRepository deals;
    @Autowired private DealDiscountRepository discounts;
    @Autowired private PaymentPlanTemplateRepository templates;
    @Autowired private CustomerPaymentPlanRepository plans;
    @Autowired private InstallmentRepository installments;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private JdbcTemplate jdbc;

    private record Tenancy(TenantProvisioningService.ProvisionedTenant tenant, UUID unitId,
                           UUID customerId, UUID templateId) {

        UUID id() {
            return tenant.tenant().id();
        }
    }

    private Tenancy tenantA;
    private Tenancy tenantB;

    /** B's deal is ACTIVE with a live schedule — the most valuable thing to leak. */
    private UUID dealOfB;
    private UUID planOfB;
    private UUID discountOfB;

    /** A's deal is a draft, so A has something of its own for every positive assertion. */
    private UUID dealOfA;

    @BeforeEach
    void provisionTwoTenants() {
        tenantB = stocked("B");
        asOwnerOf(tenantB);
        Deal b = dealService.draft(tenantB.unitId(), tenantB.customerId(), null,
                CanonicalDeal.DEAL_DATE);
        dealService.addDiscount(b.id(), Concession.ofPercent(CanonicalDeal.DISCOUNT_PERCENT),
                "B's launch offer");
        discountOfB = dealService.discountsOn(b.id()).get(0).id();
        planOfB = planService.applyTemplate(b.id(), tenantB.templateId()).plan().id();
        dealOfB = dealService.activate(b.id()).id();

        tenantA = stocked("A");
        asOwnerOf(tenantA);
        dealOfA = dealService.draft(tenantA.unitId(), tenantA.customerId(), null,
                CanonicalDeal.DEAL_DATE).id();
        planService.applyTemplate(dealOfA, tenantA.templateId());
    }

    @AfterEach
    void signOut() {
        TestIdentity.signOut();
    }

    // ==================================================================== cannot read

    @Nested
    @DisplayName("Tenant A cannot READ tenant B")
    class CannotRead {

        @Test
        @DisplayName("B's deal is simply not found")
        void the_deal_is_not_found() {
            assertNotFound(() -> dealService.get(dealOfB));
            assertNotFound(() -> dealService.discountsOn(dealOfB));
            assertNotFound(() -> dealService.history(dealOfB));
            assertNotFound(() -> planService.templatesOfferedForDeal(dealOfB));
        }

        @Test
        @DisplayName("and neither is its schedule, which is where the money is")
        void the_schedule_is_not_found() {
            // planFor takes a deal id and scopes by tenant rather than checking the deal
            // exists, so an empty answer here is the isolation working, not a 404.
            assertThat(planService.planFor(dealOfB)).isEmpty();
            assertThat(planService.planFor(dealOfA))
                    .as("A can still see its own, so this is scoping and not a dead query")
                    .isPresent();
        }

        @Test
        @DisplayName("listings contain only A's deals, and are not merely empty")
        void listings_are_scoped() {
            List<Deal> visible =
                    dealService.listVisibleToCaller(PageRequest.of(0, 50)).getContent();

            assertThat(visible).isNotEmpty();
            assertThat(visible).extracting(Deal::tenantId).containsOnly(tenantA.id());
            assertThat(visible).extracting(Deal::id)
                    .contains(dealOfA)
                    .doesNotContain(dealOfB);
        }

        @Test
        @DisplayName("B's payment plan templates are not offered to A")
        void templates_are_scoped() {
            assertThat(planService.listTemplates())
                    .isNotEmpty()
                    .extracting(PaymentPlanTemplate::id)
                    .contains(tenantA.templateId())
                    .doesNotContain(tenantB.templateId());

            assertNotFound(() -> planService.getTemplate(tenantB.templateId()));
        }

        @Test
        @DisplayName("repository finders scoped to A return nothing for B's ids")
        void repositories_refuse_cross_tenant_ids() {
            UUID a = tenantA.id();

            assertThat(deals.findByTenantIdAndId(a, dealOfB)).isEmpty();
            assertThat(deals.findLiveForUnit(a, tenantB.unitId())).isEmpty();
            assertThat(discounts.findAllForDeal(a, dealOfB)).isEmpty();
            assertThat(plans.findByTenantIdAndId(a, planOfB)).isEmpty();
            assertThat(plans.findActiveForDeal(a, dealOfB)).isEmpty();
            assertThat(plans.findAllForDeal(a, dealOfB)).isEmpty();
            assertThat(installments.findAllForPlan(a, planOfB)).isEmpty();
            assertThat(installments.findLiveForDeal(a, dealOfB)).isEmpty();
            assertThat(templates.findByTenantIdAndId(a, tenantB.templateId())).isEmpty();

            // The same finders against A's own ids are not empty, so none of the above is
            // passing because the query is broken.
            assertThat(deals.findByTenantIdAndId(a, dealOfA)).isPresent();
            assertThat(plans.findDraftForDeal(a, dealOfA)).isPresent();
        }

        @Test
        @DisplayName("passing B's tenant id to a repository does not widen anything")
        void naming_bs_tenant_id_does_not_help() {
            // The repository predicate says tenant B, and the connection is stamped for A.
            // Neither layer alone would stop this: the predicate matches B's rows, and RLS
            // is what makes them invisible. This is the test that proves the second layer
            // is doing work rather than sitting behind a correct first one.
            assertThat(deals.findByTenantIdAndId(tenantB.id(), dealOfB)).isEmpty();
            assertThat(plans.findByTenantIdAndId(tenantB.id(), planOfB)).isEmpty();
            assertThat(installments.findAllForPlan(tenantB.id(), planOfB)).isEmpty();
            assertThat(discounts.findAllForDeal(tenantB.id(), dealOfB)).isEmpty();
        }

        @Test
        @DisplayName("B's deal audit trail is invisible to A")
        void the_trail_is_scoped() {
            assertThat(auditEvents.findTrail(tenantA.id(), "Deal", dealOfB)).isEmpty();
            assertThat(auditEvents.findTrail(tenantA.id(), "CustomerPaymentPlan", planOfB))
                    .isEmpty();
            assertThat(auditEvents.findTrail(tenantA.id(), "Deal", dealOfA)).isNotEmpty();
        }

        @Test
        @DisplayName("CON-007 — raw SQL with no tenant established returns no rows at all")
        void rls_refuses_an_unbound_connection() {
            TestIdentity.signOut();
            TenantContext.clear();

            // Not "returns another tenant's rows" and not "returns everything": zero. A
            // connection that never established a tenant is one the policies match nothing
            // for, which is the only safe reading of an unknown caller.
            for (String table : List.of("deals", "deal_discounts", "customer_payment_plans",
                    "installments", "payment_plan_templates")) {
                Long rows = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
                assertThat(rows).as("%s with no tenant established", table).isZero();
            }
        }

        @Test
        @DisplayName("and with A's tenant established it returns A's rows and only A's")
        void rls_scopes_a_bound_connection() {
            Long forA = TenantContext.callAs(tenantA.id(), () -> jdbc.queryForObject(
                    "SELECT count(*) FROM deals WHERE id = ?", Long.class, dealOfA));
            Long bForA = TenantContext.callAs(tenantA.id(), () -> jdbc.queryForObject(
                    "SELECT count(*) FROM deals WHERE id = ?", Long.class, dealOfB));

            assertThat(forA).isEqualTo(1);
            assertThat(bForA).as("B's deal row does not exist as far as A's session is "
                    + "concerned").isZero();
        }
    }

    // ================================================================== cannot modify

    @Nested
    @DisplayName("Tenant A cannot MODIFY tenant B")
    class CannotModify {

        @Test
        @DisplayName("every transition on B's deal is not found")
        void transitions_are_refused() {
            assertNotFound(() -> dealService.activate(dealOfB));
            assertNotFound(() -> dealService.cancel(dealOfB, "taking this"));
            assertNotFound(() -> dealService.complete(dealOfB));
            assertNotFound(() -> dealService.confirmDownPaymentReceivedByDeveloper(dealOfB,
                    LocalDate.of(2026, 5, 1)));
            assertNotFound(() -> dealService.approveDiscount(dealOfB));
            assertNotFound(() -> dealService.addDiscount(dealOfB,
                    Concession.ofPercent(Percentage.of("50")), "taking this"));
            assertNotFound(() -> dealService.removeDiscount(dealOfB, discountOfB));
        }

        @Test
        @DisplayName("and B's schedule cannot be regenerated out from under it")
        void the_schedule_cannot_be_rewritten() {
            assertNotFound(() -> planService.applyTemplate(dealOfB, tenantA.templateId()));
            assertNotFound(() -> planService.applyTemplate(dealOfB, tenantB.templateId()));
        }

        @Test
        @DisplayName("nor can B's templates be archived, renamed or restored")
        void templates_cannot_be_administered() {
            assertNotFound(() -> planService.archiveTemplate(tenantB.templateId()));
            assertNotFound(() -> planService.restoreTemplate(tenantB.templateId()));
            assertNotFound(() -> planService.updateTemplate(tenantB.templateId(), "stolen",
                    null, shapeOf()));
        }

        @Test
        @DisplayName("B's unit cannot be drafted against, so it cannot be sold twice")
        void bs_unit_cannot_be_drafted_against() {
            assertNotFound(() -> dealService.draft(tenantB.unitId(), tenantA.customerId(),
                    null, CanonicalDeal.DEAL_DATE));
            assertNotFound(() -> dealService.draft(tenantA.unitId(), tenantB.customerId(),
                    null, CanonicalDeal.DEAL_DATE));
        }

        @Test
        @DisplayName("and after all of that, B's deal is genuinely untouched")
        void bs_deal_is_untouched() {
            assertNotFound(() -> dealService.cancel(dealOfB, "taking this"));
            assertNotFound(() -> planService.applyTemplate(dealOfB, tenantA.templateId()));

            asOwnerOf(tenantB);
            Deal b = dealService.get(dealOfB);

            assertThat(b.status()).isEqualTo(DealStatus.ACTIVE);
            assertThat(b.netValue()).isEqualTo(CanonicalDeal.NET_VALUE);
            assertThat(b.totalDiscount()).isEqualTo(CanonicalDeal.TOTAL_DISCOUNT);
            assertThat(dealService.discountsOn(dealOfB)).hasSize(1);
            assertThat(unitService.get(tenantB.unitId()).status()).isEqualTo(UnitStatus.SOLD);

            // The schedule is the part a silent cross-tenant write would corrupt without
            // anybody noticing until a customer queried an installment.
            var plan = planService.planFor(dealOfB).orElseThrow();
            assertThat(plan.plan().status()).isEqualTo(PlanStatus.ACTIVE);
            assertThat(plan.plan().id()).isEqualTo(planOfB);
            assertThat(plan.installments()).hasSize(CanonicalDeal.EXPECTED_ROW_COUNT);
            assertThat(plan.expectedTotal()).isEqualTo(CanonicalDeal.NET_VALUE);
        }

        @Test
        @DisplayName("CON-007 — a direct UPDATE from A's session touches none of B's rows")
        void rls_refuses_a_cross_tenant_write() {
            Integer updated = TenantContext.callAs(tenantA.id(), () -> jdbc.update(
                    "UPDATE deals SET status = 'cancelled' WHERE id = ?", dealOfB));
            Integer voided = TenantContext.callAs(tenantA.id(), () -> jdbc.update(
                    "UPDATE installments SET status = 'void' WHERE plan_id = ?", planOfB));

            // Zero rows, not an error: the policy makes B's rows invisible, so the UPDATE
            // is well-formed and matches nothing. That is the behaviour to assert, because
            // an implementation that raised instead would still be safe while one that
            // matched rows would not.
            assertThat(updated).isZero();
            assertThat(voided).isZero();

            asOwnerOf(tenantB);
            assertThat(dealService.get(dealOfB).status()).isEqualTo(DealStatus.ACTIVE);
            assertThat(planService.planFor(dealOfB).orElseThrow().expectedTotal())
                    .isEqualTo(CanonicalDeal.NET_VALUE);
        }

        @Test
        @DisplayName("CON-007 — and a direct INSERT cannot plant a row in B's tenant")
        void rls_refuses_a_cross_tenant_insert() {
            assertThatThrownBy(() -> TenantContext.callAs(tenantA.id(), () -> jdbc.update(
                    "INSERT INTO payment_plan_templates (tenant_id, name, down_payment_type, "
                            + "down_payment_value, delivery_payment_percent, "
                            + "installment_count, frequency) "
                            + "VALUES (?, 'planted', 'percent', 10, 5, 12, 'monthly')",
                    tenantB.id())))
                    .as("the WITH CHECK half of the policy is what stops a row being "
                            + "written into somebody else's tenant")
                    .isInstanceOf(Exception.class);
        }
    }

    // ==================================================================== helpers

    private Tenancy stocked(String label) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        var tenant = provisioning.provision("DealIso " + label + " " + unique, "own_inventory",
                label + " Branch", "Owner " + label,
                "dealiso-" + label.toLowerCase() + "-" + unique + "@example.com",
                "a-long-enough-password");
        TestIdentity.signOut();
        TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(), Role.OWNER, null);

        UUID projectId = projectService.create("own_inventory", null, null, label + " Project",
                null, CanonicalDeal.PROJECT_DELIVERY_DATE).id();
        projectService.changeStatus(projectId, ProjectStatus.ACTIVE, "launch");
        UUID unitId = unitService.create(projectId, null, label + "-101", "apartment", null,
                null, null, CanonicalDeal.LIST_PRICE).id();
        UUID customerId = customerService.create(null, label + " Buyer",
                "+2010" + Math.abs(unique.hashCode()), null, null, null, true).id();
        UUID templateId = planService.createTemplate(null, label + " template " + unique, null,
                shapeOf()).id();

        return new Tenancy(tenant, unitId, customerId, templateId);
    }

    private void asOwnerOf(Tenancy tenancy) {
        TestIdentity.signOut();
        TestIdentity.signIn(tenancy.tenant().owner().id(), tenancy.id(), Role.OWNER, null);
    }

    private static PaymentPlanTemplate.Shape shapeOf() {
        return new PaymentPlanTemplate.Shape(
                DownPayment.percent(CanonicalDeal.DOWN_PAYMENT_PERCENT),
                CanonicalDeal.DELIVERY_PERCENT, CanonicalDeal.INSTALLMENT_COUNT,
                CanonicalDeal.FREQUENCY, null);
    }

    private static void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                        .as("cross-tenant access must answer 404, never 403 (doc 28 section 6)")
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }
}
