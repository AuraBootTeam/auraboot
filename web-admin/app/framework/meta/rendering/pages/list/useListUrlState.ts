/**
 * useListUrlState — URL state encoding/decoding for list page sort & filter.
 *
 * Exports pure functions for encoding/decoding sort and filter configs
 * to/from URL-safe string representations.
 */

import { useSearchParams } from 'react-router';
import { useMemo, useCallback } from 'react';
import type { SortConfig } from '~/framework/smart/types/savedView';
import type { ViewFilterConfig } from '~/framework/smart/types/savedView';

// ---------------------------------------------------------------------------
// Pure encoding / decoding helpers (exported for unit testing)
// ---------------------------------------------------------------------------

/**
 * Encode SortConfig[] to a URL-friendly string.
 * Format: "fieldCode:direction,fieldCode:direction,..."
 * Returns null for empty arrays (caller should omit the param).
 */
export function encodeSorts(sorts: SortConfig[]): string | null {
  if (!sorts || sorts.length === 0) return null;
  return sorts.map((s) => `${s.fieldCode}:${s.direction}`).join(',');
}

/**
 * Decode a sort string back to SortConfig[].
 * Returns empty array for falsy / malformed input.
 */
export function decodeSorts(raw: string | null | undefined): SortConfig[] {
  if (!raw) return [];
  return raw
    .split(',')
    .map((segment) => {
      const [fieldCode, direction] = segment.split(':');
      if (!fieldCode || (direction !== 'asc' && direction !== 'desc')) return null;
      return { fieldCode, direction } as SortConfig;
    })
    .filter((s): s is SortConfig => s !== null)
    .map((s, idx) => ({ ...s, priority: idx + 1 }));
}

/**
 * Encode ViewFilterConfig[] to a URL-safe base64 string.
 * Returns null for empty arrays.
 */
export function encodeFilters(filters: ViewFilterConfig[]): string | null {
  if (!filters || filters.length === 0) return null;
  const json = JSON.stringify(filters);
  // btoa works in both browser and Node 18+ (Buffer.from fallback not needed)
  return btoa(json);
}

/**
 * Decode a base64-encoded filter string back to ViewFilterConfig[].
 * Returns empty array for falsy / malformed input (graceful degradation).
 */
export function decodeFilters(raw: string | null | undefined): ViewFilterConfig[] {
  if (!raw) return [];
  try {
    const json = atob(raw);
    const parsed = JSON.parse(json);
    if (!Array.isArray(parsed)) return [];
    return parsed as ViewFilterConfig[];
  } catch {
    return [];
  }
}

// ---------------------------------------------------------------------------
// React hook
// ---------------------------------------------------------------------------

const SORT_PARAM = 'sort';
const FILTER_PARAM = 'filters';

export interface ListUrlState {
  sorts: SortConfig[];
  filters: ViewFilterConfig[];
  setSorts: (sorts: SortConfig[]) => void;
  setFilters: (filters: ViewFilterConfig[]) => void;
}

/**
 * Hook that synchronises sort & filter state with URL search params.
 */
export function useListUrlState(): ListUrlState {
  const [searchParams, setSearchParams] = useSearchParams();

  const sorts = useMemo(() => decodeSorts(searchParams.get(SORT_PARAM)), [searchParams]);
  const filters = useMemo(() => decodeFilters(searchParams.get(FILTER_PARAM)), [searchParams]);

  const setSorts = useCallback(
    (next: SortConfig[]) => {
      setSearchParams(
        (prev) => {
          const p = new URLSearchParams(prev);
          const encoded = encodeSorts(next);
          if (encoded) {
            p.set(SORT_PARAM, encoded);
          } else {
            p.delete(SORT_PARAM);
          }
          return p;
        },
        { replace: true },
      );
    },
    [setSearchParams],
  );

  const setFilters = useCallback(
    (next: ViewFilterConfig[]) => {
      setSearchParams(
        (prev) => {
          const p = new URLSearchParams(prev);
          const encoded = encodeFilters(next);
          if (encoded) {
            p.set(FILTER_PARAM, encoded);
          } else {
            p.delete(FILTER_PARAM);
          }
          return p;
        },
        { replace: true },
      );
    },
    [setSearchParams],
  );

  return { sorts, filters, setSorts, setFilters };
}
