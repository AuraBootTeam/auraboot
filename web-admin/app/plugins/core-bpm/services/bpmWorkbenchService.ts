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
