package com.rescrm.inventory.api;

import com.rescrm.inventory.domain.Developer;
import com.rescrm.inventory.domain.Phase;
import com.rescrm.inventory.domain.Project;
import com.rescrm.inventory.domain.ProjectStatus;
import com.rescrm.inventory.domain.Unit;
import com.rescrm.inventory.domain.UnitStatus;
import com.rescrm.inventory.service.UnitClaim;
import com.rescrm.inventory.service.UnitImportResult;
import com.rescrm.platform.audit.AuditEvent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Request and response shapes for the inventory endpoints.
 *
 * <p>Money crosses the wire as a decimal string, never a JSON number. Doc 22 section 8 is
 * blunt about it: amounts serialize as strings or integer minor units, never as JSON floats,
 * because a JavaScript client parsing {@code 2500000.10} as a double is how a rounding error
 * enters a system that was careful everywhere else.
 *
 * <p>No request carries a tenant id, and no request carries a unit status. The first is taken
 * from the authenticated principal; the second is a consequence of reservation and deal
 * actions, and doc 23 requires that a client cannot write it — so there is no field here for
 * one to try.
 */
public final class InventoryDtos {

    private InventoryDtos() {
    }

    // ------------------------------------------------------------ developers

    public record DeveloperResponse(UUID id, String name, String contact,
                                    String paymentTermsNote, boolean active,
                                    OffsetDateTime createdAt) {

        public static DeveloperResponse from(Developer developer) {
            return new DeveloperResponse(developer.id(), developer.name(), developer.contact(),
                    developer.paymentTermsNote(), developer.isActive(), developer.createdAt());
        }
    }

    public record RegisterDeveloperRequest(@NotBlank @Size(max = 200) String name,
                                           String contact,
                                           @Size(max = 2000) String paymentTermsNote) {
    }

    public record UpdateDeveloperRequest(@Size(max = 200) String name,
                                         String contact,
                                         @Size(max = 2000) String paymentTermsNote) {
    }

    public record ReasonRequest(@Size(max = 500) String reason) {
    }

    // -------------------------------------------------------------- projects

    /**
     * {@code commercialModel} is the opaque code, and {@code sellerOfRecord} is the policy's
     * answer about it. Both are present because a client showing "sold by" should not have to
     * know what {@code brokered_inventory} implies — that is exactly the branching the policy
     * layer exists to keep out of callers, including the ones written in TypeScript.
     */
    public record ProjectResponse(UUID id, String commercialModel, String sellerOfRecord,
                                  UUID developerId, String nameAr, String nameEn,
                                  String displayName, String location, LocalDate deliveryDate,
                                  String status, OffsetDateTime createdAt,
                                  OffsetDateTime updatedAt) {

        public static ProjectResponse from(Project project, String sellerOfRecord) {
            return new ProjectResponse(project.id(), project.commercialModel(), sellerOfRecord,
                    project.developerId(), project.nameAr(), project.nameEn(),
                    project.displayName(), project.location(), project.deliveryDate(),
                    project.status().code(), project.createdAt(), project.updatedAt());
        }
    }

    /**
     * {@code commercialModel} may be omitted, in which case the tenant's default is used
     * (doc 25 section 3). It cannot be changed afterwards, which is why there is no field for
     * it on the update request.
     */
    public record CreateProjectRequest(@Size(max = 40) String commercialModel,
                                       UUID developerId,
                                       @Size(max = 200) String nameAr,
                                       @Size(max = 200) String nameEn,
                                       @Size(max = 300) String location,
                                       LocalDate deliveryDate) {
    }

    public record UpdateProjectRequest(@Size(max = 200) String nameAr,
                                       @Size(max = 200) String nameEn,
                                       @Size(max = 300) String location,
                                       LocalDate deliveryDate) {
    }

    public record ChangeProjectStatusRequest(@NotNull ProjectStatus status,
                                             @Size(max = 500) String reason) {
    }

    // ---------------------------------------------------------------- phases

    public record PhaseResponse(UUID id, UUID projectId, String name, LocalDate deliveryDate) {

        public static PhaseResponse from(Phase phase) {
            return new PhaseResponse(phase.id(), phase.projectId(), phase.name(),
                    phase.deliveryDate());
        }
    }

