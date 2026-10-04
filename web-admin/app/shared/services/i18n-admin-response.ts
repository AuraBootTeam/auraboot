/** HTTP success alone does not prove an admin workflow mutation succeeded. */
export async function readI18nAdminResponse<T>(response: Response): Promise<T> {
  const payload = await response.json() as { code?: unknown; message?: unknown; data?: T } | null;
  if (!response.ok) {
    throw new Error(typeof payload?.message === 'string' && payload.message
      ? payload.message : `HTTP ${response.status}`);
  }
  return requireI18nAdminResult(payload);
}

export function requireI18nAdminResult<T>(payload: { code?: unknown; message?: unknown; data?: T } | null): T {
  if (payload?.code !== '0') {
    throw new Error(typeof payload?.message === 'string' && payload.message
      ? payload.message : 'Unable to complete translation request');
  }
  return payload.data as T;
}
