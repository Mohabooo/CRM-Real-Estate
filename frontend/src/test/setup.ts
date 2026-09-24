import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

/**
 * No backend, by default.
 *
 * The auth provider asks the server who is signed in as soon as it mounts, so every test
 * that renders the app would otherwise make a real request — to a relative URL with no
 * origin, which fails slowly and differently depending on the runtime. A 401 is the honest
 * answer for a test with no server: the provider settles on "signed out" and the test starts
 * from a known state. A test that wants a session mocks the API module instead.
 */
beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () =>
      new Response(JSON.stringify({ error: { code: 'UNAUTHORIZED', message: 'no session' } }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  );
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
