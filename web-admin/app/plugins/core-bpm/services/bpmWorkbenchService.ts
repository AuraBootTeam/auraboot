/**
 * BPM Workbench Service
 * API service for BPM workbench operations
 */

import { get, post, ErrorCodes } from '~/shared/services/http-client';

// ==================== Types ====================

export interface TaskInstance {
  instanceId: string;
  taskId: string;
  processInstanceId: string;
  processDefinitionKey: string;
  taskDefinitionKey: string;
  taskName: string;
  assignee: string;
  claimUserId: string;
  createTime: string;
  dueDate?: string;
  priority: number;
  description?: string;
  variables?: Record<string, unknown>;
  // Business data
  title?: string;
  businessKey?: string;
}

export interface ProcessInstance {
  instanceId: string;
  processDefinitionId: string;
  processDefinitionKey: string;
  businessKey: string;
  startUserId: string;
  startTime: string;
  endTime?: string;
  status: string;
  title?: string;
  variables?: Record<string, unknown>;
}

export interface WorkbenchData {
  todoTasks: TaskInstance[];
  completedTasks: TaskInstance[];
  startedProcesses: ProcessInstance[];
  todoCount: number;
  completedCount: number;
  startedCount: number;
}

export interface ProcessInstanceDetail {
  processInstance: ProcessInstance;
  variables: Array<{ name: string; value: unknown }>;
  tasks: TaskInstance[];
}

export interface StartProcessRequest {
  processDefinitionKey: string;
  businessKey: string;
  title: string;
  businessData: Record<string, unknown>;
}

export interface CompleteTaskRequest {
  taskId: string;
  variables?: Record<string, unknown>;
  comment?: string;
}

export interface BatchProcessRequest {
  taskIds: string[];
  action: 'approve' | 'reject' | 'complete';
  comment?: string;
  variables?: Record<string, unknown>;
}

// ==================== Helper Functions ====================

function isSuccess(code: string): boolean {
  return code === ErrorCodes.SUCCESS;
}

/**
 * Map SmartEngine native TaskInstance fields to frontend interface.
 * SmartEngine returns: instanceId, startTime, processDefinitionActivityId, processDefinitionIdAndVersion
 * Frontend expects: taskId, createTime, taskDefinitionKey, processDefinitionKey, taskName
 */
function mapTaskInstance(raw: Record<string, unknown>): TaskInstance {
  const r = raw as Record<string, string | number | null | undefined>;
  const procDefIdAndVersion = (r.processDefinitionIdAndVersion as string) || '';
  // Extract process definition key by stripping the version suffix (e.g., "key:1.0.0" → "key")
  const processDefinitionKey = procDefIdAndVersion.includes(':')
    ? procDefIdAndVersion.substring(0, procDefIdAndVersion.lastIndexOf(':'))
    : procDefIdAndVersion;

  return {
    instanceId: (r.instanceId as string) || '',
    taskId: (r.taskId as string) || (r.instanceId as string) || '',
    processInstanceId: (r.processInstanceId as string) || '',
    processDefinitionKey: (r.processDefinitionKey as string) || processDefinitionKey,
    taskDefinitionKey:
      (r.taskDefinitionKey as string) || (r.processDefinitionActivityId as string) || '',
    taskName:
      (r.taskName as string) ||
      (r.title as string) ||
      (r.processDefinitionActivityId as string) ||
      '',
    assignee: (r.assignee as string) || (r.claimUserId as string) || '',
    claimUserId: (r.claimUserId as string) || '',
    createTime: (r.createTime as string) || (r.startTime as string) || '',
    dueDate: (r.dueDate as string) || undefined,
    priority: typeof r.priority === 'number' ? r.priority : 0,
    description: (r.description as string) || undefined,
    variables: (raw.variables as Record<string, unknown>) || undefined,
    title: (r.title as string) || undefined,
    businessKey: (r.businessKey as string) || undefined,
  };
}

function mapWorkbenchData(raw: Record<string, unknown>): WorkbenchData {
  const d = raw as Record<string, unknown>;
  const todoTasks = Array.isArray(d.todoTasks)
    ? d.todoTasks.map((t: Record<string, unknown>) => mapTaskInstance(t))
    : [];
  const completedTasks = Array.isArray(d.completedTasks)
    ? d.completedTasks.map((t: Record<string, unknown>) => mapTaskInstance(t))
    : [];
  return {
    todoTasks,
    completedTasks,
    startedProcesses: (d.startedProcesses as ProcessInstance[]) || [],
    todoCount: (d.todoCount as number) || todoTasks.length,
    completedCount: (d.completedCount as number) || completedTasks.length,
    startedCount: (d.startedCount as number) || 0,
  };
}

// ==================== API Functions ====================

/**
 * Get workbench data for current user
 */
