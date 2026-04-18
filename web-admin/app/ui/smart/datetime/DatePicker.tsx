import React, { forwardRef } from 'react';
import { useActionData } from 'react-router';
import clsx from 'clsx';
import type { DatePickerProps } from '~/plugins/core-designer/components/studio/domain/schema/smart-components';
import { useExpressionValue, useSmartField } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/ui/ui/field-base';
import { FieldControl } from '~/ui/ui/field-control';
import { FieldActionGroup } from '~/ui/ui/field-action-group';
import { FieldActionButton } from '~/ui/ui/field-action-button';
import {
  fieldSizeStyles,
  fieldVariantStyles,
  fieldErrorFocusStyles,
  fieldInputHeightStyles,
  fieldFocusStyles,
  fieldControlBase,
} from '~/ui/ui/field-styles';

const baseStyles = `${fieldControlBase} focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed`;

const variantStyles = fieldVariantStyles;

export const DatePicker = forwardRef<HTMLInputElement, DatePickerProps>(
  (
    {
      name,
      label: propLabel,
      disabled: propDisabled,
      required: propRequired,
      size = 'medium',
      variant = 'default',
      dateType = 'date',
      placeholder: propPlaceholder,
      clearable = false,
      inline = true,
      minDate: propMinDate,
      maxDate: propMaxDate,
      step,
      showToday = false,
      validationRules = [],
      expressions = {},
      context = {},
      value: propValue,
      defaultValue,
      visible,
      onChange,
      onBlur,
      onClear,
      onTodayClick,
      className,
      ...restProps
    },
    ref,
  ) => {
    const st = useSmartText();

    const {
      labelText,
      placeholderText,
      helpText,
      required: requiredValue,
      disabled: disabledValue,
      visible: isVisible,
    } = useSmartFieldContract({
      label: propLabel,
      placeholder: propPlaceholder,
      helpText: expressions.helpText,
      required: propRequired,
      disabled: propDisabled,
      expressions,
      context,
      visible,
    });

    // 表达式解析
    const minDate = useExpressionValue(propMinDate || expressions.minDate, context);
    const maxDate = useExpressionValue(propMaxDate || expressions.maxDate, context);

    // 使用智能组件状态管理
    const field = useSmartField<string>({
      name,
      value: propValue,
      defaultValue,
      onChange,
      onBlur,
      validationRules,
      required: requiredValue,
      context,
    });

    // 条件渲染
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

    // 获取今天的日期字符串
    const getTodayValue = () => {
      const today = new Date();
      switch (dateType) {
        case 'date':
          return today.toISOString().split('T')[0];
        case 'datetime-local':
          return today.toISOString().slice(0, 16);
        case 'time':
          return today.toTimeString().slice(0, 5);
        case 'month':
          return today.toISOString().slice(0, 7);
        case 'week':
          const year = today.getFullYear();
          const week = Math.ceil(
            (today.getTime() - new Date(year, 0, 1).getTime()) / (7 * 24 * 60 * 60 * 1000),
          );
          return `${year}-W${week.toString().padStart(2, '0')}`;
        default:
          return today.toISOString().split('T')[0];
      }
    };

    // 处理值变化
    const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
      const newValue = e.target.value;
      field.setValue(newValue);
    };

    // 处理清除
    const handleClearClick = () => {
      field.setValue('');
      onClear?.();
    };

    // 处理今天按钮点击
    const handleTodayClick = () => {
      const todayValue = getTodayValue();
      field.setValue(todayValue);
      onTodayClick?.();
    };

    // 如果不应该渲染，返回 null
    if (!shouldRender) {
      return null;
    }

    const inputElement = (
      <FieldControl
        inline={inline}
        rightSlot={
          <FieldActionGroup>
            {showToday && (
              <FieldActionButton
                type="button"
                data-testid="date-picker-today"
                onClick={handleTodayClick}
                disabled={disabledValue}
                size="md"
                variant="solid"
              >
                今天
              </FieldActionButton>
            )}
            {clearable && field.value && !disabledValue && (
              <FieldActionButton
                type="button"
                data-testid="date-picker-clear"
                onClick={handleClearClick}
                iconOnly
              >
                <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M6 18L18 6M6 6l12 12"
                  />
                </svg>
              </FieldActionButton>
            )}
          </FieldActionGroup>
        }
      >
        <input
          ref={ref}
          type={dateType}
          id={name}
          name={name}
          data-testid={`date-picker-input-${name}`}
          value={field.value || ''}
          placeholder={placeholderText}
          disabled={disabledValue}
          min={minDate}
          max={maxDate}
          step={step}
          onChange={handleInputChange}
          onBlur={field.onBlur}
          onClick={(e) => {
            // Open the native date picker when clicking anywhere on the input
            try {
              (e.target as HTMLInputElement).showPicker?.();
            } catch {
              // showPicker() may throw in some browsers; ignore
            }
          }}
          className={clsx(
            baseStyles,
            fieldSizeStyles[size],
            fieldInputHeightStyles[size],
            variantStyles[finalVariant],
            fieldFocusStyles,
            meta.showError && fieldErrorFocusStyles,
            {
              'pr-20': (clearable && field.value) || showToday, // 为按钮留出空间
              'pr-10': clearable && field.value && !showToday,
              'w-full': true,
            },
            className,
          )}
          {...restProps}
        />
      </FieldControl>
    );

    return (
      <FieldBase
        id={name}
        label={labelText}
        required={requiredValue}
        inline={inline}
        error={meta.showError ? errorText : undefined}
        helpText={helpText}
      >
        {inputElement}
      </FieldBase>
    );
  },
);

