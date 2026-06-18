/**
 * CardGridBlockRenderer.test.tsx
 *
 * TDD tests for the generic card-grid block renderer.
 * Cases:
 *  1. Renders one card per row with mapped title/description/category text.
 *  2. Empty state (card-grid-empty) when 0 rows.
 *  3. Loading state (card-grid-loading) and error state (card-grid-error).
 *  4. Clicking a card action calls handleAction with the row.
 */

import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { CardGridBlockRenderer } from './CardGridBlockRenderer';

// ---------------------------------------------------------------------------
// Mock useActionHandler — capture the handleAction call
// ---------------------------------------------------------------------------
const mockHandleAction = vi.fn();

vi.mock('~/framework/meta/hooks/useActionHandler', () => ({
  useActionHandler: () => ({
    handleAction: mockHandleAction,
    loading: false,
    error: null,
    setError: vi.fn(),
    activeTask: null,
    clearActiveTask: vi.fn(),
  }),
}));

// useNavigate is needed by useActionHandler (in the real hook), but since we
// mock the hook module we only need to make sure the import doesn't blow up.
vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>();
  return { ...actual, useNavigate: () => vi.fn() };
});

// Auth / Toast contexts — not needed for unit tests of the renderer itself.
vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ token: 'test-token' }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showSuccessToast: vi.fn(),
    showErrorToast: vi.fn(),
    showWarningToast: vi.fn(),
    showInfoToast: vi.fn(),
  }),
}));

// ---------------------------------------------------------------------------
// Runtime mock factory
// ---------------------------------------------------------------------------
function makeRuntime({
  data = [] as any[],
  state = {} as { loading?: boolean; error?: any; data?: any },
} = {}) {
  const getData = vi.fn().mockReturnValue(data);
  const getState = vi.fn().mockReturnValue(state);
  const subscribe = vi.fn().mockReturnValue(() => {});
  const reload = vi.fn();

  const dataSourceManager = {
    getData,
    getState,
    subscribe,
    reload,
  };

  const context = {
    locale: 'zh-CN',
    t: (key: string) => key,
  };

  return {
    getContext: () => context,
    getDataSourceManager: () => dataSourceManager,
    getSchema: () => ({ modelCode: 'test', id: 'test' }),
    getEvaluator: () => ({ evaluateCondition: () => true }),
    getStateManager: () => null,
    getScopeId: () => 'root',
    getShowToast: () => undefined,
    _dataSourceManager: dataSourceManager,
  };
}

// ---------------------------------------------------------------------------
// Shared block config
// ---------------------------------------------------------------------------
const baseBlock: any = {
  id: 'cg1',
  blockType: 'card-grid',
  dataSource: 'templates',
  titleField: 'name',
  descriptionField: 'description',
  categoryField: 'category',
  cardActions: [
    { code: 'install', label: 'Install', action: { type: 'command', command: 'template:install' } },
  ],
};

const sampleRows = [
  { pid: 'r1', name: 'Template A', description: 'Desc A', category: 'CRM' },
  { pid: 'r2', name: 'Template B', description: 'Desc B', category: 'IoT' },
];

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
describe('CardGridBlockRenderer', () => {
  beforeEach(() => {
    mockHandleAction.mockReset();
  });

  it('renders one card per row with mapped title/description/category text', () => {
    const runtime = makeRuntime({ data: sampleRows });
    render(<CardGridBlockRenderer block={baseBlock} runtime={runtime as any} />);

    const cards = screen.getAllByTestId('card-grid-card');
    expect(cards).toHaveLength(2);

    expect(screen.getByText('Template A')).toBeTruthy();
    expect(screen.getByText('Desc A')).toBeTruthy();
    expect(screen.getByText('CRM')).toBeTruthy();

    expect(screen.getByText('Template B')).toBeTruthy();
    expect(screen.getByText('Desc B')).toBeTruthy();
    expect(screen.getByText('IoT')).toBeTruthy();
  });

  it('shows empty state when there are 0 rows', () => {
    const runtime = makeRuntime({ data: [] });
    render(<CardGridBlockRenderer block={baseBlock} runtime={runtime as any} />);

    expect(screen.getByTestId('card-grid-empty')).toBeTruthy();
    expect(screen.queryByTestId('card-grid-card')).toBeNull();
  });

  it('shows loading state when data source is loading and no rows yet', () => {
    const runtime = makeRuntime({
      data: [],
      state: { loading: true, data: null },
    });
    render(<CardGridBlockRenderer block={baseBlock} runtime={runtime as any} />);

    expect(screen.getByTestId('card-grid-loading')).toBeTruthy();
    expect(screen.queryByTestId('card-grid-card')).toBeNull();
  });

  it('shows error state when data source has an error', () => {
    const runtime = makeRuntime({
      data: [],
      state: { error: new Error('fetch failed') },
    });
    render(<CardGridBlockRenderer block={baseBlock} runtime={runtime as any} />);

    const errorEl = screen.getByTestId('card-grid-error');
    expect(errorEl).toBeTruthy();
    expect(errorEl.getAttribute('role')).toBe('alert');
  });

  it('clicking a card action calls handleAction with the row', () => {
    const runtime = makeRuntime({ data: sampleRows });
    render(<CardGridBlockRenderer block={baseBlock} runtime={runtime as any} />);

    const installBtns = screen.getAllByTestId('card-grid-action-install');
    expect(installBtns).toHaveLength(2);

    fireEvent.click(installBtns[0]);

    expect(mockHandleAction).toHaveBeenCalledTimes(1);
    const [calledButton, calledRow] = mockHandleAction.mock.calls[0];
    expect(calledButton.code).toBe('install');
    expect(calledRow.pid).toBe('r1');
  });
});
