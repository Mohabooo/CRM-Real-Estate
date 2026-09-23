package com.rescrm.integration;

import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.inventory.domain.UnitStatus;
import com.rescrm.inventory.service.ProjectService;
import com.rescrm.inventory.service.UnitClaim;
import com.rescrm.inventory.service.UnitService;
import com.rescrm.platform.audit.AuditEventRepository;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.security.Role;
import com.rescrm.support.TestIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3-S5: "Concurrent reservation attempts: exactly one succeeds. Enforced at database level,
 * verified by a concurrency test."
 *
 * <p>This is that test, and it is deliberately a real one. Twenty threads, each in its own
 * transaction on its own connection, all released at the same instant by a latch, all trying
 * to take the same unit. Anything less — sequential calls, a mocked repository, a single
 * thread asserting that a second call fails — would pass just as happily against a
 * read-then-write implementation, which is the bug this is here to catch.
 *
 * <p>What makes exactly one win is the conditional {@code UPDATE ... WHERE status = 'available'}
 * in {@code UnitRepository.transitionIfInStatus}. Under read-committed isolation the losers
 * block on the row, re-evaluate the predicate against the committed value, and match nothing.
 * The database is the arbiter; no application-level check participates.
 *
 * <p>Doc 22's C1 and C2 will assert the same invariant from the deal and reservation side once
 * those tables exist in Epics 4 and 5. Both layers are wanted — this is the one Epic 3 owns.
 */
@DisplayName("Unit double-sell guard under concurrency")
class UnitConcurrencyIT extends AbstractPostgresIT {

    private static final int CONTENDERS = 20;

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private ProjectService projectService;
    @Autowired private UnitService unitService;
    @Autowired private AuditEventRepository auditEvents;

    private TenantProvisioningService.ProvisionedTenant tenant;
    private UUID projectId;

    @BeforeEach
    void provision() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        tenant = provisioning.provision("Race " + unique, "own_inventory", "Main", "Owner",
                "race-" + unique + "@example.com", "a-long-enough-password");
        signIn();
        projectId = projectService.create("own_inventory", null, null, "Race Project", null,
                null).id();
    }

    @AfterEach
    void signOut() {
        TestIdentity.signOut();
    }

    private void signIn() {
        TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(), Role.OWNER, null);
    }

    private UUID newUnit(String code) {
        return unitService.create(projectId, null, code, "apartment", null, null, null,
                Money.of("3000000.00", CurrencyCode.EGP)).id();
    }

    /**
     * Runs {@code work} on {@link #CONTENDERS} threads released together.
     *
     * <p>The latch matters. Without it the threads start as the executor gets round to them,
     * which in practice serialises them and lets a broken implementation pass.
     */
    private <T> List<T> race(Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int contender = 0; contender < CONTENDERS; contender++) {
                futures.add(pool.submit(() -> {
                    // Each thread authenticates for itself: the tenant and principal are
                    // thread-locals, exactly as they are behind a real request.
                    signIn();
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        return work.call();
                    } finally {
                        TestIdentity.signOut();
                    }
                }));
            }
            start.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("twenty simultaneous reservation attempts: exactly one succeeds")
    void exactly_one_reservation_wins() throws Exception {
        UUID unitId = newUnit("RACE-1");

        List<UnitClaim> claims = race(() -> unitService.claimForReservation(unitId));

        assertThat(claims).filteredOn(UnitClaim::won)
                .as("exactly one of %d contenders may take the unit", CONTENDERS)
                .hasSize(1);
        assertThat(claims).filteredOn(claim -> !claim.won())
                .hasSize(CONTENDERS - 1)
                .allSatisfy(claim -> assertThat(claim.statusNow())
                        .as("a loser is told the unit is reserved, not that it failed")
                        .isEqualTo(UnitStatus.RESERVED));

        signIn();
        assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.RESERVED);
    }

    @Test
    @DisplayName("twenty simultaneous sale attempts: exactly one succeeds")
    void exactly_one_sale_wins() throws Exception {
        UUID unitId = newUnit("RACE-2");

        List<UnitClaim> claims = race(() -> unitService.claimForSale(unitId));

        assertThat(claims).filteredOn(UnitClaim::won).hasSize(1);

        signIn();
        assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.SOLD);
    }

    @Test
    @DisplayName("a reserved unit is not in open inventory, so an ordinary sale cannot take it")
    void a_reserved_unit_is_not_for_sale() throws Exception {
        // This assertion is the opposite of what it was, and the change is deliberate.
        // claimForSale used to fall back to reserved -> sold on the grounds that doc 18
        // section 2 permits that transition. It does — but only for the hold on the unit
        // converting into a deal, not for a second buyer. Falling back unconditionally let
        // a sale take a unit actively reserved for somebody else, which a reservation
        // concurrency test caught by having a confirmation and a sale both win.
        UUID unitId = newUnit("RACE-3");
        assertThat(unitService.claimForReservation(unitId).won()).isTrue();

        List<UnitClaim> claims = race(() -> unitService.claimForSale(unitId));

        assertThat(claims).filteredOn(UnitClaim::won)
                .as("a held unit must be released before it can be sold to somebody else")
                .isEmpty();

        signIn();
        assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.RESERVED);
    }

    @Test
    @DisplayName("but the hold converting does sell it, by exactly one caller")
    void exactly_one_conversion_wins_against_a_reserved_unit() throws Exception {
        UUID unitId = newUnit("RACE-3B");
        assertThat(unitService.claimForReservation(unitId).won()).isTrue();

        // The conversion path: doc 18's reserved -> sold, which never passes back through
        // available because that would open a window for somebody else to take the unit.
        List<UnitClaim> claims = race(() -> unitService.claimForSaleOnConversion(unitId));

        assertThat(claims).filteredOn(UnitClaim::won).hasSize(1);

        signIn();
        assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.SOLD);
    }

    @Test
    @DisplayName("exactly one audit entry records the transition, not twenty")
    void the_trail_records_one_transition() throws Exception {
        UUID unitId = newUnit("RACE-4");

        race(() -> unitService.claimForSale(unitId));

        signIn();
        long soldEntries = auditEvents.findTrail(tenant.tenant().id(), "Unit", unitId).stream()
                .filter(event -> "UNIT_SOLD".equals(event.action()))
                .count();

        // A loser must not write a trail entry for something it did not do. Nineteen
        // spurious "sold" entries would make the unit's history unreadable, which is the
        // one thing GET /units/{id}/history exists to provide.
        assertThat(soldEntries).isEqualTo(1);
    }

    @Test
    @DisplayName("twenty threads claiming twenty different units all succeed")
    void independent_units_do_not_block_each_other() throws Exception {
        List<UUID> unitIds = new ArrayList<>();
        for (int index = 0; index < CONTENDERS; index++) {
            unitIds.add(newUnit("FREE-" + index));
        }
        AtomicInteger next = new AtomicInteger();

        List<UnitClaim> claims = race(() ->
                unitService.claimForSale(unitIds.get(next.getAndIncrement())));

        // The guard must serialise contention for one unit without serialising the whole
        // table: a lock that made unrelated sales wait for each other would be correct and
        // unusable.
        assertThat(claims).filteredOn(UnitClaim::won).hasSize(CONTENDERS);
    }
}
