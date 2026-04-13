/**
 * List Page Template Generator
 *
 * Generates a UnifiedSchema for list/table pages with:
 * - Filter bar (searchable fields)
 * - Toolbar (add/export/batch actions)
 * - Data table (columns + row actions)
 * - Pagination
 *
 * @since 3.8.0
 */

import type {
  UnifiedSchema,
  FieldConfig,
  ColumnConfig,
  ButtonConfig,
  BlockConfig,
} from '~/meta/schemas/types';
import type {
  TemplateModelMeta,
  TemplateOptions,
  TemplateFieldMeta,
  TemplateGenerator,
  TemplateAction,
  TemplateClassOverrides,
  TemplateStyleSet,
} from '../types';
import { TEMPLATE_STYLES } from '../types';
import { mapFieldToComponent, mapFieldToValueType } from '../utils';

export const ListTemplate: TemplateGenerator = {
  type: 'list',
  generate(model: TemplateModelMeta, options: TemplateOptions = {}): UnifiedSchema {
    const {
      variant = 'default',
      pageSize = 20,
      includeActions = true,
      includeSelection = false,
      includeExport = false,
      classOverrides = {},
      customActions = [],
    } = options;

    const styles = TEMPLATE_STYLES[variant] ?? TEMPLATE_STYLES.default;
    const primaryKey = model.primaryKey ?? 'id';
    const apiBase = model.apiBasePath ?? `/api/dynamic/${model.modelCode}`;

    const searchableFields = model.fields.filter((f) => f.searchable);
    const listFields = model.fields.filter((f) => f.listVisible !== false);

    const blocks: BlockConfig[] = [];

    // Filter block
    if (searchableFields.length > 0) {
      blocks.push(buildFilterBlock(searchableFields, styles, classOverrides));
    }

    // Toolbar block
    blocks.push(buildToolbarBlock(model, includeExport, customActions, styles, classOverrides));

    // Table block
    blocks.push(
      buildTableBlock(
        listFields,
        primaryKey,
        includeActions,
        includeSelection,
        pageSize,
        customActions,
        styles,
        classOverrides,
      ),
    );

    return {
      kind: 'list',
      version: '1.0',
      id: `${model.modelCode}_list`,
      title: { 'zh-CN': `${model.displayName}列表`, 'en-US': `${model.displayName} List` },
      layout: {
        type: 'stack',
        gap: 0,
      },
      blocks,
      dataSources: {
        ds_list: {
          type: 'api',
          endpoint: `${apiBase}/list`,
          method: 'get',
          autoFetch: true,
          pagination: true,
        },
      },
      handlers: {
        onSearch: {
          type: 'flow',
          steps: [{ action: 'refreshDataSource', args: { sourceId: 'ds_list' } }],
        },
        onReset: {
          type: 'flow',
          steps: [
            { action: 'resetFilters' },
            { action: 'refreshDataSource', args: { sourceId: 'ds_list' } },
          ],
        },
        onAdd: {
          type: 'flow',
          steps: [{ action: 'navigate', args: { path: `/${model.modelCode}/create` } }],
        },
        onEdit: {
          type: 'flow',
          steps: [
            {
              action: 'navigate',
              args: { path: `/${model.modelCode}/\${row.${primaryKey}}/edit` },
            },
          ],
        },
        onView: {
          type: 'flow',
          steps: [
            { action: 'navigate', args: { path: `/${model.modelCode}/\${row.${primaryKey}}` } },
          ],
        },
        onDelete: {
          type: 'flow',
          steps: [
            {
              action: 'confirm',
              args: { title: '确认删除', message: '删除后无法恢复，确定继续？' },
            },
            { action: 'apiCall', method: 'delete', endpoint: `${apiBase}/\${row.${primaryKey}}` },
            { action: 'toast', level: 'success', content: '删除成功' },
            { action: 'refreshDataSource', args: { sourceId: 'ds_list' } },
          ],
        },
      },
      state: {
        filters: {},
        selectedRows: [],
      },
    };
  },
};

