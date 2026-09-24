package com.rescrm.platform.security;

import com.rescrm.platform.errors.ApiErrorResponse;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.observability.CorrelationId;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refuses state-changing requests that another origin initiated.
 *
 * <p>The second half of the cross-site request forgery defence. The first half is
 * {@code SameSite=Lax} on the session cookie, which tells the browser not to attach it to a
 * cross-site POST — but that is enforced entirely in the client, by a browser whose version
 * and settings the server does not control. A same-site check the server performs itself
 * costs one header comparison and does not depend on anyone else getting it right.
 *
 * <p>Deliberately a check on {@code Origin} rather than a synchroniser token. A token would
 * mean a second credential to mint, store, rotate and hand to the frontend on every page
 * load, to defend against something the origin already identifies. If the API later serves a
 * frontend on a different host, that host goes in {@code crm.security.allowed-origins} and
 * nothing else changes.
 *
 * <p>Safe methods pass untouched, and so does a request with no {@code Origin} at all: a
 * server-to-server call, a {@code curl}, an integration test. Cross-site forgery is a browser
 * attack, and browsers send the header on exactly the requests that matter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final List<String> allowedOrigins;
    private final ObjectMapper objectMapper;

    public OriginCheckFilter(
            @Value("${crm.security.allowed-origins:}") List<String> allowedOrigins,
            ObjectMapper objectMapper) {
        this.allowedOrigins = allowedOrigins;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod().toUpperCase(java.util.Locale.ROOT))) {
            chain.doFilter(request, response);
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank() || isAcceptable(origin, request)) {
            chain.doFilter(request, response);
            return;
        }
        reject(response);
    }

    private boolean isAcceptable(String origin, HttpServletRequest request) {
        if (allowedOrigins.stream().anyMatch(allowed -> allowed.equalsIgnoreCase(origin))) {
            return true;
        }
        return sameOrigin(origin, request);
    }

    /**
     * Whether the Origin names this very server.
     *
     * <p>Compared against the request's own scheme, host and port rather than a configured
     * value, so the ordinary same-origin deployment — the frontend served from the same host
     * as the API, which is what the development proxy also produces — needs no configuration
     * at all. An installation that splits them lists the frontend explicitly.
     */
    private boolean sameOrigin(String origin, HttpServletRequest request) {
        try {
            URI candidate = new URI(origin);
            if (candidate.getHost() == null) {
                return false;
            }
            int candidatePort = portOf(candidate.getPort(), candidate.getScheme());
            int requestPort = portOf(request.getServerPort(), request.getScheme());
            return candidate.getScheme() != null
                    && candidate.getScheme().equalsIgnoreCase(request.getScheme())
                    && candidate.getHost().equalsIgnoreCase(request.getServerName())
                    && candidatePort == requestPort;
        } catch (URISyntaxException malformed) {
            return false;
        }
    }

    private static int portOf(int port, String scheme) {
        if (port > 0) {
            return port;
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.FORBIDDEN.httpStatus());
        response.setContentType("application/json");
        ApiErrorResponse body = ApiErrorResponse.of(
                ErrorCode.FORBIDDEN,
                "This request did not come from an accepted origin",
                Map.of(),
                CorrelationId.current());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
