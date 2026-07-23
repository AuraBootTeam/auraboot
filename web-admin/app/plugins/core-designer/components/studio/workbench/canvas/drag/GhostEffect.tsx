/**
 * Ghost效果组件
 * 为拖拽操作提供视觉反馈的Ghost效果
 */

import React, { useEffect, useRef, useState } from 'react';
import { sanitizeHtml } from '~/framework/meta/utils/sanitizeHtml';
import { useDragPreview } from '~/plugins/core-designer/components/studio/workbench/canvas/drag/DragPreviewProvider';

export interface GhostEffectProps {
  /** 是否启用Ghost效果 */
  enabled?: boolean;
  /** Ghost透明度 */
  opacity?: number;
  /** 动画持续时间 */
  animationDuration?: number;
  /** 自定义样式类名 */
  className?: string;
  /** 内联样式 */
  style?: React.CSSProperties;
  /** 子组件 */
  children?: React.ReactNode;
}

/**
 * Ghost效果组件
 */
export const GhostEffect: React.FC<GhostEffectProps> = ({
  enabled = true,
  opacity = 0.5,
  animationDuration = 200,
  className = '',
  style = {},
  children,
}) => {
  const { state } = useDragPreview();
  const ghostRef = useRef<HTMLDivElement>(null);
  const [isVisible, setIsVisible] = useState(false);

  useEffect(() => {
    if (!enabled || !state.isVisible) {
      setIsVisible(false);
      return;
    }

    setIsVisible(true);
  }, [enabled, state.isVisible]);

  useEffect(() => {
    if (!ghostRef.current) return;

    const element = ghostRef.current;

    if (isVisible) {
      element.style.opacity = '0';
      element.style.transform = 'scale(1.05)';

      // 触发动画
      requestAnimationFrame(() => {
        element.style.transition = `opacity ${animationDuration}ms ease-out, transform ${animationDuration}ms ease-out`;
        element.style.opacity = opacity.toString();
        element.style.transform = 'scale(1)';
      });
    } else {
      element.style.transition = `opacity ${animationDuration}ms ease-in, transform ${animationDuration}ms ease-in`;
      element.style.opacity = '0';
      element.style.transform = 'scale(0.95)';
    }
  }, [isVisible, opacity, animationDuration]);

  if (!enabled) {
    return <>{children}</>;
  }

  return (
    <div className={`ghost-effect-container ${className}`} style={style}>
      {children}
      {isVisible && (
        <div
          ref={ghostRef}
          className="ghost-effect-overlay"
          style={{
            position: 'absolute',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            pointerEvents: 'none',
            zIndex: 1000,
            opacity: 0,
          }}
        >
          <div className="ghost-effect-content">
            {state.dragItem && (
              <div className="ghost-effect-item">
                <div className="ghost-effect-item__type">{state.dragItem.type || 'Component'}</div>
                <div className="ghost-effect-item__name">
                  {state.dragItem.name || state.dragItem.data?.name || 'Dragging...'}
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

/**
 * 拖拽项Ghost效果组件
 */
export interface DragItemGhostProps {
  /** 拖拽项数据 */
  item: any;
  /** 是否正在拖拽 */
  isDragging?: boolean;
  /** Ghost配置 */
  ghostConfig?: {
    opacity?: number;
    scale?: number;
    blur?: number;
    brightness?: number;
  };
  /** 样式类名 */
  className?: string;
  /** 子组件 */
  children: React.ReactNode;
}

export const DragItemGhost: React.FC<DragItemGhostProps> = ({
  item,
  isDragging = false,
  ghostConfig = {},
  className = '',
  children,
}) => {
  const { opacity = 0.5, scale = 0.95, blur = 1, brightness = 0.8 } = ghostConfig;

  const ghostStyle: React.CSSProperties = isDragging
    ? {
        opacity,
        transform: `scale(${scale})`,
        filter: `blur(${blur}px) brightness(${brightness})`,
        transition: 'all 200ms ease-out',
        pointerEvents: 'none',
      }
    : {};

  return (
    <div
      className={`drag-item-ghost ${isDragging ? 'dragging' : ''} ${className}`}
      style={ghostStyle}
      data-drag-item={JSON.stringify(item)}
    >
      {children}
    </div>
  );
};

/**
 * 拖拽源Ghost效果Hook
 */
export function useDragSourceGhost(
  elementRef: React.RefObject<HTMLElement>,
  options: {
    /** 是否启用Ghost效果 */
    enabled?: boolean;
    /** Ghost样式配置 */
    ghostStyle?: {
      opacity?: number;
      scale?: number;
      blur?: number;
      brightness?: number;
    };
    /** 动画持续时间 */
    animationDuration?: number;
  } = {},
) {
  const { enabled = true, ghostStyle = {}, animationDuration = 200 } = options;
  const [isDragging, setIsDragging] = useState(false);
  const originalStyleRef = useRef<string>('');

  useEffect(() => {
    if (!elementRef.current || !enabled) return;

    const element = elementRef.current;
    originalStyleRef.current = element.style.cssText;

    const handleDragStart = () => {
      setIsDragging(true);

      const { opacity = 0.5, scale = 0.95, blur = 1, brightness = 0.8 } = ghostStyle;

      element.style.transition = `all ${animationDuration}ms ease-out`;
      element.style.opacity = opacity.toString();
      element.style.transform = `scale(${scale})`;
      element.style.filter = `blur(${blur}px) brightness(${brightness})`;
      element.style.pointerEvents = 'none';
    };

    const handleDragEnd = () => {
      setIsDragging(false);

      element.style.transition = `all ${animationDuration}ms ease-in`;
      element.style.cssText = originalStyleRef.current;

      // 清理过渡效果
      setTimeout(() => {
        element.style.transition = '';
      }, animationDuration);
    };

    element.addEventListener('dragstart', handleDragStart);
    element.addEventListener('dragend', handleDragEnd);

    return () => {
      element.removeEventListener('dragstart', handleDragStart);
      element.removeEventListener('dragend', handleDragEnd);

      // 恢复原始样式
      if (element.style.cssText !== originalStyleRef.current) {
        element.style.cssText = originalStyleRef.current;
      }
    };
  }, [elementRef.current, enabled, ghostStyle, animationDuration]);

  return { isDragging };
}

/**
 * 拖拽预览Ghost效果组件
 */
export interface DragPreviewGhostProps {
  /** 预览元素引用 */
  previewRef: React.RefObject<HTMLElement>;
  /** Ghost配置 */
  config?: {
    enabled?: boolean;
    opacity?: number;
    scale?: number;
    rotation?: number;
    shadow?: string;
  };
  /** 鼠标位置 */
  mousePosition?: { x: number; y: number };
}

export const DragPreviewGhost: React.FC<DragPreviewGhostProps> = ({
  previewRef,
  config = {},
  mousePosition = { x: 0, y: 0 },
}) => {
  const {
    enabled = true,
    opacity = 0.8,
    scale = 1.05,
    rotation = 2,
    shadow = '0 8px 32px rgba(0, 0, 0, 0.3)',
  } = config;

  const [isVisible, setIsVisible] = useState(false);

  useEffect(() => {
    if (!previewRef.current || !enabled) {
      setIsVisible(false);
      return;
    }

    setIsVisible(true);
  }, [previewRef.current, enabled]);

  if (!isVisible || !enabled) {
    return null;
  }

  const ghostStyle: React.CSSProperties = {
    position: 'fixed',
    left: mousePosition.x,
    top: mousePosition.y,
    opacity,
    transform: `scale(${scale}) rotate(${rotation}deg)`,
    boxShadow: shadow,
    pointerEvents: 'none',
    zIndex: 10000,
    transition: 'all 100ms ease-out',
  };

  return (
    <div className="drag-preview-ghost" style={ghostStyle}>
      <div className="drag-preview-ghost__content">
        {previewRef.current && (
          <div
            dangerouslySetInnerHTML={{
              // Defense-in-depth (SEC-20260723-13): the ghost echoes the innerHTML of an
              // already-rendered canvas node; sanitize it so no unsanitized markup can ever
              // reach the DOM through the drag-preview path.
              __html: sanitizeHtml(previewRef.current.innerHTML),
            }}
          />
        )}
      </div>
    </div>
  );
};

/**
 * 多层Ghost效果组件
 */
export interface MultiLayerGhostProps {
  /** 层数 */
  layers?: number;
  /** 每层的偏移 */
  layerOffset?: { x: number; y: number };
  /** 每层的透明度递减 */
  opacityDecay?: number;
  /** 每层的缩放递减 */
  scaleDecay?: number;
  /** 是否启用 */
  enabled?: boolean;
  /** 子组件 */
  children: React.ReactNode;
}

export const MultiLayerGhost: React.FC<MultiLayerGhostProps> = ({
  layers = 3,
  layerOffset = { x: 2, y: 2 },
  opacityDecay = 0.3,
  scaleDecay = 0.05,
  enabled = true,
  children,
}) => {
  if (!enabled) {
    return <>{children}</>;
  }

  return (
    <div className="multi-layer-ghost">
      {/* 渲染多层Ghost */}
      {Array.from({ length: layers }, (_, index) => {
        const layer = layers - index - 1;
        const opacity = Math.max(0.1, 1 - layer * opacityDecay);
        const scale = Math.max(0.8, 1 - layer * scaleDecay);
        const offsetX = layer * layerOffset.x;
        const offsetY = layer * layerOffset.y;

        return (
          <div
            key={layer}
            className={`multi-layer-ghost__layer multi-layer-ghost__layer--${layer}`}
            style={{
              position: 'absolute',
              top: offsetY,
              left: offsetX,
              opacity,
              transform: `scale(${scale})`,
              pointerEvents: 'none',
              zIndex: 1000 - layer,
            }}
          >
            {children}
          </div>
        );
      })}

      {/* 原始内容 */}
      <div className="multi-layer-ghost__original">{children}</div>
    </div>
  );
};
