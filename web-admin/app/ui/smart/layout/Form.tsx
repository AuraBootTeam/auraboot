import React, { useState, useCallback } from 'react';
import { cn } from '~/utils/cn';
import { useConditionalRender } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent';
import { ExpressionParser } from '~/plugins/core-designer/components/studio/services/runtime/expression/expression-parser';
import { Input } from '~/ui/smart/form/Input';
import { Select } from '~/ui/smart/form/Select';
import { Textarea } from '~/ui/smart/form/Textarea';
import { Checkbox } from '~/ui/smart/form/Checkbox';
import { Radio } from '~/ui/smart/form/Radio';
import { DatePicker } from '~/ui/smart/datetime/DatePicker';
import { Button } from '~/ui/smart/interaction/Button';
import { useSmartText } from '~/utils/i18n';
import type {
  FormProps,
  FormFieldConfig,
  FormActionConfig,
} from '~/plugins/core-designer/components/studio/domain/schema/smart-components';

/**
 * Form 智能表单组件
 *
 * 功能特性：
 * - 支持 FormSchema 驱动的动态表单生成
 * - 支持多种字段类型（input、select、textarea、checkbox、radio、date等）
 * - 支持表达式驱动的条件渲染和动态属性
 * - 支持表单验证和错误处理
 * - 支持表单布局配置（水平、垂直、内联）
 * - 支持表单操作按钮配置
 * - 支持表单数据绑定和状态管理
 */
