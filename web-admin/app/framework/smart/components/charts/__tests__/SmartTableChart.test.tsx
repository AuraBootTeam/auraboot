/**
 * SmartTableChart.test.tsx
 *
 * Gap 1 (backlog 2026-05-08): SmartTableChart must consume widget-level
 * `table.columns[].label` (LocalizedText), and must accept the
 * `modelCode + table.columns` shorthand even when no `dataSource` is
 * authored — the dashboard JSON pattern crm-starter ships.
 */
import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';

const mockUseChartData = vi.fn();
vi.mock('~/framework/smart/hooks/useChartData', () => ({
  useChartData: (...args: unknown[]) => mockUseChartData(...args),
}));

const mockFetchResult = vi.fn();
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: unknown[]) => mockFetchResult(...args),
}));

// Locale defaults to zh-CN in I18nContext, which is what we want for
// LocalizedText resolution assertions below.

import { SmartTableChart } from '../SmartTableChart';

describe('SmartTableChart - widget config columns + i18n', () => {
  beforeEach(() => {
    mockUseChartData.mockReset();
    mockFetchResult.mockReset();
    mockUseChartData.mockReturnValue({
      data: null,
      loading: false,
      error: null,
      refetch: vi.fn(),
    });
  });

  it('renders LocalizedText labels from table.columns in the active locale', () => {
    mockUseChartData.mockReturnValue({
      data: {
        rows: [{ crm_opp_name: 'Big Deal', crm_opp_stage: 'discovery' }],
        summary: {},
        meta: { dimensions: ['crm_opp_name', 'crm_opp_stage'], metrics: [] },
      },
      loading: false,
      error: null,
      refetch: vi.fn(),
    });
    render(
      <SmartTableChart
        dataSource={{ type: 'static', staticData: [] }}
        table={{
          columns: [
            {
              field: 'crm_opp_name',
              label: { 'zh-CN': '商机名称', en: 'Opportunity' },
            },
            {
              field: 'crm_opp_stage',
              label: { 'zh-CN': '阶段', en: 'Stage' },
            },
          ],
        }}
      />,
    );

    // Default locale in this test env is zh-CN.
    expect(screen.getByText('商机名称')).toBeInTheDocument();
    expect(screen.getByText('阶段')).toBeInTheDocument();
    // Raw fieldCode must NOT leak to the header.
    expect(screen.queryByText('crm_opp_name')).not.toBeInTheDocument();
  });

  it('falls back to fieldCode when column.label is omitted', () => {
    mockUseChartData.mockReturnValue({
      data: {
        rows: [{ foo_field: 'val' }],
        summary: {},
        meta: { dimensions: ['foo_field'], metrics: [] },
      },
      loading: false,
      error: null,
      refetch: vi.fn(),
    });
    render(
      <SmartTableChart
        dataSource={{ type: 'static', staticData: [] }}
        table={{ columns: [{ field: 'foo_field' }] }}
      />,
    );
    expect(screen.getByText('foo_field')).toBeInTheDocument();
  });

  it('renders model-table shorthand: modelCode + table.columns without dataSource', async () => {
    mockFetchResult.mockResolvedValue({
      code: '0',
      data: {
        records: [
          { crm_opp_name: 'Alpha', crm_opp_stage: 'discovery' },
          { crm_opp_name: 'Beta', crm_opp_stage: 'qualification' },
        ],
      },
    });

    render(
      <SmartTableChart
        modelCode="crm_opportunity"
        table={{
          columns: [
            { field: 'crm_opp_name', label: { 'zh-CN': '商机名称', en: 'Opportunity' } },
            { field: 'crm_opp_stage', label: { 'zh-CN': '阶段', en: 'Stage' } },
          ],
        }}
      />,
    );

    await waitFor(() => expect(mockFetchResult).toHaveBeenCalled());
    // The fetch should target the dynamic list endpoint for the model.
    const firstCallUrl = (mockFetchResult.mock.calls[0]?.[0] as string) || '';
    expect(firstCallUrl).toContain('/api/dynamic/crm_opportunity/list');

    // Header labels should render in the active locale, no placeholder.
    expect(screen.getByText('商机名称')).toBeInTheDocument();
    expect(screen.getByText('阶段')).toBeInTheDocument();
    // Data rows render via the dynamic-list payload.
    await waitFor(() => expect(screen.getByText('Alpha')).toBeInTheDocument());
    expect(screen.getByText('Beta')).toBeInTheDocument();
    // Placeholder must not be shown when the shorthand is configured.
    expect(screen.queryByText('Please configure data source')).not.toBeInTheDocument();
  });

  it('uses drillDown paramMapping source field for identity named-query rows', () => {
    const onDrillDown = vi.fn();
    mockUseChartData.mockReturnValue({
      data: {
        rows: [{ purchase_pid: 'PUR-001', provider: 'local_test' }],
        summary: {},
        meta: { dimensions: [], metrics: ['provider', 'purchase_pid'] },
      },
      loading: false,
      error: null,
      refetch: vi.fn(),
    });

    render(
      <SmartTableChart
        dataSource={{ type: 'namedQuery', queryCode: 'recent_events' }}
        table={{
          columns: [
            { field: 'provider', label: 'Provider' },
            { field: 'purchase_pid', label: 'Purchase PID' },
          ],
        }}
        drillDown={{
          enabled: true,
          action: 'navigate',
          targetPage: '/p/provider_event',
          paramMapping: { purchase_pid: 'purchase_pid' },
        }}
        onDrillDown={onDrillDown}
      />,
    );

    fireEvent.click(screen.getByText('PUR-001').closest('tr')!);
    expect(onDrillDown).toHaveBeenCalledWith([
      { field: 'purchase_pid', operator: 'eq', value: 'PUR-001' },
    ]);
  });
});
