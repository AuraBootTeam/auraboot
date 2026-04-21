import type { UnifiedSchema } from '~/framework/meta/schemas/types';

interface SelectOption {
  value: string;
  label: string;
}

export const FIELD_LIST_DATA_TYPES_FALLBACK: SelectOption[] = [
  { value: 'string', label: 'String' },
  { value: 'integer', label: 'Integer' },
  { value: 'long', label: 'Long' },
  { value: 'decimal', label: 'Decimal' },
  { value: 'boolean', label: 'Boolean' },
  { value: 'date', label: 'Date' },
  { value: 'datetime', label: 'DateTime' },
  { value: 'text', label: 'Text' },
  { value: 'json', label: 'json' },
  { value: 'reference', label: 'Reference' },
];

export function buildFieldListSchema(dataTypeOptions: SelectOption[]): UnifiedSchema {
  return {
    kind: 'list',
    version: '1.0.0',
    id: 'list.meta.fields.local',
    title: '字段库',
    profile: 'admin',
    modelCode: 'meta_fields',
    schemaVersion: 1,
    layout: {
      type: 'stack',
      gap: 0,
    },
    dataSource: {
      type: 'api',
      endpoint: '/api/meta/field-library/search',
      method: 'post',
    },
    blocks: [
      {
        id: 'field_filters',
        blockType: 'filters',
        fields: [
          {
            field: 'baseType',
            label: '基础类型',
            component: 'SmartSelect',
            dataSource: {
              type: 'static',
              data: [{ value: '', label: '全部类型' }, ...dataTypeOptions],
              valueField: 'value',
              labelField: 'label',
            },
            props: {
              placeholder: '全部类型',
            },
          },
          {
            field: 'semanticType',
            label: '语义类型',
            component: 'SmartInput',
            props: {
              placeholder: '如: email, phone',
            },
          },
          {
            field: 'systemFieldsOnly',
            label: '仅系统字段',
            component: 'checkbox',
            props: {
              checkedLabel: '是',
              uncheckedLabel: '否',
            },
          },
          {
            field: 'unusedOnly',
            label: '仅未使用',
            component: 'checkbox',
            props: {
              checkedLabel: '是',
              uncheckedLabel: '否',
            },
          },
        ],
        buttons: [
          { code: 'reset', content: '重置' },
          { code: 'search', content: '查询', primary: true },
        ],
      },
      {
        id: 'field_toolbar',
        blockType: 'form-buttons',
        buttons: [
          {
            code: 'create',
            content: '新建字段',
            primary: true,
            navigateTo: '/meta/fields/new',
          },
        ],
      },
      {
        id: 'field_table',
        blockType: 'table',
        table: {
          rowKey: 'pid',
          dataSource: 'fieldList',
          pagination: {
            pageSize: 20,
            pageSizeOptions: [20, 50, 100],
            showTotal: true,
            showSizeChanger: true,
          },
          columns: [
            {
              field: 'code',
              label: '字段编码',
              width: 220,
              valueType: 'meta_field_code',
            },
            {
              field: 'dataType',
              label: '数据类型',
              width: 140,
            },
            {
              field: 'status',
              label: '状态',
              width: 110,
              valueType: 'tag',
              tagMap: {
                draft: { label: '草稿', color: 'gray' },
                published: { label: '已发布', color: 'green' },
                archived: { label: '已归档', color: 'blue' },
                deprecated: { label: '已废弃', color: 'orange' },
                disabled: { label: '已禁用', color: 'red' },
              },
            } as any,
            {
              field: 'version',
              label: '版本',
              width: 90,
            },
            {
              field: 'required',
              label: '必填',
              width: 90,
              valueType: 'boolean',
            },
            {
              field: 'createdAt',
              label: '创建时间',
              width: 180,
              valueType: 'datetime',
            },
            {
              field: 'pid',
              label: '操作',
              width: 220,
              valueType: 'meta_field_actions',
            },
          ],
        },
      },
    ],
  };
}
