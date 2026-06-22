import React from 'react';
import { render, screen } from '@testing-library/react';
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

  it('renders number cards with zero-value guidance instead of a blank metric area', () => {
    render(<SmartNumberCard title="Accounts" label="Accounts" dataSource={aggregateSource} />);

    expect(screen.getByText('Waiting for first record')).toBeInTheDocument();
    expect(screen.getByText(/This KPI is ready\./)).toBeInTheDocument();
    expect(screen.getByText('0')).toBeInTheDocument();
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
});
