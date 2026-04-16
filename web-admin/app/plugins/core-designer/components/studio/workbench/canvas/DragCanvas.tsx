// 新的拖拽画布组件 - 集成布局引擎和网格系统
import React from 'react';
import { useDroppable } from '@dnd-kit/core';
import { useDragPreview } from '~/plugins/core-designer/components/studio/hooks/drag/useDragPreview';
import type { Component } from '~/plugins/core-designer/components/studio/workbench/canvas/types';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';

interface LayoutResult {
  grid: {
    columns: number;
    rows: number;
    gap: number;
    padding: number;
  };
  totalHeight: number;
  conflicts: Array<{ id: string; message: string }>;
}

interface DragCanvasProps {
  components: Component[];
  layoutResult: LayoutResult;
  selectedComponents: string[];
  onComponentClick: (event: React.MouseEvent) => void;
  onComponentUpdate: (updater: any) => void;
}

// 网格单元格组件
const GridCell: React.FC<{
  row: number;
  column: number;
  component?: Component;
  isSelected: boolean;
  onClick: (event: React.MouseEvent) => void;
}> = ({ row, column, component, isSelected, onClick }) => {
  const { locale } = useI18n();
  const { setNodeRef, isOver } = useDroppable({
    id: `cell-${row}-${column}`,
    data: {
      type: 'grid-cell',
      position: { row, column },
    },
  });

  return (
    <div
      ref={setNodeRef}
      className={`relative min-h-[60px] border border-gray-200 ${isOver ? 'border-blue-300 bg-blue-50' : ''} ${isSelected ? 'ring-2 ring-blue-500' : ''} ${component ? 'bg-white' : 'bg-gray-50'} `}
      data-component-id={component?.id}
      onClick={onClick}
    >
      {component && <ComponentRenderer component={component} isSelected={isSelected} />}

      {/* 网格位置指示器 */}
      <div className="absolute top-1 left-1 text-xs text-gray-400">
        {row},{column}
      </div>

      {/* 拖拽悬停指示器 */}
      {isOver && (
        <div className="bg-opacity-50 absolute inset-0 flex items-center justify-center border-2 border-dashed border-blue-400 bg-blue-100">
          <span className="text-sm font-medium text-blue-600">
            {resolveDesignerText(DESIGNER_I18N.dropZone.placeHere, locale)}
          </span>
        </div>
      )}
    </div>
  );
};

// 组件渲染器
const ComponentRenderer: React.FC<{
  component: Component;
  isSelected: boolean;
}> = ({ component, isSelected }) => {
  const { locale } = useI18n();
  return (
    <div
      className={`flex h-full flex-col p-3 ${isSelected ? 'bg-blue-50' : 'bg-white'} transition-colors hover:bg-gray-50`}
    >
      {/* 组件头部 */}
      <div className="mb-2 flex items-center justify-between">
        <div className="flex items-center space-x-2">
          <span className="rounded bg-gray-100 px-2 py-1 text-xs">{component.type}</span>
          {(component.props?.colSpan || component.span) &&
            (component.props?.colSpan || component.span) > 1 && (
              <span className="rounded bg-blue-100 px-2 py-1 text-xs text-blue-700">
                span: {component.props?.colSpan || component.span}
              </span>
            )}
        </div>

        {/* 组件操作按钮 */}
        <div className="flex space-x-1">
          <button className="text-xs text-gray-400 hover:text-gray-600">⚙️</button>
          <button className="text-xs text-gray-400 hover:text-red-600">🗑️</button>
        </div>
      </div>

      {/* 组件内容 */}
      <div className="flex flex-1 items-center justify-center">
        <div className="text-center">
          <div className="text-sm font-medium text-gray-700">
            {component.name || component.type}
          </div>
          <div className="mt-1 text-xs text-gray-500">
            {component.props?.label ||
              component.props?.placeholder ||
              resolveDesignerText(DESIGNER_I18N.dragCanvas.componentContent, locale)}
          </div>
        </div>
      </div>

      {/* 选中状态指示器 */}
      {isSelected && (
        <div className="pointer-events-none absolute inset-0 border-2 border-blue-500">
          <div className="absolute -top-6 left-0 rounded-t bg-blue-500 px-2 py-1 text-xs text-white">
            {resolveDesignerText(DESIGNER_I18N.dragCanvas.selectedIndicator, locale)}
          </div>
        </div>
      )}
    </div>
  );
};

