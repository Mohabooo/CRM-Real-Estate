import { createContext, useContext } from 'react';
import type { Credentials, Me, Tenant } from '@/api/auth';

/**
 * Who is signed in, if anyone.
 *
 * `loading` is a state of its own rather than "not signed in yet". On a page load the
 * session lives in a cookie the client cannot read, so the only way to know is to ask the
 * server — and treating that moment as signed-out would bounce a signed-in person to the
 * login form on every refresh.
 */
export type AuthStatus = 'loading' | 'signedIn' | 'signedOut';

export interface AuthContextValue {
  status: AuthStatus;
  me: Me | null;
  tenant: Tenant | null;
  signIn: (credentials: Credentials) => Promise<void>;
  signOut: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return context;
}
