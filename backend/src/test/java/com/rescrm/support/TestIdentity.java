package com.rescrm.support;

import com.rescrm.platform.security.AuthenticatedPrincipal;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.TenantContext;

import java.util.UUID;

/**
 * Establishes a caller for a test, exactly as {@code TenantAuthenticationFilter} would.
 *
 * <p>Sets both holders together, because in production they are always set together and a
 * test that set only one would be exercising a state the application can never be in.
 */
public final class TestIdentity {

    private TestIdentity() {
    }

    public static void signIn(UUID userId, UUID tenantId, Role role, UUID branchId) {
        SecurityContext.set(new AuthenticatedPrincipal(userId, tenantId, role, branchId));
        TenantContext.set(tenantId);
    }

    public static void signOut() {
        SecurityContext.clear();
        TenantContext.clear();
    }
}
