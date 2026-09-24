import { apiClient } from './client';

/**
 * Sign-in, sign-out and "who am I".
 *
 * The session token is never handled here. It travels as an HttpOnly cookie the browser
 * attaches by itself, which is the point: a token this module could read would be a token a
 * cross-site scripting bug could read.
 */

export type Role =
  | 'OWNER'
  | 'BRANCH_MANAGER'
  | 'TEAM_LEADER'
  | 'SALES_AGENT'
  | 'OPERATIONS'
  | 'FINANCE'
  | 'PLATFORM_ADMIN';

export interface Me {
  userId: string;
  tenantId: string;
  role: Role;
  branchId: string | null;
  mayAdministerIdentity: boolean;
  branchScope: string;
}

export interface Tenant {
  id: string;
  name: string;
  slug: string;
  status: string;
  defaultCommercialModel: string;
}

export interface Credentials {
  company: string;
  email: string;
  password: string;
}

export const authApi = {
  /**
   * A 401 here means the details are wrong, not that a session ended — so the global
   * session-expiry handler is suppressed for this one call.
   */
  login: (credentials: Credentials) =>
    apiClient.post<Me>('/api/v1/auth/login', credentials, { expected401: true }),

  logout: () => apiClient.post<void>('/api/v1/auth/logout'),

  /** Used to restore a session on page load: the cookie is there or it is not. */
  me: () => apiClient.get<Me>('/api/v1/me', { expected401: true }),

  currentTenant: () => apiClient.get<Tenant>('/api/v1/tenants/current'),
};
