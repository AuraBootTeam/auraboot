/**
 * Model编辑页面
 *
 * 提供Model编辑表单界面
 *
 * 功能特性:
 * - 加载现有Model数据
 * - 最小编辑表单
 * - 表单验证（必填、格式、唯一性）
 * - 版本信息摘要
 */

import React, { useState, useCallback, useEffect } from 'react';
import {
  useNavigate,
  useParams,
  useLoaderData,
  type LoaderFunctionArgs,
  type ActionFunctionArgs,
} from 'react-router';
import { modelService } from '~/shared/services/modelService';
import { confirmDialog } from '~/utils/confirmDialog';
import { useToastContext } from '~/contexts/ToastContext';
import type { MetaModelUpdateRequest } from '~/types/model';

/**
 * Loader函数 - 加载Model数据
 */
export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const { pid } = params;

  if (!pid) {
    throw new Response('Model PID is required', { status: 400 });
  }

  try {
    const model = await modelService.findByPid(pid, request);
    return { model };
  } catch (error) {
    console.error('Failed to load model:', error);
    throw new Response('Model not found', { status: 404 });
  }
};

/**
 * Action函数 - 处理表单提交
 */
export const action = async ({ request, params }: ActionFunctionArgs) => {
  const { pid } = params;

  if (!pid) {
    return { success: false, error: 'Model PID is required' };
  }

  const formData = await request.formData();

  const updateRequest: MetaModelUpdateRequest = {
    displayName: formData.get('displayName') as string,
    description: (formData.get('description') as string) || undefined,
    modelType: formData.get('modelType') as any,
    namespace: (formData.get('namespace') as string) || undefined,
    env: (formData.get('env') as string) || undefined,
    extension: formData.get('extension')
      ? JSON.parse(formData.get('extension') as string)
      : undefined,
    versionNote: (formData.get('versionNote') as string) || undefined,
  };

  try {
    const result = await modelService.update(pid, updateRequest);
    return { success: true, data: result };
  } catch (error) {
    console.error('Failed to update model:', error);
    return { success: false, error: '更新Model失败' };
  }
};

/**
 * Model编辑页面组件
 */
