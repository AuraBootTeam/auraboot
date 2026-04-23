/**
 * Physical Model creation form.
 *
 * Extracted verbatim from the previous `/meta/models/new` page so that the
 * route can host a Step 0 type selector without changing the physical model
 * creation behaviour.
 *
 * Features:
 * - Minimal physical model creation flow
 * - Field validation (required, format, uniqueness)
 * - Async code uniqueness check
 */

import React, { useState, useCallback } from 'react';
import { useNavigate } from 'react-router';
import { modelService } from '~/shared/services/modelService';
import { useToastContext } from '~/contexts/ToastContext';
import type { MetaModelCreateRequest } from '~/types/model';

export interface PhysicalModelFormProps {
  /**
   * Optional callback invoked when the user clicks the "back to type selector"
   * button. When provided, an extra button is rendered so users can return to
   * Step 0 without losing the page context.
   */
  onCancel?: () => void;
}

/**
 * Physical Model creation form component.
 */
export function PhysicalModelForm({ onCancel }: PhysicalModelFormProps) {
  const navigate = useNavigate();
  const { showSuccessToast, showErrorToast } = useToastContext();

  // Form state
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

  // Validation errors
  const [errors, setErrors] = useState<Record<string, string>>({});

  // Submit state
  const [submitting, setSubmitting] = useState(false);

  // Code uniqueness check state
  const [checkingCode, setCheckingCode] = useState(false);

  /**
   * Field change handler.
   */
  const handleChange = useCallback(
    (field: string, value: any) => {
      setFormData((prev) => ({ ...prev, [field]: value }));
      // Clear the field's error
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
   * Code uniqueness check.
   */
  const checkCodeUnique = useCallback(async (code: string) => {
    if (!code || code.length < 3) return;

    setCheckingCode(true);
    try {
      const isUnique = await modelService.checkCodeUnique(code);
      // API returns true when the code is unique (available).
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
   * Run uniqueness check on blur.
   */
  const handleCodeBlur = useCallback(() => {
    if (formData.code) {
      checkCodeUnique(formData.code);
    }
  }, [formData.code, checkCodeUnique]);

  /**
   * Form validation.
   */
  const validateForm = useCallback((): boolean => {
    const newErrors: Record<string, string> = {};

    // Required field validation
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

    if (formData.description && formData.description.length > 500) {
      newErrors.description = '描述长度不能超过500个字符';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  }, [formData]);

  /**
   * Form submit handler.
   */
  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();

      // Validate form
      if (!validateForm()) {
        showErrorToast('请检查表单输入');
        return;
      }

      setSubmitting(true);
      try {
        const result = await modelService.create(formData);
        showSuccessToast('创建Model成功');

        // Navigate to detail page
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
   * Cancel action — go back to the list page.
   */
  const handleCancel = useCallback(() => {
    navigate('/meta/models');
  }, [navigate]);

  return (
    <div className="mx-auto max-w-3xl p-6">
      {/* Page title */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900">新建物理模型</h1>
        <p className="mt-1 text-sm text-gray-500">先创建模型，再继续配置字段和页面</p>
      </div>

      {onCancel && (
        <div className="mb-4">
          <button
            type="button"
            onClick={onCancel}
            data-testid="physical-model-form-back"
            className="text-sm text-gray-500 hover:text-gray-700"
          >
            ← 返回选择类型
          </button>
        </div>
      )}

      {/* Form */}
      <form onSubmit={handleSubmit} className="rounded-2xl border border-gray-200 bg-white p-6 shadow-sm">
        {/* Basic info section */}
        <div className="mb-6">
          <h2 className="mb-4 text-lg font-semibold text-gray-900">基本信息</h2>

          <div className="grid gap-5 md:grid-cols-2">
            {/* Model code */}
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
              <p className="mt-1 text-xs text-gray-500">仅支持字母、数字、下划线，长度 3-50 位</p>
            </div>

            {/* Display name */}
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

            {/* Description */}
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

        <div className="mb-6 rounded-xl border border-gray-200 bg-gray-50 px-4 py-3 text-sm text-gray-600">
          创建后可继续配置字段、生成页面并发布。
        </div>

        {/* Button group */}
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
            type="submit"
            className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-50"
            disabled={submitting || checkingCode}
          >
            {submitting ? '创建中...' : '创建模型'}
          </button>
        </div>
      </form>
    </div>
  );
}

export default PhysicalModelForm;
