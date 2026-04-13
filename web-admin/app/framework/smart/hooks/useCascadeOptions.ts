// web-admin/app/smart/hooks/useCascadeOptions.ts
/**
 * useCascadeOptions Hook
 *
 * Hook for loading cascading select options from the backend API.
 * Supports async loading of children and path resolution.
 */

import { useState, useCallback } from 'react';
import { get } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { CascadeOption } from '~/components/smart/picker/CascadeSelect';

/**
 * Options for the useCascadeOptions hook
 */
export interface UseCascadeOptionsOptions {
  /** Dictionary code for the cascade data */
  dictCode: string;
}

/**
 * Return type for the useCascadeOptions hook
 */
export interface UseCascadeOptionsResult {
  /** Load children options for a parent value */
  loadChildren: (parentValue: string | null, level: number) => Promise<CascadeOption[]>;
  /** Get the full path for a value */
  getPath: (value: string) => Promise<CascadeOption[]>;
  /** Loading state */
  loading: boolean;
  /** Error state */
  error: Error | null;
}

/**
 * Hook for loading cascade select options
 *
 * @param options - Hook configuration options
 * @returns Object containing loadChildren, getPath, loading state, and error
 *
 * @example
 * // Basic usage
 * const { loadChildren, getPath, loading, error } = useCascadeOptions({
 *   dictCode: 'region',
 * });
 *
 * // Load root level options
 * const rootOptions = await loadChildren(null, 0);
 *
 * // Load children for a parent
 * const childOptions = await loadChildren('province_01', 1);
 *
 * // Get path for a leaf value
 * const path = await getPath('district_001');
 * // Returns: [{ value: 'province_01', label: 'Province 1' }, { value: 'city_01', label: 'City 1' }, ...]
 */
export function useCascadeOptions(options: UseCascadeOptionsOptions): UseCascadeOptionsResult {
  const { dictCode } = options;

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  /**
   * Load children options for a parent value
   *
   * @param parentValue - Parent value to load children for (null for root level)
   * @param _level - Current level (0-indexed, for future use)
   * @returns Promise resolving to array of cascade options
   */
  const loadChildren = useCallback(
    async (parentValue: string | null, _level: number): Promise<CascadeOption[]> => {
      setLoading(true);
      setError(null);

      try {
        const params = new URLSearchParams();
        params.set('dictCode', dictCode);
        if (parentValue) {
          params.set('parentValue', parentValue);
        }

        const result = await get<CascadeOption[]>(`/api/meta/cascade/options?${params}`);

        if (!ResultHelper.isSuccess(result) || !result.data) {
          throw new Error(result.desc || 'Failed to load options');
        }

        return result.data;
      } catch (err) {
        const error = err instanceof Error ? err : new Error('Failed to load options');
        setError(error);
        return [];
      } finally {
        setLoading(false);
      }
    },
    [dictCode],
  );

  /**
   * Get the full path for a value
   *
   * This returns all ancestor options from root to the specified value,
   * useful for displaying breadcrumb or initializing select values.
   *
   * @param value - The value to get the path for
   * @returns Promise resolving to array of cascade options representing the path
   */
  const getPath = useCallback(
    async (value: string): Promise<CascadeOption[]> => {
      setLoading(true);
      setError(null);

      try {
        const params = new URLSearchParams();
        params.set('dictCode', dictCode);
        params.set('value', value);

        const result = await get<CascadeOption[]>(`/api/meta/cascade/path?${params}`);

        if (!ResultHelper.isSuccess(result) || !result.data) {
          throw new Error(result.desc || 'Failed to load path');
        }

        return result.data;
      } catch (err) {
        const error = err instanceof Error ? err : new Error('Failed to load path');
        setError(error);
        return [];
      } finally {
        setLoading(false);
      }
    },
    [dictCode],
  );

  return {
    loadChildren,
    getPath,
    loading,
    error,
  };
}

export default useCascadeOptions;
