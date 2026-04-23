import React, { forwardRef } from 'react';
import { useActionData } from 'react-router';
import clsx from 'clsx';

export type DateSize = 'small' | 'medium' | 'large';
export type DateVariant = 'default' | 'error';

interface DateProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'size'> {
  /** 标签文本 */
  label?: string;
  /** 是否只读 */
  readOnly?: boolean;
  /** 尺寸 */
  size?: DateSize;
  /** 样式变体 */
  variant?: DateVariant;
  /** 占位符文本 */
  placeholder?: string;
  /** 错误信息 */
  error?: string;
  /** 是否显示清除按钮 */
  clearable?: boolean;
  /** 清除回调 */
  onClear?: () => void;
  /** 是否内联显示（label和input在一行） */
  inline?: boolean;
  /** 最小日期 */
  minDate?: string;
  /** 最大日期 */
  maxDate?: string;
  /** 是否显示时间 */
  showTime?: boolean;
  /** 是否显示今天按钮 */
  showToday?: boolean;
  /** 禁用日期函数 */
  disabledDate?: (date: Date) => boolean;
  /** 布局配置 */
  layoutConfig?: any;
}

const baseStyles =
  'rounded-lg border transition-all focus:outline-none focus:ring-2 focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed';

const sizeStyles = {
  small: 'px-2 py-1 text-sm',
  medium: 'px-3 py-2 text-base',
  large: 'px-4 py-3 text-lg',
};

const variantStyles = {
  default:
    'border-gray-300 focus:border-blue-500 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white',
  error: 'border-red-300 focus:border-red-500 focus:ring-red-500 dark:border-red-600',
};

export const Date = forwardRef<HTMLInputElement, DateProps>(
  (
    {
      label,
      readOnly = false,
      size = 'medium',
      variant = 'default',
      placeholder,
      error: propError,
      clearable = false,
      onClear,
      inline = true,
      minDate,
      maxDate,
      showTime = false,
      showToday: _showToday, // Extract to prevent DOM passing
      disabledDate: _disabledDate, // Extract to prevent DOM passing
      layoutConfig: _layoutConfig, // Extract to prevent DOM passing
      className,
      name,
      value,
      defaultValue,
      ...restProps
    },
    ref,
  ) => {
    // 从 useActionData 获取错误信息
    const actionData = useActionData();
    const actionError =
      actionData?.error?.data?.name === name ? actionData?.error?.data?.desc : undefined;

    // 优先使用 prop 传入的错误，其次使用 action 错误
    const error = propError || actionError;
    const finalVariant = error ? 'error' : variant;

    // 决定使用受控还是非受控模式
    const isControlled = value !== undefined;
    const inputProps = isControlled ? { value: value || '' } : { defaultValue };

    const inputType = showTime ? 'datetime-local' : 'date';

    const labelElement = label && (
      <label
        htmlFor={name}
        className={clsx(
          'text-sm font-medium text-gray-700 dark:text-gray-300',
          inline ? 'min-w-20 whitespace-nowrap' : 'mb-2 block',
        )}
      >
        {label}
      </label>
    );

    const inputElement = (
      <div className={clsx('relative', inline ? 'flex-1' : 'w-full')}>
        <input
          ref={ref}
          type={inputType}
          id={name}
          name={name}
          placeholder={placeholder}
          disabled={readOnly}
          min={minDate}
          max={maxDate}
          className={clsx(
            baseStyles,
            sizeStyles[size],
            variantStyles[finalVariant],
            {
              'pr-10': clearable && (value || defaultValue), // 为清除按钮留出空间
              'w-full': true,
            },
            className,
          )}
          {...inputProps}
          {...restProps}
        />

        {/* 清除按钮 */}
        {clearable && value && !readOnly && (
          <button
            type="button"
            onClick={onClear}
            className="absolute top-1/2 right-2 -translate-y-1/2 p-1 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
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

    return (
      <div className="w-full">
        {inline ? (
          <div className="flex items-center gap-3">
            {labelElement}
            {inputElement}
          </div>
        ) : (
          <>
            {labelElement}
            {inputElement}
          </>
        )}

        {error && <p className="mt-1 text-sm text-red-600 dark:text-red-400">{error}</p>}
      </div>
    );
  },
);

Date.displayName = 'Date';

export default Date;

interface DateSideBarProps {
  onChange: (event: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => void;
  focusItem: { props?: Record<string, any> };
}

export function DateSideBar({ onChange, focusItem }: DateSideBarProps) {
  return (
    <div className="w-full rounded-lg bg-white p-4 shadow-sm dark:bg-gray-800">
      <h2 className="mb-6 border-b pb-3 text-center text-xl font-bold text-gray-900 dark:text-white">
        日期选择器属性设置
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
            htmlFor="props.showTime"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            显示时间：
          </label>
          <input
            type="checkbox"
            name="props.showTime"
            onChange={onChange}
            checked={focusItem.props?.showTime || false}
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
            htmlFor="props.readOnly"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            只读：
          </label>
          <input
            type="checkbox"
            name="props.readOnly"
            onChange={onChange}
            checked={focusItem.props?.readOnly || false}
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
      </div>
    </div>
  );
}
