/**
 * Widget Registry
 * Central registry for dashboard widget definitions
 */

import type { WidgetDefinition, WidgetType, PropertySchema } from '../types';
import { DesignerRegistry } from '~/shared/designer';

/**
 * Common property schemas for data source configuration
 */
const dataSourcePropertySchemas: PropertySchema[] = [
  {
    key: 'dataSource.type',
    label: '数据源类型',
    type: 'select',
    required: true,
    options: [
      { label: '聚合查询', value: 'aggregate' },
      { label: '命名查询', value: 'namedQuery' },
    ],
    defaultValue: 'aggregate',
  },
  {
    key: 'dataSource.modelCode',
    label: '模型',
    type: 'model',
    dependsOn: { field: 'dataSource.type', value: 'aggregate' },
  },
  {
    key: 'dataSource.queryCode',
    label: '命名查询',
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
    label: '数字卡片',
    icon: 'NumberOutlined',
    category: '指标',
    description: '显示单个指标数值',
    defaultConfig: {
      title: '数字卡片',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.suffix',
        label: '后缀',
        type: 'text',
        placeholder: '如：件、人',
      },
      {
        key: 'visualization.showTrend',
        label: '显示趋势',
        type: 'boolean',
        defaultValue: false,
      },
    ],
  },
  {
    type: 'smart-bar-chart',
    label: '柱状图',
    icon: 'BarChartOutlined',
    category: '图表',
    description: '柱状图/条形图',
    defaultConfig: {
      title: '柱状图',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.horizontal',
        label: '水平显示',
        type: 'boolean',
        defaultValue: false,
      },
      {
        key: 'visualization.stacked',
        label: '堆叠显示',
        type: 'boolean',
        defaultValue: false,
      },
    ],
  },
  {
    type: 'smart-line-chart',
    label: '折线图',
    icon: 'LineChartOutlined',
    category: '图表',
    description: '折线图/趋势图',
    defaultConfig: {
      title: '折线图',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.smooth',
        label: '平滑曲线',
        type: 'boolean',
        defaultValue: true,
      },
      {
        key: 'visualization.showArea',
        label: '显示面积',
        type: 'boolean',
        defaultValue: false,
      },
    ],
  },
  {
    type: 'smart-pie-chart',
    label: '饼图',
    icon: 'PieChartOutlined',
    category: '图表',
    description: '饼图/环形图',
    defaultConfig: {
      title: '饼图',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.donut',
        label: '环形图',
        type: 'boolean',
        defaultValue: false,
      },
      {
        key: 'visualization.showLabels',
        label: '显示标签',
        type: 'boolean',
        defaultValue: true,
      },
    ],
  },
  {
    type: 'smart-area-chart',
    label: '面积图',
    icon: 'AreaChartOutlined',
    category: '图表',
    description: '面积图/堆叠面积图',
    defaultConfig: {
      title: '面积图',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.smooth',
        label: '平滑曲线',
        type: 'boolean',
        defaultValue: true,
      },
      {
        key: 'visualization.fillOpacity',
        label: '填充透明度',
        type: 'number',
        defaultValue: 0.6,
        placeholder: '0-1',
      },
    ],
  },
  {
    type: 'smart-funnel-chart',
    label: '漏斗图',
    icon: 'FunnelPlotOutlined',
    category: '图表',
    description: '漏斗图/转化图',
    defaultConfig: {
      title: '漏斗图',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.sort',
        label: '排序方式',
        type: 'select',
        options: [
          { label: '降序', value: 'descending' },
          { label: '升序', value: 'ascending' },
          { label: '不排序', value: 'none' },
        ],
        defaultValue: 'descending',
      },
    ],
  },
  {
    type: 'smart-scatter-chart',
    label: '散点图',
    icon: 'DotChartOutlined',
    category: '图表',
    description: '散点图/气泡图',
    defaultConfig: {
      title: '散点图',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.bubbleMode',
        label: '气泡模式',
        type: 'boolean',
        defaultValue: false,
      },
    ],
  },
  {
    type: 'smart-radar-chart',
    label: '雷达图',
    icon: 'RadarChartOutlined',
    category: '图表',
    description: '雷达图/多维对比',
    defaultConfig: {
      title: '雷达图',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.shape',
        label: '形状',
        type: 'select',
        options: [
          { label: '多边形', value: 'polygon' },
          { label: '圆形', value: 'circle' },
        ],
        defaultValue: 'polygon',
      },
      {
        key: 'visualization.showArea',
        label: '显示面积',
        type: 'boolean',
        defaultValue: true,
      },
    ],
  },
  {
    type: 'smart-table-chart',
    label: '数据表格',
    icon: 'TableOutlined',
    category: '数据',
    description: '表格数据展示',
    defaultConfig: {
      title: '数据表格',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.pageSize',
        label: '每页行数',
        type: 'number',
        defaultValue: 10,
      },
      {
        key: 'visualization.striped',
        label: '斑马纹',
        type: 'boolean',
        defaultValue: true,
      },
    ],
  },
  // ==================== New Data Analysis Widgets ====================
  {
    type: 'smart-gauge-chart',
    label: '仪表盘',
    icon: '🎯',
    category: '图表',
    description: '仪表盘/单指标展示',
    defaultConfig: {
      title: '仪表盘',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.min',
        label: '最小值',
        type: 'number',
        defaultValue: 0,
      },
      {
        key: 'visualization.max',
        label: '最大值',
        type: 'number',
        defaultValue: 100,
      },
      {
        key: 'visualization.splitNumber',
        label: '刻度分段数',
        type: 'number',
        defaultValue: 10,
      },
    ],
  },
  {
    type: 'smart-progress',
    label: '进度条',
    icon: '📈',
    category: '指标',
    description: '进度条/环形进度',
    defaultConfig: {
      title: '进度',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.target',
        label: '目标值',
        type: 'number',
        defaultValue: 100,
      },
      {
        key: 'visualization.format',
        label: '显示格式',
        type: 'select',
        options: [
          { label: '百分比', value: 'percent' },
          { label: '分数', value: 'fraction' },
        ],
        defaultValue: 'percent',
      },
      {
        key: 'visualization.shape',
        label: '形状',
        type: 'select',
        options: [
          { label: '条形', value: 'bar' },
          { label: '圆形', value: 'circle' },
        ],
        defaultValue: 'bar',
      },
    ],
  },
  {
    type: 'smart-heatmap-chart',
    label: '热力图',
    icon: '🗺️',
    category: '图表',
    description: '热力图/矩阵图',
    defaultConfig: {
      title: '热力图',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.xField',
        label: 'X轴字段',
        type: 'text',
        placeholder: '留空自动检测',
      },
      {
        key: 'visualization.yField',
        label: 'Y轴字段',
        type: 'text',
        placeholder: '留空自动检测',
      },
    ],
  },
  {
    type: 'smart-treemap-chart',
    label: '矩形树图',
    icon: '🌳',
    category: '图表',
    description: '矩形树图/层级占比',
    defaultConfig: {
      title: '矩形树图',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.nameField',
        label: '名称字段',
        type: 'text',
        placeholder: '留空自动检测',
      },
      {
        key: 'visualization.valueField',
        label: '值字段',
        type: 'text',
        placeholder: '留空自动检测',
      },
    ],
  },
  {
    type: 'smart-map-chart',
    label: '地图',
    icon: '🌍',
    category: '图表',
    description: '地图可视化（需要地理数据）',
    defaultConfig: {
      title: '地图',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.mapRegion',
        label: '地图区域',
        type: 'select',
        options: [
          { label: '中国', value: 'china' },
          { label: '世界', value: 'world' },
        ],
        defaultValue: 'china',
      },
    ],
  },
  {
    type: 'smart-leaderboard',
    label: '排行榜',
    icon: '🏆',
    category: '数据',
    description: '排名列表展示',
    defaultConfig: {
      title: '排行榜',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.maxItems',
        label: '最大条目数',
        type: 'number',
        defaultValue: 10,
      },
      {
        key: 'visualization.rankField',
        label: '名称字段',
        type: 'text',
        placeholder: '留空自动检测',
      },
      {
        key: 'visualization.valueField',
        label: '值字段',
        type: 'text',
        placeholder: '留空自动检测',
      },
    ],
  },
  // ==================== Content Widgets ====================
  {
    type: 'smart-rich-text',
    label: '富文本',
    icon: '📝',
    category: '内容',
    description: '富文本/HTML内容展示',
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
        label: '标题',
        type: 'text',
      },
      {
        key: 'visualization.content',
        label: '内容',
        type: 'text',
        placeholder: '输入HTML或Markdown内容',
      },
      {
        key: 'visualization.format',
        label: '格式',
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
    label: '图片',
    icon: '🖼️',
    category: '内容',
    description: '图片展示',
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
        label: '标题',
        type: 'text',
      },
      {
        key: 'visualization.src',
        label: '图片地址',
        type: 'text',
        required: true,
        placeholder: 'https://example.com/image.png',
      },
      {
        key: 'visualization.alt',
        label: '替代文本',
        type: 'text',
        placeholder: '图片描述',
      },
      {
        key: 'visualization.objectFit',
        label: '填充模式',
        type: 'select',
        options: [
          { label: '覆盖', value: 'cover' },
          { label: '包含', value: 'contain' },
          { label: '拉伸', value: 'fill' },
        ],
        defaultValue: 'cover',
      },
    ],
  },
  {
    type: 'smart-iframe',
    label: '内嵌页面',
    icon: '🌐',
    category: '内容',
    description: '嵌入外部页面',
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
        label: '标题',
        type: 'text',
      },
      {
        key: 'visualization.src',
        label: '页面地址',
        type: 'text',
        required: true,
        placeholder: 'https://example.com',
      },
    ],
  },
  {
    type: 'smart-countdown',
    label: '倒计时',
    icon: '⏰',
    category: '内容',
    description: '目标日期倒计时',
    defaultConfig: {
      title: '倒计时',
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
        label: '标题',
        type: 'text',
      },
      {
        key: 'visualization.targetDate',
        label: '目标日期',
        type: 'text',
        required: true,
        placeholder: '2026-12-31T00:00:00',
      },
      {
        key: 'visualization.format',
        label: '显示格式',
        type: 'select',
        options: [
          { label: '完整 (天时分秒)', value: 'full' },
          { label: '仅天数', value: 'days' },
        ],
        defaultValue: 'full',
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
    // Register default widgets
    widgetDefinitions.forEach((def) => this.register(def));
  }
}

export const widgetRegistry = new WidgetRegistry();
export { widgetDefinitions };
