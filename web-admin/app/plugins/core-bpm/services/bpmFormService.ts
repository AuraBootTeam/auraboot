/**
 * BPM Form Service - API calls for form binding and rendering.
 *
 * This module mirrors the backend `TaskFormResponse` DTO exactly. A task can
 * have at most ONE form binding (or none), so the response shape is:
 *
 *   { taskId, taskName?, processName?, processInstanceId, nodeId,
 *     businessKey?, processVariables?, formBinding: object | null,
 *     taskActions: TaskActionDef[] | null }
 *
 * Historical note: an earlier iteration of this file modeled the response as
 * `{ taskId, hasForm, forms: [...] }` assuming multi-form attachments per
 * task. That shape was never produced by the backend — it caused the
 * TaskDetailDrawer FormTab to permanently render the "no form bound"
 * fallback even when a formBinding was correctly persisted on
 * ab_bpm_process_definition.form_bindings. The shape below is the one the
 * controller actually returns.
 */

import { get, post, ErrorCodes } from '~/shared/services/http-client';

const API_BASE = '/api/bpm/forms';

// ==================== Types ====================

/** Permission level for a single form field. Mirrors backend FieldPermission. */
export type BpmFieldPermission = 'editable' | 'readonly' | 'hidden';

/**
 * Single form binding for one BPMN user task node. Mirrors backend
 * `FormBindingConfig` (only fields the UI consumes are typed). Maps 1:1 to
 * a Page DSL form referenced by `formRef`.
 */
export interface BpmFormBinding {
  /** Page config id or pageCode that backs this task's form */
  formRef: string;
  /** Form type discriminator. Only PAGE / PAGE_DSL are supported today. */
  formType: string;
  /** Optional pinned page version */
  version?: string;
  /** Per-field permission overrides keyed by field code */
  fieldPermissions?: Record<string, BpmFieldPermission>;
  /** Persistence strategy: business_only | dual_write | variable_only */
  saveStrategy?: string;
  /** "merge" tightens schema perms; "override" replaces them */
  permissionMode?: string;
  /** processVariableName -> formFieldCode mapping for dual-write paths */
  variableBindings?: Record<string, string>;
  /** BPMN nodeId this binding applies to */
  nodeId?: string;
  /** "FIXED" or "LATEST" version resolution strategy */
  versionStrategy?: string;
  /** Reserved built-in variable mapping (decision, comment, ...) */
  builtinVariables?: Record<string, string>;
}

/**
 * Designer-authored task action ({@code data.taskActions[*]}). Used by the UI
 * to forward resultVariable/resultValue as process variables on
 * approve/reject so downstream gateway MVEL conditions resolve.
 */
export interface BpmTaskActionDef {
  key: string;
  type?: string;
  resultVariable?: string;
  resultValue?: string;
  requireComment?: boolean;
}

/**
 * Shape returned by GET /api/bpm/forms/task/{taskId}. Mirrors backend
 * {@link com.auraboot.framework.bpm.dto.TaskFormResponse} exactly — adding or
 * renaming fields here without a paired backend change will surface as a
 * silent UI regression (see header comment).
 */
export interface TaskFormData {
  taskId: string;
  taskName?: string;
  processName?: string;
  processInstanceId?: string;
  nodeId?: string;
  businessKey?: string;
  processVariables?: Record<string, unknown>;
  /** null when the node has no form binding configured */
  formBinding: BpmFormBinding | null;
  /** null for processes authored via pure BPMN XML (no designerJson taskActions) */
  taskActions?: BpmTaskActionDef[] | null;
}

// ==================== Helper Functions ====================

function isSuccess(code: string): boolean {
  return code === ErrorCodes.SUCCESS;
}

// ==================== API Functions ====================

/**
 * Get form data for a task.
 *
 * Returns the full TaskFormResponse from the backend. Callers should check
 * `data.formBinding != null` before attempting to render a DSL form — when
 * null the task simply has no form attached and the UI should render an
 * empty-state hint (not silently no-op).
 */
export async function getTaskForm(taskId: string): Promise<TaskFormData> {
  const result = await get<TaskFormData>(`${API_BASE}/task/${taskId}`);
  if (!isSuccess(result.code) || !result.data) {
    throw new Error(result.desc || 'Failed to get task form');
  }
  return result.data;
}

/**
 * Submit form data for a task.
 */
export async function submitTaskForm(taskId: string, data: Record<string, any>): Promise<void> {
  const result = await post(`${API_BASE}/task/${taskId}/submit`, data);
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to submit task form');
  }
}

/**
 * Get available forms for binding.
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
