import React, { forwardRef, useCallback, useMemo, useState } from 'react';
import { useActionData } from 'react-router';
import clsx from 'clsx';
import type { SelectProps } from '~/plugins/core-designer/components/studio/domain/schema/smart-components';
import { useSmartField } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useFieldDataSource } from '~/framework/meta/hooks/useFieldDataSource';
import { useI18n } from '~/contexts/I18nContext';
import { useSmartText, translateArray } from '~/utils/i18n';
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
import {
  Select as BaseSelect,
  SelectTrigger,
  SelectValue,
  SelectContent,
  SelectItem,
} from '~/ui/ui/select';
import { sanitizeSmartDomProps } from './domProps';

const baseStyles = `${fieldControlBase} focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed`;

const variantStyles = fieldVariantStyles;

const EMPTY_OPTIONS: SelectProps['options'] = [];

export const CREATE_NEW_VALUE = '__aura_create_new__';

export const Select = forwardRef<HTMLSelectElement, SelectProps>(
  (
    {
      name,
      label: propLabel,
      placeholder: propPlaceholder,
      disabled: propDisabled,
      // A <select> has no native read-only state, so readOnly is rendered as
      // disabled (value shown, not changeable). The controlled value still
      // submits from form state.
      readOnly: propReadOnly,
      required: propRequired,
      size = 'medium',
      variant = 'default',
      multiple = false,
      clearable = false,
      inline = false,
      options: staticOptions = EMPTY_OPTIONS,
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
      canCreateNew = false,
      createNewLabel,
      onCreateNew,
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
      disabled: propDisabled || propReadOnly,
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

    const {
      options: rawOptions,
      loading,
      error: dataSourceError,
      refetch,
    } = useFieldDataSource({
      staticOptions,
      dataSource: dataSource as any,
      context,
    });
    const [radixOpen, setRadixOpen] = useState(false);
    const [searchQuery, setSearchQuery] = useState('');

    // 批量翻译 options 的 label 字段
    const options = translateArray(rawOptions || [], ['label'], locale, t);
    const filteredOptions = useMemo(() => {
      const query = searchQuery.trim().toLowerCase();
      if (!query) return options;
      return options.filter((option) => {
        const label = String(option.label ?? '').toLowerCase();
        const value = String(option.value ?? '').toLowerCase();
        return label.includes(query) || value.includes(query);
      });
    }, [options, searchQuery]);

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
      if (newValue === CREATE_NEW_VALUE) {
        onCreateNew?.();
        return;
      }
      if (newValue === '' && field.value != null && String(field.value) !== '') {
        return;
      }
      setSearchQuery('');
      field.setValue(newValue);
    };

    const handleRadixOpenChange = useCallback(
      (open: boolean) => {
        setRadixOpen(open);
        if (!open) {
          setSearchQuery('');
        }
        if (open && dataSource && !disabledValue) {
          void refetch();
        }
      },
      [dataSource, disabledValue, refetch],
    );

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
      const currentValue = field.value != null ? String(field.value) : '';
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
            open={radixOpen}
            onOpenChange={handleRadixOpenChange}
            disabled={disabledValue || (loading && !radixOpen)}
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
              <SelectValue
                placeholder={
                  loading ? t('common.loading') || '...' : placeholderText || actionSelectLabel
                }
              />
            </SelectTrigger>
            <SelectContent>
              <div className="border-border bg-panel sticky top-0 z-10 border-b p-2">
                <input
                  type="text"
                  value={searchQuery}
                  onChange={(event) => setSearchQuery(event.target.value)}
                  onKeyDown={(event) => event.stopPropagation()}
                  onPointerDown={(event) => event.stopPropagation()}
                  placeholder={
                    t('common.search') !== 'common.search'
                      ? t('common.search')
                      : locale === 'zh-CN'
                        ? '搜索'
                        : 'Search'
                  }
                  data-testid={`select-search-${name}`}
                  className="border-border-strong focus:border-accent focus-visible:shadow-focus bg-panel text-text placeholder:text-text-3 h-8 w-full rounded-md border px-2 text-sm focus:outline-none"
                />
              </div>
              {filteredOptions.length === 0 && !loading && (
                <div className="text-text-3 px-3 py-2 text-sm">
                  {t('common.noResults') !== 'common.noResults'
                    ? t('common.noResults')
                    : locale === 'zh-CN'
                      ? '无匹配结果'
                      : 'No results'}
                </div>
              )}
              {filteredOptions?.map((option) => (
                <SelectItem
                  key={option.key || option.value}
                  value={String(option.value)}
                  disabled={option.disabled}
                >
                  {option.label}
                </SelectItem>
              ))}
              {canCreateNew && (
                <SelectItem
                  key={CREATE_NEW_VALUE}
                  value={CREATE_NEW_VALUE}
                  data-testid={`select-create-new-${name}`}
                  className="text-accent font-medium"
                >
                  {createNewLabel ??
                    (t('action.createNew') !== 'action.createNew'
                      ? t('action.createNew')
                      : locale === 'zh-CN'
                        ? '+ 新建'
                        : '+ New')}
                </SelectItem>
              )}
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
              className="text-text-3 hover:text-text-2 absolute top-1/2 right-8 -translate-y-1/2 rounded-sm p-0.5"
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
      const hiddenInput = <input type="hidden" name={name} value={currentValue} />;

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
            {canCreateNew && !disabledValue && !loading && (
              <FieldActionButton
                type="button"
                onClick={() => onCreateNew?.()}
                data-testid={`select-create-new-${name}`}
                iconOnly
              >
                <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M12 4v16m8-8H4"
                  />
                </svg>
              </FieldActionButton>
            )}
            {loading && (
              <div className="border-accent h-4 w-4 animate-spin rounded-full border-2 border-t-transparent"></div>
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
          {...sanitizeSmartDomProps(restProps as Record<string, unknown>)}
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
    <div className="rounded-card bg-panel shadow-card w-full p-4">
      <h2 className="border-border text-text mb-6 border-b pb-3 text-center text-xl font-bold">
        下拉框属性设置
      </h2>

      <div className="space-y-5">
        <div className="flex items-center">
          <label
            htmlFor="props.label"
            className="text-text-2 w-1/4 text-sm font-medium"
          >
            标签：
          </label>
          <input
            name="props.label"
            onChange={onChange}
            value={focusItem.props?.label || ''}
            className="rounded-control border-border-strong bg-panel text-text shadow-card focus:border-accent focus-visible:shadow-focus flex-1 border px-3 py-2 focus:outline-none"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.placeholder"
            className="text-text-2 w-1/4 text-sm font-medium"
          >
            占位符：
          </label>
          <input
            name="props.placeholder"
            onChange={onChange}
            value={focusItem.props?.placeholder || ''}
            className="rounded-control border-border-strong bg-panel text-text shadow-card focus:border-accent focus-visible:shadow-focus flex-1 border px-3 py-2 focus:outline-none"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.size"
            className="text-text-2 w-1/4 text-sm font-medium"
          >
            尺寸：
          </label>
          <select
            name="props.size"
            onChange={onChange}
            value={focusItem.props?.size || 'medium'}
            className="rounded-control border-border-strong bg-panel text-text shadow-card focus:border-accent focus-visible:shadow-focus flex-1 border px-3 py-2 focus:outline-none"
          >
            <option value="small">小</option>
            <option value="medium">中</option>
            <option value="large">大</option>
          </select>
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.variant"
            className="text-text-2 w-1/4 text-sm font-medium"
          >
            样式：
          </label>
          <select
            name="props.variant"
            onChange={onChange}
            value={focusItem.props?.variant || 'default'}
            className="rounded-control border-border-strong bg-panel text-text shadow-card focus:border-accent focus-visible:shadow-focus flex-1 border px-3 py-2 focus:outline-none"
          >
            <option value="default">默认</option>
            <option value="outline">轮廓</option>
            <option value="filled">填充</option>
          </select>
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.inline"
            className="text-text-2 w-1/4 text-sm font-medium"
          >
            内联显示：
          </label>
          <input
            type="checkbox"
            name="props.inline"
            onChange={onChange}
            checked={focusItem.props?.inline || false}
            className="border-border-strong text-accent focus-visible:shadow-focus h-4 w-4 rounded focus:outline-none"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.multiple"
            className="text-text-2 w-1/4 text-sm font-medium"
          >
            多选：
          </label>
          <input
            type="checkbox"
            name="props.multiple"
            onChange={onChange}
            checked={focusItem.props?.multiple || false}
            className="border-border-strong text-accent focus-visible:shadow-focus h-4 w-4 rounded focus:outline-none"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.clearable"
            className="text-text-2 w-1/4 text-sm font-medium"
          >
            可清除：
          </label>
          <input
            type="checkbox"
            name="props.clearable"
            onChange={onChange}
            checked={focusItem.props?.clearable || false}
            className="border-border-strong text-accent focus-visible:shadow-focus h-4 w-4 rounded focus:outline-none"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.required"
            className="text-text-2 w-1/4 text-sm font-medium"
          >
            必填：
          </label>
          <input
            type="checkbox"
            name="props.required"
            onChange={onChange}
            checked={focusItem.props?.required || false}
            className="border-border-strong text-accent focus-visible:shadow-focus h-4 w-4 rounded focus:outline-none"
          />
        </div>

        <div>
          <label className="text-text-2 mb-2 block text-sm font-medium">
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
            className="rounded-control border-border-strong bg-panel text-text shadow-card focus:border-accent focus-visible:shadow-focus w-full border px-3 py-2 font-mono text-sm focus:outline-none"
          />
          <p className="text-text-3 mt-1 text-xs">
            JSON 格式的选项数据，支持 key、value、label、disabled 字段
          </p>
        </div>
      </div>
    </div>
  );
}

export default Select;
