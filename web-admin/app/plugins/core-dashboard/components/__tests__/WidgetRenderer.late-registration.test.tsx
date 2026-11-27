import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { renderWidget } from '../WidgetRenderer';
import { registerChartComponent } from '~/framework/smart/charts/SharedChartFactory';
import type { Widget } from '../../types';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: 'zh-CN',
    t: (key: string) => key,
  }),
}));

// The registry is process-wide, so the probe uses a type that no plugin registers.
const TYPE = 'smart-late-registration-probe';
const KEY = 'late-registration-probe';

describe('WidgetRenderer late registration', () => {
  it('shows the placeholder for an unregistered type, then renders it once a plugin registers it', async () => {
    // The plugin boot is async and browser-only: a widget can be rendered before its plugin has
    // registered it. The first paint must say so, and must not stay that way after registration.
    const widget = { id: 'late', type: TYPE, config: { title: '后登记的卡片' } } as unknown as Widget;
    render(<>{renderWidget({ widget })}</>);
    expect(screen.getByText(`Unknown widget: ${TYPE}`)).toBeTruthy();

    await act(async () => {
      registerChartComponent(KEY, (props: { title?: string }) => <div>probe:{props.title}</div>);
    });

    expect(screen.queryByText(`Unknown widget: ${TYPE}`)).toBeNull();
    expect(await screen.findByText('probe:后登记的卡片')).toBeTruthy();
  });

  it('keeps the placeholder when nothing registers the type', () => {
    const widget = { id: 'never', type: 'smart-never-registered-probe', config: {} } as unknown as Widget;
    render(<>{renderWidget({ widget })}</>);
    expect(screen.getByText('Unknown widget: smart-never-registered-probe')).toBeTruthy();
  });
});
