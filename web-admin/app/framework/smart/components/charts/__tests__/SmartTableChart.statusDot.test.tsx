/**
 * SmartTableChart status cells: a value whose dictionary item declares a colour is drawn as
 * 色点 + 文字 (the list-page presentation); everything else stays plain text.
 */
import React from 'react';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mockUseChartData = vi.fn();
vi.mock('~/framework/smart/hooks/useChartData', () => ({
  useChartData: (...args: unknown[]) => mockUseChartData(...args),
}));

const { labelsMock, tonesMock } = vi.hoisted(() => ({ labelsMock: vi.fn(), tonesMock: vi.fn() }));
vi.mock('~/framework/smart/hooks/useDimensionLabels', () => ({
  useDimensionLabels: (...args: unknown[]) => labelsMock(...args),
  useDimensionTones: (...args: unknown[]) => tonesMock(...args),
  __resetDimensionLabelCaches: vi.fn(),
}));

import { SmartTableChart } from '../SmartTableChart';

const chartData = (rows: Record<string, unknown>[]) => ({
  data: { rows, summary: {}, meta: { dimensions: ['run', 'status', 'kind'], metrics: [] } },
  loading: false,
  error: null,
  refetch: vi.fn(),
});

const columns = [
  { field: 'run', label: '运行' },
  { field: 'status', label: '状态', dictCode: 'orb_drun_status' },
  { field: 'kind', label: '类别' },
];

const dotOf = (text: string) => screen.getByText(text).previousElementSibling;

describe('SmartTableChart status cells', () => {
  beforeEach(() => {
    mockUseChartData.mockReset();
    labelsMock.mockReset();
    tonesMock.mockReset();
    mockUseChartData.mockReturnValue(chartData([
      { run: 'A', status: 'failed', kind: 'api' },
      { run: 'B', status: 'succeeded', kind: 'web' },
      { run: 'C', status: 'planned', kind: 'api' },
    ]));
  });

  it('draws 色点 + 文字 for a value whose dictionary item declares a colour', () => {
    labelsMock.mockReturnValue({ status: { failed: '失败', succeeded: '成功', planned: '已规划' } });
    tonesMock.mockReturnValue({ status: { failed: 'red', succeeded: 'green' } });

    render(<SmartTableChart dataSource={{ type: 'static', staticData: [] }} table={{ columns }} />);

    expect(dotOf('失败')?.className).toContain('bg-status-red');
    expect(dotOf('成功')?.className).toContain('bg-status-green');
  });

  it('keeps a value without a declared colour as plain text, in the same column', () => {
    labelsMock.mockReturnValue({ status: { failed: '失败', succeeded: '成功', planned: '已规划' } });
    tonesMock.mockReturnValue({ status: { failed: 'red', succeeded: 'green' } });

    const { container } = render(<SmartTableChart dataSource={{ type: 'static', staticData: [] }} table={{ columns }} />);

    expect(screen.getByText('已规划').previousElementSibling?.className ?? '').not.toContain('rounded-pill');
    // exactly the two coloured statuses carry a dot
    expect(container.querySelectorAll('tbody .rounded-pill')).toHaveLength(2);
  });

  it('a dictionary without colours changes nothing: text only, as before', () => {
    labelsMock.mockReturnValue({ status: { failed: '失败', succeeded: '成功', planned: '已规划' } });
    tonesMock.mockReturnValue({});

    const { container } = render(<SmartTableChart dataSource={{ type: 'static', staticData: [] }} table={{ columns }} />);

    expect(screen.getByText('失败')).toBeInTheDocument();
    expect(container.querySelectorAll('tbody .rounded-pill')).toHaveLength(0);
  });

  it('is opt-in per column: a column that names no dictionary stays text even when its values have colours', () => {
    // the model field behind `status` is dict-coded and its items declare colours — the column does not ask for them
    labelsMock.mockReturnValue({ status: { failed: '失败', succeeded: '成功' } });
    tonesMock.mockReturnValue({ status: { failed: 'red', succeeded: 'green' } });

    const { container } = render(
      <SmartTableChart
        dataSource={{ type: 'static', staticData: [] }}
        table={{ columns: columns.map(({ dictCode: _dictCode, ...rest }) => rest) }}
      />,
    );

    expect(screen.getByText('失败').textContent).toBe('失败');
    expect(container.querySelectorAll('tbody .rounded-pill')).toHaveLength(0);
    // the label lookup still runs for the field; no explicit dictionary is added
    const [source] = labelsMock.mock.calls[0] as unknown as [{ dimensionDicts?: Record<string, string> }];
    expect(source.dimensionDicts).toBeUndefined();
  });

  it('passes a column\'s own dictCode to the lookup as an explicit mapping', () => {
    labelsMock.mockReturnValue({});
    tonesMock.mockReturnValue({});

    render(<SmartTableChart dataSource={{ type: 'static', staticData: [] }} table={{ columns }} />);

    const [source, fields] = tonesMock.mock.calls[0] as unknown as [{ dimensionDicts?: Record<string, string> }, string[]];
    expect(source.dimensionDicts).toEqual({ status: 'orb_drun_status' });
    expect(fields).toEqual(['run', 'status', 'kind']);
  });
});
