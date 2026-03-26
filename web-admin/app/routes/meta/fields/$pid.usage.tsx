/**
 * Field Usage Page
 *
 * Display field usage information across models
 *
 * Features:
 * - View all models using this field
 * - View binding configurations
 * - Refresh usage cache
 * - Navigate to model details
 */

import React, { useState, useCallback } from 'react';
import { useLoaderData, useNavigate, useParams } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { fieldLibraryService } from '~/services/fieldLibraryService';
import { fieldService } from '~/services/fieldService';
import { useToastContext } from '~/contexts/ToastContext';
import type { FieldUsageInfo, BindingConfiguration, MetaFieldDTO } from '~/types/fieldLibrary';
import { LoadingSpinner } from '~/components/LoadingSpinner';
import { ErrorAlert } from '~/components/ErrorAlert';

/**
 * Loader function - Load field usage data
 */
export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  try {
    const { pid } = params;
    if (!pid) {
      throw new Error('Field PID is required');
    }

    // Load field info and usage data
    const [field, usageInfo, bindings] = await Promise.all([
      fieldService.getFieldByPid(pid, request),
      fieldLibraryService.getFieldUsage(pid, request),
      fieldLibraryService.getBindingConfigurations(pid, request),
    ]);

    return {
      field,
      usageInfo,
      bindings,
    };
  } catch (error) {
    console.error('Failed to load field usage:', error);
    return {
      field: null,
      usageInfo: null,
      bindings: [],
      error: error instanceof Error ? error.message : 'Failed to load field usage',
    };
  }
};

/**
 * Field Usage Page Component
 */
