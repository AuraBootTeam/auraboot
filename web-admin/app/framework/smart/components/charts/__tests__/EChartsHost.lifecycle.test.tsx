import React from 'react';
import type { EChartsType } from 'echarts';
import type { EChartsReactProps } from 'echarts-for-react';
import { render } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import EChartsHost from '../EChartsHost';

const captured = vi.hoisted(() => ({ props: null as EChartsReactProps | null }));
vi.mock('echarts-for-react', () => ({
  default: (props: EChartsReactProps) => {
    captured.props = props;
    return <div data-testid="chart-host" />;
  },
}));
function fixture(disposed = false) {
  const dom = document.createElement('div');
  const instance = {
    isDisposed: vi.fn(() => disposed),
    getDom: vi.fn(() => disposed ? null : dom),
    getOption: vi.fn(() => ({ title: [{ text: 'Completed work orders' }],
      xAxis: [{ name: 'Status' }], yAxis: [{ name: 'Count' }],
      legend: [{}], series: [{ type: 'bar', name: 'Work orders' }] })),
    getZr: vi.fn(() => ({ animation: { isFinished: () => true } })),
  };
  return { dom, instance, chart: instance as unknown as EChartsType };
}

describe('EChartsHost render lifecycle', () => {
  beforeEach(() => { captured.props = null; });
  it('marks a live chart pending then ready and forwards its actual events', () => {
    const rendered = vi.fn(), finished = vi.fn();
    render(<EChartsHost option={{}} onEvents={{ rendered, finished }} />);
    const f = fixture();
    captured.props!.onEvents!.rendered('render-event', f.chart);
    expect(f.dom.getAttribute('data-chart-ready')).toBe('false');
    expect(rendered).toHaveBeenCalledExactlyOnceWith('render-event', f.chart);
    captured.props!.onEvents!.finished('finish-event', f.chart);
    expect(f.dom.getAttribute('data-chart-ready')).toBe('true');
    expect(JSON.parse(f.dom.getAttribute('data-chart-ui-copy')!))
      .toEqual(['Completed work orders', 'Status', 'Count', 'Work orders']);
    expect(finished).toHaveBeenCalledExactlyOnceWith('finish-event', f.chart);
  });
  it('marks and forwards the live chart-ready callback', () => {
    const onChartReady = vi.fn();
    render(<EChartsHost option={{}} onChartReady={onChartReady} />);
    const f = fixture();
    captured.props!.onChartReady!(f.chart);
    expect(f.dom.getAttribute('data-chart-ready')).toBe('true');
    expect(onChartReady).toHaveBeenCalledExactlyOnceWith(f.chart);
  });
  for (const event of ['rendered', 'finished', 'onChartReady'] as const) {
    it(`ignores a disposed temporary instance in ${event} without forwarding a stale chart`, () => {
      const handler = vi.fn();
      render(<EChartsHost option={{}} onEvents={{ rendered: handler, finished: handler }} onChartReady={handler} />);
      const f = fixture(true);
      expect(() => event === 'onChartReady'
        ? captured.props!.onChartReady!(f.chart)
        : captured.props!.onEvents![event]('late-event', f.chart)).not.toThrow();
      expect(f.instance.getDom).not.toHaveBeenCalled();
      expect(f.instance.getOption).not.toHaveBeenCalled();
      expect(f.instance.getZr).not.toHaveBeenCalled();
      expect(handler).not.toHaveBeenCalled();
      expect(f.dom.hasAttribute('data-chart-ready')).toBe(false);
    });
  }
});
