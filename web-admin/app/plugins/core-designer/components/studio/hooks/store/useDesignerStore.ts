/**
 * Designer Store - Zustand implementation
 *
 * Provides centralized state management for the designer application
 */

import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';
import { immer } from 'zustand/middleware/immer';
import type { Component, LayoutConfig, FormSchema } from '~/plugins/core-designer/components/studio/domain/schema/types';

export interface DesignerState {
  // Core state
  pageSchema: FormSchema | null;
  layoutConfig: LayoutConfig;
  components: Record<string, Component>;

  // UI state
  selectedComponentId: string | null;
  hoveredComponentId: string | null;
  draggedComponentId: string | null;
  isPreviewMode: boolean;
  isLoading: boolean;

  // Panel tab state (persisted across re-renders)
  leftPanelTab: 'fields' | 'components' | 'outline';
  rightPanelTab: 'properties' | 'actions' | 'linkage' | 'styles';

  // History state
  canUndo: boolean;
  canRedo: boolean;

  // Error state
  error: string | null;

  // Linkage panel state
  linkageSelectedRuleId: string | null;
}

export interface DesignerActions {
  // Schema actions
  setPageSchema: (schema: FormSchema | null) => void;
  updatePageSchema: (updater: (schema: FormSchema) => void) => void;

  // Layout actions
  setLayoutConfig: (config: LayoutConfig) => void;
  updateLayoutConfig: (updater: (config: LayoutConfig) => void) => void;

  // Component actions
  updateComponent: (componentId: string, updates: Partial<Component>) => void;
  addComponent: (component: Component) => void;
  removeComponent: (componentId: string) => void;
  swapComponents: (componentId1: string, componentId2: string) => void;

  // Selection actions
  selectComponent: (componentId: string | null) => void;
  setHoveredComponent: (componentId: string | null) => void;
  setDraggedComponent: (componentId: string | null) => void;

  // UI actions
  setPreviewMode: (isPreview: boolean) => void;
  setLoading: (isLoading: boolean) => void;
  setError: (error: string | null) => void;

  // Panel tab actions
  setLeftPanelTab: (tab: 'fields' | 'components' | 'outline') => void;
  setRightPanelTab: (tab: 'properties' | 'actions' | 'linkage' | 'styles') => void;

  // Linkage actions
  setLinkageSelectedRuleId: (id: string | null) => void;

  // Command actions
  executeCommand: (command: any) => Promise<void>;
  undo: () => void;
  redo: () => void;

  // Reset actions
  reset: () => void;
}

export type DesignerStore = DesignerState & DesignerActions;

const initialState: DesignerState = {
  pageSchema: {
    id: 'default-schema',
    kind: 'home',
    title: 'New Page',
    description: 'Page created with AuraBoot Designer',
    version: '1.0.0',
    components: [],
    layout: {
      type: 'grid',
      columns: 12,
      spacing: 16,
      padding: 16,
    },
    theme: {
      primaryColor: '#3B82F6',
      backgroundColor: '#FFFFFF',
      textColor: '#1F2937',
      borderRadius: 8,
    },
    metadata: {
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      createdBy: 'system',
      tags: [],
    },
  },
  layoutConfig: {
    type: 'grid',
    columns: 12,
    spacing: 16,
    padding: 16,
    breakpoints: {
      xs: { columns: 12, gap: 8 },
      sm: { columns: 12, gap: 12 },
      md: { columns: 6, gap: 16 },
      lg: { columns: 4, gap: 16 },
      xl: { columns: 3, gap: 16 },
      xxl: { columns: 2, gap: 16 },
    },
  },
  components: {},
  selectedComponentId: null,
  hoveredComponentId: null,
  draggedComponentId: null,
  isPreviewMode: false,
  isLoading: false,
  canUndo: false,
  canRedo: false,
  error: null,
  leftPanelTab: 'fields',
  rightPanelTab: 'properties',
  linkageSelectedRuleId: null,
};

