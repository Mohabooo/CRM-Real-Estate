package com.rescrm.platform.security;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The authenticated caller: who they are, which tenant they act for, and how far they see.
 *
 * <p>Every field is established by authentication and none is read from the request body,
 * a query parameter or a header. That is the whole point: a client that can nominate its
 * own {@code tenantId} has no tenant isolation, only the appearance of it.
 */
public record AuthenticatedPrincipal(UUID userId, UUID tenantId, Role role, UUID branchId) {

    public AuthenticatedPrincipal {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(role, "role");
        if (role.branchScope() != Role.BranchScopeRule.TENANT_WIDE && branchId == null) {
            throw new IllegalArgumentException(
                    "role " + role + " is branch-scoped and requires a branch");
        }
    }

    public Optional<UUID> branch() {
        return Optional.ofNullable(branchId);
    }

    /** True when this principal may see records belonging to the given branch. */
    public boolean canSeeBranch(UUID candidateBranchId) {
        if (role.isTenantWide()) {
            return true;
        }
        return branchId != null && branchId.equals(candidateBranchId);
    }
}
