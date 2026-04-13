/**
 * 文档管理服务
 * 管理员文档上传和管理功能
 */

import axios from 'axios';

const API_BASE_URL = '/api/ai';

/**
 * 上传任务状态
 */
export type IngestionStatus = 'pending' | 'running' | 'completed' | 'failed';

/**
 * 文档类型
 */
export type DocumentType = 'research_report' | 'disclosure' | 'news' | 'user_note';

/**
 * 上传任务
 */
export interface IngestionTask {
  task_id: string;
  tenant_id: string;
  user_id: string;
  file_name: string;
  file_size: number;
  file_type: string;
  status: IngestionStatus;
  progress: number;
  chunks_created?: number;
  error_message?: string;
  created_at: string;
  started_at?: string;
  completed_at?: string;
  metadata?: {
    document_type?: DocumentType;
    symbol?: string;
    broker?: string;
    publish_date?: string;
  };
}

/**
 * 上传文档请求
 */
export interface UploadDocumentRequest {
  file: File;
  document_type: DocumentType;
  symbol?: string;
  broker?: string;
  publish_date?: string;
}

/**
 * 上传文档响应
 */
export interface UploadDocumentResponse {
  task_id: string;
  status: IngestionStatus;
  message: string;
}

/**
 * 上传文档
 */
export async function uploadDocument(
  request: UploadDocumentRequest,
): Promise<UploadDocumentResponse> {
  const formData = new FormData();
  formData.append('file', request.file);
  formData.append('document_type', request.document_type);

  if (request.symbol) {
    formData.append('symbol', request.symbol);
  }
  if (request.broker) {
    formData.append('broker', request.broker);
  }
  if (request.publish_date) {
    formData.append('publish_date', request.publish_date);
  }

  const response = await axios.post<UploadDocumentResponse>(
    `${API_BASE_URL}/documents/upload`,
    formData,
    {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    },
  );

  return response.data;
}

/**
 * 获取任务状态
 */
export async function getTaskStatus(taskId: string): Promise<IngestionTask> {
  const response = await axios.get<IngestionTask>(`${API_BASE_URL}/documents/tasks/${taskId}`);
  return response.data;
}

/**
 * 获取任务列表
 */
export async function getTaskList(params?: {
  limit?: number;
  offset?: number;
}): Promise<{ tasks: IngestionTask[]; total: number }> {
  const response = await axios.get<{ tasks: IngestionTask[]; total: number }>(
    `${API_BASE_URL}/documents/tasks`,
    { params },
  );
  return response.data;
}

/**
 * 删除文档
 */
export async function deleteDocument(docId: string): Promise<void> {
  await axios.delete(`${API_BASE_URL}/documents/${docId}`);
}

/**
 * 格式化文件大小
 */
export function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${(bytes / Math.pow(k, i)).toFixed(2)} ${sizes[i]}`;
}

/**
 * 获取文件类型图标
 */
export function getFileTypeIcon(fileType: string): string {
  const type = fileType.toLowerCase();
  if (type.includes('pdf')) return '📄';
  if (type.includes('word') || type.includes('doc')) return '📝';
  if (type.includes('excel') || type.includes('xls')) return '📊';
  if (type.includes('image') || type.includes('png') || type.includes('jpg')) return '🖼️';
  if (type.includes('text')) return '📃';
  return '📎';
}

/**
 * 获取状态显示文本
 */
export function getStatusText(status: IngestionStatus): string {
  const statusMap: Record<IngestionStatus, string> = {
    pending: '等待处理',
    running: '处理中',
    completed: '已完成',
    failed: '失败',
  };
  return statusMap[status] || status;
}

/**
 * 获取状态颜色类
 */
export function getStatusColorClass(status: IngestionStatus): string {
  const colorMap: Record<IngestionStatus, string> = {
    pending: 'text-yellow-600 bg-yellow-50',
    running: 'text-blue-600 bg-blue-50',
    completed: 'text-green-600 bg-green-50',
    failed: 'text-red-600 bg-red-50',
  };
  return colorMap[status] || 'text-gray-600 bg-gray-50';
}
