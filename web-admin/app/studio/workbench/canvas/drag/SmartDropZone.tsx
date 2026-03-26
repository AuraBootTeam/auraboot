/**
 * 智能插槽组件
 * 提供可视化的拖拽插槽，支持多种类型和状态
 */

import React, { useRef, useEffect, useState } from 'react';
import { useDropZone, useDragAndDrop } from '~/studio/hooks/drag/useDragAndDrop';
import { type DropZone } from '~/studio/services/layout/slotting/DropZoneManager';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';

export interface SmartDropZoneProps {
  /** 插槽ID */
  id: string;
  /** 插槽类型 */
  type?: DropZone['type'];
  /** 父容器ID */
  parentId?: string;
  /** 网格位置 */
  gridPosition?: DropZone['gridPosition'];
  /** 插入位置 */
  insertIndex?: number;
  /** 优先级 */
  priority?: number;
  /** 允许的拖拽项类型 */
  allowedTypes?: string[];
  /** 是否占用 */
  occupied?: boolean;
  /** 自定义数据 */
  data?: Record<string, any>;
  /** 样式类名 */
  className?: string;
  /** 内联样式 */
  style?: React.CSSProperties;
  /** 子元素 */
  children?: React.ReactNode;
  /** 拖拽和放置实例 */
  dragAndDrop: ReturnType<typeof useDragAndDrop>;
  /** 放置回调 */
  onDrop?: (item: any, zone: DropZone) => void;
  /** 激活回调 */
  onActivate?: (zone: DropZone) => void;
  /** 取消激活回调 */
  onDeactivate?: (zone: DropZone) => void;
}

/**
 * 智能插槽组件
 */