export async function getWorkbench(userId?: string): Promise<WorkbenchData> {
  const params = userId ? { userId } : {};
  const result = await get<Record<string, unknown>>('/api/bpm/workbench', { params });
  if (!isSuccess(result.code) || !result.data) {
    throw new Error(result.desc || 'Failed to get workbench data');
  }
  return mapWorkbenchData(result.data);
}

/**
 * Get todo tasks for current user
 */
export async function getTodoTasks(userId?: string): Promise<TaskInstance[]> {
  const params = userId ? { userId } : {};
  const result = await get<Record<string, unknown>[]>('/api/bpm/tasks/todo', { params });
  if (!isSuccess(result.code) || !result.data) {
    throw new Error(result.desc || 'Failed to get todo tasks');
  }
  return (result.data || []).map(mapTaskInstance);
}

/**
 * Get completed tasks for current user
 */
export async function getCompletedTasks(userId?: string): Promise<TaskInstance[]> {
  const params = userId ? { userId } : {};
  const result = await get<Record<string, unknown>[]>('/api/bpm/tasks/completed', { params });
  if (!isSuccess(result.code) || !result.data) {
    throw new Error(result.desc || 'Failed to get completed tasks');
  }
  return (result.data || []).map(mapTaskInstance);
}

/**
 * Get task detail
 */
export async function getTaskDetail(taskId: string): Promise<TaskInstance> {
  const result = await get<Record<string, unknown>>(`/api/bpm/tasks/${taskId}`);
  if (!isSuccess(result.code) || !result.data) {
    throw new Error(result.desc || 'Failed to get task detail');
  }
  return mapTaskInstance(result.data);
}

/**
 * Complete a task
 */
export async function completeTask(request: CompleteTaskRequest): Promise<void> {
  const result = await post(`/api/bpm/tasks/${request.taskId}/complete`, {
    variables: request.variables,
    comment: request.comment,
  });
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to complete task');
  }
}

/**
 * Claim a task
 */
export async function claimTask(taskId: string): Promise<void> {
  const result = await post(`/api/bpm/tasks/${taskId}/claim`);
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to claim task');
  }
}

/**
 * Delegate a task
 */
export async function delegateTask(
  taskId: string,
  userId: string,
  comment?: string,
): Promise<void> {
  const result = await post(`/api/bpm/tasks/${taskId}/delegate`, { userId, comment });
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to delegate task');
  }
}

/**
 * Transfer a task
 */
export async function transferTask(
  taskId: string,
  userId: string,
  comment?: string,
): Promise<void> {
  const result = await post(`/api/bpm/tasks/${taskId}/transfer`, { userId, comment });
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to transfer task');
  }
}

/**
 * Start a business process
 */
export async function startProcess(request: StartProcessRequest): Promise<string> {
  const result = await post<string>('/api/bpm/workbench/start-process', request);
  if (!isSuccess(result.code) || !result.data) {
    throw new Error(result.desc || 'Failed to start process');
  }
  return result.data;
}

// ==================== Action-driven Process Start ====================

/**
 * Request shape for DSL ActionDef(type=bpm) → POST /api/bpm/process-instances
 *
 * The backend `StartProcessRequest.processDefinitionId` field accepts the BPMN
 * process definition key (i.e. <process id="..."> in the .bpmn file), so the
 * frontend-facing contract uses `processDefinitionKey` for clarity and maps it
 * onto the backend field name in the request body.
 */
export interface StartProcessFromActionRequest {
  processDefinitionKey: string;
  businessKey: string;
  variables?: Record<string, unknown>;
}

/**
 * Response from starting a process via ActionDef(type=bpm).
 *
 * `processInstanceId` is the freshly-started (or existing, if the backend
 * decides to dedupe in the future) instance id. `deduped` is optional: when
 * the backend reports that an existing running instance was reused for the
 * (processKey, businessKey), UI can surface a distinct toast message.
 * Current backend controller does not emit `deduped`; this field exists to
 * allow forward-compatible handling once the controller supports it.
 */
export interface StartProcessFromActionResponse {
  processInstanceId: string;
  deduped?: boolean;
}

/**
 * Start a BPM process instance from a DSL ActionDef(type=bpm).
 *
 * Posts to the canonical `/api/bpm/process-instances` endpoint. Does not
 * perform multi-path response fallback: the backend returns ApiResponse<T>
 * with `data.instanceId`; anything else is an error.
 */
