/**
 * Adapters: legacy chart config → ChartSpec (B2a coverage proof).
 *
 * These prove that the existing chart authoring surface (the dashboard `ChartConfig`
 * and the DSL `chart` block's `chartConfig` field binding) is fully expressible as a
 * renderer-agnostic ChartSpec — so ChartSpec is a consolidation, not a parallel DSL.
 *
 * They also surface the ONE renderer leak in the legacy model: `ChartConfig.chartOptions`
 * ("passed to ECharts") is dropped and reported as a warning; removing it is B2d.
 */

import type { ChartConfig } from '../types/chart';
import {
  type ChartSpec,
  type ChartDimension,
  type ChartMeasure,
  type ChartSpecType,
  isChartSpecType,
} from './chart-spec';

export interface ChartSpecWarning {
  code: 'RENDERER_LEAK_DROPPED' | 'TYPE_COERCED';
  message: string;
}

/** Legacy `chartConfig` field binding (from the DSL chart block). */
export interface ChartFieldBinding {
  /** category axis: any of these (first non-empty wins) */
  categoryField?: string;
  xField?: string;
  /** pie/slice name */
  nameField?: string;
  /** series split */
  groupField?: string;
  seriesField?: string;
  /** measure(s) */
  valueField?: string;
  yField?: string | string[];
}

/**
 * Map the legacy field-name binding (`{ categoryField, xField, nameField, groupField,
 * seriesField, valueField, yField }`) to ChartSpec dimensions + measures.
 */
export function bindingFromFields(fields: ChartFieldBinding, type?: ChartSpecType): {
  dimensions: ChartDimension[];
  measures: ChartMeasure[];
} {
  const dimensions: ChartDimension[] = [];

  // category / name axis
  const nameLike = fields.nameField;
  const categoryLike = fields.categoryField || fields.xField;
  if (type === 'pie' && nameLike) {
    dimensions.push({ field: nameLike, role: 'name' });
  } else if (categoryLike) {
    dimensions.push({ field: categoryLike, role: 'category' });
  } else if (nameLike) {
    dimensions.push({ field: nameLike, role: 'name' });
  }

  // series split
  const series = fields.groupField || fields.seriesField;
  if (series) dimensions.push({ field: series, role: 'series' });

  // measures (yField may be multi)
  const measures: ChartMeasure[] = [];
  const yFields = Array.isArray(fields.yField)
    ? fields.yField
    : fields.yField
      ? [fields.yField]
      : [];
  const valueFields = fields.valueField ? [fields.valueField] : [];
  for (const f of [...valueFields, ...yFields]) {
    measures.push({ field: f });
  }

  return { dimensions, measures };
}

/**
 * Map the dashboard `ChartConfig` to a ChartSpec. Returns the spec plus any
 * warnings (notably the dropped `chartOptions` ECharts leak).
 *
 * Throws if `type` is not a ChartSpec type (e.g. 'number' = number-card display
 * widget, which has no dimension/measure contract).
 */
export function chartConfigToSpec(config: ChartConfig): {
  spec: ChartSpec;
  warnings: ChartSpecWarning[];
} {
  const warnings: ChartSpecWarning[] = [];

  if (!isChartSpecType(config.type)) {
    throw new Error(
      `chartConfigToSpec: "${config.type}" is not a ChartSpec type (display widgets like number-card are out of ChartSpec scope).`,
    );
  }
  const type = config.type;

  // dimensions from the query's dimension fields; measures from its metrics.
  const dimensions: ChartDimension[] = (config.dataSource.dimensions ?? []).map((field, i) => ({
    field,
    role: type === 'pie' && i === 0 ? 'name' : i === 0 ? 'category' : 'series',
  }));
  const measures: ChartMeasure[] = (config.dataSource.metrics ?? []).map((m) => ({
    field: m.field,
    aggregation: m.aggregation,
    label: m.alias,
  }));

  if (config.chartOptions && Object.keys(config.chartOptions).length > 0) {
    warnings.push({
      code: 'RENDERER_LEAK_DROPPED',
      message:
        'ChartConfig.chartOptions ("passed to ECharts") is a renderer leak and was dropped from the ChartSpec; migrate visual intent to ChartSpec.visual (B2d).',
    });
  }

  const spec: ChartSpec = {
    type,
    title: config.title,
    dataSource: config.dataSource,
    dimensions,
    measures,
    filters: config.dataSource.filters,
    drilldown: config.drillDown,
    interaction: {
      tooltip: true,
      linkage: config.linkage,
      refreshIntervalMs: config.refreshInterval,
    },
  };

  return { spec, warnings };
}
