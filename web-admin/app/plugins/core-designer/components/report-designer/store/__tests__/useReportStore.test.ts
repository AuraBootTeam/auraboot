/**
 * Unit tests for useReportStore
 * Tests block CRUD, metadata updates, undo/redo, data sources,
 * header/footer, preview mode, and page settings.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';

// ── Hoist service mocks ───────────────────────────────────────────────────
const { loadByPageKeyMock, loadByPidMock, saveMock } = vi.hoisted(() => ({
  loadByPageKeyMock: vi.fn(),
  loadByPidMock: vi.fn(),
  saveMock: vi.fn(),
}));

vi.mock('../../services/reportDesignerService', () => ({
  reportDesignerService: {
    loadByPageKey: loadByPageKeyMock,
    loadByPid: loadByPidMock,
    save: saveMock,
  },
}));

import { useReportStore } from '../useReportStore';
import { createEmptyReport } from '../../types';
import type { ReportDsl } from '../../types';

// ── Helpers ────────────────────────────────────────────────────────────────

function makeServiceResult(title = 'Loaded Report'): { dsl: ReportDsl; pid: string } {
  return { dsl: createEmptyReport(title), pid: 'page-pid-1' };
}

function textBlock(content = 'Hello') {
  return {
    blockType: 'rich-text' as const,
    content,
    align: 'left' as const,
  };
}

describe('useReportStore', () => {
  beforeEach(() => {
    useReportStore.getState().reset();
    vi.clearAllMocks();
  });

  // ── Initial state ─────────────────────────────────────────────────────────

  it('starts with null report and clean flags', () => {
    const state = useReportStore.getState();
    expect(state.report).toBeNull();
    expect(state.pageId).toBeNull();
    expect(state.isDirty).toBe(false);
    expect(state.isLoading).toBe(false);
    expect(state.isSaving).toBe(false);
    expect(state.previewMode).toBe(false);
    expect(state.historyIndex).toBe(-1);
  });

  // ── createReport ──────────────────────────────────────────────────────────

  describe('createReport', () => {
    it('creates a report with given title and sets isDirty', () => {
      useReportStore.getState().createReport('Annual Summary');
      const state = useReportStore.getState();

      expect(state.report?.title).toBe('Annual Summary');
      expect(state.report?.body).toHaveLength(0);
      expect(state.isDirty).toBe(true);
      expect(state.pageId).toBeNull();
      expect(state.historyIndex).toBe(0);
    });
  });

  // ── loadReport ────────────────────────────────────────────────────────────

  describe('loadReport', () => {
    it('loads a report by pageKey and clears dirty', async () => {
      loadByPageKeyMock.mockResolvedValue(makeServiceResult('Loaded'));

      await useReportStore.getState().loadReport('report/summary');
      const state = useReportStore.getState();

      expect(loadByPageKeyMock).toHaveBeenCalledWith('report/summary');
      expect(state.report?.title).toBe('Loaded');
      expect(state.pageId).toBe('page-pid-1');
      expect(state.isDirty).toBe(false);
      expect(state.isLoading).toBe(false);
      expect(state.historyIndex).toBe(0);
    });

    it('clears isLoading and rethrows on error', async () => {
      loadByPageKeyMock.mockRejectedValue(new Error('Page not found'));

      await expect(useReportStore.getState().loadReport('missing')).rejects.toThrow(
        'Page not found',
      );
      expect(useReportStore.getState().isLoading).toBe(false);
    });
  });

  // ── loadReportById ────────────────────────────────────────────────────────

  describe('loadReportById', () => {
    it('loads a report by pid', async () => {
      loadByPidMock.mockResolvedValue(makeServiceResult('By PID'));

      await useReportStore.getState().loadReportById('page-pid-1');
      const state = useReportStore.getState();

      expect(state.report?.title).toBe('By PID');
      expect(state.pageId).toBe('page-pid-1');
      expect(state.isDirty).toBe(false);
    });

    it('rethrows error and clears isLoading', async () => {
      loadByPidMock.mockRejectedValue(new Error('Not found'));
      await expect(useReportStore.getState().loadReportById('bad-pid')).rejects.toThrow();
      expect(useReportStore.getState().isLoading).toBe(false);
    });
  });

  // ── addBlock ──────────────────────────────────────────────────────────────

  describe('addBlock', () => {
    it('adds a block, selects it, marks dirty, pushes history', () => {
      useReportStore.getState().createReport('Report');
      useReportStore.getState().setDirty(false);

      const id = useReportStore.getState().addBlock(textBlock());
      const state = useReportStore.getState();

      expect(typeof id).toBe('string');
      expect(id.startsWith('block_')).toBe(true);
      expect(state.report?.body).toHaveLength(1);
      expect(state.report?.body[0].id).toBe(id);
      expect(state.selectedBlockId).toBe(id);
      expect(state.isDirty).toBe(true);
    });

    it('is a no-op when report is null', () => {
      const id = useReportStore.getState().addBlock(textBlock());
      expect(id.startsWith('block_')).toBe(true);
      // report is null, so block is not stored
      expect(useReportStore.getState().report).toBeNull();
    });
  });

  // ── updateBlock ───────────────────────────────────────────────────────────

  describe('updateBlock', () => {
    it('updates an existing block', () => {
      useReportStore.getState().createReport('Report');
      const id = useReportStore.getState().addBlock(textBlock('Hello'));

      useReportStore.getState().updateBlock(id, { content: 'Updated' });

      const block = useReportStore.getState().getBlockById(id);
      expect((block as any).content).toBe('Updated');
    });

    it('is a no-op for unknown block id', () => {
      useReportStore.getState().createReport('Report');
      const id = useReportStore.getState().addBlock(textBlock());
      const before = useReportStore.getState().report?.body.length;

      useReportStore.getState().updateBlock('nope', { content: 'X' });

      expect(useReportStore.getState().report?.body.length).toBe(before);
    });
  });

  // ── removeBlock ───────────────────────────────────────────────────────────

  describe('removeBlock', () => {
    it('removes the block and clears selection if it was selected', () => {
      useReportStore.getState().createReport('Report');
      const id = useReportStore.getState().addBlock(textBlock());

      useReportStore.getState().removeBlock(id);

      expect(useReportStore.getState().report?.body).toHaveLength(0);
      expect(useReportStore.getState().selectedBlockId).toBeNull();
    });

    it('does NOT clear selection when removing a different block', () => {
      useReportStore.getState().createReport('Report');
      const id1 = useReportStore.getState().addBlock(textBlock('A'));
      const id2 = useReportStore.getState().addBlock(textBlock('B'));
      useReportStore.getState().selectBlock(id1);

      useReportStore.getState().removeBlock(id2);

      expect(useReportStore.getState().selectedBlockId).toBe(id1);
    });
  });

  // ── moveBlock ─────────────────────────────────────────────────────────────

  describe('moveBlock', () => {
    it('moves block up', () => {
      useReportStore.getState().createReport('Report');
      const id1 = useReportStore.getState().addBlock(textBlock('A'));
      const id2 = useReportStore.getState().addBlock(textBlock('B'));

      useReportStore.getState().moveBlock(id2, 'up');

      const body = useReportStore.getState().report?.body!;
      expect(body[0].id).toBe(id2);
      expect(body[1].id).toBe(id1);
    });

    it('moves block down', () => {
      useReportStore.getState().createReport('Report');
      const id1 = useReportStore.getState().addBlock(textBlock('A'));
      const id2 = useReportStore.getState().addBlock(textBlock('B'));

      useReportStore.getState().moveBlock(id1, 'down');

      const body = useReportStore.getState().report?.body!;
      expect(body[0].id).toBe(id2);
      expect(body[1].id).toBe(id1);
    });

    it('is a no-op for first block moved up', () => {
      useReportStore.getState().createReport('Report');
      const id1 = useReportStore.getState().addBlock(textBlock('A'));
      const id2 = useReportStore.getState().addBlock(textBlock('B'));

      useReportStore.getState().moveBlock(id1, 'up'); // already first

      const body = useReportStore.getState().report?.body!;
      expect(body[0].id).toBe(id1);
    });
  });

  // ── reorderBlock ──────────────────────────────────────────────────────────

  describe('reorderBlock', () => {
    it('moves block to target index', () => {
      useReportStore.getState().createReport('Report');
      const id1 = useReportStore.getState().addBlock(textBlock('A'));
      const id2 = useReportStore.getState().addBlock(textBlock('B'));
      const id3 = useReportStore.getState().addBlock(textBlock('C'));

      useReportStore.getState().reorderBlock(id3, 0); // move C to front

      const body = useReportStore.getState().report?.body!;
      expect(body[0].id).toBe(id3);
      expect(body[1].id).toBe(id1);
    });

    it('is a no-op when source index equals target', () => {
      useReportStore.getState().createReport('Report');
      const id1 = useReportStore.getState().addBlock(textBlock('A'));
      const id2 = useReportStore.getState().addBlock(textBlock('B'));
      const histIdx = useReportStore.getState().historyIndex;

      useReportStore.getState().reorderBlock(id1, 0); // already at index 0

      expect(useReportStore.getState().historyIndex).toBe(histIdx); // no history push
    });
  });

  // ── selectBlock ───────────────────────────────────────────────────────────

  describe('selectBlock', () => {
    it('sets selectedBlockId', () => {
      useReportStore.getState().createReport('Report');
      const id = useReportStore.getState().addBlock(textBlock());

      useReportStore.getState().selectBlock(id);
      expect(useReportStore.getState().selectedBlockId).toBe(id);

      useReportStore.getState().selectBlock(null);
      expect(useReportStore.getState().selectedBlockId).toBeNull();
    });
  });

  // ── updateTitle / updateDescription ──────────────────────────────────────

  describe('updateTitle', () => {
    it('updates report title and marks dirty', () => {
      useReportStore.getState().createReport('Old');
      useReportStore.getState().setDirty(false);

      useReportStore.getState().updateTitle('New Title');

      const state = useReportStore.getState();
      expect(state.report?.title).toBe('New Title');
      expect(state.isDirty).toBe(true);
    });
  });

  describe('updateDescription', () => {
    it('updates report description and marks dirty', () => {
      useReportStore.getState().createReport('Report');
      useReportStore.getState().setDirty(false);

      useReportStore.getState().updateDescription('A new description');

      expect(useReportStore.getState().report?.description).toBe('A new description');
      expect(useReportStore.getState().isDirty).toBe(true);
    });
  });

  // ── updatePageSettings ────────────────────────────────────────────────────

  describe('updatePageSettings', () => {
    it('merges page settings', () => {
      useReportStore.getState().createReport('Report');
      useReportStore.getState().updatePageSettings({ orientation: 'landscape', size: 'A3' });

      const page = useReportStore.getState().report?.page;
      expect(page?.orientation).toBe('landscape');
      expect(page?.size).toBe('A3');
      // margin preserved
      expect(page?.margin).toBeDefined();
    });
  });

  // ── updateHeader / updateFooter ───────────────────────────────────────────

  describe('updateHeader / updateFooter', () => {
    it('sets header band', () => {
      useReportStore.getState().createReport('Report');
      const header = { height: 40, elements: [] };

      useReportStore.getState().updateHeader(header);
      expect(useReportStore.getState().report?.header).toEqual(header);
      expect(useReportStore.getState().isDirty).toBe(true);
    });

    it('removes header when set to undefined', () => {
      useReportStore.getState().createReport('Report');
      useReportStore.getState().updateHeader({ height: 40, elements: [] });
      useReportStore.getState().updateHeader(undefined);

      expect(useReportStore.getState().report?.header).toBeUndefined();
    });

    it('sets footer band', () => {
      useReportStore.getState().createReport('Report');
      const footer = { height: 30, elements: [] };

      useReportStore.getState().updateFooter(footer);
      expect(useReportStore.getState().report?.footer).toEqual(footer);
    });
  });

  // ── addDataSource / removeDataSource ──────────────────────────────────────

  describe('data sources', () => {
    it('adds a data source by key', () => {
      useReportStore.getState().createReport('Report');
      const ds = { type: 'model' as const, modelCode: 'Order' };

      useReportStore.getState().addDataSource('orders', ds);

      expect(useReportStore.getState().report?.dataSources['orders']).toEqual(ds);
      expect(useReportStore.getState().isDirty).toBe(true);
    });

    it('removes a data source by key', () => {
      useReportStore.getState().createReport('Report');
      useReportStore.getState().addDataSource('orders', { type: 'model', modelCode: 'Order' });

      useReportStore.getState().removeDataSource('orders');

      expect(useReportStore.getState().report?.dataSources['orders']).toBeUndefined();
    });
  });

  // ── undo / redo ───────────────────────────────────────────────────────────

  describe('undo / redo', () => {
    it('canUndo is false right after createReport', () => {
      useReportStore.getState().createReport('Report');
      expect(useReportStore.getState().canUndo()).toBe(false);
    });

    it('canUndo is true after addBlock', () => {
      useReportStore.getState().createReport('Report');
      useReportStore.getState().addBlock(textBlock());
      expect(useReportStore.getState().canUndo()).toBe(true);
    });

    it('undo removes the last added block', () => {
      useReportStore.getState().createReport('Report');
      useReportStore.getState().addBlock(textBlock());
      expect(useReportStore.getState().report?.body).toHaveLength(1);

      useReportStore.getState().undo();
      expect(useReportStore.getState().report?.body).toHaveLength(0);
      expect(useReportStore.getState().canRedo()).toBe(true);
    });

    it('redo re-applies the block', () => {
      useReportStore.getState().createReport('Report');
      useReportStore.getState().addBlock(textBlock());
      useReportStore.getState().undo();

      useReportStore.getState().redo();
      expect(useReportStore.getState().report?.body).toHaveLength(1);
    });

    it('undo is a no-op at historyIndex 0', () => {
      useReportStore.getState().createReport('Report');
      const before = useReportStore.getState().historyIndex;

      useReportStore.getState().undo();
      expect(useReportStore.getState().historyIndex).toBe(before);
    });
  });

  // ── saveReport ────────────────────────────────────────────────────────────

  describe('saveReport', () => {
    it('throws when no report is loaded', async () => {
      await expect(useReportStore.getState().saveReport()).rejects.toThrow('No report to save');
    });

    it('saves new report and sets pageId', async () => {
      useReportStore.getState().createReport('New Report');
      saveMock.mockResolvedValue('saved-page-pid');

      const pid = await useReportStore.getState().saveReport();

      expect(saveMock).toHaveBeenCalledWith(expect.objectContaining({ title: 'New Report' }), undefined);
      expect(pid).toBe('saved-page-pid');
      expect(useReportStore.getState().pageId).toBe('saved-page-pid');
      expect(useReportStore.getState().isDirty).toBe(false);
      expect(useReportStore.getState().isSaving).toBe(false);
    });

    it('passes existing pageId as the second arg when updating', async () => {
      loadByPageKeyMock.mockResolvedValue(makeServiceResult('Existing'));
      await useReportStore.getState().loadReport('existing-key');
      saveMock.mockResolvedValue('page-pid-1');

      await useReportStore.getState().saveReport();

      expect(saveMock).toHaveBeenCalledWith(expect.any(Object), 'page-pid-1');
    });

    it('clears isSaving and rethrows on error', async () => {
      useReportStore.getState().createReport('Report');
      saveMock.mockRejectedValue(new Error('Save failed'));

      await expect(useReportStore.getState().saveReport()).rejects.toThrow('Save failed');
      expect(useReportStore.getState().isSaving).toBe(false);
    });
  });

  // ── setPreviewMode / setDirty / reset ─────────────────────────────────────

  describe('setPreviewMode', () => {
    it('toggles preview mode', () => {
      useReportStore.getState().setPreviewMode(true);
      expect(useReportStore.getState().previewMode).toBe(true);
      useReportStore.getState().setPreviewMode(false);
      expect(useReportStore.getState().previewMode).toBe(false);
    });
  });

  describe('setDirty', () => {
    it('sets isDirty flag', () => {
      useReportStore.getState().setDirty(true);
      expect(useReportStore.getState().isDirty).toBe(true);
      useReportStore.getState().setDirty(false);
      expect(useReportStore.getState().isDirty).toBe(false);
    });
  });

  describe('reset', () => {
    it('restores initial state', () => {
      useReportStore.getState().createReport('Board');
      useReportStore.getState().addBlock(textBlock());

      useReportStore.getState().reset();
      const state = useReportStore.getState();

      expect(state.report).toBeNull();
      expect(state.isDirty).toBe(false);
      expect(state.historyIndex).toBe(-1);
    });
  });

  // ── getBlockById ──────────────────────────────────────────────────────────

  describe('getBlockById', () => {
    it('returns block by id', () => {
      useReportStore.getState().createReport('Report');
      const id = useReportStore.getState().addBlock(textBlock('Hello'));
      expect(useReportStore.getState().getBlockById(id)).toBeDefined();
    });

    it('returns undefined for unknown id', () => {
      useReportStore.getState().createReport('Report');
      expect(useReportStore.getState().getBlockById('nope')).toBeUndefined();
    });

    it('returns undefined when report is null', () => {
      expect(useReportStore.getState().getBlockById('any')).toBeUndefined();
    });
  });
});
