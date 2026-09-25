package com.rescrm.integration;

import com.rescrm.deals.domain.Concession;
import com.rescrm.deals.domain.Deal;
import com.rescrm.deals.domain.DealStatus;
import com.rescrm.deals.domain.PaymentPlanTemplate;
import com.rescrm.deals.domain.PlanStatus;
import com.rescrm.deals.service.DealService;
import com.rescrm.deals.service.PaymentPlanService;
import com.rescrm.finance.schedule.DownPayment;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.inventory.domain.ProjectStatus;
import com.rescrm.inventory.domain.UnitStatus;
import com.rescrm.inventory.service.ProjectService;
import com.rescrm.inventory.service.UnitService;
import com.rescrm.crm.service.CustomerService;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditEvent;
import com.rescrm.platform.audit.AuditEventRepository;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.security.Role;
import com.rescrm.reservations.domain.Reservation;
import com.rescrm.reservations.domain.ReservationStatus;
import com.rescrm.reservations.service.ReservationService;
import com.rescrm.support.CanonicalDeal;
import com.rescrm.support.TestIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CON-002 — "two simultaneous deal activations on one unit: exactly one succeeds (C1)".
 *
 * <p>One of doc 29 section 14's nine release gates, and the half of the double-sell guard
 * that Epic 3 could not write because deals did not exist. {@code UnitConcurrencyIT} proves
 * the unit row serialises claims; this proves that the whole activation — unit, plan, deal,
 * reservation and audit trail — moves exactly once no matter how many callers arrive at the
 * same instant.
 *
 * <p>Deliberately a real race, in the shape Epic 3 and Epic 4 established: twenty threads,
 * each on its own connection in its own transaction, all released together by a latch. A
 * sequential version of this test passes against a read-then-write implementation, which is
 * precisely the defect it exists to catch.
 *
 * <p>CON-002 is tested from both ends, because C1 acts at two different moments. Two agents
 * drafting against one unit race on the partial unique index; twenty callers activating one
 * draft race on the unit row. Only the second can produce a sold unit, so only the second
 * can double-sell — but a system that allowed two drafts would produce the first race a
 * moment later, with two customers each holding a quote.
 *
 * <p>Every assertion about the losers is about ABSENCE: no second sale, no second active
 * plan, no spurious audit entry. That is the part a happy-path test cannot reach, and the
 * part that matters — a loser that leaves a trace has half-committed something.
 */
@DisplayName("Deal activation under concurrency (CON-002)")
class DealConcurrencyIT extends AbstractPostgresIT {

    private static final int CONTENDERS = 20;

    @Autowired private TenantProvisioningService provisioning;
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
        tenant = provisioning.provision("Deal race " + unique, "own_inventory", "Main", "Owner",
                "deal-race-" + unique + "@example.com", "a-long-enough-password");
        signIn();

