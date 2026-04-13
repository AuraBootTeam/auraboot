import React, { forwardRef, useState } from 'react';
import { useActionData } from 'react-router';
import clsx from 'clsx';
import type { TextareaProps } from '~/plugins/core-designer/components/studio/domain/schema/smart-components';
import { useSmartField } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/components/ui/field-base';
import { Textarea as BaseTextarea } from '~/components/ui/textarea';
import {
  fieldSizeStyles,
  fieldVariantStyles,
  fieldErrorFocusStyles,
  fieldFocusStyles,
} from '~/components/ui/field-styles';

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(
  (
    {
      name,
      label,
      placeholder,
      disabled = false,
      required = false,
      size = 'medium',
      variant = 'default',
      maxLength,
      minLength,
      rows = 4,
      autoResize = false,
      validationRules = [],
      context,
      value: propValue,
      defaultValue,
      onChange,
      onBlur,
      visible,
      className,
      ...restProps
    },
    ref,
  ) => {
    const st = useSmartText();

    const {
      labelText,
      placeholderText,
      required: requiredValue,
      disabled: disabledValue,
      visible: isVisible,
    } = useSmartFieldContract({
      label,
      placeholder,
      required,
      disabled,
      context,
      visible,
    });

    const field = useSmartField<string>({
      name,
      value: propValue,
      defaultValue,
      required: requiredValue,
      validationRules,
      context,
      onChange,
      onBlur,
    });

    // 条件渲染和状态
    const isDisabled = disabledValue;
    const shouldRender = isVisible;

    // 从 useActionData 获取错误信息
    const actionData = useActionData();
    const actionError =
      actionData?.error?.data?.name === name ? actionData?.error?.data?.desc : undefined;

    // 综合错误信息
    const error = field.error || actionError;
    const finalVariant = error ? 'error' : variant;
    const meta = useSmartFieldMeta({ field, externalError: actionError });
    const errorText = meta.meta.error ? st(meta.meta.error) : undefined;

    // 字符计数
    const [currentLength, setCurrentLength] = useState((field.value || '').toString().length);

    // 处理值变化
    const handleTextareaChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
      const newValue = e.target.value;
      setCurrentLength(newValue.length);
      field.setValue(newValue);
    };

    if (!shouldRender) {
      return null;
    }

    // 基础样式
    return (
      <FieldBase
        id={name}
        label={labelText}
        required={requiredValue}
        error={meta.showError ? errorText : undefined}
        className={clsx('space-y-1', className)}
      >
        <div className="relative">
          <BaseTextarea
            ref={ref}
            id={name}
            name={name}
            placeholder={placeholderText}
            disabled={isDisabled}
            required={requiredValue}
            maxLength={maxLength}
            minLength={minLength}
            rows={rows}
            value={field.value || ''}
            onChange={handleTextareaChange}
            onBlur={field.onBlur}
            className={clsx(
              fieldSizeStyles[size],
              fieldVariantStyles[finalVariant],
              fieldFocusStyles,
              meta.showError && fieldErrorFocusStyles,
              isDisabled && 'cursor-not-allowed bg-gray-100 opacity-60',
              autoResize && 'resize-none',
              !autoResize && 'resize-vertical',
            )}
            {...(() => {
              // Filter out non-DOM props to prevent React warnings
              const { showCount, ...domProps } = restProps as any;
              return domProps;
            })()}
          />

          {/* 字符计数 */}
          {maxLength && (
            <div className="absolute right-2 bottom-2 bg-white px-1 text-xs text-gray-500">
              {currentLength}/{maxLength}
            </div>
          )}
        </div>
      </FieldBase>
    );
  },
);

Textarea.displayName = 'Textarea';

export default Textarea;
