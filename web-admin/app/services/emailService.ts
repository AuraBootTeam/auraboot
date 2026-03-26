/**
 * Email Service — API client for Gmail CRM integration endpoints.
 *
 * Covers:
 *  - Email accounts (connect, sync, members)
 *  - Email messages (list, thread, send, link)
 *  - Email sequences (CRUD, steps, enrollments)
 *  - Tracking stats
 *
 * @since 6.5.0
 */

import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface EmailAccount {
  id: number;
  tenantId: number;
  userId: number;
  accountType: string; // 'personal' | 'shared'
  provider: string;    // 'gmail'
  emailAddress: string;
  displayName?: string;
  syncMode: string;    // 'full' | 'metadata_only'
  syncState?: {
    lastSyncAt?: string;
    historyId?: string;
    syncStatus?: string; // 'syncing' | 'idle' | 'error'
  };
  status: string;      // 'active' | 'disconnected' | 'error'
  createdAt: string;
  updatedAt: string;
}

export interface EmailAccountMember {
  id: number;
  accountId: number;
  userId: number;
  role: string; // 'owner' | 'member'
  assignmentWeight?: number;
  userDisplayName?: string;
  userEmail?: string;
}

export interface EmailAttachment {
  attachmentId: string;
  filename: string;
  mimeType: string;
  size: number;
}

export interface EmailMessage {
  id: number;
  accountId: number;
  gmailMessageId: string;
  gmailThreadId: string;
  direction: string; // 'inbound' | 'outbound'
  fromAddress: string;
  fromName?: string;
  toAddresses: string[];
  ccAddresses: string[];
  bccAddresses: string[];
  subject: string;
  bodyText?: string;
  bodyHtml?: string;
  hasAttachments: boolean;
  attachments: EmailAttachment[];
  labelIds: string[];
  isRead: boolean;
  gmailDate: string;
  assignedTo?: number;
}

export interface EmailThread {
  threadId: string;
  messages: EmailMessage[];
}

export interface EmailSequence {
  id: number;
  name: string;
  description?: string;
  status: string; // 'draft' | 'active' | 'paused' | 'archived'
  createdBy: number;
  createdAt: string;
  updatedAt?: string;
}

export interface EmailSequenceStep {
  id: number;
  sequenceId: number;
  stepOrder: number;
  delayDays: number;
  subjectTemplate: string;
  bodyTemplate: string;
}

export interface EmailSequenceEnrollment {
  id: number;
  sequenceId: number;
  accountId: number;
  contactEmail: string;
  modelCode?: string;
  recordId?: number;
  currentStep: number;
  status: string; // 'active' | 'paused' | 'completed' | 'unsubscribed'
  nextSendAt?: string;
  enrolledAt: string;
}

export interface TrackingStats {
  opens: number;
  clicks: number;
}

export interface EmailPage {
  records: EmailMessage[];
  total: number;
  current: number;
  size: number;
  pages: number;
}

export interface CrmLink {
  id: number;
  messageId: number;
  modelCode: string;
  recordId: number;
  recordName?: string;
  createdAt: string;
}

// ─── Base URL ─────────────────────────────────────────────────────────────────

const BASE = '/api/email';

// ─── Account APIs ─────────────────────────────────────────────────────────────

/**
 * Get Gmail OAuth2 authorization URL to redirect user for account connection.
 */
export async function getOAuthUrl(): Promise<string | null> {
  const result = await fetchResult<string>(`${BASE}/accounts/oauth/url`, {
    method: 'get',
  });
  return ResultHelper.isSuccess(result) ? result.data ?? null : null;
}

/**
 * List all email accounts for current user/tenant.
 */
export async function listAccounts(): Promise<EmailAccount[]> {
  const result = await fetchResult<EmailAccount[]>(`${BASE}/accounts`, {
    method: 'get',
  });
  if (ResultHelper.isSuccess(result) && result.data) {
    return result.data;
  }
  return [];
}

/**
 * Update sync mode for an account.
 */
export async function updateSyncMode(
  accountId: number,
  syncMode: 'full' | 'metadata_only',
): Promise<void> {
  await fetchResult(`${BASE}/accounts/${accountId}/sync-mode`, {
    method: 'put',
    params: { syncMode },
  });
}

/**
 * Disconnect a Gmail account.
 */
export async function disconnectAccount(accountId: number): Promise<void> {
  await fetchResult(`${BASE}/accounts/${accountId}/disconnect`, {
    method: 'post',
  });
}

/**
 * Trigger a manual sync for an account.
 */
export async function triggerSync(accountId: number): Promise<void> {
  await fetchResult(`${BASE}/accounts/${accountId}/sync`, {
    method: 'post',
  });
}

