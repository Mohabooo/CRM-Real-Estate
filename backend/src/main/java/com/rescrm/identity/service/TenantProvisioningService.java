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
import org.springframework.dao.DataIntegrityViolationException;
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
 *
 * <p><b>Why the transaction starts in a nested bean.</b> This method creates the very first
 * rows of a tenant that does not exist yet, so it has to establish the tenant context itself
 * rather than inherit it from a request. The order matters more than it looks:
 * {@code TenantAwareDataSource} stamps {@code app.current_tenant_id} onto a connection at the
 * moment the connection is taken, and Spring's {@code JpaTransactionManager} takes one when
 * the transaction begins. So a {@code @Transactional} method that calls
 * {@code TenantContext.callAs} in its body is stamped with the empty binding — which every
 * policy treats as matching nothing — and every insert inside is refused by row-level
 * security.
 *
 * <p>This was not hypothetical. It is what this method did until it was measured through the
 * real Spring stack, and no test caught it because the integration tests connect as a
 * superuser, and a superuser bypasses row-level security entirely. The fix is the ordering
 * below: establish the tenant with no transaction open, then cross a bean boundary into a
 * transactional method, so the first connection taken is already stamped.
 */
@Service
public class TenantProvisioningService {

    private final TenantScopedProvisioning scoped;
    private final TenantRepository tenants;
    private final AuthorizationService authorization;
    private final AuditWriter audit;

    public TenantProvisioningService(TenantScopedProvisioning scoped, TenantRepository tenants,
                                     AuthorizationService authorization, AuditWriter audit) {
        this.scoped = scoped;
        this.tenants = tenants;
        this.authorization = authorization;
        this.audit = audit;
    }

    /** What provisioning produced, so a caller need not re-query for the ids it just created. */
    public record ProvisionedTenant(Tenant tenant, Branch initialBranch, User owner) {
    }

    /**
     * Provisions with a slug derived from the name.
     *
     * <p>The derived slug always carries a short discriminator — {@code acme-towers-4f2a9c}
     * — so it is unique by construction. Checking a bare slug for availability first is not
     * possible here and would be misleading if it were: the check would run either with no
     * tenant established, where row-level security hides every existing tenant and the answer
     * is always "available", or inside the new tenant's own context, where it is equally
     * blind. A discriminator needs no lookup and cannot be wrong.
     *
     * <p>The eventual platform-admin endpoint should call
     * {@link #provision(String, String, String, String, String, String, String)} instead and
     * let an administrator choose a slug people will actually type.
     */
    public ProvisionedTenant provision(String tenantName, String defaultCommercialModel,
                                       String initialBranchName, String ownerName,
                                       String ownerEmail, String ownerPassword) {
        return provision(tenantName, derivedSlug(tenantName), defaultCommercialModel,
                initialBranchName, ownerName, ownerEmail, ownerPassword);
    }

