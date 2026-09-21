package com.rescrm.platform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Establishes a correlation id for every request.
 *
 * <p>Accepts a caller-supplied {@code X-Correlation-Id} when present, otherwise generates one.
 * The id is placed in the MDC for the duration of the request, echoed on the response, and
 * cleared afterwards — clearing matters because servlet threads are pooled and a leaked MDC
 * entry would mislabel the next request's logs.
 *
 * <p>Runs first in the chain so that anything logged by later filters is already tagged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final int MAX_ACCEPTED_LENGTH = 128;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = sanitise(request.getHeader(CorrelationId.HEADER));
        CorrelationId.set(correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            CorrelationId.clear();
        }
    }

    /**
     * Accepts a caller-supplied id only when it is short and free of control characters —
     * an unbounded, unsanitised header value would end up in every log line for that request.
     */
    private String sanitise(String supplied) {
        if (supplied == null || supplied.isBlank() || supplied.length() > MAX_ACCEPTED_LENGTH) {
            return CorrelationId.generate();
        }
        for (int i = 0; i < supplied.length(); i++) {
            char c = supplied.charAt(i);
            boolean acceptable = Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.';
            if (!acceptable) {
                return CorrelationId.generate();
            }
        }
        return supplied;
    }
}
