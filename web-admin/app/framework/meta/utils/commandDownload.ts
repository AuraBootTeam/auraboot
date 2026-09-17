import { JWT_TOKEN_KEY } from '~/constants/AuthConstant';

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

export async function downloadWithAuth(url: string): Promise<void> {
  if (typeof window === 'undefined' || typeof document === 'undefined') return;

  const headers: Record<string, string> = {};
  const token = window.localStorage?.getItem(JWT_TOKEN_KEY);
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const response = await fetch(url, {
    method: 'GET',
    headers,
    credentials: 'include',
  });
  if (!response.ok) {
    throw new Error(`Download failed: ${response.status} ${response.statusText}`);
  }

  const blob = await response.blob();
  const objectUrl = window.URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = objectUrl;
  anchor.download =
    filenameFromContentDisposition(response.headers.get('Content-Disposition')) || 'download';
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
