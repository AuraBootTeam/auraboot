/**
 * useSchemaRuntime Hook
 *
 * 封装 SchemaRuntime 初始化逻辑，避免在页面中重复代码
 *
 * 使用场景:
 * - 动态路由页面 (List, New, Edit, View)
 * - 需要 SchemaRuntime 支持的任何页面
 *
 * 变更记录:
 * - 2025-12-03: 创建 (修复 P1-2)
 *
 * @example
 * ```tsx
 * const runtime = useSchemaRuntime({
 *   schema,
 *   dataSourceManager,
 *   navigate,
 *   locale,
 *   t,
 *   disableAutoFetch: true,
 * });
 * ```
 */

import React, { useState, useEffect, useCallback } from 'react';
import type { NavigateFunction } from 'react-router';
import { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';
import type { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { useToastContext } from '~/contexts/ToastContext';

export interface UseSchemaRuntimeOptions {
  /** Schema 对象 */
  schema: UnifiedSchema | null;

  /** DataSourceManager 实例 (P0-3: 必需) */
  dataSourceManager: DataSourceManager;

  /** React Router navigate 函数 */
  navigate: NavigateFunction;

  /** 当前语言 */
  locale: string;

  /** 翻译函数 */
  t: (key: string) => string;

  /** 禁用自动获取数据 (推荐 true) */
  disableAutoFetch?: boolean;

  /** 用户信息 (可选，如果不提供则使用硬编码) */
  user?: {
    id: string;
    name: string;
    email: string;
    roles: string[];
    permissions: string[];
  };

  /** 租户信息 (可选) */
  tenant?: any;
}

// createToastHandler removed — replaced by useToastContext in useSchemaRuntime

/**
 * 构建 GlobalState
 */
function buildGlobalState(options: UseSchemaRuntimeOptions) {
  return {
    locale: options.locale,
    theme: 'light',
    user: options.user || {
      id: 'current-user',
      name: 'Current User',
      email: 'user@example.com',
      roles: ['admin'],
      permissions: [
        'store:create',
        'store:update',
        'store:view',
        'store:delete',
        'store:viewAdvanced',
      ],
    },
    tenant: options.tenant || undefined,
    t: (key: string) => options.t(key),
  };
}

/**
 * useSchemaRuntime Hook
 *
 * 创建和管理 SchemaRuntime 实例
 *
 * @returns SchemaRuntime 实例或 null
 */
export function useSchemaRuntime(options: UseSchemaRuntimeOptions): SchemaRuntime | null {
  const [runtime, setRuntime] = useState<SchemaRuntime | null>(null);
  const { showSuccessToast, showErrorToast, showInfoToast } = useToastContext();
  const toastHandler = useCallback(
    (message: string, level: 'success' | 'error' | 'info' = 'info') => {
      switch (level) {
        case 'success':
          showSuccessToast(message);
          break;
        case 'error':
          showErrorToast(message);
          break;
        default:
          showInfoToast(message);
          break;
      }
    },
    [showSuccessToast, showErrorToast, showInfoToast],
  );

  const { schema, dataSourceManager, navigate, disableAutoFetch = true } = options;

  // 使用 ref 跟踪 schema ID，避免重复创建
  const schemaIdRef = React.useRef<string | null>(null);

  useEffect(() => {
    // 只有当 schema 存在时才初始化
    if (!schema) {
      return;
    }

    // 如果 schema ID 没变，不重新创建 runtime
    if (schemaIdRef.current === schema.id && runtime) {
      return;
    }

    // 如果有旧的 runtime，先销毁
    if (runtime) {
      runtime.destroy();
      setRuntime(null);
    }

    // 记录当前 schema ID
    schemaIdRef.current = schema.id;

    // P0-3: 创建 SchemaRuntime 实例 (dataSourceManager 必需)
    const rt = new SchemaRuntime({
      schema,
      globalState: buildGlobalState(options),
      navigate,
      showToast: toastHandler,
      dataSourceManager, // P0-3: 必需参数
      disableAutoFetch,
    });

    setRuntime(rt);

    // 清理: 销毁 runtime (仅在组件卸载时)
    return () => {
      rt.destroy();
    };
    // 只依赖 schema 和 dataSourceManager，避免不必要的重建
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schema?.id, dataSourceManager]);

  return runtime;
}
