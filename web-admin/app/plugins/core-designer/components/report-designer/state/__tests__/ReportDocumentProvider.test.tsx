/**
 * ReportDocumentProvider — behavior-preservation guard.
 *
 * B1 Phase 2b moved the report document / selection / undo-redo history onto the
 * unified-designer kernels. The single most important behavior to preserve (and
 * the one a coarse test can otherwise miss) is the HISTORY SCOPE: the prior
 * zustand `useReportStore` pushed history for the WHOLE `ReportDsl` —
 * header/footer/page/data-source edits were undoable, not just body edits.
 *
 * These tests pin that contract:
 *   - a header edit is undoable and `undo()` restores the previous header,
 *   - a body add is undoable and redoable,
 *   - selection routes to the selection kernel.
 */
import { describe, expect, it } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { ReportDocumentProvider, useReportDocument } from '../ReportDocumentProvider';
import { createEmptyReport, type ReportBand, type ReportDsl, type RichTextBlock } from '../../types';

function richText(content = 'Hello'): Omit<RichTextBlock, 'id'> {
  return { blockType: 'rich-text', content, align: 'left' };
}

describe('ReportDocumentProvider', () => {
  it('makes header edits undoable (whole-ReportDsl history scope)', () => {
    const { result } = renderHook(() => useReportDocument(), {
      wrapper: ReportDocumentProvider,
    });

    // Install a baseline document WITH a header via loadDocument (clean, not dirty).
    const baseHeader: ReportBand = { height: 15, elements: [{ type: 'text', content: 'Original' }] };
    const loaded: ReportDsl = { ...createEmptyReport('Report'), header: baseHeader };
    act(() => result.current.loadDocument(loaded));

    expect(result.current.report?.header).toEqual(baseHeader);
    expect(result.current.canUndo).toBe(false);
    expect(result.current.isDirty).toBe(false);

    // Edit the header — must add a history entry (header edits ARE undoable).
    const newHeader: ReportBand = { height: 20, elements: [{ type: 'text', content: 'Changed' }] };
    act(() => result.current.updateHeader(newHeader));

    expect(result.current.report?.header).toEqual(newHeader);
    expect(result.current.canUndo).toBe(true);
    expect(result.current.isDirty).toBe(true);

    // Undo restores the PREVIOUS header (proves whole-document history scope).
    act(() => result.current.undo());
    expect(result.current.report?.header).toEqual(baseHeader);
    expect(result.current.canRedo).toBe(true);
  });

  it('body add is undoable and redoable', () => {
    const { result } = renderHook(() => useReportDocument(), {
      wrapper: ReportDocumentProvider,
    });

    act(() => result.current.loadDocument(createEmptyReport('Report')));
    expect(result.current.report?.body).toHaveLength(0);
    expect(result.current.canUndo).toBe(false);

    let addedId = '';
    act(() => {
      addedId = result.current.addBlock(richText('Body block'));
    });
    expect(result.current.report?.body).toHaveLength(1);
    expect(result.current.report?.body[0].id).toBe(addedId);
    expect(result.current.selectedBlockId).toBe(addedId);
    expect(result.current.canUndo).toBe(true);

    // Undo removes the added block.
    act(() => result.current.undo());
    expect(result.current.report?.body).toHaveLength(0);
    expect(result.current.canRedo).toBe(true);

    // Redo restores it.
    act(() => result.current.redo());
    expect(result.current.report?.body).toHaveLength(1);
    expect(result.current.report?.body[0].id).toBe(addedId);
  });

  it('selectBlock sets selectedBlockId', () => {
    const { result } = renderHook(() => useReportDocument(), {
      wrapper: ReportDocumentProvider,
    });

    act(() => result.current.loadDocument(createEmptyReport('Report')));

    act(() => result.current.selectBlock('block-xyz'));
    expect(result.current.selectedBlockId).toBe('block-xyz');

    act(() => result.current.selectBlock(null));
    expect(result.current.selectedBlockId).toBeNull();
  });

  it('updateTitle does NOT add a history entry (non-undoable, preserving prior behavior)', () => {
    const { result } = renderHook(() => useReportDocument(), {
      wrapper: ReportDocumentProvider,
    });

    act(() => result.current.loadDocument(createEmptyReport('Original Title')));
    expect(result.current.canUndo).toBe(false);

    act(() => result.current.updateTitle('Renamed'));
    expect(result.current.report?.title).toBe('Renamed');
    // Title edits were never undoable in the store (no pushHistory) — preserve that.
    expect(result.current.canUndo).toBe(false);
    expect(result.current.isDirty).toBe(true);
  });
});
