import React from 'react';
import { useGridContainer } from '~/studio/workbench/canvas/GridContainer/useGridContainer';
import { GridCell } from '~/studio/workbench/canvas/GridCell';
import type { GridContainerProps } from '~/studio/workbench/canvas/GridContainer/types';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';

/**
 * 网格容器组件
 * 管理网格布局和组件放置
 * 集成事件域系统，确保画布域事件正确传递
 */
export const GridContainer: React.FC<GridContainerProps> = (props) => {
  const {
    columns,
    rows,
    gap,
    components,
    onComponentClick,
    onComponentUpdate,
    onComponentDelete,
    onComponentDoubleClick,
  } = props;

  const { locale } = useI18n();
  const { gridData, isComponentSelected } = useGridContainer(props);
  const isEmpty = !components || components.length === 0;

  return (
    <div
      className="relative grid rounded-lg border border-gray-200 bg-gray-50 p-4"
      style={{
        gridTemplateColumns: `repeat(${columns}, 1fr)`,
        gridTemplateRows: `repeat(${rows}, 1fr)`,
        gap: `${gap}px`,
        minHeight: '600px',
      }}
      data-domain="canvas"
    >
      {/* Empty state overlay */}
      {isEmpty && (
        <div className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center">
          <div className="rounded-xl border-2 border-dashed border-gray-300 bg-white/80 p-8 text-center">
            <div className="mb-3 text-4xl">📋</div>
            <p className="mb-1 font-medium text-gray-600">{resolveDesignerText(DESIGNER_I18N.emptyState.canvasEmpty, locale)}</p>
            <p className="text-sm text-gray-400">
              {resolveDesignerText(DESIGNER_I18N.emptyState.dragFieldsOrComponents, locale)}
            </p>
          </div>
        </div>
      )}

      {gridData.map((row, rowIndex) =>
        row.map((cell, colIndex) => {
          // 检查这个单元格是否应该被渲染
          // 如果这个单元格被占用，但不是组件的起始位置，则跳过渲染
          if (cell.occupied && cell.component) {
            const componentStartCol = cell.component.position?.column ?? colIndex;
            if (colIndex !== componentStartCol) {
              // 这是跨列组件的后续单元格，不渲染
              return null;
            }
          }

          return (
            <GridCell
              key={`${rowIndex}-${colIndex}`}
              row={rowIndex}
              column={colIndex}
              occupied={cell.occupied}
              component={cell.component}
              isSelected={isComponentSelected(cell.component)}
              onComponentClick={onComponentClick}
              onComponentUpdate={onComponentUpdate}
              onComponentDelete={onComponentDelete}
              onComponentDoubleClick={onComponentDoubleClick}
            />
          );
        }),
      )}
    </div>
  );
};
