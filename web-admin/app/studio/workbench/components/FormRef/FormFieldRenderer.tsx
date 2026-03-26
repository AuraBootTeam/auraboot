/**
 * 表单字段渲染器
 *
 * 根据字段配置渲染不同类型的表单控件
 */

import React, { useState, useCallback, useMemo } from 'react';
import type { FormFieldConfig } from '~/studio/workbench/components/FormRef/types';
import { useLocalizedText } from '~/utils/i18n';

/**
 * 字段渲染器属性
 */
interface FormFieldRendererProps {
  field: FormFieldConfig;
  value: any;
  error?: string[];
  disabled?: boolean;
  readonly?: boolean;
  onChange: (value: any) => void;
  styleOverrides?: {
    container?: React.CSSProperties;
    field?: React.CSSProperties;
    label?: React.CSSProperties;
    input?: React.CSSProperties;
    button?: React.CSSProperties;
  };
}

/**
 * 表单字段渲染器组件
 */
export const FormFieldRenderer: React.FC<FormFieldRendererProps> = ({
  field,
  value,
  error,
  disabled = false,
  readonly = false,
  onChange,
  styleOverrides = {},
}) => {
  const [focused, setFocused] = useState(false);
  const [touched, setTouched] = useState(false);

  // 使用本地化文本处理
  const lt = useLocalizedText();

  // 处理值变化
  const handleChange = useCallback(
    (newValue: any) => {
      if (disabled || readonly) return;
      onChange(newValue);
    },
    [disabled, readonly, onChange],
  );

  // 处理焦点事件
  const handleFocus = useCallback(() => {
    setFocused(true);
  }, []);

  const handleBlur = useCallback(() => {
    setFocused(false);
    setTouched(true);
  }, []);

  // 渲染标签
  const renderLabel = () => {
    if (!field.label) return null;

    return (
      <label
        className={`form-label mb-1 block text-sm font-medium text-gray-700 ${field.required ? 'required' : ''}`}
        style={styleOverrides.label}
      >
        {lt(field.label)}
        {field.required && <span className="ml-1 text-red-500">*</span>}
      </label>
    );
  };

  // 渲染错误信息
  const renderError = () => {
    if (!error || error.length === 0 || (!touched && !focused)) return null;

    return (
      <div className="form-error mt-1">
        {error.map((err, index) => (
          <p key={index} className="text-sm text-red-600">
            {err}
          </p>
        ))}
      </div>
    );
  };

  // 渲染帮助文本
  const renderHelp = () => {
    if (!field.placeholder) return null;

    return (
      <div className="form-help mt-1">
        <p className="text-sm text-gray-500">{lt(field.placeholder)}</p>
      </div>
    );
  };

  // 渲染文本输入框
  const renderTextInput = () => {
    const inputType =
      field.type === 'password'
        ? 'password'
        : field.type === 'email'
          ? 'email'
          : field.type === 'url'
            ? 'url'
            : field.type === 'number'
              ? 'number'
              : 'text';

    // 安全处理 placeholder
    const placeholderText = field.placeholder ? lt(field.placeholder) : undefined;

    return (
      <input
        type={inputType}
        value={value || ''}
        placeholder={placeholderText}
        disabled={disabled}
        readOnly={readonly}
        required={field.required}
        className={`form-input block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50 disabled:text-gray-500 ${error && touched ? 'border-red-300 focus:border-red-500 focus:ring-red-500' : ''}`}
        style={styleOverrides.input}
        onChange={(e) => handleChange(e.target.value)}
        onFocus={handleFocus}
        onBlur={handleBlur}
      />
    );
  };

  // 渲染文本域
  const renderTextarea = () => {
    // 安全处理 placeholder
    const placeholderText = field.placeholder ? lt(field.placeholder) : undefined;

    return (
      <textarea
        value={value || ''}
        placeholder={placeholderText}
        disabled={disabled}
        readOnly={readonly}
        required={field.required}
        rows={4}
        className={`form-textarea resize-vertical block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50 disabled:text-gray-500 ${error && touched ? 'border-red-300 focus:border-red-500 focus:ring-red-500' : ''}`}
        style={styleOverrides.input}
        onChange={(e) => handleChange(e.target.value)}
        onFocus={handleFocus}
        onBlur={handleBlur}
      />
    );
  };

  // 渲染选择框
  const renderSelect = () => {
    return (
      <select
        value={value || ''}
        disabled={disabled}
        required={field.required}
        className={`form-select block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50 disabled:text-gray-500 ${error && touched ? 'border-red-300 focus:border-red-500 focus:ring-red-500' : ''}`}
        style={styleOverrides.input}
        onChange={(e) => handleChange(e.target.value)}
        onFocus={handleFocus}
        onBlur={handleBlur}
      >
        {field.placeholder && <option value="">{lt(field.placeholder)}</option>}
        {field.options?.map((option, index) => (
          <option key={index} value={option.value}>
            {lt(option.label)}
          </option>
        ))}
      </select>
    );
  };

  // 渲染复选框
  const renderCheckbox = () => {
    return (
      <div className="form-checkbox flex items-center">
        <input
          type="checkbox"
          checked={Boolean(value)}
          disabled={disabled}
          readOnly={readonly}
          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500 disabled:opacity-50"
          onChange={(e) => handleChange(e.target.checked)}
          onFocus={handleFocus}
          onBlur={handleBlur}
        />
        {field.label && (
          <label className="ml-2 block text-sm text-gray-900">
            {lt(field.label)}
            {field.required && <span className="ml-1 text-red-500">*</span>}
          </label>
        )}
      </div>
    );
  };

  // 渲染单选框组
  const renderRadioGroup = () => {
    return (
      <div className="form-radio-group space-y-2">
        {field.options?.map((option, index) => (
          <div key={index} className="flex items-center">
            <input
              type="radio"
              name={field.name}
              value={option.value}
              checked={value === option.value}
              disabled={disabled}
              readOnly={readonly}
              className="h-4 w-4 border-gray-300 text-blue-600 focus:ring-blue-500 disabled:opacity-50"
              onChange={(e) => handleChange(e.target.value)}
              onFocus={handleFocus}
              onBlur={handleBlur}
            />
            <label className="ml-2 block text-sm text-gray-900">{lt(option.label)}</label>
          </div>
        ))}
      </div>
    );
  };

  // 渲染多选框组
  const renderCheckboxGroup = () => {
    const selectedValues = Array.isArray(value) ? value : [];

    const handleCheckboxChange = (optionValue: any, checked: boolean) => {
      let newValues;
      if (checked) {
        newValues = [...selectedValues, optionValue];
      } else {
        newValues = selectedValues.filter((v) => v !== optionValue);
      }
      handleChange(newValues);
    };

    return (
      <div className="form-checkbox-group space-y-2">
        {field.options?.map((option, index) => (
          <div key={index} className="flex items-center">
            <input
              type="checkbox"
              checked={selectedValues.includes(option.value)}
              disabled={disabled}
              readOnly={readonly}
              className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500 disabled:opacity-50"
              onChange={(e) => handleCheckboxChange(option.value, e.target.checked)}
              onFocus={handleFocus}
              onBlur={handleBlur}
            />
            <label className="ml-2 block text-sm text-gray-900">{lt(option.label)}</label>
          </div>
        ))}
      </div>
    );
  };

  // 渲染日期选择器
  const renderDatePicker = () => {
    const dateValue = value
      ? value instanceof Date
        ? value.toISOString().split('T')[0]
        : value
      : '';

    return (
      <input
        type="date"
        value={dateValue}
        disabled={disabled}
        readOnly={readonly}
        required={field.required}
        className={`form-input block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50 disabled:text-gray-500 ${error && touched ? 'border-red-300 focus:border-red-500 focus:ring-red-500' : ''}`}
        style={styleOverrides.input}
        onChange={(e) => handleChange(e.target.value)}
        onFocus={handleFocus}
        onBlur={handleBlur}
      />
    );
  };

  // 渲染时间选择器
  const renderTimePicker = () => {
    return (
      <input
        type="time"
        value={value || ''}
        disabled={disabled}
        readOnly={readonly}
        required={field.required}
        className={`form-input block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50 disabled:text-gray-500 ${error && touched ? 'border-red-300 focus:border-red-500 focus:ring-red-500' : ''}`}
        style={styleOverrides.input}
        onChange={(e) => handleChange(e.target.value)}
        onFocus={handleFocus}
        onBlur={handleBlur}
      />
    );
  };

  // 渲染文件上传
  const renderFileUpload = () => {
    return (
      <input
        type="file"
        disabled={disabled}
        readOnly={readonly}
        required={field.required}
        multiple={field.type === 'files'}
        className={`form-input block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50 disabled:text-gray-500 ${error && touched ? 'border-red-300 focus:border-red-500 focus:ring-red-500' : ''}`}
        style={styleOverrides.input}
        onChange={(e) => {
          const files = Array.from(e.target.files || []);
          handleChange(field.type === 'files' ? files : files[0]);
        }}
        onFocus={handleFocus}
        onBlur={handleBlur}
      />
    );
  };

  // 渲染隐藏字段
  const renderHidden = () => {
    return (
      <input
        type="hidden"
        value={value || ''}
        onChange={() => {}} // 隐藏字段不允许用户直接修改
      />
    );
  };

  // 根据字段类型渲染控件
  const renderControl = () => {
    switch (field.type) {
      case 'text':
      case 'email':
      case 'password':
      case 'url':
      case 'number':
        return renderTextInput();
      case 'textarea':
        return renderTextarea();
      case 'select':
        return renderSelect();
      case 'checkbox':
        return renderCheckbox();
      case 'radio':
        return renderRadioGroup();
      case 'checkboxes':
        return renderCheckboxGroup();
      case 'date':
        return renderDatePicker();
      case 'time':
        return renderTimePicker();
      case 'file':
      case 'files':
        return renderFileUpload();
      case 'hidden':
        return renderHidden();
      default:
        return renderTextInput();
    }
  };

  // 如果是隐藏字段，直接返回控件
  if (field.type === 'hidden') {
    return renderControl();
  }

  // 如果是复选框，使用特殊布局
  if (field.type === 'checkbox') {
    return (
      <div
        className={`form-field form-field-${field.type} ${error && touched ? 'has-error' : ''} ${focused ? 'is-focused' : ''} ${disabled ? 'is-disabled' : ''}`}
        style={styleOverrides.container}
      >
        {renderControl()}
        {renderError()}
        {renderHelp()}
      </div>
    );
  }

  // 标准字段布局
  return (
    <div
      className={`form-field form-field-${field.type} ${error && touched ? 'has-error' : ''} ${focused ? 'is-focused' : ''} ${disabled ? 'is-disabled' : ''}`}
      style={styleOverrides.container}
    >
      {renderLabel()}
      {renderControl()}
      {renderError()}
      {renderHelp()}
    </div>
  );
};

export default FormFieldRenderer;
