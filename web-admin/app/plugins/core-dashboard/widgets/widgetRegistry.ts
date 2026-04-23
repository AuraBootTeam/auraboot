/**
 * Widget Registry
 * Central registry for dashboard widget definitions
 */

import type { WidgetDefinition, PropertySchema } from '../types';
import { DesignerRegistry } from '~/shared/designer';
import { resolveWidgetTier } from '../registry/widgetManifest';

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
      {
        key: 'icon',
        label: '图标',
        type: 'icon',
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
  // ==================== New Chart Widgets ====================
  {
    type: 'smart-wordcloud-chart',
    label: '词云',
    icon: '☁️',
    category: '图表',
    description: '按词频大小展示关键词分布',
    defaultConfig: {
      title: '词云',
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
          { label: '圆形', value: 'circle' },
          { label: '矩形', value: 'rect' },
          { label: '菱形', value: 'diamond' },
          { label: '三角形', value: 'triangle' },
        ],
        defaultValue: 'circle',
      },
      {
        key: 'visualization.colorTheme',
        label: '颜色主题',
        type: 'select',
        options: [
          { label: '随机', value: 'random' },
          { label: '暖色', value: 'warm' },
          { label: '冷色', value: 'cool' },
          { label: '品牌色', value: 'brand' },
        ],
        defaultValue: 'random',
      },
      {
        key: 'visualization.gridSize',
        label: '词间距',
        type: 'number',
        defaultValue: 8,
      },
    ],
  },
  {
    type: 'smart-combo-chart',
    label: '组合图',
    icon: '📈',
    category: '图表',
    description: '多系列混合图表，支持柱状+折线+面积+散点+双Y轴',
    defaultConfig: {
      title: '组合图',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.smooth',
        label: '平滑曲线',
        type: 'boolean',
        defaultValue: false,
      },
      {
        key: 'visualization.stack',
        label: '堆叠',
        type: 'boolean',
        defaultValue: false,
      },
      {
        key: 'visualization.showDataZoom',
        label: '数据缩放',
        type: 'boolean',
        defaultValue: false,
      },
      {
        key: 'visualization.yAxisLeft.name',
        label: '左Y轴名称',
        type: 'text',
      },
      {
        key: 'visualization.yAxisLeft.formatter',
        label: '左Y轴格式',
        type: 'text',
        placeholder: '{value}万',
      },
      {
        key: 'visualization.yAxisRight.name',
        label: '右Y轴名称',
        type: 'text',
      },
      {
        key: 'visualization.yAxisRight.formatter',
        label: '右Y轴格式',
        type: 'text',
        placeholder: '{value}%',
      },
    ],
  },
  {
    type: 'smart-nps-chart',
    label: 'NPS 图',
    icon: '🎯',
    category: '图表',
    description: '净推荐值仪表盘，自动将0-10评分分为推荐者/中立者/贬损者',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.scoreField',
        label: '评分字段',
        type: 'text',
        placeholder: '0-10 数值字段名',
      },
      {
        key: 'visualization.showPercentage',
        label: '显示百分比',
        type: 'boolean',
        defaultValue: true,
      },
      {
        key: 'visualization.showLegend',
        label: '显示图例',
        type: 'boolean',
        defaultValue: true,
      },
      {
        key: 'visualization.ringWidth',
        label: '环形宽度',
        type: 'number',
        defaultValue: 30,
      },
    ],
  },
  {
    type: 'smart-gallery',
    label: '画册',
    icon: '🖼️',
    category: '内容',
    description: '网格卡片画册，支持静态图片和模型数据动态渲染',
    defaultConfig: {
      title: '画册',
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
        label: '标题',
        type: 'text',
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.columns',
        label: '列数',
        type: 'select',
        options: [
          { label: '2 列', value: '2' },
          { label: '3 列', value: '3' },
          { label: '4 列', value: '4' },
        ],
        defaultValue: '3',
      },
      {
        key: 'visualization.imageField',
        label: '图片字段',
        type: 'text',
        placeholder: '图片URL字段名',
      },
      {
        key: 'visualization.titleField',
        label: '标题字段',
        type: 'text',
        placeholder: '标题字段名',
      },
      {
        key: 'visualization.descriptionField',
        label: '摘要字段',
        type: 'text',
        placeholder: '摘要字段名',
      },
      {
        key: 'visualization.imageHeight',
        label: '图片高度',
        type: 'number',
        defaultValue: 160,
      },
      {
        key: 'visualization.imageFit',
        label: '图片填充',
        type: 'select',
        options: [
          { label: '裁剪填满', value: 'cover' },
          { label: '完整显示', value: 'contain' },
          { label: '拉伸', value: 'fill' },
        ],
        defaultValue: 'cover',
      },
      {
        key: 'visualization.showLightbox',
        label: '灯箱预览',
        type: 'boolean',
        defaultValue: true,
      },
      {
        key: 'visualization.gap',
        label: '卡片间距',
        type: 'number',
        defaultValue: 12,
      },
    ],
  },
  {
    type: 'smart-kanban',
    label: '看板',
    icon: '📋',
    category: '视图',
    description: '按维度字段分列展示数据，纯展示模式',
    defaultConfig: {
      title: '看板',
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
        label: '标题',
        type: 'text',
      },
      ...dataSourcePropertySchemas,
      {
        key: 'visualization.groupField',
        label: '分组字段',
        type: 'text',
        required: true,
        placeholder: '如 status、stage',
      },
      {
        key: 'visualization.titleField',
        label: '卡片标题字段',
        type: 'text',
        placeholder: '如 name、title',
      },
      {
        key: 'visualization.descriptionField',
        label: '卡片描述字段',
        type: 'text',
        placeholder: '如 description',
      },
      {
        key: 'visualization.maxCardsPerColumn',
        label: '每列最大卡片数',
        type: 'number',
        defaultValue: 10,
      },
      {
        key: 'visualization.showCount',
        label: '显示数量',
        type: 'boolean',
        defaultValue: true,
      },
      {
        key: 'visualization.cardClickUrl',
        label: '卡片跳转URL',
        type: 'text',
        placeholder: '/model/{id}',
      },
    ],
  },
  // ==================== Workbench Widgets: Stats ====================
  {
    type: 'smart-stats-row',
    label: '统计概览',
    icon: '📊',
    category: '工作台 · 统计',
    description: '多指标统计行，一行展示关键业务数字',
    defaultConfig: {
      title: '统计概览',
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
        label: '标题',
        type: 'text',
        required: true,
      },
    ],
  },
  {
    type: 'smart-stats-card',
    label: '统计卡片',
    icon: '🔢',
    category: '工作台 · 统计',
    description: '单个指标统计卡片，支持趋势展示',
    defaultConfig: {
      title: '统计卡片',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      {
        key: 'visualization.statKey',
        label: '指标键',
        type: 'text',
        placeholder: '如：totalLeads, openOpps',
      },
    ],
  },
  // ==================== Workbench Widgets: Tasks ====================
  {
    type: 'smart-inbox',
    label: '待办事项',
    icon: '📋',
    category: '工作台 · 任务',
    description: '显示当前用户的待办审批和任务',
    defaultConfig: {
      title: '待办事项',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      {
        key: 'visualization.maxItems',
        label: '最大显示条数',
        type: 'number',
        defaultValue: 8,
      },
      {
        key: 'visualization.itemTypes',
        label: '事项类型筛选',
        type: 'text',
        placeholder: '如：approval,task（留空显示全部）',
      },
    ],
  },
  {
    type: 'smart-calendar',
    label: '日历',
    icon: '📅',
    category: '工作台 · 任务',
    description: '日历视图，展示日程和任务',
    defaultConfig: {
      title: '日历',
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
        label: '标题',
        type: 'text',
        required: true,
      },
    ],
  },
  // ==================== Workbench Widgets: CRM ====================
  {
    type: 'smart-pipeline',
    label: '销售管道',
    icon: '🔄',
    category: '工作台 · CRM',
    description: '销售管道概览，按阶段展示商机分布',
    defaultConfig: {
      title: '销售管道',
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
        label: '标题',
        type: 'text',
        required: true,
      },
    ],
  },
  {
    type: 'smart-leads',
    label: '线索看板',
    icon: '🎯',
    category: '工作台 · CRM',
    description: '线索管理看板，展示线索状态分布',
    defaultConfig: {
      title: '线索看板',
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
        label: '标题',
        type: 'text',
        required: true,
      },
    ],
  },
  {
    type: 'smart-activities',
    label: '活动记录',
    icon: '📝',
    category: '工作台 · CRM',
    description: '最近的客户活动和跟进记录',
    defaultConfig: {
      title: '活动记录',
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
        label: '标题',
        type: 'text',
        required: true,
      },
    ],
  },
  // ==================== Workbench Widgets: BPM ====================
  {
    type: 'smart-my-process',
    label: '我的流程',
    icon: '🚀',
    category: '工作台 · BPM',
    description: '我发起和参与的流程实例',
    defaultConfig: {
      title: '我的流程',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      {
        key: 'visualization.maxItems',
        label: '最大显示条数',
        type: 'number',
        defaultValue: 5,
      },
    ],
  },
  {
    type: 'smart-process-stats',
    label: '流程统计',
    icon: '📊',
    category: '工作台 · BPM',
    description: '流程运行统计，展示各状态数量',
    defaultConfig: {
      title: '流程统计',
      dataSource: { type: 'static' },
    },
    defaultSize: {
      w: 6,
      h: 3,
      minW: 4,
      minH: 2,
      maxW: 12,
      maxH: 6,
    },
    configSchema: [
      {
        key: 'title',
        label: '标题',
        type: 'text',
        required: true,
      },
    ],
  },
  // ==================== Workbench Widgets: General ====================
  {
    type: 'smart-shortcuts',
    label: '快捷入口',
    icon: '⚡',
    category: '工作台 · 通用',
    description: '常用功能的快捷操作入口',
    defaultConfig: {
      title: '快捷入口',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      {
        key: 'visualization.columns',
        label: '每行列数',
        type: 'number',
        defaultValue: 3,
      },
    ],
  },
  {
    type: 'smart-recent',
    label: '最近访问',
    icon: '🕐',
    category: '工作台 · 通用',
    description: '显示最近访问的页面和记录',
    defaultConfig: {
      title: '最近访问',
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
        label: '标题',
        type: 'text',
        required: true,
      },
      {
        key: 'visualization.maxItems',
        label: '最大显示条数',
        type: 'number',
        defaultValue: 8,
      },
    ],
  },
  {
    type: 'smart-announcement',
    label: '公告',
    icon: '📢',
    category: '工作台 · 通用',
    description: '公告和通知展示',
    defaultConfig: {
      title: '公告',
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
        label: '标题',
        type: 'text',
        required: true,
      },
    ],
  },
  {
    type: 'smart-quick-note',
    label: '快捷便签',
    icon: '📝',
    category: '工作台 · 通用',
    description: '快速记录笔记和备忘',
    defaultConfig: {
      title: '快捷便签',
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
        label: '标题',
        type: 'text',
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
