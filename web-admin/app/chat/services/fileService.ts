/**
 * 文件上传服务
 */

export interface UploadProgress {
  loaded: number;
  total: number;
  percentage: number;
}

export interface TemporaryAttachment {
  attachment_id: number;
  file_name: string;
  file_type: string;
  file_size: number;
  processing_mode: 'direct' | 'vectorized';
  status: 'pending' | 'processing' | 'completed' | 'failed';
  expires_at: string;
  created_at?: string;
}

const API_BASE_URL = '/chat';
const ADMIN_UPLOAD_ENDPOINT = '/api/admin/documents/upload';

export interface AdminDocumentUploadResponse {
  task_id: string;
  document_id: string;
  status: string;
  priority: number;
  approval_required: boolean;
  message: string;
  estimated_processing_time?: number;
}

export interface AdminDocumentMetadata {
  admin_user_id: string;
  document_type: string;
  priority: number;
  approval_required: boolean;
  title?: string;
  symbol?: string;
  publish_date?: string;
  broker?: string;
  admin_notes?: string;
  [key: string]: unknown;
}

export interface AdminDocumentUploadOptions {
  signal?: AbortSignal;
  onUploadProgress?: (progress: UploadProgress) => void;
}

/**
 * 上传临时文件
 */
export async function uploadTemporaryFile(
  file: File,
  sessionId: string,
  onProgress?: (progress: UploadProgress) => void,
): Promise<TemporaryAttachment> {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('session_id', sessionId);

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();

    // 监听上传进度
    xhr.upload.addEventListener('progress', (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress({
          loaded: e.loaded,
          total: e.total,
          percentage: Math.round((e.loaded / e.total) * 100),
        });
      }
    });

    // 监听完成
    xhr.addEventListener('load', () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          const response = JSON.parse(xhr.responseText);
          resolve(response);
        } catch (err) {
          reject(new Error('Invalid response format'));
        }
      } else {
        try {
          const error = JSON.parse(xhr.responseText);
          reject(new Error(error.message || `Upload failed: ${xhr.status}`));
        } catch {
          reject(new Error(`Upload failed: ${xhr.status}`));
        }
      }
    });

    // 监听错误
    xhr.addEventListener('error', () => {
      reject(new Error('Network error'));
    });

    // 监听超时
    xhr.addEventListener('timeout', () => {
      reject(new Error('Upload timeout'));
    });

    // 发送请求
    xhr.open('post', `${API_BASE_URL}/upload`);
    xhr.timeout = 60000; // 60 秒超时
    xhr.send(formData);
  });
}

/**
 * 获取会话的所有附件
 */
export async function getSessionAttachments(sessionId: string): Promise<TemporaryAttachment[]> {
  const response = await fetch(`${API_BASE_URL}/attachments?session_id=${sessionId}`, {
    method: 'get',
    credentials: 'include',
  });

  if (!response.ok) {
    throw new Error(`Failed to fetch attachments: ${response.status}`);
  }

  const data = await response.json();
  return data.attachments || [];
}

/**
 * 管理端文档上传
 */
export async function uploadAdminDocument(
  file: File,
  metadata: AdminDocumentMetadata,
  options?: AdminDocumentUploadOptions,
): Promise<AdminDocumentUploadResponse> {
  if (supportsRequestStreams()) {
    try {
      return await uploadAdminDocumentWithFetchStream(file, metadata, options);
    } catch (error) {
      if (!shouldFallbackToXhr(error)) {
        throw error;
      }
      // Mark request stream support as unavailable to avoid repeated failures
      requestStreamSupport = false;
    }
  }

  return uploadAdminDocumentWithXhr(file, metadata, options);
}

/**
 * 删除附件
 */
