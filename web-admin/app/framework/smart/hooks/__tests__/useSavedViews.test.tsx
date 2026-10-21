import { renderHook, waitFor, act } from '@testing-library/react';
import { StrictMode } from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('~/shared/services/savedViewService', () => ({
  savedViewService: {
    getAccessibleViews: vi.fn(),
    getDefaultView: vi.fn(),
    createView: vi.fn(),
    updateView: vi.fn(),
    deleteView: vi.fn(),
    setDefaultView: vi.fn(),
    duplicateView: vi.fn(),
    copyToPersonal: vi.fn(),
  },
}));

import { useSavedViews, type UseSavedViewsOptions } from '../useSavedViews';
import { savedViewService } from '~/shared/services/savedViewService';
import type { SavedView } from '~/framework/smart/types/savedView';

const mockService = vi.mocked(savedViewService);

function makeView(overrides: Partial<SavedView> = {}): SavedView {
  return {
    pid: 'v1',
    name: 'Default View',
    modelCode: 'order',
    scope: 'personal',
    isDefault: false,
    viewType: 'table',
    viewConfig: {} as any,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
    createdBy: 'user1',
    ...overrides,
  };
}

describe('useSavedViews', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('loads views on mount when autoLoad=true', async () => {
    const views = [makeView({ pid: 'v1' }), makeView({ pid: 'v2', name: 'View 2', scope: 'team' })];
    mockService.getAccessibleViews.mockResolvedValue(views);
    mockService.getDefaultView.mockResolvedValue(null);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.views).toHaveLength(2);
    expect(mockService.getAccessibleViews).toHaveBeenCalledOnce();
  });

  it('does not fetch when autoLoad=false', () => {
    const { result } = renderHook(() => useSavedViews({ modelCode: 'order', autoLoad: false }));
    expect(mockService.getAccessibleViews).not.toHaveBeenCalled();
    expect(result.current.views).toEqual([]);
  });

  it('auto-selects the default view when present', async () => {
    const v1 = makeView({ pid: 'v1' });
    const v2 = makeView({ pid: 'v2', name: 'Default', isDefault: true });
    mockService.getAccessibleViews.mockResolvedValue([v1, v2]);
    mockService.getDefaultView.mockResolvedValue(v2);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.currentView?.pid).toBe('v2');
  });

  it('filters to personal views and ignores non-personal defaults when requested', async () => {
    const personal = makeView({ pid: 'personal', name: 'My View', scope: 'personal' });
    const team = makeView({ pid: 'team', name: 'Team View', scope: 'team' });
    const globalDefault = makeView({
      pid: 'global',
      name: 'Global Default',
      scope: 'global',
      isDefault: true,
    });
    mockService.getAccessibleViews.mockResolvedValue([team, globalDefault, personal]);
    mockService.getDefaultView.mockResolvedValue(globalDefault);

    const { result } = renderHook(() =>
      useSavedViews({ modelCode: 'order', scopeFilter: 'personal' }),
    );

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.views.map((view: SavedView) => view.pid)).toEqual(['personal']);
    expect(result.current.currentView?.pid).toBe('personal');
    expect(result.current.groupedViews.team).toEqual([]);
    expect(result.current.groupedViews.global).toEqual([]);
    expect(result.current.accessibleViews.map((view: SavedView) => view.pid)).toEqual([
      'team',
      'global',
      'personal',
    ]);
  });

  it('can activate an accessible plugin preset without exposing it as a personal view', async () => {
    const personal = makeView({ pid: 'personal', name: 'My View', scope: 'personal' });
    const globalKanban = makeView({
      pid: 'pipeline-board',
      name: 'Pipeline Board',
      scope: 'global',
      viewType: 'kanban',
      isDefault: true,
    });
    mockService.getAccessibleViews.mockResolvedValue([personal, globalKanban]);
    mockService.getDefaultView.mockResolvedValue(globalKanban);

    const { result } = renderHook(() =>
      useSavedViews({ modelCode: 'order', scopeFilter: 'personal' }),
    );
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.views.map((view: SavedView) => view.pid)).toEqual(['personal']);
    act(() => result.current.selectView('pipeline-board'));
    expect(result.current.currentView?.pid).toBe('pipeline-board');
    expect(result.current.currentView?.viewType).toBe('kanban');
  });

  it('keeps the backend-composed personal default instead of replacing it with the raw list row', async () => {
    const rawPersonal = makeView({
      pid: 'personal',
      name: 'My View',
      scope: 'personal',
      isDefault: true,
      viewConfig: { columns: [{ fieldCode: 'name', width: 160 }] },
    });
    const effectivePersonal = makeView({
      ...rawPersonal,
      viewConfig: {
        density: 'compact',
        columns: [
          { fieldCode: 'tenant_only', width: 100 },
          { fieldCode: 'name', width: 160 },
        ],
      },
    });
    mockService.getAccessibleViews.mockResolvedValue([rawPersonal]);
    mockService.getDefaultView.mockResolvedValue(effectivePersonal);

    const { result } = renderHook(() =>
      useSavedViews({ modelCode: 'order', scopeFilter: 'personal' }),
    );

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.currentView?.viewConfig.density).toBe('compact');
    expect(result.current.currentView?.viewConfig.columns).toEqual(
      effectivePersonal.viewConfig?.columns,
    );
  });

  it('auto-selects first view when no default view', async () => {
    const v1 = makeView({ pid: 'v1' });
    mockService.getAccessibleViews.mockResolvedValue([v1]);
    mockService.getDefaultView.mockResolvedValue(null);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.currentView?.pid).toBe('v1');
  });

  it('sets currentView to null when no views available', async () => {
    mockService.getAccessibleViews.mockResolvedValue([]);
    mockService.getDefaultView.mockResolvedValue(null);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.currentView).toBeNull();
  });

  it('sets error state on load failure', async () => {
    mockService.getAccessibleViews.mockRejectedValue(new Error('Network error'));
    mockService.getDefaultView.mockRejectedValue(new Error('Network error'));

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).not.toBeNull();
  });

  it('selectView changes currentView to the chosen view', async () => {
    const v1 = makeView({ pid: 'v1' });
    const v2 = makeView({ pid: 'v2', name: 'View 2' });
    mockService.getAccessibleViews.mockResolvedValue([v1, v2]);
    mockService.getDefaultView.mockResolvedValue(null);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => result.current.selectView('v2'));
    expect(result.current.currentView?.pid).toBe('v2');
  });

  it('reload preserves the selected view when it is still accessible', async () => {
    const defaultView = makeView({ pid: 'v1', isDefault: true });
    const treeView = makeView({ pid: 'tree1', name: 'Tree View', viewType: 'tree' });
    mockService.getAccessibleViews.mockResolvedValue([defaultView, treeView]);
    mockService.getDefaultView.mockResolvedValue(defaultView);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => result.current.selectView('tree1'));
    expect(result.current.currentView?.pid).toBe('tree1');

    await act(async () => {
      await result.current.reload();
    });

    expect(result.current.currentView?.pid).toBe('tree1');
    expect(result.current.currentView?.viewType).toBe('tree');
  });

  it('does not reselect a personal view when an in-flight reload finishes after selecting the default', async () => {
    const personal = makeView({
      pid: 'saved-week',
      viewConfig: {
        filters: [{ fieldCode: 'updated_at', operator: 'between', value: ['2026-10-15', '2026-10-22'] }],
      },
    });
    let resolveReload!: (views: SavedView[]) => void;
    const pendingViews = new Promise<SavedView[]>((resolve) => { resolveReload = resolve; });
    mockService.getAccessibleViews
      .mockResolvedValue([personal])
      .mockResolvedValueOnce([personal])
      .mockReturnValueOnce(pendingViews);
    mockService.getDefaultView.mockResolvedValue(null);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order', pageKey: 'orders' }));
    await waitFor(() => expect(result.current.currentView?.pid).toBe(personal.pid));

    let reload!: Promise<void>;
    act(() => { reload = result.current.reload(); });
    act(() => result.current.selectDefaultView());
    expect(result.current.currentView).toBeNull();

    await act(async () => {
      resolveReload([personal]);
      await reload;
    });

    expect(result.current.accessibleViews).toEqual([personal]);
    expect(result.current.currentView).toBeNull();
  });

  it('does not auto-select a personal view when the first load finishes after an explicit default selection', async () => {
    const personal = makeView({ pid: 'saved-week' });
    let resolveViews!: (views: SavedView[]) => void;
    mockService.getAccessibleViews.mockReturnValue(new Promise<SavedView[]>((resolve) => {
      resolveViews = resolve;
    }));
    mockService.getDefaultView.mockResolvedValue(null);
    const { result } = renderHook(() => useSavedViews({ modelCode: 'order', pageKey: 'orders' }));

    act(() => result.current.selectDefaultView());
    await act(async () => { resolveViews([personal]); });
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.accessibleViews).toEqual([personal]);
    expect(result.current.currentView).toBeNull();
  });

  it('keeps the default selection when the second StrictMode initial load completes late', async () => {
    const personal = makeView({ pid: 'saved-week' });
    let resolveSecondLoad!: (views: SavedView[]) => void;
    mockService.getAccessibleViews
      .mockResolvedValueOnce([personal])
      .mockReturnValueOnce(new Promise<SavedView[]>((resolve) => { resolveSecondLoad = resolve; }));
    mockService.getDefaultView.mockResolvedValue(null);
    const { result } = renderHook(() => useSavedViews({ modelCode: 'order', pageKey: 'orders' }), {
      wrapper: StrictMode,
    });
    await waitFor(() => expect(result.current.currentView?.pid).toBe(personal.pid));
    expect(mockService.getAccessibleViews).toHaveBeenCalledTimes(2);

    act(() => result.current.selectDefaultView());
    expect(result.current.currentView).toBeNull();
    await act(async () => { resolveSecondLoad([personal]); });
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.currentView).toBeNull();
  });

  it.each(['select', 'create', 'upsert', 'copy'] as const)(
    'can %s a concrete view after choosing the default and preserve that choice on reload',
    async (action) => {
      const first = makeView({ pid: 'first' });
      const selected = makeView({ pid: 'selected' });
      mockService.getAccessibleViews
        .mockResolvedValue([first, selected])
        .mockResolvedValueOnce(action === 'select' ? [first, selected] : [first]);
      mockService.getDefaultView.mockResolvedValue(null);
      mockService.createView.mockResolvedValue(selected);
      mockService.copyToPersonal.mockResolvedValue(selected);
      const { result } = renderHook(() => useSavedViews({ modelCode: 'order', pageKey: 'orders' }));
      await waitFor(() => expect(result.current.currentView?.pid).toBe('first'));

      act(() => result.current.selectDefaultView());
      expect(result.current.currentView).toBeNull();
      await act(async () => {
        if (action === 'select') result.current.selectView('selected');
        else if (action === 'upsert') result.current.upsertView(selected);
        else if (action === 'create') await result.current.createView({
          modelCode: 'order', pageKey: 'orders', name: 'Selected', scope: 'personal', viewType: 'table', viewConfig: {},
        });
        else await result.current.copyToPersonal('source-view');
      });
      await act(async () => { await result.current.reload(); });

      expect(result.current.currentView?.pid).toBe('selected');
    },
  );

  it('reloads the chosen implicit default without switching to another personal default', async () => {
    const personal = makeView({ pid: 'personal', isDefault: true });
    const implicit = makeView({ pid: 'implicit', isImplicit: true });
    const refreshedImplicit = makeView({ ...implicit, viewConfig: { density: 'compact' } });
    mockService.getAccessibleViews.mockResolvedValueOnce([personal, implicit])
      .mockResolvedValue([personal, refreshedImplicit]);
    mockService.getDefaultView.mockResolvedValue(personal);
    const { result } = renderHook(() => useSavedViews({ modelCode: 'order', pageKey: 'orders' }));
    await waitFor(() => expect(result.current.currentView?.pid).toBe(personal.pid));

    act(() => result.current.selectDefaultView());
    expect(result.current.currentView?.pid).toBe(implicit.pid);
    await act(async () => { await result.current.reload(); });

    expect(result.current.currentView?.pid).toBe(implicit.pid);
    expect(result.current.currentView?.viewConfig.density).toBe('compact');
  });

  it.each([
    { label: 'model', next: { modelCode: 'customer', pageKey: 'orders', scopeFilter: 'personal' } },
    { label: 'page', next: { modelCode: 'order', pageKey: 'other-orders', scopeFilter: 'personal' } },
    { label: 'scope', next: { modelCode: 'order', pageKey: 'orders', scopeFilter: 'team' } },
  ] as const)('does not carry an explicit default choice into another $label', async ({ next }) => {
    const first = makeView({ pid: 'first' });
    const nextView = makeView({ pid: 'next', modelCode: next.modelCode, scope: next.scopeFilter });
    mockService.getAccessibleViews.mockResolvedValue([nextView]).mockResolvedValueOnce([first]);
    mockService.getDefaultView.mockResolvedValue(null);
    const { result, rerender } = renderHook((options: UseSavedViewsOptions) => useSavedViews(options), {
      initialProps: { modelCode: 'order', pageKey: 'orders', scopeFilter: 'personal' },
    });
    await waitFor(() => expect(result.current.currentView?.pid).toBe('first'));
    act(() => result.current.selectDefaultView());
    expect(result.current.currentView).toBeNull();

    rerender(next);
    await waitFor(() => expect(result.current.currentView?.pid).toBe('next'));
  });

  it('selectView does nothing for unknown pid', async () => {
    const v1 = makeView({ pid: 'v1' });
    mockService.getAccessibleViews.mockResolvedValue([v1]);
    mockService.getDefaultView.mockResolvedValue(null);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => result.current.selectView('nonexistent'));
    expect(result.current.currentView?.pid).toBe('v1');
  });

  it('upsertView inserts and selects a returned implicit view', () => {
    const { result } = renderHook(() =>
      useSavedViews({ modelCode: 'order', scopeFilter: 'personal', autoLoad: false }),
    );
    const implicitView = makeView({
      pid: 'implicit_default',
      name: 'Default View',
      isImplicit: true,
      viewConfig: { columns: [{ fieldCode: 'title', visible: false }] } as any,
    });

    act(() => result.current.upsertView(implicitView));

    expect(result.current.views).toEqual([implicitView]);
    expect(result.current.currentView).toBe(implicitView);
  });

  it('upsertView replaces an existing selected view with the returned config', async () => {
    const implicitView = makeView({
      pid: 'implicit_default',
      name: 'Default View',
      isImplicit: true,
      viewConfig: { columns: [{ fieldCode: 'title', visible: true }] } as any,
    });
    mockService.getAccessibleViews.mockResolvedValue([implicitView]);
    mockService.getDefaultView.mockResolvedValue(implicitView);

    const { result } = renderHook(() =>
      useSavedViews({ modelCode: 'order', scopeFilter: 'personal' }),
    );
    await waitFor(() => expect(result.current.loading).toBe(false));

    const updatedImplicitView = makeView({
      ...implicitView,
      viewConfig: { columns: [{ fieldCode: 'title', visible: false }] } as any,
    });
    act(() => result.current.upsertView(updatedImplicitView));

    expect(result.current.views).toEqual([updatedImplicitView]);
    expect(result.current.currentView?.viewConfig.columns?.[0]).toEqual({
      fieldCode: 'title',
      visible: false,
    });
  });

  it('createView adds new view and selects it', async () => {
    mockService.getAccessibleViews.mockResolvedValue([]);
    mockService.getDefaultView.mockResolvedValue(null);
    const newView = makeView({ pid: 'v_new', name: 'My New View' });
    mockService.createView.mockResolvedValue(newView);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      await result.current.createView({
        name: 'My New View',
        modelCode: 'order',
        scope: 'personal',
        viewType: 'table',
        viewConfig: {} as any,
      });
    });

    expect(result.current.views).toHaveLength(1);
    expect(result.current.currentView?.pid).toBe('v_new');
  });

  it('updateView updates the selected view in list and sets currentView', async () => {
    const v1 = makeView({ pid: 'v1' });
    mockService.getAccessibleViews.mockResolvedValue([v1]);
    mockService.getDefaultView.mockResolvedValue(null);
    const updated = makeView({ pid: 'v1', name: 'Updated Name' });
    mockService.updateView.mockResolvedValue(updated);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      await result.current.updateView({ name: 'Updated Name' });
    });

    expect(result.current.views[0].name).toBe('Updated Name');
    expect(result.current.currentView?.name).toBe('Updated Name');
  });

  it('updateView throws when no view selected', async () => {
    mockService.getAccessibleViews.mockResolvedValue([]);
    mockService.getDefaultView.mockResolvedValue(null);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    await expect(
      act(async () => {
        await result.current.updateView({ name: 'fail' });
      }),
    ).rejects.toThrow('No view is currently selected');
  });

  it('deleteView removes the view from the list', async () => {
    const v1 = makeView({ pid: 'v1' });
    const v2 = makeView({ pid: 'v2', name: 'View 2' });
    mockService.getAccessibleViews.mockResolvedValue([v1, v2]);
    mockService.getDefaultView.mockResolvedValue(null);
    mockService.deleteView.mockResolvedValue(undefined);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      await result.current.deleteView('v1');
    });

    expect(result.current.views).toHaveLength(1);
    expect(result.current.views[0].pid).toBe('v2');
    // Should auto-select first remaining view
    expect(result.current.currentView?.pid).toBe('v2');
  });

  it('setDefaultView updates isDefault flags on all views', async () => {
    const v1 = makeView({ pid: 'v1', isDefault: true });
    const v2 = makeView({ pid: 'v2', name: 'View 2' });
    mockService.getAccessibleViews.mockResolvedValue([v1, v2]);
    mockService.getDefaultView.mockResolvedValue(v1);
    mockService.setDefaultView.mockResolvedValue(v2);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      await result.current.setDefaultView('v2');
    });

    const newDefault = result.current.views.find((v: { pid: string }) => v.pid === 'v2');
    const oldDefault = result.current.views.find((v: { pid: string }) => v.pid === 'v1');
    expect(newDefault?.isDefault).toBe(true);
    expect(oldDefault?.isDefault).toBe(false);
  });

  it('duplicateView appends the duplicated view to the list', async () => {
    const v1 = makeView({ pid: 'v1' });
    mockService.getAccessibleViews.mockResolvedValue([v1]);
    mockService.getDefaultView.mockResolvedValue(null);
    const dup = makeView({ pid: 'v_dup', name: 'Copy of Default View' });
    mockService.duplicateView.mockResolvedValue(dup);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      await result.current.duplicateView('v1', 'Copy of Default View');
    });

    expect(result.current.views).toHaveLength(2);
    expect(result.current.views[1].pid).toBe('v_dup');
  });

  it('copyToPersonal adds a personal copy and selects it', async () => {
    const teamView = makeView({ pid: 'team1', name: 'Team View', scope: 'team' });
    const personalCopy = makeView({
      pid: 'personal_copy',
      name: 'My Team View',
      scope: 'personal',
      viewConfig: { filters: [] } as any,
    });
    mockService.getAccessibleViews.mockResolvedValue([teamView]);
    mockService.getDefaultView.mockResolvedValue(null);
    mockService.copyToPersonal.mockResolvedValue(personalCopy);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      await result.current.copyToPersonal('team1', {
        name: 'My Team View',
        viewConfig: { filters: [] } as any,
      });
    });

    expect(mockService.copyToPersonal).toHaveBeenCalledWith('team1', {
      name: 'My Team View',
      viewConfig: { filters: [] },
    });
    expect(result.current.views).toHaveLength(2);
    expect(result.current.currentView?.pid).toBe('personal_copy');
    expect(result.current.currentView?.scope).toBe('personal');
  });

  it('groupedViews groups by scope', async () => {
    const personal = makeView({ pid: 'p1', scope: 'personal' });
    const team = makeView({ pid: 't1', scope: 'team' });
    const role = makeView({ pid: 'r1', scope: 'role', roleId: 'role-1' });
    const global = makeView({ pid: 'g1', scope: 'global' });
    mockService.getAccessibleViews.mockResolvedValue([personal, team, role, global]);
    mockService.getDefaultView.mockResolvedValue(null);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.groupedViews.personal).toHaveLength(1);
    expect(result.current.groupedViews.team).toHaveLength(1);
    expect(result.current.groupedViews.role).toHaveLength(1);
    expect(result.current.groupedViews.global).toHaveLength(1);
  });

  it('reload triggers a fresh fetch', async () => {
    mockService.getAccessibleViews.mockResolvedValue([]);
    mockService.getDefaultView.mockResolvedValue(null);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(mockService.getAccessibleViews).toHaveBeenCalledTimes(1);

    await act(async () => {
      await result.current.reload();
    });
    expect(mockService.getAccessibleViews).toHaveBeenCalledTimes(2);
  });
});
