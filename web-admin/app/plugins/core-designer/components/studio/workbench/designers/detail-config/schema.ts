import type { ExtendedPropertySchema } from '../SchemaBlockConfigPanel';

export const sectionDetailSchemas: ExtendedPropertySchema<string>[] = [
  { key: 'title', label: '分组标题', type: 'text', required: true, group: '基础' },
  {
    key: 'columns', label: '栅格列数', type: 'select', group: '布局',
    options: [
      { label: '1 列', value: '1' },
      { label: '2 列', value: '2' },
      { label: '3 列', value: '3' },
      { label: '4 列', value: '4' },
    ],
  },
  { key: 'collapsible', label: '可折叠', type: 'boolean', group: '布局' },
  { key: 'defaultCollapsed', label: '默认折叠', type: 'boolean', group: '布局', dependsOn: { field: 'collapsible', value: true } },
];

export const detailCustomButtonSchemas: ExtendedPropertySchema<string>[] = [
  { key: 'label', label: '按钮文字', type: 'text', required: true, group: '基础' },
  { key: 'icon', label: '图标', type: 'text', group: '基础' },
  { key: 'command', label: 'Command', type: 'text', required: true, placeholder: 'plugin:action', group: '绑定' },
];
