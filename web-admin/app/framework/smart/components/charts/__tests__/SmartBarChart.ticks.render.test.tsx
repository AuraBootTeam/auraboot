/**
 * SmartBarChart renders whole-number ticks on a count axis. This is the wiring test: the pure helper
 * has its own tests, and a helper that the component forgets to call would pass them all.
 */
import React from 'react';
import { render } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mockUseChartData = vi.fn();
vi.mock('~/framework/smart/hooks/useChartData', () => ({
  useChartData: (...args: unknown[]) => mockUseChartData(...args),
}));

const lastOption = vi.hoisted(() => ({ current: null as Record<string, unknown> | null }));
vi.mock('echarts-for-react', () => ({
  default: ({ option }: { option: Record<string, unknown> }) => {
    lastOption.current = option;
    return <div data-testid="echarts" />;
  },
}));

import { SmartBarChart } from '../SmartBarChart';

const chartData = (values: number[]) => ({
  data: {
    rows: values.map((value, i) => ({ state: `s${i}`, count: value })),
    summary: {},
    meta: { dimensions: ['state'], metrics: ['count'] },
  },
  loading: false,
  error: null,
  refetch: vi.fn(),
});
const staticSource = { type: 'static' as const, staticData: [] };
const valueAxis = () => (lastOption.current?.yAxis ?? {}) as { minInterval?: number };

describe('SmartBarChart whole-number ticks (rendered option)', () => {
  beforeEach(() => mockUseChartData.mockReset());

  it('a single count of 2 is drawn on an axis with whole-number ticks', () => {
    mockUseChartData.mockReturnValue(chartData([2]));
    render(<SmartBarChart dataSource={staticSource} />);
    expect(valueAxis().minInterval).toBe(1);
  });

  it('counts of 1 and 6 keep whole-number ticks', () => {
    mockUseChartData.mockReturnValue(chartData([1, 6]));
    render(<SmartBarChart dataSource={staticSource} />);
    expect(valueAxis().minInterval).toBe(1);
  });

  it('a fractional value leaves the axis to the chart library', () => {
    mockUseChartData.mockReturnValue(chartData([1, 2.5]));
    render(<SmartBarChart dataSource={staticSource} />);
    expect(valueAxis().minInterval).toBeUndefined();
  });

  it('an axis setting given by the dashboard author is not second-guessed', () => {
    mockUseChartData.mockReturnValue(chartData([2]));
    render(<SmartBarChart dataSource={staticSource} chartOptions={{ yAxis: { type: 'value', minInterval: 0.5 } }} />);
    expect(valueAxis().minInterval).toBe(0.5);
  });
});

describe('SmartBarChart number formats (rendered option)', () => {
  it('groups counts and formats currency tooltips and labels without changing chart values', () => {
    const source = chartData([1234]);
    mockUseChartData.mockReturnValue({
      ...source,
      data: {
        ...source.data,
        rows: [{ ...source.data.rows[0], amount: 12500.5 }],
        meta: { ...source.data.meta, metrics: ['count', 'amount'] },
      },
    });
    render(<SmartBarChart
      dataSource={staticSource}
      showLabel
      metricFormats={{ count: { format: 'number' }, amount: { format: 'currency', currency: 'CNY' } }}
    />);
    const series = lastOption.current?.series as Array<{
      data: number[];
      tooltip: { valueFormatter: (value: number) => string };
      label: { formatter: (item: { value: number }) => string };
    }>;
    expect(series.map((item) => item.data)).toEqual([[1234], [12500.5]]);
    expect(series[0].tooltip.valueFormatter(1234)).toBe('1,234');
    const currency = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' });
    expect(series[1].tooltip.valueFormatter(12500.5)).toBe(currency.format(12500.5));
    expect(series[1].label.formatter({ value: 12500.5 })).toBe(currency.format(12500.5));
    const axis = lastOption.current?.yAxis as { axisLabel: { formatter: (value: number) => string } };
    expect(axis.axisLabel.formatter(10000)).toBe('10,000');
  });
  it('formats a currency axis and preserves configured horizontal tick density', () => {
    mockUseChartData.mockReturnValue(chartData([1234]));
    render(<SmartBarChart
      dataSource={staticSource}
      orientation="horizontal"
      metricFormats={{ count: { format: 'currency', currency: 'CNY' } }}
      chartOptions={{ xAxis: { type: 'value', splitNumber: 3, axisLabel: { hideOverlap: true } } }}
    />);
    const axis = lastOption.current?.xAxis as {
      splitNumber: number;
      axisLabel: { hideOverlap: boolean; formatter: (value: number) => string };
    };
    expect(axis.splitNumber).toBe(3);
    expect(axis.axisLabel.hideOverlap).toBe(true);
    expect(axis.axisLabel.formatter(1234)).toBe(
      new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(1234),
    );
  });

});
