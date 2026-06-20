/**
 * ReportDocumentProvider — kernel-backed report document + selection state.
 *
 * B1 Phase 2b (report-canvas swap, Option B): the report designer's document,
 * selection and undo/redo history now come from the unified-designer kernels
 * (`useDesignerDocument` + `useDesignerSelection`) instead of being reinvented
 * inside the zustand `useReportStore`. The report KEEPS its own A4/paged-media
 * rendering — this provider only owns STATE.
 *
 * Behavior preservation is the bar. Every mutation reproduces the exact body of
 * the corresponding `useReportStore` action, including its history behavior:
 *   - `addBlock` / `updateBlock` / `removeBlock` / `moveBlock` / `reorderBlock`
 *     and `updatePageSettings` / `updateHeader` / `updateFooter` /
 *     `addDataSource` / `removeDataSource` go through `doc.update(...)`, which
 *     appends ONE history entry (they all called `pushHistory` before).
 *   - `updateTitle` / `updateDescription` mutate WITHOUT a history entry
 *     (preserving the prior no-undo-entry behavior) via `mutateNoHistory`.
 *
 * The document kernel holds the WHOLE `ReportDsl` (not just `report.body`) so the
 * history scope matches the prior store: header/footer/page/data-source edits are
 * undoable, exactly as before.
 */
