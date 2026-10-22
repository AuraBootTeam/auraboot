import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SmartPieChart } from '../SmartPieChart';

const state = vi.hoisted(() => ({ rows: [] as Array<{ status: string; count: number }> }));
vi.mock('~/framework/smart/hooks/useChartData', () => ({
  useChartData: () => ({
    loading: false,
    error: null,
    data: { rows: state.rows, meta: { dimensions: ['status'], metrics: ['count'] } },
  }),
}));
vi.mock('../EChartsHost', () => ({
  default: ({ option, onEvents }: any) => (
    <button data-testid="chart-options" onClick={() => onEvents.click({ dataIndex: 0 })}>
      {JSON.stringify(option)}
    </button>
  ),
}));
const source = { type: 'namedQuery' as const, queryCode: 'distribution' };
const options = () => JSON.parse(screen.getByTestId('chart-options').textContent!);

beforeEach(() => {
  state.rows = ['审批中', '已退回', '已通过', '待提交'].map((status, i) => ({
    status,
    count: i + 1,
  }));
});

describe('pie chart legends in designer canvases', () => {
  it('explains an unfinished filter instead of displaying stale or failed query results', () => {
    render(
      <SmartPieChart
        dataSource={{ ...source, filters: [{ field: '', operator: 'eq', value: '' }] }}
      />,
    );
    expect(screen.getByTestId('chart-filter-draft')).toBeVisible();
    expect(screen.queryByTestId('chart-options')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
  it('wraps a small distribution and preserves every actual label and value', () => {
    render(<SmartPieChart dataSource={source} ring title="Delivery acceptance status" />);
    expect(screen.getByRole('img', { name: 'Delivery acceptance status' })).toBeVisible();
    const config = options();
    expect(config.legend.type).toBe('plain');
    expect(config.legend.width).toBe('90%');
    expect(config.series[0].data).toEqual(
      state.rows.map((row) => ({ name: row.status, value: row.count })),
    );
  });

  it('keeps a bounded scrolling legend for more than six categories', () => {
    state.rows = Array.from({ length: 7 }, (_, i) => ({ status: `Stage ${i}`, count: i }));
    render(<SmartPieChart dataSource={source} />);
    expect(options().legend.type).toBe('scroll');
    expect(options().series[0].data).toHaveLength(7);
  });

  it('respects an explicit legend override and keeps drill-down tied to the actual field value', () => {
    const onDrillDown = vi.fn();
    render(
      <SmartPieChart
        dataSource={source}
        chartOptions={{ legend: { show: false } }}
        drillDown={{ enabled: true, action: 'filter' }}
        onDrillDown={onDrillDown}
      />,
    );
    expect(options().legend.show).toBe(false);
    fireEvent.click(screen.getByTestId('chart-options'));
    expect(onDrillDown).toHaveBeenCalledWith([
      { field: 'status', operator: 'eq', value: '审批中' },
    ]);
  });
});
