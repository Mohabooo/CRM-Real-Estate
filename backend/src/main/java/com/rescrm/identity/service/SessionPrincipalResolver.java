package com.rescrm.identity.service;

import com.rescrm.platform.security.AuthenticatedPrincipal;
import com.rescrm.platform.security.PrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The production implementation of the seam Epic 1 left open.
 *
 * <p>{@code TenantAuthenticationFilter} was written to refuse every protected request until
 * this bean existed — a deliberate fail-closed default, and the reason no endpoint could be
 * reached from a browser before now. This fills it with the session mechanism from doc 28
 * section 6 and changes nothing else about how the filter works.
 *
 * <p>It lives in {@code identity} rather than {@code platform} on purpose. Sessions and users
 * are identity's tables, and {@code platform} is the layer everything else depends on: having
 * it reach back into a business module would invert the dependency the module rules exist to
 * protect. The interface stays in {@code platform}, the implementation lives with its data,
 * and Spring joins them — which is what the seam was for.
 */
@Component
public class SessionPrincipalResolver implements PrincipalResolver {

    private final SessionCookie cookie;
    private final AuthenticationService authentication;

    public SessionPrincipalResolver(SessionCookie cookie, AuthenticationService authentication) {
        this.cookie = cookie;
        this.authentication = authentication;
    }

    @Override
    public Optional<AuthenticatedPrincipal> resolve(HttpServletRequest request) {
        return cookie.readFrom(request).flatMap(authentication::resolve);
    }
}
