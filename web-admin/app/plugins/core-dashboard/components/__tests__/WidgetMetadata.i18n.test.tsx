import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import { widgetDefinitions, widgetRegistry } from '../../widgets/widgetRegistry';
import { createWidgetDraft } from '../../utils/createWidgetDraft';
import { useDashboardStore } from '../../store/useDashboardStore';
import { WidgetPalette } from '../WidgetPalette';
import { WidgetPropertyPanel } from '../WidgetPropertyPanel';
import type { Dashboard, PropertySchema } from '../../types';

const fixture = vi.hoisted(() => ({ locale: 'en-GB', create: vi.fn(), findByPid: vi.fn() }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: fixture.locale, t: (_key: string, _params?: unknown, fallback?: string) => fallback ?? _key }),
}));
vi.mock('../../services/dashboardService', () => ({ dashboardService: { create: fixture.create, findByPid: fixture.findByPid } }));
vi.mock('../DataSourceConfig', () => ({ DataSourceConfig: () => null }));
vi.mock('../LinkageConfig', () => ({ LinkageConfig: () => null }));
vi.mock('../StyleConfig', () => ({ StyleConfig: () => null }));
vi.mock('../RefreshConfig', () => ({ RefreshConfig: () => null }));
vi.mock('../DrilldownConfig', () => ({ DrilldownConfig: () => null }));
vi.mock('~/plugins/core-designer/components/studio/workbench/panels/property-editors/IconPicker', () => ({ IconPicker: () => null }));

function assertEnglish(text: unknown) {
  const resolved = getLocalizedText(text as string | Record<string, string>, 'en-GB');
  expect(resolved.trim()).not.toBe('');
  expect(resolved).not.toMatch(/[一-鿿]/);
}
function checkSchema(schema: PropertySchema) {
  assertEnglish(schema.label);
  for (const value of [schema.placeholder, schema.description, schema.group]) if (value) assertEnglish(value);
  for (const option of schema.options ?? []) assertEnglish(option.label);
  for (const item of schema.itemSchema ?? []) checkSchema(item);
}

beforeEach(() => {
  fixture.locale = 'en-GB';
  fixture.create.mockReset(); fixture.findByPid.mockReset();
  useDashboardStore.getState().createDashboard('Locale fixture');
});

