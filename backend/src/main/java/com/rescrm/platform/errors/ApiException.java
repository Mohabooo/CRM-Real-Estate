package com.rescrm.platform.errors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The base exception for conditions that map onto an API error response.
 *
 * <p>Carries a {@link ErrorCode} and an optional detail map. Financial errors are expected to
 * populate details with the actual numbers involved — doc 28, section 7 is explicit that a
 * validation failure reporting only "invalid" is useless to someone reconciling a schedule at
 * month end.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, Collections.emptyMap());
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Collections.emptyMap() : new LinkedHashMap<>(details);
    }

    public ApiException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = Collections.emptyMap();
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return Collections.unmodifiableMap(details);
    }

    // ------------------------------------------------------------ common factories

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " was not found");
    }

    public static ApiException conflict(String message, Map<String, Object> details) {
        return new ApiException(ErrorCode.CONFLICT, message, details);
    }

    public static ApiException businessRule(String message, Map<String, Object> details) {
        return new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, message, details);
    }

    /**
     * The capability does not apply under this deal's commercial model.
     *
     * @param capability what was attempted, e.g. "Recording a payment"
     * @param model      the model that refuses it, e.g. "BROKERED_INVENTORY"
     */
    public static ApiException notApplicableForCommercialModel(String capability, String model) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("capability", capability);
        details.put("commercialModel", model);
        return new ApiException(
                ErrorCode.NOT_APPLICABLE_FOR_COMMERCIAL_MODEL,
                capability + " does not apply to deals under the " + model + " commercial model",
                details);
    }
}
