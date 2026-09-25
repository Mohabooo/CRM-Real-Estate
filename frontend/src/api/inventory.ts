import { apiClient, queryString } from './client';

/**
 * Projects and units (E3-S4).
 *
 * `listPrice` is a decimal string and stays one all the way to the formatter. Nothing here
 * converts it to a number.
 */

export type UnitStatus = 'available' | 'reserved' | 'sold' | 'blocked';

export interface Project {
  id: string;
  commercialModel: string;
  /** The policy's answer about the model, so a screen never has to interpret the code. */
  sellerOfRecord: string;
  developerId: string | null;
  nameAr: string | null;
  nameEn: string | null;
  displayName: string;
  location: string | null;
  deliveryDate: string | null;
  status: string;
}

export interface Unit {
  id: string;
  projectId: string;
  phaseId: string | null;
  code: string;
  type: string | null;
  areaSqm: number | null;
  floor: string | null;
  view: string | null;
  /** Decimal string. Never a number. */
  listPrice: string;
  status: UnitStatus;
  blockedReason: string | null;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface UnitFilters {
  status?: UnitStatus[];
  projectId?: string;
  type?: string;
  minPrice?: string;
  maxPrice?: string;
  page?: number;
  size?: number;
}

export const inventoryApi = {
  projects: () => apiClient.get<Page<Project>>('/api/v1/projects?size=200'),

  unit: (id: string) => apiClient.get<Unit>(`/api/v1/units/${id}`),

  /**
   * Omitting `status` gives the default view, which excludes sold and blocked units. That is
   * the server's choice and the client does not second-guess it — the value of the default
   * is that an agent never quotes a unit that is already gone.
   */
  units: (filters: UnitFilters) =>
    apiClient.get<Page<Unit>>(
      `/api/v1/units${queryString({
        status: filters.status,
        projectId: filters.projectId,
        type: filters.type,
        minPrice: filters.minPrice,
        maxPrice: filters.maxPrice,
        page: filters.page,
        size: filters.size,
      })}`,
    ),
};
