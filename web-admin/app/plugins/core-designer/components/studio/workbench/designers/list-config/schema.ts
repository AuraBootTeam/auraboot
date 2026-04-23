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
  {
    key: 'width',
    label: '宽度 (px)',
    type: 'number',
    group: '尺寸',
    placeholder: 'auto',
    description: '只为关键列设置固定宽度，其余列尽量保持自适应。',
  },
  {
    key: 'align',
    label: '对齐',
    type: 'select',
    group: '尺寸',
    description: '数字通常右对齐，文本或标签通常左对齐。',
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
    description: '优先用最朴素的表现形式，只有语义明确时才升级为标签或链接。',
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
    description: '仅用于提升可读性，不要把复杂业务计算塞进显示模板。',
  },
];

/** Per-filter detail editor schema (applied when a filter is selected). */
export const filterDetailSchemas: ExtendedPropertySchema<string>[] = [
  {
    key: 'operator',
    label: '操作符',
    type: 'select',
    group: '条件',
    description: '让用户一眼就能理解筛选逻辑，避免默认使用过宽泛的条件。',
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
  {
    key: 'defaultValue',
    label: '默认值',
    type: 'text',
    group: '条件',
    description: '只给真正高频的筛选项设置默认值，避免用户一进来就被过度限制。',
  },
  {
    key: 'displayMode',
    label: '显示模式',
    type: 'select',
    group: '外观',
    description: '高频项适合内联，次要筛选更适合收进抽屉或顶部栏。',
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
  {
    key: 'label',
    label: '按钮文字',
    type: 'text',
    required: true,
    group: '基础',
    description: '尽量使用动词短语，例如“批量指派”“导出报表”。',
  },
  {
    key: 'icon',
    label: '图标',
    type: 'icon',
    group: '基础',
    description: '图标只做辅助识别，不要替代按钮文案。',
  },
  {
    key: 'command',
    label: 'Command',
    type: 'text',
    required: true,
    placeholder: 'plugin:action',
    group: '绑定',
    description: '绑定清晰的 command，避免一个按钮承担多个隐式行为。',
  },
  {
    key: 'requiresSelection',
    label: '需要选中行',
    type: 'boolean',
    group: '绑定',
    description: '批量类动作应开启，页面级动作通常不需要。',
  },
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
      description: '决定用户进入列表时第一眼看到的优先级顺序。',
      options: [
        { label: '(不设)', value: '__none__' },
        ...sortableFields.map((f) => ({ label: f, value: f })),
      ],
    },
    {
      key: 'defaultSortOrder',
      label: '排序方向',
      type: 'select',
      group: '排序',
      description: '新记录优先出现通常用降序，基线数据浏览更适合升序。',
      // Show only when a real sort field is selected (not the '__none__' sentinel).
      // anyOf lists actual field values — '__none__' is excluded, so this row
      // hides automatically when no sort field is chosen.
      dependsOn: { field: 'defaultSortField', anyOf: sortableFields },
      options: [
        { label: '降序', value: 'desc' },
        { label: '升序', value: 'asc' },
      ],
    },
    {
      key: 'pageSize',
      label: '每页条数',
      type: 'number',
      group: '分页',
      defaultValue: 20,
      description: '更大页容量提升效率，但会牺牲首屏扫描压力。',
    },
    {
      key: 'multiSelect',
      label: '启用多选',
      type: 'boolean',
      group: '交互',
      description: '仅在存在明确批量动作时开启，避免多余的选择态。',
    },
    {
      key: 'rowClickAction',
      label: '行点击行为',
      type: 'select',
      group: '交互',
      description: '应与详情页策略一致，避免同一模块出现两套打开方式。',
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
      description: '空态要说明下一步动作，而不是只提示“没有数据”。',
    },
  ];
}
