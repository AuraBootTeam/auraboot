/**
 * useDesignerDocument — the reusable block-tree document + history kernel.
 *
 * This hook is the "node-tree store" the designer-convergence backlog
 * (DDR-2026-06-18) calls for: a single, surface-agnostic owner of the
 * PageSchemaV3 document plus its undo/redo history. It is extracted verbatim
 * from UnifiedDesignerWorkbench so the page designer and the report designer
 * (block-tree family) share ONE history kernel instead of each reinventing it.
 *
 * These tests pin the exact semantics the Workbench relied on so the extraction
 * is provably behavior-preserving.
 */
import { describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import type { PageSchemaV3 } from '../types';
import { useDesignerDocument, serializeDocument } from '../document/useDesignerDocument';

function baseDoc(title = 'P'): PageSchemaV3 {
  return {
    schemaVersion: 3,
    kind: 'detail',
    id: 'p1',
    title: { 'en-US': title },
    blocks: [{ id: 'root', blockType: 'detail-container', blocks: [] }],
  };
}

describe('useDesignerDocument', () => {
  it('starts on the initial document with no undo/redo available', () => {
    const initial = baseDoc();
    const { result } = renderHook(() => useDesignerDocument({ initialDocument: initial }));

    expect(result.current.document).toEqual(initial);
    expect(result.current.canUndo).toBe(false);
    expect(result.current.canRedo).toBe(false);
    expect(result.current.currentSnapshot).toBe(serializeDocument(initial));
  });

  it('applies an edit, pushes a history entry, and notifies onChange with the new snapshot', () => {
    const onChange = vi.fn();
    const { result } = renderHook(() =>
      useDesignerDocument({ initialDocument: baseDoc(), onChange }),
    );

    act(() => {
      result.current.update((current: PageSchemaV3) => ({ ...current, title: { 'en-US': 'Renamed' } }));
    });

    expect(result.current.document.title).toEqual({ 'en-US': 'Renamed' });
    expect(result.current.canUndo).toBe(true);
    expect(result.current.canRedo).toBe(false);
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith(serializeDocument(result.current.document));
  });

  it('treats a no-op edit (identical serialization) as nothing happened', () => {
    const onChange = vi.fn();
    const { result } = renderHook(() =>
      useDesignerDocument({ initialDocument: baseDoc(), onChange }),
    );

    act(() => {
      // structurally new object, identical serialization → must not push history
      result.current.update((current: PageSchemaV3) => ({ ...current }));
    });

    expect(result.current.canUndo).toBe(false);
    expect(onChange).not.toHaveBeenCalled();
  });

  it('undo/redo navigate the history and keep canUndo/canRedo coherent', () => {
    const onChange = vi.fn();
    const { result } = renderHook(() =>
      useDesignerDocument({ initialDocument: baseDoc('A'), onChange }),
    );

    act(() => {
      result.current.update((c: PageSchemaV3) => ({ ...c, title: { 'en-US': 'B' } }));
    });
    expect(result.current.document.title).toEqual({ 'en-US': 'B' });

    act(() => result.current.undo());
    expect(result.current.document.title).toEqual({ 'en-US': 'A' });
    expect(result.current.canUndo).toBe(false);
    expect(result.current.canRedo).toBe(true);

    act(() => result.current.redo());
    expect(result.current.document.title).toEqual({ 'en-US': 'B' });
    expect(result.current.canUndo).toBe(true);
    expect(result.current.canRedo).toBe(false);

    // onChange fired for: update, undo, redo
    expect(onChange).toHaveBeenCalledTimes(3);
  });

  it('truncates the redo branch when editing after an undo', () => {
    const { result } = renderHook(() => useDesignerDocument({ initialDocument: baseDoc('A') }));

    act(() => result.current.update((c: PageSchemaV3) => ({ ...c, title: { 'en-US': 'B' } })));
    act(() => result.current.update((c: PageSchemaV3) => ({ ...c, title: { 'en-US': 'C' } })));
    act(() => result.current.undo()); // back to B
    expect(result.current.document.title).toEqual({ 'en-US': 'B' });
    expect(result.current.canRedo).toBe(true);

    act(() => result.current.update((c: PageSchemaV3) => ({ ...c, title: { 'en-US': 'D' } })));
    expect(result.current.document.title).toEqual({ 'en-US': 'D' });
    expect(result.current.canRedo).toBe(false); // C was discarded

    act(() => result.current.redo()); // no-op
    expect(result.current.document.title).toEqual({ 'en-US': 'D' });
  });

  it('caps history at maxHistory, dropping the oldest entries', () => {
    const { result } = renderHook(() =>
      useDesignerDocument({ initialDocument: baseDoc('0'), maxHistory: 3 }),
    );

    for (let i = 1; i <= 5; i += 1) {
      act(() => result.current.update((c: PageSchemaV3) => ({ ...c, title: { 'en-US': String(i) } })));
    }
    expect(result.current.document.title).toEqual({ 'en-US': '5' });

    // Only 3 snapshots retained → can step back exactly twice (index 2 → 0).
    act(() => result.current.undo());
    act(() => result.current.undo());
    expect(result.current.canUndo).toBe(false);
    expect(result.current.document.title).toEqual({ 'en-US': '3' });
  });

  it('reset replaces the document and clears history (reload / version-rollback path)', () => {
    const { result } = renderHook(() => useDesignerDocument({ initialDocument: baseDoc('A') }));

    act(() => result.current.update((c: PageSchemaV3) => ({ ...c, title: { 'en-US': 'B' } })));
    expect(result.current.canUndo).toBe(true);

    const reloaded = baseDoc('Reloaded');
    act(() => result.current.reset(reloaded));

    expect(result.current.document).toEqual(reloaded);
    expect(result.current.canUndo).toBe(false);
    expect(result.current.canRedo).toBe(false);
    expect(result.current.currentSnapshot).toBe(serializeDocument(reloaded));
  });

  it('exposes stable mutator identities across re-renders', () => {
    const { result, rerender } = renderHook(
      ({ doc }: { doc: PageSchemaV3 }) => useDesignerDocument({ initialDocument: doc }),
      { initialProps: { doc: baseDoc() } },
    );
    const first = {
      update: result.current.update,
      undo: result.current.undo,
      redo: result.current.redo,
      reset: result.current.reset,
    };
    rerender({ doc: baseDoc() });
    expect(result.current.update).toBe(first.update);
    expect(result.current.undo).toBe(first.undo);
    expect(result.current.redo).toBe(first.redo);
    expect(result.current.reset).toBe(first.reset);
  });
});
