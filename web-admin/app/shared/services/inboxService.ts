/**
 * Unified Inbox Service — API client for /api/inbox endpoints.
 *
 * Data source: ab_inbox_item table via InboxController.
 * Used by InboxBadge, InboxDropdown, and UnifiedInboxPage.
 *
 * @since 6.4.0
 */

import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

export interface InboxItem {
  id: number;
  tenantId: number;
  userId: number;
  itemType: string; // 'approval' | 'task' | 'mention' | 'ai_suggestion' | 'alert' | 'assignment'
  title: string;
  subtitle?: string;
  priority: string; // 'low' | 'normal' | 'high' | 'urgent'
  status: string; // 'pending' | 'acted' | 'dismissed' | 'expired' | 'closed'
  sourceType?: string; // 'bpm' | 'im' | 'command' | 'ai' | 'notification'
  sourceId?: string;
  modelCode?: string;
  recordId?: number;
  cardPayload?: string; // JSON string
  actionTaken?: string;
  actedAt?: string;
  deepLink?: string;
  isRead: boolean;
  readAt?: string;
  createdAt: string;
  expiresAt?: string;
  clientItemId?: string;
}

export interface InboxPage {
  records: InboxItem[];
  total: number;
  current: number;
  size: number;
  pages: number;
}

export interface UnreadSummary {
  [key: string]: number;
}

const BASE = '/api/inbox';

/**
 * List inbox items with optional filters.
 */
export async function listInboxItems(params: {
  itemType?: string;
  status?: string;
  pageNum?: number;
  pageSize?: number;
}): Promise<InboxPage> {
  const result = await fetchResult<InboxPage>(BASE, {
    method: 'get',
    params,
  });
  if (ResultHelper.isSuccess(result) && result.data) {
    return result.data;
  }
  return { records: [], total: 0, current: 1, size: 20, pages: 0 };
}

/**
 * Get unread counts grouped by item type.
 */
export async function getUnreadSummary(): Promise<UnreadSummary> {
  const result = await fetchResult<UnreadSummary>(`${BASE}/unread-summary`, {
    method: 'get',
  });
  if (ResultHelper.isSuccess(result) && result.data) {
    return result.data;
  }
  return {};
}

/**
 * Get total unread count (for badge).
 */
export async function getUnreadCount(): Promise<number> {
  const result = await fetchResult<number>(`${BASE}/unread-count`, {
    method: 'get',
  });
  if (ResultHelper.isSuccess(result) && result.data != null) {
    return typeof result.data === 'number' ? result.data : 0;
  }
  return 0;
}

/**
 * Get single inbox item detail.
 */
export async function getInboxItem(id: number): Promise<InboxItem | null> {
  const result = await fetchResult<InboxItem>(`${BASE}/${id}`, {
    method: 'get',
  });
  if (ResultHelper.isSuccess(result) && result.data) {
    return result.data;
  }
  return null;
}

/**
 * Get full approval detail for a BPM task inbox item.
 */
export async function getApprovalDetail(id: number): Promise<any> {
  const result = await fetchResult<any>(`${BASE}/${id}/approval-detail`, {
    method: 'get',
  });
  return ResultHelper.isSuccess(result) ? result.data : null;
}

/**
 * Mark a single item as read.
 */
export async function markRead(id: number): Promise<void> {
  await fetchResult(`${BASE}/${id}/read`, { method: 'put' });
}

/**
 * Mark all items as read.
 */
export async function markAllRead(): Promise<void> {
  await fetchResult(`${BASE}/read-all`, { method: 'put' });
}

/**
 * Mark item as acted with an action.
 */
export async function markActed(id: number, action: string): Promise<void> {
  await fetchResult(`${BASE}/${id}/act`, { method: 'put', params: { action } });
}

/**
 * Dismiss an item.
 */
export async function dismissItem(id: number): Promise<void> {
  await fetchResult(`${BASE}/${id}/dismiss`, { method: 'put' });
}

/**
 * Submit approval action on an inbox item.
 */
export async function submitApprovalAction(id: number, action: string): Promise<void> {
  await fetchResult(`${BASE}/${id}/approval-action`, {
    method: 'post',
    params: { action },
  });
}

/**
 * Batch approve items.
 */
export async function batchApprove(ids: number[]): Promise<void> {
  await fetchResult(`${BASE}/batch/approve`, { method: 'post', params: { ids } });
}

/**
 * Batch reject items.
 */
export async function batchReject(ids: number[]): Promise<void> {
  await fetchResult(`${BASE}/batch/reject`, { method: 'post', params: { ids } });
}

/**
 * Batch mark items as read.
 */
export async function batchMarkRead(ids: number[]): Promise<void> {
  await fetchResult(`${BASE}/batch/read`, { method: 'put', params: { ids } });
}
