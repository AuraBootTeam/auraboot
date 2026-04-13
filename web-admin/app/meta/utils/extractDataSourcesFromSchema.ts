/**
 * Extract DataSource IDs from Schema Fields
 *
 * 从 Schema 的字段配置中提取所有 dataSource 引用
 * 用于确保所有引用的 datasource 都在页面级别注册
 *
 * 遵循架构原则:
 * - SRP: 单一职责,只负责提取 datasource IDs
 * - OCP: 开闭原则,对扩展开放(支持新的 blockType)
 * - DIP: 依赖倒置,返回抽象的 DataSourceConfig
 *
 * 性能优化 (P2-6):
 * - 使用 WeakMap 缓存避免重复遍历同一 schema
 * - 变更记录: 2025-12-03
 */

import type { UnifiedSchema, DataSourceConfig, FieldConfig } from '~/meta/schemas/types';

// P2-6 修复: 添加 WeakMap 缓存
const extractionCache = new WeakMap<UnifiedSchema, Set<string>>();

/**
 * Extract all dataSource IDs referenced in schema fields
 *
 * @param schema - UnifiedSchema 对象
 * @returns Set of dataSource IDs (去重)
 */
export function extractDataSourceIds(schema: UnifiedSchema | null): Set<string> {
  if (!schema || !schema.blocks) {
    return new Set<string>();
  }

  // P2-6 修复: 检查缓存
  if (extractionCache.has(schema)) {
    return extractionCache.get(schema)!;
  }

  const dataSourceIds = new Set<string>();

  // 遍历所有 block
  for (const block of schema.blocks) {
    // 处理 form-section 类型的 block
    if (block.blockType === 'form-section' && block.fields) {
      block.fields.forEach((field: FieldConfig) => {
        if (field.dataSource && typeof field.dataSource === 'string') {
          dataSourceIds.add(field.dataSource);
        }
      });
    }

    // 处理 filters 类型的 block
    if (block.blockType === 'filters' && block.fields) {
      block.fields.forEach((field: FieldConfig) => {
        if (field.dataSource && typeof field.dataSource === 'string') {
          dataSourceIds.add(field.dataSource);
        }
      });
    }

    // 可扩展:未来可添加其他 blockType
  }

  // P2-6 修复: 存入缓存
  extractionCache.set(schema, dataSourceIds);

  return dataSourceIds;
}

/**
 * Create DataSourceConfig for extracted IDs
 *
 * 为提取的 dataSource IDs 创建默认配置
 * 如果 schema.dataSources 中已有定义,则使用定义;否则创建默认 API 配置
 *
 * @param schema - UnifiedSchema 对象
 * @param dataSourceIds - 提取的 dataSource IDs
 * @returns Record<string, DataSourceConfig>
 */
export function createDataSourceConfigs(
  schema: UnifiedSchema | null,
  dataSourceIds: Set<string>,
): Record<string, DataSourceConfig> {
  const configs: Record<string, DataSourceConfig> = {};

  if (!schema) {
    return configs;
  }

  dataSourceIds.forEach((id) => {
    // 创建默认的 API datasource 配置
    const defaultConfig: DataSourceConfig = {
      type: 'api',
      method: 'get',
      endpoint: `/api/datasource/list`,
      params: { datasourceId: id },
      adaptor: 'optionList',
      valueField: 'value',
      labelField: 'label',
      autoFetch: true,
    };

    // 如果 schema.dataSources 中已有定义,合并配置 (schema 配置覆盖默认值)
    if (schema.dataSources && schema.dataSources[id]) {
      configs[id] = {
        ...defaultConfig,
        ...schema.dataSources[id],
      };
    } else {
      configs[id] = defaultConfig;
    }
  });

  return configs;
}

/**
 * Merge schema.dataSources with extracted field dataSources
 *
 * 合并 schema.dataSources 和从字段提取的 dataSources
 * schema.dataSources 优先级更高
 *
 * @param schema - UnifiedSchema 对象
 * @returns 合并后的 DataSourceConfig Record
 */
export function mergeDataSources(schema: UnifiedSchema | null): Record<string, DataSourceConfig> {
  // 先从字段中提取引用的 dataSource IDs
  const fieldDataSourceIds = extractDataSourceIds(schema);

  // 为这些 IDs 创建配置 (已经在 createDataSourceConfigs 中合并了 schema.dataSources)
  const fieldDataSources = createDataSourceConfigs(schema, fieldDataSourceIds);

  // 添加 schema.dataSources 中存在但字段中未引用的 dataSource
  const allDataSources = { ...fieldDataSources };
  const dataSources = schema?.dataSources;
  if (dataSources) {
    Object.keys(dataSources).forEach((id) => {
      if (!allDataSources[id]) {
        // 只添加字段中未引用的 dataSource (使用默认值填充)
        allDataSources[id] = {
          type: 'api',
          method: 'get',
          endpoint: `/api/datasource/list`,
          params: { datasourceId: id },
          adaptor: 'optionList',
          valueField: 'value',
          labelField: 'label',
          autoFetch: true,
          ...dataSources[id],
        };
      }
    });
  }

  return allDataSources;
}
