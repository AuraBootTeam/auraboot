import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';

vi.mock('~/shared/services/chartDataService', () => ({
  chartDataService: { fetchChartData: vi.fn() },
}));
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

import { chartDataService } from '~/shared/services/chartDataService';
import { fetchResult } from '~/shared/services/http-client';
import { useKanbanData } from '~/framework/smart/hooks/useKanbanData';
import type { KanbanColumn, KanbanDataSource } from '~/framework/smart/types/kanban';

const aggregate = vi.mocked(chartDataService.fetchChartData);
const list = vi.mocked(fetchResult);

const source: KanbanDataSource = {
  type: 'aggregate',
  modelCode: 'crm_opportunity_common',
  groupByField: 'stage',
  idField: 'pid',
  titleField: 'name',
  limit: 2,
  aggregations: [
    { field: 'amount', function: 'sum', label: 'Pipeline' },
    { field: 'pid', function: 'count', label: 'Quantity' },
  ],
};

describe('useKanbanData - authoritative full-pipeline aggregates', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    aggregate.mockResolvedValue({
      rows: [
        { stage: 'discovery', kanban_total_count: 5333, kanban_aggregation_0: 79993332 },
        { stage: 'negotiation', kanban_total_count: 2000, kanban_aggregation_0: 30005000 },
      ],
      summary: {},
      meta: { dimensions: ['stage'], metrics: ['kanban_total_count'] },
    });
    list.mockImplementation(async (_path, options) => {
      const filters = JSON.parse(String((options as any).params.filters));
      const stage = filters.at(-1).value;
      return {
        code: '0',
        data: {
          records: [
            { pid: `${stage}-1`, name: `${stage} 1`, stage, amount: 10 },
            { pid: `${stage}-2`, name: `${stage} 2`, stage, amount: 20 },
          ],
        },
      } as any;
    });
  });

  it('uses server totals instead of presenting the bounded card sample as the full pipeline', async () => {
    const { result } = renderHook(() => useKanbanData({ dataSource: source }));

    await waitFor(() => expect(result.current.loading).toBe(false));
    const discovery = result.current.columns.find(
      (column: KanbanColumn) => column.id === 'discovery',
    );
    const negotiation = result.current.columns.find(
      (column: KanbanColumn) => column.id === 'negotiation',
    );

    expect(discovery).toMatchObject({
      count: 5333,
      loadedCount: 2,
      hasMore: true,
      aggregations: { Pipeline: 79993332, Quantity: 5333 },
    });
    expect(negotiation).toMatchObject({ count: 2000, loadedCount: 2, hasMore: true });
    expect(list).toHaveBeenCalledTimes(2);
    expect(aggregate).toHaveBeenCalledWith(
      expect.objectContaining({
        modelCode: 'crm_opportunity_common',
        dimensions: ['stage'],
        metrics: expect.arrayContaining([
          expect.objectContaining({ alias: 'kanban_total_count', aggregation: 'count' }),
          expect.objectContaining({ alias: 'kanban_aggregation_0', aggregation: 'sum' }),
        ]),
      }),
    );
  });

  it('fails closed when the authoritative aggregate cannot be loaded', async () => {
    aggregate.mockRejectedValueOnce(new Error('aggregate unavailable'));
    const { result } = renderHook(() => useKanbanData({ dataSource: source }));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error?.message).toContain('aggregate unavailable');
    expect(result.current.columns).toEqual([]);
    expect(list).not.toHaveBeenCalled();
  });
});
