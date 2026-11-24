export class OpenPlatformApiError extends Error {
  constructor(public readonly status: number, message: string) { super(message); }
}

export async function openPlatformApiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || String(body.code) !== '0') {
    throw new OpenPlatformApiError(response.status, body.message ?? `HTTP ${response.status}`);
  }
  return body.data as T;
}
