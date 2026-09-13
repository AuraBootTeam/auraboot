/**
 * Version history API client.
 * Generic service that works with any resource type by accepting a base URL.
 */

import type { VersionEntry } from './types';

interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
  });

  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.message || `Request failed: ${response.status}`);
  }

  const result: ApiResponse<T> = await response.json();
  const code = typeof result.code === 'string' ? parseInt(result.code, 10) : result.code;
  if (code !== 0 && code !== 200) {
    throw new Error(result.message || 'Request failed');
  }

  return result.data;
}

/**
 * Create a version service for a specific resource API base path.
 *
 * Usage:
 *   const service = createVersionService('/api/dashboards');
 *   const versions = await service.getHistory('dashboard-pid-123');
 */
export function createVersionService(apiBase: string) {
  return {
    /**
     * Get version history (without snapshots)
     */
    async getHistory(resourcePid: string): Promise<VersionEntry[]> {
      return request(`${apiBase}/${resourcePid}/versions`);
    },

    /**
     * Get a specific version with full snapshot
     */
    async getVersion(resourcePid: string, versionPid: string): Promise<VersionEntry> {
      return request(`${apiBase}/${resourcePid}/versions/${versionPid}`);
    },

    /**
     * Rollback to a specific version
     */
    async rollback(resourcePid: string, versionPid: string): Promise<VersionEntry> {
      return request(`${apiBase}/${resourcePid}/versions/${versionPid}/rollback`, {
        method: 'post',
      });
    },

    /**
     * Get version count
     */
    async getCount(resourcePid: string): Promise<number> {
      const result = await request<{ count: number }>(`${apiBase}/${resourcePid}/versions/count`);
      return result.count;
    },
  };
}

/**
 * Pre-configured version service for dashboards
 */
export const dashboardVersionService = createVersionService('/api/dashboards');

/**
 * Pre-configured version service for page schemas (pages + reports)
 */
export const pageSchemaVersionService = createVersionService('/api/pages');
