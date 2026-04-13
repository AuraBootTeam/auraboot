import React, { useRef } from 'react';
import type { InputProps } from '~/plugins/core-designer/components/studio/domain/schema/smart-components';
import { useExpressionValue, useSmartField } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { Input as BaseInput } from '~/ui/ui/input';
import { FieldBase } from '~/ui/ui/field-base';
import {
  fieldInputHeightStyles,
  fieldSizeStyles,
  fieldVariantStyles,
  fieldErrorFocusStyles,
} from '~/ui/ui/field-styles';

const Input: React.FC<InputProps> = ({
  name,
  label,
  placeholder,
  type = 'text',
  value,
  defaultValue,
  required = false,
  disabled = false,
  size = 'medium',
  variant = 'default',
  className = '',
  maxLength,
  minLength,
  pattern,
  validationRules = [],
  helpText,
  context,
  expressions = {},
  visible,
  onChange,
  onBlur,
  onFocus,
  inputType, // Extract inputType to prevent it from being passed to DOM
  ...restProps
}) => {
  // 从 restProps 中提取 readOnly 属性
  const { readOnly, ...otherRestProps } = restProps as any;

  const st = useSmartText();

  // 创建 input 元素的引用
  const inputRef = useRef<HTMLInputElement>(null);

  const {
    labelText,
    placeholderText,
    helpText: helpTextText,
    required: requiredValue,
    disabled: disabledValue,
    visible: isVisible,
  } = useSmartFieldContract({
    label,
    placeholder,
    helpText,
    required,
    disabled,
    expressions,
    context,
    visible,
  });

  const field = useSmartField<string>({
    name,
    value,
    defaultValue,
    required: requiredValue,
    validationRules,
    context,
    onChange,
    onBlur,
  });

  // 表达式解析
  const isDisabled = disabledValue;
  const isReadOnly = useExpressionValue(readOnly, context);

  const meta = useSmartFieldMeta({ field });
  const errorText = meta.meta.error ? st(meta.meta.error) : undefined;

  const inputClasses = [
    fieldSizeStyles[size],
    fieldInputHeightStyles[size],
    fieldVariantStyles[variant],
    className,
    meta.showError ? `border-red-500 ${fieldErrorFocusStyles}` : '',
  ]
    .filter(Boolean)
    .join(' ');

  // 处理输入变化
  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const newValue = e.target.value;
    field.setValue(newValue);
  };

  // 处理失焦
  const handleInputBlur = () => {
    field.onBlur();
  };

  // 处理聚焦
  const handleInputFocus = () => {
    onFocus?.();
  };

  // 处理 label 点击 - 聚焦并选中所有文本
  const handleLabelClick = (e: React.MouseEvent<HTMLLabelElement>) => {
    // 阻止默认行为，我们手动处理聚焦
    e.preventDefault();

    if (inputRef.current && !isDisabled && !isReadOnly) {
      // 聚焦到输入框
      inputRef.current.focus();

      // 选中所有文本（对于文本输入框）
      const textInputTypes = ['text', 'email', 'password', 'search', 'url', 'tel'];
      if (textInputTypes.includes(type)) {
        inputRef.current.select();
      }

      // 触发 onFocus 回调
      onFocus?.();
    }
  };

  // 安全的字符串转换函数
  const getStringValue = (val: any): string => {
    if (val === null || val === undefined) return '';
    if (typeof val === 'string') return val;
    if (typeof val === 'object') {
      return val.name || val.label || val.value || JSON.stringify(val);
    }
    return String(val);
  };

  // 同步外部 value 变化
  if (!isVisible) {
    return null;
  }

  return (
    <FieldBase
      id={name}
      label={labelText}
      required={requiredValue}
      helpText={helpTextText}
      error={meta.showError ? errorText : undefined}
      className="mb-4"
      labelClassName="cursor-pointer"
      onLabelClick={handleLabelClick}
    >
      <BaseInput
        ref={inputRef}
        id={name}
        name={name}
        type={inputType || type}
        value={getStringValue(field.value)}
        placeholder={placeholderText}
        disabled={isDisabled}
        readOnly={isReadOnly}
        maxLength={maxLength}
        minLength={minLength}
        pattern={pattern}
        className={inputClasses}
        onChange={handleChange}
        onBlur={handleInputBlur}
        onFocus={handleInputFocus}
        aria-describedby={meta.showError ? `${name}-error` : undefined}
        aria-required={requiredValue}
        {...otherRestProps}
      />

      {/* 字符计数 */}
      {maxLength && (
        <div className="mt-1 text-right text-xs text-gray-500 dark:text-gray-400">
          {getStringValue(field.value).length}/{maxLength}
        </div>
      )}
    </FieldBase>
  );
};

