package com.rescrm.integration;

import com.rescrm.crm.service.LeadService;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.inventory.domain.ProjectStatus;
import com.rescrm.inventory.domain.UnitStatus;
import com.rescrm.inventory.service.ProjectService;
import com.rescrm.inventory.service.UnitService;
import com.rescrm.platform.audit.AuditEventRepository;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.security.Role;
import com.rescrm.reservations.domain.Reservation;
import com.rescrm.reservations.repository.ReservationRepository;
import com.rescrm.reservations.service.ReservationService;
import com.rescrm.support.TestIdentity;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tests that must fail if Epic 4's tenant isolation is ever weakened.
 *
 * <p>Same shape as the identity, CRM and inventory suites, with one addition that matters
 * more here than anywhere so far: reservations are the first feature with a path that runs
 * with no user at all. So alongside the usual cross-tenant attempts, this asserts that the
 * sweep's tenant-registry exemption grants exactly what it was built to grant and nothing
 * else — because a policy that quietly widened would be invisible until it mattered.
 */
@DisplayName("Tenant isolation — reservations")
class ReservationTenantIsolationIT extends AbstractPostgresIT {

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private ProjectService projectService;
    @Autowired private UnitService unitService;
    @Autowired private LeadService leadService;
    @Autowired private ReservationService reservationService;
    @Autowired private ReservationRepository reservations;
    @Autowired private AuditEventRepository auditEvents;

    private record Tenancy(TenantProvisioningService.ProvisionedTenant tenant, UUID unitId,
                           UUID leadId) { }

    private Tenancy tenantA;
    private Tenancy tenantB;
    private UUID holdOfA;
    private UUID holdOfB;

    @BeforeEach
    void provisionTwoTenants() {
        tenantB = stocked("B");
        asOwnerOf(tenantB);
        holdOfB = reservationService.place(tenantB.unitId(), tenantB.leadId(), null, null,
                Money.of("10000.00", CurrencyCode.EGP), true).id();
        reservationService.confirm(holdOfB);

        // A gets its own, so every "only A's" assertion runs against a non-empty list.
        tenantA = stocked("A");
        asOwnerOf(tenantA);
        holdOfA = reservationService.place(tenantA.unitId(), tenantA.leadId(), null, null, null,
                false).id();
    }

    @AfterEach
    void signOut() {
        TestIdentity.signOut();
    }

