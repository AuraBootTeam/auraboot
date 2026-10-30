import { renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useListSavedViewState } from '../useListSavedViewState';

const { service } = vi.hoisted(() => ({ service: { getAccessibleViews: vi.fn(), getDefaultView: vi.fn() } }));
vi.mock('~/shared/services/savedViewService', () => ({ savedViewService: service }));
const saved = { pid: 'personal', name: 'Default', modelCode: 'project', pageKey: 'project_list', scope: 'personal', isImplicit: true, isDefault: true, viewType: 'table', viewConfig: { columns: [{ fieldCode: 'name', width: 310 }] } };
const input = { modelCode: 'project', pageKey: 'project_list', schema: { blocks: [] }, hideSavedViews: true, skipListData: false, hideQuickFilters: true, hasPermission: (code: string) => code === 'dashboard.saved_view.read', user: null, activeViewType: 'table' as const, activeTab: 'all', filters: {}, chipFilters: [], t: (key: string) => key, showSuccessToast: vi.fn(), showErrorToast: vi.fn() };

describe('saved column settings with a hidden view switcher', () => {
  beforeEach(() => {
    service.getAccessibleViews.mockReset().mockResolvedValue([saved]);
    service.getDefaultView.mockReset().mockResolvedValue(saved);
  });
  it('reads the persisted width on first load and after reopening despite hidden switcher chrome', async () => {
    const first = renderHook(() => useListSavedViewState(input));
    await waitFor(() => expect(first.result.current.effectiveViewConfig?.columns?.[0].width).toBe(310));
    first.unmount();
    const second = renderHook(() => useListSavedViewState(input));
    await waitFor(() => expect(second.result.current.effectiveViewConfig?.columns?.[0].width).toBe(310));
    expect(service.getDefaultView).toHaveBeenCalledTimes(2);
    second.unmount();
  });
  it.each([
    { ...input, skipListData: true },
    { ...input, schema: null },
    { ...input, hasPermission: () => false },
  ])('does not request saved views without applicable data, schema or read permission', async options => {
    const view = renderHook(() => useListSavedViewState(options));
    await Promise.resolve();
    expect(service.getAccessibleViews).not.toHaveBeenCalled();
    expect(service.getDefaultView).not.toHaveBeenCalled();
    expect(view.result.current.currentView).toBeNull();
    view.unmount();
  });
});
