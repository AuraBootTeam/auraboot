import { internalRequest } from '~/shared/services/http-client/internal-api';
import { dictionaryData } from '~/shared/services/dict-adapter';
/**
 * useDictCache — parallel dictionary data loading and caching
 *
 * Scans columns/fields for dictCode bindings and loads all needed
 * dictionary data in parallel. Provides a lookup function.
 */

import { useState, useEffect, useRef, useCallback } from 'react';

type DictItem = Pick<import('~/types/dict').DictItemData, 'value' | 'label' | 'extension'>;

interface UseDictCacheOptions {
  /** Dict codes to preload */
  dictCodes: string[];
  /** Auth token */
  token?: string;
}

interface UseDictCacheResult {
  /** Whether all dicts have been loaded */
  loaded: boolean;
  /** Get dict items by code */
  getDictItems: (code: string) => DictItem[];
  /** Look up a label for a value */
  getDictLabel: (code: string, value: string) => string | undefined;
  /** The underlying cache map */
  cache: Map<string, DictItem[]>;
}

export function useDictCache({ dictCodes, token }: UseDictCacheOptions): UseDictCacheResult {
  const cacheRef = useRef<Map<string, DictItem[]>>(new Map());
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    if (dictCodes.length === 0) {
      setLoaded(true);
      return;
    }

    const unloadedCodes = dictCodes.filter((code) => !cacheRef.current.has(code));
    if (unloadedCodes.length === 0) {
      setLoaded(true);
      return;
    }

    // Reset loaded to false so that when setLoaded(true) is called after fetch,
    // React detects a state change (false→true) and triggers a re-render.
    // Without this, if loaded was already true (e.g. from an earlier empty dictCodes),
    // setLoaded(true) is a no-op and consumers never re-render with fresh dict data.
    setLoaded(false);

    const loadAll = async () => {
      const promises = unloadedCodes.map(async (code) => {
        try {
          const result = await internalRequest('get', '/api/meta/dict/by-code/{code}/data', {
            path: { code },
            options: { token },
          });
          if (result.code === '0' && result.data) {
            cacheRef.current.set(code, dictionaryData(result.data).items);
          }
        } catch (error) {
          console.error(`[useDictCache] Failed to load dict: ${code}`, error);
        }
      });
      await Promise.all(promises);
      setLoaded(true);
    };

    loadAll();
  }, [dictCodes.join(','), token]);

  // Include `loaded` in deps so function reference changes when data arrives,
  // forcing consumers to re-render with fresh dict data
  const getDictItems = useCallback(
    (code: string): DictItem[] => {
      return cacheRef.current.get(code) || [];
    },
    [loaded],
  );

  const getDictLabel = useCallback(
    (code: string, value: string): string | undefined => {
      const items = cacheRef.current.get(code);
      if (!items) return undefined;
      const item = items.find((i) => String(i.value) === String(value));
      return item?.label;
    },
    [loaded],
  );

  return {
    loaded,
    getDictItems,
    getDictLabel,
    cache: cacheRef.current,
  };
}
