import type { WidgetConfig } from '../types';

/** Capabilities reflect the existing Smart chart props, not a parallel renderer. */
const chartOptionsTypes = new Set([
  'pie',
  'heatmap',
  'area',
  'line',
  'scatter',
  'treemap',
  'pareto',
  'waterfall',
  'bar',
  'radar',
  'spc',
  'funnel',
  'combo',
]);
const labelTypes = new Set(['line', 'funnel', 'pie', 'waterfall', 'combo', 'bar', 'area']);
export function widgetAppearanceCapabilities(type: string) {
  const normalized = type.replace(/^smart-/, '').replace(/-chart$/, '');
  return {
    chartOptions: chartOptionsTypes.has(normalized),
    label: labelTypes.has(normalized),
    labelPosition: ['pie', 'funnel'].includes(normalized),
  };
}
const palettes: Record<string, string[]> = {
  default: ['#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de'],
  vintage: ['#d87c7c', '#919e8b', '#d7ab82', '#6e7074', '#61a0a8'],
  dark: ['#dd6b66', '#759aa0', '#e69d87', '#8dc1a9', '#ea7e53'],
  westeros: ['#516b91', '#59c4e6', '#edafda', '#93b7e3', '#a5e7f0'],
  macarons: ['#2ec7c9', '#b6a2de', '#5ab1ef', '#ffb980', '#d87a80'],
};

/** Translate only explicitly configured appearance. Keep native chart defaults intact. */
export function widgetAppearanceProps(type: string, config: WidgetConfig): Record<string, unknown> {
  if (!config.style) return {};
  const capabilities = widgetAppearanceCapabilities(type);
  const { colorTheme, showTitle, showLegend, legendPosition, showLabel, labelPosition, ...css } =
    config.style;
  const props: Record<string, unknown> = { style: css };
  if (showTitle === false) props.title = undefined;
  if (
    capabilities.label &&
    showLabel !== undefined &&
    config.visualization?.showLabels === undefined
  )
    props.showLabel = showLabel;
  if (capabilities.labelPosition && labelPosition !== undefined)
    props.labelPosition = labelPosition;
  if (capabilities.chartOptions) {
    const options = { ...((config.chartOptions ?? {}) as Record<string, unknown>) };
    if (showTitle !== undefined)
      options.title = { ...((options.title ?? {}) as Record<string, unknown>), show: showTitle };
    if (colorTheme && palettes[colorTheme]) options.color = palettes[colorTheme];
    if (showLegend !== undefined || legendPosition !== undefined) {
      const position: Record<string, unknown> =
        legendPosition === 'top'
          ? { top: 0, bottom: undefined, left: 'center', right: undefined, orient: 'horizontal' }
          : legendPosition === 'left'
            ? { top: 'middle', bottom: undefined, left: 0, right: undefined, orient: 'vertical' }
            : legendPosition === 'right'
              ? { top: 'middle', bottom: undefined, left: undefined, right: 0, orient: 'vertical' }
              : legendPosition === 'bottom'
                ? {
                    top: undefined,
                    bottom: 0,
                    left: 'center',
                    right: undefined,
                    orient: 'horizontal',
                  }
                : {};
      const apply = (legend: unknown) => ({
        ...((legend ?? {}) as Record<string, unknown>),
        ...position,
        ...(showLegend === undefined ? {} : { show: showLegend }),
      });
      options.legend = Array.isArray(options.legend)
        ? options.legend.map(apply)
        : apply(options.legend);
    }
    props.chartOptions = options;
  }
  return props;
}

export function widgetContainerStyle(config: WidgetConfig) {
  return {
    borderRadius: config.style?.borderRadius,
    backgroundColor: config.style?.backgroundColor,
  };
}

/** Adapt persisted designer vocabulary to existing Smart component contracts. */
export function widgetPresentationProps(
  type: string,
  config: WidgetConfig,
): Record<string, unknown> {
  const visual = config.visualization ?? {};
  switch (type.replace(/^smart-/, '').replace(/-chart$/, '')) {
    case 'pie':
      return {
        ring: visual.donut ?? config.ring,
        showLabel: visual.showLabels ?? config.showLabel,
      };
    case 'bar':
      return visual.horizontal === undefined
        ? {}
        : {
            orientation: visual.horizontal ? 'horizontal' : 'vertical',
          };
    case 'line':
      return visual.showArea === undefined ? {} : { areaStyle: visual.showArea };
    case 'number-card':
      return visual.showTrend === undefined
        ? {}
        : {
            trend: { ...config.trend, enabled: visual.showTrend },
          };
    default:
      return {};
  }
}
