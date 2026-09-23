package com.rescrm.integration;

import com.rescrm.inventory.domain.Developer;
import com.rescrm.inventory.domain.Project;
import com.rescrm.inventory.domain.ProjectStatus;
import com.rescrm.inventory.domain.Unit;
import com.rescrm.inventory.domain.UnitStatus;
import com.rescrm.inventory.repository.DeveloperRepository;
import com.rescrm.inventory.repository.PhaseRepository;
import com.rescrm.inventory.repository.ProjectRepository;
import com.rescrm.inventory.repository.UnitRepository;
import com.rescrm.inventory.service.DeveloperService;
import com.rescrm.inventory.service.PhaseService;
import com.rescrm.inventory.service.ProjectService;
import com.rescrm.inventory.service.UnitImportService;
import com.rescrm.inventory.service.UnitService;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.platform.audit.AuditEventRepository;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.security.Role;
import com.rescrm.support.TestIdentity;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tests that must fail if Epic 3's tenant isolation is ever weakened.
 *
 * <p>Same shape as the identity and CRM suites: two tenants provisioned and populated, then
 * every read, write and transition in the inventory module attempted from tenant A against
 * tenant B's rows. Every listing is seeded in both tenants first, so "contains only A" cannot
 * pass against an empty result — the failure mode where removing scoping entirely leaves the
 * suite green.
 *
 * <p>Inventory adds a boundary the earlier epics did not have: a project in one tenant must
 * not be able to name a developer in another. The database refuses it through a composite
 * foreign key; the service refuses it first, with an error a person can act on.
 */
@DisplayName("Tenant isolation — inventory")
class InventoryTenantIsolationIT extends AbstractPostgresIT {

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private DeveloperService developerService;
    @Autowired private ProjectService projectService;
    @Autowired private PhaseService phaseService;
    @Autowired private UnitService unitService;
    @Autowired private UnitImportService importService;
    @Autowired private DeveloperRepository developers;
    @Autowired private ProjectRepository projects;
    @Autowired private PhaseRepository phases;
    @Autowired private UnitRepository units;
    @Autowired private AuditEventRepository auditEvents;

    private TenantProvisioningService.ProvisionedTenant tenantA;
    private TenantProvisioningService.ProvisionedTenant tenantB;

    private UUID developerOfB;
    private UUID projectOfA;
    private UUID projectOfB;
    private UUID phaseOfB;
    private UUID unitOfA;
    private UUID unitOfB;

    @BeforeEach
    void provisionAndPopulate() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        tenantA = provisioning.provision("INV A " + unique, "own_inventory", "A Branch",
                "Owner A", "inv-a-" + unique + "@example.com", "a-long-enough-password");
        tenantB = provisioning.provision("INV B " + unique, "own_inventory", "B Branch",
                "Owner B", "inv-b-" + unique + "@example.com", "a-long-enough-password");

        asOwnerOf(tenantB);
        developerOfB = developerService.register("Developer B", null, null).id();
        projectOfB = projectService.create("brokered_inventory", developerOfB, null,
                "Project B", "Cairo", null).id();
        phaseOfB = phaseService.create(projectOfB, "Phase B", null).id();
        unitOfB = unitService.create(projectOfB, phaseOfB, "B-101", "apartment", null, null,
                null, egp("2000000.00")).id();