        projectId = projectService.create("own_inventory", null, null, "Race Project", null,
                CanonicalDeal.PROJECT_DELIVERY_DATE).id();
        projectService.changeStatus(projectId, ProjectStatus.ACTIVE, "launch");
        unitId = unitService.create(projectId, null, "RACE-1", "apartment", null, null, null,
                CanonicalDeal.LIST_PRICE).id();
        customerId = customerService.create(null, "Buyer", "+201000000003", null, null, null,
                false).id();
        templateId = planService.createTemplate(null, "Race template " + unique, null,
                new PaymentPlanTemplate.Shape(
                        DownPayment.percent(CanonicalDeal.DOWN_PAYMENT_PERCENT),
                        CanonicalDeal.DELIVERY_PERCENT, CanonicalDeal.INSTALLMENT_COUNT,
                        CanonicalDeal.FREQUENCY, null)).id();
    }

    @AfterEach
    void signOut() {
        TestIdentity.signOut();
    }

    private void signIn() {
        TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(), Role.OWNER, null);
    }

    // ==================================================================== the races

    @Test
    @DisplayName("twenty simultaneous activations of one deal: exactly one succeeds")
    void exactly_one_activation_wins() throws Exception {
        UUID dealId = draftWithSchedule();

        List<Outcome<Deal>> outcomes = race(() -> dealService.activate(dealId));

        signIn();
        assertThat(won(outcomes))
                .as("C1 and the unit row between them must admit exactly one activation")
                .isEqualTo(1);

        // Every loser failed for a reason the caller can act on, rather than falling out of
        // the transaction manager as a 500. Two are legitimate and both are correct: the
        // unit was already sold, or the deal had already left draft.
        assertThat(failures(outcomes))
                .hasSize(CONTENDERS - 1)
                .allSatisfy(failure -> assertThat(failure).isInstanceOf(ApiException.class));
    }

    @Test
    @DisplayName("and the deal, its plan and its unit each moved exactly once")
    void the_losers_leave_nothing_behind() throws Exception {
        UUID dealId = draftWithSchedule();

        race(() -> dealService.activate(dealId));
        signIn();

        assertThat(dealService.get(dealId).status()).isEqualTo(DealStatus.ACTIVE);
        assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.SOLD);

        // One active plan, and no draft left over beside it. C3 allows one active plan per
        // deal and a partial index allows one draft; a loser that had got as far as
        // activating the plan before losing the unit would show up as either.
        assertThat(planService.planFor(dealId))
                .hasValueSatisfying(plan ->
                        assertThat(plan.plan().status()).isEqualTo(PlanStatus.ACTIVE));

        // R-PLAN-4 still holds afterwards. A race that duplicated or dropped installment
        // rows would leave a schedule that no longer sums to the net value, which is the
        // shape of corruption nobody notices until a customer disputes a figure.
        assertThat(planService.planFor(dealId).orElseThrow().expectedTotal())
                .isEqualTo(dealService.get(dealId).netValue());
        assertThat(planService.planFor(dealId).orElseThrow().installments())
                .hasSize(CanonicalDeal.EXPECTED_ROW_COUNT);
    }

    @Test
    @DisplayName("the trail records one activation and one sale, not twenty")
    void the_trail_records_one_of_each() throws Exception {
        UUID dealId = draftWithSchedule();

        race(() -> dealService.activate(dealId));
        signIn();

        assertThat(countAction("Deal", dealId, AuditAction.DEAL_ACTIVATED)).isEqualTo(1);
        assertThat(countAction("Unit", unitId, AuditAction.UNIT_SOLD)).isEqualTo(1);

        // A loser that wrote a trail entry for something it then rolled back would make the
        // deal's history a record of sales that never happened.
        List<AuditEvent> plans = auditEvents.findTrail(tenant.tenant().id(),
                "CustomerPaymentPlan", planService.planFor(dealId).orElseThrow().plan().id());
        assertThat(plans)
                .filteredOn(event -> AuditAction.PAYMENT_PLAN_ACTIVATED.name()
                        .equals(event.action()))
                .hasSize(1);
    }

    @Test
    @DisplayName("twenty simultaneous drafts on one unit: exactly one exists afterwards (C1)")
    void exactly_one_draft_wins() throws Exception {
        List<Outcome<Deal>> outcomes = race(() ->
                dealService.draft(unitId, customerId, null, CanonicalDeal.DEAL_DATE));

        signIn();
        assertThat(won(outcomes))
                .as("C1's partial unique index is what decides; no application check "
                        + "participates")
                .isEqualTo(1);

        // The state, not just the return values. Two drafts would mean two agents each
        // quoting the same unit to a different customer, which is the situation C1 exists
        // for even though neither has sold anything yet.
        assertThat(dealService.listVisibleToCaller(PageRequest.of(0, 100)).getContent())
                .filteredOn(deal -> deal.unitId().equals(unitId))
                .hasSize(1);
        assertThat(unitService.get(unitId).status())
                .as("and a draft still does not withhold the unit")
                .isEqualTo(UnitStatus.AVAILABLE);
    }

    @Test
    @DisplayName("a converting hold is converted once, however many callers activate")
    void the_reservation_converts_once() throws Exception {
        Reservation hold = reservationService.place(unitId, null, customerId, null, null, false);
        reservationService.confirm(hold.id());

        Deal deal = dealService.draft(unitId, customerId, hold.id(), CanonicalDeal.DEAL_DATE);
        dealService.addDiscount(deal.id(), Concession.ofPercent(CanonicalDeal.DISCOUNT_PERCENT),
                null);
        planService.applyTemplate(deal.id(), templateId);

        race(() -> dealService.activate(deal.id()));
        signIn();

        assertThat(reservationService.get(hold.id()).status())
                .isEqualTo(ReservationStatus.CONVERTED);
        assertThat(countAction("Reservation", hold.id(), AuditAction.RESERVATION_CONVERTED))
                .as("converting is terminal; a second conversion would be a second sale of "
                        + "the same hold")
                .isEqualTo(1);
        assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.SOLD);
    }

    // ==================================================================== helpers

    /** A draft carrying the canonical discount and a generated schedule, ready to activate. */
    private UUID draftWithSchedule() {
        Deal deal = dealService.draft(unitId, customerId, null, CanonicalDeal.DEAL_DATE);
        dealService.addDiscount(deal.id(), Concession.ofPercent(CanonicalDeal.DISCOUNT_PERCENT),
                null);
        planService.applyTemplate(deal.id(), templateId);
        return deal.id();
    }

    private long countAction(String entityType, UUID entityId, AuditAction action) {
        return auditEvents.findTrail(tenant.tenant().id(), entityType, entityId).stream()
                .filter(event -> action.name().equals(event.action()))
                .count();
    }

    /**
     * What one contender got: a result, or the exception that refused it.
     *
     * <p>{@code UnitConcurrencyIT} can let its work return a value for winners and losers
     * alike, because claiming a unit reports losing as an ordinary outcome. Activating a
     * deal throws, so a race that propagated the first failure would fail the test instead
     * of measuring it.
     */
    private record Outcome<T>(T value, Throwable failure) {

        boolean won() {
            return failure == null;
        }
    }

    private static <T> long won(List<Outcome<T>> outcomes) {
        return outcomes.stream().filter(Outcome::won).count();
    }

    private static <T> List<Throwable> failures(List<Outcome<T>> outcomes) {
        return outcomes.stream()
                .map(Outcome::failure)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Runs {@code work} on {@link #CONTENDERS} threads released together.
     *
     * <p>The latch is what makes this a race. Without it the threads start as the executor
     * gets round to them, which in practice serialises them and lets a read-then-write
     * implementation pass.
     */
    private <T> List<Outcome<T>> race(Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome<T>>> futures = new ArrayList<>();
        try {
            for (int contender = 0; contender < CONTENDERS; contender++) {
                futures.add(pool.submit(() -> {
                    // Each thread authenticates for itself: the tenant and principal are
                    // thread-locals, exactly as they are behind a real request.
                    signIn();
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        return new Outcome<>(work.call(), null);
                    } catch (Throwable refused) {
                        return new Outcome<T>(null, refused);
                    } finally {
                        TestIdentity.signOut();
                    }
                }));
            }
            start.countDown();

            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
