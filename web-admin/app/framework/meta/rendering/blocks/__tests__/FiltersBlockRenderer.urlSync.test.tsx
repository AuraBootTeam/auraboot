import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { FiltersBlockRenderer } from '../FiltersBlockRenderer';
import { ScopedStateManager } from '../../../runtime/state/scoped-state';
import Input from '~/ui/smart/form/Input';

const urlState = vi.hoisted(() => ({
  params: new URLSearchParams(),
  setSearchParams: vi.fn(),
}));

vi.mock('react-router', async () => ({
  ...(await vi.importActual<any>('react-router')),
  useActionData: () => null,
  useSearchParams: () => [urlState.params, urlState.setSearchParams],
  useNavigate: () => vi.fn(),
  useLocation: () => ({ hash: '' }),
}));
vi.mock('~/contexts/AuthContext', () => ({ usePermission: () => true }));
vi.mock('~/framework/meta/hooks/useActionHandler', () => ({
  useActionHandler: () => ({ executeCommand: vi.fn() }),
}));
vi.mock('~/framework/meta/rendering/components/ComponentLoader', () => ({
  ComponentLoader: ({ props }: any) => <Input {...props} />,
}));

const FIELDS = [
  { field: 'query', component: 'SmartInput', label: { 'zh-CN': '商品搜索' } },
  { field: 'status', component: 'SmartSelect', label: { 'zh-CN': '商品状态' } },
];

function makeRuntime() {
  const manager = new ScopedStateManager({ locale: 'en', theme: 'light' } as any);
  manager.createScope('filters');
  const runtime = {
    getContext: () => ({ ...manager.getContext('filters'), form: manager.getScope('filters')?.form ?? {}, t: (key: string) => key }),
    getStateManager: () => manager,
    getScopeId: () => 'filters',
    getDataSourceManager: () => ({ reload: vi.fn(), notifyStateChanged: vi.fn() }),
    triggerFieldLinkage: vi.fn(),
  } as any;
  return { manager, runtime };
}

describe('filters block URL sync', () => {
  it('seeds declared fields from the URL query and restores the filtered view on mount', async () => {
    urlState.params.set('query', '保温杯');
    const { manager, runtime } = makeRuntime();
    render(
      <FiltersBlockRenderer
        runtime={runtime}
        block={{ id: 'filters', blockType: 'filters', urlSync: true, fields: FIELDS,
          onSearch: { action: 'state.set', args: { productFilters: '${form}' } } } as any}
      />,
    );
    await waitFor(() => expect(manager.getScope('filters')?.state?.productFilters?.query).toBe('保温杯'));
    expect(urlState.params.get('query')).toBe('保温杯');
  });

  it('writes non-empty field values back to the URL after search', async () => {
    urlState.params = new URLSearchParams();
    const { manager, runtime } = makeRuntime();
    manager.updateField('filters', 'status', 'DRAFT');
    render(
      <FiltersBlockRenderer
        runtime={runtime}
        block={{ id: 'filters', blockType: 'filters', urlSync: true, fields: FIELDS,
          onSearch: { action: 'state.set', args: { productFilters: '${form}' } } } as any}
      />,
    );
    const searchButton = screen.getByTestId('filter-btn-search');
    await waitFor(() => expect(searchButton).toBeEnabled());
    searchButton.click();
    await waitFor(() => expect(urlState.setSearchParams).toHaveBeenCalled());
    const written = urlState.setSearchParams.mock.calls[urlState.setSearchParams.mock.calls.length - 1][0] as URLSearchParams;
    expect(written.get('status')).toBe('DRAFT');
    expect(written.get('query')).toBeNull();
  });

  it('clears URL params on reset and never engages without urlSync', async () => {
    urlState.params = new URLSearchParams('query=leftover');
    const { manager, runtime } = makeRuntime();
    render(
      <FiltersBlockRenderer
        runtime={runtime}
        block={{ id: 'filters', blockType: 'filters', resetFieldsOnReset: true, fields: FIELDS,
          onSearch: { action: 'state.set', args: { productFilters: '${form}' } },
          onReset: { action: 'state.set', args: { productFilters: null } } } as any}
      />,
    );
    const resetButton = screen.getByTestId('filter-btn-reset');
    await waitFor(() => expect(resetButton).toBeEnabled());
    urlState.setSearchParams.mockClear();
    resetButton.click();
    await waitFor(() => expect(resetButton).toBeEnabled());
    expect(urlState.setSearchParams).not.toHaveBeenCalled();
    expect(manager.getScope('filters')?.state?.productFilters ?? null).toBeNull();
  });
});
