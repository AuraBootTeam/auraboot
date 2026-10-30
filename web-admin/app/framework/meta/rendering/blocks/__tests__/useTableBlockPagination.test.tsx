import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { useTableBlockPagination } from '../useTableBlockPagination';

function fixture() {
  const context = {
    locale: 'en',
    t: (key: string) => key,
    state: { page: 2, size: 10, store: 'first' } as Record<string, unknown>,
  };
  const notifyStateChanged = vi.fn();
  const updateState = vi.fn((_scope: string, key: string, value: unknown) => {
    context.state[key] = value;
  });
  const runtime = {
    getContext: () => context,
    getScopeId: () => 'scope',
    getStateManager: () => ({ updateState }),
    getDataSourceManager: () => ({ notifyStateChanged }),
  } as unknown as SchemaRuntime;
  const state = {
    loading: false,
    error: null,
    lastFetch: 1,
    data: { records: [{ pid: 'second-page' }], total: 35, current: 2, pageSize: 10 },
  };
  const config = {
    pageSizeOptions: [10, 20],
    server: { pageState: 'page', pageSizeState: 'size', resetOn: ['state.store'] },
  };
  return { context, runtime, notifyStateChanged, updateState, state, config };
}
function Harness({ f }: { f: ReturnType<typeof fixture> }) {
  return <>{useTableBlockPagination(f.runtime, f.config, 'queue', f.state)}</>;
}
describe('data-source table server pagination', () => {
  it('uses API totals and binds the next page to reloadable DSL state', () => {
    const f = fixture();
    render(<Harness f={f} />);
    expect(screen.getByText(/35/)).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('pagination-next'));
    expect(f.context.state.page).toBe(3);
    expect(f.notifyStateChanged).toHaveBeenCalledWith('page');
  });
  it('changes size from page one and notifies after both state values are committed', () => {
    const f = fixture();
    f.notifyStateChanged.mockImplementation(() => {
      expect(f.context.state).toMatchObject({ page: 1, size: 20 });
    });
    render(<Harness f={f} />);
    fireEvent.change(screen.getByTestId('pagination-page-size'), { target: { value: '20' } });
    expect(f.notifyStateChanged).toHaveBeenCalledOnce();
    expect(f.notifyStateChanged).toHaveBeenCalledWith('size');
  });
  it('returns to page one when the chosen store changes', () => {
    const f = fixture();
    const view = render(<Harness f={f} />);
    f.context.state.store = 'second';
    view.rerender(<Harness f={f} />);
    expect(f.context.state.page).toBe(1);
    expect(f.notifyStateChanged).toHaveBeenCalledWith('page');
  });
  for (const condition of ['loading', 'error'])
    it('disables navigation for ' + condition, () => {
      const f = fixture();
      if (condition === 'loading') f.state.loading = true;
      else f.state.error = new Error('disconnected') as any;
      render(<Harness f={f} />);
      expect(screen.getByTestId('pagination-next')).toBeDisabled();
      expect(screen.getByTestId('pagination-page-size')).toBeDisabled();
    });
  it('rejects missing total metadata instead of treating a malformed response as an empty queue', () => {
    const f = fixture();
    delete (f.state.data as any).total;
    render(<Harness f={f} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Invalid pagination data');
    expect(screen.queryByTestId('pagination-next')).not.toBeInTheDocument();
  });
  it('keeps a zero-result page visible and its next action disabled', () => {
    const f = fixture();
    f.state.data = { records: [], total: 0, current: 1, pageSize: 10 };
    render(<Harness f={f} />);
    expect(screen.getByTestId('table-pagination')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-next')).toBeDisabled();
  });
});
