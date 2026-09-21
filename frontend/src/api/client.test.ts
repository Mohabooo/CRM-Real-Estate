import { describe, expect, it, vi, beforeEach } from 'vitest';
import { request } from './client';
import { ApiError, ErrorCodes } from './errors';

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
}

describe('api client', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });

  it('sends a correlation id on every request', async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));

    await request('/api/v1/platform/info');

    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Record<string, string>;
    expect(headers['X-Correlation-Id']).toBeTruthy();
  });

  it('returns the parsed body on success', async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse({ application: 'crm-backend' }));

    const result = await request<{ application: string }>('/api/v1/platform/info');

    expect(result.application).toBe('crm-backend');
  });

  it('normalises the documented error envelope into an ApiError', async () => {
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse(
        {
          error: {
            code: 'PLAN_INVARIANT_VIOLATION',
            message: 'Schedule total does not equal net value',
            details: {
              netValue: '2850000.00',
              scheduleTotal: '2849999.84',
              difference: '0.16',
            },
            correlationId: 'abc-123',
          },
        },
        { status: 422 },
      ),
    );

    await expect(request('/api/v1/deals/1/activate')).rejects.toSatisfy((error: unknown) => {
      expect(error).toBeInstanceOf(ApiError);
      const apiError = error as ApiError;
      expect(apiError.code).toBe('PLAN_INVARIANT_VIOLATION');
      expect(apiError.status).toBe(422);
      // The figures survive the round trip — that is the whole point of carrying details.
      expect(apiError.details.difference).toBe('0.16');
      expect(apiError.correlationId).toBe('abc-123');
      return true;
    });
  });

  it('flags a commercial-model mismatch so the UI can explain rather than show zeros', async () => {
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse(
        {
          error: {
            code: 'NOT_APPLICABLE_FOR_COMMERCIAL_MODEL',
            message: 'Recording a payment does not apply to brokered deals',
          },
        },
        { status: 422 },
      ),
    );

    try {
      await request('/api/v1/payments');
      expect.unreachable('should have thrown');
    } catch (error) {
      expect((error as ApiError).isNotApplicableForCommercialModel).toBe(true);
    }
  });

  it('turns a network failure into an ApiError rather than leaking a raw TypeError', async () => {
    vi.mocked(fetch).mockRejectedValue(new TypeError('Failed to fetch'));

    try {
      await request('/api/v1/platform/info');
      expect.unreachable('should have thrown');
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).code).toBe(ErrorCodes.NETWORK_ERROR);
      expect((error as ApiError).isNetworkError).toBe(true);
      expect((error as ApiError).status).toBe(0);
    }
  });

  it('handles a non-conforming error body without producing a second error shape', async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response('<html>502 Bad Gateway</html>', { status: 502 }),
    );

    try {
      await request('/api/v1/platform/info');
      expect.unreachable('should have thrown');
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).status).toBe(502);
    }
  });

  it('returns undefined for 204 responses', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }));

    await expect(request('/api/v1/something')).resolves.toBeUndefined();
  });

  it('sends an idempotency key when supplied', async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValue(jsonResponse({}));

    await request('/api/v1/payments', {
      method: 'POST',
      body: { amount: '100.00' },
      idempotencyKey: 'key-1',
    });

    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Record<string, string>;
    expect(headers['Idempotency-Key']).toBe('key-1');
  });

  it('never converts a money string into a number', async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse({ amount: '2850000.00', currency: 'EGP' }));

    const result = await request<{ amount: string }>('/api/v1/deals/1');

    // Number("2850000.00") is exactly the float conversion the backend's string-only
    // convention exists to prevent, so the client must hand the string through untouched.
    expect(typeof result.amount).toBe('string');
    expect(result.amount).toBe('2850000.00');
  });
});
