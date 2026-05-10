import type {
  BlockConfig,
  ButtonConfig,
  ColumnConfig,
  DataSourceConfig,
  UnifiedSchema,
} from '~/framework/meta/schemas/types';
import { DslMigrator } from '~/framework/meta/migration';

const DEFAULT_DSL_VERSION = '1.0.0';

const BUILTIN_VALUE_TYPES = new Set([
  'text',
  'boolean',
  'date',
  'datetime',
  'time',
  'currency',
  'tag',
  'progress',
  'image',
  'user_identity',
  'reference',
  'button',
  'url',
  'email',
  'color',
  'link',
]);

export interface PageSchemaDTO {
  pid?: string | null;
  pageKey: string;
  modelCode?: string | null;
  modelCategory?: string | null;
  name?: string | null;
  title?: string | Record<string, string> | null;
  description?: string | null;
  kind: string;
  commandCode?: string;
  blocks?: any[];
  layout?: Record<string, any> | null;
  profile?: string | null;
  schemaVersion?: number | null;
  metaInfo?: Record<string, unknown> | null;
  isTemplate?: boolean;
  extension?: Record<string, any>;
  'name:zh-CN'?: string;
  'name:en'?: string;
  'name:en-US'?: string;
}

function jsValue(value: unknown): string {
  return JSON.stringify(value);
}

function normalizeConditionExpression(condition: unknown): string | undefined {
  if (!condition) return undefined;
  if (typeof condition === 'string') return condition;

  if (typeof condition !== 'object') return undefined;

  const { field, operator, value } = condition as {
    field?: string;
    operator?: string;
    value?: unknown;
  };
  if (!field || !operator) return undefined;

  const fieldKey = jsValue(field);
  const subject = `(row?.[${fieldKey}] ?? record?.[${fieldKey}] ?? form?.[${fieldKey}])`;
  switch (operator) {
    case 'EQ':
    case 'eq':
      return `${subject} === ${jsValue(value)}`;
    case 'NE':
    case 'neq':
      return `${subject} !== ${jsValue(value)}`;
    case 'IN':
    case 'in':
      return `${jsValue(Array.isArray(value) ? value : [value])}.includes(${subject})`;
    case 'not_in':
    case 'NOT_IN':
      return `!${jsValue(Array.isArray(value) ? value : [value])}.includes(${subject})`;
    default:
      return undefined;
  }
}

function resolveTitle(pageSchemaDTO: PageSchemaDTO): string | Record<string, string> {
  if (pageSchemaDTO.title) return pageSchemaDTO.title;
  if (pageSchemaDTO.name) return pageSchemaDTO.name;

  const zhCN = pageSchemaDTO['name:zh-CN'];
  const enUS = pageSchemaDTO['name:en-US'] || pageSchemaDTO['name:en'];
  if (zhCN || enUS) {
    return {
      ...(zhCN && { 'zh-CN': zhCN }),
      ...(enUS && { 'en-US': enUS }),
    };
  }

  return pageSchemaDTO.pageKey;
}

function normalizeDataSourceConfig(dataSource: Record<string, any>): DataSourceConfig {
  return {
    ...dataSource,
    ...(dataSource.kind && !dataSource.type && { type: dataSource.kind }),
    ...(dataSource.url && !dataSource.endpoint && { endpoint: dataSource.url }),
  };
}

function normalizeButton(button: ButtonConfig): ButtonConfig {
  const result: ButtonConfig = { ...button };
  const visibleWhen = normalizeConditionExpression((result as any).visibleWhen);
  if (visibleWhen) {
    result.visibleWhen = visibleWhen;
  }

  if (result.confirmMessageKey && !result.confirm) {
    result.confirm = result.confirmMessageKey;
  }

  if (!result.action) {
    if (result.commandCode && result.navigateTo) {
      result.action = {
        type: 'navigate',
        to: result.navigateTo,
        command: result.commandCode,
      };
    } else if (result.commandCode) {
      result.action = {
        type: 'command',
        command: result.commandCode,
      };
    } else if (result.navigateTo) {
      result.action = {
        type: 'navigate',
        to: result.navigateTo,
      };
    } else if (result.events?.onClick?.handler) {
      result.action = {
        type: 'flow',
        handler: result.events.onClick.handler,
      };
    }
  }

  delete (result as any).confirmMessageKey;
  delete (result as any).commandCode;
  delete (result as any).navigateTo;

  return result;
}

function normalizeColumn(column: ColumnConfig): ColumnConfig {
  const result: ColumnConfig = { ...column };
  const valueType = result.valueType;

  if (typeof valueType === 'string' && valueType && !BUILTIN_VALUE_TYPES.has(valueType)) {
    (result as any).cellRenderer = (result as any).cellRenderer || valueType;
    delete (result as any).valueType;
  }

  if (Array.isArray(result.buttons)) {
    result.buttons = result.buttons.map(normalizeButton);
  }

  return result;
}

