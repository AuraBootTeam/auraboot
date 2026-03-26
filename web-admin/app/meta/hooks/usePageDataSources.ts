/**
 * usePageDataSources Hook
 * 页面级数据源管理，用于批量注册和管理多个数据源
 *
 * 使用场景：
 * - 列表页（管理筛选器、表格等多个数据源）
 * - 表单页（管理多个下拉框、级联选择等数据源）
 * - 详情页（管理多个关联数据源）
 *
 * 变更记录:
 * - 2025-12-03: 增加自动合并字段引用的 dataSources (修复 P0-2)
 */

import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { DataSourceManager } from '~/meta/runtime/data-pipeline/DataSourceManager';
import type { DataSourceConfig, UnifiedSchema } from '~/meta/schemas/types';
import type { ExpressionContext } from '~/meta/runtime/expression/context';
import { mergeDataSources } from '~/meta/utils/extractDataSourcesFromSchema';

export interface DataSourceState {
  data: any;
  loading: boolean;
  error: Error | null;
  lastFetch: number | null;
}

export interface UsePageDataSourcesOptions {
  context: ExpressionContext;

  /**
   * 数据源配置 (两种方式二选一):
   * 1. 直接传入 dataSources 对象 (旧方式)
   * 2. 传入 schema，自动提取和合并字段引用的 dataSources (推荐)
   */
  dataSources?: Record<string, DataSourceConfig>;

  /**
   * 完整的 Schema 对象 (推荐方式)
   * 如果提供，会自动从字段中提取 dataSource 引用并合并到 schema.dataSources
   */
  schema?: UnifiedSchema | null;
}

export interface UsePageDataSourcesResult {
  manager: DataSourceManager;
  getData: (id: string) => any;
  getState: (id: string) => DataSourceState | undefined;
  fetch: (id: string, params?: Record<string, any>) => Promise<any>;
  reload: (id: string | string[]) => Promise<void>;
  subscribe: (id: string, callback: (state: DataSourceState) => void) => () => void;
}

/**
 * Page-level DataSource Manager Hook
 *
 * @example
 * ```tsx
 * // 方式 1: 直接传入 dataSources (旧方式)
 * const { manager } = usePageDataSources({
 *   context: expressionContext,
 *   dataSources: {
 *     ds_types: { endpoint: '/api/types', adaptor: 'optionList' },
 *     ds_statuses: { endpoint: '/api/statuses', adaptor: 'optionList' },
 *   }
 * });
 *
 * // 方式 2: 传入 schema，自动提取 (推荐)
 * const { manager } = usePageDataSources({
 *   context: expressionContext,
 *   schema: schema, // 自动提取字段中引用的 dataSources
 * });
 * ```
 */
export function usePageDataSources(options: UsePageDataSourcesOptions): UsePageDataSourcesResult {
  const managerRef = useRef<DataSourceManager | null>(null);
  const [, forceUpdate] = useState({});
  const dataSourcesStringRef = useRef<string>('');

  // 初始化 DataSourceManager
  if (!managerRef.current) {
    managerRef.current = new DataSourceManager(options.context);
  }

  const manager = managerRef.current;
  if (options.context && !(options.context as any).__dataSourceManager) {
    (options.context as any).__dataSourceManager = manager;
  }

  /**
   * 自动合并 dataSources
   *
   * 优先级:
   * 1. 如果提供了 schema，使用 mergeDataSources 自动提取和合并
   * 2. 否则使用直接传入的 dataSources
   */
  const mergedDataSources = useMemo(() => {
    if (options.schema) {
      // 方式 2: 自动提取和合并 (推荐)
      return mergeDataSources(options.schema);
    } else if (options.dataSources) {
      // 方式 1: 直接使用传入的 dataSources (向后兼容)
      return options.dataSources;
    }
    return {};
  }, [options.schema, options.dataSources]);

  // 当 dataSources 变化时,重新注册所有数据源
  // 使用 JSON.stringify 进行深度比较，避免对象引用变化导致重复注册
  useEffect(() => {
    if (!mergedDataSources || Object.keys(mergedDataSources).length === 0 || !manager) {
      return;
    }

    const dataSourcesString = JSON.stringify(mergedDataSources);

    // 如果数据源内容没有变化，直接返回
    if (dataSourcesString === dataSourcesStringRef.current) {
      return;
    }

    // 记录新的数据源字符串
    dataSourcesStringRef.current = dataSourcesString;

    // 清除旧的数据源
    manager.clear();

    // 注册新的数据源
    Object.entries(mergedDataSources).forEach(([id, config]) => {
      manager.register(id, config);
    });
  }, [mergedDataSources, manager]);

  // 清理
  useEffect(() => {
    return () => {
      manager.clear();
    };
  }, []);

  const getData = useCallback(
    (id: string) => {
      return manager.getData(id);
    },
    [manager],
  );

  const getState = useCallback(
    (id: string) => {
      return manager.getState(id);
    },
    [manager],
  );

  const fetch = useCallback(
    async (id: string, params?: Record<string, any>) => {
      return await manager.fetch(id, params);
    },
    [manager],
  );

  const reload = useCallback(
    async (id: string | string[]) => {
      await manager.reload(id);
      forceUpdate({});
    },
    [manager],
  );

  const subscribe = useCallback(
    (id: string, callback: (state: DataSourceState) => void) => {
      return manager.subscribe(id, callback);
    },
    [manager],
  );

  return {
    manager,
    getData,
    getState,
    fetch,
    reload,
    subscribe,
  };
}
