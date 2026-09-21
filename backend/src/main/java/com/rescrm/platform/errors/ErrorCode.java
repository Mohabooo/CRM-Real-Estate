package com.rescrm.platform.errors;

/**
 * The single catalogue of stable API error codes (doc 23, section 4).
 *
 * <p>Codes are part of the API contract: clients branch on them, so they are never renamed,
 * only added. The HTTP status is attached here rather than chosen at each throw site, so one
 * condition cannot surface as 400 in one endpoint and 422 in another.
 *
 * <p>Note {@code NOT_FOUND} is deliberately used for cross-tenant access too: a 403 would
 * confirm that a record exists in another tenant, which is an information leak (doc 28,
 * section 6).
 */
public enum ErrorCode {

    /** Malformed request: unparseable body, wrong types, missing required fields. */
    VALIDATION_FAILED(400),

    /** Not authenticated. */
    UNAUTHORIZED(401),

    /** Authenticated but not permitted. Never used for cross-tenant records. */
    FORBIDDEN(403),

    /** Record absent, or present in another tenant. */
    NOT_FOUND(404),

    /** Concurrent modification, or a uniqueness conflict such as a unit already sold. */
    CONFLICT(409),

    /** Same idempotency key replayed with a different payload. */
    IDEMPOTENCY_CONFLICT(409),

    /** A business rule refused the operation. The default for domain invariant failures. */
    BUSINESS_RULE_VIOLATION(422),

    /**
     * The requested capability does not apply under the deal's commercial model — for example
     * recording a payment against a brokered deal (rule R-PAY-0), or requesting collection
     * metrics for one (rule R-BRK-1).
     *
     * <p>Distinct from NOT_FOUND on purpose: the resource exists, the operation is simply
     * meaningless for it, and the client should render an explanation rather than zeros.
     */
    NOT_APPLICABLE_FOR_COMMERCIAL_MODEL(422),

    /** A generated schedule did not sum to the net value (rule R-PLAN-4). */
    PLAN_INVARIANT_VIOLATION(422),

    /** An illegal state transition was attempted (doc 18). */
    ILLEGAL_STATE_TRANSITION(422),

    /** Arithmetic or comparison across two currencies. */
    CURRENCY_MISMATCH(422),

    /** Request rate exceeded. */
    RATE_LIMITED(429),

    /** Unhandled failure. Details are never echoed to the caller. */
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    /**
     * The HTTP status this code maps to, as a plain int.
     *
     * <p>Deliberately not Spring's {@code HttpStatus}: this package is depended on by the pure
     * financial calculators, which must stay free of framework types (architecture rule
     * {@code financial_core_is_framework_free}). The web layer converts it.
     */
    public int httpStatus() {
        return httpStatus;
    }
}
