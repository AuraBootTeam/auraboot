import React from 'react';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { SubTableConfig } from '~/framework/meta/schemas/types';

const owned = vi.hoisted(() => ({
  transport: vi.fn(),
  drop: undefined as undefined | ((active: string, over: string, delta: number) => void | Promise<void>),
}));

vi.mock('react-router', () => ({ useNavigate: () => vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({ fetchResult: (...args: unknown[]) => owned.transport(...args) }));
vi.mock('~/contexts/AuthContext', () => ({ useAuth: () => ({ hasPermission: () => true }) }));
vi.mock('~/framework/meta/hooks/useTreeData', () => ({
  useTreeData: (rows: unknown[]) => ({ visibleRows: rows, toggleExpand: vi.fn() }),
}));
vi.mock('~/framework/meta/rendering/pages/hooks/useDictCache', () => ({
  useDictCache: () => ({ getDictItems: () => [], getDictLabel: () => undefined }),
}));
vi.mock('~/framework/meta/components/subtable/DndSubTableWrapper', () => ({
  DndSubTableWrapper: ({ children, onDragEnd }: { children: React.ReactNode; onDragEnd: typeof owned.drop }) => {
    owned.drop = onDragEnd;
    return <>{children}</>;
  },
}));
vi.mock('~/framework/meta/components/subtable/SortableSubTableRow', () => ({
  SortableSubTableRow: ({ id, children }: { id: string; children: React.ReactNode }) => (
    <tr data-testid={`sort-row-${id}`}>{children}</tr>
  ),
}));
vi.mock('../ToolbarBlockRenderer', () => ({ ToolbarBlockRenderer: () => null }));

import { SubTableViewer } from '../SubTableViewer';

const config: SubTableConfig = {
  childModel: 'owned_sort_rows',
  parentField: 'parent_pid',
  columns: [{ field: 'title', label: 'Title' }],
  sortable: true,
  sortField: 'sort_order',
};
const serverRows = [
  { pid: 'row-a', title: 'Alpha', sort_order: 1000 },
  { pid: 'row-b', title: 'Beta', sort_order: 2000 },
];
const rowOrder = () => screen.getAllByTestId(/^sort-row-/).map((row: HTMLElement) => row.getAttribute('data-testid'));
const reads = () => owned.transport.mock.calls.filter(([path]) => path === '/api/dynamic/owned_sort_rows/list');

function mountPendingSort() {
  let settle!: (result: { code: string; message?: string; data?: unknown }) => void;
  const pending = new Promise((resolve) => { settle = resolve; });
  owned.transport.mockImplementation((path: string) => {
    if (path === '/api/dynamic/owned_sort_rows/list') {
      return Promise.resolve({ code: '0', data: { records: serverRows.map((row) => ({ ...row })) } });
    }
    if (path === '/api/dynamic/owned_sort_rows/batch') return pending;
    throw new Error(`Unexpected sorting request: ${path}`);
  });
  render(<SubTableViewer config={config} parentRecordPid="parent-public-pid" isEditable locale="en" />);
  return settle;
}

async function startSort() {
  await screen.findByTestId('sort-row-row-a');
  expect(rowOrder()).toEqual(['sort-row-row-a', 'sort-row-row-b']);
  let completion!: Promise<void>;
  act(() => { completion = Promise.resolve(owned.drop!('row-a', 'row-b', 0)); });
  expect(rowOrder()).toEqual(['sort-row-row-b', 'sort-row-row-a']);
  expect(owned.transport).toHaveBeenCalledWith('/api/dynamic/owned_sort_rows/batch', {
    method: 'put',
    token: undefined,
    params: [{ pid: 'row-b', sort_order: 1000 }, { pid: 'row-a', sort_order: 2000 }],
  });
  return { completion };
}

describe('SubTableViewer sorting acknowledgement', () => {
  beforeEach(() => {
    owned.transport.mockReset();
    owned.drop = undefined;
    vi.spyOn(console, 'error').mockImplementation(() => {}).mockClear();
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it.each([
    ['authorization rejection', '403'],
    ['server failure', '500'],
    ['business rejection', 'SORT_REJECTED'],
    ['network failure', 'NETWORK_ERROR'],
  ])('reloads authoritative rows after a returned %s', async (_name, code) => {
    const settle = mountPendingSort();
    const { completion } = await startSort();
    await act(async () => {
      settle({ code, message: 'Order was not persisted' });
      await completion;
    });
    await waitFor(() => expect(reads()).toHaveLength(2));
    await screen.findByTestId('sort-row-row-a');
    expect(rowOrder()).toEqual(['sort-row-row-a', 'sort-row-row-b']);
  });

  it('retains the acknowledged order without a recovery read after success', async () => {
    const settle = mountPendingSort();
    const { completion } = await startSort();
    await act(async () => {
      settle({ code: '0', data: { updated: 2 } });
      await completion;
    });
    expect(rowOrder()).toEqual(['sort-row-row-b', 'sort-row-row-a']);
    expect(reads()).toHaveLength(1);
    expect(console.error).not.toHaveBeenCalled();
  });
});
