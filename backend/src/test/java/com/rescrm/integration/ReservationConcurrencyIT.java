package com.rescrm.integration;

import com.rescrm.crm.service.LeadService;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.inventory.domain.ProjectStatus;
import com.rescrm.inventory.domain.UnitStatus;
import com.rescrm.inventory.service.ProjectService;
import com.rescrm.inventory.service.UnitService;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.security.Role;
import com.rescrm.reservations.domain.Reservation;
import com.rescrm.reservations.repository.ReservationRepository;
import com.rescrm.reservations.service.ReservationService;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3-S5's other half, now that reservations exist: "concurrent reservation attempts —
 * exactly one succeeds."
 *
 * <p>Epic 3 could only guard the unit, with a conditional UPDATE, because this table did not
 * exist. Doc 22's C2 partial unique index now guards the hold itself, and the two are
 * independent: the index stops a second live hold even if the service forgets to look, and
 * the conditional update stops a second claim on the unit even if the index is ever dropped.
 * Both are exercised here.
 *
 * <p>Twenty threads, each in its own transaction on its own connection, released together by
 * a latch. Sequential calls would pass against a check-then-insert implementation, which is
 * the bug this exists to catch — the gap between the check and the insert is exactly where
 * the second hold gets in.
 */
@DisplayName("Reservations under concurrency")
class ReservationConcurrencyIT extends AbstractPostgresIT {

    private static final int CONTENDERS = 20;

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private ProjectService projectService;
    @Autowired private UnitService unitService;
    @Autowired private LeadService leadService;
    @Autowired private ReservationService reservationService;
    @Autowired private ReservationRepository reservations;

    private TenantProvisioningService.ProvisionedTenant tenant;
    private UUID projectId;
    private UUID leadId;

    @BeforeEach
    void provision() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        tenant = provisioning.provision("ResRace " + unique, "own_inventory", "Main", "Owner",
                "resrace-" + unique + "@example.com", "a-long-enough-password");
        signIn();
        projectId = projectService.create("own_inventory", null, null, "Race Project", null,
                null).id();
        projectService.changeStatus(projectId, ProjectStatus.ACTIVE, "launch");
        leadId = leadService.capture("Race Buyer", "+201000000123", null, "walk-in",
                tenant.initialBranch().id(), null, true).id();
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

    /** Runs {@code work} on {@link #CONTENDERS} threads released at the same instant. */
    private <T> List<Outcome<T>> race(Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome<T>>> futures = new ArrayList<>();
        try {
            for (int contender = 0; contender < CONTENDERS; contender++) {
                futures.add(pool.submit(() -> {
                    // Each thread authenticates for itself: tenant and principal are
                    // thread-locals, exactly as they are behind a real request.
                    signIn();
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        return Outcome.succeeded(work.call());
                    } catch (ApiException e) {
                        return Outcome.<T>refused(e.code());
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

    private record Outcome<T>(T value, ErrorCode refusedWith) {

        static <T> Outcome<T> succeeded(T value) {
            return new Outcome<>(value, null);
        }

        static <T> Outcome<T> refused(ErrorCode code) {
            return new Outcome<>(null, code);
        }

        boolean won() {
            return refusedWith == null;
        }
    }

    @Test
    @DisplayName("twenty agents placing a hold on one unit: exactly one succeeds")
    void exactly_one_hold_is_placed() throws Exception {
        UUID unitId = newUnit("RACE-1");

        List<Outcome<Reservation>> outcomes =
                race(() -> reservationService.place(unitId, leadId, null, null, null, false));

        assertThat(outcomes).filteredOn(Outcome::won)
                .as("C2 permits exactly one live hold per unit")
                .hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.won())
                .hasSize(CONTENDERS - 1)
                .allSatisfy(outcome -> assertThat(outcome.refusedWith())
                        .as("a loser is told the unit is held, not that something broke")
                        .isEqualTo(ErrorCode.CONFLICT));

        signIn();
        assertThat(reservations.findActiveForUnit(tenant.tenant().id(), unitId)).isPresent();
    }

    @Test
    @DisplayName("twenty managers confirming one hold: exactly one claims the unit")
    void exactly_one_confirmation_claims_the_unit() throws Exception {
        UUID unitId = newUnit("RACE-2");
        signIn();
        UUID holdId = reservationService.place(unitId, leadId, null, null, null, false).id();

        List<Outcome<Reservation>> outcomes = race(() -> reservationService.confirm(holdId));

        // The unit's own guard is what decides this one: the reservation row is the same
        // row for everybody, so the tie is broken by the conditional UPDATE on units.status.
        assertThat(outcomes).filteredOn(Outcome::won).hasSize(1);

        signIn();
        assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.RESERVED);
        assertThat(reservationService.get(holdId).status().code()).isEqualTo("confirmed");
    }

    @Test
    @DisplayName("a hold and a direct sale race for the same unit; only one wins")
    void a_hold_and_a_sale_cannot_both_win() throws Exception {
        UUID unitId = newUnit("RACE-3");
        signIn();
        UUID holdId = reservationService.place(unitId, leadId, null, null, null, false).id();

        // Confirming the hold claims the unit as reserved; claiming it for sale takes it to
        // sold. Both run at once, and the unit must end in exactly one of those states with
        // the loser told rather than silently overwritten.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> confirming = pool.submit(() -> {
                signIn();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    reservationService.confirm(holdId);
                    return true;
                } catch (ApiException e) {
                    return false;
                } finally {
                    TestIdentity.signOut();
                }
            });
            Future<Boolean> selling = pool.submit(() -> {
                signIn();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    return unitService.claimForSale(unitId).won();
                } finally {
                    TestIdentity.signOut();
                }
            });
            start.countDown();

            boolean confirmed = confirming.get(60, TimeUnit.SECONDS);
            boolean sold = selling.get(60, TimeUnit.SECONDS);

            signIn();
            UnitStatus finalStatus = unitService.get(unitId).status();
            assertThat(finalStatus).isIn(UnitStatus.RESERVED, UnitStatus.SOLD);
            assertThat(confirmed && sold)
                    .as("the unit cannot be both reserved for one buyer and sold to another")
                    .isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("twenty units, twenty agents: contention on one does not block the rest")
    void independent_units_do_not_block_each_other() throws Exception {
        List<UUID> unitIds = new ArrayList<>();
        for (int index = 0; index < CONTENDERS; index++) {
            unitIds.add(newUnit("FREE-" + index));
        }
        java.util.concurrent.atomic.AtomicInteger next =
                new java.util.concurrent.atomic.AtomicInteger();

        List<Outcome<Reservation>> outcomes = race(() -> reservationService.place(
                unitIds.get(next.getAndIncrement()), leadId, null, null, null, false));

        // C2 must serialise contention for one unit without serialising the table: an index
        // that made unrelated holds wait for each other would be correct and unusable.
        assertThat(outcomes).filteredOn(Outcome::won).hasSize(CONTENDERS);
    }
}