    /** Provisions with a slug an administrator chose. A duplicate is a 409. */
    public ProvisionedTenant provision(String tenantName, String slug,
                                       String defaultCommercialModel,
                                       String initialBranchName, String ownerName,
                                       String ownerEmail, String ownerPassword) {
        // Over HTTP the caller is always authenticated: TenantAuthenticationFilter rejects
        // anonymous requests before this is reached, and only a platform admin passes the
        // check below. An absent principal means an in-process caller — seeding or a test —
        // which is how the very first tenant can exist at all.
        if (SecurityContext.current().isPresent()) {
            authorization.requireIdentityAdministration();
        }

        Tenant tenant = tenantOf(tenantName, slug, defaultCommercialModel);

        // The tenant context is established BEFORE the transactional bean is entered. See the
        // class comment: doing it the other way round stamps the connection with an empty
        // tenant and every insert below is refused.
        return TenantContext.callAs(tenant.id(), () -> scoped.provision(
                tenant, initialBranchName, ownerName, ownerEmail, ownerPassword));
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

    private static Tenant tenantOf(String tenantName, String slug,
                                   String defaultCommercialModel) {
        try {
            return Tenant.provision(tenantName, slug, defaultCommercialModel, null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }

    /**
     * A slug from the name plus a discriminator, or the discriminator alone.
     *
     * <p>An Arabic or otherwise non-Latin name slugifies to nothing, which is why
     * {@code slugCandidate} returns an empty {@link java.util.Optional} rather than inventing
     * something: a tenant called شركة النيل gets {@code tenant-4f2a9c}, and an administrator
     * can set a real slug later. That is better than a slug meaningless in either script.
     */
    private static String derivedSlug(String tenantName) {
        String discriminator = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        return Tenant.slugCandidate(tenantName)
                .map(base -> trimTo(base, 63 - discriminator.length() - 1) + "-" + discriminator)
                .orElse("tenant-" + discriminator);
    }

    private static String trimTo(String value, int maxLength) {
        String trimmed = value.length() <= maxLength ? value : value.substring(0, maxLength);
        return trimmed.replaceAll("-+$", "");
    }

    /**
     * Everything that writes, once the tenant context exists.
     *
     * <p>A separate bean so Spring's transactional proxy applies — a {@code @Transactional}
     * method invoked on {@code this} is not proxied at all, which is the same lesson the CSV
     * import and the expiry sweep each learned once.
     */
    @Service
    public static class TenantScopedProvisioning {

        private final TenantRepository tenants;
        private final BranchRepository branches;
        private final UserRepository users;
        private final PasswordHasher passwordHasher;
        private final AuditWriter audit;

        public TenantScopedProvisioning(TenantRepository tenants, BranchRepository branches,
                                        UserRepository users, PasswordHasher passwordHasher,
                                        AuditWriter audit) {
            this.tenants = tenants;
            this.branches = branches;
            this.users = users;
            this.passwordHasher = passwordHasher;
            this.audit = audit;
        }

        @Transactional
        public ProvisionedTenant provision(Tenant tenant, String initialBranchName,
                                           String ownerName, String ownerEmail,
                                           String ownerPassword) {
            UUID actorUserId = SecurityContext.current()
                    .map(principal -> principal.userId())
                    .orElse(null);

            tenant.recordActor(actorUserId, true);
            Tenant savedTenant = saveWithUniqueSlug(tenant);

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
            // 'provisioning' would have no one able to sign in — and since V6, its slug would
            // not resolve either, because the login policy only sees active tenants.
            savedTenant.transitionTo(TenantStatus.ACTIVE);
            tenants.save(savedTenant);

            audit.recordCreation(AuditAction.TENANT_PROVISIONED, "Tenant", savedTenant.id(),
                    provisioningSummary(savedTenant, savedBranch, savedOwner));
            audit.recordCreation(AuditAction.BRANCH_CREATED, "Branch", savedBranch.id(),
                    Map.of("name", savedBranch.name()));
            audit.recordCreation(AuditAction.USER_CREATED, "User", savedOwner.id(),
                    Map.of("email", savedOwner.email(), "role", savedOwner.role().name()));

            return new ProvisionedTenant(savedTenant, savedBranch, savedOwner);
        }

        /**
         * Flushes the insert so a duplicate slug is a 409 rather than a commit-time failure.
         *
         * <p>{@code save} only stages it; the statement runs at commit, inside the
         * transaction manager and well past any catch block here.
         */
        private Tenant saveWithUniqueSlug(Tenant tenant) {
            try {
                return tenants.saveAndFlush(tenant);
            } catch (DataIntegrityViolationException duplicateSlug) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "That company key is already taken",
                        Map.of("slug", tenant.slug()));
            }
        }

        private static Map<String, Object> provisioningSummary(Tenant tenant, Branch branch,
                                                               User owner) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("name", tenant.name());
            summary.put("slug", tenant.slug());
            summary.put("status", tenant.status().code());
            summary.put("defaultCommercialModel", tenant.defaultCommercialModel());
            summary.put("initialBranchId", branch.id().toString());
            summary.put("ownerUserId", owner.id().toString());
            return summary;
        }
    }
}
