/**
 * LoadingSpinner - 统一的加载中提示组件
 *
 * 用于显示数据加载、提交等待等场景
 *
 * 变更记录:
 * - 2025-12-03: 创建 (修复 P1-7)
 *
 * @example
 * ```tsx
 * <LoadingSpinner message="加载中..." />
 * ```
 */

import React from 'react';

export interface LoadingSpinnerProps {
  /** 提示信息 */
  message?: string;
}

export const LoadingSpinner: React.FC<LoadingSpinnerProps> = ({ message = '加载中...' }) => {
  return (
    <div className="flex items-center justify-center p-8">
      <div className="flex items-center space-x-2">
        <span className="loading loading-spinner loading-lg"></span>
        <span className="text-gray-600">{message}</span>
      </div>
    </div>
  );
};

export default LoadingSpinner;