// 主画布组件
export const DragCanvas: React.FC<DragCanvasProps> = ({
  components,
  layoutResult,
  selectedComponents,
  onComponentClick,
  onComponentUpdate,
}) => {
  const { locale } = useI18n();
  const { setNodeRef, isOver } = useDroppable({
    id: 'canvas',
    data: {
      type: 'canvas',
    },
  });

  // 启用拖拽预览
  const { isDragging } = useDragPreview({
    enabled: true,
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
      placeholderStyle: 'dashed',
    },
  });

  // 创建组件映射，便于快速查找
  const componentMap = React.useMemo(() => {
    const map = new Map<string, Component>();
    components.forEach((comp) => {
      if (!comp.position) {
        return;
      }
      const key = `${comp.position.row}-${comp.position.column}`;
      map.set(key, comp);
    });
    return map;
  }, [components]);

  // 渲染网格
  const renderGrid = () => {
    const { grid } = layoutResult;
    const rows: React.ReactNode[] = [];

    for (let row = 0; row < Math.max(grid.rows, 3); row++) {
      const cells: React.ReactNode[] = [];

      for (let col = 0; col < grid.columns; col++) {
        const key = `${row}-${col}`;
        const component = componentMap.get(key);
        const isSelected = component ? selectedComponents.includes(component.id) : false;

        // 检查是否是跨列组件的后续单元格
        let shouldRender = true;
        const componentSpan = component?.props?.colSpan || component?.span || 1;
        if (component && component.position && componentSpan > 1) {
          // 只渲染跨列组件的第一个单元格
          if (col !== component.position.column) {
            shouldRender = false;
          }
        }

        if (shouldRender) {
          cells.push(
            <GridCell
              key={`cell-${row}-${col}`}
              row={row}
              column={col}
              component={component}
              isSelected={isSelected}
              onClick={onComponentClick}
            />,
          );
        }
      }

      rows.push(
        <div
          key={`row-${row}`}
          className="grid gap-4"
          style={{
            gridTemplateColumns: `repeat(${grid.columns}, 1fr)`,
          }}
        >
          {cells}
        </div>,
      );
    }

    return rows;
  };

  return (
    <div className="flex flex-1 flex-col bg-gray-100">
      {/* 画布工具栏 */}
      <div className="flex items-center justify-between border-b border-gray-200 bg-white px-4 py-2">
        <div className="flex items-center space-x-4">
          <span className="text-sm font-medium text-gray-700">
            {resolveDesignerText(DESIGNER_I18N.dragCanvas.canvasTitle, locale, {
              columns: layoutResult.grid.columns,
            })}
          </span>
          <div className="flex items-center space-x-2">
            <button className="rounded bg-gray-100 px-3 py-1 text-xs hover:bg-gray-200">
              {resolveDesignerText(DESIGNER_I18N.dragCanvas.cols2, locale)}
            </button>
            <button className="rounded bg-blue-100 px-3 py-1 text-xs text-blue-700">
              {resolveDesignerText(DESIGNER_I18N.dragCanvas.cols4, locale)}
            </button>
            <button className="rounded bg-gray-100 px-3 py-1 text-xs hover:bg-gray-200">
              {resolveDesignerText(DESIGNER_I18N.dragCanvas.cols6, locale)}
            </button>
          </div>
        </div>

        <div className="flex items-center space-x-2 text-xs text-gray-500">
          <span>
            {resolveDesignerText(DESIGNER_I18N.dragCanvas.components, locale)}: {components.length}
          </span>
          <span>|</span>
          <span>
            {resolveDesignerText(DESIGNER_I18N.dragCanvas.rows, locale)}: {layoutResult.grid.rows}
          </span>
          {layoutResult.conflicts.length > 0 && (
            <>
              <span>|</span>
              <span className="text-red-500">
                {resolveDesignerText(DESIGNER_I18N.dragCanvas.conflicts, locale)}:{' '}
                {layoutResult.conflicts.length}
              </span>
            </>
          )}
        </div>
      </div>

      {/* 主画布区域 */}
      <div
        ref={setNodeRef}
        className={`flex-1 overflow-auto p-6 ${isOver ? 'bg-blue-50' : 'bg-gray-50'} ${isDragging ? 'dragging' : ''} `}
      >
        <div className="mx-auto max-w-6xl">
          {/* 网格容器 */}
          <div
            className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm"
            style={{
              minHeight: `${layoutResult.totalHeight}px`,
            }}
          >
            <div className="space-y-4">{renderGrid()}</div>
          </div>

          {/* 空状态 */}
          {components.length === 0 && (
            <div className="absolute inset-0 flex items-center justify-center">
              <div className="text-center">
                <div className="mb-2 text-lg text-gray-400">📋</div>
                <div className="text-sm text-gray-500">
                  {resolveDesignerText(DESIGNER_I18N.emptyState.startDesign, locale)}
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* 画布状态栏 */}
      <div className="border-t border-gray-200 bg-white px-4 py-2 text-xs text-gray-500">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-4">
            <span>
              {resolveDesignerText(DESIGNER_I18N.dragCanvas.grid, locale)}:{' '}
              {layoutResult.grid.columns}×{layoutResult.grid.rows}
            </span>
            <span>
              {resolveDesignerText(DESIGNER_I18N.dragCanvas.gap, locale)}: {layoutResult.grid.gap}px
            </span>
            <span>
              {resolveDesignerText(DESIGNER_I18N.dragCanvas.padding, locale)}:{' '}
              {layoutResult.grid.padding}px
            </span>
          </div>

          <div className="flex items-center space-x-2">
            {selectedComponents.length > 0 && (
              <span className="text-blue-600">
                {resolveDesignerText(DESIGNER_I18N.dragCanvas.selected, locale, {
                  count: selectedComponents.length,
                })}
              </span>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
