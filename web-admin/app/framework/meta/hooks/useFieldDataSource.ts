/**
 * useFieldDataSource Hook
 * 字段级数据源管理，为单个表单字段或筛选器提供数据源功能
 *
 * 使用场景：
 * - SmartSelect 组件（下拉选择框）
 * - SmartRadio 组件（单选框组）
 * - SmartCheckbox 组件（多选框组）
 * - 任何需要选项列表的表单字段
 */

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import type { DataSourceConfig } from '~/framework/meta/schemas/types';
import type { ExpressionContext } from '~/framework/meta/runtime/expression/context';
import { useDataSourceManagerOptional } from '~/framework/meta/contexts/DataSourceContext';

export interface OptionItem {
  label: string;
  value: any;
  disabled?: boolean;
  [key: string]: any;
}

export interface UseFieldDataSourceProps {
  staticOptions?: OptionItem[];
  dataSource?: DataSourceConfig | string; // 支持 DataSourceConfig 对象或 dataSource ID
  context?: ExpressionContext;
  managerInstance?: DataSourceManager; // 可选的外部 DataSourceManager 实例
}

export interface UseFieldDataSourceResult {
  options: OptionItem[];
  loading: boolean;
  error: string | null;
  refetch: () => Promise<void>;
}

const EMPTY_OPTIONS: OptionItem[] = [];

