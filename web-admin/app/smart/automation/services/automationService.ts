/**
 * Automation Service
 *
 * API service for managing automations.
 * Provides CRUD operations, enable/disable, logs query, and manual trigger.
 */

import { get, post, put, del, ErrorCodes } from '~/services/http-client';
import type { FlowData } from '~/flow-designer-sdk';

// ==================== Types ====================

/**
 * Automation entity type
 */
export interface AutomationAction {
  type: string;
  label?: string;
  config?: Record<string, unknown>;
}

export interface Automation {
  pid: string;
  name: string;
  description?: string;
  enabled: boolean;
  triggerType: string;
  triggerConfig: Record<string, unknown>;
  /** Model code associated with the trigger (for record-based triggers) */
  modelCode?: string;
  /** Flat action list used when flowConfig is absent */
  actions?: AutomationAction[];
  flowConfig?: FlowData;
  lastRunAt?: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * Single action execution result within a log
 */
export interface ActionResult {
  sequence: number;
  actionType: string;
  status: 'success' | 'failed';
  result?: unknown;
  errorMessage?: string;
  durationMs?: number;
}

/**
 * Automation execution log entry
 */
export interface AutomationLog {
  pid: string;
  automationId: string;
  automationName?: string;
  triggerType?: string;
  triggerRecordId?: string;
  triggerPayload?: Record<string, unknown>;
  status: 'pending' | 'running' | 'success' | 'failed' | 'skipped';
  startedAt?: string;
  completedAt?: string;
  durationMs?: number;
  errorMessage?: string;
  actionResults?: ActionResult[];
  createdAt?: string;
}

/**
 * List query parameters
 */
export interface ListAutomationsParams {
  page?: number;
  pageSize?: number;
  enabled?: boolean;
  triggerType?: string;
  search?: string;
}

/**
 * Create automation request
 */
export interface AutomationCreateRequest {
  name: string;
  description?: string;
  triggerType?: string;
  triggerConfig?: Record<string, unknown>;
  flowConfig?: FlowData;
  enabled?: boolean;
}

/**
 * Update automation request
 */
export interface AutomationUpdateRequest {
  name?: string;
  description?: string;
  triggerType?: string;
  triggerConfig?: Record<string, unknown>;
  flowConfig?: FlowData;
  enabled?: boolean;
}

/**
 * Logs query parameters
 */
export interface AutomationLogsParams {
  page?: number;
  pageSize?: number;
  status?: 'success' | 'failed' | 'running';
}

// ==================== Constants ====================

const BASE_URL = '/api/automations';

// ==================== Helper Functions ====================

function isSuccess(code: string): boolean {
  return code === ErrorCodes.SUCCESS;
}

function handleResponse<T>(
  result: { code: string; desc: string; data: T | null },
  errorMsg: string,
): T {
  if (isSuccess(result.code) && result.data !== null) {
    return result.data;
  }
  throw new Error(result.desc || errorMsg);
}

// ==================== Service Class ====================

/**
 * AutomationService
 *
 * Encapsulates all Automation-related API calls
 */
export class AutomationService {
  /**
   * List all automations with optional filters
   */
  async list(params?: ListAutomationsParams, request?: Request): Promise<Automation[]> {
    const result = await get<{ records: Automation[] }>(BASE_URL, params, undefined, request);
    const page = handleResponse(result, 'Failed to fetch automations');
    return page.records || [];
  }

  /**
   * Get a single automation by PID
   */
  async get(pid: string, request?: Request): Promise<Automation> {
    const result = await get<Automation>(`${BASE_URL}/${pid}`, undefined, undefined, request);
    return handleResponse(result, 'Failed to fetch automation');
  }

  /**
   * Create a new automation
   */
  async create(data: AutomationCreateRequest, request?: Request): Promise<Automation> {
    const result = await post<Automation>(BASE_URL, data, undefined, request);
    return handleResponse(result, 'Failed to create automation');
  }

  /**
   * Update an existing automation
   */
  async update(pid: string, data: AutomationUpdateRequest, request?: Request): Promise<Automation> {
    const result = await put<Automation>(`${BASE_URL}/${pid}`, data, undefined, request);
    return handleResponse(result, 'Failed to update automation');
  }

  /**
   * Delete an automation
   */
  async delete(pid: string, request?: Request): Promise<void> {
    const result = await del<void>(`${BASE_URL}/${pid}`, undefined, undefined, request);
    if (!isSuccess(result.code)) {
      throw new Error(result.desc || 'Failed to delete automation');
    }
  }

  /**
   * Enable an automation
   */
  async enable(pid: string, request?: Request): Promise<Automation> {
    const result = await post<Automation>(
      `${BASE_URL}/${pid}/enable`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to enable automation');
  }

  /**
   * Disable an automation
   */
  async disable(pid: string, request?: Request): Promise<Automation> {
    const result = await post<Automation>(
      `${BASE_URL}/${pid}/disable`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to disable automation');
  }

  /**
   * Toggle automation enabled state
   */
  async toggle(pid: string, request?: Request): Promise<Automation> {
    const result = await post<Automation>(
      `${BASE_URL}/${pid}/toggle`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to toggle automation');
  }

  /**
   * Get automation execution logs
   */
  async getLogs(
    pid: string,
    params?: AutomationLogsParams,
    request?: Request,
  ): Promise<AutomationLog[]> {
    const result = await get<AutomationLog[]>(
      `${BASE_URL}/${pid}/logs`,
      params,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch automation logs');
  }

  /**
   * Get a single execution log detail by log PID
   */
  async getLogDetail(logPid: string, request?: Request): Promise<AutomationLog> {
    const result = await get<AutomationLog>(
      `${BASE_URL}/logs/${logPid}`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch log detail');
  }

  /**
   * Manually trigger an automation execution
   */
  async trigger(
    pid: string,
    context?: Record<string, unknown>,
    request?: Request,
  ): Promise<AutomationLog> {
    const result = await post<AutomationLog>(
      `${BASE_URL}/${pid}/trigger`,
      { context },
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to trigger automation');
  }

  /**
   * Duplicate an existing automation with a new name
   */
  async duplicate(pid: string, newName: string, request?: Request): Promise<Automation> {
    const result = await post<Automation>(
      `${BASE_URL}/${pid}/duplicate`,
      { name: newName },
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to duplicate automation');
  }

  /**
   * Validate automation configuration
   */
  async validate(
    data: AutomationCreateRequest | AutomationUpdateRequest,
    request?: Request,
  ): Promise<{ valid: boolean; errors: string[] }> {
    const result = await post<{ valid: boolean; errors: string[] }>(
      `${BASE_URL}/validate`,
      data,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to validate automation');
  }
}

// Export singleton instance
export const automationService = new AutomationService();
