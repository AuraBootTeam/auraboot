/**
 * Dashboard module (folder tree) service — talks to /api/dashboard-modules.
 */

export interface DashboardModuleNode {
  pid: string;
  name: string;
  parentPid?: string | null;
  sortOrder?: number;
  dashboardCount?: number;
  children?: DashboardModuleNode[];
}

function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = fetch(`/api/dashboard-modules${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
  }).then(async (response) => {
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.desc || error.message || `Request failed: ${response.status}`);
    }
    const result = await response.json();
    const code = typeof result.code === 'string' ? parseInt(result.code, 10) : result.code;
    if (code !== 0 && code !== 200) {
      throw new Error(result.desc || result.message || 'Request failed');
    }
    return result.data as T;
  });
  return response;
}

export const dashboardModuleService = {
  async tree(): Promise<DashboardModuleNode[]> {
    return request<DashboardModuleNode[]>('/tree');
  },

  async moduleCounts(): Promise<DashboardModuleNode[]> {
    return request<DashboardModuleNode[]>('/module-count');
  },

  async create(name: string, parentPid?: string | null): Promise<DashboardModuleNode> {
    return request<DashboardModuleNode>('', {
      method: 'POST',
      body: JSON.stringify({ name, parentPid: parentPid || null }),
    });
  },

  async rename(pid: string, name: string): Promise<DashboardModuleNode> {
    return request<DashboardModuleNode>(`/${pid}/rename`, {
      method: 'PUT',
      body: JSON.stringify({ name }),
    });
  },

  async remove(pid: string): Promise<void> {
    await request<null>(`/${pid}`, { method: 'DELETE' });
  },

  async move(pid: string, targetParentPid?: string | null): Promise<DashboardModuleNode> {
    return request<DashboardModuleNode>(`/${pid}/move`, {
      method: 'POST',
      body: JSON.stringify({ targetParentPid: targetParentPid || null }),
    });
  },
};