        // A gets its own of each, so every "only A's" assertion is made against a non-empty
        // list rather than passing vacuously.
        asOwnerOf(tenantA);
        developerService.register("Developer A", null, null);
        projectOfA = projectService.create("own_inventory", null, null, "Project A", "Giza",
                null).id();
        unitOfA = unitService.create(projectOfA, null, "A-101", "apartment", null, null, null,
                egp("2500000.00")).id();
    }

    @AfterEach
    void signOut() {
        TestIdentity.signOut();
    }

    private static Money egp(String amount) {
        return Money.of(amount, CurrencyCode.EGP);
    }

    private void asOwnerOf(TenantProvisioningService.ProvisionedTenant tenant) {
        TestIdentity.signOut();
        TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(), Role.OWNER, null);
    }

    private static void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                        .as("cross-tenant access must answer 404, never 403 (doc 28 section 6)")
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    // =============================================================================== read

    @Nested
    @DisplayName("Tenant A cannot READ tenant B")
    class CannotRead {

        @Test
        @DisplayName("B's developer, project, phase and unit are simply not found")
        void records_are_not_found() {
            assertNotFound(() -> developerService.get(developerOfB));
            assertNotFound(() -> projectService.get(projectOfB));
            assertNotFound(() -> phaseService.get(phaseOfB));
            assertNotFound(() -> unitService.get(unitOfB));
            assertNotFound(() -> unitService.history(unitOfB));
            assertNotFound(() -> phaseService.listForProject(projectOfB));
        }

        @Test
        @DisplayName("listings contain only A's rows, and are not merely empty")
        void listings_are_scoped() {
            List<Developer> visibleDevelopers =
                    developerService.list(PageRequest.of(0, 50)).getContent();
            assertThat(visibleDevelopers).isNotEmpty();
            assertThat(visibleDevelopers).extracting(Developer::tenantId)
                    .containsOnly(tenantA.tenant().id());
            assertThat(visibleDevelopers).extracting(Developer::id)
                    .doesNotContain(developerOfB);

            List<Project> visibleProjects =
                    projectService.list(null, null, PageRequest.of(0, 50)).getContent();
            assertThat(visibleProjects).extracting(Project::id)
                    .contains(projectOfA)
                    .doesNotContain(projectOfB);

            List<Unit> visibleUnits = unitService.browse(null, null, null, null, null, null,
                    null, null, PageRequest.of(0, 50)).getContent();
            assertThat(visibleUnits).extracting(Unit::id)
                    .contains(unitOfA)
                    .doesNotContain(unitOfB);
        }

        @Test
        @DisplayName("filtering by B's project id returns nothing rather than B's units")
        void a_foreign_project_filter_yields_nothing() {
            assertThat(unitService.browse(null, projectOfB, null, null, null, null, null, null,
                    PageRequest.of(0, 50)).getContent()).isEmpty();
        }

        @Test
        @DisplayName("counting units in B's project is not found")
        void counts_are_scoped() {
            assertNotFound(() -> unitService.countInProject(projectOfB, null));
            assertThat(unitService.countInProject(projectOfA, null)).isEqualTo(1);
        }

        @Test
        @DisplayName("repository finders scoped to A return nothing for B's ids")
        void repositories_refuse_cross_tenant_ids() {
            UUID a = tenantA.tenant().id();
            assertThat(developers.findByTenantIdAndId(a, developerOfB)).isEmpty();
            assertThat(projects.findByTenantIdAndId(a, projectOfB)).isEmpty();
            assertThat(phases.findByTenantIdAndId(a, phaseOfB)).isEmpty();
            assertThat(units.findByTenantIdAndId(a, unitOfB)).isEmpty();
            assertThat(units.findAllByTenantIdAndIdIn(a, List.of(unitOfB))).isEmpty();
            assertThat(units.findByTenantIdAndProjectIdAndCodeIgnoreCase(a, projectOfB, "B-101"))
                    .isEmpty();
            assertThat(phases.findAllByTenantIdAndProjectIdOrderByNameAsc(a, projectOfB))
                    .isEmpty();
        }

        @Test
        @DisplayName("B's inventory audit trail is invisible to A")
        void audit_trail_is_scoped() {
            assertThat(auditEvents.findTrail(tenantA.tenant().id(), "Unit", unitOfB)).isEmpty();
            assertThat(auditEvents.findTrail(tenantA.tenant().id(), "Project", projectOfB))
                    .isEmpty();
        }
    }

    // ============================================================================= modify

    @Nested
    @DisplayName("Tenant A cannot MODIFY tenant B")
    class CannotModify {

        @Test
        @DisplayName("every write against B's records is not found")
        void writes_are_refused() {
            assertNotFound(() -> developerService.updateDetails(developerOfB, "Renamed by A",
                    null, null));
            assertNotFound(() -> developerService.deactivate(developerOfB, "by A"));
            assertNotFound(() -> projectService.updateDetails(projectOfB, null, "Renamed by A",
                    null, null));
            assertNotFound(() -> projectService.changeStatus(projectOfB, ProjectStatus.ACTIVE,
                    "by A"));
            assertNotFound(() -> phaseService.updateDetails(phaseOfB, "Renamed by A", null));
            assertNotFound(() -> unitService.updateAttributes(unitOfB, "penthouse", null, null,
                    null, egp("1.00"), null));
            assertNotFound(() -> unitService.block(unitOfB, "by A"));
            assertNotFound(() -> unitService.unblock(unitOfB));
        }

        @Test
        @DisplayName("and B's records are genuinely untouched afterwards")
        void bs_records_are_untouched() {
            assertNotFound(() -> unitService.updateAttributes(unitOfB, "penthouse", null, null,
                    null, egp("1.00"), null));
            assertNotFound(() -> projectService.updateDetails(projectOfB, null, "Renamed by A",
                    null, null));

            asOwnerOf(tenantB);
            Unit stillThere = unitService.get(unitOfB);
            assertThat(stillThere.type()).isEqualTo("apartment");
            assertThat(stillThere.listPrice().toPlainString()).isEqualTo("2000000.00");
            assertThat(projectService.get(projectOfB).nameEn()).isEqualTo("Project B");
        }

        @Test
        @DisplayName("A cannot claim B's unit, and does not learn whether it was available")
        void claims_are_refused() {
            assertNotFound(() -> unitService.claimForReservation(unitOfB));
            assertNotFound(() -> unitService.claimForSale(unitOfB));
            assertNotFound(() -> unitService.returnToInventory(unitOfB, UnitStatus.RESERVED,
                    "by A"));

            asOwnerOf(tenantB);
            assertThat(unitService.get(unitOfB).status()).isEqualTo(UnitStatus.AVAILABLE);
        }

        @Test
        @DisplayName("A cannot add a unit or a phase to B's project")
        void cannot_add_into_another_tenants_project() {
            assertNotFound(() -> unitService.create(projectOfB, null, "SMUGGLED", null, null,
                    null, null, egp("1000000.00")));
            assertNotFound(() -> phaseService.create(projectOfB, "Smuggled phase", null));
            assertNotFound(() -> importService.importUnits(projectOfB,
                    "code,list_price\nSMUGGLED,1000000\n"));

            asOwnerOf(tenantB);
            assertThat(units.countByTenantIdAndProjectId(tenantB.tenant().id(), projectOfB))
                    .isEqualTo(1);
        }
    }

    // ======================================================== the composite foreign keys

    @Nested
    @DisplayName("Tenant-aware foreign keys")
    class ForeignKeys {

        @Test
        @DisplayName("A cannot create a project naming B's developer")
        void cannot_name_another_tenants_developer() {
            assertNotFound(() -> projectService.create("brokered_inventory", developerOfB, null,
                    "Smuggled", null, null));
        }

        @Test
        @DisplayName("A cannot put a unit into B's phase")
        void cannot_use_another_tenants_phase() {
            assertNotFound(() -> unitService.create(projectOfA, phaseOfB, "A-102", null, null,
                    null, null, egp("1000000.00")));
        }
    }

    // ======================================================================== no removal

    @Nested
    @DisplayName("Tenant A cannot REMOVE tenant B's rows")
    class CannotRemove {

        @Test
        @DisplayName("there is no delete path to reach for in the first place")
        void repositories_expose_no_delete() {
            assertThat(methodNamesOf(DeveloperRepository.class)).noneMatch(this::isRemoval);
            assertThat(methodNamesOf(ProjectRepository.class)).noneMatch(this::isRemoval);
            assertThat(methodNamesOf(PhaseRepository.class)).noneMatch(this::isRemoval);
            assertThat(methodNamesOf(UnitRepository.class)).noneMatch(this::isRemoval);
        }

        private boolean isRemoval(String methodName) {
            return methodName.startsWith("delete") || methodName.startsWith("remove");
        }

        private List<String> methodNamesOf(Class<?> type) {
            return java.util.Arrays.stream(type.getMethods())
                    .map(java.lang.reflect.Method::getName)
                    .toList();
        }

        @Test
        @DisplayName("B's row counts are unchanged after every attempt above")
        void counts_unchanged() {
            UUID b = tenantB.tenant().id();
            assertThat(developers.countByTenantId(b)).isEqualTo(1);
            assertThat(projects.countByTenantId(b)).isEqualTo(1);
            assertThat(phases.countByTenantId(b)).isEqualTo(1);
            assertThat(units.countByTenantId(b)).isEqualTo(1);
        }
    }

    // ====================================================================== role scoping

    @Nested
    @DisplayName("Inventory configuration is not open to everyone")
    class RoleScoping {

        @Test
        @DisplayName("a sales agent can browse inventory but cannot change it")
        void an_agent_reads_but_does_not_write() {
            TestIdentity.signOut();
            TestIdentity.signIn(tenantA.owner().id(), tenantA.tenant().id(), Role.SALES_AGENT,
                    tenantA.initialBranch().id());

            // Browsing is the agent's job (E3-S4) and must keep working.
            assertThat(unitService.browse(null, null, null, null, null, null, null, null,
                    PageRequest.of(0, 50)).getContent()).isNotEmpty();

            assertForbidden(() -> developerService.register("Sneaky", null, null));
            assertForbidden(() -> projectService.create("own_inventory", null, null, "Sneaky",
                    null, null));
            assertForbidden(() -> unitService.create(projectOfA, null, "A-999", null, null,
                    null, null, egp("1000000.00")));
            assertForbidden(() -> unitService.updateAttributes(unitOfA, null, null, null, null,
                    egp("1.00"), null));
            assertForbidden(() -> importService.importUnits(projectOfA,
                    "code,list_price\nA-999,1000000\n"));
        }

        @Test
        @DisplayName("a branch manager may block a unit, which is an operational call")
        void a_branch_manager_may_block() {
            TestIdentity.signOut();
            TestIdentity.signIn(tenantA.owner().id(), tenantA.tenant().id(), Role.BRANCH_MANAGER,
                    tenantA.initialBranch().id());

            assertThat(unitService.block(unitOfA, "show flat").status())
                    .isEqualTo(UnitStatus.BLOCKED);
        }

        private void assertForbidden(ThrowingCallable call) {
            assertThatThrownBy(call)
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                            .isEqualTo(ErrorCode.FORBIDDEN));
        }
    }
}
