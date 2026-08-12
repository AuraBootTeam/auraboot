import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SmartBarChart } from '../SmartBarChart';

const mockUseChartData = vi.fn();

vi.mock('~/framework/smart/hooks/useChartData', () => ({
  useChartData: (...args: unknown[]) => mockUseChartData(...args),
}));

vi.mock('echarts-for-react', () => ({
  default: ({
    option,
    onEvents,
  }: {
    option: { xAxis?: { data?: string[] } };
    onEvents?: { click?: (params: { name: string; dataIndex: number }) => void };
  }) => (
    <button
      type="button"
      data-testid="echarts-point"
      onClick={() => onEvents?.click?.({ name: '管道', dataIndex: 0 })}
    >
      {option.xAxis?.data?.join(',')}
    </button>
  ),
}));

describe('SmartBarChart drill-down', () => {
  beforeEach(() => {
    mockUseChartData.mockReset();
    mockUseChartData.mockReturnValue({
      data: {
        rows: [{ crm_opp_forecast_category: 'pipeline', analysis_value: 480000 }],
        summary: {},
        meta: {
          dimensions: ['crm_opp_forecast_category'],
          metrics: ['analysis_value'],
          dimensionLabels: {
            crm_opp_forecast_category: { pipeline: '管道' },
          },
        },
      },
      loading: false,
      error: null,
      refetch: vi.fn(),
    });
  });

  it('renders the localized category but drills into the raw dimension value', () => {
    const onDrillDown = vi.fn();

    render(
      <SmartBarChart
        dataSource={{
          type: 'aggregate',
          modelCode: 'crm_opportunity',
          dimensions: ['crm_opp_forecast_category'],
          metrics: [{ field: 'crm_opp_expected_amount', aggregation: 'sum', alias: 'analysis_value' }],
        }}
        drillDown={{ enabled: true, action: 'filter' }}
        onDrillDown={onDrillDown}
      />,
    );

    expect(screen.getByTestId('echarts-point')).toHaveTextContent('管道');
    fireEvent.click(screen.getByTestId('echarts-point'));
    expect(onDrillDown).toHaveBeenCalledWith([
      { field: 'crm_opp_forecast_category', operator: 'eq', value: 'pipeline' },
    ]);
  });
});