/**
 * List members of a shared email account.
 */
export async function listMembers(accountId: number): Promise<EmailAccountMember[]> {
  const result = await fetchResult<EmailAccountMember[]>(
    `${BASE}/accounts/${accountId}/members`,
    { method: 'get' },
  );
  if (ResultHelper.isSuccess(result) && result.data) {
    return result.data;
  }
  return [];
}

/**
 * Add a member to a shared email account.
 */
export async function addMember(
  accountId: number,
  userId: number,
  role: string,
): Promise<void> {
  await fetchResult(`${BASE}/accounts/${accountId}/members`, {
    method: 'post',
    params: { userId, role },
  });
}

/**
 * Remove a member from a shared email account.
 */
export async function removeMember(accountId: number, memberId: number): Promise<void> {
  await fetchResult(`${BASE}/accounts/${accountId}/members/${memberId}`, {
    method: 'delete',
  });
}

// ─── Message APIs ─────────────────────────────────────────────────────────────

/**
 * List email messages with optional filters.
 */
export async function listMessages(params: {
  accountId?: number;
  direction?: string;
  isRead?: boolean;
  keyword?: string;
  pageNum?: number;
  pageSize?: number;
}): Promise<EmailPage> {
  const result = await fetchResult<EmailPage>(`${BASE}/messages`, {
    method: 'get',
    params,
  });
  if (ResultHelper.isSuccess(result) && result.data) {
    return result.data;
  }
  return { records: [], total: 0, current: 1, size: 20, pages: 0 };
}

/**
 * Get all messages in a thread.
 */
export async function getThread(threadId: string): Promise<EmailThread | null> {
  const result = await fetchResult<EmailThread>(`${BASE}/threads/${threadId}`, {
    method: 'get',
  });
  return ResultHelper.isSuccess(result) ? result.data ?? null : null;
}

/**
 * Get a single email message.
 */
export async function getMessage(messageId: number): Promise<EmailMessage | null> {
  const result = await fetchResult<EmailMessage>(`${BASE}/messages/${messageId}`, {
    method: 'get',
  });
  return ResultHelper.isSuccess(result) ? result.data ?? null : null;
}

/**
 * Send an email.
 */
export async function sendEmail(params: {
  accountId: number;
  to: string[];
  cc?: string[];
  bcc?: string[];
  subject: string;
  body: string;
  threadId?: string;
  trackOpens?: boolean;
  trackClicks?: boolean;
}): Promise<boolean> {
  const result = await fetchResult(`${BASE}/messages/send`, {
    method: 'post',
    params,
  });
  return ResultHelper.isSuccess(result);
}

/**
 * Mark a message as read.
 */
export async function markMessageRead(messageId: number): Promise<void> {
  await fetchResult(`${BASE}/messages/${messageId}/read`, { method: 'put' });
}

/**
 * Link an email message to a CRM record.
 */
export async function linkToRecord(
  messageId: number,
  modelCode: string,
  recordId: number,
): Promise<void> {
  await fetchResult(`${BASE}/messages/${messageId}/links`, {
    method: 'post',
    params: { modelCode, recordId },
  });
}

/**
 * Unlink an email message from a CRM record.
 */
export async function unlinkRecord(messageId: number, linkId: number): Promise<void> {
  await fetchResult(`${BASE}/messages/${messageId}/links/${linkId}`, {
    method: 'delete',
  });
}

/**
 * Get email messages linked to a specific CRM record.
 */
export async function getMessagesByRecord(
  modelCode: string,
  recordId: number,
): Promise<EmailMessage[]> {
  const result = await fetchResult<EmailMessage[]>(`${BASE}/messages/by-record`, {
    method: 'get',
    params: { modelCode, recordId },
  });
  if (ResultHelper.isSuccess(result) && result.data) {
    return result.data;
  }
  return [];
}

/**
 * Get CRM links for a message.
 */
export async function getMessageLinks(messageId: number): Promise<CrmLink[]> {
  const result = await fetchResult<CrmLink[]>(`${BASE}/messages/${messageId}/links`, {
    method: 'get',
  });
  if (ResultHelper.isSuccess(result) && result.data) {
    return result.data;
  }
  return [];
}

/**
 * Get tracking stats (opens/clicks) for an outbound message.
 */
export async function getTrackingStats(messageId: number): Promise<TrackingStats> {
  const result = await fetchResult<TrackingStats>(
    `${BASE}/messages/${messageId}/tracking`,
    { method: 'get' },
  );
  if (ResultHelper.isSuccess(result) && result.data) {
    return result.data;
  }
  return { opens: 0, clicks: 0 };
}

// ─── Sequence APIs ────────────────────────────────────────────────────────────

