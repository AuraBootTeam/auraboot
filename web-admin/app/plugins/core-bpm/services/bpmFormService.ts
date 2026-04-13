/**
 * BPM Form Service - API calls for form binding and rendering
 */

import { get, post, ErrorCodes } from '~/services/http-client';

const API_BASE = '/api/bpm/forms';

// ==================== Types ====================

export interface TaskFormData {
  taskId: string;
  hasForm: boolean;
  forms?: Array<{
    formRef: string;
    formType: string;
    version?: string;
    initialValues: Record<string, any>;
    fieldPermissions: Record<string, string>;
  }>;
}

// ==================== Helper Functions ====================

function isSuccess(code: string): boolean {
  return code === ErrorCodes.SUCCESS;
}

// ==================== API Functions ====================

/**
 * Get form data for a task
 */
export async function getTaskForm(taskId: string): Promise<TaskFormData> {
  const result = await get<TaskFormData>(`${API_BASE}/task/${taskId}`);
  if (!isSuccess(result.code) || !result.data) {
    throw new Error(result.desc || 'Failed to get task form');
  }
  return result.data;
}

/**
 * Submit form data for a task
 */
export async function submitTaskForm(taskId: string, data: Record<string, any>): Promise<void> {
  const result = await post(`${API_BASE}/task/${taskId}/submit`, data);
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to submit task form');
  }
}

/**
 * Get available forms for binding
 */
export async function getAvailableForms(): Promise<
  Array<{ id: string; name: string; code: string }>
> {
  const result = await get<Array<{ id: string; name: string; code: string }>>(
    `${API_BASE}/available`,
  );
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to get available forms');
  }
  return result.data || [];
}
