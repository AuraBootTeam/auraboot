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

/**
 * Pre-configured version service for BPMN process definitions.
 *
 * Adapts the BPMN-specific API (returns ProcessDefinitionDTO[]) to the
 * shared VersionEntry interface expected by useVersioning + VersionHistoryPanel.
 *
 * API: GET /api/bpm/process-definitions/key/{processKey}/versions
 */
export const bpmnVersionService = (() => {
  const apiBase = '/api/bpm/process-definitions';

  interface BpmnVersionDTO {
    pid: string;
    processKey: string;
    processName: string;
    description: string | null;
    status: string;
    version: number | null;
    isCurrent: boolean | null;
    designerJson: string | null;
    createdAt: string | null;
    updatedAt: string | null;
  }

  function mapStatusToOperation(status: string | null): string {
    switch (status?.toLowerCase()) {
      case 'deployed':
        return 'PUBLISH';
      case 'suspended':
        return 'ARCHIVE';
      default:
        return 'UPDATE';
    }
  }

  function toVersionEntry(dto: BpmnVersionDTO): VersionEntry {
    let schemaSnapshot: Record<string, unknown> | undefined;
    if (dto.designerJson) {
      try {
        schemaSnapshot = JSON.parse(dto.designerJson);
      } catch {
        // Ignore malformed designerJson
      }
    }

    return {
      pid: dto.pid,
      resourceType: 'bpm_process_definition',
      resourceId: dto.processKey,
      version: String(dto.version ?? 1),
      operation: mapStatusToOperation(dto.status),
      operationBy: '',
      operationAt: dto.updatedAt || dto.createdAt || '',
      description: dto.description || undefined,
      schemaSnapshot,
    };
  }

  return {
    async getHistory(processKey: string): Promise<VersionEntry[]> {
      const dtos = await request<BpmnVersionDTO[]>(`${apiBase}/key/${processKey}/versions`);
      return dtos.map(toVersionEntry);
    },

    async getVersion(processKey: string, versionPid: string): Promise<VersionEntry> {
      // Fetch full definition by pid to get the designerJson snapshot
      const dto = await request<BpmnVersionDTO>(`${apiBase}/${versionPid}`);
      return toVersionEntry(dto);
    },

    async rollback(_processKey: string, _versionPid: string): Promise<VersionEntry> {
      // BPMN does not currently support rollback — throw a clear error
      throw new Error('Rollback is not supported for BPMN process definitions');
    },
  };
})();
