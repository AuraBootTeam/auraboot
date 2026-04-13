/**
 * InputPropertyEditor 组件
 * 专门用于编辑SmartInput组件属性的编辑器
 * 基于FormSchema驱动的属性面板
 */

import React, { useState, useCallback, useEffect } from 'react';
import { Input } from '~/components/smart/form/Input';
import { Select } from '~/components/smart/form/Select';
import { Checkbox } from '~/components/smart/form/Checkbox';
import { useDesignerStore } from '~/plugins/core-designer/components/studio/hooks/store/useDesignerStore';
import type { Component } from '~/plugins/core-designer/components/studio/domain/schema/types';

// 导入属性面板配置
import propertyPanelConfig from '~/plugins/core-designer/components/studio/workbench/panels/properties/property-panel-input.json';

export interface InputPropertyEditorProps {
  component: Component;
  onPropertyChange?: (propertyName: string, value: any) => void;
}

export const InputPropertyEditor: React.FC<InputPropertyEditorProps> = ({
  component,
  onPropertyChange,
}) => {
  const [formData, setFormData] = useState<Record<string, any>>({});
  const [validationErrors, setValidationErrors] = useState<Record<string, string>>({});
  const [expandedSections, setExpandedSections] = useState<Set<string>>(
    new Set(['basic', 'validation', 'behavior', 'layout', 'appearance']),
  );

  const updateComponent = useDesignerStore((state) => state.updateComponent);

  // 初始化表单数据
  useEffect(() => {
    if (component) {
      const initialData = {
        label: component.props?.label || '',
        name: component.props?.name || '',
        placeholder: component.props?.placeholder || '',
        defaultValue: component.props?.defaultValue || '',
        required: component.props?.required || false,
        showRequiredMark: component.props?.showRequiredMark !== false,
        maxLength: component.props?.maxLength || '',
        minLength: component.props?.minLength || '',
        disabled: component.props?.disabled || false,
        readOnly: component.props?.readOnly || false,
        hidden: component.props?.hidden || false,
        clearable: component.props?.clearable !== false,
        colSpan: component.props?.colSpan || component.span || 6,
        labelCol: component.props?.labelCol || 'auto',
        fullWidth: component.props?.fullWidth || false,
        size: component.props?.size || 'medium',
        variant: component.props?.variant || 'default',
      };
      setFormData(initialData);
    }
  }, [component]);

  // 验证字段值
  const validateField = useCallback((fieldConfig: any, value: any): string | null => {
    if (!fieldConfig.validation) return null;

    for (const rule of fieldConfig.validation) {
      switch (rule.type) {
        case 'required':
          if (value === undefined || value === null || value === '') {
            return typeof rule.message === 'object' ? rule.message.fallback : rule.message;
          }
          break;
        case 'pattern':
          if (typeof value === 'string' && !new RegExp(rule.value).test(value)) {
            return typeof rule.message === 'object' ? rule.message.fallback : rule.message;
          }
          break;
        case 'min':
          if (typeof value === 'number' && value < rule.min) {
            return typeof rule.message === 'object' ? rule.message.fallback : rule.message;
          }
          break;
        case 'max':
          if (typeof value === 'number' && value > rule.max) {
            return typeof rule.message === 'object' ? rule.message.fallback : rule.message;
          }
          break;
      }
    }

    return null;
  }, []);

  // 处理字段值变更
  const handleFieldChange = useCallback(
    (fieldName: string, value: any) => {
      // 查找字段配置
      let fieldConfig: any = null;
      for (const section of propertyPanelConfig.sections) {
        const field = section.fields.find((f: any) => f.field === fieldName);
        if (field) {
          fieldConfig = field;
          break;
        }
      }

      // 验证字段值
      const error = fieldConfig ? validateField(fieldConfig, value) : null;

      // 更新验证错误状态
      setValidationErrors((prev) => ({
        ...prev,
        [fieldName]: error || '',
      }));

      // 更新表单数据
      setFormData((prev) => ({
        ...prev,
        [fieldName]: value,
      }));

      // 如果没有验证错误，实时更新组件属性
      if (!error && component?.id) {
        const updates: any = {
          props: {
            ...component.props,
            [fieldName]: value,
          },
        };

        // 特殊处理colSpan属性
        if (fieldName === 'colSpan') {
          updates.span = value;
          if (component.size) {
            updates.size = {
              ...component.size,
              span: value,
            };
          }
        }

        updateComponent(component.id, updates);

        // 调用外部回调
        if (onPropertyChange) {
          onPropertyChange(fieldName, value);
        }
      }
    },
    [component, validateField, updateComponent, onPropertyChange],
  );

  // 切换分组展开状态
  const toggleSection = useCallback((sectionCode: string) => {
    setExpandedSections((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(sectionCode)) {
        newSet.delete(sectionCode);
      } else {
        newSet.add(sectionCode);
      }
      return newSet;
    });
  }, []);

  // 渲染字段组件
  const renderField = useCallback(
    (fieldConfig: any) => {
      const { field, component: componentType, props: fieldProps, layout } = fieldConfig;
      const value = formData[field];
      const error = validationErrors[field];

      const commonProps = {
        name: field,
        value: value,
        onChange: (newValue: any) => handleFieldChange(field, newValue),
        className: error ? 'border-red-500' : '',
        ...fieldProps,
      };

      // 处理国际化标签
      if (typeof fieldProps.label === 'object') {
        commonProps.label = fieldProps.label.fallback;
      }
      if (typeof fieldProps.placeholder === 'object') {
        commonProps.placeholder = fieldProps.placeholder.fallback;
      }

      let FieldComponent;
      switch (componentType) {
        case 'SmartInput':
          FieldComponent = Input;
          break;
        case 'SmartSelect':
          FieldComponent = Select;
          break;
        case 'SmartCheckbox':
          FieldComponent = Checkbox;
          break;
        default:
          return null;
      }

      const colSpanClass = layout?.colSpan === 2 ? 'col-span-2' : 'col-span-1';
      const fullWidthClass = layout?.fullWidth ? 'col-span-full' : '';

      return (
        <div key={field} className={`${colSpanClass} ${fullWidthClass}`}>
          <FieldComponent {...commonProps} />
          {error && <div className="mt-1 text-xs text-red-500">{error}</div>}
        </div>
      );
    },
    [formData, validationErrors, handleFieldChange],
  );

  // 渲染分组
  const renderSection = useCallback(
    (sectionConfig: any) => {
      const { code, title, layout, fields } = sectionConfig;
      const isExpanded = expandedSections.has(code);
      const sectionTitle = typeof title === 'object' ? title.fallback : title;

      const gridCols = layout?.columns === 1 ? 'grid-cols-1' : 'grid-cols-2';

      return (
        <div key={code} className="mb-4 rounded-lg border border-gray-200">
          <div
            className="flex cursor-pointer items-center justify-between rounded-t-lg bg-gray-50 p-3 hover:bg-gray-100"
            onClick={() => toggleSection(code)}
          >
            <h3 className="text-sm font-medium text-gray-900">{sectionTitle}</h3>
            <span className={`transform transition-transform ${isExpanded ? 'rotate-180' : ''}`}>
              ▼
            </span>
          </div>
          {isExpanded && (
            <div className={`grid p-4 ${gridCols} gap-4`}>{fields.map(renderField)}</div>
          )}
        </div>
      );
    },
    [expandedSections, toggleSection, renderField],
  );

  return (
    <div className="mx-auto w-full max-w-md bg-white">
      <div className="border-b border-gray-200 p-4">
        <h2 className="text-lg font-semibold text-gray-900">
          {typeof propertyPanelConfig.meta.title === 'object'
            ? propertyPanelConfig.meta.title.fallback
            : propertyPanelConfig.meta.title}
        </h2>
      </div>

      <div className="max-h-96 space-y-4 overflow-y-auto p-4">
        {propertyPanelConfig.sections.map(renderSection)}
      </div>

      <div className="flex justify-end space-x-2 border-t border-gray-200 p-4">
        {propertyPanelConfig.actions.map((action: any) => (
          <button
            key={action.code}
            className={`rounded-md px-4 py-2 text-sm ${
              action.props.type === 'primary'
                ? 'bg-blue-600 text-white hover:bg-blue-700'
                : 'bg-gray-200 text-gray-700 hover:bg-gray-300'
            }`}
            onClick={() => {
              if (action.code === 'reset') {
                // 重置表单数据
                const initialData = {
                  label: component.props?.label || '',
                  name: component.props?.name || '',
                  placeholder: component.props?.placeholder || '',
                  defaultValue: component.props?.defaultValue || '',
                  required: component.props?.required || false,
                  showRequiredMark: component.props?.showRequiredMark !== false,
                  maxLength: component.props?.maxLength || '',
                  minLength: component.props?.minLength || '',
                  disabled: component.props?.disabled || false,
                  readOnly: component.props?.readOnly || false,
                  hidden: component.props?.hidden || false,
                  clearable: component.props?.clearable !== false,
                  colSpan: component.props?.colSpan || component.span || 6,
                  labelCol: component.props?.labelCol || 'auto',
                  fullWidth: component.props?.fullWidth || false,
                  size: component.props?.size || 'medium',
                  variant: component.props?.variant || 'default',
                };
                setFormData(initialData);
                setValidationErrors({});
              }
            }}
          >
            {typeof action.props.label === 'object'
              ? action.props.label.fallback
              : action.props.label}
          </button>
        ))}
      </div>
    </div>
  );
};

export default InputPropertyEditor;