function buildFilterBlock(
  fields: TemplateFieldMeta[],
  styles: TemplateStyleSet,
  overrides: TemplateClassOverrides,
): BlockConfig {
  const filterFields: FieldConfig[] = fields.map((f) => ({
    field: f.field,
    label: f.label,
    component: mapFieldToFilterComponent(f),
    props: {
      placeholder: f.placeholder ?? `请输入${f.label}`,
      allowClear: true,
      ...(f.options ? { options: f.options } : {}),
    },
    ...(f.dataSourceId ? { dataSource: f.dataSourceId } : {}),
  }));

  return {
    id: 'block_filters',
    blockType: 'filters',
    className: overrides.card ?? styles.card,
    fields: filterFields,
    buttons: [
      { code: 'search', label: '搜索', variant: 'primary', handler: 'onSearch', icon: 'Search' },
      { code: 'reset', label: '重置', variant: 'default', handler: 'onReset' },
    ],
  };
}

function buildToolbarBlock(
  model: TemplateModelMeta,
  includeExport: boolean,
  customActions: TemplateAction[],
  styles: TemplateStyleSet,
  overrides: TemplateClassOverrides,
): BlockConfig {
  const buttons: ButtonConfig[] = [
    {
      code: 'add',
      label: { 'zh-CN': '新增', 'en-US': 'Add' },
      variant: 'primary',
      icon: 'Plus',
      handler: 'onAdd',
    },
  ];

  if (includeExport) {
    buttons.push({
      code: 'export',
      label: { 'zh-CN': '导出', 'en-US': 'Export' },
      variant: 'default',
      icon: 'Download',
      handler: 'onExport',
    });
  }

  const toolbarActions = customActions.filter((a) => a.position === 'toolbar');
  for (const action of toolbarActions) {
    buttons.push({
      code: action.code,
      label: action.label,
      variant: action.variant ?? 'default',
      icon: action.icon,
      handler: action.handler ?? `on_${action.code}`,
    });
  }

  return {
    id: 'block_toolbar',
    blockType: 'toolbar',
    className: overrides.buttonGroup ?? styles.toolbar,
    buttons,
  };
}

function buildTableBlock(
  fields: TemplateFieldMeta[],
  primaryKey: string,
  includeActions: boolean,
  includeSelection: boolean,
  pageSize: number,
  customActions: TemplateAction[],
  styles: TemplateStyleSet,
  overrides: TemplateClassOverrides,
): BlockConfig {
  const columns: ColumnConfig[] = fields.map((f) => {
    const col: ColumnConfig = {
      field: f.field,
      label: f.label,
      sortable: f.sortable,
      width: f.width,
      ellipsis: f.type === 'string' || f.type === 'text',
      valueType: f.valueType ?? mapFieldToValueType(f.type),
    };
    if (f.options) {
      col.filters = f.options.map((o) => ({ text: o.label, value: o.value }));
    }
    return col;
  });

  // Action column
  if (includeActions) {
    const rowButtons: ButtonConfig[] = [
      { code: 'view', label: '查看', handler: 'onView' },
      { code: 'edit', label: '编辑', handler: 'onEdit' },
      { code: 'delete', label: '删除', variant: 'danger', handler: 'onDelete' },
    ];

    const rowActions = customActions.filter((a) => a.position === 'row');
    for (const action of rowActions) {
      rowButtons.push({
        code: action.code,
        label: action.label,
        variant: action.variant,
        handler: action.handler ?? `on_${action.code}`,
      });
    }

    columns.push({
      field: '_actions',
      label: { 'zh-CN': '操作', 'en-US': 'Actions' },
      isActionColumn: true,
      width: 180,
      buttons: rowButtons,
    });
  }

  return {
    id: 'block_table',
    blockType: 'table',
    className: overrides.card ?? styles.card,
    table: {
      rowKey: primaryKey,
      dataSource: 'ds_list',
      pagination: {
        pageSize,
        showTotal: true,
        showSizeChanger: true,
        pageSizeOptions: [10, 20, 50, 100],
      },
      ...(includeSelection
        ? { selection: { mode: 'multiple' as const, bind: 'selectedRows' } }
        : {}),
      columns,
    },
  };
}

function mapFieldToFilterComponent(field: TemplateFieldMeta): string {
  switch (field.type) {
    case 'enum':
      return 'SmartSelect';
    case 'date':
    case 'datetime':
      return 'SmartDateRange';
    case 'boolean':
      return 'SmartSelect';
    case 'number':
      return 'SmartNumberRange';
    default:
      return 'SmartInput';
  }
}
