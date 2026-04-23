/**
 * Field Impact Analysis Page
 *
 * Analyze the impact of modifying or deleting a field
 *
 * Features:
 * - View impact level and affected components
 * - View breaking changes
 * - Get recommendations
 * - Validate modifications
 * - Validate deletion
 */

import React, { useState, useCallback } from 'react';
import { useLoaderData, useNavigate, useParams, type LoaderFunctionArgs } from 'react-router';
import { fieldLibraryService } from '~/shared/services/fieldLibraryService';
import { fieldService } from '~/shared/services/fieldService';
import { useToastContext } from '~/contexts/ToastContext';
import { LoadingSpinner } from '~/ui/LoadingSpinner';
import { ErrorAlert } from '~/ui/ErrorAlert';

/**
 * Loader function - Load field impact analysis
 */
export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  try {
    const { pid } = params;
    if (!pid) {
      throw new Error('Field PID is required');
    }

    // Load field info and impact analysis
    const [field, impactAnalysis] = await Promise.all([
      fieldService.getFieldByPid(pid, request),
      fieldLibraryService.analyzeFieldImpact(pid, request),
    ]);

    return {
      field,
      impactAnalysis,
    };
  } catch (error) {
    console.error('Failed to load field impact analysis:', error);
    return {
      field: null,
      impactAnalysis: null,
      error: error instanceof Error ? error.message : 'Failed to load field impact analysis',
    };
  }
};

/**
 * Field Impact Analysis Page Component
 */