DatePicker.displayName = 'DatePicker';

export default DatePicker;

// 保持原有的 DatePickerSideBar 组件用于设计器
export function DatePickerSideBar({
  onChange,
  focusItem,
}: {
  onChange: (value: any) => void;
  focusItem: any;
}) {
  return (
    <div className="w-full rounded-lg bg-white p-4 shadow-sm dark:bg-gray-800">
      <h2 className="mb-6 border-b pb-3 text-center text-xl font-bold text-gray-900 dark:text-white">
        日期时间选择器属性设置
      </h2>

      <div className="space-y-5">
        <div className="flex items-center">
          <label
            htmlFor="props.label"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            标签：
          </label>
          <input
            name="props.label"
            onChange={onChange}
            value={focusItem.props?.label || ''}
            className="flex-1 rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.placeholder"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            占位符：
          </label>
          <input
            name="props.placeholder"
            onChange={onChange}
            value={focusItem.props?.placeholder || ''}
            className="flex-1 rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.dateType"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            类型：
          </label>
          <select
            name="props.dateType"
            onChange={onChange}
            value={focusItem.props?.dateType || 'date'}
            className="flex-1 rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          >
            <option value="date">日期</option>
            <option value="datetime-local">日期时间</option>
            <option value="time">时间</option>
            <option value="month">月份</option>
            <option value="week">周</option>
          </select>
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.size"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            尺寸：
          </label>
          <select
            name="props.size"
            onChange={onChange}
            value={focusItem.props?.size || 'medium'}
            className="flex-1 rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          >
            <option value="small">小</option>
            <option value="medium">中</option>
            <option value="large">大</option>
          </select>
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.inline"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            内联显示：
          </label>
          <input
            type="checkbox"
            name="props.inline"
            onChange={onChange}
            checked={focusItem.props?.inline || false}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.required"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            必填：
          </label>
          <input
            type="checkbox"
            name="props.required"
            onChange={onChange}
            checked={focusItem.props?.required || false}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.showToday"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            显示今天按钮：
          </label>
          <input
            type="checkbox"
            name="props.showToday"
            onChange={onChange}
            checked={focusItem.props?.showToday || false}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.clearable"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            可清除：
          </label>
          <input
            type="checkbox"
            name="props.clearable"
            onChange={onChange}
            checked={focusItem.props?.clearable || false}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.disabled"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            禁用：
          </label>
          <input
            type="checkbox"
            name="props.disabled"
            onChange={onChange}
            checked={focusItem.props?.disabled || false}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.minDate"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            最小日期：
          </label>
          <input
            type="date"
            name="props.minDate"
            onChange={onChange}
            value={focusItem.props?.minDate || ''}
            className="flex-1 rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.maxDate"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            最大日期：
          </label>
          <input
            type="date"
            name="props.maxDate"
            onChange={onChange}
            value={focusItem.props?.maxDate || ''}
            className="flex-1 rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.step"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            步长：
          </label>
          <input
            type="number"
            name="props.step"
            onChange={onChange}
            value={focusItem.props?.step || ''}
            placeholder="时间步长（秒）"
            className="flex-1 rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          />
        </div>
      </div>
    </div>
  );
}
