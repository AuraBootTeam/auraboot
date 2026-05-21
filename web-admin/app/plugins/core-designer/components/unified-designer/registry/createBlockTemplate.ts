import type { DslBlockV3, ModelFieldDefinition } from '../types';
import { createUniqueBlockId, toStableBlockId } from '../utils/blockIds';

type BlockTemplateFactory = (id: string) => DslBlockV3;
export type ModelFieldTargetBlockType = 'field' | 'column' | 'filter-field';

const BLOCK_TEMPLATES: Record<string, { label: string; create: BlockTemplateFactory }> = {
  form: {
    label: 'New form',
    create: (id) => ({
      id,
      blockType: 'form',
      title: { en: 'New form', 'zh-CN': '新表单' },
      layout: { span: 12 },
      blocks: [],
    }),
  },
  'form-section': {
    label: 'New section',
    create: (id) => ({
      id,
      blockType: 'form-section',
      title: { en: 'New section', 'zh-CN': '新分组' },
      layout: { span: 12 },
      blocks: [],
    }),
  },
  field: {
    label: 'New field',
    create: (id) => ({
      id,
      blockType: 'field',
      field: 'new_field',
      layout: { span: 6 },
      props: { label: 'New field', component: 'input' },
    }),
  },
  list: {
    label: 'New list',
    create: (id) => ({
      id,
      blockType: 'list',
      title: { en: 'New list', 'zh-CN': '新列表' },
      layout: { span: 12 },
      blocks: [],
    }),
  },
  'filter-bar': {
    label: 'New filter bar',
    create: (id) => ({
      id,
      blockType: 'filter-bar',
      region: 'filters',
      layout: { span: 12 },
      blocks: [],
    }),
  },
  'filter-field': {
    label: 'New filter field',
    create: (id) => ({
      id,
      blockType: 'filter-field',
      field: 'new_filter',
      props: { label: 'New filter' },
    }),
  },
  table: {
    label: 'New table',
    create: (id) => ({
      id,
      blockType: 'table',
      layout: { span: 12 },
      blocks: [],
    }),
  },
  column: {
    label: 'New column',
    create: (id) => ({
      id,
      blockType: 'column',
      field: 'new_column',
      layout: { width: 160 },
      props: { label: 'New column' },
    }),
  },
  'sub-table': {
    label: 'New sub table',
    create: (id) => ({
      id,
      blockType: 'sub-table',
      title: { en: 'New sub table', 'zh-CN': '新子表' },
      layout: { span: 12 },
      dataSource: {},
      props: { rows: [] },
      blocks: [],
    }),
  },
  repeater: {
    label: 'New repeater',
    create: (id) => ({
      id,
      blockType: 'repeater',
      title: { en: 'New repeater', 'zh-CN': '新重复项' },
      layout: { span: 12 },
      props: { rows: [{}] },
      blocks: [],
    }),
  },
  subform: {
    label: 'New subform',
    create: (id) => ({
      id,
      blockType: 'subform',
      title: { en: 'New subform', 'zh-CN': '新子表单' },
      layout: { span: 12 },
      props: { rows: [{}] },
      blocks: [],
    }),
  },
  'action-bar': {
    label: 'New action bar',
    create: (id) => ({
      id,
      blockType: 'action-bar',
      region: 'toolbar',
      layout: { span: 12 },
      blocks: [],
    }),
  },
  action: {
    label: 'New action',
    create: (id) => ({
      id,
      blockType: 'action',
      actionType: 'command',
      props: { label: 'New action' },
    }),
  },
  detail: {
    label: 'New detail',
    create: (id) => ({
      id,
      blockType: 'detail',
      title: { en: 'New detail', 'zh-CN': '新详情' },
      layout: { span: 12 },
      blocks: [],
    }),
  },
  'detail-section': {
    label: 'New detail section',
    create: (id) => ({
      id,
      blockType: 'detail-section',
      title: { en: 'New detail section', 'zh-CN': '新详情分组' },
      layout: { span: 12 },
      blocks: [],
    }),
  },
  dashboard: {
    label: 'New dashboard',
    create: (id) => ({
      id,
      blockType: 'dashboard',
      title: { en: 'New dashboard', 'zh-CN': '新仪表盘' },
      layout: { span: 12, type: 'dashboard-grid', cols: 12, rowHeight: 80, gap: 16 },
      blocks: [],
    }),
  },
  widget: {
    label: 'New widget',
    create: (id) => ({
      id,
      blockType: 'widget',
      widgetType: 'number-card',
      layout: { x: 0, y: 0, w: 3, h: 2, span: 3 },
      props: { title: 'New widget' },
    }),
  },
  tabs: {
    label: 'New tabs',
    create: (id) => ({
      id,
      blockType: 'tabs',
      title: { en: 'New tabs', 'zh-CN': '新标签页' },
      layout: { span: 12 },
      blocks: [],
    }),
  },
  tab: {
    label: 'New tab',
    create: (id) => ({
      id,
      blockType: 'tab',
      title: { en: 'New tab', 'zh-CN': '新标签' },
      layout: { span: 12 },
      blocks: [],
    }),
  },
  'ai-fill-banner': {
    label: 'New AI fill banner',
    create: (id) => ({
      id,
      blockType: 'ai-fill-banner',
      title: { en: 'New AI fill banner', 'zh-CN': '新 AI 填充入口' },
      layout: { span: 12 },
      props: {
        description: 'Assist users with generated form values.',
      },
    }),
  },
  'bpm-panel': {
    label: 'New BPM panel',
    create: (id) => ({
      id,
      blockType: 'bpm-panel',
      title: { en: 'New BPM panel', 'zh-CN': '新流程面板' },
      layout: { span: 12 },
      props: {
        description: 'Workflow status and task actions.',
      },
    }),
  },
  'activity-timeline': {
    label: 'New activity timeline',
    create: (id) => ({
      id,
      blockType: 'activity-timeline',
      title: { en: 'New activity timeline', 'zh-CN': '新活动时间线' },
      layout: { span: 12 },
      props: {
        description: 'Recent activities for the current record.',
      },
    }),
  },
  'field-history': {
    label: 'New field history',
    create: (id) => ({
      id,
      blockType: 'field-history',
      title: { en: 'New field history', 'zh-CN': '新字段历史' },
      layout: { span: 12 },
      props: {
        description: 'Field change history for the current record.',
      },
    }),
  },
};

