import { apiClient } from './client';
import type { Page } from './inventory';

/**
 * Holds on units (Epic 4).
 *
 * `depositMeaning` comes from the commercial-model policy, not from the client working it
 * out. The same amount means money the tenant holds under one model and a confirmation the
 * developer received it under another, and doc 25 is clear that difference is invisible in
 * the number.
 */

export type ReservationStatus =
  | 'pending'
  | 'confirmed'
  | 'converted'
  | 'released'
  | 'expired'
  | 'cancelled';

export type DepositMeaning = 'TENANT_CASH' | 'DEVELOPER_RECEIPT_CONFIRMATION';

export interface Reservation {
  id: string;
  unitId: string;
  leadId: string | null;
  customerId: string | null;
  agentUserId: string;
  branchId: string | null;
  reservedAt: string;
  expiresAt: string;
  originalExpiresAt: string;
  extensionCount: number;
  depositAmount: string | null;
  depositReceived: boolean;
  depositMeaning: DepositMeaning;
  status: ReservationStatus;
  closedReason: string | null;
  closedAt: string | null;
}

export interface PlaceHold {
  unitId: string;
  leadId?: string;
  customerId?: string;
  expiresAt?: string;
  depositAmount?: string;
  depositReceived: boolean;
}

/** Statuses that still hold a unit off the market. */
export const ACTIVE_STATUSES: ReservationStatus[] = ['pending', 'confirmed'];

export const reservationsApi = {
  list: (page = 0, size = 50) =>
    apiClient.get<Page<Reservation>>(`/api/v1/reservations?page=${page}&size=${size}`),

  place: (hold: PlaceHold) => apiClient.post<Reservation>('/api/v1/reservations', hold),

  confirm: (id: string) => apiClient.post<Reservation>(`/api/v1/reservations/${id}/confirm`),

  /**
   * Doc 18 section 3: confirmed -> released only. A reason is required by the API, and
   * rightly — a released unit goes back on the market and somebody will ask why.
   */
  release: (id: string, reason: string) =>
    apiClient.post<Reservation>(`/api/v1/reservations/${id}/release`, { reason }),

  /**
   * Doc 18 section 3: pending -> cancelled, the close-out for a hold that never withheld
   * anything. Distinct from release and not interchangeable with it: the table has no
   * pending -> released row, because there is no unit to give back.
   *
   * The reason is optional, matching the table's empty precondition column.
   */
  cancel: (id: string, reason?: string) =>
    apiClient.post<Reservation>(`/api/v1/reservations/${id}/cancel`, { reason }),

  extend: (id: string, expiresAt: string, reason?: string) =>
    apiClient.post<Reservation>(`/api/v1/reservations/${id}/extend`, { expiresAt, reason }),
};