export const useDesignerStore = create<DesignerStore>()(
  subscribeWithSelector(
    immer((set, get) => ({
      ...initialState,

      // Schema actions
      setPageSchema: (schema) => {
        set((state) => {
          state.pageSchema = schema;
          if (schema?.components) {
            state.components = schema.components.reduce(
              (acc, comp) => {
                acc[comp.id] = comp;
                return acc;
              },
              {} as Record<string, Component>,
            );
          }
        });
      },

      updatePageSchema: (updater) => {
        set((state) => {
          if (state.pageSchema) {
            updater(state.pageSchema);
          }
        });
      },

      // Layout actions
      setLayoutConfig: (config) => {
        set((state) => {
          state.layoutConfig = config;
        });
      },

      updateLayoutConfig: (updater) => {
        set((state) => {
          updater(state.layoutConfig);
        });
      },

      // Component actions
      updateComponent: (componentId, updates) => {
        set((state) => {
          if (state.components[componentId]) {
            Object.assign(state.components[componentId], updates);

            // Also update in pageSchema if it exists
            if (state.pageSchema?.components) {
              const componentIndex = state.pageSchema.components.findIndex(
                (c) => c.id === componentId,
              );

              if (componentIndex !== -1) {
                Object.assign(state.pageSchema.components[componentIndex], updates);
              }
            }
          }
        });
      },

      addComponent: (component) => {
        set((state) => {
          state.components[component.id] = component;

          // Also add to pageSchema if it exists
          if (state.pageSchema) {
            if (!state.pageSchema.components) {
              state.pageSchema.components = [];
            }
            state.pageSchema.components.push(component);
          }
        });
      },

      removeComponent: (componentId) => {
        set((state) => {
          delete state.components[componentId];

          // Also remove from pageSchema if it exists
          if (state.pageSchema?.components) {
            state.pageSchema.components = state.pageSchema.components.filter(
              (c) => c.id !== componentId,
            );
          }

          // Clear selection if this component was selected
          if (state.selectedComponentId === componentId) {
            state.selectedComponentId = null;
          }
        });
      },

      swapComponents: (componentId1, componentId2) => {
        set((state) => {
          const comp1 = state.components[componentId1];
          const comp2 = state.components[componentId2];

          if (!comp1 || !comp2) {
            return;
          }

          if (!comp1.position || !comp2.position) {
            return;
          }

          // 交换 position
          const tempPosition = { ...comp1.position };
          comp1.position = { ...comp2.position };
          comp2.position = tempPosition;

          // 更新 state.components
          state.components[componentId1] = comp1;
          state.components[componentId2] = comp2;

          // 更新 pageSchema.components
          if (state.pageSchema?.components) {
            const index1 = state.pageSchema.components.findIndex((c) => c.id === componentId1);
            const index2 = state.pageSchema.components.findIndex((c) => c.id === componentId2);

            if (index1 !== -1) {
              (state.pageSchema.components[index1] as any).position = comp1.position;
            }
            if (index2 !== -1) {
              (state.pageSchema.components[index2] as any).position = comp2.position;
            }
          }
        });
      },

      // Selection actions
      selectComponent: (componentId) => {
        set((state) => {
          state.selectedComponentId = componentId;
        });
      },

      setHoveredComponent: (componentId) => {
        set((state) => {
          state.hoveredComponentId = componentId;
        });
      },

      setDraggedComponent: (componentId) => {
        set((state) => {
          state.draggedComponentId = componentId;
        });
      },

      // UI actions
      setPreviewMode: (isPreview) => {
        set((state) => {
          state.isPreviewMode = isPreview;
        });
      },

      setLoading: (isLoading) => {
        set((state) => {
          state.isLoading = isLoading;
        });
      },

      setError: (error) => {
        set((state) => {
          state.error = error;
        });
      },

      // Panel tab actions
      setLeftPanelTab: (tab) => {
        set((state) => {
          state.leftPanelTab = tab;
        });
      },

      setRightPanelTab: (tab) => {
        set((state) => {
          state.rightPanelTab = tab;
        });
      },

      // Linkage actions
      setLinkageSelectedRuleId: (id) => {
        set((state) => {
          state.linkageSelectedRuleId = id;
        });
      },

      // Command actions (placeholder implementations)
      executeCommand: async (command) => {
        // TODO: Implement command execution logic
      },

      undo: () => {
        // TODO: Implement undo logic
        set((state) => {
          state.canUndo = false; // Placeholder
        });
      },

      redo: () => {
        // TODO: Implement redo logic
        set((state) => {
          state.canRedo = false; // Placeholder
        });
      },

      // Reset actions
      reset: () => {
        set(() => ({ ...initialState }));
      },
    })),
  ),
);

// Selector hooks for better performance
export const usePageSchema = () => useDesignerStore((state) => state.pageSchema);
export const useLayoutConfig = () => useDesignerStore((state) => state.layoutConfig);
export const useSelectedComponent = () => useDesignerStore((state) => state.selectedComponentId);
export const useIsLoading = () => useDesignerStore((state) => state.isLoading);
export const useComponents = () => useDesignerStore((state) => state.components);

// Action hooks - using individual selectors to avoid object creation
export const useSetPageSchema = () => useDesignerStore((state) => state.setPageSchema);
export const useSetLayoutConfig = () => useDesignerStore((state) => state.setLayoutConfig);
export const useUpdateComponent = () => useDesignerStore((state) => state.updateComponent);
export const useAddComponent = () => useDesignerStore((state) => state.addComponent);
export const useRemoveComponent = () => useDesignerStore((state) => state.removeComponent);
export const useSelectComponent = () => useDesignerStore((state) => state.selectComponent);
export const useSetPreviewMode = () => useDesignerStore((state) => state.setPreviewMode);
export const useSetLoading = () => useDesignerStore((state) => state.setLoading);
export const useLeftPanelTab = () => useDesignerStore((state) => state.leftPanelTab);
export const useRightPanelTab = () => useDesignerStore((state) => state.rightPanelTab);
export const useSetLeftPanelTab = () => useDesignerStore((state) => state.setLeftPanelTab);
export const useSetRightPanelTab = () => useDesignerStore((state) => state.setRightPanelTab);
export const useExecuteCommand = () => useDesignerStore((state) => state.executeCommand);
export const useUndo = () => useDesignerStore((state) => state.undo);
export const useRedo = () => useDesignerStore((state) => state.redo);
export const useReset = () => useDesignerStore((state) => state.reset);

// Combined actions hook - returns all actions in a single object
export const useDesignerActions = () =>
  useDesignerStore((state) => ({
    setPageSchema: state.setPageSchema,
    updatePageSchema: state.updatePageSchema,
    setLayoutConfig: state.setLayoutConfig,
    updateLayoutConfig: state.updateLayoutConfig,
    updateComponent: state.updateComponent,
    addComponent: state.addComponent,
    removeComponent: state.removeComponent,
    selectComponent: state.selectComponent,
    setHoveredComponent: state.setHoveredComponent,
    setDraggedComponent: state.setDraggedComponent,
    setPreviewMode: state.setPreviewMode,
    setLoading: state.setLoading,
    setError: state.setError,
    setLeftPanelTab: state.setLeftPanelTab,
    setRightPanelTab: state.setRightPanelTab,
    executeCommand: state.executeCommand,
    undo: state.undo,
    redo: state.redo,
    reset: state.reset,
  }));
