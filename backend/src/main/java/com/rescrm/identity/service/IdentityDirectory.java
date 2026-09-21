package com.rescrm.identity.service;

import com.rescrm.identity.domain.User;
import com.rescrm.identity.repository.BranchRepository;
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

    public IdentityDirectory(UserRepository users, BranchRepository branches) {
        this.users = users;
        this.branches = branches;
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
}
