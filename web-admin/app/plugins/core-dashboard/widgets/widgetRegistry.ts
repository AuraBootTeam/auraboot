/**
 * Widget Registry
 * Central registry for dashboard widget definitions
 */

import type { WidgetDefinition, PropertySchema } from '../types';
import { widgetText } from './widgetText';
import { DesignerRegistry } from '~/shared/designer/types';
import { resolveWidgetTier } from '../registry/widgetManifest';

/**
 * Common property schemas for data source configuration.
 *
 * Semantic-layer routing (PR #377) is configured in the richer DataSourceConfig
 * panel via the "Data source mode: raw model / semantic model" switch — the single source of
 * truth for picking a semantic model and its governed metrics/dimensions. The
 * earlier standalone `semantic-model-select` schema field was removed to avoid
 * a second, conflicting model dropdown (PRD 16 W4 D4 switch-style consolidation).
 */
const dataSourcePropertySchemas: PropertySchema[] = [
  {
    key: 'dataSource.type',
    label: widgetText('registry.dataSourceType'),
    type: 'select',
    required: true,
    options: [
      { label: widgetText('registry.aggregateQuery'), value: 'aggregate' },
      { label: widgetText('registry.namedQuery'), value: 'namedQuery' },
    ],
    defaultValue: 'aggregate',
  },
  {
    key: 'dataSource.modelCode',
    label: widgetText('registry.model'),
    type: 'model',
    dependsOn: { field: 'dataSource.type', value: 'aggregate' },
  },
  {
    key: 'dataSource.queryCode',
    label: widgetText('registry.namedQuery'),
    type: 'namedQuery',
    dependsOn: { field: 'dataSource.type', value: 'namedQuery' },
  },
];

/**
 * Widget definitions for dashboard
 */
