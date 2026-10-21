/** The native Report DSL is the only path map for the report authoring surface. */
import type { ExtendedPropertySchema as Schema } from '~/shared/designer/SchemaBlockConfigPanel';
import type { ReportBlock } from '../types';
import type { useSmartText } from '~/utils/i18n';
import '../components/ReportDataSourceEditor';

type Text = ReturnType<typeof useSmartText>;
export function reportAuthoringSchemas(
  type: ReportBlock['blockType'],
  text: Text,
): Schema<string>[] {
  const label = (zh: string, en: string) => text({ zh, en });
  const field = (
    key: string,
    zh: string,
    en: string,
    type: Schema['type'] = 'text',
    extra: Partial<Schema<string>> = {},
  ): Schema<string> => ({ key, label: label(zh, en), type, ...extra });
  const select = (
    key: string,
    zh: string,
    en: string,
    entries: [string, string, string][],
    extra: Partial<Schema<string>> = {},
  ) =>
    field(key, zh, en, 'select', {
      options: entries.map(([value, zh, en]) => ({ value, label: label(zh, en) })),
      ...extra,
    });
  const aggregations: [string, string, string][] = [
    ['sum', '求和', 'Sum'],
    ['avg', '平均值', 'Average'],
    ['count', '计数', 'Count'],
    ['min', '最小值', 'Minimum'],
    ['max', '最大值', 'Maximum'],
  ];
  const alignments: [string, string, string][] = [
    ['left', '左对齐', 'Left'],
    ['center', '居中', 'Center'],
    ['right', '右对齐', 'Right'],
  ];
  const formats: [string, string, string][] = [
    ['', '默认', 'Default'],
    ['number', '数字', 'Number'],
    ['currency', '金额', 'Currency'],
    ['percent', '百分比', 'Percent'],
    ['date', '日期', 'Date'],
  ];
  const title = field('title', '标题', 'Title');
  const source = field('dataSource', '数据源', 'Data source', 'text', {
    editor: 'report.data-source',
  });
  const agg = select('aggregation', '聚合方式', 'Aggregation', aggregations, {
    defaultValue: 'sum',
  });
  const color = field('color', '颜色', 'Color', 'text', { editor: 'designer.color' });
  const columns = field('columns', '列配置', 'Columns', 'array', {
    itemLabel: (item, i) => String(item.label || item.field || i + 1),
    itemSchema: [
      field('field', '字段名称', 'Field', 'text', { required: true }),
      field('label', '显示名称', 'Display label'),
      field('width', '列宽', 'Width', 'number', { min: 1 }),
      select('align', '对齐方式', 'Alignment', alignments, { defaultValue: 'left' }),
      select('format', '格式', 'Format', formats),
    ],
  });
  const summary = (key: string, zh: string, en: string): Schema<string>[] => [
    field(`${key}.enabled`, zh, en, 'boolean', { defaultValue: false }),
    field(`${key}.label`, '汇总名称', 'Summary label', 'text', {
      defaultValue: label('合计', 'Total'),
      dependsOn: { field: `${key}.enabled`, value: true },
    }),
    field(`${key}.columns`, '汇总列', 'Summary columns', 'array', {
      defaultValue: [],
      dependsOn: { field: `${key}.enabled`, value: true },
      itemSchema: [
        field('field', '字段名称', 'Field', 'text', { required: true }),
        select('aggregation', '聚合方式', 'Aggregation', aggregations, { defaultValue: 'sum' }),
        select('format', '格式', 'Format', formats),
      ],
    }),
  ];
  const style = [
    field('showHeader', '显示表头', 'Show header', 'boolean', { defaultValue: true }),
    field('border', '单元格边框', 'Cell borders', 'boolean', { defaultValue: true }),
  ];
  const fields: Record<ReportBlock['blockType'], Schema<string>[]> = {
    table: [
      title,
      source,
      columns,
      ...summary('summary', '显示汇总行', 'Show summary'),
      ...style,
      field('stripe', '交替行底色', 'Striped rows', 'boolean', { defaultValue: true }),
    ],
    'grouped-table': [
      title,
      source,
      field('groupByField', '分组字段', 'Group by field', 'text', { required: true }),
      columns,
      ...summary('groupSubtotal', '分组小计', 'Group subtotal'),
      ...summary('grandTotal', '总计', 'Grand total'),
      ...style,
    ],
    'stat-card': [
      title,
      field('label', '显示名称', 'Label'),
      source,
      field('valueField', '数值字段', 'Value field', 'text', { required: true }),
      agg,
      select('format', '格式', 'Format', formats),
      color,
    ],
    'rich-text': [
      title,
      field('content', '内容', 'Content', 'textarea', {
        placeholder: label('输入文本内容…', 'Enter text content...'),
      }),
      select('align', '对齐方式', 'Alignment', alignments, { defaultValue: 'left' }),
      field('style.fontSize', '字号（磅）', 'Font size (pt)', 'number', {
        min: 6,
        max: 48,
        defaultValue: 10,
      }),
      select(
        'style.fontWeight',
        '字重',
        'Font weight',
        [
          ['normal', '常规', 'Normal'],
          ['bold', '粗体', 'Bold'],
        ],
        { defaultValue: 'normal' },
      ),
      field('style.color', '颜色', 'Color', 'text', {
        defaultValue: '#333333',
        editor: 'designer.color',
      }),
    ],
    'cross-tab': [
      title,
      source,
      field('rowLabel', '行标题', 'Row label', 'text'),
      field('rowField', '行维度', 'Row field', 'text', { required: true }),
      field('columnLabel', '列标题', 'Column label', 'text'),
      field('columnField', '列维度', 'Column field', 'text', { required: true }),
      field('valueField', '数值字段', 'Value field', 'text', { required: true }),
      agg,
      select('format', '格式', 'Format', formats),
      field('totalLabel', '合计标题', 'Total label', 'text'),
      field('showRowTotal', '显示行合计', 'Show row total', 'boolean', { defaultValue: true }),
      field('showColumnTotal', '显示列合计', 'Show column total', 'boolean', {
        defaultValue: true,
      }),
    ],
    chart: [
      title,
      select('chartType', '图表类型', 'Chart type', [
        ['bar', '柱状图', 'Bar'],
        ['horizontal-bar', '横向柱状图', 'Horizontal bar'],
        ['pie', '饼图', 'Pie'],
      ]),
      source,
      field('categoryField', '分类字段', 'Category field', 'text', { required: true }),
      field('valueField', '数值字段', 'Value field', 'text', { required: true }),
      agg,
      field('width', '宽度', 'Width', 'number', { min: 200, max: 800, defaultValue: 400 }),
      field('height', '高度', 'Height', 'number', { min: 120, max: 600, defaultValue: 240 }),
    ],
    barcode: [
      title,
      source,
      field('field', '数值字段', 'Value field'),
      field('staticValue', '固定值', 'Static value'),
      select(
        'format',
        '条码格式',
        'Barcode format',
        ['code128', 'code39', 'ean13', 'ean8', 'upc', 'itf14'].map((v) => [
          v,
          v.toUpperCase(),
          v.toUpperCase(),
        ]),
      ),
      field('width', '宽度', 'Width', 'number', { min: 1, defaultValue: 2 }),
      field('height', '高度', 'Height', 'number', { min: 10, defaultValue: 50 }),
      field('displayValue', '显示文本', 'Display value', 'boolean', { defaultValue: true }),
      field('fontSize', '字号', 'Font size', 'number', { min: 6, max: 48, defaultValue: 12 }),
    ],
    watermark: [
      field('text', '文本', 'Text'),
      field('rotation', '旋转角度', 'Rotation', 'number', { min: -90, max: 90, defaultValue: -30 }),
      field('opacity', '不透明度', 'Opacity', 'number', { min: 0.02, max: 0.5, defaultValue: 0.1 }),
      field('fontSize', '字号', 'Font size', 'number', { min: 8, max: 72, defaultValue: 16 }),
      color,
      field('repeat', '重复水印', 'Repeat pattern', 'boolean', { defaultValue: true }),
    ],
  };
  return fields[type];
}
