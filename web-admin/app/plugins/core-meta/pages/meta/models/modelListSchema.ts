import type { UnifiedSchema } from '~/framework/meta/schemas/types';

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'draft', label: '草稿' },
  { value: 'published', label: '已发布' },
  { value: 'archived', label: '已归档' },
];

const SOURCE_TYPE_OPTIONS = [
  { value: '', label: '全部来源' },
  { value: 'physical', label: '物理表' },
  { value: 'namedQuery', label: 'NamedQuery' },
  { value: 'sqlView', label: 'SQL View' },
  { value: 'endpoint', label: 'Endpoint' },
];

export const MODEL_LIST_SCHEMA: UnifiedSchema = {
  kind: 'list',
  version: '1.0.0',
  id: 'list.meta.models.local',
  title: '模型',
  profile: 'admin',
  modelCode: 'meta_models',
  schemaVersion: 1,
  layout: {
    type: 'stack',
    gap: 0,
  },
  dataSource: {
    type: 'api',
    endpoint: '/api/meta/models',
    method: 'get',
  },
  blocks: [
    {
      id: 'model_filters',
      blockType: 'filters',
      fields: [
        {
          field: 'sourceType',
          label: '数据来源',
          component: 'SmartSelect',
          dataSource: {
            type: 'static',
            data: SOURCE_TYPE_OPTIONS,
            valueField: 'value',
            labelField: 'label',
          },
          props: {
            placeholder: '全部来源',
          },
        },
        {
          field: 'status',
          label: '状态',
          component: 'SmartSelect',
          dataSource: {
            type: 'static',
            data: STATUS_OPTIONS,
            valueField: 'value',
            labelField: 'label',
          },
          props: {
            placeholder: '全部状态',
          },
        },
      ],
      buttons: [
        { code: 'reset', content: '重置' },
        { code: 'search', content: '查询', primary: true },
      ],
    },
    {
      id: 'model_toolbar',
      blockType: 'form-buttons',
      buttons: [
        {
          code: 'create',
          content: '新建模型',
          primary: true,
          navigateTo: '/meta/models/new',
        },
      ],
    },
    {
      id: 'model_table',
      blockType: 'table',
      table: {
        rowKey: 'pid',
        dataSource: 'modelList',
        pagination: {
          pageSize: 20,
          pageSizeOptions: [20, 50, 100],
          showTotal: true,
          showSizeChanger: true,
        },
        columns: [
          {
            field: 'code',
            label: '模型编码',
            width: 220,
            sortable: true,
            valueType: 'meta_model_code',
          },
          {
            field: 'displayName',
            label: '显示名称',
            width: 220,
            sortable: true,
          },
          {
            field: 'sourceType',
            label: '数据来源',
            width: 140,
            valueType: 'meta_model_source_type',
            allowNullRenderer: true,
          } as any,
          {
            field: 'health',
            label: '健康',
            width: 140,
            valueType: 'meta_model_health',
            allowNullRenderer: true,
          } as any,
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
            width: 80,
            sortable: true,
          },
          {
            field: 'fieldCount',
            label: '字段数',
            width: 90,
          },
          {
            field: 'createdAt',
            label: '创建时间',
            width: 180,
            sortable: true,
            valueType: 'datetime',
          },
          {
            field: 'pid',
            label: '操作',
            width: 190,
            valueType: 'meta_model_actions',
          },
        ],
      },
    },
  ],
};
