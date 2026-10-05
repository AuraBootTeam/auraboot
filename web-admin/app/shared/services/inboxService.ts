/**
 * Unified Inbox Service — API client for /api/inbox endpoints.
 *
 * Data source: ab_inbox_item table via InboxController.
 * Used by InboxBadge, InboxDropdown, and UnifiedInboxPage.
 *
 * @since 6.4.0
 */

import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper, type Result } from '~/utils/type';

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
  sourceModel?: string;
  recordPid?: string;
  sourceRecordPid?: string;
  cardPayload?: string; // JSON string
  cardData?: Record<string, unknown>;
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

function requireSuccess<T>(result: Result<T>): T | null {
  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.message || 'Inbox request failed');
  }
  return result.data;
}

function requireData<T>(result: Result<T>): T {
  const data = requireSuccess(result);
  if (data == null) throw new Error('Inbox response is missing data');
  return data;
}

function notifyInboxChanged(count?: number): void {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('aura:inbox-update', {
      detail: { type: 'inbox', ...(count == null ? {} : { count }) },
    }));
  }
}

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
  return requireData(result);
}

/**
 * Get unread counts grouped by item type.
 */
export async function getUnreadSummary(): Promise<UnreadSummary> {
  const result = await fetchResult<UnreadSummary>(`${BASE}/unread-summary`, {
    method: 'get',
  });
  return requireData(result);
}

/**
 * Get total unread count (for badge).
 */
export async function getUnreadCount(): Promise<number> {
  const result = await fetchResult<number>(`${BASE}/unread-count`, {
    method: 'get',
  });
  const count = requireData(result);
  if (typeof count !== 'number' || !Number.isInteger(count) || count < 0) {
    throw new Error('Inbox unread count is invalid');
  }
  return count;
}

/**
 * Get single inbox item detail.
 */
export async function getInboxItem(id: number): Promise<InboxItem | null> {
  const result = await fetchResult<InboxItem>(`${BASE}/${id}`, {
    method: 'get',
  });
  return requireSuccess(result);
}

/**
 * Get full approval detail for a BPM task inbox item.
 */
export async function getApprovalDetail(id: number): Promise<any> {
  const result = await fetchResult<any>(`${BASE}/${id}/approval-detail`, {
    method: 'get',
  });
  return requireSuccess(result);
}

/**
 * Mark a single item as read.
 */
export async function markRead(id: number): Promise<void> {
  const result = await fetchResult(`${BASE}/${id}/read`, { method: 'put' });
  requireSuccess(result);
  notifyInboxChanged();
}

/**
 * Mark all items as read.
 */
export async function markAllRead(): Promise<void> {
  const result = await fetchResult(`${BASE}/read-all`, { method: 'put' });
  requireSuccess(result);
  notifyInboxChanged(0);
}

/**
 * Mark item as acted with an action.
 */
export async function markActed(id: number, action: string, comment?: string): Promise<void> {
  const result = await fetchResult(`${BASE}/${id}/act`, {
    method: 'put',
    params: { action, ...(comment != null ? { comment } : {}) },
  });
  requireSuccess(result);
  notifyInboxChanged();
}

/**
 * Dismiss an item.
 */
export async function dismissItem(id: number): Promise<void> {
  const result = await fetchResult(`${BASE}/${id}/dismiss`, { method: 'put' });
  requireSuccess(result);
  notifyInboxChanged();
}

/**
 * Submit approval action on an inbox item.
 */
export async function submitApprovalAction(
  id: number,
  action: string,
  comment?: string,
): Promise<void> {
  const result = await fetchResult(`${BASE}/${id}/approval-action`, {
    method: 'post',
    params: { action, ...(comment != null ? { comment } : {}) },
  });
  requireSuccess(result);
  notifyInboxChanged();
}

/**
 * Batch approve items.
 */
export async function batchApprove(ids: number[]): Promise<void> {
  const result = await fetchResult(`${BASE}/batch/approve`, { method: 'post', params: { ids } });
  requireSuccess(result);
  notifyInboxChanged();
}

/**
 * Batch reject items.
 */
export async function batchReject(ids: number[], comment: string): Promise<void> {
  const result = await fetchResult(`${BASE}/batch/reject`, { method: 'post', params: { ids, comment } });
  requireSuccess(result);
  notifyInboxChanged();
}

/**
 * Batch mark items as read.
 */
export async function batchMarkRead(ids: number[]): Promise<void> {
  const result = await fetchResult(`${BASE}/batch/read`, { method: 'put', params: { ids } });
  requireSuccess(result);
  notifyInboxChanged();
}
