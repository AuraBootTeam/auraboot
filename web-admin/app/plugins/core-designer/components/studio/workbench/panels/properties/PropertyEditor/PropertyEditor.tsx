/**
 * PropertyEditor 组件
 * 动态属性编辑器，支持不同类型的属性输入控件
 */

import React, { useState, useCallback, useMemo, useEffect } from 'react';
import { PropertyGroup } from '~/plugins/core-designer/components/studio/workbench/panels/properties/PropertyEditor/PropertyGroup';
import { PropertyInput } from '~/plugins/core-designer/components/studio/workbench/panels/properties/PropertyEditor/PropertyInput';
import { getPropertyPersistenceManager } from '~/plugins/core-designer/components/studio/services/state/PropertyPersistenceManager';
import { useLocalizedText } from '~/utils/i18n';
import type { ComponentConfig } from '~/ui/smart/types';
import type { Component } from '~/plugins/core-designer/components/studio/domain/schema/types';

export interface PropertyEditorProps {
  component: Component;
  config: ComponentConfig;
  /** Called when component props should be updated in the schema */
  onComponentChange?: (id: string, updates: Partial<Component>) => void;
  onPropertyChange?: (propertyName: string, value: any) => void;
  onValidationError?: (propertyName: string, error: string | null) => void;
}