function stableDataSourceKey(value: unknown): string {
  if (value === undefined || value === null) return '';
  if (typeof value === 'string') return `id:${value}`;
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

/**
 * Field-level DataSource Hook
 *
 * 功能:
 * 1. 支持静态选项 (staticOptions)
 * 2. 支持 DataSourceConfig (自动创建临时 DataSourceManager)
 * 3. 支持 dataSource ID (使用页面级 DataSourceManager)
 * 4. 支持 autoFetch
 * 5. 支持默认值 (valueField, labelField, adaptor)
 * 6. 支持参数求值和拼接
 * 7. 支持订阅机制 (数据变化自动更新)
 *
 * @example
 * ```tsx
 * // 使用数据源 ID（推荐，配合 usePageDataSources）
 * const { options, loading, error } = useFieldDataSource({
 *   dataSource: 'ds_types',
 *   context: expressionContext
 * });
 *
 * // 使用静态选项
 * const { options } = useFieldDataSource({
 *   staticOptions: [
 *     { label: '选项1', value: '1' },
 *     { label: '选项2', value: '2' }
 *   ]
 * });
 *
 * // 使用内联配置（不推荐，仅用于简单场景）
 * const { options, loading } = useFieldDataSource({
 *   dataSource: {
 *     endpoint: '/api/options',
 *     adaptor: 'optionList'
 *   },
 *   context: expressionContext
 * });
 * ```
 */
export function useFieldDataSource({
  staticOptions = EMPTY_OPTIONS,
  dataSource,
  context,
  managerInstance,
}: UseFieldDataSourceProps): UseFieldDataSourceResult {
  const [options, setOptions] = useState<OptionItem[]>(staticOptions);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 内部 DataSourceManager (如果没有传入外部实例)
  const internalManagerRef = useRef<DataSourceManager | null>(null);
  const dataSourceIdRef = useRef<string>(`ds_${Date.now()}_${Math.random()}`);

  // 尝试从 React Context 中获取 DataSourceManager (优先级最高)
  const reactContextManager = useDataSourceManagerOptional();

  // 尝试从 ExpressionContext 中获取 DataSourceManager (降级方案)
  const expressionContextManager = context ? (context as any).__dataSourceManager : null;

  // 选择使用 manager 的优先级:
  // 1. managerInstance (显式传入)
  // 2. reactContextManager (React Context Provider)
  // 3. expressionContextManager (ExpressionContext 中的 __dataSourceManager)
  // 4. internalManagerRef (最后创建内部实例)
  const manager =
    managerInstance ||
    reactContextManager ||
    expressionContextManager ||
    internalManagerRef.current;

  const dataSourceKey = useMemo(() => stableDataSourceKey(dataSource), [dataSource]);
  const staticOptionsKey = useMemo(() => stableDataSourceKey(staticOptions), [staticOptions]);

  // P0-3: 强制单例 - 如果没有外部 manager,抛出错误
  useEffect(() => {
    if (
      !managerInstance &&
      !reactContextManager &&
      !expressionContextManager &&
      dataSource &&
      typeof dataSource !== 'string'
    ) {
      // 只有在使用内联配置时才抛出错误（字符串 ID 和静态选项不需要 manager）
      throw new Error(
        '[useFieldDataSource] P0-3: DataSourceManager is required but not provided. ' +
          'Please wrap your component tree with <DataSourceProvider> or pass managerInstance prop. ' +
          'Inline dataSource configs are no longer supported without an external DataSourceManager.',
      );
    }
  }, [managerInstance, reactContextManager, expressionContextManager, dataSourceKey]);

  // 注册数据源
  useEffect(() => {
    if (!dataSource || !manager) {
      // 没有数据源,使用静态选项
      setOptions(staticOptions);
      return;
    }

    const dsId = dataSourceIdRef.current;

    // 如果 dataSource 是字符串,说明是已注册的 dataSource ID
    if (typeof dataSource === 'string') {
      // 订阅数据源变化 (重要! 先订阅再获取状态)
      const unsubscribe = manager.subscribe(dataSource, (newState: any) => {
        if (newState.data) {
          setOptions(Array.isArray(newState.data) ? newState.data : []);
        }
        setLoading(newState.loading);
        setError(newState.error?.message || null);
      });

      // 获取初始状态 (订阅后立即获取，确保不会错过已有的数据)
      const state = manager.getState(dataSource);

      if (state?.data) {
        setOptions(Array.isArray(state.data) ? state.data : []);
      }
      setLoading(state?.loading || false);
      setError(state?.error?.message || null);

      return () => {
        unsubscribe();
      };
    }

    // 如果 dataSource 是对象,注册为临时数据源
    const dsConfig: DataSourceConfig = {
      type: 'api',
      method: 'get',
      adaptor: 'optionList',
      valueField: 'value',
      labelField: 'name',
      autoFetch: true,
      endpoint: '/api/datasource/list',
      ...dataSource,
    };

    // 注册数据源
    manager.register(dsId, dsConfig);

    // 订阅数据源变化
    const unsubscribe = manager.subscribe(dsId, (state: any) => {
      if (state.data) {
        setOptions(Array.isArray(state.data) ? state.data : []);
      }
      setLoading(state.loading);
      setError(state.error?.message || null);
    });

    // 如果不是 autoFetch,手动触发首次加载
    if (!dsConfig.autoFetch) {
      const state = manager.getState(dsId);
      if (state?.data) {
        setOptions(Array.isArray(state.data) ? state.data : []);
      }
    }

    return () => {
      unsubscribe();
      // 清理临时数据源
      manager.unregister(dsId);
    };
  }, [dataSourceKey, manager, staticOptionsKey]);

  // 重新加载数据
  const refetch = useCallback(async () => {
    if (!dataSource || !manager) {
      return;
    }

    const dsId = typeof dataSource === 'string' ? dataSource : dataSourceIdRef.current;

    try {
      setLoading(true);
      setError(null);
      const data = await manager.fetch(dsId);
      if (data) {
        setOptions(Array.isArray(data) ? data : []);
      }
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : '数据加载失败';
      setError(errorMsg);
      console.error('useFieldDataSource refetch error:', err);
    } finally {
      setLoading(false);
    }
  }, [dataSourceKey, manager]);

  // 清理内部 manager
  useEffect(() => {
    return () => {
      if (internalManagerRef.current) {
        internalManagerRef.current.clear();
      }
    };
  }, []);

  return {
    options,
    loading,
    error,
    refetch,
  };
}
