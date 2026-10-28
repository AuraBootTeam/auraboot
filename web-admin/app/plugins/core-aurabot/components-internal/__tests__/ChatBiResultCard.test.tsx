import React from 'react';
import type { ChartDataSource } from '~/framework/smart/types/chart';
import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
vi.mock('~/contexts/AuthContext', () => ({ usePermission: () => false }));
vi.mock('../../components-shell/AuraBotProvider', () => ({
  useAuraBot: () => ({ state: { isLoading: false }, sendMessage: vi.fn() }),
}));

vi.mock('~/plugins/core-dashboard/services/dashboardService', () => ({
  dashboardService: {
    create: vi.fn().mockResolvedValue({ pid: 'saved-dashboard', code: 'saved-dashboard-code' }),
  },
}));
import { dashboardService } from '~/plugins/core-dashboard/services/dashboardService';

vi.mock(
  '~/plugins/core-designer/components/report-designer/services/reportDesignerService',
  () => ({
    reportDesignerService: { save: vi.fn().mockResolvedValue('saved-report') },
  }),
);
import { reportDesignerService } from '~/plugins/core-designer/components/report-designer/services/reportDesignerService';

import { ChatBiResultCard } from '../ChatBiResultCard';

describe('ChatBiResultCard', () => {
  it('renders raw records as a table when columns metadata is absent', () => {
    render(
      <ChatBiResultCard
        result={{
          total: 1,
          records: [
            {
              product_id: '01PRODUCT',
              supplier_id: '01SUPPLIER',
              supplier_name: 'Shenzhen Precision Components',
              unit_price: 8.75,
            },
          ],
        }}
      />,
    );

    expect(screen.getByText('数据查询')).toBeInTheDocument();
    expect(screen.getByText('supplier_name')).toBeInTheDocument();
    expect(screen.getByText('Shenzhen Precision Components')).toBeInTheDocument();
    expect(screen.getByText('01SUPPLIER')).toBeInTheDocument();
  });
});

// A saved chart must execute the exact same filtered query after reload.
it('persists the full query instead of broadening a filtered result', async () => {
  const dataSource: ChartDataSource = {
    type: 'aggregate' as const,
    modelCode: 'orders',
    semanticModelCode: 'sales',
    dimensions: ['region'],
    metrics: [{ field: 'total_sales', aggregation: 'sum' }],
    filters: [{ field: 'region', operator: 'eq', value: 'East' }],
    orderBy: [{ field: 'total_sales', direction: 'desc' }],
    limit: 5,
  };
  render(
    <ChatBiResultCard
      result={{
        modelCode: 'orders',
        dimensions: dataSource.dimensions,
        metrics: dataSource.metrics,
        dataSource,
        chartType: 'table',
        columns: ['region', 'total_sales'],
        records: [{ region: 'East', total_sales: 120 }],
      }}
    />,
  );
  fireEvent.click(screen.getByTestId('chatbi-save-dashboard'));
  await waitFor(() => expect(dashboardService.create).toHaveBeenCalled());
  expect(
    vi.mocked(dashboardService.create).mock.calls.at(-1)?.[0].widgets?.[0].config.dataSource,
  ).toEqual(dataSource);
});

it('saves an executable report query and links to the persisted report', async () => {
  const dataSource: ChartDataSource = {
    type: 'aggregate',
    semanticModelCode: 'sales',
    dimensions: ['region'],
    metrics: [{ field: 'revenue', aggregation: 'sum' }],
    filters: [{ field: 'region', operator: 'eq', value: 'East' }],
    limit: 10,
  };
  render(
    <ChatBiResultCard
      result={{
        dataSource,
        chartType: 'table',
        columns: ['region', 'revenue'],
        records: [{ region: 'East', revenue: 120 }],
        interpretation: 'East revenue is 120. <script>not executable</script>',
      }}
    />,
  );
  fireEvent.click(screen.getByTestId('chatbi-save-report'));
  expect(screen.getByTestId('analysis-report-confirm')).toBeDisabled();
  fireEvent.click(screen.getByRole('checkbox', { name: '我已核对查询范围、事实和采用内容' }));
  fireEvent.click(screen.getByTestId('analysis-report-confirm'));
  await waitFor(() => expect(reportDesignerService.save).toHaveBeenCalled());
  const report = vi.mocked(reportDesignerService.save).mock.calls.at(-1)![0];
  expect(report.dataSources.analysis).toEqual({ type: 'aggregate', aggregateQuery: dataSource });
  expect(report.body[0]).toMatchObject({
    blockType: 'table',
    dataSource: 'analysis',
    columns: [
      { field: 'region', label: 'region', width: 300, align: 'left', format: '' },
      { field: 'revenue', label: 'revenue · 合计', width: 150, align: 'right', format: 'number' },
    ],
  });
  expect(report.body[1]).toMatchObject({
    blockType: 'rich-text',
    content: expect.stringContaining('East revenue is 120. <script>not executable</script>'),
  });
  expect(await screen.findByTestId('chatbi-saved-report')).toHaveAttribute(
    'href',
    '/report-designer/saved-report',
  );
});

