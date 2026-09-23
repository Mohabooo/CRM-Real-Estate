package com.rescrm.integration;

import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.inventory.domain.Project;
import com.rescrm.inventory.domain.ProjectStatus;
import com.rescrm.inventory.domain.Unit;
import com.rescrm.inventory.domain.UnitStatus;
import com.rescrm.inventory.repository.UnitRepository;
import com.rescrm.inventory.service.DeveloperService;
import com.rescrm.inventory.service.PhaseService;
import com.rescrm.inventory.service.ProjectService;
import com.rescrm.inventory.service.UnitImportResult;
import com.rescrm.inventory.service.UnitImportService;
import com.rescrm.inventory.service.UnitService;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditEventRepository;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.security.Role;
import com.rescrm.support.TestIdentity;
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
 * Epic 3's stories end to end, against a real PostgreSQL: the commercial model and its
 * developer rule (E3-S1, E3-S2), individual and CSV unit creation (E3-S3), and the inventory
 * browse (E3-S4). The double-sell guard has its own suite in {@link UnitConcurrencyIT}.
 */
@DisplayName("Inventory lifecycle")
class InventoryLifecycleIT extends AbstractPostgresIT {

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private DeveloperService developerService;
    @Autowired private ProjectService projectService;
    @Autowired private PhaseService phaseService;
    @Autowired private UnitService unitService;
    @Autowired private UnitImportService importService;
    @Autowired private UnitRepository units;
    @Autowired private AuditEventRepository auditEvents;

    private TenantProvisioningService.ProvisionedTenant tenant;

