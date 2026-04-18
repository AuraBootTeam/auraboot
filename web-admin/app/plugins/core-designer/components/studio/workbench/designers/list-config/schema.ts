/**
 * PropertySchema definitions for List config tabs.
 *
 * Each tab composes an ExtendedPropertySchema[] array that is handed to
 * `SchemaBlockConfigPanel` for schema-driven rendering. No hand-coded JSX
 * panels — Studio red-line requires all configuration editors to go through
 * PropertyFieldRenderer via SchemaBlockConfigPanel.
 */

import type { ExtendedPropertySchema } from '../SchemaBlockConfigPanel';

/** Per-column detail editor schema (applied when a column is selected). */
export const columnDetailSchemas: ExtendedPropertySchema<string>[] = [
  { key: 'width', label: '宽度 (px)', type: 'number', group: '尺寸', placeholder: 'auto' },
  {
    key: 'align',
    label: '对齐',
    type: 'select',
    group: '尺寸',
    options: [
      { label: '左对齐', value: 'left' },
      { label: '居中', value: 'center' },
      { label: '右对齐', value: 'right' },
    ],
  },
  {
    key: 'renderer',
    label: '渲染器',
    type: 'select',
    group: '显示',
    options: [
      { label: '文本', value: 'text' },
      { label: '富文本', value: 'richtext' },
      { label: '标签', value: 'badge' },
      { label: '链接', value: 'link' },
      { label: '图片', value: 'image' },
    ],
  },
  {
    key: 'format',
    label: '格式化模板',
    type: 'text',
    group: '显示',
    placeholder: 'e.g. YYYY-MM-DD / {0}件',
  },
];

/** Per-filter detail editor schema (applied when a filter is selected). */
export const filterDetailSchemas: ExtendedPropertySchema<string>[] = [
  {
    key: 'operator',
    label: '操作符',
    type: 'select',
    group: '条件',
    options: [
      { label: '等于', value: 'eq' },
      { label: '不等', value: 'neq' },
      { label: '包含', value: 'like' },
      { label: '介于', value: 'between' },
      { label: '大于', value: 'gt' },
      { label: '大于等于', value: 'gte' },
      { label: '小于', value: 'lt' },
      { label: '小于等于', value: 'lte' },
    ],
  },
  { key: 'defaultValue', label: '默认值', type: 'text', group: '条件' },
  {
    key: 'displayMode',
    label: '显示模式',
    type: 'select',
    group: '外观',
    options: [
      { label: '内联', value: 'inline' },
      { label: '抽屉', value: 'drawer' },
      { label: '顶部栏', value: 'top-bar' },
    ],
  },
];

/** Toolbar preset toggles (gated by capabilities at render time). */
export const toolbarPresetSchemas: ExtendedPropertySchema<string>[] = [
  { key: 'presetCreate', label: '新增', type: 'boolean', group: '预设按钮' },
  { key: 'presetExport', label: '导出', type: 'boolean', group: '预设按钮' },
  { key: 'presetBulkDelete', label: '批量删除', type: 'boolean', group: '预设按钮' },
];

/** Custom button schema (for the add-custom-button list editor). */
export const customButtonSchemas: ExtendedPropertySchema<string>[] = [
  { key: 'label', label: '按钮文字', type: 'text', required: true, group: '基础' },
  {
    key: 'icon',
    label: '图标',
    type: 'text',
    placeholder: 'emoji 或图标名',
    group: '基础',
  },
  {
    key: 'command',
    label: 'Command',
    type: 'text',
    required: true,
    placeholder: 'plugin:action',
    group: '绑定',
  },
  { key: 'requiresSelection', label: '需要选中行', type: 'boolean', group: '绑定' },
];

/**
 * Behavior tab schemas — option lists for sort fields are derived from
 * `capabilities.sortableFields` (whitelist enforced at render time).
 */
export function buildBehaviorSchemas(
  sortableFields: string[],
  _filterableFields: string[],
): ExtendedPropertySchema<string>[] {
  return [
    {
      key: 'defaultSortField',
      label: '默认排序字段',
      type: 'select',
      group: '排序',
      options: [
        { label: '(不设)', value: '' },
        ...sortableFields.map((f) => ({ label: f, value: f })),
      ],
    },
    {
      key: 'defaultSortOrder',
      label: '排序方向',
      type: 'select',
      group: '排序',
      dependsOn: { field: 'defaultSortField' },
      options: [
        { label: '降序', value: 'desc' },
        { label: '升序', value: 'asc' },
      ],
    },
    { key: 'pageSize', label: '每页条数', type: 'number', group: '分页', defaultValue: 20 },
    { key: 'multiSelect', label: '启用多选', type: 'boolean', group: '交互' },
    {
      key: 'rowClickAction',
      label: '行点击行为',
      type: 'select',
      group: '交互',
      options: [
        { label: '进入详情', value: 'detail' },
        { label: '打开抽屉', value: 'drawer' },
        { label: '不响应', value: 'none' },
      ],
    },
    {
      key: 'emptyStateText',
      label: '空态文案',
      type: 'text',
      group: '显示',
      placeholder: '暂无数据',
    },
  ];
}