export async function deleteAttachment(attachmentId: number): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/attachments/${attachmentId}`, {
    method: 'delete',
    credentials: 'include',
  });

  if (!response.ok) {
    throw new Error(`Failed to delete attachment: ${response.status}`);
  }
}

async function uploadAdminDocumentWithFetchStream(
  file: File,
  metadata: AdminDocumentMetadata,
  options?: AdminDocumentUploadOptions,
): Promise<AdminDocumentUploadResponse> {
  const boundary = `----AurabootFormBoundary${Math.random().toString(16).slice(2)}`;
  const normalizedFields: Array<[string, string]> = Object.entries(metadata)
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map<[string, string]>(([key, value]) => [key, String(value)]);

  const bodyStream = createMultipartStream(
    file,
    normalizedFields,
    boundary,
    options?.onUploadProgress,
  );

  const response = await fetch(ADMIN_UPLOAD_ENDPOINT, {
    method: 'post',
    headers: {
      'Content-Type': `multipart/form-data; boundary=${boundary}`,
    },
    body: bodyStream,
    // Required by fetch when using a streaming request body
    duplex: 'half',
    signal: options?.signal,
    credentials: 'include',
  } as RequestInit & { duplex: 'half' });

  const payload = await parseJsonSafe(response);

  if (!response.ok) {
    throw new Error(extractErrorMessage(payload, `Upload failed with status ${response.status}`));
  }

  return normalizeUploadResponse(payload);
}

function uploadAdminDocumentWithXhr(
  file: File,
  metadata: AdminDocumentMetadata,
  options?: AdminDocumentUploadOptions,
): Promise<AdminDocumentUploadResponse> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('post', ADMIN_UPLOAD_ENDPOINT);
    xhr.withCredentials = true;

    const formData = buildAdminUploadFormData(file, metadata);

    if (options?.onUploadProgress) {
      xhr.upload.addEventListener('progress', (event) => {
        if (event.lengthComputable) {
          const percentage = event.total > 0 ? Math.round((event.loaded / event.total) * 100) : 0;
          options.onUploadProgress?.({
            loaded: event.loaded,
            total: event.total,
            percentage,
          });
        }
      });
    }

    let unregisterAbortListener = () => {};

    xhr.addEventListener('load', () => {
      unregisterAbortListener();
      const responseText = xhr.responseText;
      try {
        const payload = responseText ? JSON.parse(responseText) : null;
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve(normalizeUploadResponse(payload));
        } else {
          reject(
            new Error(extractErrorMessage(payload, `Upload failed with status ${xhr.status}`)),
          );
        }
      } catch (err) {
        reject(err instanceof Error ? err : new Error('Invalid response payload'));
      }
    });

    xhr.addEventListener('error', () => {
      unregisterAbortListener();
      reject(new Error('Network error during upload'));
    });

    xhr.addEventListener('timeout', () => {
      unregisterAbortListener();
      reject(new Error('Upload timeout'));
    });

    const abortHandler = () => {
      xhr.abort();
      unregisterAbortListener();
      reject(new DOMException('Upload aborted', 'AbortError'));
    };

    if (options?.signal) {
      if (options.signal.aborted) {
        abortHandler();
        return;
      }
      options.signal.addEventListener('abort', abortHandler, { once: true });
      unregisterAbortListener = () => {
        options.signal?.removeEventListener('abort', abortHandler);
      };
    }

    xhr.send(formData);
  });
}

let requestStreamSupport: boolean | null = null;

function supportsRequestStreams(): boolean {
  if (requestStreamSupport !== null) {
    return requestStreamSupport;
  }

  if (
    typeof window === 'undefined' ||
    typeof ReadableStream === 'undefined' ||
    typeof Request === 'undefined'
  ) {
    requestStreamSupport = false;
    return requestStreamSupport;
  }

  try {
    const stream = new ReadableStream();
    // eslint-disable-next-line @typescript-eslint/consistent-type-assertions
    new Request('https://example.com', {
      method: 'post',
      body: stream,
      duplex: 'half',
    } as RequestInit & { duplex: 'half' });
    requestStreamSupport = true;
  } catch {
    requestStreamSupport = false;
  }

  return requestStreamSupport;
}

function shouldFallbackToXhr(error: unknown): boolean {
  if (!error) {
    return false;
  }
  if (error instanceof DOMException && error.name === 'AbortError') {
    return false;
  }
  if (error instanceof TypeError && typeof error.message === 'string') {
    return (
      /duplex|ReadableStream|body.*stream/i.test(error.message) ||
      error.message.includes('Failed to fetch')
    );
  }
  return false;
}

function buildAdminUploadFormData(file: File, metadata: AdminDocumentMetadata): FormData {
  const formData = new FormData();
  formData.append('file', file);

  Object.entries(metadata).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      formData.append(key, String(value));
    }
  });

  return formData;
}

function normalizeUploadResponse(payload: any): AdminDocumentUploadResponse {
  if (payload && typeof payload === 'object') {
    const data = payload as Record<string, any>;
    if (data.success === false) {
      throw new Error(typeof data.message === 'string' ? data.message : 'Upload failed');
    }
    if (data.data) {
      return data.data as AdminDocumentUploadResponse;
    }
  }
  return payload as AdminDocumentUploadResponse;
}

function extractErrorMessage(payload: any, fallback: string): string {
  if (payload && typeof payload === 'object') {
    const data = payload as Record<string, any>;
    if (typeof data.message === 'string') {
      return data.message;
    }
    if (typeof data.detail === 'string') {
      return data.detail;
    }
  }

  if (typeof payload === 'string') {
    return payload;
  }

  return fallback;
}

function createMultipartStream(
  file: File,
  fields: Array<[string, string]>,
  boundary: string,
  onUploadProgress?: (progress: UploadProgress) => void,
): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  const newLine = '\r\n';
  const fieldChunks = fields.map(([name, value]) =>
    encoder.encode(
      `--${boundary}${newLine}Content-Disposition: form-data; name="${name}"${newLine}${newLine}${value}${newLine}`,
    ),
  );

  const escapedFilename = file.name.replace(/"/g, '%22');
  const fileHeader = encoder.encode(
    `--${boundary}${newLine}Content-Disposition: form-data; name="file"; filename="${escapedFilename}"${newLine}` +
      `Content-Type: ${file.type || 'application/octet-stream'}${newLine}${newLine}`,
  );
  const fileFooter = encoder.encode(newLine);
  const closing = encoder.encode(`--${boundary}--${newLine}`);

  const totalBytes =
    fieldChunks.reduce((sum, chunk) => sum + chunk.length, 0) +
    fileHeader.length +
    file.size +
    fileFooter.length +
    closing.length;

  let uploaded = 0;

  const notifyProgress = () => {
    if (onUploadProgress) {
      const percentage = totalBytes ? Math.min(100, Math.round((uploaded / totalBytes) * 100)) : 0;
      onUploadProgress({
        loaded: uploaded,
        total: totalBytes,
        percentage,
      });
    }
  };

  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      const enqueueChunk = (chunk: Uint8Array) => {
        controller.enqueue(chunk);
        uploaded += chunk.length;
        notifyProgress();
      };

      for (const chunk of fieldChunks) {
        enqueueChunk(chunk);
      }

      enqueueChunk(fileHeader);

      const reader = file.stream().getReader();
      while (true) {
        const { done, value } = await reader.read();
        if (done) {
          break;
        }
        if (value) {
          enqueueChunk(value);
        }
      }

      enqueueChunk(fileFooter);
      enqueueChunk(closing);
      controller.close();
    },
  });

  notifyProgress();

  return stream;
}

async function parseJsonSafe(response: Response): Promise<any> {
  const contentType = response.headers.get('content-type') || '';
  if (contentType.includes('application/json')) {
    return response.json();
  }
  const text = await response.text();
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
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
  const icons: Record<string, string> = {
    pdf: '📄',
    excel: '📊',
    word: '📝',
    image: '🖼️',
    text: '📃',
  };
  return icons[fileType] || '📎';
}

/**
 * 获取文件类型标签
 */
export function getFileTypeLabel(fileType: string): string {
  const labels: Record<string, string> = {
    pdf: 'pdf',
    excel: 'Excel',
    word: 'Word',
    image: '图片',
    text: '文本',
  };
  return labels[fileType] || fileType;
}
