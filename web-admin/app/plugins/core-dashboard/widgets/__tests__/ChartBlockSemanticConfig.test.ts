/**
 * Tests for the semantic model config integration in dashboard chart widgets.
 *
 * Covers:
 *  1. semanticModelCode defaults to undefined in widget schemas
 *  2. DataSourceConfig type accepts semanticModelCode
 *  3. widgetRegistry chart schemas include the semanticModelCode field
 *  4. useChartData serialises semanticModelCode into the request payload
 *  5. useChartData omits semanticModelCode from the request when not set
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { widgetRegistry } from '../widgetRegistry';
import type { DataSourceConfig } from '../../types';
import type { ChartDataSource } from '~/framework/smart/types/chart';

// ---------------------------------------------------------------------------
// Case 1 – semanticModelCode defaults to undefined in chart widget schemas
// ---------------------------------------------------------------------------
describe('chart widget configSchema – semanticModelCode field', () => {
  const CHART_TYPES = [
    'smart-bar-chart',
    'smart-line-chart',
    'smart-pie-chart',
    'smart-area-chart',
    'smart-number-card',
  ] as const;

  it.each(CHART_TYPES)(
    '%s has a semanticModelCode property schema entry',
    (widgetType) => {
      const def = widgetRegistry.get(widgetType);
      expect(def, `${widgetType} must be registered`).toBeDefined();

      const schema = def!.configSchema ?? [];
      const semanticField = schema.find(
        (s) => s.key === 'dataSource.semanticModelCode',
      );
      expect(
        semanticField,
        `${widgetType} configSchema must include dataSource.semanticModelCode`,
      ).toBeDefined();
      expect(semanticField!.type).toBe('semantic-model-select');
    },
  );

  it('semanticModelCode schema has no defaultValue (opt-in, not set by default)', () => {
    const def = widgetRegistry.get('smart-bar-chart');
    const schema = def!.configSchema ?? [];
    const semanticField = schema.find((s) => s.key === 'dataSource.semanticModelCode');
    expect(semanticField!.defaultValue).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// Case 2 – DataSourceConfig accepts semanticModelCode without TS error
// ---------------------------------------------------------------------------
describe('DataSourceConfig type – semanticModelCode', () => {
  it('allows semanticModelCode to be set', () => {
    const config: DataSourceConfig = {
      type: 'aggregate',
      modelCode: 'order',
      semanticModelCode: 'finance_kpi',
    };
    expect(config.semanticModelCode).toBe('finance_kpi');
  });

  it('allows semanticModelCode to be omitted (optional field)', () => {
    const config: DataSourceConfig = {
      type: 'aggregate',
      modelCode: 'order',
    };
    expect(config.semanticModelCode).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// Case 3 – ChartDataSource type accepts semanticModelCode
// ---------------------------------------------------------------------------
describe('ChartDataSource type – semanticModelCode', () => {
  it('carries semanticModelCode through to the request shape', () => {
    const ds: ChartDataSource = {
      type: 'aggregate',
      modelCode: 'order',
      metrics: [{ field: 'id', aggregation: 'count' }],
      semanticModelCode: 'sales_semantic',
    };
    expect(ds.semanticModelCode).toBe('sales_semantic');
  });
});

// ---------------------------------------------------------------------------
// Case 4 – serialisation: semanticModelCode is included when present
// ---------------------------------------------------------------------------
describe('useChartData – request serialisation', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
  });

  it('includes semanticModelCode in the AggregateQueryRequest when set', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      rows: [],
      summary: {},
      meta: { dimensions: [], metrics: [] },
    });

    vi.doMock('~/shared/services/chartDataService', () => ({
      chartDataService: { fetchChartData: mockFetch },
      default: { fetchChartData: mockFetch },
    }));

    const { useChartData } = await import('~/framework/smart/hooks/useChartData');
    const { renderHook, waitFor } = await import('@testing-library/react');

    const dataSource: ChartDataSource = {
      type: 'aggregate',
      modelCode: 'order',
      metrics: [{ field: 'id', aggregation: 'count' }],
      semanticModelCode: 'sales_semantic',
    };

    renderHook(() => useChartData({ dataSource }));

    await waitFor(() => expect(mockFetch).toHaveBeenCalled());

    const calledWith = mockFetch.mock.calls[0][0];
    expect(calledWith.semanticModelCode).toBe('sales_semantic');
  });

  it('omits semanticModelCode from the request when not configured', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      rows: [],
      summary: {},
      meta: { dimensions: [], metrics: [] },
    });

    vi.doMock('~/shared/services/chartDataService', () => ({
      chartDataService: { fetchChartData: mockFetch },
      default: { fetchChartData: mockFetch },
    }));

    const { useChartData } = await import('~/framework/smart/hooks/useChartData');
    const { renderHook, waitFor } = await import('@testing-library/react');

    const dataSource: ChartDataSource = {
      type: 'aggregate',
      modelCode: 'order',
      metrics: [{ field: 'id', aggregation: 'count' }],
      // no semanticModelCode
    };

    renderHook(() => useChartData({ dataSource }));

    await waitFor(() => expect(mockFetch).toHaveBeenCalled());

    const calledWith = mockFetch.mock.calls[0][0];
    expect(calledWith.semanticModelCode).toBeUndefined();
  });
});
