/**
 * useKanbanData.dictColumns.test.ts
 *
 * Gap 2 (backlog 2026-05-08): when groupByDictItems are provided, the hook
 * must render columns for every dict enum value in dict order — including
 * stages with zero cards. Drift values (in data but not in dict) trail.
 */
import { describe, it, expect, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

import { useKanbanData } from '~/framework/smart/hooks/useKanbanData';
import type { KanbanDataSource } from '~/framework/smart/types/kanban';

function staticSource(rows: Record<string, unknown>[]): KanbanDataSource {
  return {
    type: 'static',
    groupByField: 'stage',
    titleField: 'name',
    staticData: rows,
  };
}

const FULL_DICT = [
  { value: 'discovery', label: 'Discovery' },
  { value: 'qualification', label: 'Qualification' },
  { value: 'proposal', label: 'Proposal' },
  { value: 'negotiation', label: 'Negotiation' },
  { value: 'closed_won', label: 'Closed Won' },
];

describe('useKanbanData - dict-driven columns', () => {
  it('renders all dict columns even when most stages have zero cards', async () => {
    const dataSource = staticSource([
      { id: 'o1', name: 'Opp 1', stage: 'discovery' },
      { id: 'o2', name: 'Opp 2', stage: 'qualification' },
    ]);

    const { result } = renderHook(() =>
      useKanbanData({ dataSource, groupByDictItems: FULL_DICT }),
    );

    await waitFor(() => expect(result.current.columns.length).toBe(5));

    const ids = result.current.columns.map((c: { id: string; cards: unknown[] }) => c.id);
    expect(ids).toEqual([
      'discovery',
      'qualification',
      'proposal',
      'negotiation',
      'closed_won',
    ]);

    const counts = Object.fromEntries(
      result.current.columns.map((c: { id: string; cards: unknown[] }) => [c.id, c.cards.length]),
    );
    expect(counts).toEqual({
      discovery: 1,
      qualification: 1,
      proposal: 0,
      negotiation: 0,
      closed_won: 0,
    });
  });

  it('preserves dict order regardless of data appearance order', async () => {
    // Data appears in reverse-of-dict order; dict order must still win.
    const dataSource = staticSource([
      { id: 'a', name: 'A', stage: 'closed_won' },
      { id: 'b', name: 'B', stage: 'discovery' },
      { id: 'c', name: 'C', stage: 'proposal' },
    ]);

    const { result } = renderHook(() =>
      useKanbanData({ dataSource, groupByDictItems: FULL_DICT }),
    );

    await waitFor(() => expect(result.current.columns.length).toBe(5));
    expect(result.current.columns.map((c: { id: string; cards: unknown[] }) => c.id)).toEqual([
      'discovery',
      'qualification',
      'proposal',
      'negotiation',
      'closed_won',
    ]);
  });

  it('appends fallback column when data has stage not in dict (drift)', async () => {
    const dataSource = staticSource([
      { id: 'o1', name: 'Opp 1', stage: 'discovery' },
      // 'archived' is not in FULL_DICT — should appear as trailing fallback column.
      { id: 'o2', name: 'Opp 2', stage: 'archived' },
    ]);

    const { result } = renderHook(() =>
      useKanbanData({ dataSource, groupByDictItems: FULL_DICT }),
    );

    await waitFor(() => expect(result.current.columns.length).toBe(6));

    const ids = result.current.columns.map((c: { id: string; cards: unknown[] }) => c.id);
    // Dict columns first in dict order, drift column trailing.
    expect(ids.slice(0, 5)).toEqual([
      'discovery',
      'qualification',
      'proposal',
      'negotiation',
      'closed_won',
    ]);
    expect(ids[5]).toBe('archived');
    const archived = result.current.columns.find(
      (c: { id: string; cards: unknown[] }) => c.id === 'archived',
    );
    expect(archived?.cards.length).toBe(1);
  });

  it('falls back to data-derived columns when groupByDictItems is omitted (back-compat)', async () => {
    const dataSource = staticSource([
      { id: 'o1', name: 'Opp 1', stage: 'discovery' },
      { id: 'o2', name: 'Opp 2', stage: 'qualification' },
    ]);

    const { result } = renderHook(() => useKanbanData({ dataSource }));

    await waitFor(() => expect(result.current.columns.length).toBe(2));
    expect(result.current.columns.map((c: { id: string; cards: unknown[] }) => c.id).sort()).toEqual([
      'discovery',
      'qualification',
    ]);
  });
});
