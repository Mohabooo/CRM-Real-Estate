package com.rescrm.identity.service;

import com.rescrm.identity.domain.Branch;
import com.rescrm.identity.domain.Tenant;
import com.rescrm.identity.domain.TenantStatus;
import com.rescrm.identity.domain.User;
import com.rescrm.identity.repository.BranchRepository;
import com.rescrm.identity.repository.TenantRepository;
import com.rescrm.identity.repository.UserRepository;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditWriter;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.security.AuthorizationService;
import com.rescrm.platform.security.PasswordHasher;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Provisions a tenant with its first branch and owner (doc 20, E1-S1).
 *
 * <p>All three are created in one transaction. A tenant with no owner is unusable and a
 * half-provisioned one is worse than none, so there is no path that creates the tenant alone.
 */
@Service
public class TenantProvisioningService {

    private final TenantRepository tenants;
    private final BranchRepository branches;
    private final UserRepository users;
    private final PasswordHasher passwordHasher;
    private final AuthorizationService authorization;
    private final AuditWriter audit;

    public TenantProvisioningService(TenantRepository tenants, BranchRepository branches,
                                     UserRepository users, PasswordHasher passwordHasher,
                                     AuthorizationService authorization, AuditWriter audit) {
        this.tenants = tenants;
        this.branches = branches;
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.authorization = authorization;
        this.audit = audit;
    }

    /** What provisioning produced, so a caller need not re-query for the ids it just created. */
    public record ProvisionedTenant(Tenant tenant, Branch initialBranch, User owner) {
    }

    @Transactional
    public ProvisionedTenant provision(String tenantName, String defaultCommercialModel,
                                       String initialBranchName, String ownerName,
                                       String ownerEmail, String ownerPassword) {
        // Over HTTP the caller is always authenticated: TenantAuthenticationFilter rejects
        // anonymous requests before this is reached, and only a platform admin passes the
        // check below. An absent principal means an in-process caller — seeding or a test —
        // which is how the very first tenant can exist at all.
        if (SecurityContext.current().isPresent()) {
            authorization.requireIdentityAdministration();
        }

        Tenant tenant = tenantOf(tenantName, defaultCommercialModel);

        // The tenant's own id becomes the tenant context before anything is written, so the
        // row-level security WITH CHECK on every insert below has a value to match. Without
        // this the first insert of a new tenant would be refused by its own policy.
        return TenantContext.callAs(tenant.id(), () -> {
            UUID actorUserId = SecurityContext.current()
                    .map(principal -> principal.userId())
                    .orElse(null);

            tenant.recordActor(actorUserId, true);
            Tenant savedTenant = tenants.save(tenant);

            Branch branch = Branch.create(savedTenant.id(), initialBranchName);
            branch.recordActor(actorUserId, true);
            Branch savedBranch = branches.save(branch);

            if (users.existsByTenantIdAndEmail(savedTenant.id(), User.normalizeEmail(ownerEmail))) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "A user with that email already exists in this tenant");
            }

            // The owner is tenant-wide, so no branch is attached: doc 22 notes branch_id is
            // nullable precisely because an owner is not scoped to one office.
            User owner = User.create(savedTenant.id(), ownerEmail,
                    passwordHasher.hash(ownerPassword), ownerName, null, Role.OWNER, null);
            owner.recordActor(actorUserId, true);
            User savedOwner = users.save(owner);

            // Provisioning completes the lifecycle's first transition; a tenant left in
            // 'provisioning' would have no one able to sign in.
            savedTenant.transitionTo(TenantStatus.ACTIVE);
            tenants.save(savedTenant);

            audit.recordCreation(AuditAction.TENANT_PROVISIONED, "Tenant", savedTenant.id(),
                    provisioningSummary(savedTenant, savedBranch, savedOwner));
            audit.recordCreation(AuditAction.BRANCH_CREATED, "Branch", savedBranch.id(),
                    Map.of("name", savedBranch.name()));
            audit.recordCreation(AuditAction.USER_CREATED, "User", savedOwner.id(),
                    Map.of("email", savedOwner.email(), "role", savedOwner.role().name()));

            return new ProvisionedTenant(savedTenant, savedBranch, savedOwner);
        });
    }

    @Transactional
    public Tenant changeStatus(UUID tenantId, TenantStatus target, String reason) {
        authorization.requireIdentityAdministration();
        authorization.requireSameTenant(tenantId, "Tenant");

        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> ApiException.notFound("Tenant"));
        TenantStatus before = tenant.status();
        tenant.transitionTo(target);
        tenant.recordActor(SecurityContext.require().userId(), false);
        Tenant saved = tenants.save(tenant);

        audit.record(AuditAction.TENANT_STATUS_CHANGED, "Tenant", saved.id(),
                Map.of("status", before.code()), Map.of("status", target.code()), reason);
        return saved;
    }

    private Tenant tenantOf(String tenantName, String defaultCommercialModel) {
        try {
            return Tenant.provision(tenantName, defaultCommercialModel, null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }

    private static Map<String, Object> provisioningSummary(Tenant tenant, Branch branch, User owner) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", tenant.name());
        summary.put("status", tenant.status().code());
        summary.put("defaultCommercialModel", tenant.defaultCommercialModel());
        summary.put("initialBranchId", branch.id().toString());
        summary.put("ownerUserId", owner.id().toString());
        return summary;
    }
}
