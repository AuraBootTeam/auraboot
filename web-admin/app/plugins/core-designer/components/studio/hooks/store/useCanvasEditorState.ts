/**
 * Canvas Editor State — Zustand store for UI-only state.
 *
 * Deliberately does NOT hold schema/component data.
 * Schema flows through BlocksDesigner → children via props / hook params.
 *
 * Removed from previous DesignerStore:
 *   - pageSchema, layoutConfig, components (→ props/params)
 *   - canUndo, canRedo, undo, redo, reset, executeCommand (→ stubs deleted;
 *     real undo/redo comes from usePageSchemaHistory in PageDesignerEditorImpl)
 *   - all schema/component mutation actions
 */

import { create } from 'zustand';

export interface CanvasEditorState {
  // Selection / hover / drag
  selectedComponentId: string | null;
  hoveredComponentId: string | null;
  draggedComponentId: string | null;

  // Panel tab state (persisted across re-renders)
  leftPanelTab: 'fields' | 'components' | 'outline';
  rightPanelTab: 'properties' | 'actions' | 'linkage' | 'styles';

  // View state
  isPreviewMode: boolean;
  isLoading: boolean;
  error: string | null;

  // Linkage panel selection
  linkageSelectedRuleId: string | null;

  // Actions
  selectComponent: (id: string | null) => void;
  setHoveredComponent: (id: string | null) => void;
  setDraggedComponent: (id: string | null) => void;
  setLeftPanelTab: (tab: 'fields' | 'components' | 'outline') => void;
  setRightPanelTab: (tab: 'properties' | 'actions' | 'linkage' | 'styles') => void;
  setPreviewMode: (on: boolean) => void;
  setLoading: (on: boolean) => void;
  setError: (e: string | null) => void;
  setLinkageSelectedRuleId: (id: string | null) => void;
}

/** @deprecated Use CanvasEditorState */
export type DesignerState = CanvasEditorState;

export const useCanvasEditorState = create<CanvasEditorState>((set) => ({
  selectedComponentId: null,
  hoveredComponentId: null,
  draggedComponentId: null,
  leftPanelTab: 'fields',
  rightPanelTab: 'properties',
  isPreviewMode: false,
  isLoading: false,
  error: null,
  linkageSelectedRuleId: null,

  selectComponent: (id) => set({ selectedComponentId: id }),
  setHoveredComponent: (id) => set({ hoveredComponentId: id }),
  setDraggedComponent: (id) => set({ draggedComponentId: id }),
  setLeftPanelTab: (tab) => set({ leftPanelTab: tab }),
  setRightPanelTab: (tab) => set({ rightPanelTab: tab }),
  setPreviewMode: (on) => set({ isPreviewMode: on }),
  setLoading: (on) => set({ isLoading: on }),
  setError: (e) => set({ error: e }),
  setLinkageSelectedRuleId: (id) => set({ linkageSelectedRuleId: id }),
}));

// Convenience selector hooks
export const useSelectedComponent = () =>
  useCanvasEditorState((s) => s.selectedComponentId);
export const useSelectComponent = () =>
  useCanvasEditorState((s) => s.selectComponent);
export const useLeftPanelTab = () =>
  useCanvasEditorState((s) => s.leftPanelTab);
export const useRightPanelTab = () =>
  useCanvasEditorState((s) => s.rightPanelTab);
export const useSetLeftPanelTab = () =>
  useCanvasEditorState((s) => s.setLeftPanelTab);
export const useSetRightPanelTab = () =>
  useCanvasEditorState((s) => s.setRightPanelTab);
export const useIsPreviewMode = () =>
  useCanvasEditorState((s) => s.isPreviewMode);
export const useIsLoading = () =>
  useCanvasEditorState((s) => s.isLoading);
