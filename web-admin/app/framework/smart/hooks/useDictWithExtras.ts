/**
 * useDictWithExtras — Load dict items with extension fields flattened.
 *
 * Phase 1 Task 3 (CRM Kanban platform enhancements):
 *
 * The platform delivers per-dict-item metadata (column color, terminal-stage
 * marker for won/lost) via the `extension` JSON field on each DictItemData
 * (see `platform/.../DictDataResult.java`). Plugin import wires those
 * properties into `extension.color` / `extension.terminal`.
 *
 * This hook fetches `/api/meta/dict/by-code/{dictCode}/data` and flattens
 * the relevant extension keys onto the returned items so consumers (e.g.
 * SmartKanban column header) can read them as plain top-level fields.
 *
 * Terminal narrowing: only the literal values 'won' | 'lost' are accepted;
 * any other value is normalized to undefined.
 *
 * When `dictCode` is undefined/empty, no request is fired and an empty
 * items array is returned with loading=false (back-compat for callers that
 * may not always have a dict-bound groupBy field).
 */

import { useEffect, useRef, useState } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

export type DictItemTerminal = 'won' | 'lost';

export interface DictItemWithExtras {
  value: string;
  label: string;
  /** Extracted from `extension.color` if present. */
  color?: string;
  /** Extracted from `extension.terminal` if it is exactly 'won' or 'lost'. */
  terminal?: DictItemTerminal;
}

interface RawDictItem {
  value: string;
  label: string;
  extension?: Record<string, unknown> | null;
}

interface UseDictWithExtrasResult {
  items: DictItemWithExtras[];
  loading: boolean;
}

function narrowTerminal(value: unknown): DictItemTerminal | undefined {
  return value === 'won' || value === 'lost' ? value : undefined;
}

function flattenItem(raw: RawDictItem): DictItemWithExtras {
  const ext = raw.extension ?? undefined;
  const color = typeof ext?.color === 'string' ? (ext.color as string) : undefined;
  const terminal = narrowTerminal(ext?.terminal);
  return { value: raw.value, label: raw.label, color, terminal };
}

export function useDictWithExtras(
  dictCode: string | undefined | null,
): UseDictWithExtrasResult {
  const [items, setItems] = useState<DictItemWithExtras[]>([]);
  const [loading, setLoading] = useState(false);
  // Track latest request to avoid races when dictCode changes mid-flight.
  const requestIdRef = useRef(0);

  useEffect(() => {
    if (!dictCode) {
      setItems([]);
      setLoading(false);
      return;
    }

    const requestId = ++requestIdRef.current;
    setLoading(true);

    fetchResult<{ items?: RawDictItem[] }>(
      `/api/meta/dict/by-code/${encodeURIComponent(dictCode)}/data`,
      { method: 'get' },
    )
      .then((result) => {
        if (requestId !== requestIdRef.current) return;
        if (!ResultHelper.isSuccess(result) || !result.data) {
          setItems([]);
          setLoading(false);
          return;
        }
        const raws = Array.isArray(result.data)
          ? (result.data as RawDictItem[])
          : (result.data.items ?? []);
        setItems(raws.map(flattenItem));
        setLoading(false);
      })
      .catch((err) => {
        if (requestId !== requestIdRef.current) return;
        console.error(`[useDictWithExtras] fetch failed for dict "${dictCode}"`, err);
        setItems([]);
        setLoading(false);
      });
  }, [dictCode]);

  return { items, loading };
}
