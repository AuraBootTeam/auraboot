import React, { useEffect, useRef } from 'react';
import { GridContainer } from '~/plugins/core-designer/components/studio/workbench/canvas/GridContainer';
import { eventDomainManager, globalShortcutManager } from '~/plugins/core-designer/components/studio/services/managers';
import type { DesignCanvasProps } from '~/plugins/core-designer/components/studio/workbench/canvas/DesignCanvas/types';

/**
 * 设计画布组件
 * 主要的设计区域，包含网格容器和相关功能
 * 强化画布作为组件选择的唯一所有者
 */
export const DesignCanvas: React.FC<DesignCanvasProps> = (props) => {
  const canvasRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const canvasElement = canvasRef.current;
    if (!canvasElement) return;

    // 注册画布域
    eventDomainManager.registerDomain({
      id: 'canvas',
      name: 'canvas',
      element: canvasElement,
      isActive: true,
      priority: 1,
      handlers: {
        click: (event) => {
          // 处理画布点击事件，确保画布是组件选择的唯一所有者
          const target = event.target as HTMLElement;
          const componentId = target
            .closest('[data-component-id]')
            ?.getAttribute('data-component-id');

          if (componentId) {
            // 如果点击的是组件，查找对应的组件对象并触发选择
            const component = props.components?.find((c) => c.id === componentId);
            if (component) {
              event.stopPropagation();
              props.onComponentClick?.(component, event);
            }
          } else {
            // 如果点击的是画布空白区域，取消选择
            // 注意：这里不能传 null，因为类型不允许
            event.stopPropagation();
            // 不调用 onComponentClick，而是让上层处理取消选择
          }
        },
        dblclick: (event) => {
          // 处理画布双击事件
          const target = event.target as HTMLElement;
          const componentId = target
            .closest('[data-component-id]')
            ?.getAttribute('data-component-id');

          if (componentId) {
            event.stopPropagation();
            const component = props.components?.find((c) => c.id === componentId);
            if (component) {
              props.onComponentDoubleClick?.(component, event);
            }
          }
        },
        keydown: (event) => {
          // 处理画布快捷键
          if (event.key === 'Delete' || event.key === 'Backspace') {
            event.preventDefault();
            event.stopPropagation();
            // 触发删除选中的组件
            const selectedComponents = props.selectedComponents || [];
            if (selectedComponents.length > 0) {
              selectedComponents.forEach((component) => {
                props.onComponentDelete?.(component.id);
              });
            }
          }
        },
      },
    });

    // 注册全局快捷键管理器的画布域支持
    globalShortcutManager.registerDomain('canvas', {
      delete: () => {
        const selectedComponents = props.selectedComponents || [];
        if (selectedComponents.length > 0) {
          selectedComponents.forEach((component) => {
            props.onComponentDelete?.(component.id);
          });
        }
      },
      'ctrl+a': (event) => {
        event.preventDefault();
        // 全选画布中的组件
        const allComponents = props.components || [];
        if (allComponents.length > 0) {
          allComponents.forEach((component) => {
            props.onComponentClick?.(component, event as any);
          });
        }
      },
    });

    return () => {
      // 清理时注销域
      eventDomainManager.unregisterDomain('canvas');
      globalShortcutManager.unregisterDomain('canvas');
    };
  }, [props.components, props.selectedComponents]);

  const columns = props.columns ?? 12;
  const rows = props.rows ?? 12;
  const gap = props.gap ?? 16;
  const components = props.components ?? [];
  const selectedComponents = props.selectedComponents ?? [];

  return (
    <div ref={canvasRef} className="flex-1 bg-white p-4" data-domain="canvas">
      <div className="flex h-full flex-col">
        <div className="flex-1 overflow-auto">
          <GridContainer
            columns={columns}
            rows={rows}
            gap={gap}
            components={components}
            selectedComponents={selectedComponents}
            onComponentClick={props.onComponentClick}
            onComponentUpdate={props.onComponentUpdate}
            onComponentDelete={props.onComponentDelete}
            onComponentDoubleClick={props.onComponentDoubleClick}
          />
        </div>
      </div>
    </div>
  );
};