function normalizeColumns(columns: unknown): unknown {
  if (!Array.isArray(columns)) return columns;
  return columns.map((column) => normalizeColumn(column as ColumnConfig));
}

function normalizeBlock(
  block: BlockConfig,
  dataSources: Record<string, DataSourceConfig>,
): BlockConfig {
  const result: BlockConfig = { ...block };
  const visibleWhen = normalizeConditionExpression((result as any).visibleWhen);
  if (visibleWhen) {
    result.visibleWhen = visibleWhen;
  }

  if (
    result.dataSource &&
    typeof result.dataSource === 'object' &&
    !Array.isArray(result.dataSource)
  ) {
    const source = normalizeDataSourceConfig(result.dataSource as Record<string, any>);
    const id = source.id || `${result.id}_dataSource`;
    dataSources[id] = { ...source, id };
    result.dataSource = id;
  }

  if (Array.isArray(result.buttons)) {
    result.buttons = result.buttons.map(normalizeButton);
  }

  if (Array.isArray(result.rowActions)) {
    result.rowActions = result.rowActions.map(normalizeButton);
  }

  if (Array.isArray(result.columns)) {
    result.columns = normalizeColumns(result.columns) as ColumnConfig[];
  }

  if (result.table?.columns) {
    const tableDataSource = (result.table as any).dataSource;
    let normalizedTableDataSource = tableDataSource;
    if (tableDataSource && typeof tableDataSource === 'object' && !Array.isArray(tableDataSource)) {
      const source = normalizeDataSourceConfig(tableDataSource);
      const id = source.id || `${result.id}_table_dataSource`;
      dataSources[id] = { ...source, id };
      normalizedTableDataSource = id;
    }

    result.table = {
      ...result.table,
      dataSource: normalizedTableDataSource,
      columns: normalizeColumns(result.table.columns) as ColumnConfig[],
      rowActions: Array.isArray(result.table.rowActions)
        ? result.table.rowActions.map(normalizeButton)
        : result.table.rowActions,
    };
  }

  if (result.subTable?.columns) {
    result.subTable = {
      ...result.subTable,
      columns: normalizeColumns(result.subTable.columns) as ColumnConfig[],
      actions: Array.isArray(result.subTable.actions)
        ? result.subTable.actions.map(normalizeButton)
        : result.subTable.actions,
    };
  }

  if (Array.isArray(result.tabs)) {
    result.tabs = result.tabs.map((tab: any) =>
      Array.isArray(tab.blocks)
        ? {
            ...tab,
            blocks: tab.blocks.map((child: BlockConfig) => normalizeBlock(child, dataSources)),
          }
        : tab,
    ) as any;
  }

  return result;
}

export function canonicalizePageSchemaDto(pageSchemaDTO: PageSchemaDTO): UnifiedSchema {
  const raw: Record<string, any> = {
    id: pageSchemaDTO.pid || pageSchemaDTO.pageKey,
    version: DEFAULT_DSL_VERSION,
    kind: pageSchemaDTO.kind,
    title: resolveTitle(pageSchemaDTO),
    name: pageSchemaDTO.name,
    blocks: pageSchemaDTO.blocks || [],
    layout: pageSchemaDTO.layout || { type: 'stack' },
    profile: pageSchemaDTO.profile || 'admin',
    schemaVersion: pageSchemaDTO.schemaVersion ?? undefined,
    pageKey: pageSchemaDTO.pageKey,
    commandCode: pageSchemaDTO.commandCode,
    ...(pageSchemaDTO.modelCode && { modelCode: pageSchemaDTO.modelCode }),
    ...(pageSchemaDTO.modelCategory && { modelCategory: pageSchemaDTO.modelCategory }),
    ...(pageSchemaDTO.description && { description: pageSchemaDTO.description }),
    ...(pageSchemaDTO.extension?.dataSource && {
      dataSource: pageSchemaDTO.extension.dataSource,
    }),
    ...(pageSchemaDTO.extension?.options && {
      options: pageSchemaDTO.extension.options,
    }),
    ...(pageSchemaDTO.extension && { extension: pageSchemaDTO.extension }),
  };

  const migrated = DslMigrator.migrate(raw);
  const dataSources: Record<string, DataSourceConfig> = {
    ...(migrated.dataSources || {}),
  };
  const blocks = Array.isArray(migrated.blocks)
    ? migrated.blocks.map((block: BlockConfig) => normalizeBlock(block, dataSources))
    : [];

  return {
    ...migrated,
    id: migrated.id || pageSchemaDTO.pid || pageSchemaDTO.pageKey,
    version: migrated.version || DEFAULT_DSL_VERSION,
    blocks,
    ...(Object.keys(dataSources).length > 0 && { dataSources }),
    layout: migrated.layout || { type: 'stack' },
  } as UnifiedSchema;
}

export const PAGE_DSL_BUILTIN_VALUE_TYPES = BUILTIN_VALUE_TYPES;
