package com.rescrm.platform.observability;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Access to the current request's correlation id.
 *
 * <p>Stored in SLF4J's {@link MDC} so every log line carries it automatically via the logging
 * pattern, and echoed on the response so a user reporting a problem can quote an id that
 * leads straight to their request.
 */
public final class CorrelationId {

    /** Header clients may supply to propagate an id across service boundaries. */
    public static final String HEADER = "X-Correlation-Id";

    /** MDC key; referenced by the logback pattern in {@code logback-spring.xml}. */
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }

    /** The current correlation id, or {@code "none"} outside a request. */
    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null ? "none" : value;
    }

    static void set(String correlationId) {
        MDC.put(MDC_KEY, correlationId);
    }

    static void clear() {
        MDC.remove(MDC_KEY);
    }

    static String generate() {
        return UUID.randomUUID().toString();
    }
}
