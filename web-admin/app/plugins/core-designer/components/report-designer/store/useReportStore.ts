/**
 * Report Designer Store (lifecycle-only)
 *
 * B1 Phase 2b (report-canvas swap): the report DOCUMENT (the ReportDsl body /
 * page / header / footer / data sources), SELECTION and UNDO/REDO HISTORY now
 * live in the unified-designer kernels via `ReportDocumentProvider`
 * (`useReportDocument`). This zustand store keeps ONLY the non-document
 * lifecycle: the persisted page id and the transient saving / loading / preview
 * UI flags. Load/save ORCHESTRATION (service calls + wiring the fetched ReportDsl
 * into the document kernel) lives in `ReportDesigner`.
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';

interface ReportStore {
  pageId: string | null; // ab_page_schema PID

  // UI state
  isSaving: boolean;
  isLoading: boolean;
  previewMode: boolean;

  // Setters
  setPageId: (pageId: string | null) => void;
  setSaving: (saving: boolean) => void;
  setLoading: (loading: boolean) => void;
  setPreviewMode: (preview: boolean) => void;
  reset: () => void;
}

const initialState = {
  pageId: null as string | null,
  isSaving: false,
  isLoading: false,
  previewMode: false,
};

export const useReportStore = create<ReportStore>()(
  subscribeWithSelector(
    immer((set) => ({
      ...initialState,

      setPageId: (pageId) => {
        set((s) => {
          s.pageId = pageId;
        });
      },

      setSaving: (saving) => {
        set((s) => {
          s.isSaving = saving;
        });
      },

      setLoading: (loading) => {
        set((s) => {
          s.isLoading = loading;
        });
      },

      setPreviewMode: (preview) => {
        set((s) => {
          s.previewMode = preview;
        });
      },

      reset: () => {
        set(initialState);
      },
    })),
  ),
);
