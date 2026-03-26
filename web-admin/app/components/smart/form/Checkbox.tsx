import React, { forwardRef } from 'react';
import type { CheckboxProps } from '~/studio/domain/schema/smart-components';
import {
  useConditionalRender,
  useExpressionValue,
  useSmartField,
} from '~/studio/hooks/runtime/useSmartComponent';
import { useSmartFieldContract } from '~/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/components/ui/field-base';

export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(
  (
    {
      name,
      label,
      value,
      checked,
      disabled,
      required,
      size = 'medium',
      variant = 'default',
      indeterminate = false,
      context,
      visible,
      onChange,
      onBlur,
      ...props
    },
    ref,
  ) => {
    const st = useSmartText();

    const {
      labelText,
      required: requiredValue,
      disabled: resolvedDisabled,
      visible: isVisible,
    } = useSmartFieldContract({
      label,
      required,
      disabled,
      context,
      expressions: props.expressions,
      visible,
    });

    // 条件渲染检查
    const shouldRender = useConditionalRender(visible, context);
    if (!shouldRender || !isVisible) return null;

    // 状态管理 - 支持 value 和 checked 两种方式
    // 优先使用 value (用于属性面板)，否则使用 checked (用于表单)
    const initialValue = value !== undefined ? value : checked;

    const field = useSmartField<boolean>({
      name,
      value: initialValue,
      onChange,
      onBlur,
      validationRules: props.validationRules || [],
      required: requiredValue,
      context,
    });
    const meta = useSmartFieldMeta({ field });

    // 表达式解析
    const resolvedValue = useExpressionValue(value, context);

    // 处理复选框变化
    const handleCheckboxChange = (event: React.ChangeEvent<HTMLInputElement>) => {
      const newChecked = event.target.checked;
      field.setValue(newChecked);
    };

    // 处理失焦
    const handleCheckboxBlur = (event: React.FocusEvent<HTMLInputElement>) => {
      field.onBlur();
    };

    // 样式类
    const sizeClasses = {
      small: 'w-4 h-4',
      medium: 'w-5 h-5',
      large: 'w-6 h-6',
    };

    const variantClasses = {
      default:
        'text-blue-600 bg-gray-100 border-gray-300 rounded focus:ring-blue-500 dark:focus:ring-blue-600 dark:ring-offset-gray-800 focus:ring-2 dark:bg-gray-700 dark:border-gray-600',
      outline:
        'text-blue-600 bg-white border-2 border-gray-300 rounded focus:ring-blue-500 focus:border-blue-500',
      filled: 'text-blue-600 bg-gray-50 border-gray-300 rounded focus:ring-blue-500',
    };

    const containerClasses = 'flex items-center';

    const checkboxClasses = [
      sizeClasses[size as keyof typeof sizeClasses],
      variantClasses[variant as keyof typeof variantClasses],
      resolvedDisabled ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer',
      meta.showError ? 'border-red-300 focus:border-red-500 focus:ring-red-500' : '',
      'transition-colors duration-200',
    ]
      .filter(Boolean)
      .join(' ');

    return (
      <FieldBase
        id={name}
        label={labelText}
        required={requiredValue}
        labelPosition="right"
        labelClassName={`text-sm font-medium text-gray-900 dark:text-gray-300 ${
          resolvedDisabled ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer'
        }`}
        error={meta.showError ? st(meta.meta.error) : undefined}
      >
        <div className={containerClasses}>
          <input
            ref={(element) => {
              if (typeof ref === 'function') {
                ref(element);
              } else if (ref) {
                ref.current = element;
              }
              if (element && indeterminate) {
                element.indeterminate = true;
              }
            }}
            id={name}
            name={name}
            type="checkbox"
            checked={field.value || false}
            disabled={resolvedDisabled}
            required={requiredValue}
            value={resolvedValue}
            onChange={handleCheckboxChange}
            onBlur={handleCheckboxBlur}
            className={checkboxClasses}
            aria-required={requiredValue}
            aria-invalid={!!field.error}
            aria-describedby={field.error ? `${name}-error` : undefined}
            {...props}
          />
        </div>
      </FieldBase>
    );
  },
);

Checkbox.displayName = 'Checkbox';

export default Checkbox;
