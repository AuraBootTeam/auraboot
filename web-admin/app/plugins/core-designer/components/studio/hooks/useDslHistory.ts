/**
 * useDslHistory Hook
 *
 * Manages DSL undo/redo history as JSON snapshots.
 * Independent from the Zustand designer store — operates on DslV4Schema directly.
 *
 * @since 4.0.0
 */

import { useState, useCallback, useRef } from 'react';
import type { DslV4Schema } from '~/plugins/core-designer/components/studio/domain/dsl/types';

const MAX_HISTORY = 50;

export interface DslHistoryState {
  canUndo: boolean;
  canRedo: boolean;
}

export interface DslHistoryActions {
  /** Record current DSL state as a new snapshot (truncates redo stack) */
  pushState: (dsl: DslV4Schema) => void;
  /** Undo — returns previous DSL state or null if nothing to undo */
  undo: () => DslV4Schema | null;
  /** Redo — returns next DSL state or null if nothing to redo */
  redo: () => DslV4Schema | null;
}

/**
 * DSL history hook for undo/redo
 *
 * @param initialDsl - The initial DSL to seed history with
 */
export function useDslHistory(initialDsl: DslV4Schema): DslHistoryState & DslHistoryActions {
  // Use refs for the history stack to avoid re-renders on every push
  const historyRef = useRef<string[]>([JSON.stringify(initialDsl)]);
  const indexRef = useRef(0);

  // Track canUndo/canRedo as state to trigger re-renders in toolbar
  const [canUndo, setCanUndo] = useState(false);
  const [canRedo, setCanRedo] = useState(false);

  const syncFlags = useCallback(() => {
    setCanUndo(indexRef.current > 0);
    setCanRedo(indexRef.current < historyRef.current.length - 1);
  }, []);

  const pushState = useCallback(
    (dsl: DslV4Schema) => {
      const serialized = JSON.stringify(dsl);
      const history = historyRef.current;
      const currentIndex = indexRef.current;

      // Skip if identical to current state
      if (history[currentIndex] === serialized) return;

      // Truncate redo stack
      historyRef.current = history.slice(0, currentIndex + 1);
      historyRef.current.push(serialized);

      // Enforce max history size
      if (historyRef.current.length > MAX_HISTORY) {
        historyRef.current = historyRef.current.slice(historyRef.current.length - MAX_HISTORY);
      }

      indexRef.current = historyRef.current.length - 1;
      syncFlags();
    },
    [syncFlags],
  );

  const undo = useCallback((): DslV4Schema | null => {
    if (indexRef.current <= 0) return null;
    indexRef.current -= 1;
    syncFlags();
    return JSON.parse(historyRef.current[indexRef.current]);
  }, [syncFlags]);

  const redo = useCallback((): DslV4Schema | null => {
    if (indexRef.current >= historyRef.current.length - 1) return null;
    indexRef.current += 1;
    syncFlags();
    return JSON.parse(historyRef.current[indexRef.current]);
  }, [syncFlags]);

  return { canUndo, canRedo, pushState, undo, redo };
}

export default useDslHistory;