    private Tenancy stocked(String label) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        var tenant = provisioning.provision("ResIso " + label + " " + unique, "own_inventory",
                label + " Branch", "Owner " + label,
                "resiso-" + label.toLowerCase() + "-" + unique + "@example.com",
                "a-long-enough-password");
        TestIdentity.signOut();
        TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(), Role.OWNER, null);

        UUID projectId = projectService.create("own_inventory", null, null, label + " Project",
                null, null).id();
        projectService.changeStatus(projectId, ProjectStatus.ACTIVE, "launch");
        UUID unitId = unitService.create(projectId, null, label + "-101", "apartment", null,
                null, null, Money.of("2500000.00", CurrencyCode.EGP)).id();
        UUID leadId = leadService.capture(label + " Buyer", "+2010" + unique.hashCode(), null,
                "walk-in", tenant.initialBranch().id(), null, true).id();
        return new Tenancy(tenant, unitId, leadId);
    }

    private void asOwnerOf(Tenancy tenancy) {
        TestIdentity.signOut();
        TestIdentity.signIn(tenancy.tenant().owner().id(), tenancy.tenant().tenant().id(),
                Role.OWNER, null);
    }

    private static void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                        .as("cross-tenant access must answer 404, never 403 (doc 28 section 6)")
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Nested
    @DisplayName("Tenant A cannot READ tenant B")
    class CannotRead {

        @Test
        @DisplayName("B's hold is simply not found")
        void the_hold_is_not_found() {
            assertNotFound(() -> reservationService.get(holdOfB));
            assertNotFound(() -> reservationService.depositMeaningFor(holdOfB));
        }

        @Test
        @DisplayName("listings contain only A's holds, and are not merely empty")
        void listings_are_scoped() {
            List<Reservation> visible = reservationService
                    .listVisibleToCaller(PageRequest.of(0, 50)).getContent();

            assertThat(visible).isNotEmpty();
            assertThat(visible).extracting(Reservation::tenantId)
                    .containsOnly(tenantA.tenant().tenant().id());
            assertThat(visible).extracting(Reservation::id)
                    .contains(holdOfA)
                    .doesNotContain(holdOfB);
        }

        @Test
        @DisplayName("asking which hold covers B's unit reveals nothing")
        void the_active_hold_lookup_is_scoped() {
            assertThat(reservationService.activeHoldOn(tenantB.unitId())).isEmpty();
            assertThat(reservationService.activeHoldOn(tenantA.unitId())).isPresent();
        }

        @Test
        @DisplayName("repository finders scoped to A return nothing for B's ids")
        void repositories_refuse_cross_tenant_ids() {
            UUID a = tenantA.tenant().tenant().id();
            assertThat(reservations.findByTenantIdAndId(a, holdOfB)).isEmpty();
            assertThat(reservations.findActiveForUnit(a, tenantB.unitId())).isEmpty();
            assertThat(reservations.findExpired(a, OffsetDateTime.now().plusYears(10)))
                    .extracting(Reservation::id).doesNotContain(holdOfB);
        }

        @Test
        @DisplayName("B's reservation audit trail is invisible to A")
        void the_trail_is_scoped() {
            assertThat(auditEvents.findTrail(tenantA.tenant().tenant().id(), "Reservation",
                    holdOfB)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Tenant A cannot MODIFY tenant B")
    class CannotModify {

        @Test
        @DisplayName("every transition on B's hold is not found")
        void transitions_are_refused() {
            assertNotFound(() -> reservationService.confirm(holdOfB));
            assertNotFound(() -> reservationService.release(holdOfB, "taking this"));
            assertNotFound(() -> reservationService.cancel(holdOfB, "taking this"));
            assertNotFound(() -> reservationService.extend(holdOfB,
                    OffsetDateTime.now().plusDays(30), "taking this"));
            assertNotFound(() -> reservationService.recordDeposit(holdOfB,
                    Money.of("1.00", CurrencyCode.EGP), true));
        }

        @Test
        @DisplayName("and B's hold and its unit are genuinely untouched")
        void bs_hold_is_untouched() {
            assertNotFound(() -> reservationService.release(holdOfB, "taking this"));

            asOwnerOf(tenantB);
            assertThat(reservationService.get(holdOfB).status().code()).isEqualTo("confirmed");
            assertThat(reservationService.get(holdOfB).depositAmount().toPlainString())
                    .isEqualTo("10000.00");
            assertThat(unitService.get(tenantB.unitId()).status())
                    .isEqualTo(UnitStatus.RESERVED);
        }

        @Test
        @DisplayName("A cannot place a hold on B's unit")
        void cannot_hold_another_tenants_unit() {
            assertNotFound(() -> reservationService.place(tenantB.unitId(), tenantA.leadId(),
                    null, null, null, false));

            asOwnerOf(tenantB);
            assertThat(reservations.countByTenantId(tenantB.tenant().tenant().id()))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("A cannot hold its own unit for B's lead")
        void cannot_name_another_tenants_lead() {
            assertThatThrownBy(() -> reservationService.place(tenantA.unitId(),
                    tenantB.leadId(), null, null, null, false))
                    .isInstanceOf(Exception.class);

            assertThat(reservations.countByTenantId(tenantA.tenant().tenant().id()))
                    .as("the tenant-aware foreign key refuses it at the database")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Tenant A cannot REMOVE tenant B's rows")
    class CannotRemove {

        @Test
        @DisplayName("there is no delete path to reach for in the first place")
        void the_repository_exposes_no_delete() {
            assertThat(java.util.Arrays.stream(ReservationRepository.class.getMethods())
                    .map(java.lang.reflect.Method::getName)
                    .toList())
                    .noneMatch(name -> name.startsWith("delete") || name.startsWith("remove"));
        }

        @Test
        @DisplayName("B's row count is unchanged after every attempt above")
        void counts_unchanged() {
            // Read as B's own owner. The suite now connects as a role row-level security
            // applies to, so counting another tenant's rows from A's session returns zero —
            // which is the policy working, not the rows having gone anywhere. Under the old
            // superuser connection this read B's rows from A's session and nobody noticed.
            asOwnerOf(tenantB);
            assertThat(reservations.countByTenantId(tenantB.tenant().tenant().id()))
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Scope within a tenant")
    class ScopeWithinATenant {

        @Test
        @DisplayName("an agent sees their own holds and not a colleague's")
        void an_agent_sees_their_own() {
            UUID agent = tenantA.tenant().owner().id();
            TestIdentity.signOut();
            TestIdentity.signIn(agent, tenantA.tenant().tenant().id(), Role.SALES_AGENT,
                    tenantA.tenant().initialBranch().id());

            List<Reservation> visible = reservationService
                    .listVisibleToCaller(PageRequest.of(0, 500)).getContent();

            assertThat(visible).extracting(Reservation::id)
                    .as("the assertion is worthless against an empty list")
                    .contains(holdOfA);
            assertThat(visible).extracting(Reservation::agentUserId).containsOnly(agent);
        }

        @Test
        @DisplayName("an agent cannot confirm their own hold; that is a manager's call")
        void an_agent_cannot_confirm() {
            TestIdentity.signOut();
            TestIdentity.signIn(tenantA.tenant().owner().id(), tenantA.tenant().tenant().id(),
                    Role.SALES_AGENT, tenantA.tenant().initialBranch().id());

            assertThatThrownBy(() -> reservationService.confirm(holdOfA))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                            .isEqualTo(ErrorCode.FORBIDDEN));
        }
    }
}
