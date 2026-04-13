/**
 * Report Designer Store
 * Zustand store for report state management
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type {
  ReportDsl,
  ReportBlock,
  DataTableBlock,
  GroupedTableBlock,
  StatCardBlock,
  RichTextBlock,
  ReportBand,
  ReportDataSource,
  PageConfig,
} from '../types';
import { createEmptyReport, generateBlockId } from '../types';
import { reportDesignerService } from '../services/reportDesignerService';

interface ReportStore {
  // Report data
  report: ReportDsl | null;
  pageId: string | null; // ab_page_schema PID

  // Selection
  selectedBlockId: string | null;

  // UI state
  isDirty: boolean;
  isSaving: boolean;
  isLoading: boolean;
  previewMode: boolean;

  // Undo/Redo
  history: ReportDsl[];
  historyIndex: number;

  // Actions — Report lifecycle
  loadReport: (pageKey: string) => Promise<void>;
  loadReportById: (pid: string) => Promise<void>;
  createReport: (title: string) => void;
  saveReport: () => Promise<string>;

  // Actions — Blocks
  addBlock: (block: Record<string, unknown>) => string;
  updateBlock: (blockId: string, updates: Record<string, unknown>) => void;
  removeBlock: (blockId: string) => void;
  moveBlock: (blockId: string, direction: 'up' | 'down') => void;
  reorderBlock: (blockId: string, targetIndex: number) => void;
  selectBlock: (blockId: string | null) => void;

  // Actions — Page settings
  updatePageSettings: (settings: Partial<PageConfig>) => void;
  updateTitle: (title: string) => void;
  updateDescription: (description: string) => void;

  // Actions — Header/Footer
  updateHeader: (header: ReportBand | undefined) => void;
  updateFooter: (footer: ReportBand | undefined) => void;

  // Actions — Data sources
  addDataSource: (key: string, ds: ReportDataSource) => void;
  removeDataSource: (key: string) => void;

  // Actions — History
  undo: () => void;
  redo: () => void;
  canUndo: () => boolean;
  canRedo: () => boolean;

  // Actions — UI
  setPreviewMode: (preview: boolean) => void;
  setDirty: (dirty: boolean) => void;
  reset: () => void;

  // Utilities
  getBlockById: (blockId: string) => ReportBlock | undefined;
}

const initialState = {
  report: null as ReportDsl | null,
  pageId: null as string | null,
  selectedBlockId: null as string | null,
  isDirty: false,
  isSaving: false,
  isLoading: false,
  previewMode: false,
  history: [] as ReportDsl[],
  historyIndex: -1,
};

function pushHistory(
  state: typeof initialState & {
    report: ReportDsl | null;
    history: ReportDsl[];
    historyIndex: number;
  },
) {
  if (!state.report) return;
  state.history = state.history.slice(0, state.historyIndex + 1);
  state.history.push(JSON.parse(JSON.stringify(state.report)));
  state.historyIndex = state.history.length - 1;
}

export const useReportStore = create<ReportStore>()(
  subscribeWithSelector(
    immer((set, get) => ({
      ...initialState,

      // ==================== Report Lifecycle ====================

      loadReport: async (pageKey: string) => {
        set((s) => {
          s.isLoading = true;
        });
        try {
          const result = await reportDesignerService.loadByPageKey(pageKey);
          set((s) => {
            s.report = result.dsl;
            s.pageId = result.pid;
            s.isDirty = false;
            s.isLoading = false;
            s.selectedBlockId = null;
            s.history = [JSON.parse(JSON.stringify(result.dsl))];
            s.historyIndex = 0;
          });
        } catch (error) {
          set((s) => {
            s.isLoading = false;
          });
          throw error;
        }
      },

      loadReportById: async (pid: string) => {
        set((s) => {
          s.isLoading = true;
        });
        try {
          const result = await reportDesignerService.loadByPid(pid);
          set((s) => {
            s.report = result.dsl;
            s.pageId = result.pid;
            s.isDirty = false;
            s.isLoading = false;
            s.selectedBlockId = null;
            s.history = [JSON.parse(JSON.stringify(result.dsl))];
            s.historyIndex = 0;
          });
        } catch (error) {
          set((s) => {
            s.isLoading = false;
          });
          throw error;
        }
      },

      createReport: (title: string) => {
        const report = createEmptyReport(title);
        set((s) => {
          s.report = report;
          s.pageId = null;
          s.isDirty = true;
          s.selectedBlockId = null;
          s.history = [JSON.parse(JSON.stringify(report))];
          s.historyIndex = 0;
        });
      },

      saveReport: async () => {
        const state = get();
        if (!state.report) throw new Error('No report to save');

        set((s) => {
          s.isSaving = true;
        });

        try {
          const pid = await reportDesignerService.save(state.report, state.pageId || undefined);
          set((s) => {
            s.pageId = pid;
            s.isDirty = false;
            s.isSaving = false;
          });
          return pid;
        } catch (error) {
          set((s) => {
            s.isSaving = false;
          });
          throw error;
        }
      },

      // ==================== Block Actions ====================

      addBlock: (blockData) => {
        const blockId = generateBlockId();
        const block: ReportBlock = { ...blockData, id: blockId } as ReportBlock;

        set((s) => {
          if (!s.report) return;
          s.report.body.push(block);
          s.selectedBlockId = blockId;
          s.isDirty = true;
          pushHistory(s);
        });

        return blockId;
      },

      updateBlock: (blockId, updates) => {
        set((s) => {
          if (!s.report) return;
          const idx = s.report.body.findIndex((b) => b.id === blockId);
          if (idx !== -1) {
            s.report.body[idx] = { ...s.report.body[idx], ...updates } as any;
            s.isDirty = true;
            pushHistory(s);
          }
        });
      },

      removeBlock: (blockId) => {
        set((s) => {
          if (!s.report) return;
          s.report.body = s.report.body.filter((b) => b.id !== blockId);
          if (s.selectedBlockId === blockId) s.selectedBlockId = null;
          s.isDirty = true;
          pushHistory(s);
        });
      },

      moveBlock: (blockId, direction) => {
        set((s) => {
          if (!s.report) return;
          const idx = s.report.body.findIndex((b) => b.id === blockId);
          if (idx === -1) return;
          const newIdx = direction === 'up' ? idx - 1 : idx + 1;
          if (newIdx < 0 || newIdx >= s.report.body.length) return;
          const [block] = s.report.body.splice(idx, 1);
          s.report.body.splice(newIdx, 0, block);
          s.isDirty = true;
          pushHistory(s);
        });
      },

      reorderBlock: (blockId, targetIndex) => {
        set((s) => {
          if (!s.report) return;
          const idx = s.report.body.findIndex((b) => b.id === blockId);
          if (idx === -1 || idx === targetIndex) return;
          const [block] = s.report.body.splice(idx, 1);
          s.report.body.splice(targetIndex, 0, block);
          s.isDirty = true;
          pushHistory(s);
        });
      },

      selectBlock: (blockId) => {
        set((s) => {
          s.selectedBlockId = blockId;
        });
      },

      // ==================== Page Settings ====================

      updatePageSettings: (settings) => {
        set((s) => {
          if (!s.report) return;
          s.report.page = { ...s.report.page, ...settings };
          s.isDirty = true;
          pushHistory(s);
        });
      },

      updateTitle: (title) => {
        set((s) => {
          if (!s.report) return;
          s.report.title = title;
          s.isDirty = true;
        });
      },

      updateDescription: (description) => {
        set((s) => {
          if (!s.report) return;
          s.report.description = description;
          s.isDirty = true;
        });
      },

      // ==================== Header/Footer ====================

      updateHeader: (header) => {
        set((s) => {
          if (!s.report) return;
          s.report.header = header;
          s.isDirty = true;
          pushHistory(s);
        });
      },

      updateFooter: (footer) => {
        set((s) => {
          if (!s.report) return;
          s.report.footer = footer;
          s.isDirty = true;
          pushHistory(s);
        });
      },

      // ==================== Data Sources ====================

      addDataSource: (key, ds) => {
        set((s) => {
          if (!s.report) return;
          s.report.dataSources[key] = ds;
          s.isDirty = true;
          pushHistory(s);
        });
      },

      removeDataSource: (key) => {
        set((s) => {
          if (!s.report) return;
          delete s.report.dataSources[key];
          s.isDirty = true;
          pushHistory(s);
        });
      },

      // ==================== History ====================

      undo: () => {
        set((s) => {
          if (s.historyIndex > 0) {
            s.historyIndex -= 1;
            s.report = JSON.parse(JSON.stringify(s.history[s.historyIndex]));
            s.isDirty = true;
          }
        });
      },

      redo: () => {
        set((s) => {
          if (s.historyIndex < s.history.length - 1) {
            s.historyIndex += 1;
            s.report = JSON.parse(JSON.stringify(s.history[s.historyIndex]));
            s.isDirty = true;
          }
        });
      },

      canUndo: () => get().historyIndex > 0,
      canRedo: () => {
        const s = get();
        return s.historyIndex < s.history.length - 1;
      },

      // ==================== UI ====================

      setPreviewMode: (preview) => {
        set((s) => {
          s.previewMode = preview;
        });
      },

      setDirty: (dirty) => {
        set((s) => {
          s.isDirty = dirty;
        });
      },

      reset: () => {
        set(initialState);
      },

      // ==================== Utilities ====================

      getBlockById: (blockId) => {
        return get().report?.body.find((b) => b.id === blockId);
      },
    })),
  ),
);
