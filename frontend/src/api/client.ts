import { ApiError, ErrorCodes, type ApiErrorEnvelope } from './errors';

const CORRELATION_HEADER = 'X-Correlation-Id';

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  signal?: AbortSignal;
  headers?: Record<string, string>;
  /** Sent as Idempotency-Key. Required by the API for money-affecting writes (doc 23). */
  idempotencyKey?: string;
  /**
   * Suppresses the session-expiry handler for this call.
   *
   * Set on sign-in, where a 401 means "those details are wrong" rather than "your session
   * ended". Without it, a mistyped password would sign the caller out of a session they
   * never had and bounce them to the page they are already on.
   */
  expected401?: boolean;
}

type UnauthorizedHandler = () => void;

let onUnauthorized: UnauthorizedHandler | null = null;

/**
 * Registers what to do when the server says the session is gone.
 *
 * A session can end between one request and the next — it expires, or an administrator
 * deactivates the account — and every screen would otherwise have to handle that itself.
 * One handler, registered by the auth provider, keeps that out of every feature.
 */
export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  onUnauthorized = handler;
}

function generateCorrelationId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `c-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * The single HTTP entry point.
 *
 * Every call goes through here so that correlation ids, error normalisation and JSON handling
 * exist in one place. Components never call `fetch` directly; an ad-hoc fetch would produce an
 * error shape nothing else understands.
 *
 * Money is transported as a string and is never parsed into a JavaScript number anywhere in
 * this client — `Number("2850000.00")` is exactly the float conversion the backend's
 * string-only convention exists to prevent.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const correlationId = generateCorrelationId();
  const headers: Record<string, string> = {
    Accept: 'application/json',
    [CORRELATION_HEADER]: correlationId,
    ...options.headers,
  };

  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (options.idempotencyKey) {
    headers['Idempotency-Key'] = options.idempotencyKey;
  }

  let response: Response;
  try {
    response = await fetch(path, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      ...(options.signal ? { signal: options.signal } : {}),
    });
  } catch (cause) {
    // The request never reached the server. Distinguished from a server-side failure because
    // the remedy is different: retry or check connectivity, rather than fix the input.
    throw new ApiError({
      code: ErrorCodes.NETWORK_ERROR,
      message: cause instanceof Error ? cause.message : 'Network request failed',
      status: 0,
      correlationId,
    });
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  const payload: unknown = text.length > 0 ? safeParse(text) : undefined;

  if (!response.ok) {
    const error = toApiError(payload, response, correlationId);
    if (error.status === 401 && !options.expected401) {
      onUnauthorized?.();
    }
    throw error;
  }

  return payload as T;
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return { raw: text };
  }
}

function toApiError(payload: unknown, response: Response, fallbackCorrelationId: string): ApiError {
  const envelope = payload as Partial<ApiErrorEnvelope> | undefined;
  const body = envelope?.error;
  const serverCorrelationId =
    body?.correlationId ?? response.headers.get(CORRELATION_HEADER) ?? fallbackCorrelationId;

  if (body?.code) {
    return new ApiError({
      code: body.code,
      message: body.message ?? 'Request failed',
      status: response.status,
      details: body.details ?? {},
      correlationId: serverCorrelationId,
    });
  }

  // A non-conforming error body (a proxy error page, for instance) still becomes an ApiError,
  // so no caller has to handle two shapes.
  return new ApiError({
    code: ErrorCodes.INTERNAL_ERROR,
    message: `Request failed with status ${response.status}`,
    status: response.status,
    correlationId: serverCorrelationId,
  });
}

export const apiClient = {
  get: <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'POST', body }),
  patch: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'PATCH', body }),
};

/**
 * Builds a query string, leaving out anything unset.
 *
 * A repeated key produces a repeated parameter, which is what the API's list-valued filters
 * expect — `?status=available&status=reserved` rather than a comma-joined string.
 */
export function queryString(params: Record<string, string | number | string[] | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') {
      continue;
    }
    if (Array.isArray(value)) {
      value.forEach((entry) => search.append(key, entry));
    } else {
      search.append(key, String(value));
    }
  }
  const query = search.toString();
  return query === '' ? '' : `?${query}`;
}