import React, { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import {
  createEmptyReport,
  generateBlockId,
  type ReportDsl,
  type ReportBlock,
  type ReportBand,
  type ReportDataSource,
  type PageConfig,
} from '../types';
import {
  useDesignerDocument,
  serializeDocument,
} from '../../unified-designer/document/useDesignerDocument';
import { useDesignerSelection } from '../../unified-designer/selection/useDesignerSelection';

export interface ReportDocumentContextValue {
  report: ReportDsl | null;
  selectedBlockId: string | null;
  isDirty: boolean;

  // Block actions
  addBlock: (block: Record<string, unknown>) => string;
  updateBlock: (blockId: string, updates: Record<string, unknown>) => void;
  removeBlock: (blockId: string) => void;
  moveBlock: (blockId: string, direction: 'up' | 'down') => void;
  reorderBlock: (blockId: string, targetIndex: number) => void;
  selectBlock: (blockId: string | null) => void;
  getBlockById: (blockId: string) => ReportBlock | undefined;

  // Page settings
  updatePageSettings: (settings: Partial<PageConfig>) => void;
  updateTitle: (title: string) => void;
  updateDescription: (description: string) => void;

  // Header / footer
  updateHeader: (header: ReportBand | undefined) => void;
  updateFooter: (footer: ReportBand | undefined) => void;

  // Data sources
  addDataSource: (key: string, ds: ReportDataSource) => void;
  removeDataSource: (key: string) => void;

  // History
  undo: () => void;
  redo: () => void;
  canUndo: boolean;
  canRedo: boolean;

  // Lifecycle
  loadDocument: (dsl: ReportDsl) => void;
  markSaved: () => void;
  setDirty: (dirty: boolean) => void;

  /**
   * Mutate the live document WITHOUT adding a history entry (mirrors the store's
   * updateTitle/updateDescription and the ParameterEditor direct-mutation path).
   * The host marks dirty separately when needed.
   */
  mutateNoHistory: (updater: (report: ReportDsl) => ReportDsl) => void;
}

const ReportDocumentContext = createContext<ReportDocumentContextValue | null>(null);

export const ReportDocumentProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  // savedSnapshot mirrors the last persisted document; isDirty is derived from it
  // (matches UnifiedDesignerWorkbench's pattern). A ref backs the synchronous
  // reads that setDirty(false) needs without forcing a re-snapshot.
  const initialReport = useMemo(() => createEmptyReport('Untitled Report'), []);
  const [savedSnapshot, setSavedSnapshot] = useState<string>(() =>
    serializeDocument<ReportDsl>(initialReport),
  );
  const savedSnapshotRef = useRef(savedSnapshot);
  savedSnapshotRef.current = savedSnapshot;

  const doc = useDesignerDocument<ReportDsl>({ initialDocument: initialReport });
  const selection = useDesignerSelection();

  const report = doc.document;
  const currentSnapshot = doc.currentSnapshot;
  const isDirty = currentSnapshot !== savedSnapshot;

  // ── History-bearing mutation (every action that called pushHistory) ─────────
  const update = doc.update;

  // ── No-history mutation (updateTitle / updateDescription / parameters) ──────
  // The kernel's `update` appends a history entry; `mutateNoHistory` advances the
  // live document while leaving the undo/redo stack untouched — exactly what the
  // store's updateTitle/updateDescription (and the ParameterEditor direct write)
  // did: they set the field + dirty WITHOUT calling pushHistory.
  const mutateNoHistory = useCallback(
    (updater: (report: ReportDsl) => ReportDsl) => {
      doc.mutateNoHistory(updater);
    },
    [doc],
  );

  // ── Block actions ───────────────────────────────────────────────────────────
  const addBlock = useCallback(
    (blockData: Record<string, unknown>): string => {
      const blockId = generateBlockId();
      const block: ReportBlock = { ...blockData, id: blockId } as ReportBlock;
      update((r) => ({ ...r, body: [...r.body, block] }));
      selection.setSelectedBlockId(blockId);
      return blockId;
    },
    [update, selection],
  );

  const updateBlock = useCallback(
    (blockId: string, updates: Record<string, unknown>) => {
      update((r) => {
        const idx = r.body.findIndex((b) => b.id === blockId);
        if (idx === -1) return r; // no-op edit (kernel drops it: no history, no dirty)
        const body = r.body.slice();
        body[idx] = { ...body[idx], ...updates } as ReportBlock;
        return { ...r, body };
      });
    },
    [update],
  );

  const removeBlock = useCallback(
    (blockId: string) => {
      update((r) => ({ ...r, body: r.body.filter((b) => b.id !== blockId) }));
      if (selection.selectedBlockId === blockId) selection.setSelectedBlockId(null);
    },
    [update, selection],
  );

  const moveBlock = useCallback(
    (blockId: string, direction: 'up' | 'down') => {
      update((r) => {
        const idx = r.body.findIndex((b) => b.id === blockId);
        if (idx === -1) return r;
        const newIdx = direction === 'up' ? idx - 1 : idx + 1;
        if (newIdx < 0 || newIdx >= r.body.length) return r;
        const body = r.body.slice();
        const [block] = body.splice(idx, 1);
        body.splice(newIdx, 0, block);
        return { ...r, body };
      });
    },
    [update],
  );

  const reorderBlock = useCallback(
    (blockId: string, targetIndex: number) => {
      update((r) => {
        const idx = r.body.findIndex((b) => b.id === blockId);
        if (idx === -1 || idx === targetIndex) return r;
        const body = r.body.slice();
        const [block] = body.splice(idx, 1);
        body.splice(targetIndex, 0, block);
        return { ...r, body };
      });
    },
    [update],
  );

  const selectBlock = useCallback(
    (blockId: string | null) => {
      selection.setSelectedBlockId(blockId);
    },
    [selection],
  );

  const getBlockById = useCallback(
    (blockId: string): ReportBlock | undefined => report.body.find((b) => b.id === blockId),
    [report],
  );

  // ── Page settings ───────────────────────────────────────────────────────────
  const updatePageSettings = useCallback(
    (settings: Partial<PageConfig>) => {
      update((r) => ({ ...r, page: { ...r.page, ...settings } }));
    },
    [update],
  );

  const updateTitle = useCallback(
    (title: string) => {
      mutateNoHistory((r) => ({ ...r, title }));
    },
    [mutateNoHistory],
  );

  const updateDescription = useCallback(
    (description: string) => {
      mutateNoHistory((r) => ({ ...r, description }));
    },
    [mutateNoHistory],
  );

  // ── Header / footer ─────────────────────────────────────────────────────────
  const updateHeader = useCallback(
    (header: ReportBand | undefined) => {
      update((r) => ({ ...r, header }));
    },
    [update],
  );

  const updateFooter = useCallback(
    (footer: ReportBand | undefined) => {
      update((r) => ({ ...r, footer }));
    },
    [update],
  );

  // ── Data sources ────────────────────────────────────────────────────────────
  const addDataSource = useCallback(
    (key: string, ds: ReportDataSource) => {
      update((r) => ({ ...r, dataSources: { ...r.dataSources, [key]: ds } }));
    },
    [update],
  );

  const removeDataSource = useCallback(
    (key: string) => {
      update((r) => {
        const dataSources = { ...r.dataSources };
        delete dataSources[key];
        return { ...r, dataSources };
      });
    },
    [update],
  );

  // ── Lifecycle ───────────────────────────────────────────────────────────────
  const loadDocument = useCallback(
    (dsl: ReportDsl) => {
      doc.reset(dsl);
      const snapshot = serializeDocument<ReportDsl>(dsl);
      setSavedSnapshot(snapshot);
      savedSnapshotRef.current = snapshot;
      selection.setSelectedBlockId(null);
    },
    [doc, selection],
  );

  const markSaved = useCallback(() => {
    const snapshot = doc.currentSnapshot;
    setSavedSnapshot(snapshot);
    savedSnapshotRef.current = snapshot;
  }, [doc]);

  const setDirty = useCallback(
    (dirty: boolean) => {
      if (dirty) {
        // Force dirty by pointing savedSnapshot at a sentinel distinct from the
        // live document (the live snapshot is never a single space).
        setSavedSnapshot(' ');
        savedSnapshotRef.current = ' ';
      } else {
        const snapshot = doc.currentSnapshot;
        setSavedSnapshot(snapshot);
        savedSnapshotRef.current = snapshot;
      }
    },
    [doc],
  );

  const value = useMemo<ReportDocumentContextValue>(
    () => ({
      report,
      selectedBlockId: selection.selectedBlockId,
      isDirty,
      addBlock,
      updateBlock,
      removeBlock,
      moveBlock,
      reorderBlock,
      selectBlock,
      getBlockById,
      updatePageSettings,
      updateTitle,
      updateDescription,
      updateHeader,
      updateFooter,
      addDataSource,
      removeDataSource,
      undo: doc.undo,
      redo: doc.redo,
      canUndo: doc.canUndo,
      canRedo: doc.canRedo,
      loadDocument,
      markSaved,
      setDirty,
      mutateNoHistory,
    }),
    [
      report,
      selection.selectedBlockId,
      isDirty,
      addBlock,
      updateBlock,
      removeBlock,
      moveBlock,
      reorderBlock,
      selectBlock,
      getBlockById,
      updatePageSettings,
      updateTitle,
      updateDescription,
      updateHeader,
      updateFooter,
      addDataSource,
      removeDataSource,
      doc.undo,
      doc.redo,
      doc.canUndo,
      doc.canRedo,
      loadDocument,
      markSaved,
      setDirty,
      mutateNoHistory,
    ],
  );

  return <ReportDocumentContext.Provider value={value}>{children}</ReportDocumentContext.Provider>;
};

export function useReportDocument(): ReportDocumentContextValue {
  const ctx = useContext(ReportDocumentContext);
  if (!ctx) {
    throw new Error('useReportDocument must be used within a ReportDocumentProvider');
  }
  return ctx;
}
