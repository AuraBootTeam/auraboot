import { render } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWidget } from '../WidgetRenderer';
import type { Widget } from '../../types';

const chartMock = vi.hoisted(() => ({
  props: [] as Array<Record<string, unknown>>,
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: 'en-US',
    t: (key: string) => `translated:${key}`,
  }),
}));

vi.mock('~/framework/smart/charts/SharedChartFactory', () => ({
  normalizeChartType: (type: string) => type.replace(/^smart-/, '').replace(/-chart$/, ''),
  getChartComponent: () => (props: Record<string, unknown>) => {
    chartMock.props.push(props);
    return null;
  },
}));

describe('WidgetRenderer i18n config resolution', () => {
  beforeEach(() => {
    chartMock.props = [];
  });

  it('resolves localized widget titles before passing config to chart components', () => {
    const widget = {
      id: 'revenue',
      type: 'smart-number-card',
      x: 0,
      y: 0,
      w: 3,
      h: 2,
      props: {},
      config: {
        title: { 'zh-CN': '收入', en: 'Revenue' },
        dataSource: { type: 'static', staticData: [] },
        drillDown: {
          enabled: true,
          action: 'navigate',
          targetPage: '/p/revenue',
          paramMapping: { revenue_id: 'pid' },
        },
      },
    } as unknown as Widget;

    render(<>{renderWidget({ widget })}</>);

    expect(chartMock.props[0]?.title).toBe('Revenue');
    expect(chartMock.props[0]?.drillDown).toMatchObject({
      paramMapping: { revenue_id: 'pid' },
    });
  });
});


describe('Persisted pie designer presentation', () => {
  beforeEach(() => { chartMock.props = []; });
  it.each([true, false])('applies donut and label switches (%s) instead of silently ignoring them', value => {
    const widget = { id: 'status', type: 'smart-pie-chart', config: {
      ring: !value, showLabel: !value,
      visualization: { donut: value, showLabels: value },
      dataSource: { type: 'namedQuery', queryCode: 'delivery_status' },
    } } as unknown as Widget;
    render(<>{renderWidget({ widget })}</>);
    expect(chartMock.props[0]?.ring).toBe(value);
    expect(chartMock.props[0]?.showLabel).toBe(value);
    expect(chartMock.props[0]?.dataSource).toEqual(widget.config.dataSource);
  });
  it('keeps existing direct chart settings when no designer switch is stored', () => {
    const widget = { id: 'status', type: 'smart-pie-chart', config: { ring: true, showLabel: false } } as unknown as Widget;
    render(<>{renderWidget({ widget })}</>);
    expect(chartMock.props[0]?.ring).toBe(true);
    expect(chartMock.props[0]?.showLabel).toBe(false);
  });
});
