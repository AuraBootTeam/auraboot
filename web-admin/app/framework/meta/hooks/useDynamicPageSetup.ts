/**
 * useDynamicPageSetup Hook
 *
 * 封装动态页面的通用设置逻辑,消除重复代码
 *
 * 使用场景:
 * - 列表页 (dynamic.$tableName.tsx)
 * - 新建页 (dynamic.$tableName.new.tsx)
 * - 编辑页 (dynamic.$tableName.edit.tsx)
 * - 详情页 (dynamic.$tableName.view.tsx)
 *
 * 功能:
 * 1. 加载 Schema (useSchemaLoader)
 * 2. 初始化 DataSourceManager (usePageDataSources)
 * 3. 初始化 SchemaRuntime (useSchemaRuntime)
 * 4. 提供统一的错误处理和加载状态
 *
 * 变更记录:
 * - 2025-12-04: 创建 (修复 P1-1 - 提取动态路由共享代码)
 *
 * @example
 * ```tsx
 * const {
 *   schema,
 *   runtime,
 *   dataSourceManager,
 *   loading,
 *   error,
 *   t,
 *   locale,
 *   navigate
 * } = useDynamicPageSetup({
 *   tableName,
 *   type: 'list',
 *   token,
 *   additionalContext: { filters, pagination }
 * });
 * ```
 */

import { useMemo } from 'react';
import { useNavigate } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';
import { useSchemaLoader } from '~/framework/meta/hooks/useSchemaLoader';
import { usePageDataSources } from '~/framework/meta/hooks/usePageDataSources';
import { useSchemaRuntime } from '~/framework/meta/hooks/useSchemaRuntime';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { fetchResult } from '~/services/http-client';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';

export type PageType = 'list' | 'form' | 'detail' | 'dashboard' | 'kanban';

export interface UseDynamicPageSetupOptions {
  /** 表名 */
  tableName: string;

  /** 页面类型 */
  type: PageType;

  /** 认证 token (可选) */
  token?: string;

  /** 额外的表达式上下文 (可选) */
  additionalContext?: Record<string, any>;

  /** 是否禁用 SchemaRuntime (某些页面可能不需要) */
  disableRuntime?: boolean;
}

export interface UseDynamicPageSetupResult {
  /** Schema 对象 */
  schema: UnifiedSchema | null;

  /** SchemaRuntime 实例 (如果 disableRuntime=false) */
  runtime: SchemaRuntime | null;

  /** DataSourceManager 实例 */
  dataSourceManager: DataSourceManager;

  /** 是否正在加载 */
  loading: boolean;

  /** 错误信息 */
  error: string | null;

  /** 翻译函数 */
  t: (key: string) => string;

  /** 当前语言 */
  locale: string;

  /** React Router navigate 函数 */
  navigate: ReturnType<typeof useNavigate>;
}

/**
 * 动态页面通用设置 Hook
 *
 * 封装了 Schema 加载、DataSourceManager 初始化、SchemaRuntime 初始化等通用逻辑
 */
export function useDynamicPageSetup(
  options: UseDynamicPageSetupOptions,
): UseDynamicPageSetupResult {
  const { tableName, type, token, additionalContext = {}, disableRuntime = false } = options;

  const navigate = useNavigate();
  const { t, locale } = useI18n();

  // 1. 加载 Schema
  const {
    schema,
    loading: schemaLoading,
    error: schemaError,
  } = useSchemaLoader({
    tableName,
    type,
    token,
  });

  // 2. 构建表达式上下文
  const expressionContext = useMemo(() => {
    return createExpressionContext({
      locale,
      global: {
        locale,
        theme: 'light',
        user: undefined,
        tenant: undefined,
        t,
      },
      t,
      fetchResult,
      ...additionalContext,
    });
  }, [locale, t, additionalContext]);

  // 3. 初始化 DataSourceManager
  const { manager: dataSourceManager } = usePageDataSources({
    context: expressionContext,
    schema,
  });

  // 4. 初始化 SchemaRuntime (可选)
  const runtime = useSchemaRuntime(
    disableRuntime || !schema || !dataSourceManager
      ? { schema: null, dataSourceManager: dataSourceManager as any, navigate, locale, t }
      : {
          schema,
          dataSourceManager,
          navigate,
          locale,
          t,
          disableAutoFetch: true,
        },
  );

  return {
    schema,
    runtime: disableRuntime ? null : runtime,
    dataSourceManager,
    loading: schemaLoading,
    error: schemaError?.message || null,
    t,
    locale,
    navigate,
  };
}
