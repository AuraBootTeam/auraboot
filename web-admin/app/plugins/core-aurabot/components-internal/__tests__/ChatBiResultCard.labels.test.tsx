import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import type { ChartDataSource } from '~/framework/smart/types/chart';
vi.mock('~/contexts/AuthContext', () => ({ usePermission: () => false }));
vi.mock('../../components-shell/AuraBotProvider', () => ({
  useAuraBot: () => ({ state: { isLoading: false }, sendMessage: vi.fn() }),
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: 'zh-CN',
    t: (key: string, _?: unknown, fallback?: string) =>
      ({
        'model.tpm_project.tpm_pj_budget.label': '项目预算',
        'model.tpm_project.tpm_pj_name.label': '项目名称',
      })[key] ||
      fallback ||
      key,
  }),
}));
vi.mock('~/framework/smart/charts/SharedChartFactory', () => ({
  normalizeChartType: (type: string) => type,
  getChartComponent:
    () =>
    (props: { metricLabels: Record<string, string>; orientation?: string; title?: string; chartOptions?: any }) => (
      <div
        data-testid="shared-chart-labels"
        data-orientation={props.orientation}
        data-title={props.title}
        data-axis={JSON.stringify(props.chartOptions)}
        data-tick={props.chartOptions?.xAxis?.axisLabel.formatter?.(200000)}
      >
        {JSON.stringify(props.metricLabels)}
      </div>
    ),
}));
vi.mock('~/plugins/core-dashboard/services/dashboardService', () => ({
  dashboardService: {
    create: vi.fn().mockResolvedValue({ pid: 'saved-dashboard', code: 'saved-dashboard' }),
  },
}));
vi.mock(
  '~/plugins/core-designer/components/report-designer/services/reportDesignerService',
  () => ({ reportDesignerService: { save: vi.fn() } }),
);
import { dashboardService } from '~/plugins/core-dashboard/services/dashboardService';
import { ChatBiResultCard } from '../ChatBiResultCard';
describe('chat chart presentation labels', () => {
  it('passes translated metric names into the existing chart renderer and persisted widget', async () => {
    const dataSource: ChartDataSource = {
      type: 'aggregate',
      modelCode: 'tpm_project',
      dimensions: ['tpm_pj_name'],
      metrics: [{ field: 'tpm_pj_budget', aggregation: 'sum', alias: 'budget_total' }],
    };
    render(
      <ChatBiResultCard
        result={{
          chartType: 'bar',
          modelCode: 'tpm_project',
          columns: ['tpm_pj_name', 'budget_total'],
          records: [{ tpm_pj_name: '华辰制造 · 第4批供应链协同平台交付项目', budget_total: 128000 }],
          dataSource,
          metrics: dataSource.metrics,
          dimensions: dataSource.dimensions,
        }}
      />,
    );
    expect(await screen.findByTestId('shared-chart-labels')).toHaveTextContent('项目预算 · 合计');
    expect(screen.getByTestId('shared-chart-labels')).toHaveAttribute(
      'data-orientation',
      'horizontal',
    );
    expect(screen.getByTestId('shared-chart-labels')).not.toHaveAttribute('data-title');
    expect(screen.getByTestId('shared-chart-labels')).toHaveAttribute('data-tick', '20万');
    expect(JSON.parse(screen.getByTestId('shared-chart-labels').getAttribute('data-axis')!)).toMatchObject({ xAxis: { splitNumber: 2, axisLabel: { hideOverlap: true } }, yAxis: { type: 'category', data: ['华辰制造 · 第4批供应链协同平台交付项目'], axisLabel: { width: 96, overflow: 'truncate' } } });
    fireEvent.click(screen.getByTestId('chatbi-save-dashboard'));
    await waitFor(() => expect(dashboardService.create).toHaveBeenCalled());
    const widget = vi.mocked(dashboardService.create).mock.calls[0][0].widgets![0];
    expect(widget.config!.dataSource).toEqual(dataSource);
    expect(widget.config).toMatchObject({
      metricLabels: { tpm_pj_name: '项目名称', budget_total: '项目预算 · 合计' },
      orientation: 'horizontal',
    });
    expect(vi.mocked(dashboardService.create).mock.calls[0][0].title).not.toContain('tpm_project');
  });
});

it('keeps numeric columns aligned and grouped without converting record codes to numbers', () => {
  render(
    <ChatBiResultCard
      result={{
        chartType: 'table',
        columns: ['code', 'amount'],
        records: [
          { code: '00123', amount: 128000 },
          { code: '00999', amount: 132000.5 },
        ],
      }}
    />,
  );
  expect(screen.getByText('00123')).toBeVisible();
  expect(screen.getByText('128,000.00')).toHaveClass('text-right');
  expect(screen.getByText('132,000.50')).toHaveClass('tabular-nums');
});
it('respects an explicit vertical chart configuration', async () => {
  render(
    <ChatBiResultCard
      result={{
        chartType: 'bar',
        chartConfig: { orientation: 'vertical' },
        columns: ['name', 'amount'],
        records: [{ name: '项目', amount: 1 }],
      }}
    />,
  );
  expect(await screen.findByTestId('shared-chart-labels')).toHaveAttribute(
    'data-orientation',
    'vertical',
  );
});

it('honors an explicit empty presentation without falling back to private storage keys', () => {
  render(<ChatBiResultCard result={{ modelCode: 'customer', columns: [], records: [{ tenant_id: 'private-tenant', pid: 'private-pid' }], total: 1 }} />);
  expect(screen.queryByText('tenant_id')).not.toBeInTheDocument();
  expect(screen.queryByText('private-tenant')).not.toBeInTheDocument();
  expect(screen.queryByText('private-pid')).not.toBeInTheDocument();
});
