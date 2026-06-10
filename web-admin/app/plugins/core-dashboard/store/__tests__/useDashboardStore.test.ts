/**
 * Unit tests for useDashboardStore
 * Tests all state transitions: CRUD widgets, undo/redo, layout, validation, meta.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';

// ── Hoist service mocks ───────────────────────────────────────────────────
const {
  findByPidMock,
  createDashboardMock,
  updateDashboardMock,
  publishDashboardMock,
  unpublishDashboardMock,
} = vi.hoisted(() => ({
  findByPidMock: vi.fn(),
  createDashboardMock: vi.fn(),
  updateDashboardMock: vi.fn(),
  publishDashboardMock: vi.fn(),
  unpublishDashboardMock: vi.fn(),
}));

vi.mock('../../services/dashboardService', () => ({
  dashboardService: {
    findByPid: findByPidMock,
    create: createDashboardMock,
    update: updateDashboardMock,
    publish: publishDashboardMock,
    unpublish: unpublishDashboardMock,
  },
}));

import { useDashboardStore } from '../useDashboardStore';
import type { Widget, LayoutConfig } from '../../types';

// ── Helper: minimal widget ────────────────────────────────────────────────

function widgetData(overrides: Partial<Omit<Widget, 'id'>> = {}): Omit<Widget, 'id'> {
  return {
    type: 'smart-number-card',
    x: 0,
    y: 0,
    w: 4,
    h: 2,
    componentType: 'smart-number-card',
    props: {},
    config: {
      title: 'KPI',
    },
    ...overrides,
  };
}

function minimalDashboard(overrides: Record<string, unknown> = {}) {
  return {
    pid: 'dash-1',
    title: 'Test Dashboard',
    scope: 'personal' as const,
    status: 'draft' as const,
    layoutConfig: { columns: 12, rowHeight: 100, gap: 16, compactType: 'vertical' as const },
    widgets: [],
    ...overrides,
  };
}

describe('useDashboardStore', () => {
  beforeEach(() => {
    useDashboardStore.getState().reset();
    vi.clearAllMocks();
  });

  // ── Initial state ─────────────────────────────────────────────────────────

  it('starts with null dashboard and empty widgets', () => {
    const state = useDashboardStore.getState();
    expect(state.dashboard).toBeNull();
    expect(state.widgets).toHaveLength(0);
    expect(state.isDirty).toBe(false);
    expect(state.isSaving).toBe(false);
    expect(state.isLoading).toBe(false);
  });

  // ── createDashboard ───────────────────────────────────────────────────────

  describe('createDashboard', () => {
    it('sets dashboard with given title and default scope', () => {
      useDashboardStore.getState().createDashboard('My Board');
      const { dashboard, widgets, isDirty, historyIndex } = useDashboardStore.getState();

      expect(dashboard?.title).toBe('My Board');
      expect(dashboard?.scope).toBe('personal');
      expect(dashboard?.status).toBe('draft');
      expect(widgets).toHaveLength(0);
      expect(isDirty).toBe(true);
      expect(historyIndex).toBe(0);
    });

    it('uses provided scope', () => {
      useDashboardStore.getState().createDashboard('Team Board', 'team');
      expect(useDashboardStore.getState().dashboard?.scope).toBe('team');
    });
  });

  // ── updateDashboardMeta ───────────────────────────────────────────────────

  describe('updateDashboardMeta', () => {
    it('updates title and marks dirty', () => {
      useDashboardStore.getState().createDashboard('Original');
      useDashboardStore.getState().setDirty(false); // reset for clarity

      useDashboardStore.getState().updateDashboardMeta({ title: 'Updated' });

      const state = useDashboardStore.getState();
      expect(state.dashboard?.title).toBe('Updated');
      expect(state.isDirty).toBe(true);
    });

    it('does nothing when dashboard is null', () => {
      // dashboard is null at start
      expect(() =>
        useDashboardStore.getState().updateDashboardMeta({ title: 'X' }),
      ).not.toThrow();
    });
  });

  // ── loadDashboard ─────────────────────────────────────────────────────────

  describe('loadDashboard', () => {
    it('sets dashboard from service and clears dirty flag', async () => {
      findByPidMock.mockResolvedValue(minimalDashboard());

      await useDashboardStore.getState().loadDashboard('dash-1');
      const state = useDashboardStore.getState();

      expect(state.dashboard?.pid).toBe('dash-1');
      expect(state.isDirty).toBe(false);
      expect(state.isLoading).toBe(false);
      expect(state.historyIndex).toBe(0);
    });

    it('clears isLoading and rethrows on error', async () => {
      findByPidMock.mockRejectedValue(new Error('Not found'));

      await expect(
        useDashboardStore.getState().loadDashboard('missing'),
      ).rejects.toThrow('Not found');

      expect(useDashboardStore.getState().isLoading).toBe(false);
    });
  });

  // ── addWidget ─────────────────────────────────────────────────────────────

  describe('addWidget', () => {
    it('adds a widget, selects it, marks dirty, advances history', () => {
      useDashboardStore.getState().createDashboard('Board');
      useDashboardStore.getState().setDirty(false);

      const id = useDashboardStore.getState().addWidget(widgetData());
      const state = useDashboardStore.getState();

      expect(typeof id).toBe('string');
      expect(id.startsWith('widget_')).toBe(true);
      expect(state.widgets).toHaveLength(1);
      expect(state.widgets[0].id).toBe(id);
      expect(state.selectedWidgetId).toBe(id);
      expect(state.isDirty).toBe(true);
      expect(state.historyIndex).toBe(1); // after createDashboard (0) + addWidget (1)
    });

    it('returns unique IDs for successive calls', () => {
      useDashboardStore.getState().createDashboard('Board');
      const id1 = useDashboardStore.getState().addWidget(widgetData());
      const id2 = useDashboardStore.getState().addWidget(widgetData());
      expect(id1).not.toBe(id2);
    });
  });

  // ── updateWidget ──────────────────────────────────────────────────────────

  describe('updateWidget', () => {
    it('updates an existing widget by id', () => {
      useDashboardStore.getState().createDashboard('Board');
      const id = useDashboardStore.getState().addWidget(widgetData({ config: { title: 'Old' } }));

      useDashboardStore.getState().updateWidget(id, {
        config: { title: 'New' },
      } as any);

      const widget = useDashboardStore.getState().getWidgetById(id);
      expect(widget?.config.title).toBe('New');
    });

    it('is a no-op for unknown id', () => {
      useDashboardStore.getState().createDashboard('Board');
      const id = useDashboardStore.getState().addWidget(widgetData());
      const before = useDashboardStore.getState().widgets.length;

      useDashboardStore.getState().updateWidget('unknown-id', { x: 99 });

      expect(useDashboardStore.getState().widgets.length).toBe(before);
    });
  });

  // ── updateWidgetConfig ────────────────────────────────────────────────────

  describe('updateWidgetConfig', () => {
    it('merges config updates', () => {
      useDashboardStore.getState().createDashboard('Board');
      const id = useDashboardStore.getState().addWidget(
        widgetData({ config: { title: 'Original', refreshInterval: 60 } }),
      );

      useDashboardStore.getState().updateWidgetConfig(id, { refreshInterval: 120 });

      const widget = useDashboardStore.getState().getWidgetById(id);
      expect(widget?.config.refreshInterval).toBe(120);
      expect(widget?.config.title).toBe('Original'); // preserved
    });
  });

  // ── deleteWidget ──────────────────────────────────────────────────────────

  describe('deleteWidget', () => {
    it('removes widget and clears selection if it was selected', () => {
      useDashboardStore.getState().createDashboard('Board');
      const id = useDashboardStore.getState().addWidget(widgetData());
      expect(useDashboardStore.getState().selectedWidgetId).toBe(id);

      useDashboardStore.getState().deleteWidget(id);
      const state = useDashboardStore.getState();

      expect(state.widgets).toHaveLength(0);
      expect(state.selectedWidgetId).toBeNull();
    });

    it('does NOT clear selection when a different widget is deleted', () => {
      useDashboardStore.getState().createDashboard('Board');
      const id1 = useDashboardStore.getState().addWidget(widgetData());
      const id2 = useDashboardStore.getState().addWidget(widgetData());
      useDashboardStore.getState().selectWidget(id1);

      useDashboardStore.getState().deleteWidget(id2);

      expect(useDashboardStore.getState().selectedWidgetId).toBe(id1);
    });
  });

  // ── duplicateWidget ───────────────────────────────────────────────────────

  describe('duplicateWidget', () => {
    it('creates a copy with a new id and offset position', () => {
      useDashboardStore.getState().createDashboard('Board');
      const id = useDashboardStore.getState().addWidget(widgetData({ x: 2, y: 3 }));
      const widget = useDashboardStore.getState().getWidgetById(id)!;

      const newId = useDashboardStore.getState().duplicateWidget(id);
      const newWidget = useDashboardStore.getState().getWidgetById(newId);

      expect(newId).not.toBe(id);
      expect(newWidget?.x).toBe(widget.x + 1);
      expect(newWidget?.y).toBe(widget.y + 1);
      expect(newWidget?.config.title).toContain('副本');
    });

    it('returns empty string for unknown widget id', () => {
      useDashboardStore.getState().createDashboard('Board');
      const result = useDashboardStore.getState().duplicateWidget('non-existent');
      expect(result).toBe('');
    });
  });

  // ── selectWidget ──────────────────────────────────────────────────────────

  describe('selectWidget', () => {
    it('sets selectedWidgetId', () => {
      useDashboardStore.getState().createDashboard('Board');
      const id = useDashboardStore.getState().addWidget(widgetData());

      useDashboardStore.getState().selectWidget(id);
      expect(useDashboardStore.getState().selectedWidgetId).toBe(id);

      useDashboardStore.getState().selectWidget(null);
      expect(useDashboardStore.getState().selectedWidgetId).toBeNull();
    });
  });

  // ── updateLayout ──────────────────────────────────────────────────────────

  describe('updateLayout', () => {
    it('replaces widgets array and marks dirty', () => {
      useDashboardStore.getState().createDashboard('Board');
      const id = useDashboardStore.getState().addWidget(widgetData());

      const movedWidget = {
        ...useDashboardStore.getState().getWidgetById(id)!,
        x: 6,
      };
      useDashboardStore.getState().updateLayout([movedWidget]);

      const state = useDashboardStore.getState();
      expect(state.widgets[0].x).toBe(6);
      expect(state.isDirty).toBe(true);
    });
  });

  // ── updateLayoutConfig ────────────────────────────────────────────────────

  describe('updateLayoutConfig', () => {
    it('merges config and marks dirty', () => {
      useDashboardStore.getState().createDashboard('Board');
      useDashboardStore.getState().setDirty(false);

      useDashboardStore.getState().updateLayoutConfig({ rowHeight: 200 });

      const state = useDashboardStore.getState();
      expect(state.layoutConfig.rowHeight).toBe(200);
      expect(state.layoutConfig.columns).toBe(12); // preserved
      expect(state.isDirty).toBe(true);
    });
  });

  // ── undo / redo ───────────────────────────────────────────────────────────

  describe('undo / redo', () => {
    it('canUndo is false initially', () => {
      useDashboardStore.getState().createDashboard('Board');
      expect(useDashboardStore.getState().canUndo()).toBe(false);
    });

    it('canUndo is true after adding a widget', () => {
      useDashboardStore.getState().createDashboard('Board');
      useDashboardStore.getState().addWidget(widgetData());
      expect(useDashboardStore.getState().canUndo()).toBe(true);
    });

    it('undo removes the last-added widget', () => {
      useDashboardStore.getState().createDashboard('Board');
      useDashboardStore.getState().addWidget(widgetData());
      expect(useDashboardStore.getState().widgets).toHaveLength(1);

      useDashboardStore.getState().undo();

      expect(useDashboardStore.getState().widgets).toHaveLength(0);
      expect(useDashboardStore.getState().canRedo()).toBe(true);
    });

    it('redo re-applies the widget after undo', () => {
      useDashboardStore.getState().createDashboard('Board');
      useDashboardStore.getState().addWidget(widgetData());
      useDashboardStore.getState().undo();

      useDashboardStore.getState().redo();

      expect(useDashboardStore.getState().widgets).toHaveLength(1);
    });

    it('undo is a no-op when historyIndex is 0', () => {
      useDashboardStore.getState().createDashboard('Board');
      const before = useDashboardStore.getState().historyIndex;

      useDashboardStore.getState().undo(); // at index 0, should not go below
      expect(useDashboardStore.getState().historyIndex).toBe(before);
    });

    it('redo truncates forward history after new action', () => {
      useDashboardStore.getState().createDashboard('Board');
      useDashboardStore.getState().addWidget(widgetData());
      useDashboardStore.getState().undo(); // back to empty

      // New action after undo should truncate redo stack
      useDashboardStore.getState().addWidget(widgetData({ config: { title: 'New' } }));

      expect(useDashboardStore.getState().canRedo()).toBe(false);
    });
  });

  // ── validate ──────────────────────────────────────────────────────────────

  describe('validate', () => {
    it('returns valid:false with error when dashboard title is missing', () => {
      useDashboardStore.getState().createDashboard('');
      const result = useDashboardStore.getState().validate();

      expect(result.valid).toBe(false);
      expect(result.errors.some((e) => e.message.includes('标题'))).toBe(true);
    });

    it('returns error when scope is team but teamId is missing', () => {
      useDashboardStore.getState().createDashboard('Board', 'team');
      const result = useDashboardStore.getState().validate();

      expect(result.valid).toBe(false);
      const teamError = result.errors.find((e) => e.field === 'teamId');
      expect(teamError).toBeDefined();
    });

    it('returns valid:true for a dashboard with title and no widgets', () => {
      useDashboardStore.getState().createDashboard('Good Board');
      const result = useDashboardStore.getState().validate();
      expect(result.valid).toBe(true);
    });

    it('adds error for widget missing dataSource', () => {
      useDashboardStore.getState().createDashboard('Board');
      useDashboardStore.getState().addWidget(widgetData({ config: { title: 'Chart' } }));
      const result = useDashboardStore.getState().validate();

      const dsError = result.errors.find((e) => e.field === 'dataSource');
      expect(dsError).toBeDefined();
    });

    it('adds warning for widget missing title', () => {
      useDashboardStore.getState().createDashboard('Board');
      useDashboardStore.getState().addWidget(widgetData({ config: { title: '' } }));
      const result = useDashboardStore.getState().validate();

      const titleWarning = result.errors.find((e) => e.type === 'warning' && e.field === 'title');
      expect(titleWarning).toBeDefined();
    });

    it('stores validation result in state', () => {
      useDashboardStore.getState().createDashboard('Board');
      useDashboardStore.getState().validate();
      expect(useDashboardStore.getState().validationResult).not.toBeNull();
    });

    it('adds error for aggregate dataSource missing modelCode', () => {
      useDashboardStore.getState().createDashboard('Board');
      useDashboardStore.getState().addWidget(
        widgetData({
          config: {
            title: 'Agg Widget',
            dataSource: { type: 'aggregate', modelCode: undefined },
          },
        }),
      );
      const result = useDashboardStore.getState().validate();
      const err = result.errors.find((e) => e.field === 'dataSource.modelCode');
      expect(err).toBeDefined();
    });

    it('adds error for namedQuery dataSource missing queryCode', () => {
      useDashboardStore.getState().createDashboard('Board');
      useDashboardStore.getState().addWidget(
        widgetData({
          config: {
            title: 'Query Widget',
            dataSource: { type: 'namedQuery', queryCode: undefined },
          },
        }),
      );
      const result = useDashboardStore.getState().validate();
      const err = result.errors.find((e) => e.field === 'dataSource.queryCode');
      expect(err).toBeDefined();
    });
  });

  // ── saveDashboard ─────────────────────────────────────────────────────────

  describe('saveDashboard', () => {
    it('calls dashboardService.create for new dashboard (no pid)', async () => {
      useDashboardStore.getState().createDashboard('New Board');
      const created = minimalDashboard({ pid: 'new-pid' });
      createDashboardMock.mockResolvedValue(created);

      await useDashboardStore.getState().saveDashboard();

      expect(createDashboardMock).toHaveBeenCalledOnce();
      const state = useDashboardStore.getState();
      expect(state.dashboard?.pid).toBe('new-pid');
      expect(state.isDirty).toBe(false);
      expect(state.isSaving).toBe(false);
    });

    it('calls dashboardService.update for existing dashboard (has pid)', async () => {
      findByPidMock.mockResolvedValue(minimalDashboard({ pid: 'existing-pid' }));
      await useDashboardStore.getState().loadDashboard('existing-pid');
      const updated = minimalDashboard({ pid: 'existing-pid', title: 'Updated' });
      updateDashboardMock.mockResolvedValue(updated);

      await useDashboardStore.getState().saveDashboard();

      expect(updateDashboardMock).toHaveBeenCalledWith('existing-pid', expect.any(Object));
    });

    it('clears isSaving and rethrows on error', async () => {
      useDashboardStore.getState().createDashboard('Board');
      createDashboardMock.mockRejectedValue(new Error('Save failed'));

      await expect(useDashboardStore.getState().saveDashboard()).rejects.toThrow('Save failed');
      expect(useDashboardStore.getState().isSaving).toBe(false);
    });

    it('is a no-op when dashboard is null', async () => {
      // dashboard is null at reset
      await expect(useDashboardStore.getState().saveDashboard()).resolves.toBeUndefined();
      expect(createDashboardMock).not.toHaveBeenCalled();
    });
  });

  // ── setDirty / reset ──────────────────────────────────────────────────────

  describe('setDirty', () => {
    it('sets isDirty flag', () => {
      useDashboardStore.getState().setDirty(true);
      expect(useDashboardStore.getState().isDirty).toBe(true);
      useDashboardStore.getState().setDirty(false);
      expect(useDashboardStore.getState().isDirty).toBe(false);
    });
  });

  describe('reset', () => {
    it('restores initial state', () => {
      useDashboardStore.getState().createDashboard('Board');
      useDashboardStore.getState().addWidget(widgetData());

      useDashboardStore.getState().reset();
      const state = useDashboardStore.getState();

      expect(state.dashboard).toBeNull();
      expect(state.widgets).toHaveLength(0);
      expect(state.isDirty).toBe(false);
      expect(state.historyIndex).toBe(-1);
    });
  });

  // ── getWidgetById ─────────────────────────────────────────────────────────

  describe('getWidgetById', () => {
    it('returns the widget when found', () => {
      useDashboardStore.getState().createDashboard('Board');
      const id = useDashboardStore.getState().addWidget(widgetData());
      expect(useDashboardStore.getState().getWidgetById(id)?.id).toBe(id);
    });

    it('returns undefined when not found', () => {
      useDashboardStore.getState().createDashboard('Board');
      expect(useDashboardStore.getState().getWidgetById('nope')).toBeUndefined();
    });
  });
});
