/**
 * Dashboard Designer Store
 * Zustand store for dashboard state management
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import type {
  Dashboard,
  Widget,
  WidgetConfig,
  LayoutConfig,
  DashboardStatus,
  DashboardScope,
  ValidationResult,
  ValidationError,
} from '../types';
import { dashboardService } from '../services/dashboardService';

/**
 * Generate unique widget ID using crypto.randomUUID for collision resistance
 */
function generateWidgetId(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return `widget_${crypto.randomUUID()}`;
  }
  return `widget_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Default layout configuration
 */
const DEFAULT_LAYOUT_CONFIG: LayoutConfig = {
  columns: 12,
  rowHeight: 100,
  gap: 16,
  compactType: 'vertical',
};

interface DashboardStore {
  // Dashboard data
  dashboard: Dashboard | null;
  widgets: Widget[];
  layoutConfig: LayoutConfig;

  // Selection state
  selectedWidgetId: string | null;

  // UI state
  isDirty: boolean;
  isSaving: boolean;
  isLoading: boolean;

  // Undo/Redo history
  history: Widget[][];
  historyIndex: number;

  // Validation
  validationResult: ValidationResult | null;

  // Actions - Dashboard
  loadDashboard: (pid: string) => Promise<void>;
  createDashboard: (title: string, scope?: DashboardScope) => void;
  updateDashboardMeta: (meta: {
    title?: string;
    description?: string;
    scope?: DashboardScope;
    teamId?: string;
  }) => void;
  saveDashboard: () => Promise<void>;
  publishDashboard: () => Promise<void>;
  unpublishDashboard: () => Promise<void>;

  // Actions - Widgets
  addWidget: (widget: Omit<Widget, 'id'>) => string;
  updateWidget: (widgetId: string, updates: Partial<Widget>) => void;
  updateWidgetConfig: (widgetId: string, config: Partial<WidgetConfig>) => void;
  deleteWidget: (widgetId: string) => void;
  duplicateWidget: (widgetId: string) => string;

  // Actions - Layout
  updateLayout: (widgets: Widget[]) => void;
  updateLayoutConfig: (config: Partial<LayoutConfig>) => void;

  // Actions - Selection
  selectWidget: (widgetId: string | null) => void;

  // Actions - History
  undo: () => void;
  redo: () => void;
  canUndo: () => boolean;
  canRedo: () => boolean;

  // Actions - Validation
  validate: () => ValidationResult;

  // Actions - State
  setDirty: (dirty: boolean) => void;
  reset: () => void;

  // Utilities
  getWidgetById: (widgetId: string) => Widget | undefined;
}

const initialState = {
  dashboard: null as Dashboard | null,
  widgets: [] as Widget[],
  layoutConfig: { ...DEFAULT_LAYOUT_CONFIG },
  selectedWidgetId: null as string | null,
  isDirty: false,
  isSaving: false,
  isLoading: false,
  history: [] as Widget[][],
  historyIndex: -1,
  validationResult: null as ValidationResult | null,
};

export const useDashboardStore = create<DashboardStore>()(
  subscribeWithSelector(
    immer((set, get) => ({
      ...initialState,

      // ==================== Dashboard Actions ====================

      loadDashboard: async (pid: string) => {
        set((state) => {
          state.isLoading = true;
        });

        try {
          const dashboard = await dashboardService.findByPid(pid);
          set((state) => {
            state.dashboard = dashboard;
            state.widgets = dashboard.widgets || [];
            state.layoutConfig = dashboard.layoutConfig || { ...DEFAULT_LAYOUT_CONFIG };
            state.isDirty = false;
            state.isLoading = false;
            state.selectedWidgetId = null;
            state.history = [dashboard.widgets || []];
            state.historyIndex = 0;
          });
        } catch (error) {
          set((state) => {
            state.isLoading = false;
          });
          throw error;
        }
      },

      createDashboard: (title: string, scope: DashboardScope = 'personal') => {
        set((state) => {
          state.dashboard = {
            title,
            scope,
            teamId: undefined,
            status: 'draft',
            layoutConfig: { ...DEFAULT_LAYOUT_CONFIG },
            widgets: [],
          };
          state.widgets = [];
          state.layoutConfig = { ...DEFAULT_LAYOUT_CONFIG };
          state.isDirty = true;
          state.selectedWidgetId = null;
          state.history = [[]];
          state.historyIndex = 0;
        });
      },

      updateDashboardMeta: (meta: {
        title?: string;
        description?: string;
        scope?: DashboardScope;
        teamId?: string;
      }) => {
        set((state) => {
          if (state.dashboard) {
            if (meta.title !== undefined) state.dashboard.title = meta.title;
            if (meta.description !== undefined) state.dashboard.description = meta.description;
            if (meta.scope !== undefined) state.dashboard.scope = meta.scope;
            if (meta.teamId !== undefined) state.dashboard.teamId = meta.teamId || undefined;
            state.isDirty = true;
          }
        });
      },

      saveDashboard: async () => {
        const state = get();
        if (!state.dashboard) return;

        set((s) => {
          s.isSaving = true;
        });

        try {
          const dashboardData = {
            ...state.dashboard,
            widgets: state.widgets,
            layoutConfig: state.layoutConfig,
          };

          let savedDashboard: Dashboard;
          if (state.dashboard.pid) {
            savedDashboard = await dashboardService.update(state.dashboard.pid, dashboardData);
          } else {
            savedDashboard = await dashboardService.create(dashboardData);
          }

          set((s) => {
            s.dashboard = savedDashboard;
            s.isDirty = false;
            s.isSaving = false;
          });
        } catch (error) {
          set((s) => {
            s.isSaving = false;
          });
          throw error;
        }
      },

      publishDashboard: async () => {
        const state = get();
        if (!state.dashboard?.pid) return;

        const published = await dashboardService.publish(state.dashboard.pid);
        set((s) => {
          if (s.dashboard) {
            s.dashboard.status = published.status;
          }
        });
      },

      unpublishDashboard: async () => {
        const state = get();
        if (!state.dashboard?.pid) return;

        const unpublished = await dashboardService.unpublish(state.dashboard.pid);
        set((s) => {
          if (s.dashboard) {
            s.dashboard.status = unpublished.status;
          }
        });
      },

      // ==================== Widget Actions ====================

      addWidget: (widgetData: Omit<Widget, 'id'>) => {
        const widgetId = generateWidgetId();
        const widget: Widget = {
          ...widgetData,
          id: widgetId,
        };

        set((state) => {
          state.widgets.push(widget);
          state.isDirty = true;
          state.selectedWidgetId = widgetId;

          // Add to history (deep copy to prevent shared references)
          state.history = state.history.slice(0, state.historyIndex + 1);
          state.history.push(JSON.parse(JSON.stringify(state.widgets)));
          state.historyIndex = state.history.length - 1;
        });

        return widgetId;
      },

      updateWidget: (widgetId: string, updates: Partial<Widget>) => {
        set((state) => {
          const index = state.widgets.findIndex((w) => w.id === widgetId);
          if (index !== -1) {
            state.widgets[index] = { ...state.widgets[index], ...updates };
            state.isDirty = true;

            // Add to history (deep copy to prevent shared references)
            state.history = state.history.slice(0, state.historyIndex + 1);
            state.history.push(JSON.parse(JSON.stringify(state.widgets)));
            state.historyIndex = state.history.length - 1;
          }
        });
      },

      updateWidgetConfig: (widgetId: string, configUpdates: Partial<WidgetConfig>) => {
        set((state) => {
          const widget = state.widgets.find((w) => w.id === widgetId);
          if (widget) {
            widget.config = { ...widget.config, ...configUpdates };
            state.isDirty = true;

            // Add to history (deep copy to prevent shared references)
            state.history = state.history.slice(0, state.historyIndex + 1);
            state.history.push(JSON.parse(JSON.stringify(state.widgets)));
            state.historyIndex = state.history.length - 1;
          }
        });
      },

      deleteWidget: (widgetId: string) => {
        set((state) => {
          state.widgets = state.widgets.filter((w) => w.id !== widgetId);
          if (state.selectedWidgetId === widgetId) {
            state.selectedWidgetId = null;
          }
          state.isDirty = true;

          // Add to history (deep copy to prevent shared references)
          state.history = state.history.slice(0, state.historyIndex + 1);
          state.history.push(JSON.parse(JSON.stringify(state.widgets)));
          state.historyIndex = state.history.length - 1;
        });
      },

      duplicateWidget: (widgetId: string) => {
        const state = get();
        const widget = state.widgets.find((w) => w.id === widgetId);
        if (!widget) return '';

        const newWidgetId = generateWidgetId();
        const newWidget: Widget = {
          ...widget,
          id: newWidgetId,
          x: widget.x + 1,
          y: widget.y + 1,
          config: {
            ...widget.config,
            title: `${widget.config.title} (副本)`,
          },
        };

        set((s) => {
          s.widgets.push(newWidget);
          s.selectedWidgetId = newWidgetId;
          s.isDirty = true;

          // Add to history (deep copy to prevent shared references)
          s.history = s.history.slice(0, s.historyIndex + 1);
          s.history.push(JSON.parse(JSON.stringify(s.widgets)));
          s.historyIndex = s.history.length - 1;
        });

        return newWidgetId;
      },

      // ==================== Layout Actions ====================

      updateLayout: (widgets: Widget[]) => {
        set((state) => {
          state.widgets = widgets;
          state.isDirty = true;

          // Add to history so layout changes can be undone
          state.history = state.history.slice(0, state.historyIndex + 1);
          state.history.push(JSON.parse(JSON.stringify(state.widgets)));
          state.historyIndex = state.history.length - 1;
        });
      },

      updateLayoutConfig: (config: Partial<LayoutConfig>) => {
        set((state) => {
          state.layoutConfig = { ...state.layoutConfig, ...config };
          state.isDirty = true;
        });
      },

      // ==================== Selection Actions ====================

      selectWidget: (widgetId: string | null) => {
        set((state) => {
          state.selectedWidgetId = widgetId;
        });
      },

      // ==================== History Actions ====================

      undo: () => {
        set((state) => {
          if (state.historyIndex > 0) {
            state.historyIndex -= 1;
            state.widgets = JSON.parse(JSON.stringify(state.history[state.historyIndex]));
            state.isDirty = true;
          }
        });
      },

      redo: () => {
        set((state) => {
          if (state.historyIndex < state.history.length - 1) {
            state.historyIndex += 1;
            state.widgets = JSON.parse(JSON.stringify(state.history[state.historyIndex]));
            state.isDirty = true;
          }
        });
      },

      canUndo: () => {
        return get().historyIndex > 0;
      },

      canRedo: () => {
        const state = get();
        return state.historyIndex < state.history.length - 1;
      },

      // ==================== Validation Actions ====================

      validate: () => {
        const state = get();
        const errors: ValidationError[] = [];

        // Check dashboard title
        if (!state.dashboard?.title) {
          errors.push({
            message: '请输入仪表盘标题',
            type: 'error',
          });
        }
        if (state.dashboard?.scope === 'team' && !state.dashboard?.teamId) {
          errors.push({
            field: 'teamId',
            message: '团队可见范围必须选择团队',
            type: 'error',
          });
        }

        // Check each widget
        state.widgets.forEach((widget) => {
          // Check widget title
          if (!widget.config.title) {
            errors.push({
              widgetId: widget.id,
              field: 'title',
              message: `组件缺少标题`,
              type: 'warning',
            });
          }

          // Check data source
          if (!widget.config.dataSource) {
            errors.push({
              widgetId: widget.id,
              field: 'dataSource',
              message: `组件 "${widget.config.title || widget.id}" 缺少数据源配置`,
              type: 'error',
            });
          } else {
            const ds = widget.config.dataSource;
            if (ds.type === 'aggregate') {
              if (!ds.modelCode) {
                errors.push({
                  widgetId: widget.id,
                  field: 'dataSource.modelCode',
                  message: `组件 "${widget.config.title}" 缺少模型配置`,
                  type: 'error',
                });
              }
            } else if (ds.type === 'namedQuery') {
              if (!ds.queryCode) {
                errors.push({
                  widgetId: widget.id,
                  field: 'dataSource.queryCode',
                  message: `组件 "${widget.config.title}" 缺少查询配置`,
                  type: 'error',
                });
              }
            }
          }
        });

        const result: ValidationResult = {
          valid: errors.filter((e) => e.type === 'error').length === 0,
          errors,
        };

        set((s) => {
          s.validationResult = result;
        });

        return result;
      },

      // ==================== State Actions ====================

      setDirty: (dirty: boolean) => {
        set((state) => {
          state.isDirty = dirty;
        });
      },

      reset: () => {
        set(initialState);
      },

      // ==================== Utilities ====================

      getWidgetById: (widgetId: string) => {
        return get().widgets.find((w) => w.id === widgetId);
      },
    })),
  ),
);
