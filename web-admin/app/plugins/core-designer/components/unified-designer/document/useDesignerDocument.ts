/**
 * useDesignerDocument — the reusable block-tree document + history kernel.
 *
 * Extracted verbatim (behavior-preserving) from UnifiedDesignerWorkbench so that
 * every block-tree-family designer surface (page designer today, report designer
 * next — DDR-2026-06-18) shares ONE owner of the document and its undo/redo
 * history instead of each reinventing it.
 *
 * The kernel is generic over the document type `T` (defaulting to `PageSchemaV3`
 * so the unified workbench and its tests are unchanged). The report designer
 * instantiates it as `useDesignerDocument<ReportDsl>` so the WHOLE ReportDsl
 * (page settings / header / footer / data sources / body) shares one history
 * scope — matching the report store's prior whole-document undo/redo.
 *
 * Scope (intentionally narrow): the document state machine ONLY —
 *   - the live document
 *   - the bounded undo/redo history (snapshot stack + cursor)
 *   - edit / undo / redo / reset transitions with no-op de-duplication
 *
 * NOT in scope (stays with the host surface): selection, drag-and-drop, the
 * block registry, save/publish/validation UI state. Those are layered on top of
 * this kernel by the surface (the host syncs its save-status via `onChange`).
 */
import { useCallback, useMemo, useRef, useState } from 'react';
import type { PageSchemaV3 } from '../types';

/** Canonical document serialization (history snapshots + dirty comparison). */
export function serializeDocument<T = PageSchemaV3>(document: T): string {
  return JSON.stringify(document);
}

export function parseDocumentSnapshot<T = PageSchemaV3>(snapshot: string): T {
  return JSON.parse(snapshot) as T;
}

/** Matches the Workbench's prior MAX_DOCUMENT_HISTORY. */
const DEFAULT_MAX_HISTORY = 50;

interface DocumentHistoryState<T> {
  document: T;
  /** Serialized snapshots; `document` always equals the entry at `historyIndex`. */
  history: string[];
  historyIndex: number;
}

export interface UseDesignerDocumentOptions<T = PageSchemaV3> {
  initialDocument: T;
  /** Maximum retained history entries (oldest dropped past the cap). */
  maxHistory?: number;
  /**
   * Notified with the new serialized snapshot whenever the LIVE document changes
   * via edit / undo / redo. NOT called by `reset` (a reload installs a clean
   * baseline that the host marks saved directly). Lets the host sync save-status.
   */
  onChange?: (snapshot: string) => void;
}

export interface DesignerDocumentController<T = PageSchemaV3> {
  document: T;
  /** `serializeDocument(document)` — memoized; useful for dirty comparison. */
  currentSnapshot: string;
  canUndo: boolean;
  canRedo: boolean;
  /**
   * Apply an edit. The updater receives the current document and returns the
   * next one. A result that serializes identically to the current document is a
   * no-op (no history entry, no `onChange`).
   */
  update: (updater: (current: T) => T) => void;
  /**
   * Mutate the LIVE document WITHOUT appending a history entry and WITHOUT
   * perturbing the existing undo/redo stack (the snapshot at `historyIndex`
   * keeps its previous value, so it may diverge from the live document until the
   * next `update`/`undo`/`redo`). Mirrors edits that were intentionally
   * non-undoable in their host (e.g. the report designer's title/description and
   * parameter edits). A result that serializes identically to the current
   * document is a no-op (no `onChange`).
   */
  mutateNoHistory: (updater: (current: T) => T) => void;
  undo: () => void;
  redo: () => void;
  /** Replace the document and collapse history to a single baseline entry. */
  reset: (nextDocument: T) => void;
}

export function useDesignerDocument<T = PageSchemaV3>({
  initialDocument,
  maxHistory = DEFAULT_MAX_HISTORY,
  onChange,
}: UseDesignerDocumentOptions<T>): DesignerDocumentController<T> {
  const [state, setState] = useState<DocumentHistoryState<T>>(() => ({
    document: initialDocument,
    history: [serializeDocument(initialDocument)],
    historyIndex: 0,
  }));

  // Mirror the latest committed state for synchronous reads inside stable
  // mutators (lets several mutators run in one tick without stale closures).
  const stateRef = useRef(state);
  stateRef.current = state;

  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  const maxHistoryRef = useRef(maxHistory);
  maxHistoryRef.current = maxHistory;

  const commit = useCallback((next: DocumentHistoryState<T>, changedSnapshot?: string) => {
    stateRef.current = next;
    setState(next);
    if (changedSnapshot !== undefined) onChangeRef.current?.(changedSnapshot);
  }, []);

  const update = useCallback(
    (updater: (current: T) => T) => {
      const prev = stateRef.current;
      const prevSnapshot = serializeDocument(prev.document);
      const nextDocument = updater(prev.document);
      const nextSnapshot = serializeDocument(nextDocument);
      if (nextSnapshot === prevSnapshot) return; // no-op edit: nothing changed

      const appended = [...prev.history.slice(0, prev.historyIndex + 1), nextSnapshot];
      const trimmed = appended.slice(-maxHistoryRef.current);
      commit(
        { document: nextDocument, history: trimmed, historyIndex: trimmed.length - 1 },
        nextSnapshot,
      );
    },
    [commit],
  );

  const mutateNoHistory = useCallback(
    (updater: (current: T) => T) => {
      const prev = stateRef.current;
      const prevSnapshot = serializeDocument(prev.document);
      const nextDocument = updater(prev.document);
      const nextSnapshot = serializeDocument(nextDocument);
      if (nextSnapshot === prevSnapshot) return; // no-op edit: nothing changed
      // History stack (and its cursor) is left exactly as-is; only the live
      // document advances. The entry at historyIndex may now differ from the
      // live document, which is the intended non-undoable behavior.
      commit({ ...prev, document: nextDocument }, nextSnapshot);
    },
    [commit],
  );

  const undo = useCallback(() => {
    const prev = stateRef.current;
    if (prev.historyIndex <= 0) return;
    const nextIndex = prev.historyIndex - 1;
    const snapshot = prev.history[nextIndex];
    commit({ ...prev, document: parseDocumentSnapshot<T>(snapshot), historyIndex: nextIndex }, snapshot);
  }, [commit]);

  const redo = useCallback(() => {
    const prev = stateRef.current;
    if (prev.historyIndex >= prev.history.length - 1) return;
    const nextIndex = prev.historyIndex + 1;
    const snapshot = prev.history[nextIndex];
    commit({ ...prev, document: parseDocumentSnapshot<T>(snapshot), historyIndex: nextIndex }, snapshot);
  }, [commit]);

  const reset = useCallback(
    (nextDocument: T) => {
      const snapshot = serializeDocument(nextDocument);
      commit({ document: nextDocument, history: [snapshot], historyIndex: 0 });
    },
    [commit],
  );

  const currentSnapshot = useMemo(() => serializeDocument(state.document), [state.document]);

  return {
    document: state.document,
    currentSnapshot,
    canUndo: state.historyIndex > 0,
    canRedo: state.historyIndex < state.history.length - 1,
    update,
    mutateNoHistory,
    undo,
    redo,
    reset,
  };
}
