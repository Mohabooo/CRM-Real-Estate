package com.rescrm.identity.service;

import com.rescrm.identity.domain.User;
import com.rescrm.identity.repository.BranchRepository;
import com.rescrm.identity.repository.TenantRepository;
import com.rescrm.identity.repository.UserRepository;
import com.rescrm.platform.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Identity's published lookup for other modules.
 *
 * <p>Other modules routinely need two small facts about identity: does this branch belong to
 * my tenant, and is this user someone I can hand work to. Both are answered here rather than
 * by letting the calling module hold an identity repository, which the
 * {@code modules_do_not_access_foreign_internals} architecture rule forbids and which would
 * couple every module to identity's storage.
 *
 * <p>The answers are values, not entities. A caller that receives a {@link User} can navigate
 * to its password hash and can call its mutators; a caller that receives
 * {@link DirectoryUser} can do neither. Every lookup is tenant-scoped, and a user in another
 * tenant is simply absent — the caller cannot tell the difference between "not yours" and
 * "does not exist", which is the same 404-not-403 rule the API follows.
 *
 * <p>This is a read-only directory by design. Deactivating a user, moving a branch or changing
 * a role stays behind {@link UserService} and {@link BranchService}, where the permission
 * checks and the audit entries live.
 */
@Service
public class IdentityDirectory {

    private final UserRepository users;
    private final BranchRepository branches;
    private final TenantRepository tenants;

    public IdentityDirectory(UserRepository users, BranchRepository branches,
                             TenantRepository tenants) {
        this.users = users;
        this.branches = branches;
        this.tenants = tenants;
    }

    /** The subset of a user that another module may see. Never carries a credential. */
    public record DirectoryUser(UUID id, String name, Role role, UUID branchId, boolean active) {

        static DirectoryUser of(User user) {
            return new DirectoryUser(user.id(), user.name(), user.role(), user.branchId(),
                    user.isActive());
        }
    }

    @Transactional(readOnly = true)
    public Optional<DirectoryUser> findUser(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            return Optional.empty();
        }
        return users.findByTenantIdAndId(tenantId, userId).map(DirectoryUser::of);
    }

    @Transactional(readOnly = true)
    public boolean branchExists(UUID tenantId, UUID branchId) {
        return tenantId != null && branchId != null
                && branches.existsByTenantIdAndId(tenantId, branchId);
    }

    /**
     * The tenant's default commercial model, used to pre-fill a new project (doc 25
     * section 3).
     *
     * <p>Returned as the opaque code it is stored as. The caller resolves it through a
     * policy; identity neither knows nor cares what it means.
     */
    /**
     * The tenant's settings blob, for a module that needs its own section of it.
     *
     * <p>Returned as the opaque JSON it is stored as. Identity owns the row; what any
     * particular section means belongs to the module that put it there.
     */
    @Transactional(readOnly = true)
    public String tenantSettings(UUID tenantId) {
        return tenants.findById(tenantId)
                .map(tenant -> tenant.settings())
                .orElseThrow(() -> new IllegalStateException(
                        "No tenant " + tenantId + "; a request cannot be authenticated "
                                + "against a tenant that does not exist"));
    }

    @Transactional(readOnly = true)
    public String tenantDefaultCommercialModel(UUID tenantId) {
        return tenants.findById(tenantId)
                .map(tenant -> tenant.defaultCommercialModel())
                .orElseThrow(() -> new IllegalStateException(
                        "No tenant " + tenantId + "; a request cannot be authenticated "
                                + "against a tenant that does not exist"));
    }
}