export const Form: React.FC<FormProps> = ({
  name: _name,
  schema,
  data = {},
  context,
  className,
  onSubmit,
  onFieldChange,
  onValidationChange,
  ...props
}) => {
  // 条件渲染控制
  const isVisible = useConditionalRender(props.visible, context);
  const st = useSmartText();

  // 表单数据状态管理
  const [formData, setFormData] = useState(data);
  const [_errors, setErrors] = useState<Record<string, string[]>>({});
  const [_touched, setTouched] = useState<Record<string, boolean>>({});
  const [isSubmitting, setIsSubmitting] = useState(false);

  // 更新表单数据
  const handleFieldChange = useCallback(
    (fieldName: string, value: any) => {
      const newFormData = { ...formData, [fieldName]: value };
      setFormData(newFormData);
      setTouched((prev) => ({ ...prev, [fieldName]: true }));
      onFieldChange?.(fieldName, value, newFormData);
    },
    [formData, onFieldChange],
  );

  // 字段验证
  const validateField = useCallback(
    async (field: FormFieldConfig, value: any) => {
      if (!field.validationRules?.length) return [];

      const fieldErrors: string[] = [];
      const fieldContext = { ...context, $value: value, $data: formData };

      for (const rule of field.validationRules) {
        try {
          let isValid = true;

          switch (rule.type) {
            case 'required':
              isValid = value !== undefined && value !== null && value !== '';
              break;
            case 'minLength':
              isValid = !value || String(value).length >= (rule.value as number);
              break;
            case 'maxLength':
              isValid = !value || String(value).length <= (rule.value as number);
              break;
            case 'pattern':
              isValid = !value || new RegExp(rule.value as string).test(String(value));
              break;
            case 'email':
              isValid = !value || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(value));
              break;
            case 'custom':
              if (rule.validator) {
                isValid = ExpressionParser.evaluate(rule.validator, fieldContext);
              }
              break;
          }

          if (!isValid) {
            fieldErrors.push(rule.message || `字段 ${field.label || field.name} 验证失败`);
          }
        } catch (error) {
          console.error('Field validation error:', error);
          fieldErrors.push('验证过程中发生错误');
        }
      }

      return fieldErrors;
    },
    [context, formData],
  );

  // 表单验证
  const validateForm = useCallback(async () => {
    const newErrors: Record<string, string[]> = {};
    let isValid = true;

    for (const field of schema.fields) {
      const fieldErrors = await validateField(field, formData[field.name]);
      if (fieldErrors?.length > 0) {
        newErrors[field.name] = fieldErrors;
        isValid = false;
      }
    }

    setErrors(newErrors);
    onValidationChange?.(isValid, newErrors);
    return isValid;
  }, [schema.fields, formData, validateField, onValidationChange]);

  // 处理表单提交
  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();

      if (isSubmitting) return;

      setIsSubmitting(true);

      try {
        const isValid = await validateForm();
        if (isValid) {
          await onSubmit?.(formData);
        }
      } catch (error) {
        console.error('Form submission error:', error);
      } finally {
        setIsSubmitting(false);
      }
    },
    [formData, isSubmitting, validateForm, onSubmit],
  );

  // 处理操作按钮点击
  const handleAction = useCallback(
    async (action: FormActionConfig) => {
      const actionContext = { ...context, $data: formData };

      // 检查可见性
      if (action.visible && !ExpressionParser.evaluate(action.visible, actionContext)) {
        return;
      }

      // 检查禁用状态
      if (action.disabled && ExpressionParser.evaluate(action.disabled, actionContext)) {
        return;
      }

      if (action.type === 'submit') {
        await handleSubmit(new Event('submit') as any);
      } else if (action.onClick) {
        try {
          ExpressionParser.evaluate(action.onClick, actionContext);
        } catch (error) {
          console.error('Action click error:', error);
        }
      }
    },
    [context, formData, handleSubmit],
  );

  // 渲染表单字段
  const renderField = (field: FormFieldConfig) => {
    const fieldContext = { ...context, $data: formData, $field: field };
    const fieldValue = formData[field.name];

    // 检查字段可见性
    if (field.visible && !ExpressionParser.evaluate(field.visible, fieldContext)) {
      return null;
    }

    // 解析动态属性
    const label = field.label ? ExpressionParser.evaluate(field.label, fieldContext) : undefined;
    const placeholder = field.placeholder
      ? ExpressionParser.evaluate(field.placeholder, fieldContext)
      : undefined;
    const disabled = field.disabled
      ? ExpressionParser.evaluate(field.disabled, fieldContext)
      : false;
    const required = field.required
      ? ExpressionParser.evaluate(field.required, fieldContext)
      : false;

    const commonProps = {
      name: field.name,
      label,
      placeholder,
      disabled,
      required,
      value: fieldValue,
      context: fieldContext,
      validationRules: field.validationRules,
      size: field.size || schema.size,
      variant: field.variant || schema.variant,
      onChange: (value: any) => handleFieldChange(field.name, value),
      onBlur: () => setTouched((prev) => ({ ...prev, [field.name]: true })),
    };

    // 根据字段类型渲染对应组件
    switch (field.type) {
      case 'input':
        return (
          <Input
            key={field.name}
            {...commonProps}
            type={
              ['text', 'password', 'email', 'number', 'tel', 'url'].includes(field.inputType || '')
                ? (field.inputType as any)
                : 'text'
            }
            maxLength={field.maxLength}
            minLength={field.minLength}
          />
        );

      case 'select':
        return (
          <Select
            key={field.name}
            {...commonProps}
            options={field.options}
            dataSource={field.dataSource}
            multiple={field.multiple}
            searchable={field.searchable}
          />
        );

      case 'textarea':
        return (
          <Textarea
            key={field.name}
            {...commonProps}
            rows={field.rows}
            maxLength={field.maxLength}
            minLength={field.minLength}
            showCount={field.showCount}
          />
        );

      case 'checkbox':
        return (
          <Checkbox
            key={field.name}
            {...commonProps}
            options={field.options}
            dataSource={field.dataSource}
            variant="default"
          />
        );

      case 'radio':
        return (
          <Radio
            key={field.name}
            {...commonProps}
            options={field.options}
            dataSource={field.dataSource}
            variant="default"
          />
        );

      case 'date':
        return (
          <DatePicker
            key={field.name}
            {...commonProps}
            format={field.format}
            showTime={field.showTime}
            minDate={field.minDate}
            maxDate={field.maxDate}
          />
        );

      default:
        console.warn(`Unsupported field type: ${field.type}`);
        return null;
    }
  };

  // 渲染操作按钮
  const renderActions = () => {
    if (!schema.actions?.length) return null;

    return (
      <div
        className={cn(
          'flex gap-3',
          schema.layout === 'horizontal' ? 'justify-end' : 'justify-center',
          schema.layout === 'inline' && 'inline-flex',
        )}
      >
        {schema.actions.map((action) => {
          const actionContext = { ...context, $data: formData };

          // 检查可见性
          if (action.visible && !ExpressionParser.evaluate(action.visible, actionContext)) {
            return null;
          }

          const disabled = action.disabled
            ? ExpressionParser.evaluate(action.disabled, actionContext)
            : false;
          const loading = action.type === 'submit' ? isSubmitting : false;

          return (
            <Button
              key={action.key}
              type={action.type === 'submit' ? 'submit' : 'button'}
              variant={action.variant || (action.type === 'submit' ? 'primary' : 'default')}
              size={action.size || schema.size}
              disabled={disabled}
              loading={loading}
              onClick={() => handleAction(action)}
            >
              {st(action.label as any)}
            </Button>
          );
        })}
      </div>
    );
  };

  if (!isVisible) return null;

  return (
    <form
      className={cn(
        'smart-form',
        schema.layout === 'horizontal' && 'space-y-4',
        schema.layout === 'vertical' && 'space-y-6',
        schema.layout === 'inline' && 'flex flex-wrap items-end gap-4',
        className,
      )}
      onSubmit={handleSubmit}
    >
      {/* 表单标题 */}
      {schema.title && (
        <div className="mb-6">
          <h2 className="text-xl font-semibold text-gray-900">{st(schema.title as any)}</h2>
          {schema.description && (
            <p className="mt-1 text-sm text-gray-600">{st(schema.description as any)}</p>
          )}
        </div>
      )}

      {/* 表单字段 */}
      <div
        className={cn(
          schema.layout === 'horizontal' && 'space-y-4',
          schema.layout === 'vertical' && 'space-y-6',
          schema.layout === 'inline' && 'flex flex-wrap gap-4',
        )}
      >
        {schema.fields.map(renderField)}
      </div>

      {/* 操作按钮 */}
      {schema.actions && (
        <div
          className={cn(
            'mt-8 border-t border-gray-200 pt-6',
            schema.layout === 'inline' && 'mt-4 pt-4',
          )}
        >
          {renderActions()}
        </div>
      )}
    </form>
  );
};

export default Form;