export function createBlockTemplate(
  blockType: string,
  existingIds: Set<string>,
): DslBlockV3 | null {
  const template = BLOCK_TEMPLATES[blockType];
  if (!template) return null;

  const baseId = toStableBlockId(blockType, template.label);
  return template.create(createUniqueBlockId(baseId, existingIds));
}

export function createFieldBlockFromModelField(
  field: ModelFieldDefinition,
  existingIds: Set<string>,
): DslBlockV3 {
  return createModelFieldBlock(field, 'field', existingIds);
}

export function createModelFieldBlock(
  field: ModelFieldDefinition,
  targetBlockType: ModelFieldTargetBlockType,
  existingIds: Set<string>,
): DslBlockV3 {
  if (targetBlockType === 'column') {
    return createColumnBlockFromModelField(field, existingIds);
  }
  if (targetBlockType === 'filter-field') {
    return createFilterFieldBlockFromModelField(field, existingIds);
  }
  return createFieldInputBlockFromModelField(field, existingIds);
}

function createFieldInputBlockFromModelField(
  field: ModelFieldDefinition,
  existingIds: Set<string>,
): DslBlockV3 {
  const baseId = toStableBlockId('field', field.code);
  const pickerProps = relationPickerPropsForModelField(field);
  return {
    id: createUniqueBlockId(baseId, existingIds),
    blockType: 'field',
    field: field.code,
    layout: { span: 6 },
    props: {
      label: localizedToString(field.label),
      component: pickerProps ? 'picker' : field.component ?? componentForFieldType(field.type),
      dataType: field.type,
      dictCode: field.dictCode,
      required: Boolean(field.required),
      ...pickerProps,
    },
  };
}

