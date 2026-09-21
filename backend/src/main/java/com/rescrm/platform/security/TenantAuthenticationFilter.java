package com.rescrm.platform.security;

import com.rescrm.platform.errors.ApiErrorResponse;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.observability.CorrelationId;
import com.rescrm.platform.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Establishes the tenant and the caller for the request, from authentication alone.
 *
 * <p>This is the only place {@link TenantContext} is populated for an HTTP request. Nothing
 * reads a tenant id from a header, a path variable or a body field, so a client cannot
 * nominate the tenant it wants to act as — which is the difference between tenant isolation
 * and the appearance of it (doc 28, section 4).
 *
 * <p>With no {@link PrincipalResolver} bean present, every protected path answers 401. That is
 * deliberate: authentication lands in a later increment, and an application that fails closed
 * until then is safer than one carrying a permissive stand-in.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class TenantAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Paths that carry no tenant and no caller.
     *
     * <p>Invitation acceptance is here by necessity: the person presenting an invitation token
     * has no account yet, so requiring authentication would make the invitation unusable. The
     * token itself is the credential, and it is verified by hash inside the service.
     */
    private static final List<String> PUBLIC_PATHS = List.of(
            "/actuator",
            "/api/v1/platform/info",
            "/api/v1/invitations/accept");

    private final Optional<PrincipalResolver> principalResolver;
    private final ObjectMapper objectMapper;

    public TenantAuthenticationFilter(Optional<PrincipalResolver> principalResolver,
                                      ObjectMapper objectMapper) {
        this.principalResolver = principalResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Optional<AuthenticatedPrincipal> principal =
                principalResolver.flatMap(resolver -> resolver.resolve(request));

        if (principal.isEmpty()) {
            reject(response);
            return;
        }

        try {
            AuthenticatedPrincipal caller = principal.get();
            SecurityContext.set(caller);
            TenantContext.set(caller.tenantId());
            chain.doFilter(request, response);
        } finally {
            // Both holders, always. A leaked tenant on a pooled servlet thread would be
            // inherited by whoever is served next on it.
            TenantContext.clear();
            SecurityContext.clear();
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.UNAUTHORIZED.httpStatus());
        response.setContentType("application/json");
        ApiErrorResponse body = ApiErrorResponse.of(
                ErrorCode.UNAUTHORIZED,
                "Authentication is required",
                Map.of(),
                CorrelationId.current());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
