import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { RecursiveBlockRenderer } from '../runtime/RecursiveBlockRenderer';
import { defaultInspectorSchemaRegistry } from '../registry/InspectorSchemaRegistry';
import type { DslBlockV3, PageSchemaV3 } from '../types';

/**
 * E1 — widget chart parity. The live dashboard WidgetRenderer already renders any
 * SharedChartFactory chart type (getChartComponent + normalizeChartType). This
 * brings the DESIGNER preview to parity: a widget whose widgetType is any chart
 * type (radar / gauge / funnel / …) now shows a representative chart preview
 * instead of falling back to the number-card value box. number-card itself, and
 * the five hand-written mini-renderers (bar/line/pie/area + progress/table/
 * markdown), are unchanged.
 */

function renderWidget(widgetType: string, extra: Partial<DslBlockV3> = {}) {
  const block = { id: 'wg', blockType: 'widget', widgetType, ...extra } as unknown as DslBlockV3;
  const schema: PageSchemaV3 = {
    schemaVersion: 3,
    kind: 'dashboard',
    id: 'e1_dash',
    blocks: [{ id: 'root', blockType: 'dashboard', blocks: [block] } as DslBlockV3],
  };
  return render(<RecursiveBlockRenderer schema={schema} />);
}

describe('E1 widget chart parity — designer preview', () => {
  it('renders a representative chart preview for an extended chart type (radar)', () => {
    renderWidget('radar', { dataSource: { model: 'sales' } } as Partial<DslBlockV3>);
    const preview = screen.getByTestId('runtime-widget-chart-wg');
    expect(preview).toBeInTheDocument();
    expect(preview).toHaveAttribute('data-chart-type', 'radar');
    expect(preview).toHaveTextContent('radar');
    expect(preview).toHaveTextContent('sales');
  });

  it.each(['scatter', 'gauge', 'funnel', 'heatmap', 'treemap', 'gantt', 'pareto', 'combo'])(
    'renders a chart preview for %s (was previously a number-card fallback)',
    (widgetType) => {
      renderWidget(widgetType);
      const preview = screen.getByTestId('runtime-widget-chart-wg');
      expect(preview).toHaveAttribute('data-chart-type', widgetType);
    },
  );

  it('does NOT show a chart preview for number-card (keeps the value box)', () => {
    renderWidget('number-card', { props: { value: 42 } } as Partial<DslBlockV3>);
    expect(screen.queryByTestId('runtime-widget-chart-wg')).toBeNull();
  });

  it('keeps the dedicated mini-renderer for the original five (bar-chart not generic)', () => {
    renderWidget('bar-chart');
    // bar-chart routes to RuntimeBarChart, NOT the generic chart preview.
    expect(screen.queryByTestId('runtime-widget-chart-wg')).toBeNull();
  });
});

describe('E1 widget chart parity — inspector options', () => {
  it('exposes the extended chart types in the widgetType select', () => {
    const widgetType = defaultInspectorSchemaRegistry
      .getFields('widget')
      .find((field) => field.key === 'widgetType');
    const values = (widgetType?.options ?? []).map((option) => option.value);
    for (const type of ['bar-chart', 'radar', 'scatter', 'gauge', 'funnel', 'heatmap', 'treemap', 'gantt', 'pareto', 'combo']) {
      expect(values, `widgetType offers ${type}`).toContain(type);
    }
    // number-card / table / markdown still offered (non-chart bodies).
    expect(values).toContain('number-card');
    expect(values).toContain('table');
  });
});