function createColumnBlockFromModelField(
  field: ModelFieldDefinition,
  existingIds: Set<string>,
): DslBlockV3 {
  const baseId = toStableBlockId('column', field.code);
  return {
    id: createUniqueBlockId(baseId, existingIds),
    blockType: 'column',
    field: field.code,
    layout: { width: 160 },
    props: {
      label: localizedToString(field.label),
      dataType: field.type,
      dictCode: field.dictCode,
    },
  };
}

function createFilterFieldBlockFromModelField(
  field: ModelFieldDefinition,
  existingIds: Set<string>,
): DslBlockV3 {
  const baseId = toStableBlockId('filter', field.code);
  const pickerProps = relationPickerPropsForModelField(field);
  return {
    id: createUniqueBlockId(baseId, existingIds),
    blockType: 'filter-field',
    field: field.code,
    props: {
      label: localizedToString(field.label),
      component: pickerProps ? 'picker' : field.component ?? componentForFieldType(field.type),
      dataType: field.type,
      dictCode: field.dictCode,
      operator: defaultFilterOperatorForFieldType(field.type),
      ...pickerProps,
    },
  };
}

function localizedToString(value: ModelFieldDefinition['label']): string {
  if (typeof value === 'string') return value;
  return value.en || value['zh-CN'] || Object.values(value)[0] || '';
}

function componentForFieldType(type: ModelFieldDefinition['type']): string {
  const normalizedType = normalizeFieldType(type);
  if (['date', 'datetime', 'timestamp', 'time'].includes(normalizedType)) return 'date';
  if (['text', 'longtext', 'textarea', 'json', 'richtext'].includes(normalizedType)) {
    return 'textarea';
  }
  if (['select', 'enum', 'dict', 'dictionary', 'relation', 'lookup', 'reference'].includes(normalizedType)) {
    return 'select';
  }
  if (['boolean', 'bool'].includes(normalizedType)) return 'checkbox';
  if (
    ['integer', 'int', 'long', 'decimal', 'number', 'float', 'double'].includes(normalizedType)
  ) {
    return 'number';
  }
  if (['money', 'currency'].includes(normalizedType)) return 'moneyinput';
  if (['file', 'attachment', 'image'].includes(normalizedType)) return 'upload';
  return 'input';
}

function relationPickerPropsForModelField(
  field: ModelFieldDefinition,
): Record<string, unknown> | null {
  const refTarget = normalizeModelFieldRefTarget(field.refTarget);
  if (!refTarget?.modelCode && !isRelationFieldType(field.type)) return null;

  const pickerProps: Record<string, unknown> = {
    pickerDataSource: 'model',
    valueField: refTarget?.valueField ?? 'pid',
    displayField: refTarget?.displayField ?? 'displayName',
    searchable: true,
    searchField: refTarget?.displayField ?? 'displayName',
    pageSize: 20,
  };
  if (refTarget?.modelCode) {
    pickerProps.pickerSource = refTarget.modelCode;
  }
  return pickerProps;
}

function normalizeModelFieldRefTarget(
  value: ModelFieldDefinition['refTarget'],
): ModelFieldDefinition['refTarget'] | null {
  if (!value) return null;
  const refTarget: ModelFieldDefinition['refTarget'] = {};
  if (value.modelCode?.trim()) refTarget.modelCode = value.modelCode.trim();
  if (value.valueField?.trim()) refTarget.valueField = value.valueField.trim();
  if (value.displayField?.trim()) refTarget.displayField = value.displayField.trim();
  return Object.keys(refTarget).length > 0 ? refTarget : null;
}

function isRelationFieldType(type: ModelFieldDefinition['type']): boolean {
  return ['relation', 'lookup', 'reference', 'ref', 'belongsTo', 'hasOne']
    .map(normalizeFieldType)
    .includes(normalizeFieldType(type));
}

function defaultFilterOperatorForFieldType(type: ModelFieldDefinition['type']): string {
  const normalizedType = normalizeFieldType(type);
  if (['text', 'longtext', 'textarea', 'string'].includes(normalizedType)) return 'contains';
  return 'equals';
}

function normalizeFieldType(type: ModelFieldDefinition['type']): string {
  return (type ?? '').replace(/[\s_-]/g, '').toLowerCase();
}
