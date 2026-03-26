import React from 'react';
import { useGridCell } from '~/studio/workbench/canvas/GridCell/useGridCell';
import { DraggableWrapper } from '~/studio/workbench/canvas/drag/DraggableWrapper';
import type { GridCellProps } from '~/studio/workbench/canvas/GridCell/types';

/**
 * 网格单元格组件
 * 支持拖拽放置、组件选择等功能
 */
export const GridCell: React.FC<GridCellProps> = (props) => {
  const { row, column, occupied, component, onComponentClick, isSelected } = props;

  const { setNodeRef, isOver, isDragTarget } = useGridCell(props);

  // 获取组件的跨度值
  const getComponentSpan = () => {
    if (!component) return 1;
    // 优先使用 colSpan，然后是 width，最后是 size.span 和 span
    return (
      component.props?.colSpan ||
      component.props?.width ||
      component.size?.span ||
      component.span ||
      1
    );
  };

  // 根据跨度值生成对应的CSS类名
  const getColSpanClass = (span: number) => {
    const colSpanMap: { [key: number]: string } = {
      1: 'col-span-1',
      2: 'col-span-2',
      3: 'col-span-3',
      4: 'col-span-4',
      5: 'col-span-5',
      6: 'col-span-6',
      7: 'col-span-7',
      8: 'col-span-8',
      9: 'col-span-9',
      10: 'col-span-10',
      11: 'col-span-11',
      12: 'col-span-12',
    };
    return colSpanMap[span] || 'col-span-1';
  };

  const componentSpan = getComponentSpan();
  const colSpanClass = getColSpanClass(componentSpan);

  // 渲染组件内容
  const renderComponentContent = () => {
    if (!component) return null;

    // 获取组件图标
    const getComponentIcon = (type: string) => {
      const icons: Record<string, string> = {
        input: '📝',
        textarea: '📄',
        select: '📋',
        checkbox: '☑️',
        radio: '🔘',
        button: '🔲',
        date: '📅',
        file: '📎',
      };
      return icons[type] || '📦';
    };

    return (
      <div className="space-y-1">
        <div className="flex items-center space-x-1">
          <span className="text-sm">{getComponentIcon(component.type)}</span>
          <span className="truncate text-xs font-medium">
            {component.props.label || component.name}
          </span>
        </div>
        <div className="truncate text-xs text-gray-500">
          {component.type} · span:{' '}
          {component.props?.colSpan ||
            component.props?.width ||
            component.size?.span ||
            component.span ||
            1}
        </div>
        {component.props.placeholder && (
          <div className="truncate text-xs text-gray-400">{component.props.placeholder}</div>
        )}
      </div>
    );
  };

  return (
    <div
      ref={setNodeRef}
      className={`relative min-h-[80px] rounded-lg border-2 border-dashed p-2 transition-all duration-200 ${colSpanClass} ${
        occupied
          ? isSelected
            ? 'border-blue-500 bg-blue-50 shadow-md'
            : isDragTarget
              ? 'scale-105 transform animate-pulse border-orange-400 bg-orange-50 shadow-lg'
              : 'border-gray-300 bg-gray-50 hover:border-gray-400 hover:bg-gray-100'
          : isOver
            ? 'animate-pulse border-blue-400 bg-blue-100 shadow-sm'
            : 'border-gray-200 hover:border-blue-300 hover:bg-blue-50'
      } ${component ? 'cursor-pointer' : 'cursor-default'} `}
      data-component-id={component?.id}
      data-domain="canvas"
    >
      {/* 智能插槽高亮指示器 */}
      {isOver && !occupied && (
        <div className="absolute inset-0 flex items-center justify-center">
          <div className="animate-bounce rounded-full bg-blue-500 px-2 py-1 text-xs text-white shadow-lg">
            放置组件
          </div>
        </div>
      )}

      {/* 组件交换提示 */}
      {isDragTarget && (
        <div className="absolute inset-0 z-10 flex items-center justify-center">
          <div className="animate-bounce rounded-full bg-orange-500 px-2 py-1 text-xs text-white shadow-lg">
            交换位置
          </div>
        </div>
      )}

      {/* 网格位置指示器 */}
      {!occupied && (
        <div className="absolute top-1 left-1 font-mono text-xs text-gray-400">
          {row},{column}
        </div>
      )}

      {occupied && component ? (
        <DraggableWrapper component={component} onComponentClick={onComponentClick}>
          {renderComponentContent()}
        </DraggableWrapper>
      ) : null}
    </div>
  );
};