export const SmartDropZone: React.FC<SmartDropZoneProps> = ({
  id,
  type = 'container',
  parentId,
  gridPosition,
  insertIndex,
  priority = 0,
  allowedTypes,
  occupied = false,
  data = {},
  className = '',
  style = {},
  children,
  dragAndDrop,
  onDrop,
  onActivate,
  onDeactivate,
}) => {
  const { locale } = useI18n();
  const elementRef = useRef<HTMLDivElement>(null);
  const [isActive, setIsActive] = useState(false);
  const [isHighlighted, setIsHighlighted] = useState(false);

  // 合并自定义数据
  const zoneData = {
    ...data,
    allowedTypes,
    occupied,
  };

  // 注册插槽
  useDropZone(
    elementRef as React.RefObject<HTMLElement>,
    {
      id,
      type,
      parentId,
      gridPosition,
      insertIndex,
      priority,
      data: zoneData,
    },
    dragAndDrop,
  );

  // 监听状态变化
  useEffect(() => {
    const { state } = dragAndDrop;

    // 检查是否为激活插槽
    const active = state.activeZone?.id === id;
    if (active !== isActive) {
      setIsActive(active);
      if (active) {
        onActivate?.(state.activeZone!);
      } else if (isActive) {
        // 从激活状态变为非激活状态
        onDeactivate?.(state.activeZone || ({ id } as DropZone));
      }
    }

    // 检查是否为高亮插槽
    const highlighted = state.highlightedZones.some((zone) => zone.id === id);
    setIsHighlighted(highlighted);
  }, [dragAndDrop.state, id, isActive, onActivate, onDeactivate]);

  // 处理放置
  useEffect(() => {
    const handleDrop = (event: DragEvent) => {
      if (isActive && dragAndDrop.state.dragItem) {
        const zone = dragAndDrop.state.activeZone;
        if (zone && zone.id === id) {
          onDrop?.(dragAndDrop.state.dragItem, zone);
        }
      }
    };

    const element = elementRef.current;
    if (element) {
      element.addEventListener('drop', handleDrop);
      return () => element.removeEventListener('drop', handleDrop);
    }
  }, [isActive, dragAndDrop.state.dragItem, id, onDrop]);

  // 计算样式类名
  const getClassName = () => {
    const classes = ['smart-drop-zone'];

    if (className) {
      classes.push(className);
    }

    classes.push(`smart-drop-zone--${type}`);

    if (isActive) {
      classes.push('smart-drop-zone--active');
    }

    if (isHighlighted) {
      classes.push('smart-drop-zone--highlighted');
    }

    if (occupied) {
      classes.push('smart-drop-zone--occupied');
    }

    if (dragAndDrop.state.isDragging) {
      classes.push('smart-drop-zone--dragging');

      // 检查是否可接受当前拖拽项
      const canAccept = checkCanAccept();
      if (canAccept) {
        classes.push('smart-drop-zone--acceptable');
      } else {
        classes.push('smart-drop-zone--rejected');
      }
    }

    return classes.join(' ');
  };

  // 检查是否可接受拖拽项
  const checkCanAccept = () => {
    const { dragItem } = dragAndDrop.state;
    if (!dragItem) return false;

    // 检查类型限制
    if (allowedTypes && allowedTypes.length > 0) {
      if (!allowedTypes.includes(dragItem.type)) {
        return false;
      }
    }

    // 检查是否已占用
    if (occupied && type !== 'between') {
      return false;
    }

    return true;
  };

  // 计算内联样式
  const getStyle = (): React.CSSProperties => {
    const computedStyle: React.CSSProperties = { ...style };

    // 网格位置样式
    if (gridPosition) {
      computedStyle.gridRow = `${gridPosition.row} / span ${gridPosition.rowSpan || 1}`;
      computedStyle.gridColumn = `${gridPosition.col} / span ${gridPosition.colSpan || 1}`;
    }

    return computedStyle;
  };

  return (
    <div
      ref={elementRef}
      className={getClassName()}
      style={getStyle()}
      data-drop-zone-id={id}
      data-drop-zone-type={type}
      data-can-accept={checkCanAccept()}
    >
      {children}

      {/* 插槽指示器 */}
      {dragAndDrop.state.isDragging && (
        <div className="smart-drop-zone__indicator">
          {type === 'between' && <div className="smart-drop-zone__insertion-line" />}
          {type === 'slot' && !children && (
            <div className="smart-drop-zone__placeholder">
              <div className="smart-drop-zone__placeholder-icon">+</div>
              <div className="smart-drop-zone__placeholder-text">{resolveDesignerText(DESIGNER_I18N.dropZone.dropHere, locale)}</div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

/**
 * 容器插槽
 */
export const ContainerDropZone: React.FC<Omit<SmartDropZoneProps, 'type'>> = (props) => (
  <SmartDropZone {...props} type="container" />
);

/**
 * 组件插槽
 */
export const ComponentDropZone: React.FC<Omit<SmartDropZoneProps, 'type'>> = (props) => (
  <SmartDropZone {...props} type="slot" />
);

/**
 * 网格单元插槽
 */
export const GridCellDropZone: React.FC<Omit<SmartDropZoneProps, 'type'>> = (props) => (
  <SmartDropZone {...props} type="grid-cell" />
);

/**
 * 插入位置插槽
 */
export const InsertionDropZone: React.FC<Omit<SmartDropZoneProps, 'type'>> = (props) => (
  <SmartDropZone {...props} type="between" />
);

/**
 * 网格布局插槽容器
 */
export interface GridDropZoneContainerProps {
  /** 网格行数 */
  rows: number;
  /** 网格列数 */
  cols: number;
  /** 单元格大小 */
  cellSize?: { width: number; height: number };
  /** 间距 */
  gap?: number;
  /** 容器ID */
  containerId: string;
  /** 拖拽和放置实例 */
  dragAndDrop: ReturnType<typeof useDragAndDrop>;
  /** 占用的单元格 */
  occupiedCells?: Array<{ row: number; col: number; rowSpan?: number; colSpan?: number }>;
  /** 单元格放置回调 */
  onCellDrop?: (item: any, position: { row: number; col: number }) => void;
  /** 样式类名 */
  className?: string;
  /** 子元素 */
  children?: React.ReactNode;
}

export const GridDropZoneContainer: React.FC<GridDropZoneContainerProps> = ({
  rows,
  cols,
  cellSize = { width: 40, height: 40 },
  gap = 4,
  containerId,
  dragAndDrop,
  occupiedCells = [],
  onCellDrop,
  className = '',
  children,
}) => {
  // 检查单元格是否被占用
  const isCellOccupied = (row: number, col: number) => {
    return occupiedCells.some((cell) => {
      const rowSpan = cell.rowSpan || 1;
      const colSpan = cell.colSpan || 1;
      return (
        row >= cell.row && row < cell.row + rowSpan && col >= cell.col && col < cell.col + colSpan
      );
    });
  };

  // 生成网格单元格
  const renderGridCells = () => {
    const cells = [];

    for (let row = 1; row <= rows; row++) {
      for (let col = 1; col <= cols; col++) {
        const cellId = `${containerId}-cell-${row}-${col}`;
        const occupied = isCellOccupied(row, col);

        cells.push(
          <GridCellDropZone
            key={cellId}
            id={cellId}
            parentId={containerId}
            gridPosition={{ row, col }}
            occupied={occupied}
            dragAndDrop={dragAndDrop}
            onDrop={(item) => onCellDrop?.(item, { row, col })}
            style={{
              width: cellSize.width,
              height: cellSize.height,
            }}
          />,
        );
      }
    }

    return cells;
  };

  const containerStyle: React.CSSProperties = {
    display: 'grid',
    gridTemplateRows: `repeat(${rows}, ${cellSize.height}px)`,
    gridTemplateColumns: `repeat(${cols}, ${cellSize.width}px)`,
    gap: `${gap}px`,
    position: 'relative',
  };

  return (
    <div className={`grid-drop-zone-container ${className}`} style={containerStyle}>
      {renderGridCells()}
      {children}
    </div>
  );
};
