package com.rescrm.inventory.service;

import com.rescrm.inventory.domain.Phase;
import com.rescrm.inventory.repository.PhaseRepository;
import com.rescrm.inventory.repository.ProjectRepository;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditWriter;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.security.AuthorizationService;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Phases within a project (doc 16, section 8). Thin, as the document intends. */
@Service
public class PhaseService {

    private final PhaseRepository phases;
    private final ProjectRepository projects;
    private final AuthorizationService authorization;
    private final AuditWriter audit;

    public PhaseService(PhaseRepository phases, ProjectRepository projects,
                        AuthorizationService authorization, AuditWriter audit) {
        this.phases = phases;
        this.projects = projects;
        this.authorization = authorization;
        this.audit = audit;
    }

    @Transactional
    public Phase create(UUID projectId, String name, LocalDate deliveryDate) {
        authorization.requireAnyRole(Role.OWNER, Role.OPERATIONS, Role.PLATFORM_ADMIN);
        UUID tenantId = TenantContext.require();
        requireProjectInTenant(tenantId, projectId);

        Phase phase;
        try {
            phase = Phase.create(tenantId, projectId, name, deliveryDate);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        phase.recordActor(SecurityContext.require().userId(), true);

        Phase saved = saveUnique(phase);
        audit.recordCreation(AuditAction.PHASE_CREATED, "Phase", saved.id(),
                Map.of("name", saved.name(), "projectId", projectId.toString()));
        return saved;
    }

    @Transactional
    public Phase updateDetails(UUID phaseId, String name, LocalDate deliveryDate) {
        authorization.requireAnyRole(Role.OWNER, Role.OPERATIONS, Role.PLATFORM_ADMIN);
        Phase phase = get(phaseId);
        try {
            phase.updateDetails(name, deliveryDate);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        phase.recordActor(SecurityContext.require().userId(), false);

        Phase saved = saveUnique(phase);
        audit.record(AuditAction.PHASE_UPDATED, "Phase", saved.id(), null,
                Map.of("name", saved.name()), null);
        return saved;
    }

    @Transactional(readOnly = true)
    public Phase get(UUID phaseId) {
        return phases.findByTenantIdAndId(TenantContext.require(), phaseId)
                .orElseThrow(() -> ApiException.notFound("Phase"));
    }

    @Transactional(readOnly = true)
    public List<Phase> listForProject(UUID projectId) {
        UUID tenantId = TenantContext.require();
        requireProjectInTenant(tenantId, projectId);
        return phases.findAllByTenantIdAndProjectIdOrderByNameAsc(tenantId, projectId);
    }

    private void requireProjectInTenant(UUID tenantId, UUID projectId) {
        if (projectId == null || !projects.existsByTenantIdAndId(tenantId, projectId)) {
            throw ApiException.notFound("Project");
        }
    }

    private Phase saveUnique(Phase phase) {
        try {
            return phases.save(phase);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "This project already has a phase named '" + phase.name() + "'", e);
        }
    }
}
