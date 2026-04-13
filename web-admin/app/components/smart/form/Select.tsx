import React, { forwardRef } from 'react';
import { useActionData } from 'react-router';
import clsx from 'clsx';
import type { SelectProps } from '~/plugins/core-designer/components/studio/domain/schema/smart-components';
import { useSmartField } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useFieldDataSource } from '~/meta/hooks/useFieldDataSource';
import { useI18n } from '~/contexts/I18nContext';
import { useSmartText, translateArray } from '~/utils/i18n';
import { FieldBase } from '~/components/ui/field-base';
import { FieldControl } from '~/components/ui/field-control';
import { FieldActionGroup } from '~/components/ui/field-action-group';
import { FieldActionButton } from '~/components/ui/field-action-button';
import {
  fieldSizeStyles,
  fieldVariantStyles,
  fieldErrorFocusStyles,
  fieldInputHeightStyles,
  fieldFocusStyles,
  fieldControlBase,
} from '~/components/ui/field-styles';
import {
  Select as BaseSelect,
  SelectTrigger,
  SelectValue,
  SelectContent,
  SelectItem,
} from '~/components/ui/select';

const baseStyles = `${fieldControlBase} focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed`;

const variantStyles = fieldVariantStyles;

export const Select = forwardRef<HTMLSelectElement, SelectProps>(
  (
    {
      name,
      label: propLabel,
      placeholder: propPlaceholder,
      disabled: propDisabled,
      required: propRequired,
      size = 'medium',
      variant = 'default',
      multiple = false,
      clearable = false,
      inline = false,
      options: staticOptions = [],
      dataSource,
      validationRules = [],
      expressions = {},
      context = {},
      value: propValue,
      defaultValue,
      visible,
      onChange,
      onBlur,
      onClear,
      className,
      ...restProps
    },
    ref,
  ) => {
    const st = useSmartText();
    const { t, locale } = useI18n();

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

    // 使用智能组件状态管理
    const field = useSmartField<any>({
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

    // 数据源处理 - 只有在存在 dataSource 配置时才使用 useFieldDataSource
    const dataSourceResult = dataSource
      ? useFieldDataSource({
          staticOptions,
          dataSource: dataSource as any,
          context,
        })
      : { options: staticOptions, loading: false, error: null };

    const { options: rawOptions, loading, error: dataSourceError } = dataSourceResult;

    // 批量翻译 options 的 label 字段
    const options = translateArray(rawOptions || [], ['label'], locale, t);

    // 验证处理
    // 从 useActionData 获取错误信息
    const actionData = useActionData();
    const actionError =
      actionData?.error?.data?.name === name ? actionData?.error?.data?.desc : undefined;

    // 综合错误信息
    const error = field.error || dataSourceError || actionError;
    const finalVariant = error ? 'error' : variant;
    const meta = useSmartFieldMeta({
      field,
      externalError: dataSourceError || actionError,
    });

    // 处理值变化 (native select for multiple mode)
    const handleSelectChange = (event: React.ChangeEvent<HTMLSelectElement>) => {
      const newValue = multiple
        ? Array.from(event.target.selectedOptions, (option) => option.value)
        : event.target.value;

      field.setValue(newValue);
    };

    // Radix Select value change handler (single-select mode)
    const handleRadixValueChange = (newValue: string) => {
      field.setValue(newValue);
    };

    // 处理清除
    const handleClearClick = () => {
      const clearedValue = multiple ? [] : '';
      field.setValue(clearedValue);
      onClear?.();
    };

    // 如果不应该渲染，返回 null
    if (!shouldRender) {
      return null;
    }

    const errorText = meta.meta.error ? st(meta.meta.error) : undefined;

    // Single-select mode: use Radix Select
    if (!multiple) {
      const currentValue =
        field.value != null && field.value !== '' ? String(field.value) : undefined;
      const selectedLabel = options?.find((o) => String(o.value) === currentValue)?.label;
      const actionSelectLabel = (() => {
        const key = 'action.select';
        const translated = t(key);
        if (translated && translated !== key) {
          return translated;
        }
        return locale === 'zh-CN' ? '请选择' : 'Select';
      })();

      const radixSelectElement = (
        <div className="relative">
          <BaseSelect
            value={currentValue}
            onValueChange={handleRadixValueChange}
            disabled={disabledValue || loading}
          >
            <SelectTrigger
              data-testid={`select-trigger-${name}`}
              className={clsx(
                fieldSizeStyles[size],
                fieldInputHeightStyles[size],
                meta.showError && 'border-red-500 focus:ring-red-500',
                clearable && currentValue && 'pr-8',
                loading && 'opacity-50',
                className,
              )}
              onBlur={field.onBlur}
            >
              <SelectValue placeholder={placeholderText || actionSelectLabel}>
                {loading ? t('common.loading') || '...' : selectedLabel}
              </SelectValue>
            </SelectTrigger>
            <SelectContent>
              {options?.map((option) => (
                <SelectItem
                  key={option.key || option.value}
                  value={String(option.value)}
                  disabled={option.disabled}
                >
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </BaseSelect>
          {/* Clear button overlaid on trigger */}
          {clearable && currentValue && !disabledValue && !loading && (
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                handleClearClick();
              }}
              className="absolute top-1/2 right-8 -translate-y-1/2 rounded-sm p-0.5 text-gray-400 hover:text-gray-600"
              tabIndex={-1}
            >
              <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M6 18L18 6M6 6l12 12"
                />
              </svg>
            </button>
          )}
        </div>
      );

      // Hidden input for form submission
      const hiddenInput = <input type="hidden" name={name} value={currentValue || ''} />;

      return (
        <FieldBase
          id={name}
          label={labelText}
          required={requiredValue}
          inline={inline}
          error={meta.showError ? errorText : undefined}
          helpText={helpText}
        >
          {radixSelectElement}
          {hiddenInput}
        </FieldBase>
      );
    }

    // Multiple-select mode: keep native <select>
    const selectElement = (
      <FieldControl
        inline={inline}
        rightSlot={
          <FieldActionGroup>
            {clearable && field.value && !multiple && !disabledValue && !loading && (
              <FieldActionButton type="button" onClick={handleClearClick} iconOnly>
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
            {loading && (
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-blue-500 border-t-transparent"></div>
            )}
          </FieldActionGroup>
        }
      >
        <select
          ref={ref}
          id={name}
          name={name}
          multiple={multiple}
          disabled={disabledValue || loading}
          value={field.value ?? (multiple ? [] : '')}
          onChange={handleSelectChange}
          onBlur={field.onBlur}
          className={clsx(
            baseStyles,
            fieldSizeStyles[size],
            fieldInputHeightStyles[size],
            variantStyles[finalVariant],
            fieldFocusStyles,
            meta.showError && fieldErrorFocusStyles,
            {
              'pr-10': clearable && field.value && !multiple,
              'w-full': true,
              'opacity-50': loading,
            },
            className,
          )}
          {...restProps}
        >
          {placeholderText && !multiple && (
            <option value="" disabled hidden>
              {placeholderText}
            </option>
          )}
          {loading && (
            <option value="" disabled>
              加载中...
            </option>
          )}
          {options?.map((option) => (
            <option
              key={option.key || option.value}
              value={option.value}
              disabled={option.disabled}
            >
              {option.label}
            </option>
          ))}
        </select>
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
        {selectElement}
      </FieldBase>
    );
  },
);

Select.displayName = 'Select';

// 保持原有的 SelectSideBar 组件用于设计器
export function SelectSideBar({
  onChange,
  focusItem,
}: {
  onChange: (value: any) => void;
  focusItem: any;
}) {
  return (
    <div className="w-full rounded-lg bg-white p-4 shadow-sm dark:bg-gray-800">
      <h2 className="mb-6 border-b pb-3 text-center text-xl font-bold text-gray-900 dark:text-white">
        下拉框属性设置
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
            htmlFor="props.variant"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            样式：
          </label>
          <select
            name="props.variant"
            onChange={onChange}
            value={focusItem.props?.variant || 'default'}
            className="flex-1 rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          >
            <option value="default">默认</option>
            <option value="outline">轮廓</option>
            <option value="filled">填充</option>
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
            htmlFor="props.multiple"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            多选：
          </label>
          <input
            type="checkbox"
            name="props.multiple"
            onChange={onChange}
            checked={focusItem.props?.multiple || false}
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

        <div>
          <label className="mb-2 block text-sm font-medium text-gray-700 dark:text-gray-300">
            选项配置：
          </label>
          <textarea
            name="props.optionsJson"
            onChange={onChange}
            value={
              focusItem.props?.optionsJson ||
              '[{"key":"1","value":"option1","label":"选项1"},{"key":"2","value":"option2","label":"选项2"}]'
            }
            placeholder='[{"key":"1","value":"option1","label":"选项1"}]'
            rows={4}
            className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          />
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
            JSON 格式的选项数据，支持 key、value、label、disabled 字段
          </p>
        </div>
      </div>
    </div>
  );
}

export default Select;
