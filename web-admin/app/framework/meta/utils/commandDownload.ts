function filenameFromContentDisposition(header: string | null): string | undefined {
  if (!header) return undefined;
  const utf8Match = header.match(/filename\*=UTF-8''([^;]+)/i);
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1]);
    } catch {
      return utf8Match[1];
    }
  }
  const quotedMatch = header.match(/filename="([^"]+)"/i);
  if (quotedMatch?.[1]) return quotedMatch[1];
  const plainMatch = header.match(/filename=([^;]+)/i);
  return plainMatch?.[1]?.trim();
}

export class FileDownloadHttpError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

export class FileDownloadValidationError extends Error {
  constructor(readonly code: 'CONTENT_TYPE' | 'ATTACHMENT' | 'EMPTY') {
    super('The protected file response failed validation');
  }
}

export interface FileDownloadOptions {
  expectedContentTypes?: readonly string[];
  requireAttachment?: boolean;
  requireNonEmpty?: boolean;
  resolveFailure?: (response: Response) => Promise<string>;
}

export async function downloadWithAuth(url: string, options: FileDownloadOptions = {}): Promise<void> {
  if (typeof window === 'undefined' || typeof document === 'undefined') return;

  const response = await fetch(url, {
    method: 'GET',
    credentials: 'same-origin',
  });
  if (!response.ok) {
    throw new FileDownloadHttpError(response.status, options.resolveFailure
      ? await options.resolveFailure(response) : `Download failed: ${response.status} ${response.statusText}`);
  }
  const disposition = response.headers.get('Content-Disposition');
  const fileName = filenameFromContentDisposition(disposition);
  const contentType = response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase();
  if (options.expectedContentTypes?.length && !options.expectedContentTypes.some(type => type.toLowerCase() === contentType)) {
    throw new FileDownloadValidationError('CONTENT_TYPE');
  }
  if (options.requireAttachment && (!/^attachment(?:;|$)/i.test(disposition?.trim() || '') || !fileName)) {
    throw new FileDownloadValidationError('ATTACHMENT');
  }
  const blob = await response.blob();
  if (options.requireNonEmpty && blob.size === 0) throw new FileDownloadValidationError('EMPTY');
  const objectUrl = window.URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = objectUrl;
  anchor.download = fileName || 'download';
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => window.URL.revokeObjectURL(objectUrl), 0);
}

/** Header commands opt in explicitly; an unrelated record id is never a file id. */
export function resolveCommandFileDownload(
  value: any,
  config?: { fileIdField: string },
): string | undefined {
  if (!config?.fileIdField) return undefined;
  let data = value;
  for (let depth = 0; depth < 4; depth += 1) {
    const fileId = config.fileIdField.split('.').reduce((current, key) => current?.[key], data);
    if (typeof fileId === 'string' && fileId.trim()) {
      return `/api/file/download/${encodeURIComponent(fileId.trim())}`;
    }
    if (!data?.data || typeof data.data !== 'object') return undefined;
    data = data.data;
  }
  return undefined;
}
