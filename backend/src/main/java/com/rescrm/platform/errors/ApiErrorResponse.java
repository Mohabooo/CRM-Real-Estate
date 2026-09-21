package com.rescrm.platform.errors;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single error envelope returned by every endpoint (doc 23, section 4):
 *
 * <pre>
 * { "error": { "code": "PLAN_INVARIANT_VIOLATION",
 *              "message": "Schedule total does not equal net value",
 *              "details": { "netValue": "2850000.00",
 *                           "scheduleTotal": "2849999.84",
 *                           "difference": "0.16" },
 *              "correlationId": "…",
 *              "timestamp": "…" } }
 * </pre>
 *
 * <p>One shape for every failure means a client writes one error handler. The correlation id
 * is included so a user can quote it and an engineer can find the exact request in the logs.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class ApiErrorResponse {

    private final ErrorBody error;

    private ApiErrorResponse(ErrorBody error) {
        this.error = error;
    }

    public static ApiErrorResponse of(ErrorCode code, String message, Map<String, Object> details,
                                      String correlationId) {
        return new ApiErrorResponse(new ErrorBody(code.name(), message, details, correlationId, Instant.now()));
    }

    public ErrorBody getError() {
        return error;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static final class ErrorBody {

        private final String code;
        private final String message;
        private final Map<String, Object> details;
        private final String correlationId;
        private final Instant timestamp;

        ErrorBody(String code, String message, Map<String, Object> details, String correlationId,
                  Instant timestamp) {
            this.code = code;
            this.message = message;
            this.details = details == null || details.isEmpty()
                    ? Collections.emptyMap()
                    : new LinkedHashMap<>(details);
            this.correlationId = correlationId;
            this.timestamp = timestamp;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public Map<String, Object> getDetails() {
            return details;
        }

        public String getCorrelationId() {
            return correlationId;
        }

        public Instant getTimestamp() {
            return timestamp;
        }
    }
}
