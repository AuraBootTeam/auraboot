/**
 * ErrorAlert - 统一的错误提示组件
 *
 * 用于显示加载失败、操作错误等场景
 *
 * 变更记录:
 * - 2025-12-03: 创建 (修复 P1-6)
 *
 * @example
 * ```tsx
 * <ErrorAlert
 *   error="加载数据失败"
 *   onRetry={() => window.location.reload()}
 * />
 * ```
 */

import React from 'react';

export interface ErrorAlertProps {
  /** 错误信息 */
  error: string;

  /** 重试回调 (可选) */
  onRetry?: () => void;

  /** 标题 (可选) */
  title?: string;
}

export const ErrorAlert: React.FC<ErrorAlertProps> = ({ error, onRetry, title = '加载失败' }) => {
  return (
    <div className="rounded-lg border border-red-200 bg-red-50 p-6">
      <h3 className="mb-2 text-lg font-medium text-red-800">{title}</h3>
      <p className="mb-4 text-red-600">{error}</p>
      {onRetry && (
        <button
          onClick={onRetry}
          className="rounded-md bg-red-600 px-4 py-2 text-white hover:bg-red-700"
        >
          重试
        </button>
      )}
    </div>
  );
};

export default ErrorAlert;
