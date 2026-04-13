/**
 * BPM Notification Service - CC and URGE operations
 */

import { get, post, put, ErrorCodes } from '~/services/http-client';

const API_BASE = '/api/bpm/notify';

// ==================== Types ====================

export interface NotifyRecord {
  pid: string;
  processInstanceId: string;
  taskId: string;
  notifyType: string;
  senderUserId: number;
  recipientUserId: number;
  content: string;
  isRead: boolean;
  readAt: string | null;
  createdAt: string;
}

export interface SendCarbonCopyRequest {
  taskId: string;
  processInstanceId: string;
  senderUserId: number;
  recipientUserIds: number[];
  content?: string;
}

export interface SendUrgeRequest {
  taskId: string;
  processInstanceId: string;
  senderUserId: number;
  assigneeUserId: number;
  content?: string;
}

// ==================== Helper Functions ====================

function isSuccess(code: string): boolean {
  return code === ErrorCodes.SUCCESS;
}

// ==================== API Functions ====================

/**
 * Send carbon copy notification
 */
export async function sendCarbonCopy(params: SendCarbonCopyRequest): Promise<void> {
  const result = await post(`${API_BASE}/cc`, params);
  if (!isSuccess(result.code)) {
    throw new Error(result.message || 'Failed to send carbon copy');
  }
}

/**
 * Send urge (reminder) notification
 */
export async function sendUrge(params: SendUrgeRequest): Promise<void> {
  const result = await post(`${API_BASE}/urge`, params);
  if (!isSuccess(result.code)) {
    throw new Error(result.message || 'Failed to send urge');
  }
}

/**
 * Get received notifications for a user.
 */
export async function getReceivedNotifications(
  userId: number,
  type: 'CC' | 'urge' = 'CC',
): Promise<NotifyRecord[]> {
  const result = await get<NotifyRecord[]>(`${API_BASE}/received`, {
    userId: String(userId),
    type,
  });
  if (!isSuccess(result.code)) {
    throw new Error(result.message || 'Failed to get notifications');
  }
  return result.data || [];
}

/**
 * Mark a notification as read
 */
export async function markAsRead(pid: string): Promise<void> {
  const result = await put(`${API_BASE}/${pid}/read`);
  if (!isSuccess(result.code)) {
    throw new Error(result.message || 'Failed to mark as read');
  }
}