it('keeps review edits local until adoption and preserves the governed query', async () => {
  const before = vi.mocked(reportDesignerService.save).mock.calls.length;
  const result = {
    dataSource: {
      type: 'aggregate' as const,
      modelCode: 'orders',
      dimensions: ['name'],
      metrics: [{ field: 'amount', aggregation: 'sum' as const }],
      filters: [{ field: 'customer', operator: 'eq' as const, value: 'customer-a' }],
    },
    columns: ['name', 'amount'],
    records: [{ name: 'Project A', amount: 132000.5 }],
    interpretation: 'Short tool summary',
  };
  const narrative =
    'Customer A\n\n| Project | Budget |\n| --- | ---: |\n| Project A | 132000.50 |\n\nReview actual cost before deciding profit.';
  render(<ChatBiResultCard result={result} reportNarrative={narrative} />);
  fireEvent.click(screen.getByTestId('chatbi-save-report'));
  expect(screen.getByLabelText('采用的分析说明')).toHaveValue(
    'Customer A\n\nReview actual cost before deciding profit.',
  );
  fireEvent.change(screen.getByLabelText('报表名称'), {
    target: { value: 'Customer A · Budget review' },
  });
  fireEvent.change(screen.getAllByRole('combobox', { name: '列格式' })[1], {
    target: { value: 'currency' },
  });
  fireEvent.click(screen.getByRole('button', { name: '取消', exact: true }));
  expect(vi.mocked(reportDesignerService.save).mock.calls).toHaveLength(before);
  fireEvent.click(screen.getByTestId('chatbi-save-report'));
  fireEvent.change(screen.getAllByRole('combobox', { name: '列格式' })[1], {
    target: { value: 'currency' },
  });
  fireEvent.change(screen.getByLabelText('采用的分析说明'), {
    target: { value: 'Reviewed facts, not revenue.' },
  });
  fireEvent.click(screen.getByRole('checkbox'));
  fireEvent.click(screen.getByTestId('analysis-report-confirm'));
  await waitFor(() =>
    expect(vi.mocked(reportDesignerService.save).mock.calls).toHaveLength(before + 1),
  );
  const report = vi.mocked(reportDesignerService.save).mock.calls.at(-1)![0];
  expect(report.dataSources.analysis.aggregateQuery).toEqual(result.dataSource);
  expect(report.body[0]).toMatchObject({
    columns: [
      { field: 'name', align: 'left' },
      { field: 'amount', format: 'currency', align: 'right', width: 150 },
    ],
  });
  expect(report.body[1]).toMatchObject({
    content: expect.stringContaining('Reviewed facts, not revenue.'),
  });
  expect(report.body[1]).not.toHaveProperty('records');
});

it('blocks adoption while this turn is streaming', () => {
  render(
    <ChatBiResultCard
      result={{
        dataSource: {
          type: 'aggregate',
          modelCode: 'orders',
          dimensions: ['name'],
          metrics: [{ field: 'amount', aggregation: 'sum' }],
        },
        columns: ['name', 'amount'],
        records: [{ name: 'A', amount: 1 }],
      }}
      narrativePending
    />,
  );
  expect(screen.getByTestId('chatbi-save-report')).toBeDisabled();
});

it('retains the reviewed draft after a failed save', async () => {
  vi.mocked(reportDesignerService.save).mockRejectedValueOnce(new Error('denied'));
  render(
    <ChatBiResultCard
      result={{
        dataSource: {
          type: 'aggregate',
          modelCode: 'orders',
          dimensions: ['name'],
          metrics: [{ field: 'amount', aggregation: 'sum' }],
        },
        columns: ['name', 'amount'],
        records: [{ name: 'A', amount: 1 }],
      }}
      reportNarrative="Facts requiring review"
    />,
  );
  const before = vi.mocked(reportDesignerService.save).mock.calls.length;
  fireEvent.click(screen.getByTestId('chatbi-save-report'));
  fireEvent.change(screen.getByLabelText('采用的分析说明'), {
    target: { value: 'My reviewed facts' },
  });
  fireEvent.click(screen.getByRole('checkbox'));
  fireEvent.click(screen.getByTestId('analysis-report-confirm'));
  await waitFor(() =>
    expect(vi.mocked(reportDesignerService.save).mock.calls).toHaveLength(before + 1),
  );
  await waitFor(() => expect(screen.getByTestId('analysis-report-confirm')).not.toBeDisabled());
  expect(screen.getByLabelText('采用的分析说明')).toHaveValue('My reviewed facts');
  expect(screen.queryByTestId('chatbi-saved-report')).not.toBeInTheDocument();
});