const widgetDefinitions: WidgetDefinition[] = [
  {
    type: 'smart-number-card',
    label: widgetText('registry.numberCard'),
    icon: 'NumberOutlined',
    category: widgetText('registry.metrics')['zh-CN'],
    categoryLabel: widgetText('registry.metrics'),
    description: widgetText('registry.displayASingleMetricValue'),
    defaultConfig: {
      title: widgetText('registry.numberCard'),
      dataSource: {
        type: 'aggregate',
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 3,
      h: 2,
      minW: 2,
      minH: 2,
      maxW: 6,
      maxH: 4,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      {
        key: 'icon',
        label: widgetText('registry.icon'),
        type: 'icon',
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.suffix',
        label: widgetText('registry.suffix'),
        type: 'text',
        placeholder: widgetText('registry.forExampleItemsPeople'),
      },
      {
        key: 'visualization.showTrend',
        label: widgetText('registry.showTrend'),
        type: 'boolean',
        defaultValue: false,
      },
    ],
  },
  {
    type: 'smart-bar-chart',
    label: widgetText('registry.barChart'),
    icon: 'BarChartOutlined',
    category: widgetText('registry.charts')['zh-CN'],
    categoryLabel: widgetText('registry.charts'),
    description: widgetText('registry.columnOrBarChart'),
    defaultConfig: {
      title: widgetText('registry.barChart'),
      dataSource: {
        type: 'aggregate',
        dimensions: [],
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 6,
      h: 4,
      minW: 4,
      minH: 3,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.horizontal',
        label: widgetText('registry.horizontal'),
        type: 'boolean',
        defaultValue: false,
      },
      {
        key: 'visualization.stacked',
        label: widgetText('registry.stacked'),
        type: 'boolean',
        defaultValue: false,
      },
    ],
  },
  {
    type: 'smart-line-chart',
    label: widgetText('registry.lineChart'),
    icon: 'LineChartOutlined',
    category: widgetText('registry.charts')['zh-CN'],
    categoryLabel: widgetText('registry.charts'),
    description: widgetText('registry.lineOrTrendChart'),
    defaultConfig: {
      title: widgetText('registry.lineChart'),
      dataSource: {
        type: 'aggregate',
        dimensions: [],
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 6,
      h: 4,
      minW: 4,
      minH: 3,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.smooth',
        label: widgetText('registry.smoothCurve'),
        type: 'boolean',
        defaultValue: true,
      },
      {
        key: 'visualization.showArea',
        label: widgetText('registry.showArea'),
        type: 'boolean',
        defaultValue: false,
      },
    ],
  },
  {
    type: 'smart-pie-chart',
    label: widgetText('registry.pieChart'),
    icon: 'PieChartOutlined',
    category: widgetText('registry.charts')['zh-CN'],
    categoryLabel: widgetText('registry.charts'),
    description: widgetText('registry.pieOrDoughnutChart'),
    defaultConfig: {
      title: widgetText('registry.pieChart'),
      dataSource: {
        type: 'aggregate',
        dimensions: [],
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 4,
      h: 4,
      minW: 3,
      minH: 3,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.donut',
        label: widgetText('registry.doughnut'),
        type: 'boolean',
        defaultValue: false,
      },
      {
        key: 'visualization.showLabels',
        label: widgetText('registry.showLabels'),
        type: 'boolean',
        defaultValue: true,
      },
    ],
  },
  {
    type: 'smart-waterfall-chart',
    label: widgetText('registry.waterfallChart'),
    icon: 'StockOutlined',
    category: widgetText('registry.charts')['zh-CN'],
    categoryLabel: widgetText('registry.charts'),
    description: widgetText('registry.waterfallChartWithIncreaseDecreaseAndTotalAnchorRows'),
    defaultConfig: {
      title: widgetText('registry.waterfallChart'),
      dataSource: {
        type: 'aggregate',
        dimensions: [],
        metrics: [{ field: 'id', aggregation: 'sum' }],
      },
      visualization: { showLabel: true },
    },
    defaultSize: {
      w: 6,
      h: 4,
      minW: 4,
      minH: 3,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.totalField',
        label: widgetText('registry.totalRowFieldOptional'),
        type: 'text',
        placeholder: 'bridge_kind',
      },
      {
        key: 'visualization.totalValues',
        label: widgetText('registry.totalRowValuesCommaSeparated'),
        type: 'text',
        placeholder: widgetText('registry.totalSubtotal'),
      },
      {
        key: 'visualization.showLabel',
        label: widgetText('registry.showLabels'),
        type: 'boolean',
        defaultValue: true,
      },
    ],
  },
  {
    type: 'smart-pareto-chart',
    label: widgetText('registry.paretoChart'),
    icon: 'BarChartOutlined',
    category: widgetText('registry.charts')['zh-CN'],
    categoryLabel: widgetText('registry.charts'),
    description: widgetText('registry.barsWithACumulativePercentageLine'),
    defaultConfig: {
      title: widgetText('registry.paretoChart'),
      dataSource: {
        type: 'aggregate',
        dimensions: [],
        metrics: [{ field: 'id', aggregation: 'sum' }],
      },
    },
    defaultSize: {
      w: 6,
      h: 4,
      minW: 4,
      minH: 3,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
    ],
  },
  {
    type: 'smart-gantt-chart',
    label: widgetText('registry.ganttChart'),
    icon: 'OrderedListOutlined',
    category: widgetText('registry.charts')['zh-CN'],
    categoryLabel: widgetText('registry.charts'),
    description: widgetText('registry.taskStartEndAndProgressChart'),
    defaultConfig: {
      title: widgetText('registry.ganttChart'),
      dataSource: {
        type: 'aggregate',
        dimensions: [],
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 8,
      h: 5,
      minW: 5,
      minH: 4,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
    ],
  },
  {
    type: 'smart-area-chart',
    label: widgetText('registry.areaChart'),
    icon: 'AreaChartOutlined',
    category: widgetText('registry.charts')['zh-CN'],
    categoryLabel: widgetText('registry.charts'),
    description: widgetText('registry.areaOrStackedAreaChart'),
    defaultConfig: {
      title: widgetText('registry.areaChart'),
      dataSource: {
        type: 'aggregate',
        dimensions: [],
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
      visualization: { smooth: true, fillOpacity: 0.6 },
    },
    defaultSize: {
      w: 6,
      h: 4,
      minW: 4,
      minH: 3,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.smooth',
        label: widgetText('registry.smoothCurve'),
        type: 'boolean',
        defaultValue: true,
      },
      {
        key: 'visualization.fillOpacity',
        label: widgetText('registry.fillOpacity'),
        type: 'number',
        defaultValue: 0.6,
        placeholder: '0-1',
      },
    ],
  },
  {
    type: 'smart-funnel-chart',
    label: widgetText('registry.funnelChart'),
    icon: 'FunnelPlotOutlined',
    category: widgetText('registry.charts')['zh-CN'],
    categoryLabel: widgetText('registry.charts'),
    description: widgetText('registry.funnelOrConversionChart'),
    defaultConfig: {
      title: widgetText('registry.funnelChart'),
      dataSource: {
        type: 'aggregate',
        dimensions: [],
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 4,
      h: 4,
      minW: 3,
      minH: 3,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.sort',
        label: widgetText('registry.sortOrder'),
        type: 'select',
        options: [
          { label: widgetText('registry.descending'), value: 'descending' },
          { label: widgetText('registry.ascending'), value: 'ascending' },
          { label: widgetText('registry.unsorted'), value: 'none' },
        ],
        defaultValue: 'descending',
      },
    ],
  },
  {
    type: 'smart-scatter-chart',
    label: widgetText('registry.scatterChart'),
    icon: 'DotChartOutlined',
    category: widgetText('registry.charts')['zh-CN'],
    categoryLabel: widgetText('registry.charts'),
    description: widgetText('registry.scatterOrBubbleChart'),
    defaultConfig: {
      title: widgetText('registry.scatterChart'),
      dataSource: {
        type: 'aggregate',
        dimensions: [],
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 6,
      h: 4,
      minW: 4,
      minH: 3,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.bubbleMode',
        label: widgetText('registry.bubbleMode'),
        type: 'boolean',
        defaultValue: false,
      },
    ],
  },
  {
    type: 'smart-radar-chart',
    label: widgetText('registry.radarChart'),
    icon: 'RadarChartOutlined',
    category: widgetText('registry.charts')['zh-CN'],
    categoryLabel: widgetText('registry.charts'),
    description: widgetText('registry.radarChartForMultidimensionalComparison'),
    defaultConfig: {
      title: widgetText('registry.radarChart'),
      dataSource: {
        type: 'aggregate',
        dimensions: [],
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 4,
      h: 4,
      minW: 4,
      minH: 4,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.shape',
        label: widgetText('registry.shape'),
        type: 'select',
        options: [
          { label: widgetText('registry.polygon'), value: 'polygon' },
          { label: widgetText('registry.circle'), value: 'circle' },
        ],
        defaultValue: 'polygon',
      },
      {
        key: 'visualization.showArea',
        label: widgetText('registry.showArea'),
        type: 'boolean',
        defaultValue: true,
      },
    ],
  },
  {
    type: 'smart-table-chart',
    label: widgetText('registry.dataTable'),
    icon: 'TableOutlined',
    category: widgetText('registry.data')['zh-CN'],
    categoryLabel: widgetText('registry.data'),
    description: widgetText('registry.displayTabularData'),
    defaultConfig: {
      title: widgetText('registry.dataTable'),
      dataSource: {
        type: 'aggregate',
        dimensions: [],
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 6,
      h: 4,
      minW: 4,
      minH: 3,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.pageSize',
        label: widgetText('registry.rowsPerPage'),
        type: 'number',
        defaultValue: 10,
      },
      {
        key: 'visualization.striped',
        label: widgetText('registry.stripedRows'),
        type: 'boolean',
        defaultValue: true,
      },
    ],
  },
  // ==================== New Data Analysis Widgets ====================
  {
    type: 'smart-gauge-chart',
    label: widgetText('registry.gauge'),
    icon: '🎯',
    category: widgetText('registry.charts')['zh-CN'],
    categoryLabel: widgetText('registry.charts'),
    description: widgetText('registry.gaugeDisplayingASingleMetric'),
    defaultConfig: {
      title: widgetText('registry.gauge'),
      dataSource: {
        type: 'aggregate',
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 4,
      h: 4,
      minW: 3,
      minH: 3,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.min',
        label: widgetText('registry.minimum'),
        type: 'number',
        defaultValue: 0,
      },
      {
        key: 'visualization.max',
        label: widgetText('registry.maximum'),
        type: 'number',
        defaultValue: 100,
      },
      {
        key: 'visualization.splitNumber',
        label: widgetText('registry.scaleSegments'),
        type: 'number',
        defaultValue: 10,
      },
    ],
  },
  {
    type: 'smart-filter-bar',
    label: widgetText('registry.filterBar'),
    icon: '🔎',
    category: widgetText('registry.interaction')['zh-CN'],
    categoryLabel: widgetText('registry.interaction'),
    description: widgetText('registry.dashboardFiltersSelectMultiselectAndDateRangeRefreshEveryReceivingWidget'),
    defaultConfig: {
      title: widgetText('registry.filters'),
      filterFields: [],
      linkage: { groupId: 'amos-filter', enabled: true },
    },
    defaultSize: {
      w: 12,
      h: 1,
    },
  },
  {
    type: 'smart-progress',
    label: widgetText('registry.progressBar'),
    icon: '📈',
    category: widgetText('registry.metrics')['zh-CN'],
    categoryLabel: widgetText('registry.metrics'),
    description: widgetText('registry.linearOrCircularProgressIndicator'),
    defaultConfig: {
      title: widgetText('registry.progress'),
      dataSource: {
        type: 'aggregate',
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 3,
      h: 3,
      minW: 2,
      minH: 2,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.target',
        label: widgetText('registry.targetValue'),
        type: 'number',
        defaultValue: 100,
      },
      {
        key: 'visualization.format',
        label: widgetText('registry.displayFormat'),
        type: 'select',
        options: [
          { label: widgetText('registry.percentage'), value: 'percent' },
          { label: widgetText('registry.fraction'), value: 'fraction' },
        ],
        defaultValue: 'percent',
      },
      {
        key: 'visualization.shape',
        label: widgetText('registry.shape'),
        type: 'select',
        options: [
          { label: widgetText('registry.bar'), value: 'bar' },
          { label: widgetText('registry.circle'), value: 'circle' },
        ],
        defaultValue: 'bar',
      },
    ],
  },
  {
    type: 'smart-heatmap-chart',
    label: widgetText('registry.heatmap'),
    icon: '🗺️',
    category: widgetText('registry.charts')['zh-CN'],
    categoryLabel: widgetText('registry.charts'),
    description: widgetText('registry.heatmapOrMatrixChart'),
    defaultConfig: {
      title: widgetText('registry.heatmap'),
      dataSource: {
        type: 'aggregate',
        dimensions: [],
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 6,
      h: 4,
      minW: 4,
      minH: 3,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.xField',
        label: widgetText('registry.xAxisField'),
        type: 'text',
        placeholder: widgetText('registry.leaveBlankToDetectAutomatically'),
      },
      {
        key: 'visualization.yField',
        label: widgetText('registry.yAxisField'),
        type: 'text',
        placeholder: widgetText('registry.leaveBlankToDetectAutomatically'),
      },
    ],
  },
  {
    type: 'smart-treemap-chart',
    label: widgetText('registry.treemap'),
    icon: '🌳',
    category: widgetText('registry.charts')['zh-CN'],
    categoryLabel: widgetText('registry.charts'),
    description: widgetText('registry.treemapShowingHierarchicalProportions'),
    defaultConfig: {
      title: widgetText('registry.treemap'),
      dataSource: {
        type: 'aggregate',
        dimensions: [],
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 6,
      h: 4,
      minW: 4,
      minH: 3,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.nameField',
        label: widgetText('registry.nameField'),
        type: 'text',
        placeholder: widgetText('registry.leaveBlankToDetectAutomatically'),
      },
      {
        key: 'visualization.valueField',
        label: widgetText('registry.valueField'),
        type: 'text',
        placeholder: widgetText('registry.leaveBlankToDetectAutomatically'),
      },
    ],
  },
  {
    type: 'smart-map-chart',
    label: widgetText('registry.map'),
    icon: '🌍',
    category: widgetText('registry.charts')['zh-CN'],
    categoryLabel: widgetText('registry.charts'),
    description: widgetText('registry.mapVisualizationRequiringGeographicData'),
    defaultConfig: {
      title: widgetText('registry.map'),
      dataSource: {
        type: 'aggregate',
        dimensions: [],
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 6,
      h: 4,
      minW: 4,
      minH: 3,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.mapRegion',
        label: widgetText('registry.mapRegion'),
        type: 'select',
        options: [
          { label: widgetText('registry.china'), value: 'china' },
          { label: widgetText('registry.world'), value: 'world' },
        ],
        defaultValue: 'china',
      },
    ],
  },
  {
    type: 'smart-leaderboard',
    label: widgetText('registry.ranking'),
    icon: '🏆',
    category: widgetText('registry.data')['zh-CN'],
    categoryLabel: widgetText('registry.data'),
    description: widgetText('registry.displayARankedList'),
    defaultConfig: {
      title: widgetText('registry.ranking'),
      dataSource: {
        type: 'aggregate',
        dimensions: [],
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 4,
      h: 4,
      minW: 3,
      minH: 3,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.maxItems',
        label: widgetText('registry.maximumItems'),
        type: 'number',
        defaultValue: 10,
      },
      {
        key: 'visualization.rankField',
        label: widgetText('registry.nameField'),
        type: 'text',
        placeholder: widgetText('registry.leaveBlankToDetectAutomatically'),
      },
      {
        key: 'visualization.valueField',
        label: widgetText('registry.valueField'),
        type: 'text',
        placeholder: widgetText('registry.leaveBlankToDetectAutomatically'),
      },
    ],
  },
  // ==================== Content Widgets ====================
  {
    type: 'smart-rich-text',
    label: widgetText('registry.richText'),
    icon: '📝',
    category: widgetText('registry.content')['zh-CN'],
    categoryLabel: widgetText('registry.content'),
    description: widgetText('registry.displayRichTextOrHTMLContent'),
    defaultConfig: {
      title: '',
      dataSource: { type: 'static' },
    },
    defaultSize: {
      w: 4,
      h: 3,
      minW: 2,
      minH: 2,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
      },
      {
        key: 'visualization.content',
        label: widgetText('registry.content'),
        type: 'text',
        placeholder: widgetText('registry.enterHTMLOrMarkdownContent'),
      },
      {
        key: 'visualization.format',
        label: widgetText('registry.format'),
        type: 'select',
        options: [
          { label: 'html', value: 'html' },
          { label: 'Markdown', value: 'markdown' },
        ],
        defaultValue: 'html',
      },
    ],
  },
  {
    type: 'smart-image',
    label: widgetText('registry.image'),
    icon: '🖼️',
    category: widgetText('registry.content')['zh-CN'],
    categoryLabel: widgetText('registry.content'),
    description: widgetText('registry.displayAnImage'),
    defaultConfig: {
      title: '',
      dataSource: { type: 'static' },
    },
    defaultSize: {
      w: 4,
      h: 3,
      minW: 2,
      minH: 2,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
      },
      {
        key: 'visualization.src',
        label: widgetText('registry.imageURL'),
        type: 'text',
        required: true,
        placeholder: 'https://example.com/image.png',
      },
      {
        key: 'visualization.alt',
        label: widgetText('registry.alternativeText'),
        type: 'text',
        placeholder: widgetText('registry.imageDescription'),
      },
      {
        key: 'visualization.objectFit',
        label: widgetText('registry.fitMode'),
        type: 'select',
        options: [
          { label: widgetText('registry.cover'), value: 'cover' },
          { label: widgetText('registry.contain'), value: 'contain' },
          { label: widgetText('registry.stretch'), value: 'fill' },
        ],
        defaultValue: 'cover',
      },
    ],
  },
  {
    type: 'smart-iframe',
    label: widgetText('registry.embeddedPage'),
    icon: '🌐',
    category: widgetText('registry.content')['zh-CN'],
    categoryLabel: widgetText('registry.content'),
    description: widgetText('registry.embedAnExternalPage'),
    defaultConfig: {
      title: '',
      dataSource: { type: 'static' },
    },
    defaultSize: {
      w: 6,
      h: 4,
      minW: 3,
      minH: 3,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
      },
      {
        key: 'visualization.src',
        label: widgetText('registry.pageURL'),
        type: 'text',
        required: true,
        placeholder: 'https://example.com',
      },
    ],
  },
  {
    type: 'smart-countdown',
    label: widgetText('registry.countdown'),
    icon: '⏰',
    category: widgetText('registry.content')['zh-CN'],
    categoryLabel: widgetText('registry.content'),
    description: widgetText('registry.countdownToATargetDate'),
    defaultConfig: {
      title: widgetText('registry.countdown'),
      dataSource: { type: 'static' },
    },
    defaultSize: {
      w: 4,
      h: 2,
      minW: 3,
      minH: 2,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
      },
      {
        key: 'visualization.targetDate',
        label: widgetText('registry.targetDate'),
        type: 'text',
        required: true,
        placeholder: '2026-12-31T00:00:00',
      },
      {
        key: 'visualization.format',
        label: widgetText('registry.displayFormat'),
        type: 'select',
        options: [
          { label: widgetText('registry.fullDaysHoursMinutesSeconds'), value: 'full' },
          { label: widgetText('registry.daysOnly'), value: 'days' },
        ],
        defaultValue: 'full',
      },
    ],
  },
  // ==================== New Chart Widgets ====================
  {
    type: 'smart-wordcloud-chart',
    label: widgetText('registry.wordCloud'),
    icon: '☁️',
    category: widgetText('registry.charts')['zh-CN'],
    categoryLabel: widgetText('registry.charts'),
    description: widgetText('registry.displayKeywordsSizedByFrequency'),
    defaultConfig: {
      title: widgetText('registry.wordCloud'),
      dataSource: {
        type: 'aggregate',
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 6,
      h: 4,
      minW: 4,
      minH: 3,
      maxW: 12,
      maxH: 8,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.shape',
        label: widgetText('registry.shape'),
        type: 'select',
        options: [
          { label: widgetText('registry.circle'), value: 'circle' },
          { label: widgetText('registry.rectangle'), value: 'rect' },
          { label: widgetText('registry.diamond'), value: 'diamond' },
          { label: widgetText('registry.triangle'), value: 'triangle' },
        ],
        defaultValue: 'circle',
      },
      {
        key: 'visualization.colorTheme',
        label: widgetText('registry.colorTheme'),
        type: 'select',
        options: [
          { label: widgetText('registry.random'), value: 'random' },
          { label: widgetText('registry.warm'), value: 'warm' },
          { label: widgetText('registry.cool'), value: 'cool' },
          { label: widgetText('registry.brand'), value: 'brand' },
        ],
        defaultValue: 'random',
      },
      {
        key: 'visualization.gridSize',
        label: widgetText('registry.wordSpacing'),
        type: 'number',
        defaultValue: 8,
      },
    ],
  },
  {
    type: 'smart-combo-chart',
    label: widgetText('registry.combinationChart'),
    icon: '📈',
    category: widgetText('registry.charts')['zh-CN'],
    categoryLabel: widgetText('registry.charts'),
    description: widgetText('registry.mixedSeriesChartWithBarsLinesAreasScatterAndDualYAxes'),
    defaultConfig: {
      title: widgetText('registry.combinationChart'),
      dataSource: {
        type: 'aggregate',
        metrics: [
          { field: 'id', aggregation: 'count' },
        ],
      },
    },
    defaultSize: {
      w: 8,
      h: 5,
      minW: 6,
      minH: 4,
      maxW: 12,
      maxH: 8,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.smooth',
        label: widgetText('registry.smoothCurve'),
        type: 'boolean',
        defaultValue: false,
      },
      {
        key: 'visualization.stack',
        label: widgetText('registry.stacking'),
        type: 'boolean',
        defaultValue: false,
      },
      {
        key: 'visualization.showDataZoom',
        label: widgetText('registry.dataZoom'),
        type: 'boolean',
        defaultValue: false,
      },
      {
        key: 'visualization.yAxisLeft.name',
        label: widgetText('registry.leftYAxisName'),
        type: 'text',
      },
      {
        key: 'visualization.yAxisLeft.formatter',
        label: widgetText('registry.leftYAxisFormat'),
        type: 'text',
        placeholder: widgetText('registry.value10000'),
      },
      {
        key: 'visualization.yAxisRight.name',
        label: widgetText('registry.rightYAxisName'),
        type: 'text',
      },
      {
        key: 'visualization.yAxisRight.formatter',
        label: widgetText('registry.rightYAxisFormat'),
        type: 'text',
        placeholder: '{value}%',
      },
    ],
  },
  {
    type: 'smart-nps-chart',
    label: widgetText('registry.npsChart'),
    icon: '🎯',
    category: widgetText('registry.charts')['zh-CN'],
    categoryLabel: widgetText('registry.charts'),
    description: widgetText('registry.netPromoterScoreGaugeGrouping010RatingsIntoPromotersPassivesAndDetractors'),
    defaultConfig: {
      title: 'NPS',
      dataSource: {
        type: 'aggregate',
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 4,
      h: 4,
      minW: 3,
      minH: 3,
      maxW: 8,
      maxH: 8,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.scoreField',
        label: widgetText('registry.ratingField'),
        type: 'text',
        placeholder: widgetText('registry.nameOfANumeric010RatingField'),
      },
      {
        key: 'visualization.showPercentage',
        label: widgetText('registry.showPercentage'),
        type: 'boolean',
        defaultValue: true,
      },
      {
        key: 'visualization.showLegend',
        label: widgetText('registry.showLegend'),
        type: 'boolean',
        defaultValue: true,
      },
      {
        key: 'visualization.ringWidth',
        label: widgetText('registry.ringWidth'),
        type: 'number',
        defaultValue: 30,
      },
    ],
  },
  {
    type: 'smart-gallery',
    label: widgetText('registry.gallery'),
    icon: '🖼️',
    category: widgetText('registry.content')['zh-CN'],
    categoryLabel: widgetText('registry.content'),
    description: widgetText('registry.gridGallerySupportingStaticImagesAndDynamicModelData'),
    defaultConfig: {
      title: widgetText('registry.gallery'),
      dataSource: { type: 'static' },
    },
    defaultSize: {
      w: 8,
      h: 5,
      minW: 4,
      minH: 3,
      maxW: 12,
      maxH: 10,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.columns',
        label: widgetText('registry.columns'),
        type: 'select',
        options: [
          { label: widgetText('registry.2Columns'), value: '2' },
          { label: widgetText('registry.3Columns'), value: '3' },
          { label: widgetText('registry.4Columns'), value: '4' },
        ],
        defaultValue: '3',
      },
      {
        key: 'visualization.imageField',
        label: widgetText('registry.imageField'),
        type: 'text',
        placeholder: widgetText('registry.imageURLFieldName'),
      },
      {
        key: 'visualization.titleField',
        label: widgetText('registry.titleField'),
        type: 'text',
        placeholder: widgetText('registry.titleFieldName'),
      },
      {
        key: 'visualization.descriptionField',
        label: widgetText('registry.summaryField'),
        type: 'text',
        placeholder: widgetText('registry.summaryFieldName'),
      },
      {
        key: 'visualization.imageHeight',
        label: widgetText('registry.imageHeight'),
        type: 'number',
        defaultValue: 160,
      },
      {
        key: 'visualization.imageFit',
        label: widgetText('registry.imageFit'),
        type: 'select',
        options: [
          { label: widgetText('registry.cropToFill'), value: 'cover' },
          { label: widgetText('registry.showEntireImage'), value: 'contain' },
          { label: widgetText('registry.stretch'), value: 'fill' },
        ],
        defaultValue: 'cover',
      },
      {
        key: 'visualization.showLightbox',
        label: widgetText('registry.lightboxPreview'),
        type: 'boolean',
        defaultValue: true,
      },
      {
        key: 'visualization.gap',
        label: widgetText('registry.cardGap'),
        type: 'number',
        defaultValue: 12,
      },
    ],
  },
  {
    type: 'smart-kanban',
    label: widgetText('registry.kanban'),
    icon: '📋',
    category: widgetText('registry.views')['zh-CN'],
    categoryLabel: widgetText('registry.views'),
    description: widgetText('registry.displayDataInDimensionBasedColumnsWithoutEditing'),
    defaultConfig: {
      title: widgetText('registry.kanban'),
      dataSource: {
        type: 'aggregate',
        metrics: [{ field: 'id', aggregation: 'count' }],
      },
    },
    defaultSize: {
      w: 12,
      h: 6,
      minW: 6,
      minH: 4,
      maxW: 12,
      maxH: 10,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.groupField',
        label: widgetText('registry.groupField'),
        type: 'text',
        required: true,
        placeholder: widgetText('registry.forExampleStatusStage'),
      },
      {
        key: 'visualization.titleField',
        label: widgetText('registry.cardTitleField'),
        type: 'text',
        placeholder: widgetText('registry.forExampleNameTitle'),
      },
      {
        key: 'visualization.descriptionField',
        label: widgetText('registry.cardDescriptionField'),
        type: 'text',
        placeholder: widgetText('registry.forExampleDescription'),
      },
      {
        key: 'visualization.maxCardsPerColumn',
        label: widgetText('registry.maximumCardsPerColumn'),
        type: 'number',
        defaultValue: 10,
      },
      {
        key: 'visualization.showCount',
        label: widgetText('registry.showCount'),
        type: 'boolean',
        defaultValue: true,
      },
      {
        key: 'visualization.cardClickUrl',
        label: widgetText('registry.cardLinkURL'),
        type: 'text',
        placeholder: '/model/{id}',
      },
    ],
  },
  // ==================== Workbench Widgets: Stats ====================
  {
    type: 'smart-stats-row',
    label: widgetText('registry.statisticsOverview'),
    icon: '📊',
    category: widgetText('registry.workbenchStatistics')['zh-CN'],
    categoryLabel: widgetText('registry.workbenchStatistics'),
    description: widgetText('registry.displayKeyBusinessMetricsInASingleRow'),
    defaultConfig: {
      title: widgetText('registry.statisticsOverview'),
      dataSource: { type: 'static' },
    },
    defaultSize: {
      w: 12,
      h: 2,
      minW: 6,
      minH: 2,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
    ],
  },
  {
    type: 'smart-stats-card',
    label: widgetText('registry.statisticsCard'),
    icon: '🔢',
    category: widgetText('registry.workbenchStatistics')['zh-CN'],
    categoryLabel: widgetText('registry.workbenchStatistics'),
    description: widgetText('registry.singleMetricStatisticsCardWithTrendDisplay'),
    defaultConfig: {
      title: widgetText('registry.statisticsCard'),
      dataSource: { type: 'static' },
    },
    defaultSize: {
      w: 3,
      h: 2,
      minW: 3,
      minH: 2,
      maxW: 6,
      maxH: 4,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      {
        key: 'visualization.statKey',
        label: widgetText('registry.metricKey'),
        type: 'text',
        placeholder: widgetText('registry.forExampleTotalLeadsOpenOpps'),
      },
    ],
  },
  // ==================== Workbench Widgets: Tasks ====================
  {
    type: 'smart-inbox',
    label: widgetText('registry.tasks'),
    icon: '📋',
    category: widgetText('registry.workbenchTasks')['zh-CN'],
    categoryLabel: widgetText('registry.workbenchTasks'),
    description: widgetText('registry.displayPendingApprovalsAndTasksForTheCurrentUser'),
    defaultConfig: {
      title: widgetText('registry.tasks'),
      dataSource: { type: 'static' },
    },
    defaultSize: {
      w: 6,
      h: 4,
      minW: 4,
      minH: 3,
      maxW: 12,
      maxH: 8,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      {
        key: 'visualization.maxItems',
        label: widgetText('registry.maximumRows'),
        type: 'number',
        defaultValue: 8,
      },
      {
        key: 'visualization.itemTypes',
        label: widgetText('registry.taskTypeFilter'),
        type: 'text',
        placeholder: widgetText('registry.forExampleApprovalTaskLeaveBlankToShowAll'),
      },
    ],
  },
  {
    type: 'smart-calendar',
    label: widgetText('registry.calendar'),
    icon: '📅',
    category: widgetText('registry.workbenchTasks')['zh-CN'],
    categoryLabel: widgetText('registry.workbenchTasks'),
    description: widgetText('registry.calendarShowingSchedulesAndTasks'),
    defaultConfig: {
      title: widgetText('registry.calendar'),
      dataSource: { type: 'static' },
    },
    defaultSize: {
      w: 6,
      h: 4,
      minW: 4,
      minH: 3,
      maxW: 12,
      maxH: 8,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
    ],
  },
  // ==================== Workbench Widgets: General ====================
  {
    type: 'smart-shortcuts',
    label: widgetText('registry.shortcuts'),
    icon: '⚡',
    category: widgetText('registry.workbenchGeneral')['zh-CN'],
    categoryLabel: widgetText('registry.workbenchGeneral'),
    description: widgetText('registry.quickAccessToFrequentlyUsedActions'),
    defaultConfig: {
      title: widgetText('registry.shortcuts'),
      dataSource: { type: 'static' },
    },
    defaultSize: {
      w: 6,
      h: 2,
      minW: 3,
      minH: 2,
      maxW: 12,
      maxH: 4,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      {
        key: 'visualization.columns',
        label: widgetText('registry.columnsPerRow'),
        type: 'number',
        defaultValue: 3,
      },
      {
        key: 'personalizable',
        label: widgetText('registry.allowPersonalization'),
        type: 'boolean',
        defaultValue: false,
      },
    ],
  },
  {
    type: 'smart-recent',
    label: widgetText('registry.recentItems'),
    icon: '🕐',
    category: widgetText('registry.workbenchGeneral')['zh-CN'],
    categoryLabel: widgetText('registry.workbenchGeneral'),
    description: widgetText('registry.displayRecentlyVisitedPagesAndRecords'),
    defaultConfig: {
      title: widgetText('registry.recentItems'),
      dataSource: { type: 'static' },
    },
    defaultSize: {
      w: 6,
      h: 3,
      minW: 3,
      minH: 2,
      maxW: 12,
      maxH: 6,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
      {
        key: 'visualization.maxItems',
        label: widgetText('registry.maximumRows'),
        type: 'number',
        defaultValue: 8,
      },
    ],
  },
  {
    type: 'smart-announcement',
    label: widgetText('registry.announcements'),
    icon: '📢',
    category: widgetText('registry.workbenchGeneral')['zh-CN'],
    categoryLabel: widgetText('registry.workbenchGeneral'),
    description: widgetText('registry.displayAnnouncementsAndNotifications'),
    defaultConfig: {
      title: widgetText('registry.announcements'),
      dataSource: { type: 'static' },
    },
    defaultSize: {
      w: 6,
      h: 3,
      minW: 3,
      minH: 2,
      maxW: 12,
      maxH: 6,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
    ],
  },
  {
    type: 'smart-quick-note',
    label: widgetText('registry.quickNote'),
    icon: '📝',
    category: widgetText('registry.workbenchGeneral')['zh-CN'],
    categoryLabel: widgetText('registry.workbenchGeneral'),
    description: widgetText('registry.quicklyCaptureNotesAndReminders'),
    defaultConfig: {
      title: widgetText('registry.quickNote'),
      dataSource: { type: 'static' },
    },
    defaultSize: {
      w: 4,
      h: 3,
      minW: 3,
      minH: 2,
      maxW: 8,
      maxH: 6,
    },
    configSchema: [
      {
        key: 'title',
        label: widgetText('registry.title'),
        type: 'localizedText',
        required: true,
      },
    ],
  },
];

/**
 * Widget registry extends shared DesignerRegistry.
 */
class WidgetRegistry extends DesignerRegistry<WidgetDefinition> {
  constructor() {
    super();
    // Register default widgets with auto-tagged tier via manifest
    widgetDefinitions.forEach((def) =>
      this.register({ ...def, tier: def.tier ?? resolveWidgetTier(def.type) })
    );
  }
}

export const widgetRegistry = new WidgetRegistry();
export { widgetDefinitions };
