/**
 * The error envelope returned by every backend endpoint.
 *
 * Mirrors `ApiErrorResponse` on the server (doc 23, section 4). One shape for every failure
 * means the client writes one error handler rather than guessing per endpoint.
 */
export interface ApiErrorBody {
  code: string;
  message: string;
  details?: Record<string, unknown>;
  correlationId?: string;
  timestamp?: string;
}

export interface ApiErrorEnvelope {
  error: ApiErrorBody;
}

/** Error codes the client treats specially. Mirrors the server's ErrorCode enum. */
export const ErrorCodes = {
  VALIDATION_FAILED: 'VALIDATION_FAILED',
  UNAUTHORIZED: 'UNAUTHORIZED',
  FORBIDDEN: 'FORBIDDEN',
  NOT_FOUND: 'NOT_FOUND',
  CONFLICT: 'CONFLICT',
  NOT_APPLICABLE_FOR_COMMERCIAL_MODEL: 'NOT_APPLICABLE_FOR_COMMERCIAL_MODEL',
  PLAN_INVARIANT_VIOLATION: 'PLAN_INVARIANT_VIOLATION',
  NETWORK_ERROR: 'NETWORK_ERROR',
  INTERNAL_ERROR: 'INTERNAL_ERROR',
} as const;

export type ErrorCode = (typeof ErrorCodes)[keyof typeof ErrorCodes];

/**
 * A normalised client-side error.
 *
 * Every failure path — HTTP error, network failure, unparseable body — produces one of these,
 * so callers never have to distinguish "the server said no" from "the request never arrived".
 */
export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  readonly details: Record<string, unknown>;
  readonly correlationId: string | undefined;

  constructor(params: {
    code: string;
    message: string;
    status: number;
    details?: Record<string, unknown>;
    correlationId?: string;
  }) {
    super(params.message);
    this.name = 'ApiError';
    this.code = params.code;
    this.status = params.status;
    this.details = params.details ?? {};
    this.correlationId = params.correlationId;
  }

  /**
   * True when the capability does not apply under the deal's commercial model.
   *
   * The UI must render an explanation for this case rather than an empty state showing
   * zeros — a zero reads as "nothing owed", which would be false (rule R-BRK-1).
   */
  get isNotApplicableForCommercialModel(): boolean {
    return this.code === ErrorCodes.NOT_APPLICABLE_FOR_COMMERCIAL_MODEL;
  }

  get isNetworkError(): boolean {
    return this.code === ErrorCodes.NETWORK_ERROR;
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}
