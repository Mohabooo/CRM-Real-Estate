package com.rescrm.integration;

import com.rescrm.crm.service.LeadService;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.inventory.domain.ProjectStatus;
import com.rescrm.inventory.domain.UnitStatus;
import com.rescrm.inventory.service.ProjectService;
import com.rescrm.inventory.service.UnitService;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditEvent;
import com.rescrm.platform.audit.AuditEventRepository;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.tenancy.PlatformTenantRegistry;
import com.rescrm.platform.tenancy.TenantContext;
import com.rescrm.reservations.domain.Reservation;
import com.rescrm.reservations.domain.ReservationStatus;
import com.rescrm.reservations.service.ExpirySweepResult;
import com.rescrm.reservations.service.ReservationExpirySweep;
import com.rescrm.reservations.service.ReservationService;
import com.rescrm.support.TestIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E4-S2: the sweep that expires stale holds and frees the units they were withholding.
 *
 * <p>Two things here are worth more than the happy path.
 *
 * <p>The sweep runs with no principal, as doc 18's SYS actor, and the audit entries it
 * writes must record a null actor — which is what V2 documents a null {@code actor_user_id}
 * to mean. If an expiry ever recorded a user, the trail would be claiming somebody did
 * something they did not.
 *
 * <p>And it runs per tenant. A cross-tenant query would return nothing in production,
 * because an unset tenant matches no rows under row-level security, while passing here,
 * because this connection is a superuser and bypasses RLS entirely. So the test that matters
 * is the one proving a second tenant's expired hold is also swept — not that the query
 * compiles.
 */
@DisplayName("Reservation expiry sweep")
class ReservationExpiryIT extends AbstractPostgresIT {

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private ProjectService projectService;
    @Autowired private UnitService unitService;
    @Autowired private LeadService leadService;
    @Autowired private ReservationService reservationService;
    @Autowired private ReservationExpirySweep sweep;
    @Autowired private PlatformTenantRegistry registry;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private JdbcTemplate jdbc;

    private record Fixture(TenantProvisioningService.ProvisionedTenant tenant, UUID unitId,
                           UUID leadId) { }

    private Fixture fixture;

    @BeforeEach
    void provision() {
        fixture = newTenantWithAUnit("Exp");
    }

    @AfterEach
    void signOut() {
        TestIdentity.signOut();
    }