    public record CreatePhaseRequest(@NotBlank @Size(max = 200) String name,
                                     LocalDate deliveryDate) {
    }

    public record UpdatePhaseRequest(@Size(max = 200) String name, LocalDate deliveryDate) {
    }

    // ----------------------------------------------------------------- units

    public record UnitResponse(UUID id, UUID projectId, UUID phaseId, String code, String type,
                               BigDecimal areaSqm, String floor, String view, String listPrice,
                               String status, String blockedReason, OffsetDateTime createdAt,
                               OffsetDateTime updatedAt) {

        public static UnitResponse from(Unit unit) {
            return new UnitResponse(unit.id(), unit.projectId(), unit.phaseId(), unit.code(),
                    unit.type(), unit.areaSqm(), unit.floor(), unit.view(),
                    unit.listPrice().toPlainString(), unit.status().code(),
                    unit.blockedReason(), unit.createdAt(), unit.updatedAt());
        }
    }

    /** {@code listPrice} is a decimal string: "2500000.00", never 2500000.0. */
    public record CreateUnitRequest(@NotNull UUID projectId,
                                    UUID phaseId,
                                    @NotBlank @Size(max = 60) String code,
                                    @Size(max = 60) String type,
                                    @Positive BigDecimal areaSqm,
                                    @Size(max = 20) String floor,
                                    @Size(max = 60) String view,
                                    @NotBlank String listPrice) {
    }

    /** No status field, deliberately (doc 23). */
    public record UpdateUnitRequest(@Size(max = 60) String type,
                                    @Positive BigDecimal areaSqm,
                                    @Size(max = 20) String floor,
                                    @Size(max = 60) String view,
                                    String listPrice,
                                    UUID phaseId) {
    }

    public record BlockUnitRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /**
     * The answer to a claim attempt (E3-S5). A loss is reported as 409 with the unit's current
     * status, which is what doc 23 specifies for a unit that is already held.
     */
    public record UnitClaimResponse(UUID unitId, boolean claimed, String statusNow) {

        public static UnitClaimResponse from(UnitClaim claim) {
            return new UnitClaimResponse(claim.unitId(), claim.won(), claim.statusNow().code());
        }
    }

    public record UnitHistoryEntry(UUID id, String action, String before, String after,
                                   String reason, UUID actorUserId, OffsetDateTime at) {

        public static UnitHistoryEntry from(AuditEvent event) {
            return new UnitHistoryEntry(event.id(), event.action(), event.before(),
                    event.after(), event.reason(), event.actorUserId(), event.createdAt());
        }
    }

    // ---------------------------------------------------------------- import

    public record ImportUnitsRequest(@NotBlank String csv) {
    }

    public record ImportUnitsResponse(int rowsRead, int imported, int failed,
                                      List<UUID> unitIds, List<RowErrorResponse> errors) {

        public static ImportUnitsResponse from(UnitImportResult result) {
            return new ImportUnitsResponse(result.rowsRead(), result.importedCount(),
                    result.errorCount(), result.imported(),
                    result.errors().stream().map(RowErrorResponse::from).toList());
        }
    }

    public record RowErrorResponse(int row, String code, String message) {

        public static RowErrorResponse from(UnitImportResult.RowError error) {
            return new RowErrorResponse(error.rowNumber(), error.code(), error.message());
        }
    }

    // ------------------------------------------------------------------ misc

    public record InventoryCounts(UUID projectId, long total, long available, long reserved,
                                  long sold, long blocked) {
    }

    public record PageResponse<T>(List<T> items, int page, int size, long totalElements,
                                  int totalPages) {

        public static <E, T> PageResponse<T> of(Page<E> source,
                                                java.util.function.Function<E, T> mapper) {
            return new PageResponse<>(source.getContent().stream().map(mapper).toList(),
                    source.getNumber(), source.getSize(), source.getTotalElements(),
                    source.getTotalPages());
        }
    }

    /** Parsed here rather than in a service, so a bad string is a 400 with the field named. */
    public record UnitStatusFilter(List<UnitStatus> statuses) {
    }
}
