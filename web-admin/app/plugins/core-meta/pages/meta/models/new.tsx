/**
 * Model创建页面
 *
 * 提供Model创建表单界面
 *
 * 功能特性:
 * - 表单验证（必填、格式、唯一性）
 * - 异步编码唯一性检查
 * - Git-First工作流提示
 * - CRUD模板向导集成
 */

import React, { useState, useCallback } from 'react';
import { useNavigate } from 'react-router';
import type { ActionFunctionArgs } from 'react-router';
import { modelService } from '~/shared/services/modelService';
import { useToastContext } from '~/contexts/ToastContext';
import type { MetaModelCreateRequest } from '~/types/model';

/**
 * Action函数 - 处理表单提交
 */
export const action = async ({ request }: ActionFunctionArgs) => {
  const formData = await request.formData();

  const createRequest: MetaModelCreateRequest = {
    code: formData.get('code') as string,
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
    const result = await modelService.create(createRequest);
    return { success: true, data: result };
  } catch (error) {
    console.error('Failed to create model:', error);
    return { success: false, error: '创建Model失败' };
  }
};

/**
 * Model创建页面组件
 */
export default function ModelNewPage() {
  const navigate = useNavigate();
  const { showSuccessToast, showErrorToast } = useToastContext();

  // 表单状态
  const [formData, setFormData] = useState<MetaModelCreateRequest>({
    code: '',
    displayName: '',
    description: '',
    modelType: 'entity',
    namespace: '',
    env: 'dev',
    extension: undefined,
    versionNote: '',
  });

  // 验证错误状态
  const [errors, setErrors] = useState<Record<string, string>>({});

  // 提交状态
  const [submitting, setSubmitting] = useState(false);

  // 编码唯一性检查状态
  const [checkingCode, setCheckingCode] = useState(false);

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
   * 编码唯一性检查
   */
  const checkCodeUnique = useCallback(async (code: string) => {
    if (!code || code.length < 3) return;

    setCheckingCode(true);
    try {
      const isUnique = await modelService.checkCodeUnique(code);
      // API返回true表示编码唯一（可用），false表示编码已存在
      if (!isUnique) {
        setErrors((prev) => ({ ...prev, code: '编码已存在' }));
      }
    } catch (error) {
      console.error('Failed to check code uniqueness:', error);
    } finally {
      setCheckingCode(false);
    }
  }, []);

  /**
   * 编码输入框失焦时检查唯一性
   */
  const handleCodeBlur = useCallback(() => {
    if (formData.code) {
      checkCodeUnique(formData.code);
    }
  }, [formData.code, checkCodeUnique]);

  /**
   * 表单验证
   */
  const validateForm = useCallback((): boolean => {
    const newErrors: Record<string, string> = {};

    // 必填字段验证
    if (!formData.code) {
      newErrors.code = '模型编码不能为空';
    } else if (!/^[a-zA-Z0-9_]+$/.test(formData.code)) {
      newErrors.code = '模型编码只能包含字母、数字和下划线';
    } else if (formData.code.length < 3) {
      newErrors.code = '模型编码长度不能少于3个字符';
    } else if (formData.code.length > 50) {
      newErrors.code = '模型编码长度不能超过50个字符';
    }

    if (!formData.displayName) {
      newErrors.displayName = '显示名称不能为空';
    } else if (formData.displayName.length > 100) {
      newErrors.displayName = '显示名称长度不能超过100个字符';
    }

    if (!formData.modelType) {
      newErrors.modelType = '模型类型不能为空';
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

      setSubmitting(true);
      try {
        const result = await modelService.create(formData);
        showSuccessToast('创建Model成功');

        // 跳转到详情页
        navigate(`/meta/models/${result.pid}`);
      } catch (error) {
        console.error('Failed to create model:', error);
        showErrorToast('创建Model失败');
      } finally {
        setSubmitting(false);
      }
    },
    [formData, validateForm, navigate, showSuccessToast, showErrorToast],
  );

  /**
   * 重置表单
   */
  const handleReset = useCallback(() => {
    setFormData({
      code: '',
      displayName: '',
      description: '',
      modelType: 'entity',
      namespace: '',
      env: 'dev',
      extension: undefined,
      versionNote: '',
    });
    setErrors({});
  }, []);

  /**
   * 取消操作
   */
  const handleCancel = useCallback(() => {
    navigate('/meta/models');
  }, [navigate]);

  return (
    <div className="mx-auto max-w-4xl p-6">
      {/* 页面标题 */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900">新建模型</h1>
        <p className="mt-1 text-sm text-gray-500">创建新的业务实体模型，定义数据结构和字段关系</p>
      </div>

      {/* 表单 */}
      <form onSubmit={handleSubmit} className="rounded-lg bg-white p-6 shadow">
        {/* 基本信息区域 */}
        <div className="mb-6">
          <h2 className="mb-4 text-lg font-semibold text-gray-900">基本信息</h2>

          <div className="grid grid-cols-2 gap-4">
            {/* 模型编码 */}
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">
                模型编码 <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={formData.code}
                onChange={(e) => handleChange('code', e.target.value)}
                onBlur={handleCodeBlur}
                placeholder="例如: user_order"
                className={`w-full rounded-md border px-3 py-2 focus:ring-2 focus:outline-none ${
                  errors.code
                    ? 'border-red-300 focus:ring-red-500'
                    : 'border-gray-300 focus:ring-blue-500'
                }`}
                disabled={submitting}
              />
              {checkingCode && <p className="mt-1 text-sm text-gray-500">正在检查编码唯一性...</p>}
              {errors.code && <p className="mt-1 text-sm text-red-600">{errors.code}</p>}
              <p className="mt-1 text-xs text-gray-500">
                只能包含字母、数字和下划线，长度3-50个字符
              </p>
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

            {/* 模型类型 */}
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">
                模型类型 <span className="text-red-500">*</span>
              </label>
              <select
                value={formData.modelType}
                onChange={(e) => handleChange('modelType', e.target.value)}
                className={`w-full rounded-md border px-3 py-2 focus:ring-2 focus:outline-none ${
                  errors.modelType
                    ? 'border-red-300 focus:ring-red-500'
                    : 'border-gray-300 focus:ring-blue-500'
                }`}
                disabled={submitting}
              >
                <option value="entity">实体</option>
                <option value="view">视图</option>
                <option value="aggregate">聚合</option>
              </select>
              {errors.modelType && <p className="mt-1 text-sm text-red-600">{errors.modelType}</p>}
            </div>

            {/* 描述 */}
            <div className="col-span-2">
              <label className="mb-1 block text-sm font-medium text-gray-700">描述</label>
              <textarea
                value={formData.description}
                onChange={(e) => handleChange('description', e.target.value)}
                placeholder="请输入模型描述"
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

        {/* 高级配置区域 */}
        <div className="mb-6">
          <h2 className="mb-4 text-lg font-semibold text-gray-900">高级配置</h2>

          <div className="grid grid-cols-2 gap-4">
            {/* 命名空间 */}
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">命名空间</label>
              <input
                type="text"
                value={formData.namespace}
                onChange={(e) => handleChange('namespace', e.target.value)}
                placeholder="例如: com.example"
                className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                disabled={submitting}
              />
            </div>

            {/* 环境 */}
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">环境</label>
              <select
                value={formData.env}
                onChange={(e) => handleChange('env', e.target.value)}
                className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                disabled={submitting}
              >
                <option value="dev">开发环境</option>
                <option value="test">测试环境</option>
                <option value="staging">预发布环境</option>
                <option value="prod">生产环境</option>
              </select>
            </div>
          </div>
        </div>

        {/* Git-First提示 */}
        <div className="mb-6 rounded-md border border-blue-200 bg-blue-50 p-4">
          <div className="flex">
            <div className="flex-shrink-0">
              <svg className="h-5 w-5 text-blue-400" viewBox="0 0 20 20" fill="currentColor">
                <path
                  fillRule="evenodd"
                  d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z"
                  clipRule="evenodd"
                />
              </svg>
            </div>
            <div className="ml-3">
              <h3 className="text-sm font-medium text-blue-800">Git-First工作流</h3>
              <div className="mt-2 text-sm text-blue-700">
                <p>创建Model将触发Git提交和Release流程：</p>
                <ul className="mt-1 list-inside list-disc space-y-1">
                  <li>生成DSL文件并提交到Git仓库</li>
                  <li>自动创建Release并处理</li>
                  <li>投影到运行时数据表</li>
                  <li>支持版本回滚和审计追踪</li>
                </ul>
              </div>
            </div>
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
            disabled={submitting}
          >
            重置
          </button>
          <button
            type="submit"
            className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-50"
            disabled={submitting || checkingCode}
          >
            {submitting ? '创建中...' : '创建'}
          </button>
        </div>
      </form>
    </div>
  );
}
