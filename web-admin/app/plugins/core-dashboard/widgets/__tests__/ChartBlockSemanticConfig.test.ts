/**
 * Tests for the semantic model config integration in dashboard chart widgets.
 *
 * Covers:
 *  1. semanticModelCode is NOT a standalone configSchema field — it is
 *     configured in the DataSourceConfig panel switch (PRD 16 W4 D4
 *     switch-style consolidation; the old #378 dropdown was removed).
 *  2. DataSourceConfig type accepts semanticModelCode
 *  3. ChartDataSource type accepts semanticModelCode
 *  4. useChartData serialises semanticModelCode into the request payload
 *  5. useChartData omits semanticModelCode from the request when not set
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { widgetRegistry } from '../widgetRegistry';
import type { DataSourceConfig } from '../../types';
import type { ChartDataSource } from '~/framework/smart/types/chart';

// ---------------------------------------------------------------------------
// Case 1 – semanticModelCode is configured via the DataSourceConfig panel
//          switch, NOT a standalone configSchema dropdown.
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
    '%s does NOT carry a standalone semantic-model-select configSchema field',
    (widgetType) => {
      const def = widgetRegistry.get(widgetType);
      expect(def, `${widgetType} must be registered`).toBeDefined();

      const schema = def!.configSchema ?? [];
      const semanticField = schema.find((s) => s.key === 'dataSource.semanticModelCode');
      expect(
        semanticField,
        `${widgetType} must configure semantic mode via DataSourceConfig, not a duplicate dropdown`,
      ).toBeUndefined();
      // The semantic-model-select PropertyType must no longer be used here.
      expect(schema.some((s) => s.type === 'semantic-model-select')).toBe(false);
    },
  );
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
