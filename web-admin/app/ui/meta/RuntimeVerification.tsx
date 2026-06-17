/**
 * Runtime Verification Component
 *
 * 显示Model的运行时闭环验证结果,包括:
 * - 动态页面生成状态
 * - 菜单配置状态
 * - 权限映射状态
 * - 页面访问测试
 * - Field配置验证
 * - Dict关联验证
 * - 权限控制验证
 */

import React, { useState, useEffect } from 'react';
import {
  CheckCircleIcon,
  XCircleIcon,
  ExclamationTriangleIcon,
  ArrowPathIcon,
  EyeIcon,
} from '@heroicons/react/24/outline';
import type { MetaModelDTO, ModelFieldBinding } from '~/types/model';
import { type RuntimeVerificationResult, templateService } from '~/shared/services/templateService';

interface RuntimeVerificationProps {
  model: MetaModelDTO;
  fields: ModelFieldBinding[];
  onRefresh?: () => void;
}

/**
 * Runtime Verification Component
 */
export const RuntimeVerification: React.FC<RuntimeVerificationProps> = ({
  model,
  fields,
  onRefresh,
}) => {
  const [loading, setLoading] = useState(false);
  const [verificationResult, setVerificationResult] = useState<RuntimeVerificationResult | null>(
    null,
  );
  const [error, setError] = useState<string | null>(null);

  /**
   * 执行运行时验证
   */
  const runVerification = async () => {
    setLoading(true);
    setError(null);

    try {
      const result = await templateService.verifyRuntimeLoop(model, fields);
      setVerificationResult(result);
    } catch (err) {
      console.error('Runtime verification failed:', err);
      setError(err instanceof Error ? err.message : 'Verification failed');
    } finally {
      setLoading(false);
    }
  };

  /**
   * 组件挂载时自动执行验证
   */
  useEffect(() => {
    runVerification();
  }, [model.code]);

  /**
   * 渲染状态图标
   */
  const renderStatusIcon = (success: boolean) => {
    if (success) {
      return <CheckCircleIcon className="h-5 w-5 text-green-500" />;
    }
    return <XCircleIcon className="text-status-red h-5 w-5" />;
  };

  /**
   * 渲染页面访问链接
   */
  const renderPageLink = (url: string, label: string) => {
    return (
      <a
        href={url}
        target="_blank"
        rel="noopener noreferrer"
        className="text-accent inline-flex items-center gap-1 hover:text-blue-800 hover:underline"
      >
        <EyeIcon className="h-4 w-4" />
        {label}
      </a>
    );
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <ArrowPathIcon className="text-accent h-8 w-8 animate-spin" />
        <span className="text-text-2 ml-3">正在验证运行时闭环...</span>
      </div>
    );
  }

  if (error) {
    return (
      <div className="rounded-card bg-status-red-bg p-4">
        <div className="flex">
          <XCircleIcon className="h-5 w-5 text-red-400" />
          <div className="ml-3">
            <h3 className="text-sm font-medium text-red-800">验证失败</h3>
            <div className="mt-2 text-sm text-red-700">{error}</div>
            <button
              onClick={runVerification}
              className="text-status-red hover:text-status-red mt-3 text-sm font-medium"
            >
              重试
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (!verificationResult) {
    return null;
  }

  return (
    <div className="space-y-6">
      {/* 总体状态 */}
      <div
        className={`rounded-card p-4 ${
          verificationResult.success
            ? 'border-status-green bg-status-green-bg border'
            : 'border-status-amber bg-status-amber-bg border'
        }`}
      >
        <div className="flex items-start">
          {verificationResult.success ? (
            <CheckCircleIcon className="mt-0.5 h-6 w-6 text-green-500" />
          ) : (
            <ExclamationTriangleIcon className="text-status-amber mt-0.5 h-6 w-6" />
          )}
          <div className="ml-3 flex-1">
            <h3
              className={`text-sm font-medium ${
                verificationResult.success ? 'text-green-800' : 'text-status-amber'
              }`}
            >
              {verificationResult.success ? '运行时闭环验证通过' : '运行时闭环验证部分通过'}
            </h3>
            <div
              className={`mt-2 text-sm ${
                verificationResult.success ? 'text-status-green' : 'text-status-amber'
              }`}
            >
              {verificationResult.success
                ? 'Model已成功配置并可以在运行时使用。所有动态页面、菜单和权限配置均已生效。'
                : '部分配置存在问题,请查看下方详情并修复。'}
            </div>
          </div>
          <button
            onClick={runVerification}
            className="text-text-2 hover:text-text ml-3 text-sm font-medium"
            title="刷新验证结果"
          >
            <ArrowPathIcon className="h-5 w-5" />
          </button>
        </div>
      </div>

      {/* 生成的页面 */}
      <div className="rounded-card border-border bg-panel border p-4">
        <h4 className="text-text mb-3 text-sm font-medium">生成的动态页面</h4>
        <div className="space-y-2">
          {verificationResult.generatedPages.list && (
            <div className="bg-subtle flex items-center justify-between rounded px-3 py-2">
              <div className="flex items-center gap-2">
                {renderStatusIcon(true)}
                <span className="text-text-2 text-sm">列表页</span>
              </div>
              {renderPageLink(`/p/${model.code}`, '访问列表页')}
            </div>
          )}
          {verificationResult.generatedPages.form && (
            <div className="bg-subtle flex items-center justify-between rounded px-3 py-2">
              <div className="flex items-center gap-2">
                {renderStatusIcon(true)}
                <span className="text-text-2 text-sm">表单页</span>
              </div>
              {renderPageLink(`/p/${model.code}/new`, '访问表单页')}
            </div>
          )}
          {verificationResult.generatedPages.detail && (
            <div className="bg-subtle flex items-center justify-between rounded px-3 py-2">
              <div className="flex items-center gap-2">
                {renderStatusIcon(true)}
                <span className="text-text-2 text-sm">详情页</span>
              </div>
              {renderPageLink(`/p/${model.code}/view/example`, '访问详情页')}
            </div>
          )}
        </div>
      </div>

      {/* 菜单配置 */}
      {verificationResult.menuPath && (
        <div className="rounded-card border-border bg-panel border p-4">
          <h4 className="text-text mb-3 text-sm font-medium">菜单配置</h4>
          <div className="bg-subtle flex items-center gap-2 rounded px-3 py-2">
            {renderStatusIcon(true)}
            <span className="text-text-2 text-sm">
              菜单路径: <code className="text-accent">{verificationResult.menuPath}</code>
            </span>
          </div>
        </div>
      )}

      {/* 权限配置 */}
      {verificationResult.permissions.length > 0 && (
        <div className="rounded-card border-border bg-panel border p-4">
          <h4 className="text-text mb-3 text-sm font-medium">权限配置</h4>
          <div className="space-y-2">
            {verificationResult.permissions.map((permission, index) => (
              <div key={index} className="bg-subtle flex items-center gap-2 rounded px-3 py-2">
                {renderStatusIcon(true)}
                <span className="text-text-2 text-sm">
                  <code className="text-accent">{permission}</code>
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 错误信息 */}
      {verificationResult.errors && verificationResult.errors.length > 0 && (
        <div className="rounded-card bg-panel border border-red-200 p-4">
          <h4 className="mb-3 text-sm font-medium text-red-900">错误</h4>
          <ul className="space-y-2">
            {verificationResult.errors.map((error, index) => (
              <li key={index} className="flex items-start gap-2 text-sm text-red-700">
                <XCircleIcon className="mt-0.5 h-4 w-4 flex-shrink-0" />
                <span>{error}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* 警告信息 */}
      {verificationResult.warnings && verificationResult.warnings.length > 0 && (
        <div className="rounded-card border-status-amber bg-panel border p-4">
          <h4 className="text-status-amber mb-3 text-sm font-medium">警告</h4>
          <ul className="space-y-2">
            {verificationResult.warnings.map((warning, index) => (
              <li key={index} className="text-status-amber flex items-start gap-2 text-sm">
                <ExclamationTriangleIcon className="mt-0.5 h-4 w-4 flex-shrink-0" />
                <span>{warning}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* 操作按钮 */}
      <div className="border-border flex items-center justify-between border-t pt-4">
        <button
          onClick={runVerification}
          className="rounded-control border-border-strong bg-panel text-text-2 hover:bg-subtle inline-flex items-center gap-2 border px-4 py-2 text-sm font-medium"
        >
          <ArrowPathIcon className="h-4 w-4" />
          重新验证
        </button>
        {onRefresh && (
          <button
            onClick={onRefresh}
            className="rounded-control bg-accent hover:bg-accent-hover inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white"
          >
            刷新Model数据
          </button>
        )}
      </div>
    </div>
  );
};

export default RuntimeVerification;
