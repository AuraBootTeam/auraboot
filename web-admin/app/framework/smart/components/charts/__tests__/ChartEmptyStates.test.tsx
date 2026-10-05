import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SmartBarChart } from '../SmartBarChart';
import { SmartLineChart } from '../SmartLineChart';
import { SmartPieChart } from '../SmartPieChart';
import { SmartNumberCard } from '../SmartNumberCard';

const mockUseChartData = vi.fn();

vi.mock('~/framework/smart/hooks/useChartData', () => ({
  useChartData: (...args: unknown[]) => mockUseChartData(...args),
}));

vi.mock('echarts-for-react', () => ({
  default: () => <div data-testid="echarts-mock" />,
}));

const aggregateSource = {
  type: 'aggregate' as const,
  modelCode: 'crm_account',
  dimensions: ['status'],
  metrics: [{ field: 'id', aggregation: 'count' as const, alias: 'count' }],
};

describe('dashboard chart empty states', () => {
  beforeEach(() => {
    mockUseChartData.mockReset();
    mockUseChartData.mockReturnValue({
      data: { rows: [], summary: {}, meta: { dimensions: ['status'], metrics: ['count'] } },
      loading: false,
      error: null,
      refetch: vi.fn(),
    });
  });

  it('renders a designed empty state for bar, line, and pie charts when no rows exist', () => {
    render(
      <div>
        <SmartBarChart title="Bar Chart" dataSource={aggregateSource} />
        <SmartLineChart title="Line Chart" dataSource={aggregateSource} />
        <SmartPieChart title="Pie Chart" dataSource={aggregateSource} />
      </div>,
    );

    expect(screen.getAllByTestId('chart-empty-state')).toHaveLength(3);
    expect(screen.getByText('Bar Chart')).toBeInTheDocument();
    expect(screen.getByText('Line Chart')).toBeInTheDocument();
    expect(screen.getByText('Pie Chart')).toBeInTheDocument();
    expect(screen.queryByTestId('echarts-mock')).not.toBeInTheDocument();
  });

  it('renders missing number cards with guidance instead of a fabricated zero', () => {
    render(<SmartNumberCard title="Accounts" label="Accounts" dataSource={aggregateSource} />);

    expect(screen.getByText('Waiting for first record')).toBeInTheDocument();
    expect(screen.getByText(/This KPI is ready\./)).toBeInTheDocument();
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.queryByText('0')).not.toBeInTheDocument();
  });

  it.each([null, undefined, '', 'invalid', Infinity])(
    'preserves unavailable single values (%s)',
    (value) => {
      mockUseChartData.mockReturnValue({
        data: { rows: [{ count: value }], meta: { metrics: ['count'] } },
        loading: false,
        error: null,
      });
      render(<SmartNumberCard title="Missing" dataSource={aggregateSource} format="percent" />);
      expect(screen.getByText('—')).toBeInTheDocument();
      expect(screen.queryByText('0%')).not.toBeInTheDocument();
    },
  );

  it('distinguishes missing multi-card values from numeric zero', () => {
    mockUseChartData.mockReturnValue({
      data: { rows: [{ missing: null, zero: 0 }], meta: { metrics: [] } },
      loading: false,
      error: null,
    });
    render(
      <SmartNumberCard
        title="Metrics"
        dataSource={aggregateSource}
        cards={[
          { field: 'missing', label: 'Missing', format: 'percent' },
          { field: 'zero', label: 'Zero', format: 'percent' },
        ]}
      />,
    );
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.getByText('0%')).toBeInTheDocument();
  });

  it('lets a single configured number card fill the widget grid', () => {
    mockUseChartData.mockReturnValue({
      data: { rows: [{ count: 2 }], summary: {}, meta: { dimensions: [], metrics: ['count'] } },
      loading: false,
      error: null,
      refetch: vi.fn(),
    });

    render(
      <SmartNumberCard
        title="Equipment"
        dataSource={aggregateSource}
        cards={[{ field: 'count', label: '设备数' }]}
      />,
    );

    const card = screen.getByText('设备数').parentElement;
    const grid = card?.parentElement;

    expect(screen.getByText('2')).toBeInTheDocument();
    expect(grid).toHaveClass('grid-cols-1');
    expect(grid).not.toHaveClass('xl:grid-cols-6');
  });

  it('makes each configured KPI card invoke its own drill-down cohort', () => {
    mockUseChartData.mockReturnValue({
      data: { rows: [{ open_deals: 7 }], summary: {}, meta: { dimensions: [], metrics: [] } },
      loading: false,
      error: null,
      refetch: vi.fn(),
    });
    const onDrillDown = vi.fn();
    const drillDown = {
      enabled: true,
      action: 'navigate' as const,
      targetPage: '/p/crm_opportunity_common',
      filters: [{ targetField: 'crm_opp_stage', operator: 'ne' as const, value: 'closed_won' }],
    };

    render(
      <SmartNumberCard
        title="Forecast"
        dataSource={aggregateSource}
        cards={[{ field: 'open_deals', label: 'Open deals', drillDown }]}
        onDrillDown={onDrillDown}
      />,
    );

    fireEvent.click(screen.getByTestId('number-card-open_deals-drilldown'));
    expect(onDrillDown).toHaveBeenCalledWith(drillDown);
  });

  it('reads the configured metric field from a multi-metric response', () => {
    mockUseChartData.mockReturnValue({
      data: {
        rows: [{ total_contacts: 18, open_opportunities: 4 }],
        summary: {},
        meta: { dimensions: [], metrics: ['total_contacts', 'open_opportunities'] },
      },
      loading: false,
      error: null,
      refetch: vi.fn(),
    });

    render(
      <SmartNumberCard
        title="Open opportunities"
        dataSource={aggregateSource}
        metricField="open_opportunities"
      />,
    );

    expect(screen.getByText('4')).toBeInTheDocument();
    expect(screen.queryByText('18')).not.toBeInTheDocument();
  });
});