export async function startProcessFromAction(
  request: StartProcessFromActionRequest,
): Promise<StartProcessFromActionResponse> {
  const body = {
    // Backend `StartProcessRequest.processDefinitionId` accepts the process key.
    processDefinitionId: request.processDefinitionKey,
    businessKey: request.businessKey,
    variables: request.variables,
  };
  const result = await post<Record<string, unknown>>('/api/bpm/process-instances', body);
  if (!isSuccess(result.code) || !result.data) {
    throw new Error(
      result.desc ||
        `BPM start failed: empty response for processDefinitionKey=${request.processDefinitionKey}`,
    );
  }
  const data = result.data;
  const processInstanceId = data.instanceId as string | undefined;
  if (!processInstanceId) {
    throw new Error(
      `BPM start failed: response missing instanceId for processDefinitionKey=${request.processDefinitionKey}`,
    );
  }
  return {
    processInstanceId,
    deduped: typeof data.deduped === 'boolean' ? (data.deduped as boolean) : undefined,
  };
}

/**
 * Batch process tasks
 */
export async function batchProcessTasks(request: BatchProcessRequest): Promise<void> {
  const result = await post('/api/bpm/workbench/batch-process-tasks', request);
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to batch process tasks');
  }
}

/**
 * Get process instance detail
 */
export async function getProcessDetail(processInstanceId: string): Promise<ProcessInstanceDetail> {
  const result = await get<ProcessInstanceDetail>(
    `/api/bpm/workbench/process-detail/${processInstanceId}`,
  );
  if (!isSuccess(result.code) || !result.data) {
    throw new Error(result.desc || 'Failed to get process detail');
  }
  return result.data;
}

/**
 * Get started processes by current user
 */
export async function getStartedProcesses(userId?: string): Promise<ProcessInstance[]> {
  const params = userId ? { userId } : {};
  const result = await get<ProcessInstance[]>('/api/bpm/process-instances', { params });
  if (!isSuccess(result.code) || !result.data) {
    throw new Error(result.desc || 'Failed to get started processes');
  }
  return result.data || [];
}

/**
 * Suspend a process instance
 */
export async function suspendProcess(processInstanceId: string): Promise<void> {
  const result = await post(`/api/bpm/process-instances/${processInstanceId}/suspend`);
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to suspend process');
  }
}

/**
 * Resume a process instance
 */
export async function resumeProcess(processInstanceId: string): Promise<void> {
  const result = await post(`/api/bpm/process-instances/${processInstanceId}/resume`);
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to resume process');
  }
}

/**
 * Terminate a process instance
 */
export async function terminateProcess(processInstanceId: string, reason?: string): Promise<void> {
  const result = await post(`/api/bpm/process-instances/${processInstanceId}/terminate`, {
    reason,
  });
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to terminate process');
  }
}

// ==================== Approval Operations ====================

/**
 * Approve a task
 */
export async function approveTask(
  taskId: string,
  comment?: string,
  variables?: Record<string, unknown>,
): Promise<void> {
  const result = await post(`/api/bpm/tasks/${taskId}/approve`, { comment, variables });
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to approve task');
  }
}

/**
 * Reject a task
 */
export async function rejectTask(
  taskId: string,
  comment?: string,
  variables?: Record<string, unknown>,
): Promise<void> {
  const result = await post(`/api/bpm/tasks/${taskId}/reject`, { comment, variables });
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to reject task');
  }
}

/**
 * Rollback a task to a target activity node
 */
export async function rollbackTask(
  taskId: string,
  targetActivityId: string,
  reason?: string,
): Promise<void> {
  const result = await post(`/api/bpm/tasks/${taskId}/rollback`, { targetActivityId, reason });
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to rollback task');
  }
}

/**
 * Add a sign (additional assignee) to a task
 */
export async function addSign(taskId: string, userId: string, reason?: string): Promise<void> {
  const result = await post(`/api/bpm/tasks/${taskId}/add-sign`, { userId, reason });
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to add sign');
  }
}

/**
 * Remove a sign (assignee candidate) from a task
 */
export async function removeSign(taskId: string, userId: string, reason?: string): Promise<void> {
  const result = await post(`/api/bpm/tasks/${taskId}/remove-sign`, { userId, reason });
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to remove sign');
  }
}

/**
 * Get rollback target nodes for a process instance (completed activities)
 */
export interface RollbackTarget {
  nodeId: string;
  type: string;
  name: string | null;
  completedAt: string | null;
}

export async function getRollbackTargets(processInstanceId: string): Promise<RollbackTarget[]> {
  const result = await get<{
    completedNodes: RollbackTarget[];
  }>(`/api/bpm/process-instances/${processInstanceId}/status`);
  if (!isSuccess(result.code) || !result.data) {
    throw new Error(result.desc || 'Failed to get rollback targets');
  }
  return (result.data?.completedNodes || []).filter(
    (n) => n.nodeId && !n.nodeId.startsWith('start') && !n.nodeId.startsWith('end'),
  );
}

/**
 * Get started processes by current user (via task controller)
 */
