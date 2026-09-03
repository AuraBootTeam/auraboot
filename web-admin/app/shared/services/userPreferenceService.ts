import { get, put } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

const BASE = '/api/user-preferences';

export const userPreferenceService = {
  async get<T = unknown>(key: string): Promise<T | null> {
    const result = await get<{ value: T }>(`${BASE}/${key}`);
    return ResultHelper.isSuccess(result) ? (result.data?.value ?? null) : null;
  },

  async set(key: string, value: unknown): Promise<void> {
    // The HTTP client resolves transport failures into an error Result
    // instead of rejecting — surface them so callers can react (e.g. the
    // dashboard tab ordering reverts with a visible failure).
    const result = await put<void>(`${BASE}/${key}`, { value });
    if (!ResultHelper.isSuccess(result)) {
      throw new Error(result.desc || result.message || 'Failed to save preference');
    }
  },
};
