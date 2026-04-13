/**
 * BPM Attachment Service - File upload/download for BPM tasks
 */

import { get, del, ErrorCodes } from '~/services/http-client';

const API_BASE = '/api/bpm/attachments';

// ==================== Types ====================

export interface FileMetadata {
  pid: string;
  fileName: string;
  filePath: string;
  fileSize: number;
  contentType: string;
  sourceType: string;
  sourceId: string;
  createdAt: string;
}

// ==================== Helper Functions ====================

function isSuccess(code: string): boolean {
  return code === ErrorCodes.SUCCESS;
}

// ==================== API Functions ====================

/**
 * Get attachments for a process instance
 */
export async function getProcessAttachments(processInstanceId: string): Promise<FileMetadata[]> {
  const result = await get<FileMetadata[]>(`${API_BASE}/process/${processInstanceId}`);
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to get process attachments');
  }
  return result.data || [];
}

/**
 * Get attachments for a task
 */
export async function getTaskAttachments(taskId: string): Promise<FileMetadata[]> {
  const result = await get<FileMetadata[]>(`${API_BASE}/task/${taskId}`);
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to get task attachments');
  }
  return result.data || [];
}

/**
 * Delete an attachment
 */
export async function deleteAttachment(fileId: string): Promise<void> {
  const result = await del(`${API_BASE}/${fileId}`);
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to delete attachment');
  }
}
