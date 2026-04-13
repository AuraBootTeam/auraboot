/**
 * Field Detail Page
 *
 * Displays detailed information about a field from the field library.
 *
 * Features:
 * - Basic field information
 * - Usage tracking (which models use this field)
 * - Impact analysis (what happens if field is modified/deleted)
 * - Edit and delete actions
 */

import React, { useState, useCallback } from 'react';
import { useNavigate, useParams, useLoaderData, Link } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { fieldService } from '~/services/fieldService';
import { confirmDialog } from '~/utils/confirmDialog';
import { fieldLibraryService } from '~/services/fieldLibraryService';
import { useToastContext } from '~/contexts/ToastContext';
import type { MetaFieldDTO } from '~/types/model';
import type { FieldUsageInfo, FieldImpactAnalysis } from '~/types/fieldLibrary';

/**
 * Tab type definition
 */
type TabType = 'basic' | 'usage' | 'impact';

/**
 * Loader function - loads field data
 */
export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const { pid } = params;

  if (!pid) {
    throw new Response('Field PID is required', { status: 400 });
  }

  try {
    const field = await fieldService.getFieldByPid(pid, request);

    // Load usage and impact data in parallel
    let usage: FieldUsageInfo | null = null;
    let impact: FieldImpactAnalysis | null = null;

    try {
      [usage, impact] = await Promise.all([
        fieldLibraryService.getFieldUsage(pid, request),
        fieldLibraryService.analyzeFieldImpact(pid, request),
      ]);
    } catch (e) {
      // Non-critical, continue without usage/impact data
      console.warn('Failed to load usage/impact data:', e);
    }

    return { field, usage, impact };
  } catch (error) {
    console.error('Failed to load field details:', error);
    throw new Response('Field not found', { status: 404 });
  }
};

/**
 * Data type display mapping
 */
const DATA_TYPE_LABELS: Record<string, string> = {
  STRING: '字符串',
  TEXT: '文本',
  INTEGER: '整数',
  LONG: '长整数',
  DECIMAL: '小数',
  BOOLEAN: '布尔',
  DATE: '日期',
  DATETIME: '日期时间',
  TIME: '时间',
  JSON: 'json',
  ARRAY: '数组',
  REFERENCE: '引用',
  ENUM: '枚举',
};

/**
 * Status badge component
 */
function StatusBadge({ status }: { status: string }) {
  const colorMap: Record<string, string> = {
    published: 'bg-green-100 text-green-800',
    draft: 'bg-yellow-100 text-yellow-800',
    deprecated: 'bg-red-100 text-red-800',
  };

  const labelMap: Record<string, string> = {
    published: '已发布',
    draft: '草稿',
    deprecated: '已弃用',
  };

  return (
    <span
      className={`inline-flex rounded-full px-2 py-1 text-xs font-medium ${colorMap[status] || 'bg-gray-100 text-gray-800'}`}
    >
      {labelMap[status] || status}
    </span>
  );
}

/**
 * Field Detail Page Component
 */
