package com.rescrm.platform.security;

import com.rescrm.platform.tenancy.TenantContext;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs unattended work for one tenant, with no user behind it.
 *
 * <p>Doc 18 names SYS as the actor for transitions nobody requests — a reservation
 * expiring, the unit it held returning to inventory. Those still have to happen inside a
 * tenant: {@code TenantAwareDataSource} stamps every connection from {@link TenantContext},
 * and the row-level security policies treat an unset tenant as matching nothing. A sweep
 * that ran with no tenant established would quietly see zero rows and report success.
 *
 * <p>So this establishes the tenant and, just as deliberately, guarantees that no principal
 * is present. The audit trail then records a null actor, which V2 already documents as
 * meaning exactly this: "Null only for SYS-actor transitions (doc 18)". The alternative —
 * inventing a service account and signing work in as it — would put a user id on rows no
 * user touched, and would create a principal that a request could one day be made to
 * assume.
 *
 * <p>Because it removes the principal rather than adding privilege, it grants nothing. Code
 * running inside it cannot call {@link AuthorizationService#requireAnyRole} at all; it must
 * take a path that asks for no authorization, which is the honest shape for work that has no
 * requester. An architecture rule keeps it out of the API layer, where a controller running
 * as the system would be a way to shed the caller's identity.
 */
public final class SystemActor {

    private SystemActor() {
    }

    /** Runs the action for one tenant, with the principal removed and then restored. */
    public static <T> T callFor(UUID tenantId, Supplier<T> action) {
        Optional<AuthenticatedPrincipal> previous = SecurityContext.current();
        SecurityContext.clear();
        try {
            return TenantContext.callAs(tenantId, action);
        } finally {
            previous.ifPresent(SecurityContext::set);
        }
    }

    /** As {@link #callFor}, for work that returns nothing. */
    public static void runFor(UUID tenantId, Runnable action) {
        callFor(tenantId, () -> {
            action.run();
            return null;
        });
    }

    /** Whether the current thread is doing unattended work rather than serving a request. */
    public static boolean isActing() {
        return TenantContext.isSet() && SecurityContext.current().isEmpty();
    }
}