/**
 * List email sequences.
 */
export async function listSequences(): Promise<EmailSequence[]> {
  const result = await fetchResult<EmailSequence[]>(`${BASE}/sequences`, {
    method: 'get',
  });
  if (ResultHelper.isSuccess(result) && result.data) {
    return result.data;
  }
  return [];
}

/**
 * Create a new email sequence.
 */
export async function createSequence(data: {
  name: string;
  description?: string;
}): Promise<EmailSequence | null> {
  const result = await fetchResult<EmailSequence>(`${BASE}/sequences`, {
    method: 'post',
    params: data,
  });
  return ResultHelper.isSuccess(result) ? result.data ?? null : null;
}

/**
 * Get a sequence with its steps.
 */
export async function getSequence(sequenceId: number): Promise<EmailSequence | null> {
  const result = await fetchResult<EmailSequence>(`${BASE}/sequences/${sequenceId}`, {
    method: 'get',
  });
  return ResultHelper.isSuccess(result) ? result.data ?? null : null;
}

/**
 * Update sequence metadata (name, description).
 */
export async function updateSequence(
  sequenceId: number,
  data: { name?: string; description?: string },
): Promise<void> {
  await fetchResult(`${BASE}/sequences/${sequenceId}`, {
    method: 'put',
    params: data,
  });
}

/**
 * Update sequence status (activate / pause / archive).
 */
export async function updateSequenceStatus(
  sequenceId: number,
  status: 'active' | 'paused' | 'archived',
): Promise<void> {
  await fetchResult(`${BASE}/sequences/${sequenceId}/status`, {
    method: 'put',
    params: { status },
  });
}

/**
 * Add a step to a sequence.
 */
export async function addStep(
  sequenceId: number,
  data: {
    stepOrder: number;
    delayDays: number;
    subjectTemplate: string;
    bodyTemplate: string;
  },
): Promise<EmailSequenceStep | null> {
  const result = await fetchResult<EmailSequenceStep>(
    `${BASE}/sequences/${sequenceId}/steps`,
    { method: 'post', params: data },
  );
  return ResultHelper.isSuccess(result) ? result.data ?? null : null;
}

/**
 * Update a sequence step.
 */
export async function updateStep(
  sequenceId: number,
  stepId: number,
  data: {
    delayDays?: number;
    subjectTemplate?: string;
    bodyTemplate?: string;
  },
): Promise<void> {
  await fetchResult(`${BASE}/sequences/${sequenceId}/steps/${stepId}`, {
    method: 'put',
    params: data,
  });
}

/**
 * Delete a sequence step.
 */
export async function deleteStep(sequenceId: number, stepId: number): Promise<void> {
  await fetchResult(`${BASE}/sequences/${sequenceId}/steps/${stepId}`, {
    method: 'delete',
  });
}

/**
 * Get steps for a sequence.
 */
export async function listSteps(sequenceId: number): Promise<EmailSequenceStep[]> {
  const result = await fetchResult<EmailSequenceStep[]>(
    `${BASE}/sequences/${sequenceId}/steps`,
    { method: 'get' },
  );
  if (ResultHelper.isSuccess(result) && result.data) {
    return result.data;
  }
  return [];
}

/**
 * Enroll contacts into a sequence.
 */
export async function enrollContacts(
  sequenceId: number,
  enrollments: Array<{
    accountId: number;
    contactEmail: string;
    modelCode?: string;
    recordId?: number;
  }>,
): Promise<void> {
  await fetchResult(`${BASE}/sequences/${sequenceId}/enroll`, {
    method: 'post',
    params: { enrollments },
  });
}

/**
 * List enrollments for a sequence.
 */
export async function listEnrollments(
  sequenceId: number,
): Promise<EmailSequenceEnrollment[]> {
  const result = await fetchResult<EmailSequenceEnrollment[]>(
    `${BASE}/sequences/${sequenceId}/enrollments`,
    { method: 'get' },
  );
  if (ResultHelper.isSuccess(result) && result.data) {
    return result.data;
  }
  return [];
}

/**
 * Pause an enrollment.
 */
export async function pauseEnrollment(
  sequenceId: number,
  enrollmentId: number,
): Promise<void> {
  await fetchResult(
    `${BASE}/sequences/${sequenceId}/enrollments/${enrollmentId}/pause`,
    { method: 'put' },
  );
}

/**
 * Resume a paused enrollment.
 */
export async function resumeEnrollment(
  sequenceId: number,
  enrollmentId: number,
): Promise<void> {
  await fetchResult(
    `${BASE}/sequences/${sequenceId}/enrollments/${enrollmentId}/resume`,
    { method: 'put' },
  );
}
