/**
 * Approval Task Service
 * API service for approval task operations in APPROVAL-mode command chains.
 */

import { get, post } from '~/shared/services/http-client';

export interface ApprovalTaskDTO {
  pid: string;
  taskTitle: string;
  taskDescription?: string;
  priority: 'low' | 'normal' | 'high' | 'urgent';
  status: 'pending' | 'approved' | 'rejected' | 'expired' | 'cancelled';
  assigneeStrategy: 'any' | 'all';
  assigneeUserIds: number[];
  actualApproverId?: number;
  processKey: string;
  businessKey?: string;
  chainExecutionId: string;
  formRef?: string;
  approvalComment?: string;
  signature?: string;
  attachments?: Array<{ fileId: string; fileName: string; fileSize?: number; url?: string }>;
  approverName?: string;
  deadlineAt?: string;
  completedAt?: string;
  createdAt: string;
  formSnapshot?: Record<string, unknown>;
  approvalData?: Record<string, unknown>;
}

export interface ApprovalActionRequest {
  comment?: string;
  formData?: Record<string, unknown>;
  /** Base64-encoded PNG signature image */
  signature?: string;
  /** Attachment file references */
  attachments?: Array<{ fileId: string; fileName: string; fileSize?: number; url?: string }>;
}

export interface ReassignRequest {
  assigneeUserIds: number[];
}

export async function getTaskDetail(taskPid: string) {
  return get<ApprovalTaskDTO>(`/api/bpm/approval-tasks/${taskPid}`);
}

export async function getPendingCount() {
  return get<{ pending: number }>('/api/bpm/approval-tasks/count');
}

export async function approveTask(taskPid: string, request: ApprovalActionRequest) {
  return post<unknown>(`/api/bpm/approval-tasks/${taskPid}/approve`, request);
}

export async function rejectTask(taskPid: string, request: ApprovalActionRequest) {
  return post<unknown>(`/api/bpm/approval-tasks/${taskPid}/reject`, request);
}

export async function reassignTask(taskPid: string, request: ReassignRequest) {
  return post<void>(`/api/bpm/approval-tasks/${taskPid}/reassign`, request);
}

export interface CcRequest {
  ccUserIds: number[];
  comment?: string;
}

/**
 * Carbon copy an approval task to additional users.
 * Creates read-only informational notifications for each recipient.
 */
export async function carbonCopyTask(taskPid: string, request: CcRequest) {
  return post<void>(`/api/bpm/approval-tasks/${taskPid}/cc`, request);
}

/**
 * Get approval comments for a business record (completed tasks only).
 */
export async function getApprovalComments(businessKey: string) {
  return get<ApprovalTaskDTO[]>(`/api/bpm/approval-tasks/comments/${businessKey}`);
}

/**
 * Get full approval trail for a business record (all tasks including pending).
 */
export async function getApprovalTrail(businessKey: string) {
  return get<ApprovalTaskDTO[]>(`/api/bpm/approval-tasks/trail/${businessKey}`);
}
