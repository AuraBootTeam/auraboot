import { useEffect, useMemo, useRef } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getPublicRecordKey } from '~/framework/meta/utils/publicRecordId';
import { writeRuntimeState } from '../../blocks/workbenchBlockUtils';
import { createSelectionModel, type SelectionState } from './selectionModel';

interface ListSelectionBindingInput {
  runtime: SchemaRuntime | null;
  tableBlock: any;
  data: Record<string, any>[];
  selectionState: SelectionState;
  setSelectionState: Dispatch<SetStateAction<SelectionState>>;
  loading: boolean;
}

/** Expose complete, current explicit selections to existing DSL flows. */
export function useListSelectionBinding({
  runtime,
  tableBlock,
  data,
  selectionState,
  setSelectionState,
  loading,
}: ListSelectionBindingInput): void {
  const config = tableBlock?.table?.selection ?? tableBlock?.selection;
  const bind =
    config?.mode === 'multiple' && typeof config.bind === 'string' ? config.bind.trim() : '';
  const rows = useMemo(() => {
    // Never treat an unloaded cross-page or all-matching set as a single row.
    if (!bind || loading || selectionState.mode !== 'explicit') return [];
    const selected = data.filter((row) => selectionState.ids.has(getPublicRecordKey(row) || ''));
    return selected.length === selectionState.ids.size ? selected : [];
  }, [bind, data, loading, selectionState]);
  const published = useRef<unknown>(undefined);

  useEffect(() => {
    if (!runtime || !bind) return;
    const scope = runtime.getScopeId?.();
    const store = scope ? runtime.getStateManager?.()?.getStore?.(scope) : undefined;
    if (!store?.subscribe) return;
    return store.subscribe(() => {
      const value = runtime.getContext().state?.[bind];
      // state.set([]) from a successful flow or Reload must clear the checkboxes.
      if (value !== published.current && Array.isArray(value) && value.length === 0) {
        setSelectionState((previous) =>
          previous.mode === 'explicit' && previous.ids.size === 0
            ? previous
            : createSelectionModel(),
        );
      }
    });
  }, [runtime, bind, setSelectionState]);

  useEffect(() => {
    if (!runtime || !bind) return;
    published.current = rows;
    writeRuntimeState(runtime, bind, rows);
  }, [runtime, bind, rows]);
}
