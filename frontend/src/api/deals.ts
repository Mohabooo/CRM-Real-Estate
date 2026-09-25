import { apiClient, queryString } from './client';
import type { Page } from './inventory';

/**
 * Deals and payment plans (Epic 5).
 *
 * Every amount is a decimal string and stays one all the way to the formatter. Nothing in
 * this module converts money to a number, and nothing in it computes a schedule: the server
 * generates and stores the schedule, and what arrives here is what activation will make
 * live. A client-side recalculation would be a second implementation of doc 17's arithmetic,
 * and the day it disagreed the customer would be holding whichever one was wrong.
 *
 * There is deliberately no outstanding or paid figure anywhere below. Those need actual
 * payments, which are Epic 6; a zero under one of those names would be read as a fact.
 */

export type DealStatus = 'draft' | 'active' | 'completed' | 'cancelled';
export type InstallmentKind = 'down_payment' | 'installment' | 'delivery';
export type PlanStatus = 'draft' | 'active' | 'superseded' | 'closed' | 'cancelled';
export type Frequency = 'monthly' | 'quarterly' | 'semi_annual' | 'annual';

export interface DealDiscount {
  id: string;
  kind: 'percent' | 'fixed';
  /** The rate for a percentage, the amount for a fixed sum. Always a decimal string. */
  value: string;
  amount: string;
  reason: string | null;
  createdAt: string;
}

export interface ScheduleRow {
  id: string;
  sequenceNo: number;
  kind: InstallmentKind;
  dueDate: string;
  expectedAmount: string;
  status: 'pending' | 'void';
}

export interface Schedule {
  planId: string;
  version: number;
  status: PlanStatus;
  sourceTemplateId: string | null;
  netValue: string;
  downPaymentAmount: string;
  deliveryPaymentAmount: string;
  financedAmount: string;
  installmentCount: number;
  frequency: Frequency;
  firstDueDate: string;
  /** The sum of the live rows, computed server-side so the screen can show R-PLAN-4. */
  total: string;
  rows: ScheduleRow[];
}

export interface Deal {
  id: string;
  unitId: string;
  primaryCustomerId: string;
  agentUserId: string;
  branchId: string | null;
  sourceReservationId: string | null;
  dealDate: string;
  grossValue: string;
  totalDiscount: string;
  netValue: string;
  currency: string;
  commercialModel: string;
  status: DealStatus;
  discountApproved: boolean;
  discountApprovedAt: string | null;
  /** Whether the tenant's threshold has been crossed (R-DISC-5). The server decides. */
  approvalRequired: boolean;
  downPaymentConfirmedAt: string | null;
  /** Whether E5-S7's action exists on this deal at all — brokered, and a non-zero down. */
  downPaymentConfirmationApplies: boolean;
  activatedAt: string | null;
  completedAt: string | null;
  cancelledAt: string | null;
  cancelledReason: string | null;
  discounts: DealDiscount[];
  schedule: Schedule | null;
}

export interface PaymentPlanTemplate {
  id: string;
  projectId: string | null;
  name: string;
  shorthandLabel: string | null;
  downPaymentType: 'percent' | 'fixed';
  downPaymentValue: string;
  deliveryPaymentPercent: string;
  installmentCount: number;
  frequency: Frequency;
  firstInstallmentOffsetDays: number | null;
  active: boolean;
  createdAt: string;
}

export interface DraftDealInput {
  unitId: string;
  customerId: string;
  sourceReservationId?: string;
  dealDate?: string;
}

/** Exactly one of the two, matching the API: a discount is a rate or a sum, never both. */
export type DiscountInput =
  | { percent: string; reason?: string }
  | { amount: string; reason?: string };

export const dealsApi = {
  list: (page = 0, size = 25) =>
    apiClient.get<Page<Deal>>(`/api/v1/deals${queryString({ page, size })}`),

  get: (id: string) => apiClient.get<Deal>(`/api/v1/deals/${id}`),

  draft: (input: DraftDealInput) => apiClient.post<Deal>('/api/v1/deals', input),

  addDiscount: (id: string, input: DiscountInput) =>
    apiClient.post<Deal>(`/api/v1/deals/${id}/discounts`, input),

  removeDiscount: (id: string, discountId: string) =>
    apiClient.post<Deal>(`/api/v1/deals/${id}/discounts/${discountId}/remove`),

  approveDiscount: (id: string) =>
    apiClient.post<Deal>(`/api/v1/deals/${id}/discounts/approve`),

  /** The templates this deal may be put on: active, and offered for its project. */
  offeredTemplates: (id: string) =>
    apiClient.get<PaymentPlanTemplate[]>(`/api/v1/deals/${id}/payment-plan-templates`),

  /**
   * Generates and STORES the schedule, returning the deal with it attached.
   *
   * Run again it regenerates in place, so comparing two templates leaves the one that was
   * settled on and no orphans.
   */
  applyTemplate: (id: string, templateId: string) =>
    apiClient.post<Deal>(`/api/v1/deals/${id}/payment-plan`, { templateId }),

  activate: (id: string) => apiClient.post<Deal>(`/api/v1/deals/${id}/activate`),

  confirmDownPayment: (id: string, receivedOn?: string) =>
    apiClient.post<Deal>(`/api/v1/deals/${id}/down-payment-confirmation`, { receivedOn }),

  complete: (id: string) => apiClient.post<Deal>(`/api/v1/deals/${id}/complete`),

  cancel: (id: string, reason: string) =>
    apiClient.post<Deal>(`/api/v1/deals/${id}/cancel`, { reason }),

  templates: () => apiClient.get<PaymentPlanTemplate[]>('/api/v1/payment-plan-templates'),
};

/**
 * A template's terms as one readable line — "10% down · 32 × quarterly · 5% on delivery".
 *
 * Built from the template's own fields rather than from its shorthand label, which is free
 * text a tenant may have written before editing the numbers underneath it.
 */
export function describeTemplate(
  template: PaymentPlanTemplate,
  t: (key: string, options?: Record<string, unknown>) => string,
): string {
  const down =
    template.downPaymentType === 'percent'
      ? t('deals.template.downPercent', { value: template.downPaymentValue })
      : t('deals.template.downAmount', { value: template.downPaymentValue });

  return [
    down,
    t('deals.template.installments', {
      count: template.installmentCount,
      frequency: t(`frequency.${template.frequency}`),
    }),
    t('deals.template.delivery', { value: template.deliveryPaymentPercent }),
  ].join(' · ');
}
