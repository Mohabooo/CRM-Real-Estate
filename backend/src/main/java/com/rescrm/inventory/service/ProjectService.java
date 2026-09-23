package com.rescrm.inventory.service;

import com.rescrm.commercialmodel.CommercialModelCodes;
import com.rescrm.commercialmodel.policy.CommercialModelPolicies;
import com.rescrm.commercialmodel.policy.InventoryOwnershipPolicy;
import com.rescrm.identity.service.IdentityDirectory;
import com.rescrm.inventory.domain.Project;
import com.rescrm.inventory.domain.ProjectStatus;
import com.rescrm.inventory.repository.DeveloperRepository;
import com.rescrm.inventory.repository.ProjectRepository;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditWriter;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.security.AuthorizationService;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Projects, and with them the commercial model (E3-S1).
 *
 * <p>This service is the only place in the application outside the {@code commercialmodel}
 * package that has any business asking what a model means, and even here it does not know:
 * it hands the stored code to {@link InventoryOwnershipPolicy} and acts on the answer. There
 * is no {@code if (model.equals("brokered"))} below, and the architecture test would fail the
 * build if someone reached for the enum to write one.
 */
@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final DeveloperRepository developers;
    private final CommercialModelPolicies policies;
    private final IdentityDirectory directory;
    private final AuthorizationService authorization;
    private final AuditWriter audit;

    public ProjectService(ProjectRepository projects, DeveloperRepository developers,
                          CommercialModelPolicies policies, IdentityDirectory directory,
                          AuthorizationService authorization, AuditWriter audit) {
        this.projects = projects;
        this.developers = developers;
        this.policies = policies;
        this.directory = directory;
        this.authorization = authorization;
        this.audit = audit;
    }

    /**
     * E3-S1. The model defaults to the tenant's, which keeps the common single-model case a
     * one-time setting rather than a decision on every project (doc 25 section 3).
     */
    @Transactional
    public Project create(String commercialModelCode, UUID developerId, String nameAr,
                          String nameEn, String location, LocalDate deliveryDate) {
        requireInventoryAdministration();
        UUID tenantId = TenantContext.require();

        String modelCode = resolveModelCode(commercialModelCode);
        InventoryOwnershipPolicy ownership = policies.inventoryOwnership(modelCode);

        // The policy decides; this method only reports. Both halves of E3-S1's acceptance
        // criteria come from one call: brokered without a developer is rejected, and
        // own-inventory with one is rejected just as firmly — a stray developer_id on an
        // owned project would make it look brokered to every commission query later.
        ownership.rejectDeveloperAssignment(developerId != null).ifPresent(reason -> {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, reason,
                    Map.of("commercialModel", modelCode));
        });

        if (developerId != null) {
            requireDeveloperInTenant(tenantId, developerId);
        }

        Project project;
        try {
            project = Project.create(tenantId, modelCode, developerId, nameAr, nameEn, location,
                    deliveryDate);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        project.recordActor(SecurityContext.require().userId(), true);
        Project saved = projects.save(project);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", saved.displayName());
        after.put("commercialModel", saved.commercialModel());
        after.put("sellerOfRecord", ownership.sellerOfRecord().name());
        after.put("developerId", developerId == null ? null : developerId.toString());
        audit.recordCreation(AuditAction.PROJECT_CREATED, "Project", saved.id(), after);
        return saved;
    }

    /**
     * Doc 23's {@code PATCH /projects/{id}}. The commercial model is not a parameter: it is
     * fixed at creation (doc 25 section 3), and the honest way to express that is to give
     * callers nothing to send.
     */
    @Transactional
    public Project updateDetails(UUID projectId, String nameAr, String nameEn, String location,
                                 LocalDate deliveryDate) {
        requireInventoryAdministration();
        Project project = get(projectId);
        try {
            project.updateDetails(nameAr, nameEn, location, deliveryDate);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        project.recordActor(SecurityContext.require().userId(), false);
        Project saved = projects.save(project);

        audit.record(AuditAction.PROJECT_UPDATED, "Project", saved.id(), null,
                Map.of("name", saved.displayName()), null);
        return saved;
    }

    @Transactional
    public Project changeStatus(UUID projectId, ProjectStatus next, String reason) {
        requireInventoryAdministration();
        Project project = get(projectId);
        ProjectStatus before = project.status();
        try {
            project.changeStatus(next);
        } catch (IllegalStateException e) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION, e.getMessage());
        }
        project.recordActor(SecurityContext.require().userId(), false);
        Project saved = projects.save(project);

        audit.record(AuditAction.PROJECT_STATUS_CHANGED, "Project", saved.id(),
                Map.of("status", before.code()), Map.of("status", saved.status().code()), reason);
        return saved;
    }

    @Transactional(readOnly = true)
    public Project get(UUID projectId) {
        return projects.findByTenantIdAndId(TenantContext.require(), projectId)
                .orElseThrow(() -> ApiException.notFound("Project"));
    }

    @Transactional(readOnly = true)
    public Page<Project> list(ProjectStatus status, UUID developerId, Pageable pageable) {
        return projects.findFiltered(TenantContext.require(),
                status == null ? null : status.code(), developerId, pageable);
    }

    /** Who sells this project's units, for a caller that needs to say so on screen. */
    @Transactional(readOnly = true)
    public String sellerOfRecord(UUID projectId) {
        return sellerOfRecordFor(get(projectId));
    }

    /**
     * The same answer for a project already in hand.
     *
     * <p>Exists so a listing of fifty projects resolves fifty policies rather than issuing
     * fifty more queries to fetch rows it is already holding.
     */
    public String sellerOfRecordFor(Project project) {
        return policies.inventoryOwnership(project.commercialModel()).sellerOfRecord().name();
    }

    // ------------------------------------------------------------------ internals

    /**
     * Falls back to the tenant's default model. Validation happens here rather than in the
     * entity so that an unknown code is a 400 naming the accepted values, not a 500.
     */
    private String resolveModelCode(String requested) {
        String code = requested != null && !requested.isBlank()
                ? requested.trim()
                : directory.tenantDefaultCommercialModel(TenantContext.require());
        try {
            return CommercialModelCodes.requireValid(code);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }

    private void requireDeveloperInTenant(UUID tenantId, UUID developerId) {
        if (!developers.existsByTenantIdAndId(tenantId, developerId)) {
            throw ApiException.notFound("Developer");
        }
    }

    private void requireInventoryAdministration() {
        authorization.requireAnyRole(Role.OWNER, Role.OPERATIONS, Role.PLATFORM_ADMIN);
    }
}