export default function FieldDetailPage() {
  const navigate = useNavigate();
  const { pid } = useParams();
  const { field, usage, impact } = useLoaderData<typeof loader>();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const [activeTab, setActiveTab] = useState<TabType>('basic');
  const [loading, setLoading] = useState(false);

  /**
   * Handle field deletion
   */
  const handleDelete = useCallback(async () => {
    // First validate if deletion is allowed
    if (impact && !impact.canDelete) {
      showErrorToast(`无法删除此字段: ${impact.blockingReasons?.join(', ') || '存在依赖'}`);
      return;
    }

    const confirmed = await confirmDialog({
      content: `确定要删除字段 "${field.code}" 吗？此操作不可恢复。`,
      variant: 'danger',
    });

    if (!confirmed) return;

    setLoading(true);
    try {
      await fieldService.deleteField(pid!);
      showSuccessToast('字段删除成功');
      navigate('/meta/fields');
    } catch (error) {
      console.error('Failed to delete field:', error);
      showErrorToast('删除字段失败');
    } finally {
      setLoading(false);
    }
  }, [pid, field, impact, navigate, showSuccessToast, showErrorToast]);

  /**
   * Handle refresh usage cache
   */
  const handleRefreshUsage = useCallback(async () => {
    setLoading(true);
    try {
      await fieldLibraryService.refreshFieldUsageCache(pid!);
      showSuccessToast('使用情况缓存已刷新');
      window.location.reload();
    } catch (error) {
      console.error('Failed to refresh usage cache:', error);
      showErrorToast('刷新使用情况失败');
    } finally {
      setLoading(false);
    }
  }, [pid, showSuccessToast, showErrorToast]);

  return (
    <div className="w-full p-6">
      {/* Page header */}
      <div className="mb-6 flex items-start justify-between">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold text-gray-900">{field.code}</h1>
            <StatusBadge status={field.status} />
          </div>
          <p className="mt-1 text-sm text-gray-500">
            数据类型:{' '}
            <span className="font-medium">
              {DATA_TYPE_LABELS[field.dataType] || field.dataType}
            </span>
            {field.description && ` · ${field.description}`}
          </p>
        </div>

        <div className="flex gap-2">
          <Link
            to={`/meta/fields/${pid}/usage`}
            className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
          >
            使用情况
          </Link>
          <Link
            to={`/meta/fields/${pid}/impact`}
            className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
          >
            影响分析
          </Link>
          <button
            onClick={handleDelete}
            className="rounded-md border border-red-300 px-4 py-2 text-red-700 hover:bg-red-50 focus:ring-2 focus:ring-red-500 focus:outline-none"
            disabled={loading}
          >
            删除
          </button>
        </div>
      </div>

      {/* Tab navigation */}
      <div className="rounded-lg bg-white shadow">
        <div className="border-b border-gray-200">
          <nav className="-mb-px flex">
            <button
              onClick={() => setActiveTab('basic')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'basic'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              基本信息
            </button>
            <button
              onClick={() => setActiveTab('usage')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'usage'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              使用情况 {usage && `(${usage.totalUsageCount || 0})`}
            </button>
            <button
              onClick={() => setActiveTab('impact')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'impact'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              影响分析
            </button>
          </nav>
        </div>

        {/* Tab content */}
        <div className="p-6">
          {/* Basic Info Tab */}
          {activeTab === 'basic' && (
            <div className="space-y-6">
              <div className="grid grid-cols-2 gap-6">
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">字段编码</label>
                  <div className="font-mono text-sm text-gray-900">{field.code}</div>
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">数据类型</label>
                  <div className="text-sm text-gray-900">
                    {DATA_TYPE_LABELS[field.dataType] || field.dataType}
                  </div>
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">状态</label>
                  <StatusBadge status={field.status} />
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">版本</label>
                  <div className="text-sm text-gray-900">{field.version || 1}</div>
                </div>

                {/* Feature flags */}
                <div className="col-span-2">
                  <label className="mb-2 block text-sm font-medium text-gray-700">字段特性</label>
                  <div className="flex gap-2">
                    {field.feature?.required && (
                      <span className="inline-flex rounded bg-red-100 px-2 py-1 text-xs font-medium text-red-800">
                        必填
                      </span>
                    )}
                    {field.feature?.unique && (
                      <span className="inline-flex rounded bg-purple-100 px-2 py-1 text-xs font-medium text-purple-800">
                        唯一
                      </span>
                    )}
                    {field.feature?.indexed && (
                      <span className="inline-flex rounded bg-blue-100 px-2 py-1 text-xs font-medium text-blue-800">
                        索引
                      </span>
                    )}
                    {!field.feature?.required &&
                      !field.feature?.unique &&
                      !field.feature?.indexed && (
                        <span className="text-sm text-gray-500">无特殊特性</span>
                      )}
                  </div>
                </div>

                {field.description && (
                  <div className="col-span-2">
                    <label className="mb-1 block text-sm font-medium text-gray-700">描述</label>
                    <div className="text-sm text-gray-900">{field.description}</div>
                  </div>
                )}

                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">创建时间</label>
                  <div className="text-sm text-gray-900">
                    {field.createdAt ? new Date(field.createdAt).toLocaleString() : '-'}
                  </div>
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">更新时间</label>
                  <div className="text-sm text-gray-900">
                    {field.updatedAt ? new Date(field.updatedAt).toLocaleString() : '-'}
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Usage Tab */}
          {activeTab === 'usage' && (
            <div>
              <div className="mb-4 flex items-center justify-between">
                <h3 className="text-lg font-medium text-gray-900">使用此字段的模型</h3>
                <button
                  onClick={handleRefreshUsage}
                  className="text-sm text-blue-600 hover:text-blue-800"
                  disabled={loading}
                >
                  刷新缓存
                </button>
              </div>

              {!usage || !usage.modelUsages || usage.modelUsages.length === 0 ? (
                <div className="py-12 text-center">
                  <p className="text-gray-500">此字段尚未被任何模型使用</p>
                </div>
              ) : (
                <div className="space-y-3">
                  {usage.modelUsages.map((model: any) => (
                    <div
                      key={model.modelPid || model.modelCode}
                      className="rounded-lg border border-gray-200 p-4"
                    >
                      <div className="flex items-center justify-between">
                        <div>
                          <h4 className="text-sm font-medium text-gray-900">
                            {model.modelDisplayName || model.modelCode}
                          </h4>
                          <p className="font-mono text-xs text-gray-500">{model.modelCode}</p>
                        </div>
                        <Link
                          to={`/meta/models/${model.modelPid}`}
                          className="text-sm text-blue-600 hover:text-blue-800"
                        >
                          查看模型
                        </Link>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* Impact Tab */}
          {activeTab === 'impact' && (
            <div>
              {!impact ? (
                <div className="py-12 text-center">
                  <p className="text-gray-500">无法获取影响分析数据</p>
                </div>
              ) : (
                <div className="space-y-6">
                  {/* Deletion status */}
                  <div
                    className={`rounded-lg p-4 ${impact.canDelete ? 'bg-green-50' : 'bg-red-50'}`}
                  >
                    <h4 className="mb-2 text-sm font-medium">
                      {impact.canDelete ? '可以安全删除' : '不可删除'}
                    </h4>
                    {!impact.canDelete && impact.blockingReasons && (
                      <ul className="list-inside list-disc text-sm text-red-700">
                        {impact.blockingReasons.map((reason: string, i: number) => (
                          <li key={i}>{reason}</li>
                        ))}
                      </ul>
                    )}
                  </div>

                  {/* Risk assessment */}
                  {impact.riskLevel && (
                    <div>
                      <h4 className="mb-2 text-sm font-medium text-gray-700">风险等级</h4>
                      <span
                        className={`inline-flex rounded-full px-3 py-1 text-sm font-medium ${
                          impact.riskLevel === 'high'
                            ? 'bg-red-100 text-red-800'
                            : impact.riskLevel === 'medium'
                              ? 'bg-yellow-100 text-yellow-800'
                              : 'bg-green-100 text-green-800'
                        }`}
                      >
                        {impact.riskLevel === 'high'
                          ? '高风险'
                          : impact.riskLevel === 'medium'
                            ? '中风险'
                            : '低风险'}
                      </span>
                    </div>
                  )}

                  {/* Affected items */}
                  {impact.affectedModels && impact.affectedModels.length > 0 && (
                    <div>
                      <h4 className="mb-2 text-sm font-medium text-gray-700">受影响的模型</h4>
                      <div className="flex flex-wrap gap-2">
                        {impact.affectedModels.map((model, i: number) => (
                          <span
                            key={i}
                            className="inline-flex rounded bg-gray-100 px-2 py-1 text-sm"
                          >
                            {typeof model === 'string' ? model : model.modelName || model.modelCode}
                          </span>
                        ))}
                      </div>
                    </div>
                  )}

                  {impact.affectedPages && impact.affectedPages.length > 0 && (
                    <div>
                      <h4 className="mb-2 text-sm font-medium text-gray-700">受影响的页面</h4>
                      <div className="flex flex-wrap gap-2">
                        {impact.affectedPages.map((page, i: number) => (
                          <span
                            key={i}
                            className="inline-flex rounded bg-gray-100 px-2 py-1 text-sm"
                          >
                            {typeof page === 'string' ? page : page.pageName || page.pageCode}
                          </span>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Back link */}
      <div className="mt-6">
        <Link to="/meta/fields" className="text-sm text-blue-600 hover:text-blue-800">
          &larr; 返回字段列表
        </Link>
      </div>
    </div>
  );
}
