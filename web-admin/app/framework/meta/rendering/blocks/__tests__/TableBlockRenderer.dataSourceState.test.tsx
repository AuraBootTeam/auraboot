import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { TableBlockRenderer } from '../TableBlockRenderer';

vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ token: 'token', hasPermission: () => true }),
}));
vi.mock('react-router', () => ({ useNavigate: () => vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(async () => ({ code: '0', data: [] })),
}));

function runtime(rows: any[], state: any, reload = vi.fn()): SchemaRuntime {
  const context = { locale: 'en', t: (key: string) => key, state: {} };
  return {
    getContext: () => context,
    getEvaluator: () => ({
      evaluateCondition: () => true,
      evaluateTemplate: (v: any) => v,
      evaluateObject: (v: any) => v,
    }),
    getDataSourceManager: () => ({
      getData: () => rows,
      getState: () => state,
      subscribe: () => vi.fn(),
      reload,
    }),
    getStateManager: () => ({ updateState: vi.fn(), getContext: () => context }),
    getScopeId: () => 'scope',
    getSchema: () => ({ id: 'state-test', modelCode: 'test_model' }),
  } as unknown as SchemaRuntime;
}

const block = {
  id: 'state-table',
  blockType: 'table',
  dataSource: 'rows',
  columns: [{ field: 'name', label: 'Name' }],
  states: {
    loading: { title: { en: 'Loading onboarding batches…' } },
    '403': {
      title: { en: 'You cannot view these batches' },
      description: { en: 'Ask the owner to grant read access, then retry.' },
    },
    stale: { title: { en: 'Connection lost; preserved rows are read-only until retry' } },
  },
} as any;