export default function ModelEditPage() {
  const navigate = useNavigate();
  const { pid } = useParams();
  const { model: initialModel } = useLoaderData<typeof loader>();
  const { showSuccessToast, showErrorToast } = useToastContext();

  // 表单状态
  const [formData, setFormData] = useState<MetaModelUpdateRequest>({
    displayName: initialModel.displayName,
    description: initialModel.description || '',
    modelType: initialModel.modelType || 'entity',
    namespace: initialModel.namespace || '',
    env: initialModel.env || 'dev',
    extension: initialModel.extension,
    versionNote: '',
  });

  // 验证错误状态
  const [errors, setErrors] = useState<Record<string, string>>({});

  // 提交状态
  const [submitting, setSubmitting] = useState(false);

  // 检测表单是否有变更
  const [hasChanges, setHasChanges] = useState(false);

  /**
   * 检测表单变更
   */
  useEffect(() => {
    const changed =
      formData.displayName !== initialModel.displayName ||
      formData.description !== (initialModel.description || '');

    setHasChanges(changed);
  }, [formData, initialModel]);

  /**
   * 表单字段变更处理
   */
  const handleChange = useCallback(
    (field: string, value: any) => {
      setFormData((prev) => ({ ...prev, [field]: value }));
      // 清除该字段的错误
      if (errors[field]) {
        setErrors((prev) => {
          const newErrors = { ...prev };
          delete newErrors[field];
          return newErrors;
        });
      }
    },
    [errors],
  );

  /**
   * 表单验证
   */
  const validateForm = useCallback((): boolean => {
    const newErrors: Record<string, string> = {};

    // 必填字段验证
    if (!formData.displayName) {
      newErrors.displayName = '显示名称不能为空';
    } else if (formData.displayName.length > 100) {
      newErrors.displayName = '显示名称长度不能超过100个字符';
    }

    if (formData.description && formData.description.length > 500) {
      newErrors.description = '描述长度不能超过500个字符';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  }, [formData]);

  /**
   * 表单提交处理
   */
  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();

      // 验证表单
      if (!validateForm()) {
        showErrorToast('请检查表单输入');
        return;
      }

      if (!hasChanges) {
        showErrorToast('没有检测到任何变更');
        return;
      }

      setSubmitting(true);
      try {
        const result = await modelService.update(pid!, formData);
        showSuccessToast('更新Model成功');

        // 跳转到详情页
        navigate(`/meta/models/${result.pid}`);
      } catch (error) {
        console.error('Failed to update model:', error);
        showErrorToast('更新Model失败');
      } finally {
        setSubmitting(false);
      }
    },
    [pid, formData, hasChanges, validateForm, navigate, showSuccessToast, showErrorToast],
  );

  /**
   * 重置表单
   */
  const handleReset = useCallback(() => {
    setFormData({
      displayName: initialModel.displayName,
      description: initialModel.description || '',
      modelType: initialModel.modelType,
      namespace: initialModel.namespace || '',
      env: initialModel.env || 'dev',
      extension: initialModel.extension,
      versionNote: '',
    });
    setErrors({});
  }, [initialModel]);

  /**
   * 取消操作
   */
  const handleCancel = useCallback(async () => {
    if (hasChanges) {
      const confirmed = await confirmDialog({
        content: '表单有未保存的变更，确定要离开吗？',
      });
      if (!confirmed) return;
    }
    navigate(`/meta/models/${pid}`);
  }, [pid, hasChanges, navigate]);

  return (
    <div className="mx-auto max-w-4xl p-6">
      {/* 页面标题 */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900">编辑模型</h1>
        <p className="mt-1 text-sm text-gray-500">
          调整模型名称和说明，字段与页面配置请在详情页继续处理
        </p>
      </div>

      {/* 表单 */}
      <form onSubmit={handleSubmit} className="rounded-2xl border border-gray-200 bg-white p-6 shadow-sm">
        {/* 基本信息区域 */}
        <div className="mb-6">
          <h2 className="mb-4 text-lg font-semibold text-gray-900">基本信息</h2>

          <div className="grid gap-5 md:grid-cols-2">
            {/* 模型编码（只读） */}
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">模型编码</label>
              <input
                type="text"
                value={initialModel.code}
                disabled
                className="w-full cursor-not-allowed rounded-md border border-gray-300 bg-gray-50 px-3 py-2 text-gray-500"
              />
              <p className="mt-1 text-xs text-gray-500">模型编码创建后不可修改</p>
            </div>

            {/* 显示名称 */}
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">
                显示名称 <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={formData.displayName}
                onChange={(e) => handleChange('displayName', e.target.value)}
                placeholder="例如: 用户订单"
                className={`w-full rounded-md border px-3 py-2 focus:ring-2 focus:outline-none ${
                  errors.displayName
                    ? 'border-red-300 focus:ring-red-500'
                    : 'border-gray-300 focus:ring-blue-500'
                }`}
                disabled={submitting}
              />
              {errors.displayName && (
                <p className="mt-1 text-sm text-red-600">{errors.displayName}</p>
              )}
            </div>

            {/* 描述 */}
            <div className="col-span-2">
              <label className="mb-1 block text-sm font-medium text-gray-700">描述</label>
              <textarea
                value={formData.description}
                onChange={(e) => handleChange('description', e.target.value)}
                placeholder="选填，帮助团队理解模型用途"
                rows={3}
                className={`w-full rounded-md border px-3 py-2 focus:ring-2 focus:outline-none ${
                  errors.description
                    ? 'border-red-300 focus:ring-red-500'
                    : 'border-gray-300 focus:ring-blue-500'
                }`}
                disabled={submitting}
              />
              {errors.description && (
                <p className="mt-1 text-sm text-red-600">{errors.description}</p>
              )}
            </div>
          </div>
        </div>

        {/* 版本信息 */}
        <div className="mb-6 rounded-xl border border-gray-200 bg-gray-50 p-4">
          <h3 className="mb-2 text-sm font-medium text-gray-700">当前版本信息</h3>
          <div className="grid grid-cols-2 gap-2 text-sm">
            <div>
              <span className="text-gray-500">版本号:</span>
              <span className="ml-2 font-mono">{initialModel.version || 'N/A'}</span>
            </div>
            <div>
              <span className="text-gray-500">创建时间:</span>
              <span className="ml-2">{new Date(initialModel.createdAt).toLocaleString()}</span>
            </div>
            <div>
              <span className="text-gray-500">更新时间:</span>
              <span className="ml-2">{new Date(initialModel.updatedAt).toLocaleString()}</span>
            </div>
            {initialModel.releaseId && (
              <div>
                <span className="text-gray-500">Release ID:</span>
                <span className="ml-2 font-mono">{initialModel.releaseId}</span>
              </div>
            )}
          </div>
        </div>

        {/* 按钮组 */}
        <div className="flex justify-end gap-3">
          <button
            type="button"
            onClick={handleCancel}
            className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50 focus:ring-2 focus:ring-gray-500 focus:outline-none"
            disabled={submitting}
          >
            取消
          </button>
          <button
            type="button"
            onClick={handleReset}
            className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50 focus:ring-2 focus:ring-gray-500 focus:outline-none"
            disabled={submitting || !hasChanges}
          >
            重置
          </button>
          <button
            type="submit"
            className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-50"
            disabled={submitting || !hasChanges}
          >
            {submitting ? '保存中...' : '保存'}
          </button>
        </div>
      </form>
    </div>
  );
}
