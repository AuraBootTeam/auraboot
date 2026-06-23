import { renderHook, waitFor, act } from '@testing-library/react';
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

import { useSavedViews } from '../useSavedViews';
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
    const { result } = renderHook(() =>
      useSavedViews({ modelCode: 'order', autoLoad: false }),
    );
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

  it('selectView does nothing for unknown pid', async () => {
    const v1 = makeView({ pid: 'v1' });
    mockService.getAccessibleViews.mockResolvedValue([v1]);
    mockService.getDefaultView.mockResolvedValue(null);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => result.current.selectView('nonexistent'));
    expect(result.current.currentView?.pid).toBe('v1');
  });

  it('createView adds new view and selects it', async () => {
    mockService.getAccessibleViews.mockResolvedValue([]);
    mockService.getDefaultView.mockResolvedValue(null);
    const newView = makeView({ pid: 'v_new', name: 'My New View' });
    mockService.createView.mockResolvedValue(newView);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      await result.current.createView({ name: 'My New View', modelCode: 'order', scope: 'personal', viewType: 'table', viewConfig: {} as any });
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
    const global = makeView({ pid: 'g1', scope: 'global' });
    mockService.getAccessibleViews.mockResolvedValue([personal, team, global]);
    mockService.getDefaultView.mockResolvedValue(null);

    const { result } = renderHook(() => useSavedViews({ modelCode: 'order' }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.groupedViews.personal).toHaveLength(1);
    expect(result.current.groupedViews.team).toHaveLength(1);
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
