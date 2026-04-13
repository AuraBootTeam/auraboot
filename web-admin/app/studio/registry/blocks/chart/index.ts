import type { BlockDefinition } from '../../types';

export const chartBlock: BlockDefinition = {
  type: 'chart',
  name: 'Chart',
  icon: '📊',
  description: 'Bar/line/pie chart',
  category: 'data',
  defaultColSpan: 6,
  schema: [
    // ── Data Source ──────────────────────────────────────────────
    {
      key: 'dataSourceMode',
      label: 'Data Source',
      type: 'select',
      group: 'Data Source',
      defaultValue: 'modelAggregate',
      options: [
        { label: 'Model Aggregate', value: 'modelAggregate' },
        { label: 'Named Query', value: 'namedQuery' },
        { label: 'Custom API', value: 'customApi' },
      ],
    },

    // Model Aggregate sub-config
    {
      key: 'dataSource.modelCode',
      label: 'Model',
      type: 'model-select',
      group: 'Data Source',
      dependsOn: { field: 'dataSourceMode', value: 'modelAggregate' },
    },
    {
      key: 'metricField',
      label: 'Metric field',
      type: 'text',
      group: 'Data Source',
      placeholder: 'e.g. amount',
      dependsOn: { field: 'dataSourceMode', value: 'modelAggregate' },
    },
    {
      key: 'aggregation',
      label: 'Aggregation',
      type: 'select',
      group: 'Data Source',
      defaultValue: 'SUM',
      options: [
        { label: 'SUM', value: 'SUM' },
        { label: 'COUNT', value: 'COUNT' },
        { label: 'AVG', value: 'AVG' },
        { label: 'MIN', value: 'MIN' },
        { label: 'MAX', value: 'MAX' },
      ],
      dependsOn: { field: 'dataSourceMode', value: 'modelAggregate' },
    },
    {
      key: 'groupDimension',
      label: 'Group dimension',
      type: 'text',
      group: 'Data Source',
      placeholder: 'e.g. status',
      dependsOn: { field: 'dataSourceMode', value: 'modelAggregate' },
    },

    // Named Query sub-config
    {
      key: 'dataSource.queryCode',
      label: 'Query Code',
      type: 'text',
      group: 'Data Source',
      placeholder: 'e.g. sales_by_month',
      dependsOn: { field: 'dataSourceMode', value: 'namedQuery' },
    },

    // Custom API sub-config
    {
      key: 'dataSource.endpoint',
      label: 'Endpoint',
      type: 'text',
      group: 'Data Source',
      placeholder: '/api/stats/monthly',
      dependsOn: { field: 'dataSourceMode', value: 'customApi' },
    },
    {
      key: 'dataSource.params',
      label: 'Params (JSON)',
      type: 'json',
      group: 'Data Source',
      placeholder: '{"period":"month"}',
      dependsOn: { field: 'dataSourceMode', value: 'customApi' },
    },

    // ── Chart Type ──────────────────────────────────────────────
    {
      key: 'chartType',
      label: 'Chart Type',
      type: 'select',
      group: 'Chart Type',
      defaultValue: 'bar',
      options: [
        { label: 'Bar', value: 'bar' },
        { label: 'Line', value: 'line' },
        { label: 'Pie', value: 'pie' },
        { label: 'Area', value: 'area' },
        { label: 'Scatter', value: 'scatter' },
        { label: 'Radar', value: 'radar' },
        { label: 'Heatmap', value: 'heatmap' },
        { label: 'Treemap', value: 'treemap' },
        { label: 'Sunburst', value: 'sunburst' },
        { label: 'Funnel', value: 'funnel' },
        { label: 'Gauge', value: 'gauge' },
        { label: 'Sankey', value: 'sankey' },
        { label: 'Boxplot', value: 'boxplot' },
        { label: 'Candlestick', value: 'candlestick' },
        { label: 'Waterfall', value: 'waterfall' },
        { label: 'Word Cloud', value: 'wordcloud' },
        { label: 'Combo (Bar+Line)', value: 'combo' },
        { label: 'Donut', value: 'donut' },
        { label: 'Stacked Bar', value: 'stacked-bar' },
        { label: 'Stacked Area', value: 'stacked-area' },
      ],
    },

    // ── Style ───────────────────────────────────────────────────
    {
      key: 'chartTitle',
      label: 'Chart title',
      type: 'text',
      group: 'Style',
      placeholder: 'Optional title...',
    },
    {
      key: 'height',
      label: 'Height (px)',
      type: 'number',
      group: 'Style',
      defaultValue: 300,
    },
    {
      key: 'showLegend',
      label: 'Show legend',
      type: 'boolean',
      group: 'Style',
      defaultValue: false,
    },
    {
      key: 'showValues',
      label: 'Show value labels',
      type: 'boolean',
      group: 'Style',
      defaultValue: false,
    },

    // ── Conditions ──────────────────────────────────────────────
    {
      key: 'visibleWhen',
      label: 'Visible when',
      type: 'expression',
      group: 'Conditions',
      description: 'Condition expression to control block visibility',
    },
  ],
};
