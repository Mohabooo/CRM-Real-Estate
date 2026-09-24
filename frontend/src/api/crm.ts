import { apiClient, queryString } from './client';
import type { Page } from './inventory';

/**
 * Leads and customers, only as far as a hold needs them.
 *
 * A reservation is held for exactly one of a lead or a customer — the database enforces it
 * with `num_nonnulls(lead_id, customer_id) = 1` — so the screen that places one has to offer
 * both and let the agent pick a single party.
 */

export interface Lead {
  id: string;
  name: string;
  phone: string | null;
  stage: string;
  status: string;
}

export interface Customer {
  id: string;
  displayName: string;
  phone: string | null;
  status: string;
}

/** One party a hold can be placed for, whichever kind it is. */
export interface Party {
  kind: 'lead' | 'customer';
  id: string;
  name: string;
  phone: string | null;
}

export const crmApi = {
  leads: (size = 100) =>
    apiClient.get<Page<Lead>>(`/api/v1/leads${queryString({ size })}`),

  customers: (size = 100) =>
    apiClient.get<Page<Customer>>(`/api/v1/customers${queryString({ size })}`),
};

/**
 * Both lists as one set of choices.
 *
 * Customers first: a hold for somebody already converted is the commoner case, and putting
 * them at the top of a long list saves the scrolling that leads to picking the wrong one.
 */
export function partiesOf(customers: Customer[], leads: Lead[]): Party[] {
  return [
    ...customers.map<Party>((customer) => ({
      kind: 'customer',
      id: customer.id,
      name: customer.displayName,
      phone: customer.phone,
    })),
    ...leads.map<Party>((lead) => ({
      kind: 'lead',
      id: lead.id,
      name: lead.name,
      phone: lead.phone,
    })),
  ];
}
