import React, { useEffect, useRef, useState } from 'react';
import { useDroppable } from '@dnd-kit/core';
import { cn } from '~/utils/cn';
import type { SmartSlot as SmartSlotType } from '~/studio/services/layout/slotting/SmartSlotSystem';

interface SmartSlotProps {
  /** 插槽配置 */
  slot: SmartSlotType;
  /** 子元素 */
  children?: React.ReactNode;
  /** 自定义类名 */
  className?: string;
  /** 插槽更新回调 */
  onSlotUpdate?: (slotId: string, element: HTMLElement) => void;
  /** 是否显示插槽边框 */
  showBorder?: boolean;
  /** 是否显示插槽标签 */
  showLabel?: boolean;
}

/**
 * 智能插槽组件
 */
export const SmartSlot: React.FC<SmartSlotProps> = ({
  slot,
  children,
  className,
  onSlotUpdate,
  showBorder = false,
  showLabel = false,
}) => {
  const elementRef = useRef<HTMLDivElement>(null);
  const [isHovered, setIsHovered] = useState(false);

  // 使用 @dnd-kit 的 useDroppable
  const { isOver, setNodeRef, active } = useDroppable({
    id: slot.id,
    data: {
      type: slot.type,
      slotId: slot.id,
      containerId: slot.containerId,
      gridPosition: slot.gridPosition,
      canAccept: slot.canAccept,
    },
  });

  // 合并refs
  const setRefs = (element: HTMLDivElement | null) => {
    elementRef.current = element;
    setNodeRef(element);

    if (element && onSlotUpdate) {
      onSlotUpdate(slot.id, element);
    }
  };

  // 监听插槽状态变化
  useEffect(() => {
    if (elementRef.current && onSlotUpdate) {
      onSlotUpdate(slot.id, elementRef.current);
    }
  }, [slot, onSlotUpdate]);

  // 计算插槽样式
  const slotStyle: React.CSSProperties = {
    position: 'absolute',
    left: slot.position.x,
    top: slot.position.y,
    width: slot.size.width,
    height: slot.size.height,
    zIndex: slot.priority,
  };

  // 计算插槽类名
  const slotClassName = cn(
    'designer-smart-slot',
    'transition-all duration-200 ease-in-out',
    {
      // 基础状态
      'designer-slot-idle': slot.state === 'idle',
      'designer-slot-active': slot.state === 'active',
      'designer-slot-highlighted': slot.state === 'highlighted',
      'designer-slot-invalid': slot.state === 'invalid',

      // 拖拽状态
      'designer-slot-drag-over': isOver,
      'designer-slot-can-accept': slot.canAccept,
      'designer-slot-cannot-accept': !slot.canAccept,

      // 交互状态
      'designer-slot-hovered': isHovered,

      // 类型样式
      'designer-slot-grid': slot.type === 'grid',
      'designer-slot-container': slot.type === 'container',
      'designer-slot-column': slot.type === 'column',
      'designer-slot-row': slot.type === 'row',

      // 边框显示
      'designer-slot-show-border': showBorder || slot.state !== 'idle',

      // 标签显示
      'designer-slot-show-label': showLabel,
    },
    className,
  );

  return (
    <div
      ref={setRefs}
      className={slotClassName}
      style={slotStyle}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
      data-slot-id={slot.id}
      data-slot-type={slot.type}
      data-slot-state={slot.state}
      data-can-accept={slot.canAccept}
      data-is-over={isOver}
      data-has-active={!!active}
    >
      {/* 插槽内容 */}
      <div className="designer-slot-content h-full w-full">{children}</div>

      {/* 插槽标签 */}
      {showLabel && (
        <div className="designer-slot-label absolute -top-6 left-0 rounded border bg-white px-2 py-1 text-xs text-gray-500 shadow-sm">
          {slot.type} #{slot.id.slice(-4)}
        </div>
      )}

      {/* 插槽状态指示器 */}
      {slot.state !== 'idle' && (
        <div className="designer-slot-indicator absolute top-1 right-1 h-2 w-2 rounded-full">
          <div
            className={cn('h-full w-full rounded-full', {
              'bg-blue-500': slot.state === 'active',
              'bg-green-500': slot.state === 'highlighted',
              'bg-red-500': slot.state === 'invalid',
            })}
          />
        </div>
      )}

      {/* 拖拽悬停效果 */}
      {isOver && slot.canAccept && (
        <div className="designer-slot-drop-indicator bg-opacity-20 absolute inset-0 flex items-center justify-center rounded-md border-2 border-dashed border-blue-500 bg-blue-50">
          <div className="text-sm font-medium text-blue-600">放置到这里</div>
        </div>
      )}

      {/* 无效拖拽指示 */}
      {isOver && !slot.canAccept && (
        <div className="designer-slot-invalid-indicator bg-opacity-20 absolute inset-0 flex items-center justify-center rounded-md border-2 border-dashed border-red-500 bg-red-50">
          <div className="text-sm font-medium text-red-600">无法放置</div>
        </div>
      )}
    </div>
  );
};

/**
 * 插槽容器组件 - 用于管理多个插槽
 */
interface SmartSlotContainerProps {
  /** 插槽列表 */
  slots: SmartSlotType[];
  /** 子元素 */
  children?: React.ReactNode;
  /** 容器类名 */
  className?: string;
  /** 插槽更新回调 */
  onSlotUpdate?: (slotId: string, element: HTMLElement) => void;
  /** 是否显示所有插槽边框 */
  showBorders?: boolean;
  /** 是否显示所有插槽标签 */
  showLabels?: boolean;
  /** 是否启用调试模式 */
  debug?: boolean;
}

export const SmartSlotContainer: React.FC<SmartSlotContainerProps> = ({
  slots,
  children,
  className,
  onSlotUpdate,
  showBorders = false,
  showLabels = false,
  debug = false,
}) => {
  return (
    <div className={cn('designer-slot-container relative', className)}>
      {/* 背景内容 */}
      {children}

      {/* 插槽层 */}
      <div className="designer-slots-layer pointer-events-none absolute inset-0">
        {slots.map((slot) => (
          <SmartSlot
            key={slot.id}
            slot={slot}
            onSlotUpdate={onSlotUpdate}
            showBorder={showBorders || debug}
            showLabel={showLabels || debug}
            className="pointer-events-auto"
          />
        ))}
      </div>

      {/* 调试信息 */}
      {debug && (
        <div className="designer-debug-info bg-opacity-75 absolute top-2 left-2 rounded bg-black p-2 text-xs text-white">
          <div>插槽数量: {slots.length}</div>
          <div>活跃插槽: {slots.filter((s) => s.state === 'active').length}</div>
          <div>高亮插槽: {slots.filter((s) => s.state === 'highlighted').length}</div>
        </div>
      )}
    </div>
  );
};

export default SmartSlot;