describe('TableBlockRenderer data-source states', () => {
  it('discloses the exact long value locally without selecting a different record', () => {
    const text='Material A\nCODE-A\n'+ 'Full specification '.repeat(12);
    render(<TableBlockRenderer block={{...block,columns:[{field:'name',label:'Name',textPreviewLength:80,verticalAlign:'top'}]}} runtime={runtime([{pid:'row-a',name:text}],{data:[{pid:'row-a',name:text}]})} />);
    const details=screen.getByTestId('table-text-details') as HTMLDetailsElement;
    expect(details.open).toBe(false);fireEvent.click(screen.getByText('Full text'));expect(details.open).toBe(true);
    expect(details.querySelector('div')?.textContent).toBe(text);
    expect(details.closest('td')?.style.verticalAlign).toBe('top');
  });
  it('keeps empty guidance outside the horizontally scrolling column canvas', () => {
    render(
      <TableBlockRenderer
        block={
          {
            ...block,
            empty: {
              title: { en: 'No images yet' },
              description: { en: 'Add an image or select another product.' },
            },
          } as any
        }
        runtime={runtime([], { data: [], loading: false, error: null })}
      />,
    );
    const guidance = screen.getByRole('status');
    expect(guidance).toHaveTextContent('No images yet');
    expect(guidance).toHaveTextContent('Add an image or select another product.');
    expect(guidance.closest('[data-testid="table-block"]')).toBeNull();
    expect(screen.getByRole('columnheader', { name: 'Name' })).toBeVisible();
  });
  it('distinguishes loading from an empty result', () => {
    render(
      <TableBlockRenderer
        block={block}
        runtime={runtime([], { data: null, loading: true, error: null })}
      />,
    );
    expect(screen.getByTestId('table-loading-state')).toHaveTextContent(
      'Loading onboarding batches…',
    );
    expect(screen.queryByText('No data')).not.toBeInTheDocument();
  });

  it('renders an actionable status-specific error and retries', () => {
    const reload = vi.fn();
    const error = Object.assign(new Error('Forbidden'), { status: 403 });
    render(
      <TableBlockRenderer
        block={block}
        runtime={runtime([], { data: null, loading: false, error }, reload)}
      />,
    );
    expect(screen.getByTestId('table-error-state-403')).toHaveTextContent(
      'You cannot view these batches',
    );
    expect(screen.getByText('Ask the owner to grant read access, then retry.')).toBeInTheDocument();
    expect(screen.queryByText('Forbidden')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('table-error-retry'));
    expect(reload).toHaveBeenCalledWith('rows');
  });

  it('keeps stale rows visible and names the disconnected state', () => {
    const error = Object.assign(new Error('Service unavailable'), { status: 503 });
    render(
      <TableBlockRenderer
        block={block}
        runtime={runtime([{ pid: '1', name: 'Batch A' }], { data: [], loading: false, error })}
      />,
    );
    expect(screen.getByTestId('table-stale-state')).toHaveTextContent('Connection lost');
    expect(screen.getByText('Batch A')).toBeInTheDocument();
    expect(screen.getByTestId('table-row-1')).toHaveAttribute('data-record-pid', '1');
  });

  it('keeps preserved rows in place without an inline status during background refresh', () => {
    render(
      <TableBlockRenderer
        block={block}
        runtime={runtime([{ pid: '1', name: 'Batch A' }], {
          data: [],
          loading: true,
          error: null,
        })}
      />,
    );

    expect(screen.queryByTestId('table-refreshing-state')).not.toBeInTheDocument();
    expect(screen.getByText('Batch A')).toBeInTheDocument();
  });
  it('announces hierarchical expansion and preserves keyboard button semantics', () => {
    const rows = [
      { pid: 'parent', name: 'Apparel' },
      { pid: 'child', name: 'Tops', parentPid: 'parent' },
    ];
    render(
      <TableBlockRenderer
        block={
          { ...block, treeConfig: { parentField: 'parentPid', defaultExpanded: false } } as any
        }
        runtime={runtime(rows, { data: rows, loading: false, error: null })}
      />,
    );
    const expand = screen.getByRole('button', { name: 'Expand child rows' });
    expect(expand).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('Tops')).not.toBeInTheDocument();
    fireEvent.click(expand);
    expect(screen.getByRole('button', { name: 'Collapse child rows' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    expect(screen.getByText('Tops')).toBeInTheDocument();
  });
  it('searches the full navigation projection and binds the selected row including its version', () => {
    const rows = Array.from({ length: 125 }, (_, index) => ({
      pid: `root-${index}`,
      name: `Category ${index}`,
      childCount: index,
      rowVersion: 7,
    }));
    const r = runtime(rows, {
      data: { records: rows, total: rows.length },
      loading: false,
      error: null,
    });
    const context = { ...r.getContext(), state: {} as Record<string, any> };
    const updateState = vi.fn((_scope: string, key: string, value: any) => {
      context.state[key] = value;
    });
    r.getContext = () => context;
    r.getStateManager = () => ({ getContext: () => context, updateState }) as any;
    render(
      <TableBlockRenderer
        block={
          {
            ...block,
            title: 'Categories',
            table: {
              showTitle: true,
              selection: {
                mode: 'single',
                bind: 'selectedRoot',
                presentation: 'navigation',
                labelField: 'name',
                countField: 'childCount',
                searchPlaceholder: 'Search categories',
              },
            },
          } as any
        }
        runtime={r}
      />,
    );
    expect(screen.queryByRole('columnheader')).not.toBeInTheDocument();
    expect(screen.queryByTestId('table-server-pagination')).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search categories' }), {
      target: { value: 'Category 124' },
    });
    expect(screen.getAllByRole('button')).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: 'Category 124' }));
    expect(updateState).toHaveBeenLastCalledWith('scope', 'selectedRoot', rows[124]);
    expect(screen.getByRole('button', { name: 'Category 124' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'missing' } });
    expect(screen.getByRole('status')).toHaveTextContent('No matching items');
    expect(context.state.selectedRoot).toEqual(rows[124]);
  });

  it.each([0, 1, 20, 21])(
    'hides only an explicitly opted-in single-page footer with total %i',
    (total) => {
      const page = {
        records: Array.from({ length: Math.min(total, 20) }, (_, i) => ({
          pid: `row-${i}`,
          name: `Result ${i}`,
        })),
        total,
        current: 1,
        pageSize: 20,
      };
      const r = runtime(page as any, { data: page, loading: false, error: null });
      render(
        <TableBlockRenderer
          block={
            {
              ...block,
              table: {
                pagination: { mode: 'server', pageField: 'current', hideOnSinglePage: true },
              },
            } as any
          }
          runtime={r}
        />,
      );
      expect(Boolean(screen.queryByTestId('table-server-pagination'))).toBe(total > 20);
    },
  );
});