export { Input };

export function InputSideBar({ onChange, focusItem }: { onChange: any; focusItem: any }) {
  let str = focusItem.props?.required?.toLowerCase();
  let fromServerCheckedStatus = str === 'true';
  let [isChecked, setIsChecked] = React.useState(fromServerCheckedStatus);

  const handleCheckboxChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    setIsChecked(!isChecked);
    (event.target as any).value = String(!isChecked);
    onChange(event);
  };

  return (
    <div className="w-full rounded-lg bg-white p-4 shadow-sm dark:bg-gray-800">
      <h2 className="mb-6 border-b pb-3 text-center text-xl font-bold text-gray-900 dark:text-white">
        文本属性设置
      </h2>

      <div className="space-y-5">
        <div className="flex items-center">
          <label
            htmlFor="props.name"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            名称：
          </label>
          <input
            name="props.name"
            onChange={onChange}
            value={focusItem.props?.name || ''}
            className="flex-1 rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          />
        </div>

        <div className="flex items-center">
          <label className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300">
            必填：
          </label>
          <div className="flex items-center">
            <input
              type="checkbox"
              id="props.required"
              name="props.required"
              onChange={handleCheckboxChange}
              checked={isChecked}
              value={isChecked + ''}
              className="peer sr-only"
            />
            <label
              htmlFor="props.required"
              className="relative inline-flex cursor-pointer items-center"
            >
              <div className="peer h-6 w-11 rounded-full bg-gray-200 peer-checked:bg-blue-600 peer-focus:ring-4 peer-focus:ring-blue-300 peer-focus:outline-none after:absolute after:top-[2px] after:left-[2px] after:h-5 after:w-5 after:rounded-full after:border after:border-gray-300 after:bg-white after:transition-all after:content-[''] peer-checked:after:translate-x-full peer-checked:after:border-white rtl:peer-checked:after:-translate-x-full dark:border-gray-600 dark:bg-gray-700 dark:peer-focus:ring-blue-800"></div>
            </label>
          </div>
        </div>

        <div className="flex items-center">
          <label className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300">
            内联显示：
          </label>
          <div className="flex items-center">
            <input
              type="checkbox"
              id="props.inline"
              name="props.inline"
              onChange={onChange}
              checked={focusItem.props?.inline || false}
              value={focusItem.props?.inline || false}
              className="peer sr-only"
            />
            <label
              htmlFor="props.inline"
              className="relative inline-flex cursor-pointer items-center"
            >
              <div className="peer h-6 w-11 rounded-full bg-gray-200 peer-checked:bg-blue-600 peer-focus:ring-4 peer-focus:ring-blue-300 peer-focus:outline-none after:absolute after:top-[2px] after:left-[2px] after:h-5 after:w-5 after:rounded-full after:border after:border-gray-300 after:bg-white after:transition-all after:content-[''] peer-checked:after:translate-x-full peer-checked:after:border-white rtl:peer-checked:after:-translate-x-full dark:border-gray-600 dark:bg-gray-700 dark:peer-focus:ring-blue-800"></div>
            </label>
          </div>
        </div>
      </div>
    </div>
  );
}

export default Input;
