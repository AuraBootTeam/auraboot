/**
 * Helpers for command buttons configured with `promptUpload`.
 *
 * A toolbar/command button may declare `promptUpload: true` (or
 * `promptUpload: "<payloadKey>"`) to collect a file from the user *before* the
 * command runs — the file is uploaded via the platform file-upload API and its
 * id is injected into the command payload. Without this, such buttons fire the
 * command with an empty payload and the handler rejects it (e.g.
 * `source_file_id is required`).
 */

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
    throw new Error(`File upload failed (${res.status})`);
  }
  const json = await res.json();
  const fileId = json?.data?.fileId ?? json?.fileId;
  if (!fileId) {
    throw new Error('File upload returned no fileId');
  }
  return String(fileId);
}

/**
 * Resolve the payload key under which a `promptUpload` button injects the
 * uploaded file id. `promptUpload: true` → default `source_file_id`;
 * `promptUpload: "<key>"` → that explicit key.
 */
export function resolvePromptUploadKey(promptUpload: unknown): string {
  return typeof promptUpload === 'string' && promptUpload.trim()
    ? promptUpload.trim()
    : 'source_file_id';
}