export async function getMyStartedProcesses(): Promise<ProcessInstance[]> {
  const result = await get<ProcessInstance[]>('/api/bpm/tasks/started');
  if (!isSuccess(result.code) || !result.data) {
    throw new Error(result.desc || 'Failed to get started processes');
  }
  return result.data || [];
}

// ==================== Detail-page BPM panel APIs ====================
//
// The following helpers support the `bpm-panel` detail block (see
// BpmPanelBlock.tsx). Each wraps a canonical backend endpoint:
//
//   - getInstanceForRecord:   GET /api/bpm/process-instances/by-business-key/status
//     → backed by ProcessInstanceController#getProcessInstanceStatusByBusinessKey,
//       returns ProcessInstanceStatusDTO (instanceId, processDefinitionId, status,
//       currentNodes, completedNodes, variables).
//
//   - listAuditEvents:        GET /api/bpm/monitor/instances/{processInstanceId}/audit
//     → backed by BpmMonitorController#getAuditTrail, returns List<BpmAuditRecordEntity>.
//
// Diagram rendering uses the existing `getProcessDefinitionByKey` helper
// from `core-designer/services/bpmnService`; no separate BPMN-XML wrapper
// lives here. The backend `GET /api/bpm/process-definitions/{pid}/bpmn`
// endpoint is still available for plugins that want raw BPMN XML.
//
// These helpers do NOT perform multi-path response fallback. Any deviation from
// the expected envelope surfaces as an error.

/**
 * Status of a single BPMN node in a process instance, as returned by backend
 * `NodeStatusDTO`. Field names mirror backend record verbatim.
 */
export interface BpmNodeStatus {
  nodeId: string;
  type: string;
  name: string | null;
  status: string;
  assignee: string | null;
  completedAt: string | null;
  completedBy: string | null;
}

/**
 * Per-business-key process instance state, as returned by backend
 * `ProcessInstanceStatusDTO`. Field names mirror backend record verbatim.
 *
 * The wrapper endpoint returns 4xx when no process instance exists for the
 * given (businessKey, optional processKey) pair; `getInstanceForRecord`
 * catches that case and resolves to `null` so callers can render an empty
 * state instead of treating missing-instance as a bug.
 */
export interface BpmInstanceForRecord {
  instanceId: string;
  processDefinitionId: string;
  status: string;
  currentNodes: BpmNodeStatus[];
  completedNodes: BpmNodeStatus[];
  variables: Record<string, unknown>;
}

/**
 * Audit event for a process instance, as returned by backend
 * `BpmAuditRecordEntity`. Field names mirror backend entity verbatim so the
 * frontend can render operation/timestamp/user details without further
 * transformation.
 */
export interface BpmAuditEvent {
  id: number;
  pid: string;
  userId: string | null;
  operation: string;
  processInstanceId: string | null;
  taskId: string | null;
  processDefinitionKey: string | null;
  version: number | null;
  details: Record<string, unknown> | null;
  ipAddress: string | null;
  result: string | null;
  errorMessage: string | null;
  createdAt: string | null;
}

/**
 * Fetch the process instance status bound to `businessKey` (optionally
 * filtered by `processKey`). Returns `null` when no instance exists.
 *
 * Uses ErrorCodes.SUCCESS to distinguish "no data" from a real failure:
 * - code === SUCCESS + data present → instance
 * - code === SUCCESS + data absent → null (shouldn't happen; backend returns 4xx)
 * - code !== SUCCESS → error thrown for caller to surface
 *
 * The backend may respond with `BadParam` when the instance does not exist;
 * the wrapper translates that into `null` so the UI can render an empty
 * state. All other non-success codes propagate as errors.
 */
export async function getInstanceForRecord(
  businessKey: string,
  processKey?: string,
): Promise<BpmInstanceForRecord | null> {
  const params: Record<string, string> = { businessKey };
  if (processKey) {
    params.processKey = processKey;
  }
  const result = await get<BpmInstanceForRecord>(
    '/api/bpm/process-instances/by-business-key/status',
    { params },
  );
  if (isSuccess(result.code)) {
    return result.data ?? null;
  }
  // Backend throws BadParam when instance is absent; translate to null instead
  // of an error so the UI can render an empty state.
  const desc = result.desc || '';
  if (desc.includes('not found')) {
    return null;
  }
  throw new Error(desc || 'Failed to get process instance for record');
}

/**
 * Fetch audit events for a process instance. Ordered by backend insertion
 * order (typically ascending createdAt). Empty array is a valid result.
 */
export async function listAuditEvents(processInstanceId: string): Promise<BpmAuditEvent[]> {
  const result = await get<BpmAuditEvent[]>(
    `/api/bpm/monitor/instances/${processInstanceId}/audit`,
  );
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to list audit events');
  }
  return result.data || [];
}
