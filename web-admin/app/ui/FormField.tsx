import React from 'react';

interface BaseFieldProps {
  label: string;
  name: string;
  required?: boolean;
  error?: string;
  className?: string;
  disabled?: boolean;
}

interface InputFieldProps extends BaseFieldProps {
  type?: 'text' | 'email' | 'tel' | 'url';
  value: string;
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
  placeholder?: string;
}

interface SelectFieldProps extends BaseFieldProps {
  value: string;
  onChange: (e: React.ChangeEvent<HTMLSelectElement>) => void;
  options: Array<{ value: string; label: string }>;
  placeholder?: string;
}

interface TextareaFieldProps extends BaseFieldProps {
  value: string;
  onChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => void;
  placeholder?: string;
  rows?: number;
}

// 基础样式类
const baseInputClasses =
  'w-full px-3 py-2 border rounded-md shadow-sm focus:outline-none focus:ring-2 focus:ring-blue-500 dark:bg-gray-700 dark:text-white dark:border-gray-600 dark:focus:border-blue-400';
const errorInputClasses = 'border-red-500 dark:border-red-400';
const normalInputClasses = 'border-gray-300 dark:border-gray-600';

// 输入框组件
export function InputField({
  label,
  name,
  type = 'text',
  value,
  onChange,
  placeholder,
  required = false,
  error,
  className = '',
  disabled = false,
}: InputFieldProps) {
  return (
    <div className={className}>
      <label
        htmlFor={name}
        className="mb-2 block text-sm font-medium text-gray-700 dark:text-gray-300"
      >
        {label} {required && <span className="text-red-500">*</span>}
      </label>
      <input
        type={type}
        id={name}
        name={name}
        value={value}
        onChange={onChange}
        disabled={disabled}
        className={`${baseInputClasses} ${error ? errorInputClasses : normalInputClasses}`}
        placeholder={placeholder}
      />
      {error && <p className="mt-1 text-sm text-red-500 dark:text-red-400">{error}</p>}
    </div>
  );
}

// 选择框组件
export function SelectField({
  label,
  name,
  value,
  onChange,
  options,
  placeholder,
  required = false,
  error,
  className = '',
  disabled = false,
}: SelectFieldProps) {
  return (
    <div className={className}>
      <label
        htmlFor={name}
        className="mb-2 block text-sm font-medium text-gray-700 dark:text-gray-300"
      >
        {label} {required && <span className="text-red-500">*</span>}
      </label>
      <select
        id={name}
        name={name}
        value={value}
        onChange={onChange}
        disabled={disabled}
        className={`${baseInputClasses} ${error ? errorInputClasses : normalInputClasses}`}
      >
        {placeholder && <option value="">{placeholder}</option>}
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      {error && <p className="mt-1 text-sm text-red-500 dark:text-red-400">{error}</p>}
    </div>
  );
}

// 文本域组件
export function TextareaField({
  label,
  name,
  value,
  onChange,
  placeholder,
  rows = 4,
  required = false,
  error,
  className = '',
  disabled = false,
}: TextareaFieldProps) {
  return (
    <div className={className}>
      <label
        htmlFor={name}
        className="mb-2 block text-sm font-medium text-gray-700 dark:text-gray-300"
      >
        {label} {required && <span className="text-red-500">*</span>}
      </label>
      <textarea
        id={name}
        name={name}
        rows={rows}
        value={value}
        onChange={onChange}
        disabled={disabled}
        className={`${baseInputClasses} ${error ? errorInputClasses : normalInputClasses}`}
        placeholder={placeholder}
      />
      {error && <p className="mt-1 text-sm text-red-500 dark:text-red-400">{error}</p>}
    </div>
  );
}
