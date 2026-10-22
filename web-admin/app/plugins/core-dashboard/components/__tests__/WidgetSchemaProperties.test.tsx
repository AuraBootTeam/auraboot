import React from 'react';
import { afterEach, describe, it, expect, vi } from 'vitest';
import { render, fireEvent, cleanup, screen, within, act } from '@testing-library/react';
import { I18nProvider } from '~/contexts/I18nContext';
import { WidgetPropertyPanel } from '../WidgetPropertyPanel';
import { useDashboardStore } from '../../store/useDashboardStore';
import { widgetRegistry } from '../../widgets/widgetRegistry';
import { dashboardPropertySchemas } from '../dashboardPropertySchemas';

// Complex editors have their own tests; this suite exercises the real basic schema/store seam.
vi.mock('../DataSourceConfig', () => ({ DataSourceConfig: () => null }));
vi.mock('../DrilldownConfig', () => ({ DrilldownConfig: () => null }));
afterEach(cleanup);
it('keeps every registered widget schema and its custom-control declarations reachable', () => {
  expect(widgetRegistry.getAll().length).toBeGreaterThan(20);
  for (const definition of widgetRegistry.getAll()) {
    const schema = dashboardPropertySchemas(
      definition.configSchema ?? [],
      (k) => k,
      'en-US',
      definition.type,
    );
    const keys = schema.map((field) => field.key);
    expect(new Set(keys).size, definition.type).toBe(keys.length);
    for (const original of definition.configSchema ?? []) {
      if (!original.key.startsWith('dataSource.'))
        expect(keys, definition.type).toContain(original.key);
    }
    expect(schema.find((field) => field.key === 'dataSource')?.editor).toBe(
      'dashboard.data-source',
    );
  }
});
describe('dashboard schema document properties', () => {
  it('changes a zero numeric value, nested toggles and defaults through real undo/redo', () => {
    const store = useDashboardStore.getState();
    store.createDashboard('Schema board');
    const id = store.addWidget({
      type: 'smart-number-card',
      componentType: 'smart-number-card',
      props: {},
      x: 0,
      y: 0,
      w: 3,
      h: 2,
      config: { title: 'Revenue', precision: 0, dataSource: { type: 'static' } },
    });
    useDashboardStore.getState().selectWidget(id);
    render(
      <I18nProvider initialLocale="en-US" initialData={{ fixture: 'loaded' }}>
        <WidgetPropertyPanel />
      </I18nProvider>,
    );
    expect(
      within(screen.getByTestId('schema-config-field-refreshInterval')).getByRole('spinbutton'),
    ).toHaveValue(0);
    const radius = within(screen.getByTestId('schema-config-field-style.borderRadius')).getByRole(
      'spinbutton',
    );
    fireEvent.change(radius, { target: { value: '12' } });
    const widget = () => useDashboardStore.getState().widgets.find((widget) => widget.id === id)!;
    expect(widget().config.style?.borderRadius).toBe(12);
    expect(widget().config.precision).toBe(0);
    act(() => useDashboardStore.getState().undo());
    expect(widget().config.style).toBeUndefined();
    act(() => useDashboardStore.getState().redo());
    expect(widget().config.style?.borderRadius).toBe(12);
    expect(
      screen.queryByTestId('schema-config-field-style.legendPosition'),
    ).not.toBeInTheDocument();
    const pie = widgetRegistry.get('smart-pie-chart')!;
    let pieId = '';
    act(() => {
      pieId = useDashboardStore
        .getState()
        .addWidget({
          type: pie.type,
          componentType: pie.type,
          props: {},
          x: 0,
          y: 3,
          w: 6,
          h: 4,
          config: { ...pie.defaultConfig, title: pie.defaultConfig.title ?? 'Distribution' },
        });
      useDashboardStore.getState().selectWidget(pieId);
    });
    expect(useDashboardStore.getState().widgets.find((widget) => widget.id === pieId)?.type).toBe(
      'smart-pie-chart',
    );
    expect(screen.getByTestId('schema-config-field-style.legendPosition')).toBeInTheDocument();
    fireEvent.click(
      within(screen.getByTestId('schema-config-field-style.showLegend')).getByRole('switch'),
    );
    expect(
      useDashboardStore.getState().widgets.find((widget) => widget.id === pieId)!.config.style
        ?.showLegend,
    ).toBe(false);
    expect(
      screen.queryByTestId('schema-config-field-style.legendPosition'),
    ).not.toBeInTheDocument();
    fireEvent.change(
      within(screen.getByTestId('schema-config-field-style.borderRadius')).getByRole('spinbutton'),
      { target: { value: '99' } },
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(
      useDashboardStore.getState().widgets.find((widget) => widget.id === pieId)!.config.style
        ?.borderRadius,
    ).toBeUndefined();
  });
});

it('edits monetary precision through the registered schema with undo and redo', () => {
  const store = useDashboardStore.getState();
  store.createDashboard('Money board');
  const id = store.addWidget({ type: 'smart-number-card', componentType: 'smart-number-card', props: {}, x: 0, y: 0, w: 3, h: 2, config: { title: 'Budget', precision: 0, format: 'currency' } });
  useDashboardStore.getState().selectWidget(id);
  render(<I18nProvider initialLocale="en-US" initialData={{ fixture: 'loaded' }}><WidgetPropertyPanel /></I18nProvider>);
  const precision = within(screen.getByTestId('schema-config-field-precision')).getByRole('spinbutton');
  expect(precision).toHaveValue(0);
  fireEvent.change(precision, { target: { value: '2' } });
  const widget = () => useDashboardStore.getState().widgets.find(widget => widget.id === id)!;
  expect(widget().config.precision).toBe(2);
  act(() => useDashboardStore.getState().undo());
  expect(widget().config.precision).toBe(0);
  act(() => useDashboardStore.getState().redo());
  expect(widget().config.precision).toBe(2);
  fireEvent.click(within(screen.getByTestId('schema-config-field-format')).getByRole('combobox'));
  fireEvent.click(screen.getByRole('option', { name: 'Number', exact: true }));
  expect(widget().config.format).toBe('number');
});
