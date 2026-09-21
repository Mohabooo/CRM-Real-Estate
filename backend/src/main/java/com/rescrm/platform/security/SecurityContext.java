package com.rescrm.platform.security;

import java.util.Optional;

/**
 * Holds the authenticated principal for the current thread.
 *
 * <p>Deliberately mirrors {@link com.rescrm.platform.tenancy.TenantContext}: same lifecycle,
 * same clearing discipline, same refusal to invent a default. Servlet threads are pooled, so
 * a principal left behind would be inherited by the next request on that thread.
 */
public final class SecurityContext {

    private static final ThreadLocal<AuthenticatedPrincipal> CURRENT = new ThreadLocal<>();

    private SecurityContext() {
    }

    public static void set(AuthenticatedPrincipal principal) {
        if (principal == null) {
            throw new IllegalArgumentException("principal must not be null");
        }
        CURRENT.set(principal);
    }

    public static Optional<AuthenticatedPrincipal> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static AuthenticatedPrincipal require() {
        AuthenticatedPrincipal principal = CURRENT.get();
        if (principal == null) {
            throw new NotAuthenticatedException();
        }
        return principal;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Thrown when an operation needing a caller runs without one. */
    public static final class NotAuthenticatedException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        NotAuthenticatedException() {
            super("No authenticated principal is established for the current thread");
        }
    }
}
