import React, { forwardRef } from 'react';
import { useActionData } from 'react-router';
import clsx from 'clsx';
import type { RadioProps } from '~/studio/domain/schema/smart-components';
import { useDataSource, useSmartField } from '~/studio/hooks/runtime/useSmartComponent';
import { useSmartFieldContract } from '~/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/components/ui/field-base';

export const Radio = forwardRef<HTMLDivElement, RadioProps>(
  (
    {
      name,
      label: propLabel,
      disabled: propDisabled,
      required: propRequired,
      direction = 'vertical',
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
      className,
      ...restProps
    },
    ref,
  ) => {
    const st = useSmartText();

    const {
      labelText,
      helpText,
      required: requiredValue,
      disabled: disabledValue,
      visible: isVisible,
    } = useSmartFieldContract({
      label: propLabel,
      helpText: expressions.helpText,
      required: propRequired,
      disabled: propDisabled,
      expressions,
      context,
      visible,
    });

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

    // 数据源处理
    const resolvedDataSource = dataSource ? ({ type: 'static', ...dataSource } as any) : undefined;
    const {
      options,
      loading,
      error: dataSourceError,
    } = useDataSource({
      staticOptions,
      dataSource: resolvedDataSource,
      context,
    });

    // 从 useActionData 获取错误信息
    const actionData = useActionData();
    const actionError =
      actionData?.error?.data?.name === name ? actionData?.error?.data?.desc : undefined;

    // 综合错误信息
    const error = field.error || dataSourceError || actionError;

    // 处理单选框变化
    const handleRadioChange = (optionValue: string) => {
      field.setValue(optionValue);
    };

    // 如果不应该渲染，返回 null
    if (!shouldRender) {
      return null;
    }

    const radioElement = (
      <div className={clsx('space-y-2', inline ? 'flex-1' : 'w-full', className)}>
        {/* 加载状态 */}
        {loading && (
          <div className="flex items-center space-x-2 text-sm text-gray-500">
            <div className="h-4 w-4 animate-spin rounded-full border-2 border-blue-500 border-t-transparent"></div>
            <span>加载选项中...</span>
          </div>
        )}

        {/* 选项列表 */}
        <div className={clsx(direction === 'horizontal' ? 'flex flex-wrap gap-4' : 'space-y-2')}>
          {options.map((option) => (
            <label key={option.key || option.value} className="flex items-center space-x-2">
              <input
                type="radio"
                name={name}
                value={option.value}
                checked={field.value === option.value}
                disabled={disabledValue || loading || option.disabled}
                onChange={() => handleRadioChange(option.value)}
                onBlur={field.onBlur}
                className="h-4 w-4 border-gray-300 text-blue-600 focus:ring-blue-500 disabled:opacity-50"
              />
              <span
                className={clsx(
                  'text-sm text-gray-700 dark:text-gray-300',
                  (disabledValue || loading || option.disabled) && 'opacity-50',
                )}
              >
                {option.label}
              </span>
            </label>
          ))}
        </div>

        {/* 空状态 */}
        {!loading && options.length === 0 && (
          <div className="text-sm text-gray-500 dark:text-gray-400">暂无选项</div>
        )}
      </div>
    );

    const meta = useSmartFieldMeta({
      field,
      externalError: dataSourceError || actionError,
    });
    const errorText = meta.meta.error ? st(meta.meta.error) : undefined;

    return (
      <FieldBase
        id={name}
        label={labelText}
        required={requiredValue}
        inline={inline}
        error={meta.showError ? errorText : undefined}
        helpText={helpText}
      >
        <div ref={ref} className="w-full">
          {radioElement}
        </div>
      </FieldBase>
    );
  },
);

Radio.displayName = 'Radio';

export default Radio;

// 保持原有的 RadioSideBar 组件用于设计器
export function RadioSideBar({
  onChange,
  focusItem,
}: {
  onChange: (value: any) => void;
  focusItem: any;
}) {
  return (
    <div className="w-full rounded-lg bg-white p-4 shadow-sm dark:bg-gray-800">
      <h2 className="mb-6 border-b pb-3 text-center text-xl font-bold text-gray-900 dark:text-white">
        单选框属性设置
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
            htmlFor="props.direction"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            排列方向：
          </label>
          <select
            name="props.direction"
            onChange={onChange}
            value={focusItem.props?.direction || 'vertical'}
            className="flex-1 rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          >
            <option value="vertical">垂直</option>
            <option value="horizontal">水平</option>
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
