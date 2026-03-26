import { get, put } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';

const BASE = '/api/tenant-preferences';

export const tenantPreferenceService = {
  async get<T = unknown>(key: string): Promise<T | null> {
    const result = await get<{ value: T }>(`${BASE}/${key}`);
    return ResultHelper.isSuccess(result) ? (result.data?.value ?? null) : null;
  },

  async set(key: string, value: unknown): Promise<void> {
    await put<void>(`${BASE}/${key}`, { value });
  },
};
