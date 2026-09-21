import { apiClient } from './client';

export interface HealthResponse {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN';
  components?: Record<string, { status: string }>;
}

export interface PlatformInfo {
  application: string;
  version: string;
  epic: string;
  businessDomainsImplemented: string[];
}

export const platformApi = {
  health: () => apiClient.get<HealthResponse>('/actuator/health'),
  info: () => apiClient.get<PlatformInfo>('/api/v1/platform/info'),
};
