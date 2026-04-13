import { useMemo } from 'react';
import type { Component } from '~/plugins/core-designer/components/studio/domain/schema/types';
import type { GridContainerProps } from '~/plugins/core-designer/components/studio/workbench/canvas/GridContainer/types';

export const useGridContainer = ({
  columns,
  rows,
  components,
  selectedComponents,
}: GridContainerProps) => {
  // 创建网格数据结构
  const gridData = useMemo(() => {
    const grid: Array<Array<{ occupied: boolean; component?: Component }>> = [];

    // 初始化网格
    for (let row = 0; row < rows; row++) {
      grid[row] = [];
      for (let col = 0; col < columns; col++) {
        grid[row][col] = { occupied: false };
      }
    }

    // 填充组件到网格中
    components.forEach((component) => {
      if (
        component.position &&
        component.position.row < rows &&
        component.position.column < columns
      ) {
        // 获取组件的宽度（span），优先使用props.colSpan，其次使用props.width，再使用span属性，默认为1
        // 确保width值在有效范围内
        const rawWidth = component.props?.colSpan || component.props?.width || component.span || 1;
        const componentWidth = Math.max(1, Math.min(columns, Number(rawWidth) || 1));
        const startCol = component.position.column;
        const endCol = Math.min(startCol + componentWidth, columns); // 确保不超出网格边界

        // 占用多个网格单元格
        for (let col = startCol; col < endCol; col++) {
          if (col < columns) {
            // 额外检查确保不超出边界
            grid[component.position.row][col] = {
              occupied: true,
              component,
            };
          }
        }
      }
    });

    return grid;
  }, [columns, rows, components]);

  // 检查组件是否被选中
  const isComponentSelected = (component?: Component) => {
    if (!component) return false;
    return selectedComponents.some((selected) => selected.id === component.id);
  };

  return {
    gridData,
    isComponentSelected,
  };
};