    private Fixture newTenantWithAUnit(String prefix) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        var tenant = provisioning.provision(prefix + " " + unique, "own_inventory", "Main",
                "Owner", prefix.toLowerCase() + "-" + unique + "@example.com",
                "a-long-enough-password");
        TestIdentity.signOut();
        TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(), Role.OWNER, null);

        UUID projectId = projectService.create("own_inventory", null, null, prefix + " Project",
                null, null).id();
        projectService.changeStatus(projectId, ProjectStatus.ACTIVE, "launch");
        UUID unitId = unitService.create(projectId, null, prefix + "-1", null, null, null, null,
                Money.of("2500000.00", CurrencyCode.EGP)).id();
        UUID leadId = leadService.capture(prefix + " Buyer",
                "+2010" + String.format("%07d", Math.abs(unique.hashCode() % 10_000_000)),
                null, "walk-in", tenant.initialBranch().id(), null, true).id();
        return new Fixture(tenant, unitId, leadId);
    }

    private void signIn(Fixture f) {
        TestIdentity.signOut();
        TestIdentity.signIn(f.tenant().owner().id(), f.tenant().tenant().id(), Role.OWNER, null);
    }

    /**
     * Ages the hold so the sweep has something genuinely due.
     *
     * <p>The whole hold moves back, not just its expiry. Backdating the expiry alone would
     * leave a row that expired before it was placed, which the schema refuses
     * ({@code chk_reservations_expiry_after_start}) and rightly so — a test that fabricates
     * a row the application could never produce proves nothing about the application. What
     * this writes is an ordinary hold placed eight days ago whose week has run out.
     */
    private void makeOverdue(Fixture owner, Reservation held) {
        // Under that hold's OWN tenant: this is fixture SQL like any other write, and
        // row-level security applies to it exactly as it applies to the application. The
        // tenant has to be the reservation's, not a fixed one — the sweep test backdates a
        // second tenant's hold as well.
        TenantContext.callAs(owner.tenant().tenant().id(),
                () -> jdbc.update("UPDATE reservations SET "
                + "reserved_at = now() - interval '8 days', "
                + "expires_at = now() - interval '1 hour', "
                + "original_expires_at = now() - interval '1 hour' "
                        + "WHERE id = ?", held.id()));
    }

    private Reservation confirmedHold(Fixture f) {
        signIn(f);
        Reservation held = reservationService.place(f.unitId(), f.leadId(), null, null, null,
                false);
        return reservationService.confirm(held.id());
    }

    @Test
    @DisplayName("an expired hold is expired and its unit comes back")
    void the_unit_comes_back() {
        Reservation held = confirmedHold(fixture);
        assertThat(unitService.get(fixture.unitId()).status()).isEqualTo(UnitStatus.RESERVED);
        makeOverdue(fixture, held);

        ExpirySweepResult result = sweep.sweep();

        assertThat(result.expired()).contains(held.id());
        assertThat(result.unitsReleased()).contains(fixture.unitId());
        assertThat(result.isClean()).isTrue();

        signIn(fixture);
        assertThat(reservationService.get(held.id()).status())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(unitService.get(fixture.unitId()).status()).isEqualTo(UnitStatus.AVAILABLE);
    }

    @Test
    @DisplayName("a pending hold expires too, and withholds no unit to give back")
    void a_pending_hold_expires_without_a_unit() {
        signIn(fixture);
        Reservation held = reservationService.place(fixture.unitId(), fixture.leadId(), null,
                null, null, false);
        makeOverdue(fixture, held);

        ExpirySweepResult result = sweep.sweep();

        assertThat(result.expired()).contains(held.id());
        assertThat(result.unitsReleased())
                .as("a pending hold never withheld the unit, so none is released")
                .doesNotContain(fixture.unitId());

        signIn(fixture);
        assertThat(unitService.get(fixture.unitId()).status()).isEqualTo(UnitStatus.AVAILABLE);
    }

    @Test
    @DisplayName("a hold that has not run out is left alone")
    void a_live_hold_is_untouched() {
        Reservation held = confirmedHold(fixture);

        ExpirySweepResult result = sweep.sweep();

        assertThat(result.expired()).doesNotContain(held.id());
        signIn(fixture);
        assertThat(reservationService.get(held.id()).status())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(unitService.get(fixture.unitId()).status()).isEqualTo(UnitStatus.RESERVED);
    }

    @Test
    @DisplayName("the expiry records no actor, because nobody asked for it")
    void the_trail_records_a_system_actor() {
        Reservation held = confirmedHold(fixture);
        makeOverdue(fixture, held);

        sweep.sweep();

        signIn(fixture);
        List<AuditEvent> trail = auditEvents.findTrail(fixture.tenant().tenant().id(),
                "Reservation", held.id());
        assertThat(trail)
                .filteredOn(event -> AuditAction.RESERVATION_EXPIRED.name()
                        .equals(event.action()))
                .hasSize(1)
                .allSatisfy(event -> assertThat(event.actorUserId())
                        .as("V2: a null actor means a SYS transition, and this is one")
                        .isNull());
    }

    @Test
    @DisplayName("every tenant is swept, not just whichever one happened to be current")
    void the_sweep_spans_tenants() {
        // The assertion that matters. A cross-tenant query would see nothing in production
        // under RLS while passing here, where the connection is a superuser — so the proof
        // has to be that a SECOND tenant's hold is swept too.
        Fixture other = newTenantWithAUnit("Other");
        Reservation mine = confirmedHold(fixture);
        Reservation theirs = confirmedHold(other);
        makeOverdue(fixture, mine);
        makeOverdue(other, theirs);

        ExpirySweepResult result = sweep.sweep();

        assertThat(result.expired()).contains(mine.id(), theirs.id());
        assertThat(result.tenantsSwept()).isGreaterThanOrEqualTo(2);

        signIn(other);
        assertThat(unitService.get(other.unitId()).status()).isEqualTo(UnitStatus.AVAILABLE);
    }

    @Test
    @DisplayName("the tenant registry is what makes that possible, and returns ids only")
    void the_registry_lists_tenants() {
        TestIdentity.signOut();
        List<UUID> ids = registry.activeTenantIds();

        assertThat(ids)
                .as("read with no tenant established, which only the registry policy allows")
                .contains(fixture.tenant().tenant().id());
    }

    @Test
    @DisplayName("sweeping twice does not expire the same hold twice")
    void the_sweep_is_idempotent() {
        Reservation held = confirmedHold(fixture);
        makeOverdue(fixture, held);

        sweep.sweep();
        ExpirySweepResult second = sweep.sweep();

        assertThat(second.expired()).doesNotContain(held.id());

        signIn(fixture);
        assertThat(auditEvents.findTrail(fixture.tenant().tenant().id(), "Reservation",
                held.id()))
                .filteredOn(event -> AuditAction.RESERVATION_EXPIRED.name()
                        .equals(event.action()))
                .hasSize(1);
    }
}