export default function FieldUsagePage() {
  const loaderData = useLoaderData<typeof loader>();
  const { field, usageInfo, bindings } = loaderData;
  const loaderError = 'error' in loaderData ? loaderData.error : null;

  const navigate = useNavigate();
  const params = useParams();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const [refreshing, setRefreshing] = useState(false);

  /**
   * Refresh usage cache
   */
  const handleRefresh = useCallback(async () => {
    if (!params.pid) return;

    setRefreshing(true);
    try {
      await fieldLibraryService.refreshFieldUsageCache(params.pid);
      showSuccessToast('使用情况已刷新');
      // Reload page
      window.location.reload();
    } catch (error) {
      console.error('Failed to refresh usage cache:', error);
      showErrorToast('刷新失败');
    } finally {
      setRefreshing(false);
    }
  }, [params.pid, showSuccessToast, showErrorToast]);

  /**
   * Navigate to model details
   */
  const handleViewModel = useCallback(
    (modelPid: string) => {
      navigate(`/meta/models/${modelPid}`);
    },
    [navigate],
  );

  /**
   * Back to field library
   */
  const handleBack = useCallback(() => {
    navigate('/meta/fields');
  }, [navigate]);

  // Render error state
  if (loaderError) {
    return (
      <div className="p-6">
        <ErrorAlert error={loaderError} />
        <button
          onClick={handleBack}
          className="mt-4 rounded-md bg-gray-200 px-4 py-2 text-gray-700 hover:bg-gray-300"
        >
          返回字段库
        </button>
      </div>
    );
  }

  // Render loading state
  if (!field || !usageInfo) {
    return <LoadingSpinner />;
  }

  return (
    <div className="p-6">
      {/* Page header */}
      <div className="mb-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">字段使用情况</h1>
            <p className="mt-1 text-sm text-gray-500">
              查看字段 <span className="font-medium">{field.code}</span> 在各个模型中的使用情况
            </p>
          </div>
          <div className="flex gap-2">
            <button
              onClick={handleRefresh}
              disabled={refreshing}
              className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:opacity-50"
            >
              {refreshing ? '刷新中...' : '刷新缓存'}
            </button>
            <button
              onClick={handleBack}
              className="rounded-md bg-gray-200 px-4 py-2 text-gray-700 hover:bg-gray-300 focus:ring-2 focus:ring-gray-500 focus:outline-none"
            >
              返回
            </button>
          </div>
        </div>
      </div>

      {/* Field info card */}
      <div className="mb-6 rounded-lg bg-white p-6 shadow">
        <h2 className="mb-4 text-lg font-semibold text-gray-900">字段信息</h2>
        <div className="grid grid-cols-4 gap-4">
          <div>
            <div className="text-sm text-gray-500">字段编码</div>
            <div className="mt-1 text-sm font-medium text-gray-900">{field.code}</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">数据类型</div>
            <div className="mt-1 text-sm font-medium text-gray-900">{field.dataType}</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">状态</div>
            <div className="mt-1">
              <span
                className={`rounded-full px-2 py-1 text-xs ${
                  field.status === 'published'
                    ? 'bg-green-100 text-green-800'
                    : field.status === 'draft'
                      ? 'bg-yellow-100 text-yellow-800'
                      : 'bg-gray-100 text-gray-800'
                }`}
              >
                {field.status === 'published'
                  ? '已发布'
                  : field.status === 'draft'
                    ? '草稿'
                    : field.status}
              </span>
            </div>
          </div>
          <div>
            <div className="text-sm text-gray-500">总使用次数</div>
            <div className="mt-1 text-sm font-medium text-gray-900">
              {usageInfo.totalUsageCount}
            </div>
          </div>
        </div>
      </div>

      {/* Usage summary */}
      <div className="mb-6 rounded-lg bg-white p-6 shadow">
        <h2 className="mb-4 text-lg font-semibold text-gray-900">使用统计</h2>
        <div className="grid grid-cols-3 gap-4">
          <div className="rounded-lg bg-blue-50 p-4 text-center">
            <div className="text-3xl font-bold text-blue-600">{usageInfo.modelUsages.length}</div>
            <div className="mt-1 text-sm text-gray-600">使用的模型数</div>
          </div>
          <div className="rounded-lg bg-green-50 p-4 text-center">
            <div className="text-3xl font-bold text-green-600">
              {usageInfo.modelUsages.filter((u) => u.required).length}
            </div>
            <div className="mt-1 text-sm text-gray-600">必填绑定数</div>
          </div>
          <div className="rounded-lg bg-purple-50 p-4 text-center">
            <div className="text-3xl font-bold text-purple-600">
              {usageInfo.modelUsages.filter((u) => u.aliasCode).length}
            </div>
            <div className="mt-1 text-sm text-gray-600">使用别名数</div>
          </div>
        </div>
      </div>

      {/* Model usages table */}
      <div className="mb-6 rounded-lg bg-white shadow">
        <div className="border-b border-gray-200 px-6 py-4">
          <h2 className="text-lg font-semibold text-gray-900">模型使用列表</h2>
        </div>
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  模型编码
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  模型名称
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  别名
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  必填
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  可见
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  可编辑
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  排序
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  创建时间
                </th>
                <th className="px-6 py-3 text-right text-xs font-medium tracking-wider text-gray-500 uppercase">
                  操作
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 bg-white">
              {usageInfo.modelUsages.length === 0 ? (
                <tr>
                  <td colSpan={9} className="px-6 py-12 text-center text-gray-500">
                    该字段暂未被任何模型使用
                  </td>
                </tr>
              ) : (
                usageInfo.modelUsages.map((usage) => (
                  <tr key={usage.bindingId} className="hover:bg-gray-50">
                    <td className="px-6 py-4 text-sm font-medium whitespace-nowrap text-gray-900">
                      {usage.modelCode}
                    </td>
                    <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-900">
                      {usage.modelName}
                    </td>
                    <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                      {usage.aliasCode || '-'}
                    </td>
                    <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                      {usage.required ? (
                        <span className="text-red-600">是</span>
                      ) : (
                        <span className="text-gray-400">否</span>
                      )}
                    </td>
                    <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                      {usage.visible ? '是' : '否'}
                    </td>
                    <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                      {usage.editable ? '是' : '否'}
                    </td>
                    <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                      {usage.fieldOrder || '-'}
                    </td>
                    <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                      {new Date(usage.createdAt).toLocaleDateString('zh-CN')}
                    </td>
                    <td className="px-6 py-4 text-right text-sm font-medium whitespace-nowrap">
                      <button
                        onClick={() => handleViewModel(usage.modelPid)}
                        className="text-blue-600 hover:text-blue-900"
                      >
                        查看模型
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Binding configurations */}
      {bindings.length > 0 && (
        <div className="rounded-lg bg-white shadow">
          <div className="border-b border-gray-200 px-6 py-4">
            <h2 className="text-lg font-semibold text-gray-900">绑定配置详情</h2>
          </div>
          <div className="p-6">
            <div className="space-y-4">
              {bindings.map((binding) => (
                <div key={binding.bindingId} className="rounded-lg border border-gray-200 p-4">
                  <div className="mb-3 flex items-center justify-between">
                    <div className="font-medium text-gray-900">
                      {binding.modelCode} {binding.aliasCode && `(别名: ${binding.aliasCode})`}
                    </div>
                    <div className="text-sm text-gray-500">绑定ID: {binding.bindingId}</div>
                  </div>
                  <div className="grid grid-cols-4 gap-4 text-sm">
                    <div>
                      <span className="text-gray-500">必填:</span>{' '}
                      <span className={binding.required ? 'text-red-600' : 'text-gray-900'}>
                        {binding.required ? '是' : '否'}
                      </span>
                    </div>
                    <div>
                      <span className="text-gray-500">可空:</span>{' '}
                      <span className="text-gray-900">{binding.nullable ? '是' : '否'}</span>
                    </div>
                    <div>
                      <span className="text-gray-500">只读:</span>{' '}
                      <span className="text-gray-900">{binding.readonly ? '是' : '否'}</span>
                    </div>
                    <div>
                      <span className="text-gray-500">排序:</span>{' '}
                      <span className="text-gray-900">{binding.fieldOrder || '-'}</span>
                    </div>
                  </div>
                  {binding.defaultValue && (
                    <div className="mt-2 text-sm">
                      <span className="text-gray-500">默认值:</span>{' '}
                      <span className="text-gray-900">{binding.defaultValue}</span>
                    </div>
                  )}
                  {binding.remarks && (
                    <div className="mt-2 text-sm text-gray-600">{binding.remarks}</div>
                  )}
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
