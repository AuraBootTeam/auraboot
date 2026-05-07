/**
 * useKanbanData.persistence.test.ts
 *
 * Phase 1 Task 2: pin contract that moveCard persists the column change via
 * PUT /api/dynamic/{pageKey}/{recordId} with optimistic update + rollback
 * when pageKey is supplied. Without pageKey, moveCard stays purely optimistic
 * (back-compat with existing callers).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useMemo } from 'react';
import { renderHook, act, waitFor } from '@testing-library/react';

// Mock http-client BEFORE importing the hook so the hook picks up the mock
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

import { fetchResult } from '~/shared/services/http-client';
import { useKanbanData } from '~/framework/smart/hooks/useKanbanData';
import type { KanbanDataSource } from '~/framework/smart/types/kanban';

const mockFetchResult = vi.mocked(fetchResult);

const STATIC_DATA = [
  { id: 'c1', title: 'Card 1', status: 'todo' },
  { id: 'c2', title: 'Card 2', status: 'todo' },
  { id: 'c3', title: 'Card 3', status: 'doing' },
];

function makeDataSource(): KanbanDataSource {
  return {
    type: 'static',
    groupByField: 'status',
    titleField: 'title',
    staticData: [...STATIC_DATA.map((r) => ({ ...r }))],
  };
}

function findCardColumn(columns: ReturnType<typeof useKanbanData>['columns'], cardId: string) {
  for (const col of columns) {
    if (col.cards.some((c) => c.id === cardId)) return col.id;
  }
  return null;
}

describe('useKanbanData - moveCard persistence', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('PUTs /api/dynamic/{pageKey}/{cardId} with the new groupByField value on move', async () => {
    mockFetchResult.mockResolvedValue({
      code: '0',
      desc: '',
      message: '',
      data: { id: 'c1', status: 'doing' },
    } as any);

    const { result } = renderHook(() => {
      const dataSource = useMemo(() => makeDataSource(), []);
      return useKanbanData({ dataSource, pageKey: 'task' });
    });

    await waitFor(() => expect(result.current.columns.length).toBeGreaterThan(0));
    expect(findCardColumn(result.current.columns, 'c1')).toBe('todo');

    await act(async () => {
      await result.current.moveCard('c1', 'todo', 'doing', 0);
    });

    expect(mockFetchResult).toHaveBeenCalledTimes(1);
    const [path, options] = mockFetchResult.mock.calls[0];
    expect(path).toBe('/api/dynamic/task/c1');
    expect(options).toMatchObject({
      method: 'put',
      params: { status: 'doing' },
    });
    // Optimistic update remains after success
    expect(findCardColumn(result.current.columns, 'c1')).toBe('doing');
  });

  it('rolls back the optimistic update when the server returns code !== SUCCESS', async () => {
    mockFetchResult.mockResolvedValue({
      code: 'PERMISSION_DENIED',
      desc: 'forbidden',
      message: 'forbidden',
      data: null,
    } as any);

    const onMoveError = vi.fn();
    const { result } = renderHook(() => {
      const dataSource = useMemo(() => makeDataSource(), []);
      return useKanbanData({ dataSource, pageKey: 'task', onMoveError });
    });

    await waitFor(() => expect(result.current.columns.length).toBeGreaterThan(0));

    await act(async () => {
      await result.current.moveCard('c1', 'todo', 'doing', 0);
    });

    // Card returned to source column
    await waitFor(() => {
      expect(findCardColumn(result.current.columns, 'c1')).toBe('todo');
    });
    expect(onMoveError).toHaveBeenCalledTimes(1);
    const errArg = onMoveError.mock.calls[0][0];
    expect(errArg.code).toBe('PERMISSION_DENIED');
    expect(errArg.message).toBe('forbidden');
  });

  it('rolls back when the network call rejects', async () => {
    mockFetchResult.mockRejectedValue(new Error('network down'));

    const onMoveError = vi.fn();
    const { result } = renderHook(() => {
      const dataSource = useMemo(() => makeDataSource(), []);
      return useKanbanData({ dataSource, pageKey: 'task', onMoveError });
    });

    await waitFor(() => expect(result.current.columns.length).toBeGreaterThan(0));

    await act(async () => {
      await result.current.moveCard('c1', 'todo', 'doing', 0);
    });

    await waitFor(() => {
      expect(findCardColumn(result.current.columns, 'c1')).toBe('todo');
    });
    expect(onMoveError).toHaveBeenCalledTimes(1);
    expect(onMoveError.mock.calls[0][0].message).toContain('network down');
  });

  it('skips PUT and stays purely optimistic when pageKey is not supplied', async () => {
    const { result } = renderHook(() => {
      const dataSource = useMemo(() => makeDataSource(), []);
      return useKanbanData({ dataSource });
    });

    await waitFor(() => expect(result.current.columns.length).toBeGreaterThan(0));

    await act(async () => {
      await result.current.moveCard('c1', 'todo', 'doing', 0);
    });

    expect(mockFetchResult).not.toHaveBeenCalled();
    expect(findCardColumn(result.current.columns, 'c1')).toBe('doing');
  });
});