describe('dashboard registry locale flow', () => {
  it('provides English metadata for every registered widget, category and property', () => {
    // Frozen supported types prevent pruning the localization denominator.
    expect(widgetDefinitions.map((definition) => definition.type)).toEqual(["smart-number-card", "smart-bar-chart", "smart-line-chart", "smart-pie-chart", "smart-waterfall-chart", "smart-pareto-chart", "smart-gantt-chart", "smart-area-chart", "smart-funnel-chart", "smart-scatter-chart", "smart-radar-chart", "smart-table-chart", "smart-gauge-chart", "smart-filter-bar", "smart-progress", "smart-heatmap-chart", "smart-treemap-chart", "smart-map-chart", "smart-leaderboard", "smart-rich-text", "smart-image", "smart-iframe", "smart-countdown", "smart-wordcloud-chart", "smart-combo-chart", "smart-nps-chart", "smart-gallery", "smart-kanban", "smart-stats-row", "smart-stats-card", "smart-inbox", "smart-calendar", "smart-shortcuts", "smart-recent", "smart-announcement", "smart-quick-note"]);
    for (const definition of widgetDefinitions) {
      assertEnglish(definition.label);
      assertEnglish(definition.description);
      assertEnglish(definition.categoryLabel);
      if (definition.defaultConfig.title) assertEnglish(definition.defaultConfig.title);
      for (const schema of definition.configSchema ?? []) checkSchema(schema);
      expect(widgetRegistry.get(definition.type)?.category).toBe(definition.category);
    }
  });

  it('renders English palette labels/categories and preserves click and drag widget identity', () => {
    const onWidgetClick = vi.fn(); const setData = vi.fn(); const onDragStart = vi.fn();
    render(<WidgetPalette onWidgetClick={onWidgetClick} onDragStart={onDragStart} />);
    expect(screen.getByText('Metrics')).toBeVisible();
    const item = screen.getByTestId('widget-palette-item-smart-number-card');
    expect(item).toHaveTextContent('Number Card');
    fireEvent.click(item);
    expect(onWidgetClick).toHaveBeenCalledExactlyOnceWith('smart-number-card');
    fireEvent.dragStart(item, { dataTransfer: { setData, setDragImage: vi.fn(), effectAllowed: '' } });
    expect(setData).toHaveBeenCalledWith('application/widget-type', 'smart-number-card');
    expect(onDragStart.mock.calls[0][1]).toBe('smart-number-card');
  });

  it('changes palette text with locale without changing registry grouping', () => {
    const categories = widgetRegistry.getCategories();
    const view = render(<WidgetPalette />);
    expect(screen.getByTestId('widget-palette-item-smart-number-card')).toHaveTextContent('Number Card');
    fixture.locale = 'zh-CN'; view.rerender(<WidgetPalette />);
    expect(screen.getByTestId('widget-palette-item-smart-number-card')).toHaveTextContent('数字卡片');
    expect(widgetRegistry.getCategories()).toEqual(categories);
  });

  it('keeps independently editable bilingual default titles in widget drafts', () => {
    const definition = widgetRegistry.get('smart-number-card')!;
    const first = createWidgetDraft(definition, { x: 0, y: 0 });
    const second = createWidgetDraft(definition, { x: 2, y: 0 });
    expect(first.config.title).toEqual({ 'zh-CN': '数字卡片', 'en-US': 'Number Card' });
    (first.config.title as Record<string, string>)['en-US'] = 'My Metric';
    expect(second.config.title).toEqual({ 'zh-CN': '数字卡片', 'en-US': 'Number Card' });
    expect(definition.defaultConfig.title).toEqual(second.config.title);
    expect(first.type).toBe('smart-number-card');
    expect(first.config.dataSource).toEqual(definition.defaultConfig.dataSource);
  });

  it('edits a bilingual title through the property panel and preserves it in save/load DTOs', async () => {
    const id = useDashboardStore.getState().addWidget(createWidgetDraft(widgetRegistry.get('smart-number-card')!, { x: 0, y: 0 }));
    render(<WidgetPropertyPanel />);
    expect(screen.getByRole('heading', { name: 'Number Card' })).toBeVisible();
    expect(screen.getByText('Data Source Type')).toBeVisible();
    expect(screen.getByRole('option', { name: 'Aggregate Query' })).toHaveValue('aggregate');
    fireEvent.change(screen.getByTestId('widget-prop-title-en'), { target: { value: 'My Metric' } });
    fireEvent.click(screen.getByTestId('widget-prop-title-toggle'));
    fireEvent.change(screen.getByTestId('widget-prop-title-zh'), { target: { value: '我的指标' } });
    expect(useDashboardStore.getState().getWidgetById(id)?.config.title).toEqual({ 'zh-CN': '我的指标', 'en-US': 'My Metric' });
    fixture.create.mockImplementation(async (data: Dashboard) => ({ ...JSON.parse(JSON.stringify(data)), pid: 'locale-fixture' }));
    await useDashboardStore.getState().saveDashboard();
    const payload = fixture.create.mock.calls[0][0] as Dashboard;
    expect(payload.widgets[0].config.title).toEqual({ 'zh-CN': '我的指标', 'en-US': 'My Metric' });
    fixture.findByPid.mockResolvedValue({ ...JSON.parse(JSON.stringify(payload)), pid: 'locale-fixture' });
    await useDashboardStore.getState().loadDashboard('locale-fixture');
    expect(getLocalizedText(useDashboardStore.getState().widgets[0].config.title, 'en-GB')).toBe('My Metric');
    expect(widgetRegistry.get('smart-number-card')!.defaultConfig.title).toEqual({ 'zh-CN': '数字卡片', 'en-US': 'Number Card' });
  });

  it('duplicates localized titles without stringifying objects or changing widget type', () => {
    const store = useDashboardStore.getState();
    const id = store.addWidget(createWidgetDraft(widgetRegistry.get('smart-number-card')!, { x: 0, y: 0 }));
    const duplicateId = useDashboardStore.getState().duplicateWidget(id);
    const copy = useDashboardStore.getState().getWidgetById(duplicateId)!;
    expect(copy.config.title).toEqual({ 'zh-CN': '数字卡片 (副本)', 'en-US': 'Number Card (Copy)' });
    expect(copy.type).toBe('smart-number-card');
    expect(getLocalizedText(copy.config.title, 'en-GB')).toBe('Number Card (Copy)');
  });

  it('localizes the empty property panel without inventing a selected widget', () => {
    render(<WidgetPropertyPanel />);
    expect(screen.getByRole('heading', { name: 'Properties' })).toBeVisible();
    expect(screen.getByText('Select a widget to inspect its properties')).toBeVisible();
    expect(useDashboardStore.getState().selectedWidgetId).toBeNull();
  });
});
