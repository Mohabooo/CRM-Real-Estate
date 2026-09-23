package com.rescrm.inventory.api;

import com.rescrm.inventory.api.InventoryDtos.ChangeProjectStatusRequest;
import com.rescrm.inventory.api.InventoryDtos.CreatePhaseRequest;
import com.rescrm.inventory.api.InventoryDtos.CreateProjectRequest;
import com.rescrm.inventory.api.InventoryDtos.ImportUnitsRequest;
import com.rescrm.inventory.api.InventoryDtos.ImportUnitsResponse;
import com.rescrm.inventory.api.InventoryDtos.InventoryCounts;
import com.rescrm.inventory.api.InventoryDtos.PageResponse;
import com.rescrm.inventory.api.InventoryDtos.PhaseResponse;
import com.rescrm.inventory.api.InventoryDtos.ProjectResponse;
import com.rescrm.inventory.api.InventoryDtos.UnitResponse;
import com.rescrm.inventory.api.InventoryDtos.UpdateProjectRequest;
import com.rescrm.inventory.domain.Project;
import com.rescrm.inventory.domain.ProjectStatus;
import com.rescrm.inventory.domain.UnitStatus;
import com.rescrm.inventory.service.PhaseService;
import com.rescrm.inventory.service.ProjectService;
import com.rescrm.inventory.service.UnitImportService;
import com.rescrm.inventory.service.UnitService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Doc 23's {@code /projects} (E3-S1, E3-S3).
 *
 * <p>The commercial model is accepted on creation and never afterwards. Doc 25 section 3 makes
 * it immutable once deals exist, and rather than accept it on the update and reject it
 * conditionally, the update request simply has no field for it — a client cannot get the rule
 * wrong if there is nothing to send.
 *
 * <p>{@code POST /projects/{id}/payment-plan-templates} from doc 23's map belongs to Epic 5
 * and is not stubbed.
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private static final int MAX_PAGE_SIZE = 200;

    private final ProjectService projects;
    private final PhaseService phases;
    private final UnitService units;
    private final UnitImportService imports;

    public ProjectController(ProjectService projects, PhaseService phases, UnitService units,
                             UnitImportService imports) {
        this.projects = projects;
        this.phases = phases;
        this.units = units;
        this.imports = imports;
    }

    // -------------------------------------------------------------- projects

    @GetMapping
    public PageResponse<ProjectResponse> list(
            @RequestParam(required = false) ProjectStatus status,
            @RequestParam(required = false) UUID developerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int bounded = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.of(
                projects.list(status, developerId, PageRequest.of(Math.max(page, 0), bounded)),
                this::toResponse);
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable UUID id) {
        return toResponse(projects.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@Valid @RequestBody CreateProjectRequest request) {
        return toResponse(projects.create(request.commercialModel(), request.developerId(),
                request.nameAr(), request.nameEn(), request.location(), request.deliveryDate()));
    }

    @PatchMapping("/{id}")
    public ProjectResponse update(@PathVariable UUID id,
                                  @Valid @RequestBody UpdateProjectRequest request) {
        return toResponse(projects.updateDetails(id, request.nameAr(), request.nameEn(),
                request.location(), request.deliveryDate()));
    }

    @PostMapping("/{id}/status")
    public ProjectResponse changeStatus(@PathVariable UUID id,
                                        @Valid @RequestBody ChangeProjectStatusRequest request) {
        return toResponse(projects.changeStatus(id, request.status(), request.reason()));
    }

    // ---------------------------------------------------------------- phases

    @GetMapping("/{id}/phases")
    public List<PhaseResponse> listPhases(@PathVariable UUID id) {
        return phases.listForProject(id).stream().map(PhaseResponse::from).toList();
    }

    @PostMapping("/{id}/phases")
    @ResponseStatus(HttpStatus.CREATED)
    public PhaseResponse createPhase(@PathVariable UUID id,
                                     @Valid @RequestBody CreatePhaseRequest request) {
        return PhaseResponse.from(phases.create(id, request.name(), request.deliveryDate()));
    }

    // ----------------------------------------------------------------- units

    /** Doc 23's {@code GET /projects/{id}/units?status=&type=&min_price=&max_price=}. */
    @GetMapping("/{id}/units")
    public PageResponse<UnitResponse> listUnits(
            @PathVariable UUID id,
            @RequestParam(required = false) List<UnitStatus> status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int bounded = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.of(units.browse(status, id, null, type,
                        UnitPrices.parseOrNull(minPrice), UnitPrices.parseOrNull(maxPrice),
                        null, null, PageRequest.of(Math.max(page, 0), bounded)),
                UnitResponse::from);
    }

    @GetMapping("/{id}/unit-counts")
    public InventoryCounts unitCounts(@PathVariable UUID id) {
        return new InventoryCounts(id,
                units.countInProject(id, null),
                units.countInProject(id, UnitStatus.AVAILABLE),
                units.countInProject(id, UnitStatus.RESERVED),
                units.countInProject(id, UnitStatus.SOLD),
                units.countInProject(id, UnitStatus.BLOCKED));
    }

    /**
     * E3-S3. Always 200, even when rows failed: the response body is the report, and a 4xx
     * would tell a client that nothing happened when in fact ninety-seven units were created.
     * A file that could not be parsed at all is the genuine 400, and that comes from the
     * service.
     */
    @PostMapping("/{id}/units/import")
    public ImportUnitsResponse importUnits(@PathVariable UUID id,
                                           @Valid @RequestBody ImportUnitsRequest request) {
        return ImportUnitsResponse.from(imports.importUnits(id, request.csv()));
    }

    private ProjectResponse toResponse(Project project) {
        return ProjectResponse.from(project, projects.sellerOfRecordFor(project));
    }
}
