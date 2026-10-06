import type { ComponentText, ValidationRule } from '~/framework/meta/registry/components/ComponentConfig';
/**
 * 属性输入控件组件
 * 根据属性类型动态渲染不同的输入控件
 * 最小化stopPropagation使用，只在叶子组件中使用
 */

import { usePropertyEditorText } from './propertyEditorI18n';
import React, { useCallback } from 'react';
import { useLocalizedText } from '~/utils/i18n';
import { IconPicker } from '~/plugins/core-designer/components/studio/workbench/panels/property-editors/IconPicker';

// PropertySchema interface definition
interface PropertySchema {
  key: string;
  type: string;
  label?: ComponentText;
  description?: ComponentText;
  defaultValue?: any;
  options?: Array<{ label: ComponentText; value: any }>;
  validation?: ValidationRule[] | {
    required?: boolean;
    min?: number;
    max?: number;
    pattern?: string;
  };
  group?: string;
  min?: number;
  max?: number;
  pattern?: string;
  required?: boolean;
}

export interface PropertyInputProps {
  property: PropertySchema;
  value: any;
  error?: string;
  onChange: (value: any) => void;
}

export const PropertyInput: React.FC<PropertyInputProps> = ({
  property,
  value,
  error,
  onChange,
}) => {
  const lt = useLocalizedText();
  const text = usePropertyEditorText();

  const handleChange = useCallback(
    (event: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
      const newValue = event.target.value;

      // 根据属性类型转换值
      switch (property.type) {
        case 'number':
          if (newValue === '') {
            // 空值时使用默认值或1
            const defaultVal = property.defaultValue || 1;
            onChange(defaultVal);
          } else {
            const numValue = Number(newValue);
            if (!isNaN(numValue)) {
              // 应用min/max限制
              let validValue = numValue;
              if (property.min !== undefined) {
                validValue = Math.max(validValue, property.min);
              }
              if (property.max !== undefined) {
                validValue = Math.min(validValue, property.max);
              }
              onChange(validValue);
            } else {
              // 无效数字时保持当前值不变
              return;
            }
          }
          break;
        case 'boolean':
          const boolValue = newValue === 'true';
          onChange(boolValue);
          break;
        default:
          onChange(newValue);
      }
    },
    [
      property.type,
      property.min,
      property.max,
      property.defaultValue,
      onChange,
    ],
  );

  const handleCheckboxChange = useCallback(
    (event: React.ChangeEvent<HTMLInputElement>) => {
      onChange(event.target.checked);
    },
    [onChange],
  );

  // 翻译 label 和 description
  const translatedLabel = property.label ? lt(property.label) : '';
  const translatedDescription = property.description ? lt(property.description) : '';

  const renderInput = () => {
    const commonProps = {
      id: property.key,
      value: value !== undefined && value !== null ? value : '',
      onChange: handleChange,
      className: `w-full px-3 py-2 border rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500 ${
        error ? 'border-red-500' : 'border-gray-300'
      }`,
      // 只在叶子组件中使用stopPropagation，最小化事件干扰
      onClick: (e: React.MouseEvent) => {
        e.stopPropagation();
      },
      onFocus: (e: React.FocusEvent) => {
        e.stopPropagation();
      },
    };

    switch (property.type) {
      case 'string':
      case 'text':
        return (
          <input
            {...commonProps}
            type="text"
            placeholder={translatedDescription || text('enter', { label: translatedLabel })}
            data-domain="input"
          />
        );

      case 'number':
        return (
          <input
            {...commonProps}
            type="number"
            min={property.min}
            max={property.max}
            placeholder={translatedDescription || text('enter', { label: translatedLabel })}
            data-domain="input"
          />
        );

      case 'boolean':
        return (
          <div className="flex items-center" data-domain="input">
            <input
              id={property.key}
              type="checkbox"
              checked={Boolean(value)}
              onChange={handleCheckboxChange}
              className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
              onClick={(e: React.MouseEvent) => {
                e.stopPropagation();
              }}
              onFocus={(e: React.FocusEvent) => {
                e.stopPropagation();
              }}
            />
            <label htmlFor={property.key} className="ml-2 text-sm text-gray-700">
              {translatedDescription || translatedLabel}
            </label>
          </div>
        );

      case 'select':
        return (
          <select {...commonProps} value={value || ''} data-domain="input">
            <option value="">{text('select', { label: translatedLabel })}</option>
            {property.options?.map((option) => (
              <option key={option.value} value={option.value}>
                {lt(option.label)}
              </option>
            ))}
          </select>
        );

      case 'textarea':
        return (
          <textarea
            {...commonProps}
            rows={4}
            placeholder={translatedDescription || text('enter', { label: translatedLabel })}
            data-domain="input"
          />
        );

      case 'color':
        return (
          <div className="flex items-center space-x-2" data-domain="input">
            <input
              type="color"
              value={value || '#000000'}
              onChange={handleChange}
              className="h-10 w-16 cursor-pointer rounded border border-gray-300"
              data-domain="input"
            />
            <input
              {...commonProps}
              type="text"
              placeholder="#000000"
              className="flex-1"
              data-domain="input"
            />
          </div>
        );

      case 'icon':
        return (
          <div
            data-domain="input"
            onClick={(e: React.MouseEvent) => {
              e.stopPropagation();
            }}
            onFocus={(e: React.FocusEvent) => {
              e.stopPropagation();
            }}
          >
            <IconPicker
              value={typeof value === 'string' ? value : ''}
              onChange={onChange}
              iconSize="md"
            />
          </div>
        );

      case 'date':
        return <input {...commonProps} type="date" data-domain="input" />;

      case 'array':
        return (
          <textarea
            {...commonProps}
            rows={3}
            placeholder={text('array')}
            value={Array.isArray(value) ? JSON.stringify(value) : value || ''}
            onChange={(e) => {
              try {
                const parsed = JSON.parse(e.target.value);
                if (Array.isArray(parsed)) {
                  onChange(parsed);
                } else {
                  onChange(e.target.value);
                }
              } catch {
                onChange(e.target.value);
              }
            }}
            data-domain="input"
          />
        );

      case 'object':
        return (
          <textarea
            {...commonProps}
            rows={4}
            placeholder={text('object')}
            value={typeof value === 'object' ? JSON.stringify(value, null, 2) : value || ''}
            onChange={(e) => {
              try {
                const parsed = JSON.parse(e.target.value);
                onChange(parsed);
              } catch {
                onChange(e.target.value);
              }
            }}
            data-domain="input"
          />
        );

      case 'formref-select':
        // 动态导入 FormRefSelectEditor 组件
        const FormRefSelectEditor = React.lazy(() =>
          import('~/plugins/core-designer/components/studio/workbench/panels/properties/PropertyEditor/FormRefSelectEditor').then(
            (module) => ({ default: module.FormRefSelectEditor }),
          ),
        );

        return (
          <React.Suspense fallback={<div className="text-sm text-gray-500">{text('loading')}</div>}>
            <FormRefSelectEditor
              value={value}
              onChange={onChange}
              placeholder={translatedDescription || text('select', { label: translatedLabel })}
            />
          </React.Suspense>
        );

      default:
        return (
          <input
            {...commonProps}
            type="text"
            placeholder={translatedDescription || text('enter', { label: translatedLabel })}
            data-domain="input"
          />
        );
    }
  };

  return (
    <div className="space-y-2" data-domain="input">
      <label htmlFor={property.key} className="block text-sm font-medium text-gray-700">
        {translatedLabel}
        {property.required && <span className="ml-1 text-red-500">*</span>}
      </label>

      {renderInput()}

      {error && <p className="text-sm text-red-600">{error}</p>}

      {translatedDescription && !error && (
        <p className="text-sm text-gray-500">{translatedDescription}</p>
      )}
    </div>
  );
};
