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
    '403': { title: { en: 'You cannot view these batches' } },
    stale: { title: { en: 'Connection lost; preserved rows are read-only until retry' } },
  },
} as any;

describe('TableBlockRenderer data-source states', () => {
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
  });
});
