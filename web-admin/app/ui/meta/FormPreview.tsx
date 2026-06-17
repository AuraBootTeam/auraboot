/**
 * 表单预览组件
 *
 * 根据Model和Field配置动态生成表单预览
 *
 * 功能特性:
 * - 根据Field配置动态生成表单项
 * - 支持Dict关联的下拉选择
 * - 实时表单验证
 * - 测试提交功能
 *
 * 需求: 9.1-9.9
 */

import React, { useState, useCallback, useEffect } from 'react';
import type { MetaModelDTO, ModelFieldBinding } from '~/types/model';
import { useToastContext } from '~/contexts/ToastContext';

/**
 * 表单预览Props
 */
interface FormPreviewProps {
  /** 是否显示预览 */
  visible: boolean;
  /** Model信息 */
  model: MetaModelDTO | null;
  /** Field绑定列表 */
  fieldBindings: ModelFieldBinding[];
  /** 关闭回调 */
  onClose: () => void;
}

/**
 * 表单字段值类型
 */
interface FormValues {
  [fieldCode: string]: any;
}

/**
 * 表单错误类型
 */
interface FormErrors {
  [fieldCode: string]: string;
}

/**
 * 表单预览组件
 */
export function FormPreview({ visible, model, fieldBindings, onClose }: FormPreviewProps) {
  const { showSuccessToast, showErrorToast, showWarningToast, showInfoToast } = useToastContext();

  // 表单值
  const [formValues, setFormValues] = useState<FormValues>({});

  // 表单错误
  const [formErrors, setFormErrors] = useState<FormErrors>({});

  // 字典选项缓存
  const [dictOptions, setDictOptions] = useState<
    Record<string, Array<{ value: string; label: string }>>
  >({});

  // 加载状态
  const [loading, setLoading] = useState(false);

  // 提交状态
  const [submitting, setSubmitting] = useState(false);

  /**
   * 初始化表单
   */
  useEffect(() => {
    if (visible && model && fieldBindings.length > 0) {
      // 初始化表单值（使用默认值）
      const initialValues: FormValues = {};
      fieldBindings.forEach((binding) => {
        if (binding.defaultValue !== undefined) {
          initialValues[binding.fieldCode] = binding.defaultValue;
        }
      });
      setFormValues(initialValues);
      setFormErrors({});

      // 加载字典选项
      loadDictOptions();
    }
  }, [visible, model, fieldBindings]);

  /**
   * 加载字典选项
   */
  const loadDictOptions = useCallback(async () => {
    setLoading(true);
    try {
      const dictCodes = fieldBindings
        .filter((binding) => binding.dictCode)
        .map((binding) => binding.dictCode!);

      if (dictCodes.length === 0) {
        setLoading(false);
        return;
      }

      // TODO: 调用API批量加载字典选项
      // const options = await dictService.batchGetOptions(dictCodes);
      // setDictOptions(options);

      // 模拟数据
      const mockOptions: Record<string, Array<{ value: string; label: string }>> = {};
      dictCodes.forEach((code) => {
        mockOptions[code] = [
          { value: '1', label: '选项1' },
          { value: '2', label: '选项2' },
          { value: '3', label: '选项3' },
        ];
      });
      setDictOptions(mockOptions);
    } catch (error) {
      console.error('Failed to load dict options:', error);
    } finally {
      setLoading(false);
    }
  }, [fieldBindings]);

  /**
   * 更新字段值
   */
  const updateFieldValue = useCallback(
    (fieldCode: string, value: any) => {
      setFormValues((prev) => ({ ...prev, [fieldCode]: value }));

      // 清除该字段的错误
      setFormErrors((prev) => {
        const newErrors = { ...prev };
        delete newErrors[fieldCode];
        return newErrors;
      });

      // 实时验证
      validateField(fieldCode, value);
    },
    [fieldBindings],
  );

  /**
   * 验证单个字段
   */
  const validateField = useCallback(
    (fieldCode: string, value: any) => {
      const binding = fieldBindings.find((b) => b.fieldCode === fieldCode);
      if (!binding) return;

      // 必填验证
      if (binding.required && (value === undefined || value === null || value === '')) {
        setFormErrors((prev) => ({
          ...prev,
          [fieldCode]: '此字段为必填项',
        }));
        return;
      }

      // 验证规则
      if (binding.validationRules && binding.validationRules.length > 0) {
        for (const rule of binding.validationRules) {
          let isValid = true;
          let errorMessage = rule.message;

          switch (rule.type) {
            case 'required':
              isValid = value !== undefined && value !== null && value !== '';
              break;
            case 'pattern':
              if (rule.value && value) {
                const regex = new RegExp(String(rule.value));
                isValid = regex.test(String(value));
              }
              break;
            case 'minLength':
              if (rule.value && value) {
                isValid = String(value).length >= parseInt(String(rule.value));
              }
              break;
            case 'maxLength':
              if (rule.value && value) {
                isValid = String(value).length <= parseInt(String(rule.value));
              }
              break;
            case 'min':
              if (rule.value && value !== undefined) {
                isValid = Number(value) >= Number(rule.value);
              }
              break;
            case 'max':
              if (rule.value && value !== undefined) {
                isValid = Number(value) <= Number(rule.value);
              }
              break;
          }

          if (!isValid) {
            setFormErrors((prev) => ({
              ...prev,
              [fieldCode]: errorMessage,
            }));
            return;
          }
        }
      }
    },
    [fieldBindings],
  );

  /**
   * 验证整个表单
   */
  const validateForm = useCallback((): boolean => {
    const errors: FormErrors = {};

    fieldBindings.forEach((binding) => {
      const value = formValues[binding.fieldCode];

      // 必填验证
      if (binding.required && (value === undefined || value === null || value === '')) {
        errors[binding.fieldCode] = '此字段为必填项';
        return;
      }

      // 验证规则
      if (binding.validationRules && binding.validationRules.length > 0) {
        for (const rule of binding.validationRules) {
          let isValid = true;

          switch (rule.type) {
            case 'required':
              isValid = value !== undefined && value !== null && value !== '';
              break;
            case 'pattern':
              if (rule.value && value) {
                const regex = new RegExp(String(rule.value));
                isValid = regex.test(String(value));
              }
              break;
            case 'minLength':
              if (rule.value && value) {
                isValid = String(value).length >= parseInt(String(rule.value));
              }
              break;
            case 'maxLength':
              if (rule.value && value) {
                isValid = String(value).length <= parseInt(String(rule.value));
              }
              break;
            case 'min':
              if (rule.value && value !== undefined) {
                isValid = Number(value) >= Number(rule.value);
              }
              break;
            case 'max':
              if (rule.value && value !== undefined) {
                isValid = Number(value) <= Number(rule.value);
              }
              break;
          }

          if (!isValid) {
            errors[binding.fieldCode] = rule.message;
            break;
          }
        }
      }
    });

    setFormErrors(errors);
    return Object.keys(errors).length === 0;
  }, [fieldBindings, formValues]);

  /**
   * 测试提交
   */
  const handleTestSubmit = useCallback(async () => {
    // 验证表单
    const isValid = validateForm();

    if (!isValid) {
      showWarningToast('表单验证失败，请检查错误提示');
      return;
    }

    setSubmitting(true);
    try {
      // 模拟提交延迟
      await new Promise((resolve) => setTimeout(resolve, 1000));

      showSuccessToast('表单验证通过！提交的数据：' + JSON.stringify(formValues, null, 2));
    } catch (error) {
      console.error('Submit error:', error);
      showErrorToast('提交失败');
    } finally {
      setSubmitting(false);
    }
  }, [formValues, validateForm]);

  /**
   * 重置表单
   */
  const handleReset = useCallback(() => {
    const initialValues: FormValues = {};
    fieldBindings.forEach((binding) => {
      if (binding.defaultValue !== undefined) {
        initialValues[binding.fieldCode] = binding.defaultValue;
      }
    });
    setFormValues(initialValues);
    setFormErrors({});
  }, [fieldBindings]);

  /**
   * 渲染表单字段
   */
  const renderField = useCallback(
    (binding: ModelFieldBinding) => {
      const value = formValues[binding.fieldCode];
      const error = formErrors[binding.fieldCode];
      const hasError = !!error;

      // 如果关联了字典，渲染为下拉选择
      if (binding.dictCode && dictOptions[binding.dictCode]) {
        return (
          <div key={binding.fieldCode} className="mb-4">
            <label className="text-text-2 mb-1 block text-sm font-medium">
              {binding.fieldName}
              {binding.required && <span className="text-status-red ml-1">*</span>}
            </label>
            <select
              value={value || ''}
              onChange={(e) => updateFieldValue(binding.fieldCode, e.target.value)}
              className={`rounded-control w-full border px-3 py-2 focus:ring-2 focus:outline-none ${
                hasError
                  ? 'border-status-red focus-visible:shadow-focus focus:outline-none'
                  : 'border-border-strong focus-visible:shadow-focus focus:outline-none'
              }`}
            >
              <option value="">请选择</option>
              {dictOptions[binding.dictCode].map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
            {hasError && <p className="text-status-red mt-1 text-sm">{error}</p>}
          </div>
        );
      }

      // 根据数据类型渲染不同的输入控件
      const dataType = binding.dataType || 'string';

      if (dataType === 'text' || dataType === 'longtext') {
        return (
          <div key={binding.fieldCode} className="mb-4">
            <label className="text-text-2 mb-1 block text-sm font-medium">
              {binding.fieldName}
              {binding.required && <span className="text-status-red ml-1">*</span>}
            </label>
            <textarea
              value={value || ''}
              onChange={(e) => updateFieldValue(binding.fieldCode, e.target.value)}
              rows={dataType === 'longtext' ? 6 : 3}
              className={`rounded-control w-full border px-3 py-2 focus:ring-2 focus:outline-none ${
                hasError
                  ? 'border-status-red focus-visible:shadow-focus focus:outline-none'
                  : 'border-border-strong focus-visible:shadow-focus focus:outline-none'
              }`}
            />
            {hasError && <p className="text-status-red mt-1 text-sm">{error}</p>}
          </div>
        );
      }

      if (dataType === 'boolean') {
        return (
          <div key={binding.fieldCode} className="mb-4">
            <div className="flex items-center">
              <input
                type="checkbox"
                checked={value || false}
                onChange={(e) => updateFieldValue(binding.fieldCode, e.target.checked)}
                className="border-border-strong text-accent focus-visible:shadow-focus h-4 w-4 rounded focus:outline-none"
              />
              <label className="text-text-2 ml-2 text-sm">
                {binding.fieldName}
                {binding.required && <span className="text-status-red ml-1">*</span>}
              </label>
            </div>
            {hasError && <p className="text-status-red mt-1 text-sm">{error}</p>}
          </div>
        );
      }

      // 默认渲染为文本输入
      const inputType = ['integer', 'long', 'decimal'].includes(dataType) ? 'number' : 'text';

      return (
        <div key={binding.fieldCode} className="mb-4">
          <label className="text-text-2 mb-1 block text-sm font-medium">
            {binding.fieldName}
            {binding.required && <span className="text-status-red ml-1">*</span>}
          </label>
          <input
            type={inputType}
            value={value || ''}
            onChange={(e) => updateFieldValue(binding.fieldCode, e.target.value)}
            className={`rounded-control w-full border px-3 py-2 focus:ring-2 focus:outline-none ${
              hasError
                ? 'border-status-red focus-visible:shadow-focus focus:outline-none'
                : 'border-border-strong focus-visible:shadow-focus focus:outline-none'
            }`}
          />
          {hasError && <p className="text-status-red mt-1 text-sm">{error}</p>}
        </div>
      );
    },
    [formValues, formErrors, dictOptions, updateFieldValue],
  );

  if (!visible || !model) return null;

  // 按displayOrder排序
  const sortedBindings = [...fieldBindings].sort((a, b) => a.displayOrder - b.displayOrder);

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto">
      {/* 遮罩层 */}
      <div className="bg-opacity-50 fixed inset-0 bg-black" onClick={onClose} />

      {/* 对话框 */}
      <div className="flex min-h-screen items-center justify-center p-4">
        <div className="rounded-card bg-panel relative flex max-h-[90vh] w-full max-w-4xl flex-col shadow-xl">
          {/* 标题栏 */}
          <div className="border-border border-b px-6 py-4">
            <h2 className="text-text text-lg font-semibold">表单预览</h2>
            <p className="text-text-2 mt-1 text-sm">
              预览模型 <span className="text-accent font-mono">{model.code}</span> 生成的动态表单
            </p>
          </div>

          {/* 表单内容 */}
          <div className="flex-1 overflow-y-auto px-6 py-4">
            {loading ? (
              <div className="flex items-center justify-center py-12">
                <div className="rounded-pill border-accent h-8 w-8 animate-spin border-b-2"></div>
                <span className="text-text-2 ml-3">加载中...</span>
              </div>
            ) : sortedBindings.length === 0 ? (
              <div className="py-12 text-center">
                <p className="text-text-2">该模型还没有关联任何字段</p>
                <p className="text-text-3 mt-2 text-sm">请先添加字段后再预览表单</p>
              </div>
            ) : (
              <div className="mx-auto max-w-2xl">
                <div className="rounded-card bg-subtle p-6">
                  <h3 className="text-text mb-4 text-base font-medium">{model.displayName}</h3>
                  {model.description && (
                    <p className="text-text-2 mb-6 text-sm">{model.description}</p>
                  )}

                  <form
                    onSubmit={(e) => {
                      e.preventDefault();
                      handleTestSubmit();
                    }}
                  >
                    {sortedBindings.map((binding) => renderField(binding))}

                    {/* 表单按钮 */}
                    <div className="border-border mt-6 flex justify-end gap-3 border-t pt-4">
                      <button
                        type="button"
                        onClick={handleReset}
                        className="rounded-control border-border-strong text-text-2 hover:bg-subtle border px-4 py-2"
                      >
                        重置
                      </button>
                      <button
                        type="submit"
                        disabled={submitting}
                        className="rounded-control bg-accent hover:bg-accent-hover px-4 py-2 text-white disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        {submitting ? '提交中...' : '测试提交'}
                      </button>
                    </div>
                  </form>
                </div>
              </div>
            )}
          </div>

          {/* 底部按钮 */}
          <div className="border-border flex justify-end border-t px-6 py-4">
            <button
              onClick={onClose}
              className="rounded-control border-border-strong text-text-2 hover:bg-subtle border px-4 py-2"
            >
              关闭
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
