package com.rescrm.platform.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * Turns a request into an authenticated principal, or into nothing.
 *
 * <p>This is the seam where authentication plugs in. Doc 28 section 6 specifies email and
 * password with server-side sessions or short-lived JWT; that mechanism is not part of Epic 1,
 * so this interface has no production implementation yet and
 * {@link TenantAuthenticationFilter} refuses every protected request until one exists.
 *
 * <p>Refusing is the correct default. The alternative — a stand-in that trusts a header — is
 * the exact vulnerability the two-layer isolation design exists to prevent, and stand-ins have
 * a way of surviving into production.
 */
@FunctionalInterface
public interface PrincipalResolver {

    Optional<AuthenticatedPrincipal> resolve(HttpServletRequest request);
}
