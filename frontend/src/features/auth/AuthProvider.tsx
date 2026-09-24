import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { authApi, type Credentials, type Me, type Tenant } from '@/api/auth';
import { setUnauthorizedHandler } from '@/api/client';
import { AuthContext, type AuthStatus } from './AuthContext';

/**
 * Holds the session for the application.
 *
 * Two things here are less obvious than they look.
 *
 * The session is restored by ASKING the server, not by reading storage. The cookie is
 * HttpOnly, so there is nothing to read; `GET /me` either answers or returns 401, and that
 * answer is the truth. It also means a session revoked elsewhere — an administrator
 * deactivating the account — takes effect on the next page load rather than whenever a
 * cached flag happens to expire.
 *
 * And a 401 from any later request signs the caller out here, through the handler the API
 * client calls. A session can end between one request and the next; without this, screens
 * would keep rendering as though it had not.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [me, setMe] = useState<Me | null>(null);
  const [tenant, setTenant] = useState<Tenant | null>(null);

  const clear = useCallback(() => {
    setMe(null);
    setTenant(null);
    setStatus('signedOut');
  }, []);

  const adopt = useCallback(async (principal: Me) => {
    setMe(principal);
    setStatus('signedIn');
    try {
      setTenant(await authApi.currentTenant());
    } catch {
      // The tenant is for the header only. Failing to read it must not undo a sign-in that
      // already succeeded.
      setTenant(null);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;

    void (async () => {
      try {
        const principal = await authApi.me();
        if (!cancelled) {
          await adopt(principal);
        }
      } catch {
        if (!cancelled) {
          clear();
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [adopt, clear]);

  useEffect(() => {
    setUnauthorizedHandler(clear);
    return () => setUnauthorizedHandler(null);
  }, [clear]);

  const signIn = useCallback(
    async (credentials: Credentials) => {
      await adopt(await authApi.login(credentials));
    },
    [adopt],
  );

  const signOut = useCallback(async () => {
    try {
      await authApi.logout();
    } finally {
      // Cleared whatever the server said. Sign-out is the one action that must never leave
      // somebody looking at a signed-in screen because a request failed.
      clear();
    }
  }, [clear]);

  const value = useMemo(
    () => ({ status, me, tenant, signIn, signOut }),
    [status, me, tenant, signIn, signOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
