import React, { useEffect, useRef } from 'react';
import type {
  PropertyPanelProps,
  ComponentType,
} from '~/plugins/core-designer/components/studio/workbench/panels/properties/PropertyPanel/types';
import { PropertyEditor } from '~/plugins/core-designer/components/studio/workbench/panels/properties/PropertyEditor';
import { InputPropertyEditor } from '~/plugins/core-designer/components/studio/workbench/panels/properties/PropertyEditor/InputPropertyEditor';
import { InputPropertyPanel } from '~/plugins/core-designer/components/studio/workbench/panels/properties/InputPropertyPanel';
import { componentRegistry } from '~/framework/meta/registry/components';
import { eventDomainManager } from '~/plugins/core-designer/components/studio/services/actions/event/EventDomainManager';

export const PropertyPanel: React.FC<PropertyPanelProps> = ({
  selectedComponents,
  onComponentUpdate,
  layoutConfig,
  onLayoutConfigChange,
  layoutSettings,
  onLayoutSettingsChange,
}) => {
  const selectedComponent = selectedComponents[0];
  const panelRef = useRef<HTMLDivElement>(null);

  // 管理属性面板的焦点状态
  useEffect(() => {
    const panelElement = panelRef.current;
    if (!panelElement) return;

    // 设置属性面板域标识
    panelElement.setAttribute('data-domain', 'property-panel');

    // 处理焦点进入事件
    const handleFocusIn = (event: FocusEvent) => {
      const target = event.target as HTMLElement;
      if (panelElement.contains(target)) {
        // 通知事件域管理器属性面板获得焦点
        eventDomainManager.dispatchEvent({
          eventType: 'domain:focus-changed',
          targetDomain: 'property-panel',
          data: { domain: 'property-panel', action: 'focus' },
        });
      }
    };

    // 处理焦点离开事件
    const handleFocusOut = (event: FocusEvent) => {
      const target = event.target as HTMLElement;
      if (panelElement.contains(target)) {
        // 延迟检查，确保焦点确实离开了属性面板
        setTimeout(() => {
          const activeElement = document.activeElement;
          if (!panelElement.contains(activeElement as HTMLElement)) {
            eventDomainManager.dispatchEvent({
              eventType: 'domain:focus-changed',
              targetDomain: 'property-panel',
              data: { domain: 'property-panel', action: 'blur' },
            });
          }
        }, 0);
      }
    };

    // 注册事件监听器
    panelElement.addEventListener('focusin', handleFocusIn, true);
    panelElement.addEventListener('focusout', handleFocusOut, true);

    return () => {
      panelElement.removeEventListener('focusin', handleFocusIn, true);
      panelElement.removeEventListener('focusout', handleFocusOut, true);
    };
  }, []);

  // 注册属性面板的事件处理器
  useEffect(() => {
    const propertyPanelDomain = {
      name: 'property-panel',
      element: panelRef.current,
      isActive: true,
      handlers: {
        'component:selected': (_event: any) => {
          // Handle component selection event
        },
        'component:deselected': (_event: any) => {
          // Handle component deselection event
        },
        'property:updated': (_event: any) => {
          // Handle property update event
        },
      },
    };

    eventDomainManager.registerDomain(propertyPanelDomain);

    return () => {
      eventDomainManager.unregisterDomain('property-panel');
    };
  }, []);

  return (
    <div
      ref={panelRef}
      className="flex h-full w-64 flex-col border-l border-gray-200 bg-white lg:w-80"
      data-domain="property-panel"
    >
      <div className="flex-shrink-0 border-b border-gray-200 p-4">
        <h2 className="text-lg font-semibold text-gray-900">属性面板</h2>
        <p className="mt-1 text-sm text-gray-500">配置组件和布局</p>
      </div>

      <div className="scrollbar-thin scrollbar-thumb-gray-300 scrollbar-track-gray-100 hover:scrollbar-thumb-gray-400 min-h-0 flex-1 space-y-6 overflow-y-auto p-4">
        {/* 布局配置 - 只在没有选中组件时显示 */}
        {!selectedComponent && (
          <div className="space-y-4">
            <h3 className="border-b border-gray-200 pb-2 text-sm font-medium text-gray-900">
              布局配置
            </h3>

            <div className="space-y-3">
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-700">
                  列数: {layoutConfig.columns}
                </label>
                <input
                  type="range"
                  min="2"
                  max="6"
                  value={layoutConfig.columns}
                  onChange={(e) =>
                    onLayoutConfigChange({
                      ...layoutConfig,
                      columns: parseInt(e.target.value),
                    })
                  }
                  className="h-2 w-full cursor-pointer appearance-none rounded-lg bg-gray-200"
                />
                <div className="mt-1 flex justify-between text-xs text-gray-500">
                  <span>2列</span>
                  <span>6列</span>
                </div>
              </div>

              <div>
                <label className="mb-1 block text-xs font-medium text-gray-700">
                  间距: {layoutConfig.gap}px
                </label>
                <input
                  type="range"
                  min="4"
                  max="24"
                  step="2"
                  value={layoutConfig.gap}
                  onChange={(e) =>
                    onLayoutConfigChange({
                      ...layoutConfig,
                      gap: parseInt(e.target.value),
                    })
                  }
                  className="h-2 w-full cursor-pointer appearance-none rounded-lg bg-gray-200"
                />
                <div className="mt-1 flex justify-between text-xs text-gray-500">
                  <span>4px</span>
                  <span>24px</span>
                </div>
              </div>

              <div>
                <label className="flex items-center text-xs text-gray-600">
                  <input
                    type="checkbox"
                    checked={layoutSettings.densePackingEnabled}
                    onChange={(e) =>
                      onLayoutSettingsChange({
                        ...layoutSettings,
                        densePackingEnabled: e.target.checked,
                      })
                    }
                    className="mr-2"
                  />
                  启用密排模式
                </label>
              </div>

              {layoutSettings.densePackingEnabled && (
                <div>
                  <label className="mb-1 block text-xs text-gray-600">优化策略</label>
                  <select
                    value={layoutSettings.optimizeFor}
                    onChange={(e) =>
                      onLayoutSettingsChange({
                        ...layoutSettings,
                        optimizeFor: e.target.value as typeof layoutSettings.optimizeFor,
                      })
                    }
                    className="w-full rounded border border-gray-300 px-2 py-1 text-xs"
                  >
                    <option value="space">空间利用率</option>
                    <option value="readability">可读性</option>
                    <option value="performance">性能</option>
                  </select>
                </div>
              )}
            </div>
          </div>
        )}

        {/* 组件属性编辑 */}
        {selectedComponent ? (
          <div className="space-y-4">
            <h3 className="border-b border-gray-200 pb-2 text-sm font-medium text-gray-900">
              组件属性
            </h3>

            <div className="space-y-3">
              <div className="rounded bg-gray-50 p-2 text-xs text-gray-600">
                <div className="font-medium">{selectedComponent.name}</div>
                <div className="text-gray-500">类型: {selectedComponent.type}</div>
              </div>

              {(() => {
                const componentConfig = componentRegistry.getComponent(selectedComponent.type);

                if (componentConfig) {
                  // 为SmartInput组件使用专门的属性编辑器
                  if (selectedComponent.type === 'input') {
                    return (
                      <InputPropertyPanel
                        component={selectedComponent}
                        onPropertyChange={(propertyName: string, value: any) => {
                          onComponentUpdate(selectedComponent.id, {
                            props: {
                              ...selectedComponent.props,
                              [propertyName]: value,
                            },
                          });
                        }}
                      />
                    );
                  }

                  // 其他组件使用通用属性编辑器
                  return (
                    <PropertyEditor
                      config={componentConfig}
                      component={selectedComponent}
                      onPropertyChange={(propertyName: string, value: any) => {
                        onComponentUpdate(selectedComponent.id, {
                          props: {
                            ...selectedComponent.props,
                            [propertyName]: value,
                          },
                        });
                      }}
                    />
                  );
                } else {
                  return (
                    <div className="rounded-lg border border-yellow-200 bg-yellow-50 p-3">
                      <div className="flex items-center space-x-2">
                        <span className="text-yellow-600">⚠️</span>
                        <span className="text-sm text-yellow-700">
                          未找到组件配置: {selectedComponent.type}
                        </span>
                      </div>
                    </div>
                  );
                }
              })()}
            </div>
          </div>
        ) : (
          <div className="py-8 text-center">
            <div className="text-sm text-gray-400">
              <div className="mb-2 text-2xl">👆</div>
              <div>选择一个组件</div>
              <div>来编辑其属性</div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default PropertyPanel;