export default function FieldImpactPage() {
  const loaderData = useLoaderData<typeof loader>();
  const { field, impactAnalysis } = loaderData;
  const loaderError = 'error' in loaderData ? loaderData.error : null;

  const navigate = useNavigate();
  const params = useParams();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const [validating, setValidating] = useState(false);
  const [validationResult, setValidationResult] = useState<{
    type: 'modify' | 'delete';
    result: any;
  } | null>(null);

  /**
   * Validate field modification
   */
  const handleValidateModification = useCallback(async () => {
    if (!params.pid) return;

    setValidating(true);
    try {
      // Example modifications - in real app, this would come from a form
      const modifications = {
        dataType: field?.dataType,
        required: field?.required,
      };

      const result = await fieldLibraryService.validateFieldModification(params.pid, modifications);
      setValidationResult({ type: 'modify', result });

      if (result.valid) {
        showSuccessToast('字段修改验证通过');
      } else {
        showErrorToast(`字段修改验证失败: ${result.issues.join(', ')}`);
      }
    } catch (error) {
      console.error('Failed to validate modification:', error);
      showErrorToast('验证失败');
    } finally {
      setValidating(false);
    }
  }, [params.pid, field, showSuccessToast, showErrorToast]);

  /**
   * Validate field deletion
   */
  const handleValidateDeletion = useCallback(async () => {
    if (!params.pid) return;

    setValidating(true);
    try {
      const result = await fieldLibraryService.validateFieldDeletion(params.pid);
      setValidationResult({ type: 'delete', result });

      if (result.canDelete) {
        showSuccessToast('字段可以安全删除');
      } else {
        showErrorToast(`字段无法删除: ${result.blockingReasons.join(', ')}`);
      }
    } catch (error) {
      console.error('Failed to validate deletion:', error);
      showErrorToast('验证失败');
    } finally {
      setValidating(false);
    }
  }, [params.pid, showSuccessToast, showErrorToast]);

  /**
   * Back to field library
   */
  const handleBack = useCallback(() => {
    navigate('/meta/fields');
  }, [navigate]);

  /**
   * Get impact level color
   */
  const getImpactLevelColor = (level: string) => {
    switch (level) {
      case 'low':
        return 'bg-green-100 text-green-800';
      case 'medium':
        return 'bg-yellow-100 text-yellow-800';
      case 'high':
        return 'bg-orange-100 text-orange-800';
      case 'critical':
        return 'bg-red-100 text-red-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  };

  /**
   * Get impact level text
   */
  const getImpactLevelText = (level: string) => {
    switch (level) {
      case 'low':
        return '低';
      case 'medium':
        return '中';
      case 'high':
        return '高';
      case 'critical':
        return '严重';
      default:
        return level;
    }
  };

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
  if (!field || !impactAnalysis) {
    return <LoadingSpinner />;
  }

  return (
    <div className="p-6">
      {/* Page header */}
      <div className="mb-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">字段影响分析</h1>
            <p className="mt-1 text-sm text-gray-500">
              分析字段 <span className="font-medium">{field.code}</span> 的修改或删除影响
            </p>
          </div>
          <button
            onClick={handleBack}
            className="rounded-md bg-gray-200 px-4 py-2 text-gray-700 hover:bg-gray-300 focus:ring-2 focus:ring-gray-500 focus:outline-none"
          >
            返回
          </button>
        </div>
      </div>

      {/* Impact summary card */}
      <div className="mb-6 rounded-lg bg-white p-6 shadow">
        <h2 className="mb-4 text-lg font-semibold text-gray-900">影响概览</h2>
        <div className="grid grid-cols-4 gap-4">
          <div>
            <div className="text-sm text-gray-500">影响等级</div>
            <div className="mt-1">
              <span
                className={`rounded-full px-3 py-1 text-sm font-medium ${getImpactLevelColor(impactAnalysis.impactLevel)}`}
              >
                {getImpactLevelText(impactAnalysis.impactLevel)}
              </span>
            </div>
          </div>
          <div>
            <div className="text-sm text-gray-500">受影响模型</div>
            <div className="mt-1 text-2xl font-bold text-gray-900">
              {impactAnalysis.affectedModels.length}
            </div>
          </div>
          <div>
            <div className="text-sm text-gray-500">受影响页面</div>
            <div className="mt-1 text-2xl font-bold text-gray-900">
              {impactAnalysis.affectedPages.length}
            </div>
          </div>
          <div>
            <div className="text-sm text-gray-500">受影响查询</div>
            <div className="mt-1 text-2xl font-bold text-gray-900">
              {impactAnalysis.affectedQueries.length}
            </div>
          </div>
        </div>

        <div className="mt-4 grid grid-cols-2 gap-4">
          <div className="flex items-center">
            <div
              className={`mr-2 h-3 w-3 rounded-full ${impactAnalysis.canSafelyModify ? 'bg-green-500' : 'bg-red-500'}`}
            ></div>
            <span className="text-sm text-gray-700">
              {impactAnalysis.canSafelyModify ? '可以安全修改' : '修改需谨慎'}
            </span>
          </div>
          <div className="flex items-center">
            <div
              className={`mr-2 h-3 w-3 rounded-full ${impactAnalysis.canSafelyDelete ? 'bg-green-500' : 'bg-red-500'}`}
            ></div>
            <span className="text-sm text-gray-700">
              {impactAnalysis.canSafelyDelete ? '可以安全删除' : '无法删除'}
            </span>
          </div>
        </div>
      </div>

      {/* Validation actions */}
      <div className="mb-6 rounded-lg bg-white p-6 shadow">
        <h2 className="mb-4 text-lg font-semibold text-gray-900">验证操作</h2>
        <div className="flex gap-4">
          <button
            onClick={handleValidateModification}
            disabled={validating}
            className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:opacity-50"
          >
            {validating ? '验证中...' : '验证修改'}
          </button>
          <button
            onClick={handleValidateDeletion}
            disabled={validating}
            className="rounded-md bg-red-600 px-4 py-2 text-white hover:bg-red-700 focus:ring-2 focus:ring-red-500 focus:outline-none disabled:opacity-50"
          >
            {validating ? '验证中...' : '验证删除'}
          </button>
        </div>

        {/* Validation result */}
        {validationResult && (
          <div className="mt-4 rounded-lg bg-gray-50 p-4">
            <div className="mb-2 font-medium text-gray-900">
              {validationResult.type === 'modify' ? '修改验证结果' : '删除验证结果'}
            </div>
            <pre className="text-sm whitespace-pre-wrap text-gray-700">
              {JSON.stringify(validationResult.result, null, 2)}
            </pre>
          </div>
        )}
      </div>

      {/* Affected models */}
      {impactAnalysis.affectedModels.length > 0 && (
        <div className="mb-6 rounded-lg bg-white shadow">
          <div className="border-b border-gray-200 px-6 py-4">
            <h2 className="text-lg font-semibold text-gray-900">受影响的模型</h2>
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
                    影响类型
                  </th>
                  <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                    影响描述
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200 bg-white">
                {impactAnalysis.affectedModels.map((model, index) => {
                  const modelInfo =
                    typeof model === 'string'
                      ? {
                          modelCode: model,
                          modelName: model,
                          impactType: 'direct' as const,
                          impactDescription: '',
                        }
                      : model;
                  return (
                    <tr key={index} className="hover:bg-gray-50">
                      <td className="px-6 py-4 text-sm font-medium whitespace-nowrap text-gray-900">
                        {modelInfo.modelCode}
                      </td>
                      <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-900">
                        {modelInfo.modelName}
                      </td>
                      <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                        <span
                          className={`rounded-full px-2 py-1 text-xs ${
                            modelInfo.impactType === 'direct'
                              ? 'bg-red-100 text-red-800'
                              : 'bg-yellow-100 text-yellow-800'
                          }`}
                        >
                          {modelInfo.impactType === 'direct' ? '直接' : '间接'}
                        </span>
                      </td>
                      <td className="px-6 py-4 text-sm text-gray-500">
                        {modelInfo.impactDescription}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Affected pages */}
      {impactAnalysis.affectedPages.length > 0 && (
        <div className="mb-6 rounded-lg bg-white shadow">
          <div className="border-b border-gray-200 px-6 py-4">
            <h2 className="text-lg font-semibold text-gray-900">受影响的页面</h2>
          </div>
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                    页面编码
                  </th>
                  <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                    页面名称
                  </th>
                  <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                    使用上下文
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200 bg-white">
                {impactAnalysis.affectedPages.map((page, index) => {
                  const pageInfo =
                    typeof page === 'string'
                      ? {
                          pageCode: page,
                          pageName: page,
                          usageContext: '',
                        }
                      : page;
                  return (
                    <tr key={index} className="hover:bg-gray-50">
                      <td className="px-6 py-4 text-sm font-medium whitespace-nowrap text-gray-900">
                        {pageInfo.pageCode}
                      </td>
                      <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-900">
                        {pageInfo.pageName}
                      </td>
                      <td className="px-6 py-4 text-sm text-gray-500">{pageInfo.usageContext}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Breaking changes */}
      {impactAnalysis.breakingChanges.length > 0 && (
        <div className="mb-6 rounded-lg bg-white shadow">
          <div className="border-b border-gray-200 px-6 py-4">
            <h2 className="text-lg font-semibold text-gray-900">破坏性变更</h2>
          </div>
          <div className="p-6">
            <div className="space-y-4">
              {impactAnalysis.breakingChanges.map((change, index) => (
                <div key={index} className="rounded-lg border border-gray-200 p-4">
                  <div className="mb-2 flex items-start justify-between">
                    <div className="font-medium text-gray-900">{change.changeType}</div>
                    <span
                      className={`rounded-full px-2 py-1 text-xs font-medium ${getImpactLevelColor(change.severity)}`}
                    >
                      {getImpactLevelText(change.severity)}
                    </span>
                  </div>
                  <div className="mb-2 text-sm text-gray-700">{change.description}</div>
                  {change.affectedComponents.length > 0 && (
                    <div className="mb-2 text-sm text-gray-600">
                      <span className="font-medium">受影响组件:</span>{' '}
                      {change.affectedComponents.join(', ')}
                    </div>
                  )}
                  {change.migrationPath && (
                    <div className="text-sm text-blue-600">
                      <span className="font-medium">迁移路径:</span> {change.migrationPath}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Recommendations */}
      {impactAnalysis.recommendations.length > 0 && (
        <div className="rounded-lg bg-white shadow">
          <div className="border-b border-gray-200 px-6 py-4">
            <h2 className="text-lg font-semibold text-gray-900">建议</h2>
          </div>
          <div className="p-6">
            <ul className="space-y-2">
              {impactAnalysis.recommendations.map((recommendation, index) => (
                <li key={index} className="flex items-start">
                  <svg
                    className="mt-0.5 mr-2 h-5 w-5 flex-shrink-0 text-blue-500"
                    fill="currentColor"
                    viewBox="0 0 20 20"
                  >
                    <path
                      fillRule="evenodd"
                      d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z"
                      clipRule="evenodd"
                    />
                  </svg>
                  <span className="text-sm text-gray-700">{recommendation}</span>
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}
    </div>
  );
}
