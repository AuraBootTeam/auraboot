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
