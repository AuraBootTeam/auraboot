/**
 * InputPropertyPanel - SmartInput组件专用属性面板
 * 基于FormSchema配置驱动的属性编辑器
 * 支持实时自动保存
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import type { Component } from '~/plugins/core-designer/components/studio/workbench/canvas/types';
import { Input } from '~/ui/smart/form/Input';
import { Select } from '~/ui/smart/form/Select';
import { Checkbox } from '~/ui/smart/form/Checkbox';
import { useLocalizedText } from '~/utils/i18n';
import {
  PropertyPersistenceManager,
  PropertySaveStatus,
  getPropertyPersistenceManager,
  resetPropertyPersistenceManager,
} from '~/plugins/core-designer/components/studio/services/state/PropertyPersistenceManager';

// 导入配置文件
import propertyPanelConfig from '~/plugins/core-designer/components/studio/workbench/panels/properties/property-panel-input.json';

interface InputPropertyPanelProps {
  component: Component;
  onPropertyChange: (propertyName: string, value: any) => void;
}

interface FormData {
  [key: string]: any;
}

interface ValidationError {
  field: string;
  message: string;
}

interface FieldConfig {
  field: string;
  component: string;
  props: any;
  layout?: any;
  validation?: any[];
}

interface SectionConfig {
  code: string;
  title: { i18n?: string; fallback: string };
  layout: {
    columns: number;
    gap: string;
  };
  fields: FieldConfig[];
}

export const InputPropertyPanel: React.FC<InputPropertyPanelProps> = ({
  component,
  onPropertyChange,
}) => {
  const lt = useLocalizedText();
  const [formData, setFormData] = useState<FormData>({});
  const [errors, setErrors] = useState<ValidationError[]>([]);
  const [expandedSections, setExpandedSections] = useState<Set<string>>(new Set(['basic']));

  // 保存状态管理
  const [saveStatus, setSaveStatus] = useState<PropertySaveStatus>(PropertySaveStatus.Idle);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);

  // 使用 ref 保存最新的表单数据，避免闭包导致读取到旧值
  const formDataRef = useRef<FormData>({});
  useEffect(() => {
    formDataRef.current = formData;
  }, [formData]);

  // 属性持久化管理器
  const persistenceManagerRef = useRef<PropertyPersistenceManager | null>(null);

  // 初始化持久化管理器
  useEffect(() => {
    // 重置全局实例，确保使用最新配置
    resetPropertyPersistenceManager();

    persistenceManagerRef.current = getPropertyPersistenceManager({
      debounceDelay: 350, // 350ms防抖，平衡响应性和性能
      autoSaveInterval: 30000, // 30秒自动保存
      enableLocalStorage: true,
      enableUndoRedo: false,
    });

    // 监听保存状态变化
    const unsubscribe = persistenceManagerRef.current.onSaveStatusChange((status, error) => {
      setSaveStatus(status);
      setSaveError(error || null);

      if (status === PropertySaveStatus.Saved) {
        setHasUnsavedChanges(false);
      }
    });

    return () => {
      unsubscribe();
      // 组件卸载时清理
      if (persistenceManagerRef.current) {
        persistenceManagerRef.current.destroy();
        persistenceManagerRef.current = null;
      }
    };
  }, []);

  // 初始化表单数据
  useEffect(() => {
    const initializeFormData = async () => {
      const initialData: FormData = {};

      // 尝试从本地存储加载保存的属性
      if (persistenceManagerRef.current) {
        const savedProperties = await persistenceManagerRef.current.loadComponentProperties(
          component.id,
        );
        if (savedProperties) {
          Object.assign(initialData, savedProperties);
        }
      }

      // 从组件属性中提取数据（优先级较低）
      if (component?.props) {
        Object.keys(component.props).forEach((key) => {
          if (!(key in initialData)) {
            initialData[key] = component.props[key];
          }
        });
      }

      // 设置默认值
      propertyPanelConfig.sections.forEach((section: SectionConfig) => {
        section.fields.forEach((field: FieldConfig) => {
          if (!(field.field in initialData)) {
            // 根据组件类型设置默认值
            switch (field.component) {
              case 'SmartCheckbox':
                initialData[field.field] = false;
                break;
              case 'SmartSelect':
                if (field.props.options && field.props.options.length > 0) {
                  initialData[field.field] = field.props.options[0].value;
                }
                break;
              case 'SmartInput':
                if (field.props.type === 'number') {
                  initialData[field.field] = field.props.min || 0;
                } else {
                  initialData[field.field] = '';
                }
                break;
              default:
                initialData[field.field] = '';
            }
          }
        });
      });

      setFormData(initialData);
      setHasUnsavedChanges(false);
    };

    initializeFormData();
  }, [component.id]);

  // 处理字段值变更（实时保存 + 同步到store）
  const handleFieldChange = useCallback(
    (fieldName: string, value: any) => {
      // 读取最新的旧值，避免闭包读取到过期的 formData
      const oldValue = formDataRef.current[fieldName];

      // 先更新本地状态，保证受控输入不被回滚
      setFormData((prev) => {
        const newData = { ...prev, [fieldName]: value };

        // 自动保存属性变更到本地存储（防抖在管理器内部处理）
        if (persistenceManagerRef.current && oldValue !== value) {
          persistenceManagerRef.current.savePropertyChange(
            component.id,
            fieldName,
            value,
            oldValue,
          );
          setHasUnsavedChanges(true);
        }

        return newData;
      });

      // 稍微延后同步到设计器 store，避免与本地 setState 竞争导致值被吞
      setTimeout(() => {
        try {
          onPropertyChange(fieldName, value);
        } catch (e) {
          console.warn('[InputPropertyPanel] onPropertyChange failed:', e);
        }
      }, 30);

      // 清除该字段的错误
      setErrors((prev) => prev.filter((error) => error.field !== fieldName));
    },
    [component.id, onPropertyChange],
  );

  // 切换区域展开状态
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
    (field: FieldConfig) => {
      const fieldError = errors.find((error) => error.field === field.field);
      const fieldValue = formData[field.field];

      const commonProps = {
        ...field.props,
        value: fieldValue,
        onChange: (value: any) => handleFieldChange(field.field, value),
        error: fieldError?.message,
        name: field.field,
      };

      switch (field.component) {
        case 'SmartInput':
          return <Input key={field.field} {...commonProps} />;
        case 'SmartSelect':
          return <Select key={field.field} {...commonProps} />;
        case 'SmartCheckbox':
          return <Checkbox key={field.field} {...commonProps} />;
        default:
          return (
            <div key={field.field} className="text-xs text-red-500">
              未知组件类型: {field.component}
            </div>
          );
      }
    },
    [formData, errors, handleFieldChange],
  );

  return (
    <div className="flex max-h-[60vh] w-80 flex-col border-l border-gray-200 bg-white p-4">
      {/* 标题和自动保存状态 */}
      <div className="mb-4">
        <h3 className="mb-2 text-lg font-semibold text-gray-800">属性面板</h3>

        {/* 自动保存说明 */}
        <div className="mb-3 rounded border-l-2 border-blue-200 bg-blue-50 p-2 text-xs text-gray-500">
          <div className="flex items-center space-x-1">
            <svg className="h-3 w-3 text-blue-500" fill="currentColor" viewBox="0 0 20 20">
              <path
                fillRule="evenodd"
                d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z"
                clipRule="evenodd"
              />
            </svg>
            <span>修改后自动保存到本地存储 (350ms 防抖)</span>
          </div>
        </div>

        {/* 自动保存状态指示器 */}
        <div className="flex items-center space-x-2 text-xs">
          <div
            className={`h-2 w-2 rounded-full ${
              saveStatus === PropertySaveStatus.Saving
                ? 'animate-pulse bg-yellow-500'
                : saveStatus === PropertySaveStatus.Saved
                  ? 'bg-green-500'
                  : saveStatus === PropertySaveStatus.Error
                    ? 'bg-red-500'
                    : 'bg-gray-300'
            }`}
          />
          <span
            className={`${
              saveStatus === PropertySaveStatus.Saving
                ? 'text-yellow-600'
                : saveStatus === PropertySaveStatus.Saved
                  ? 'text-green-600'
                  : saveStatus === PropertySaveStatus.Error
                    ? 'text-red-600'
                    : 'text-gray-500'
            }`}
          >
            {saveStatus === PropertySaveStatus.Saving
              ? '自动保存中...'
              : saveStatus === PropertySaveStatus.Saved
                ? '已自动保存'
                : saveStatus === PropertySaveStatus.Error
                  ? '自动保存失败'
                  : hasUnsavedChanges
                    ? '等待自动保存...'
                    : '无更改'}
          </span>
        </div>

        {/* 错误信息 */}
        {saveError && (
          <div className="mt-2 rounded border-l-2 border-red-200 bg-red-50 p-2 text-xs text-red-600">
            <div className="font-medium">自动保存失败:</div>
            <div className="mt-1">{saveError}</div>
          </div>
        )}
      </div>

      {/* 表单区域（仅此处滚动） */}
      <div className="min-h-0 flex-1 overflow-y-auto pr-1">
        {propertyPanelConfig.sections.map((section: SectionConfig) => (
          <div key={section.code} className="mb-4">
            {/* 区域标题 */}
            <button
              className="flex w-full items-center justify-between rounded-t border border-gray-200 bg-gray-50 p-2 transition-colors hover:bg-gray-100"
              onClick={() => toggleSection(section.code)}
            >
              <span className="font-medium text-gray-700">
                {section.title.i18n ? lt(`$i18n:${section.title.i18n}`) : section.title.fallback}
              </span>
              <span
                className={`transform transition-transform ${
                  expandedSections.has(section.code) ? 'rotate-180' : ''
                }`}
              >
                ▼
              </span>
            </button>

            {/* 区域内容 */}
            {expandedSections.has(section.code) && (
              <div className="rounded-b border border-t-0 border-gray-200 bg-white p-3">
                <div
                  className="grid gap-3"
                  style={{
                    gridTemplateColumns: `repeat(${section.layout.columns}, 1fr)`,
                    gap: section.layout.gap,
                  }}
                >
                  {section.fields.map(renderField)}
                </div>
              </div>
            )}
          </div>
        ))}

        {/* 错误提示 */}
        {errors.length > 0 && (
          <div className="mt-3 rounded border border-red-200 bg-red-50 p-2">
            <div className="mb-1 text-xs font-medium text-red-600">验证错误:</div>
            {errors.map((error, index) => (
              <div key={index} className="text-xs text-red-600">
                • {error.message}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};
