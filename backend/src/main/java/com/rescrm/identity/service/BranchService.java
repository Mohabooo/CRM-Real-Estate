package com.rescrm.identity.service;

import com.rescrm.identity.domain.Branch;
import com.rescrm.identity.repository.BranchRepository;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditWriter;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.security.AuthorizationService;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Branches within the caller's tenant.
 *
 * <p>Every method takes the tenant from {@link TenantContext} rather than from an argument.
 * A service that accepts a tenant id from its caller is one careless controller away from
 * accepting it from the client.
 */
@Service
public class BranchService {

    private final BranchRepository branches;
    private final AuthorizationService authorization;
    private final AuditWriter audit;

    public BranchService(BranchRepository branches, AuthorizationService authorization,
                         AuditWriter audit) {
        this.branches = branches;
        this.authorization = authorization;
        this.audit = audit;
    }

    @Transactional
    public Branch create(String name) {
        authorization.requireIdentityAdministration();
        UUID tenantId = TenantContext.require();
        Branch branch;
        try {
            branch = Branch.create(tenantId, name);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        branch.recordActor(SecurityContext.require().userId(), true);
        Branch saved = branches.save(branch);
        audit.recordCreation(AuditAction.BRANCH_CREATED, "Branch", saved.id(),
                Map.of("name", saved.name()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Branch> list() {
        return branches.findAllByTenantIdOrderByNameAsc(TenantContext.require());
    }

    @Transactional(readOnly = true)
    public Branch get(UUID branchId) {
        UUID tenantId = TenantContext.require();
        Branch branch = branches.findByTenantIdAndId(tenantId, branchId)
                .orElseThrow(() -> ApiException.notFound("Branch"));
        // A branch-scoped caller asking for someone else's branch gets the same answer as if
        // it did not exist (doc 28, section 6): 403 would confirm it does.
        authorization.requireBranchVisible(branch.id(), "Branch");
        return branch;
    }

    @Transactional
    public Branch rename(UUID branchId, String newName) {
        authorization.requireIdentityAdministration();
        UUID tenantId = TenantContext.require();
        Branch branch = branches.findByTenantIdAndId(tenantId, branchId)
                .orElseThrow(() -> ApiException.notFound("Branch"));
        String before = branch.name();
        try {
            branch.rename(newName);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        branch.recordActor(SecurityContext.require().userId(), false);
        Branch saved = branches.save(branch);
        audit.record(AuditAction.BRANCH_UPDATED, "Branch", saved.id(),
                Map.of("name", before), Map.of("name", saved.name()), null);
        return saved;
    }

    @Transactional
    public Branch deactivate(UUID branchId, String reason) {
        authorization.requireIdentityAdministration();
        UUID tenantId = TenantContext.require();
        Branch branch = branches.findByTenantIdAndId(tenantId, branchId)
                .orElseThrow(() -> ApiException.notFound("Branch"));
        branch.deactivate();
        branch.recordActor(SecurityContext.require().userId(), false);
        Branch saved = branches.save(branch);
        audit.record(AuditAction.BRANCH_DEACTIVATED, "Branch", saved.id(),
                Map.of("active", true), Map.of("active", false), reason);
        return saved;
    }
}
