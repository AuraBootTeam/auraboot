import { useCallback, useEffect, useRef } from 'react';
import { useDndMonitor } from '@dnd-kit/core';
import { DragPreviewSystem } from '~/studio/services/layout/drag-preview/DragPreviewSystem';
import type { ComponentSchema } from '~/studio/domain/schema/types';
import type {
  DragPreviewConfig,
  GhostEffectConfig,
} from '~/studio/services/layout/drag-preview/DragPreviewSystem';

/**
 * 拖拽预览Hook配置
 */
export interface UseDragPreviewOptions {
  /** 预览配置 */
  previewConfig?: Partial<DragPreviewConfig>;
  /** Ghost效果配置 */
  ghostConfig?: Partial<GhostEffectConfig>;
  /** 是否启用 */
  enabled?: boolean;
  /** 拖拽开始回调 */
  onDragStart?: (component: ComponentSchema, element: HTMLElement) => void;
  /** 拖拽结束回调 */
  onDragEnd?: (component: ComponentSchema | null) => void;
  /** 拖拽移动回调 */
  onDragMove?: (position: { x: number; y: number }) => void;
}

/**
 * 拖拽预览Hook
 */
export const useDragPreview = (options: UseDragPreviewOptions = {}) => {
  const {
    previewConfig,
    ghostConfig,
    enabled = true,
    onDragStart,
    onDragEnd,
    onDragMove,
  } = options;

  const previewSystemRef = useRef<DragPreviewSystem | null>(null);
  const draggedElementRef = useRef<HTMLElement | null>(null);
  const draggedComponentRef = useRef<ComponentSchema | null>(null);

  // 初始化预览系统
  useEffect(() => {
    if (enabled) {
      previewSystemRef.current = DragPreviewSystem.getInstance();

      // 更新配置
      if (previewConfig) {
        previewSystemRef.current.updateConfig(previewConfig);
      }
      if (ghostConfig) {
        previewSystemRef.current.updateGhostConfig(ghostConfig);
      }
    }

    return () => {
      // 清理时不销毁单例，只重置状态
      if (previewSystemRef.current) {
        previewSystemRef.current.endDragPreview();
      }
    };
  }, [enabled, previewConfig, ghostConfig]);

  // 监听@dnd-kit的拖拽事件
  useDndMonitor({
    onDragStart(event) {
      if (!enabled || !previewSystemRef.current) return;

      const { active } = event;
      const activeData = active.data.current;

      // 获取拖拽的组件数据
      const component = activeData?.component as ComponentSchema;
      if (!component) return;

      // 获取拖拽元素
      const element = document.querySelector(`[data-component-id="${active.id}"]`) as HTMLElement;
      if (!element) return;

      // 保存引用
      draggedElementRef.current = element;
      draggedComponentRef.current = component;

      // 创建模拟的鼠标事件
      const mouseEvent = new MouseEvent('mousedown', {
        clientX: 0,
        clientY: 0,
        bubbles: true,
      });

      // 确定拖拽类型
      const dragType = activeData?.dragType || 'move';

      // 开始预览
      previewSystemRef.current.startDragPreview(component, element, mouseEvent, dragType);

      // 触发回调
      onDragStart?.(component, element);
    },

    onDragMove(event) {
      if (!enabled || !previewSystemRef.current) return;

      const state = previewSystemRef.current.getState();
      if (state.isDragging) {
        onDragMove?.(state.mousePosition);
      }
    },

    onDragEnd() {
      if (!enabled || !previewSystemRef.current) return;

      const component = draggedComponentRef.current;

      // 结束预览
      previewSystemRef.current.endDragPreview();

      // 清理引用
      draggedElementRef.current = null;
      draggedComponentRef.current = null;

      // 触发回调
      onDragEnd?.(component);
    },

    onDragCancel() {
      if (!enabled || !previewSystemRef.current) return;

      const component = draggedComponentRef.current;

      // 结束预览
      previewSystemRef.current.endDragPreview();

      // 清理引用
      draggedElementRef.current = null;
      draggedComponentRef.current = null;

      // 触发回调
      onDragEnd?.(component);
    },
  });

  // 手动开始拖拽预览
  const startPreview = useCallback(
    (
      component: ComponentSchema,
      element: HTMLElement,
      mouseEvent: MouseEvent,
      dragType: 'move' | 'copy' | 'create' = 'move',
    ) => {
      if (!enabled || !previewSystemRef.current) return;

      previewSystemRef.current.startDragPreview(component, element, mouseEvent, dragType);
      draggedElementRef.current = element;
      draggedComponentRef.current = component;

      onDragStart?.(component, element);
    },
    [enabled, onDragStart],
  );

  // 手动结束拖拽预览
  const endPreview = useCallback(() => {
    if (!enabled || !previewSystemRef.current) return;

    const component = draggedComponentRef.current;
    previewSystemRef.current.endDragPreview();

    draggedElementRef.current = null;
    draggedComponentRef.current = null;

    onDragEnd?.(component);
  }, [enabled, onDragEnd]);

  // 更新预览配置
  const updatePreviewConfig = useCallback((config: Partial<DragPreviewConfig>) => {
    if (previewSystemRef.current) {
      previewSystemRef.current.updateConfig(config);
    }
  }, []);

  // 更新Ghost配置
  const updateGhostConfig = useCallback((config: Partial<GhostEffectConfig>) => {
    if (previewSystemRef.current) {
      previewSystemRef.current.updateGhostConfig(config);
    }
  }, []);

  // 获取当前状态
  const getPreviewState = useCallback(() => {
    return previewSystemRef.current?.getState() || null;
  }, []);

  // 获取当前配置
  const getPreviewConfig = useCallback(() => {
    return previewSystemRef.current?.getConfig() || null;
  }, []);

  // 获取Ghost配置
  const getGhostConfig = useCallback(() => {
    return previewSystemRef.current?.getGhostConfig() || null;
  }, []);

  return {
    // 状态
    isEnabled: enabled,
    isDragging: getPreviewState()?.isDragging || false,
    draggedComponent: draggedComponentRef.current,
    draggedElement: draggedElementRef.current,

    // 方法
    startPreview,
    endPreview,
    updatePreviewConfig,
    updateGhostConfig,
    getPreviewState,
    getPreviewConfig,
    getGhostConfig,
  };
};

/**
 * 简化的拖拽预览Hook - 只启用基础功能
 */
export const useSimpleDragPreview = (enabled: boolean = true) => {
  return useDragPreview({
    enabled,
    previewConfig: {
      enabled: true,
      opacity: 0.8,
      scale: 0.9,
      style: 'ghost',
      showInfo: true,
    },
    ghostConfig: {
      enabled: true,
      opacity: 0.3,
      showPlaceholder: true,
    },
  });
};

/**
 * 高级拖拽预览Hook - 启用所有功能
 */
export const useAdvancedDragPreview = (
  onDragStart?: (component: ComponentSchema, element: HTMLElement) => void,
  onDragEnd?: (component: ComponentSchema | null) => void,
) => {
  return useDragPreview({
    enabled: true,
    previewConfig: {
      enabled: true,
      opacity: 0.9,
      scale: 0.95,
      style: 'ghost',
      showInfo: true,
      animationDuration: 150,
    },
    ghostConfig: {
      enabled: true,
      opacity: 0.4,
      blur: 1,
      showPlaceholder: true,
      placeholderStyle: 'dashed',
    },
    onDragStart,
    onDragEnd,
  });
};

export default useDragPreview;
