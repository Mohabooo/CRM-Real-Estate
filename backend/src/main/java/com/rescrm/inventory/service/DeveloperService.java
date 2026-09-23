package com.rescrm.inventory.service;

import com.rescrm.inventory.domain.Developer;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * External developers (E3-S2).
 *
 * <p>Only ever populated for brokered projects. A tenant selling nothing but its own stock
 * will never call any of this, which is the point of the architecture change in doc 25
 * section 3 — the abstraction is absent rather than empty.
 */
@Service
public class DeveloperService {

    private final DeveloperRepository developers;
    private final ProjectRepository projects;
    private final AuthorizationService authorization;
    private final AuditWriter audit;

    public DeveloperService(DeveloperRepository developers, ProjectRepository projects,
                            AuthorizationService authorization, AuditWriter audit) {
        this.developers = developers;
        this.projects = projects;
        this.authorization = authorization;
        this.audit = audit;
    }

    /** E3-S2: a name is the only requirement. */
    @Transactional
    public Developer register(String name, String contact, String paymentTermsNote) {
        requireInventoryAdministration();
        UUID tenantId = TenantContext.require();

        Developer developer;
        try {
            developer = Developer.register(tenantId, name, contact, paymentTermsNote);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        developer.recordActor(SecurityContext.require().userId(), true);

        Developer saved = saveUnique(developer);
        audit.recordCreation(AuditAction.DEVELOPER_REGISTERED, "Developer", saved.id(),
                Map.of("name", saved.name()));
        return saved;
    }

    @Transactional
    public Developer updateDetails(UUID developerId, String name, String contact,
                                   String paymentTermsNote) {
        requireInventoryAdministration();
        Developer developer = get(developerId);
        try {
            developer.updateDetails(name, contact, paymentTermsNote);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        developer.recordActor(SecurityContext.require().userId(), false);

        Developer saved = saveUnique(developer);
        audit.record(AuditAction.DEVELOPER_UPDATED, "Developer", saved.id(), null,
                Map.of("name", saved.name()), null);
        return saved;
    }

    /**
     * Deactivated, never deleted.
     *
     * <p>A developer with projects behind it is part of the financial record, and the
     * restricting foreign key on {@code projects} would refuse a delete anyway. Making that
     * refusal the default — there is no delete method — means nobody has to discover it.
     */
    @Transactional
    public Developer deactivate(UUID developerId, String reason) {
        requireInventoryAdministration();
        Developer developer = get(developerId);
        developer.deactivate();
        developer.recordActor(SecurityContext.require().userId(), false);
        Developer saved = developers.save(developer);

        long remaining = projects.countByTenantIdAndDeveloperId(saved.tenantId(), saved.id());
        audit.record(AuditAction.DEVELOPER_DEACTIVATED, "Developer", saved.id(),
                Map.of("active", true),
                Map.of("active", false, "projectsStillReferencing", remaining), reason);
        return saved;
    }

    @Transactional
    public Developer reactivate(UUID developerId) {
        requireInventoryAdministration();
        Developer developer = get(developerId);
        developer.reactivate();
        developer.recordActor(SecurityContext.require().userId(), false);
        Developer saved = developers.save(developer);

        audit.record(AuditAction.DEVELOPER_REACTIVATED, "Developer", saved.id(),
                Map.of("active", false), Map.of("active", true), null);
        return saved;
    }

    @Transactional(readOnly = true)
    public Developer get(UUID developerId) {
        return developers.findByTenantIdAndId(TenantContext.require(), developerId)
                .orElseThrow(() -> ApiException.notFound("Developer"));
    }

    @Transactional(readOnly = true)
    public Page<Developer> list(Pageable pageable) {
        return developers.findAllByTenantIdOrderByNameAsc(TenantContext.require(), pageable);
    }

    @Transactional(readOnly = true)
    public List<Developer> listActive() {
        return developers.findAllByTenantIdAndActiveTrueOrderByNameAsc(TenantContext.require());
    }

    /**
     * Inventory is configuration, not day-to-day sales work. Doc 28 section 6 gives no
     * per-role table for it, so the set is the same one identity administration uses plus
     * operations, who are the people doc 20 actually names in E3-S1 to E3-S3.
     */
    private void requireInventoryAdministration() {
        authorization.requireAnyRole(Role.OWNER, Role.OPERATIONS, Role.PLATFORM_ADMIN);
    }

    private Developer saveUnique(Developer developer) {
        try {
            // saveAndFlush, not save: a plain save only stages the insert, so the unique
            // violation would surface during commit rather than here, and reach the client
            // as a 500 instead of the conflict it is.
            return developers.saveAndFlush(developer);
        } catch (DataIntegrityViolationException e) {
            // uniq_developer_name_per_tenant. Two developers with the same name would make
            // every commission statement ambiguous about who owes what.
            throw new ApiException(ErrorCode.CONFLICT,
                    "A developer named '" + developer.name() + "' already exists", e);
        }
    }
}