export const PropertyEditor: React.FC<PropertyEditorProps> = ({
  component,
  config,
  onComponentChange,
  onPropertyChange,
  onValidationError,
}) => {
  const lt = useLocalizedText();
  const [validationErrors, setValidationErrors] = useState<Record<string, string>>({});
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set(['basic']));

  // ✅ 当组件切换时，从localStorage加载最新属性
  useEffect(() => {
    const loadSavedProperties = async () => {
      if (!component?.id) return;

      const persistenceManager = getPropertyPersistenceManager();
      const savedProperties = await persistenceManager.loadComponentProperties(component.id);

      if (savedProperties && Object.keys(savedProperties).length > 0) {
        // Merge saved props back into component via onComponentChange
        onComponentChange?.(component.id, {
          props: {
            ...component.props,
            ...savedProperties,
          },
        });
      }
    };

    loadSavedProperties();
  }, [component?.id]); // 只在组件ID变化时触发

  const handleGroupToggle = useCallback((groupName: string) => {
    setExpandedGroups((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(groupName)) {
        newSet.delete(groupName);
      } else {
        newSet.add(groupName);
      }
      return newSet;
    });
  }, []);

  // 根据配置分组属性
  const groupedProperties = useMemo(() => {
    if (!config?.propertySchema) return {};

    return config.propertySchema.reduce((groups: Record<string, any[]>, property: any) => {
      const groupName = property.group || 'basic';
      if (!groups[groupName]) {
        groups[groupName] = [];
      }
      groups[groupName].push(property);
      return groups;
    }, {});
  }, [config.propertySchema]);

  // 移除重复的 handleGroupToggle 函数，使用 toggleGroup 替代
  // const handleGroupToggle = useCallback((groupName: string) => {
  //   setExpandedGroups(prev => {
  //     const newSet = new Set(prev);
  //     if (newSet.has(groupName)) {
  //       newSet.delete(groupName);
  //     } else {
  //       newSet.add(groupName);
  //     }
  //     return newSet;
  //   });
  // }, []);

  // 验证属性值
  const validateProperty = useCallback(
    (property: any, value: any): string | null => {
      if (!property.validation) return null;

      // 翻译属性标签
      const translatedLabel = property.label ? lt(property.label) : property.key;

      for (const rule of property.validation) {
        switch (rule.type) {
          case 'required':
            if (rule.value && (value === undefined || value === null || value === '')) {
              return rule.message || `${translatedLabel} 是必填项`;
            }
            break;
          case 'minLength':
            if (typeof value === 'string' && value.length < rule.value) {
              return rule.message || `${translatedLabel} 最少需要 ${rule.value} 个字符`;
            }
            break;
          case 'maxLength':
            if (typeof value === 'string' && value.length > rule.value) {
              return rule.message || `${translatedLabel} 最多允许 ${rule.value} 个字符`;
            }
            break;
          case 'min':
            if (typeof value === 'number' && value < rule.value) {
              return rule.message || `${translatedLabel} 最小值为 ${rule.value}`;
            }
            break;
          case 'max':
            if (typeof value === 'number' && value > rule.value) {
              return rule.message || `${translatedLabel} 最大值为 ${rule.value}`;
            }
            break;
          case 'pattern':
            if (typeof value === 'string' && !new RegExp(rule.value).test(value)) {
              return rule.message || `${translatedLabel} 格式不正确`;
            }
            break;
        }
      }

      return null;
    },
    [lt],
  );

  // 处理属性值变更
  const handlePropertyChange = useCallback(
    (property: any, value: any) => {
      // 验证新值
      const error = validateProperty(property, value);

      // 更新验证错误状态
      setValidationErrors((prev) => ({
        ...prev,
        [property.key]: error || '',
      }));

      // 通知验证错误
      if (onValidationError) {
        onValidationError(property.key, error);
      }

      // 如果没有错误，更新属性值
      if (!error) {
        // 实时更新组件属性到设计器状态
        if (component?.id) {
          // 保存旧值用于持久化
          const oldValue = component.props?.[property.key];

          const updates: any = {
            props: {
              ...component.props,
              [property.key]: value,
            },
          };

          // 如果是width属性，同时更新span属性以保持兼容性
          if (property.key === 'width') {
            // 确保width值在有效范围内
            const validWidth = Math.max(1, Math.min(12, Number(value) || 1));
            updates.props[property.key] = validWidth;
            updates.span = validWidth;

            // 如果组件有size属性，也更新size.span
            if (component.size) {
              updates.size = {
                ...component.size,
                span: validWidth,
              };
            }
          }

          onComponentChange?.(component.id, updates);

          // ✅ 保存到 localStorage 以支持导出
          const persistenceManager = getPropertyPersistenceManager();
          persistenceManager.savePropertyChange(component.id, property.key, value, oldValue);
        }

        // 调用外部回调
        if (onPropertyChange) {
          onPropertyChange(property.key, value);
        }
      }
    },
    [
      validateProperty,
      validationErrors,
      onPropertyChange,
      onValidationError,
      component,
      onComponentChange,
    ],
  );

  // 切换组展开状态
  const toggleGroup = useCallback((groupName: string) => {
    setExpandedGroups((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(groupName)) {
        newSet.delete(groupName);
      } else {
        newSet.add(groupName);
      }
      return newSet;
    });
  }, []);

  // 获取组的显示信息
  const getGroupInfo = (groupName: string) => {
    const groupMap: Record<string, { title: string; icon?: string; description?: string }> = {
      basic: { title: '基础属性', icon: '⚙️', description: '组件的基本配置' },
      validation: { title: '验证规则', icon: '✅', description: '输入验证和约束' },
      style: { title: '外观样式', icon: '🎨', description: '组件的视觉样式' },
      behavior: { title: '行为配置', icon: '⚡', description: '组件的交互行为' },
      data: { title: '数据配置', icon: '📊', description: '数据源和绑定' },
    };

    return groupMap[groupName] || { title: groupName };
  };

  if (!component || !config) {
    return <div className="p-4 text-center text-gray-500">请选择一个组件进行属性编辑</div>;
  }

  return (
    <div className="property-editor">
      <div className="mb-4 rounded-lg bg-gray-50 p-3">
        <h3 className="text-sm font-medium text-gray-900">{config.name}</h3>
        <p className="mt-1 text-xs text-gray-500">{config.description}</p>
      </div>

      <div className="space-y-2">
        {Object.entries(groupedProperties).map(([groupName, properties]) => {
          const groupInfo = getGroupInfo(groupName);
          const isExpanded = expandedGroups.has(groupName);

          return (
            <PropertyGroup
              key={groupName}
              id={groupName}
              title={groupInfo.title}
              icon={groupInfo.icon}
              description={groupInfo.description}
              expanded={isExpanded}
              onToggle={() => toggleGroup(groupName)}
            >
              <div className="space-y-3">
                {(properties as any[]).map((property: any) => (
                  <PropertyInput
                    key={property.key}
                    property={property}
                    value={component.props?.[property.key] ?? property.defaultValue}
                    onChange={(value: any) => handlePropertyChange(property, value)}
                    error={validationErrors[property.key]}
                  />
                ))}
              </div>
            </PropertyGroup>
          );
        })}
      </div>
    </div>
  );
};