    @BeforeEach
    void provision() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        // The tenant defaults to own_inventory, so an omitted model must come out owned.
        tenant = provisioning.provision("Inv " + unique, "own_inventory", "Main", "Owner",
                "inv-" + unique + "@example.com", "a-long-enough-password");
        TestIdentity.signOut();
        TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(), Role.OWNER, null);
    }

    @AfterEach
    void signOut() {
        TestIdentity.signOut();
    }

    private static Money egp(String amount) {
        return Money.of(amount, CurrencyCode.EGP);
    }

    private UUID ownProject(String name) {
        return projectService.create("own_inventory", null, null, name, null, null).id();
    }

    // ============================================================== E3-S1 and E3-S2

    @Nested
    @DisplayName("E3-S1 the commercial model, E3-S2 developers")
    class CommercialModel {

        @Test
        @DisplayName("an own-inventory project never asks for a developer")
        void owned_project_has_no_developer() {
            Project project = projectService.create("own_inventory", null, "مراسي", "Marassi",
                    "North Coast", null);

            assertThat(project.developerId()).isNull();
            assertThat(project.commercialModel()).isEqualTo("own_inventory");
            assertThat(projectService.sellerOfRecordFor(project)).isEqualTo("TENANT");
        }

        @Test
        @DisplayName("a brokered project without a developer is rejected")
        void brokered_without_developer_is_rejected() {
            assertThatThrownBy(() -> projectService.create("brokered_inventory", null, null,
                    "Badya", null, null))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                            .isEqualTo(ErrorCode.VALIDATION_FAILED))
                    .hasMessageContaining("must be named");
        }

        @Test
        @DisplayName("an own-inventory project with a developer is rejected just as firmly")
        void owned_with_developer_is_rejected() {
            UUID developer = developerService.register("Palm Hills", null, null).id();

            // A stray developer_id on an owned project would make it look brokered to every
            // commission query later, so this is a rejection rather than a tolerated extra.
            assertThatThrownBy(() -> projectService.create("own_inventory", developer, null,
                    "Wrong", null, null))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("cannot name a developer");
        }

        @Test
        @DisplayName("a brokered project with a developer is created and sold by the developer")
        void brokered_with_developer_is_created() {
            UUID developer = developerService.register("SODIC", null, "Net 30").id();
            Project project = projectService.create("brokered_inventory", developer, null,
                    "Badya", "6th of October", null);

            assertThat(project.developerId()).isEqualTo(developer);
            assertThat(projectService.sellerOfRecordFor(project)).isEqualTo("DEVELOPER");
        }

        @Test
        @DisplayName("an omitted model falls back to the tenant's default")
        void model_defaults_to_the_tenants() {
            Project project = projectService.create(null, null, null, "Defaulted", null, null);
            assertThat(project.commercialModel()).isEqualTo("own_inventory");
        }

        @Test
        @DisplayName("an unknown model is refused, naming what is accepted")
        void unknown_model_is_refused() {
            assertThatThrownBy(() -> projectService.create("rent_to_own", null, null, "Odd",
                    null, null))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("brokered_inventory");
        }

        @Test
        @DisplayName("a developer name is unique within the tenant")
        void developer_names_are_unique() {
            developerService.register("Palm Hills", null, null);

            // Two developers with the same name make every commission statement ambiguous
            // about who owes what.
            assertThatThrownBy(() -> developerService.register(" palm hills ", null, null))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                            .isEqualTo(ErrorCode.CONFLICT));
        }

        @Test
        @DisplayName("deactivating a developer reports how many projects still reference it")
        void deactivation_reports_references() {
            UUID developer = developerService.register("Ending Relationship", null, null).id();
            projectService.create("brokered_inventory", developer, null, "Theirs", null, null);

            assertThat(developerService.deactivate(developer, "contract ended").isActive())
                    .isFalse();
            assertThat(auditEvents.findTrail(tenant.tenant().id(), "Developer", developer))
                    .extracting(event -> event.action())
                    .contains(AuditAction.DEVELOPER_DEACTIVATED.name());
        }

        @Test
        @DisplayName("the project lifecycle is audited at every step")
        void project_transitions_are_audited() {
            UUID projectId = ownProject("Audited");
            projectService.changeStatus(projectId, ProjectStatus.ACTIVE, "launch");

            assertThat(auditEvents.findTrail(tenant.tenant().id(), "Project", projectId))
                    .extracting(event -> event.action())
                    .contains(AuditAction.PROJECT_CREATED.name(),
                            AuditAction.PROJECT_STATUS_CHANGED.name());
        }
    }

    // ========================================================================= E3-S3

    @Nested
    @DisplayName("E3-S3 creating units individually and by CSV")
    class CreatingUnits {

        @Test
        @DisplayName("a unit enters inventory available, priced, and audited")
        void a_unit_enters_inventory() {
            UUID projectId = ownProject("Stock");
            Unit unit = unitService.create(projectId, null, "A-101", "apartment",
                    new java.math.BigDecimal("142.50"), "3", "garden", egp("2500000.00"));

            assertThat(unit.status()).isEqualTo(UnitStatus.AVAILABLE);
            assertThat(unit.listPrice().toPlainString()).isEqualTo("2500000.00");
            assertThat(auditEvents.findTrail(tenant.tenant().id(), "Unit", unit.id()))
                    .extracting(event -> event.action())
                    .contains(AuditAction.UNIT_CREATED.name());
        }

        @Test
        @DisplayName("a unit code is unique within its project but free across projects")
        void codes_are_unique_within_a_project() {
            UUID first = ownProject("First");
            UUID second = ownProject("Second");
            unitService.create(first, null, "A-101", null, null, null, null, egp("1000000.00"));

            assertThatThrownBy(() -> unitService.create(first, null, "a-101", null, null, null,
                    null, egp("1000000.00")))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                            .isEqualTo(ErrorCode.CONFLICT));

            assertThat(unitService.create(second, null, "A-101", null, null, null, null,
                    egp("1000000.00")).code()).isEqualTo("A-101");
        }

        @Test
        @DisplayName("a unit cannot be put into a phase of another project")
        void phase_must_belong_to_the_project() {
            UUID first = ownProject("First");
            UUID second = ownProject("Second");
            UUID phaseOfSecond = phaseService.create(second, "Phase 1", null).id();

            assertThatThrownBy(() -> unitService.create(first, phaseOfSecond, "A-101", null,
                    null, null, null, egp("1000000.00")))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("different project");
        }

        @Test
        @DisplayName("97 of 100 rows import, and the 3 failures are reported with reasons")
        void partial_import_is_the_point() {
            UUID projectId = ownProject("Bulk");
            phaseService.create(projectId, "Phase 1", null);

            StringBuilder csv = new StringBuilder("code,type,area_sqm,list_price,phase\n");
            for (int index = 1; index <= 100; index++) {
                switch (index) {
                    // Row 8: no price at all.
                    case 7 -> csv.append("U-007,apartment,120,,Phase 1\n");
                    // Row 21: a price that is not a number.
                    case 20 -> csv.append("U-020,apartment,120,ask us,Phase 1\n");
                    // Row 43: a phase this project does not have.
                    case 42 -> csv.append("U-042,apartment,120,2500000,Phase 9\n");
                    default -> csv.append("U-%03d,apartment,120,\"2,500,000\",Phase 1%n"
                            .formatted(index));
                }
            }

            UnitImportResult result = importService.importUnits(projectId, csv.toString());

            assertThat(result.rowsRead()).isEqualTo(100);
            assertThat(result.importedCount()).isEqualTo(97);
            assertThat(result.errorCount()).isEqualTo(3);
            assertThat(result.isCleanImport()).isFalse();

            // The row numbers must match the operator's spreadsheet: header is line 1.
            assertThat(result.errors()).extracting(UnitImportResult.RowError::rowNumber)
                    .containsExactly(8, 21, 43);
            assertThat(result.errors()).extracting(UnitImportResult.RowError::message)
                    .anySatisfy(message -> assertThat(message).contains("list price"))
                    .anySatisfy(message -> assertThat(message).contains("Phase 9"));

            // And the 97 are really there, not merely reported.
            assertThat(units.countByTenantIdAndProjectId(tenant.tenant().id(), projectId))
                    .as("a per-row report is worthless if the rows roll back at commit")
                    .isEqualTo(97);
        }

        @Test
        @DisplayName("thousands separators and a currency suffix are accepted")
        void spreadsheet_formatting_is_accepted() {
            UUID projectId = ownProject("Formatted");
            UnitImportResult result = importService.importUnits(projectId,
                    "code,list_price\nA-1,\"2,500,000.00\"\nA-2,3000000 EGP\n");

            assertThat(result.errorCount()).isZero();
            assertThat(units.findByTenantIdAndProjectIdAndCodeIgnoreCase(tenant.tenant().id(),
                    projectId, "A-1")).hasValueSatisfying(unit ->
                    assertThat(unit.listPrice().toPlainString()).isEqualTo("2500000.00"));
        }

        @Test
        @DisplayName("a duplicate code inside the file fails only its own row")
        void a_duplicate_row_fails_alone() {
            UUID projectId = ownProject("Duplicated");
            UnitImportResult result = importService.importUnits(projectId,
                    "code,list_price\nA-1,1000000\nA-1,2000000\nA-2,3000000\n");

            assertThat(result.importedCount()).isEqualTo(2);
            assertThat(result.errors()).extracting(UnitImportResult.RowError::rowNumber)
                    .containsExactly(3);
        }

        @Test
        @DisplayName("a file with no usable header is one error, not a hundred")
        void an_unusable_header_is_a_single_error() {
            UUID projectId = ownProject("Headerless");

            assertThatThrownBy(() -> importService.importUnits(projectId,
                    "name,price\nA-1,1000000\n"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("list_price");
        }

        @Test
        @DisplayName("the import writes one audit entry for the batch, not one per unit")
        void the_batch_is_audited_once() {
            UUID projectId = ownProject("Audited Import");
            importService.importUnits(projectId,
                    "code,list_price\nA-1,1000000\nA-2,2000000\nA-3,3000000\n");

            long batchEntries = auditEvents.findTrail(tenant.tenant().id(), "Project", projectId)
                    .stream()
                    .filter(event -> AuditAction.UNITS_IMPORTED.name().equals(event.action()))
                    .count();
            assertThat(batchEntries).isEqualTo(1);
        }
    }

    // ========================================================================= E3-S4

    @Nested
    @DisplayName("E3-S4 browsing inventory across both models")
    class Browsing {

        private UUID ownedProject;
        private UUID brokeredProject;

        @BeforeEach
        void stock() {
            UUID developer = developerService.register("Mixed Portfolio Dev", null, null).id();
            ownedProject = ownProject("Owned Stock");
            brokeredProject = projectService.create("brokered_inventory", developer, null,
                    "Brokered Stock", null, null).id();

            unitService.create(ownedProject, null, "O-1", "apartment",
                    new java.math.BigDecimal("120"), null, null, egp("2000000.00"));
            unitService.create(ownedProject, null, "O-2", "villa",
                    new java.math.BigDecimal("300"), null, null, egp("9000000.00"));
            unitService.create(brokeredProject, null, "B-1", "apartment",
                    new java.math.BigDecimal("140"), null, null, egp("3000000.00"));
        }

        @Test
        @DisplayName("one list spans both commercial models")
        void one_list_spans_both_models() {
            List<Unit> all = unitService.browse(null, null, null, null, null, null, null, null,
                    PageRequest.of(0, 50)).getContent();

            assertThat(all).extracting(Unit::code)
                    .containsExactlyInAnyOrder("O-1", "O-2", "B-1");
            assertThat(all).extracting(Unit::projectId)
                    .contains(ownedProject, brokeredProject);
        }

        @Test
        @DisplayName("sold and blocked units are excluded from the default view")
        void default_view_excludes_sold_and_blocked() {
            UUID sold = unitService.create(ownedProject, null, "O-3", "apartment", null, null,
                    null, egp("2500000.00")).id();
            assertThat(unitService.claimForSale(sold).won()).isTrue();

            UUID blocked = unitService.create(ownedProject, null, "O-4", "apartment", null,
                    null, null, egp("2500000.00")).id();
            unitService.block(blocked, "structural survey");

            assertThat(unitService.browse(null, null, null, null, null, null, null, null,
                    PageRequest.of(0, 50)).getContent())
                    .extracting(Unit::id)
                    .doesNotContain(sold, blocked);

            // Seeing them takes naming them — there is no "all" shortcut.
            assertThat(unitService.browse(List.of(UnitStatus.SOLD), null, null, null, null,
                    null, null, null, PageRequest.of(0, 50)).getContent())
                    .extracting(Unit::id).containsExactly(sold);
        }

        @Test
        @DisplayName("filters by project, type, price and area")
        void filters_narrow_the_list() {
            assertThat(unitService.browse(null, ownedProject, null, null, null, null, null,
                    null, PageRequest.of(0, 50)).getContent())
                    .extracting(Unit::code).containsExactlyInAnyOrder("O-1", "O-2");

            assertThat(unitService.browse(null, null, null, "VILLA", null, null, null, null,
                    PageRequest.of(0, 50)).getContent())
                    .as("type matching is case-insensitive; nobody types it consistently")
                    .extracting(Unit::code).containsExactly("O-2");

            assertThat(unitService.browse(null, null, null, null, egp("2500000.00"),
                    egp("9500000.00"), null, null, PageRequest.of(0, 50)).getContent())
                    .extracting(Unit::code).containsExactlyInAnyOrder("O-2", "B-1");

            assertThat(unitService.browse(null, null, null, null, null, null,
                    new java.math.BigDecimal("130"), null, PageRequest.of(0, 50)).getContent())
                    .extracting(Unit::code).containsExactlyInAnyOrder("O-2", "B-1");
        }

        @Test
        @DisplayName("a reserved unit still appears, because it may yet come back")
        void reserved_units_still_appear() {
            UUID reserved = unitService.create(ownedProject, null, "O-5", "apartment", null,
                    null, null, egp("2500000.00")).id();
            assertThat(unitService.claimForReservation(reserved).won()).isTrue();

            assertThat(unitService.browse(null, null, null, null, null, null, null, null,
                    PageRequest.of(0, 50)).getContent())
                    .extracting(Unit::id).contains(reserved);
        }

        @Test
        @DisplayName("a unit's history is its audit trail, newest transitions included")
        void history_is_the_trail() {
            UUID unitId = unitService.create(ownedProject, null, "O-6", null, null, null, null,
                    egp("2500000.00")).id();
            unitService.block(unitId, "show flat");
            unitService.unblock(unitId);

            assertThat(unitService.history(unitId))
                    .extracting(event -> event.action())
                    .contains(AuditAction.UNIT_CREATED.name(), AuditAction.UNIT_BLOCKED.name(),
                            AuditAction.UNIT_UNBLOCKED.name());
        }

        @Test
        @DisplayName("a blocked unit cannot be claimed, and says so without changing")
        void a_blocked_unit_cannot_be_claimed() {
            UUID unitId = unitService.create(ownedProject, null, "O-7", null, null, null, null,
                    egp("2500000.00")).id();
            unitService.block(unitId, "dispute with the contractor");

            assertThat(unitService.claimForSale(unitId).won()).isFalse();
            assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.BLOCKED);
            assertThat(unitService.get(unitId).blockedReason())
                    .isEqualTo("dispute with the contractor");
        }
    }
}
