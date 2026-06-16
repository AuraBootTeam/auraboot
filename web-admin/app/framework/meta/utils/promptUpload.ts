/**
 * Helpers for command buttons configured with `promptUpload`.
 *
 * A toolbar/command button may declare `promptUpload: true` (or
 * `promptUpload: "<payloadKey>"`) to collect a file from the user *before* the
 * command runs — the file is uploaded via the platform file-upload API and its
 * id is injected into the command payload. The original browser filename is
 * injected under a companion key so command handlers can preserve artifact
 * provenance. Without this, such buttons fire the command with an empty payload
 * and the handler rejects it (e.g. `source_file_id is required`).
 */

const MAX_COMMAND_UPLOAD_SIZE_MB = 50;
const MAX_COMMAND_UPLOAD_SIZE_BYTES = MAX_COMMAND_UPLOAD_SIZE_MB * 1024 * 1024;

/**
 * Programmatically open the OS file picker and resolve with the chosen File, or
 * null if the user cancels. Kept side-effect-light so it can be invoked from a
 * hook callback without rendering a hidden input.
 */
export function pickFile(accept = '.xlsx,.xls,.csv'): Promise<File | null> {
  return new Promise((resolve) => {
    if (typeof document === 'undefined') {
      resolve(null);
      return;
    }
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = accept;
    input.style.position = 'fixed';
    input.style.left = '-9999px';
    let settled = false;
    const finish = (file: File | null) => {
      if (settled) return;
      settled = true;
      input.remove();
      resolve(file);
    };
    input.addEventListener('change', () => finish(input.files?.[0] ?? null));
    // Modern browsers fire 'cancel' when the dialog is dismissed.
    input.addEventListener('cancel', () => finish(null));
    document.body.appendChild(input);
    input.click();
  });
}

/**
 * Upload a File via the platform file-upload API and return the platform file
 * id. Throws on a non-2xx response or a missing file id.
 */
export async function uploadCommandFile(
  file: File,
  token?: string,
  uploadUrl = '/api/file/upload',
): Promise<string> {
  if (file.size > MAX_COMMAND_UPLOAD_SIZE_BYTES) {
    throw new Error(
      `文件过大：${file.name} 为 ${formatFileSize(file.size)}，当前上传上限为 ${MAX_COMMAND_UPLOAD_SIZE_MB}MB。请压缩后重试；如为 Gerber/坐标/BOM 资料包，请拆分后分别上传。`,
    );
  }
  const formData = new FormData();
  formData.append('file', file);
  const headers: Record<string, string> = {};
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  const res = await fetch(uploadUrl, {
    method: 'POST',
    body: formData,
    headers,
    credentials: 'include',
  });
  if (!res.ok) {
    throw new Error(await buildUploadFailureMessage(res));
  }
  const json = await res.json();
  const fileId = json?.data?.fileId ?? json?.fileId;
  if (!fileId) {
    throw new Error('File upload returned no fileId');
  }
  return String(fileId);
}

async function buildUploadFailureMessage(res: Response): Promise<string> {
  const details = await readUploadErrorDetails(res);
  const sizeLimit = details.match(/File too large:\s*max\s*([0-9]+)\s*MB/i)?.[1];
  if (sizeLimit) {
    return `文件过大，当前上传上限为 ${sizeLimit}MB。请压缩后重试；如为 Gerber/坐标/BOM 资料包，请拆分后分别上传。`;
  }
  if (details.trim()) {
    return `文件上传失败：${details.trim()}`;
  }
  return `File upload failed (${res.status})`;
}

function formatFileSize(size: number): string {
  if (size >= 1024 * 1024) {
    return `${(size / 1024 / 1024).toFixed(1)}MB`;
  }
  if (size >= 1024) {
    return `${(size / 1024).toFixed(1)}KB`;
  }
  return `${size}B`;
}

async function readUploadErrorDetails(res: Response): Promise<string> {
  try {
    const json = await res.json();
    return [
      json?.message,
      json?.error,
      json?.context?.detail,
      json?.context?.exception,
      json?.detail,
    ]
      .filter((value): value is string => typeof value === 'string' && value.trim().length > 0)
      .join(' ');
  } catch {
    try {
      return await res.text();
    } catch {
      return '';
    }
  }
}

/**
 * Resolve the payload key under which a `promptUpload` button injects the
 * uploaded file id. `promptUpload: true` → default `source_file_id`;
 * `promptUpload: "<key>"` → that explicit key.
 */
export function resolvePromptUploadKey(promptUpload: unknown): string {
  if (typeof promptUpload === 'string' && promptUpload.trim()) {
    return promptUpload.trim();
  }
  if (promptUpload && typeof promptUpload === 'object' && !Array.isArray(promptUpload)) {
    const key = (promptUpload as Record<string, unknown>).key;
    if (typeof key === 'string' && key.trim()) {
      return key.trim();
    }
  }
  return 'source_file_id';
}

export function resolvePromptUploadAccept(promptUpload: unknown): string {
  if (promptUpload && typeof promptUpload === 'object' && !Array.isArray(promptUpload)) {
    const accept = (promptUpload as Record<string, unknown>).accept;
    if (typeof accept === 'string' && accept.trim()) {
      return accept.trim();
    }
  }
  return '.xlsx,.xls,.csv';
}

/**
 * Resolve the companion filename payload key for a promptUpload file id key.
 *
 * Examples:
 * - `corrected_bom_file_id` → `corrected_bom_filename`
 * - `process_rule_file_id` → `process_rule_filename`
 * - `source_file_id` → `source_filename`
 */
export function resolvePromptUploadFilenameKey(promptUpload: unknown): string {
  const fileIdKey = resolvePromptUploadKey(promptUpload);
  if (fileIdKey.endsWith('_file_id')) {
    return `${fileIdKey.slice(0, -'_file_id'.length)}_filename`;
  }
  if (fileIdKey.endsWith('_fileId')) {
    return `${fileIdKey.slice(0, -'_fileId'.length)}_filename`;
  }
  if (fileIdKey.endsWith('FileId')) {
    return `${fileIdKey.slice(0, -'FileId'.length)}Filename`;
  }
  return `${fileIdKey}_filename`;
}
