import React, { useState, useRef, useImperativeHandle, forwardRef } from 'react';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  DragOverlay,
} from '@dnd-kit/core';
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  rectSwappingStrategy,
} from '@dnd-kit/sortable';
import { SortableItem } from '~/routes/pages/SortableItem';

export const HorizontalDndLayout = forwardRef(
  ({ initialComponents = [], onDragOver, onDrop }, ref) => {
    const [activeId, setActiveId] = useState(null);

    // 修改数据结构为行包装器结构
    const [rows, setRows] = useState([]);

    // 初始化行数据
    React.useEffect(() => {
      if (initialComponents.length > 0) {
        setRows([
          {
            id: 'row-1',
            components: initialComponents,
          },
        ]);
      }
    }, []);

    // 获取所有组件的ID列表（用于DnD上下文）
    const getAllComponentIds = () => {
      // 添加安全检查，确保每个组件都有 id
      return rows.flatMap((row) =>
        row.components
          .filter((comp) => comp && comp.id) // 过滤掉没有 id 的组件
          .map((comp) => comp.id),
      );
    };

    // 获取组件所在的行和索引
    const getComponentLocation = (id) => {
      for (let rowIndex = 0; rowIndex < rows.length; rowIndex++) {
        const compIndex = rows[rowIndex].components.findIndex((comp) => comp.id === id);
        if (compIndex !== -1) {
          return { rowIndex, compIndex };
        }
      }
      return { rowIndex: -1, compIndex: -1 };
    };

    // 计算每个组件的宽度
    const getComponentWidth = (componentId) => {
      const { rowIndex } = getComponentLocation(componentId);
      if (rowIndex === -1) return '100%';

      const componentsInRow = rows[rowIndex].components.length;
      return `${100 / Math.min(componentsInRow, 4)}%`;
    };

    // 计算画布的最小高度
    const getCanvasMinHeight = () => {
      // 基础高度
      const baseHeight = 300;
      // 每行高度（组件高度 + 边距）
      const rowHeight = 120;
      // 根据行数计算最小高度，确保至少有一个空行用于拖放
      return Math.max(baseHeight, (rows.length + 1) * rowHeight);
    };

    const sensors = useSensors(
      useSensor(PointerSensor),
      useSensor(KeyboardSensor, {
        coordinateGetter: sortableKeyboardCoordinates,
      }),
    );

    const handleDragStart = (event) => {
      const { active } = event;
      setActiveId(active.id);
    };

    const handleDragEnd = (event) => {
      const { active, over } = event;

      if (!over || active.id === over.id) {
        setActiveId(null);
        return;
      }

      const { rowIndex: sourceRowIndex, compIndex: sourceCompIndex } = getComponentLocation(
        active.id,
      );
      const { rowIndex: targetRowIndex, compIndex: targetCompIndex } = getComponentLocation(
        over.id,
      );

      if (sourceRowIndex === -1 || targetRowIndex === -1) {
        setActiveId(null);
        return;
      }

      // 判断是交换行还是合并行
      // 获取鼠标位置和目标组件位置
      const overRect = event.over.rect;
      const activeRect = event.active.rect;

      // 计算鼠标在目标组件上的相对位置
      const isTopHalf = event.activatorEvent.clientY < overRect.top + overRect.height / 2;
      const isBottomHalf = !isTopHalf;

      console.log('拖拽信息:', {
        sourceRowIndex,
        sourceCompIndex,
        targetRowIndex,
        targetCompIndex,
        activeId: active.id,
        overId: over.id,
        isTopHalf,
        isBottomHalf,
        clientY: event.activatorEvent.clientY,
        overRect,
      });

      setRows((prevRows) => {
        try {
          const newRows = [...prevRows];

          // 从源行中移除组件
          const sourceComponent = { ...newRows[sourceRowIndex].components[sourceCompIndex] };
          if (!sourceComponent) {
            console.error('源组件不存在:', sourceRowIndex, sourceCompIndex);
            return prevRows;
          }

          // 先从源行中移除组件
          newRows[sourceRowIndex] = {
            ...newRows[sourceRowIndex],
            components: newRows[sourceRowIndex].components.filter(
              (_, index) => index !== sourceCompIndex,
            ),
          };

          // 判断操作类型
          // 如果是同一行内的移动，或者明确是要合并到目标行
          if (
            sourceRowIndex === targetRowIndex ||
            (sourceRowIndex !== targetRowIndex && !isTopHalf && !isBottomHalf)
          ) {
            // 合并行操作 - 将组件添加到目标行
            if (!newRows[targetRowIndex]) {
              console.error('目标行不存在:', targetRowIndex);
              return prevRows;
            }

            const targetComponents = [...newRows[targetRowIndex].components];
            targetComponents.splice(targetCompIndex, 0, sourceComponent);

            newRows[targetRowIndex] = {
              ...newRows[targetRowIndex],
              components: targetComponents,
            };
          } else {
            // 交换行操作 - 创建新行并插入到适当位置
            const newRow = {
              id: `row-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
              components: [sourceComponent],
            };

            // 根据拖放位置决定插入新行的位置
            if (isTopHalf) {
              // 在目标行之前插入
              newRows.splice(targetRowIndex, 0, newRow);
            } else {
              // 在目标行之后插入
              newRows.splice(targetRowIndex + 1, 0, newRow);
            }
          }

          // 移除空行，但确保每行都有有效的 id
          const filteredRows = newRows
            .filter((row) => row && row.components && row.components.length > 0)
            .map((row) => {
              if (!row.id) {
                return {
                  ...row,
                  id: `row-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
                };
              }
              return row;
            });

          console.log('更新后的行:', filteredRows);
          return filteredRows;
        } catch (error) {
          console.error('拖拽处理错误:', error);
          return prevRows;
        }
      });

      setActiveId(null);
    };

    const handleDragCancel = () => {
      setActiveId(null);
    };

    // 处理新组件的添加
    const addComponent = (newComponent, rowIndex = -1) => {
      if (rowIndex >= 0 && rowIndex < rows.length) {
        // 添加到指定行
        setRows((prevRows) => {
          const newRows = [...prevRows];
          newRows[rowIndex] = {
            ...newRows[rowIndex],
            components: [...newRows[rowIndex].components, newComponent],
          };
          return newRows;
        });
      } else {
        // 创建新行
        setRows((prevRows) => [
          ...prevRows,
          {
            id: `row-${Date.now()}`,
            components: [newComponent],
          },
        ]);
      }
    };

    // 处理组件的删除
    const handleDeleteComponent = (id) => {
      console.log('删除组件:', id);
      setRows((prevRows) => {
        // 找到包含该组件的行
        const newRows = prevRows.map((row) => ({
          ...row,
          components: row.components.filter((comp) => comp.id !== id),
        }));

        // 移除空行
        return newRows.filter((row) => row.components.length > 0);
      });
    };

    // 暴露 addComponent 方法给父组件
    useImperativeHandle(ref, () => ({
      addComponent: (component) => addComponent(component),
    }));

    // 自定义处理从侧边栏拖放的组件
    const handleExternalDrop = (e) => {
      e.preventDefault();
      const content = e.dataTransfer.getData('text/plain');
      if (!content) return;

      // 获取鼠标位置和容器位置
      const mouseY = e.clientY;
      const containerRect = e.currentTarget.getBoundingClientRect();
      const relativeY = mouseY - containerRect.top;

      // 估算每行高度
      const rowHeight = 120; // 组件高度 + 边距

      // 计算应该放在哪一行
      let targetRowIndex = Math.floor(relativeY / rowHeight);
      targetRowIndex = Math.max(0, Math.min(targetRowIndex, rows.length));

      const newComponent = {
        id: `component-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`, // 确保 ID 唯一
        content: content,
      };

      // 如果目标行存在，添加到该行，否则创建新行
      if (targetRowIndex < rows.length) {
        addComponent(newComponent, targetRowIndex);
      } else {
        addComponent(newComponent);
      }
    };

    return (
      <div
        style={{
          width: '100%',
          minHeight: `${getCanvasMinHeight()}px`, // 动态计算最小高度
          height: 'auto', // 自适应高度
          border: '1px solid #ccc',
          display: 'flex',
          flexDirection: 'column',
          position: 'relative',
          padding: '4px',
          boxSizing: 'border-box',
        }}
        onDragOver={(e) => e.preventDefault()}
        onDrop={handleExternalDrop}
      >
        <DndContext
          sensors={sensors}
          collisionDetection={closestCenter}
          onDragStart={handleDragStart}
          onDragEnd={handleDragEnd}
          onDragCancel={handleDragCancel}
        >
          <SortableContext items={getAllComponentIds()} strategy={rectSwappingStrategy}>
            {rows.map((row) => (
              <div
                key={row.id}
                style={{
                  display: 'flex',
                  width: '100%',
                  marginBottom: '8px',
                }}
              >
                {row.components.map((component) => (
                  <SortableItem
                    key={component.id}
                    id={component.id}
                    width={getComponentWidth(component.id)}
                    onDelete={handleDeleteComponent}
                  >
                    {component.content}
                  </SortableItem>
                ))}
              </div>
            ))}
            {/* 添加一个空白区域，用于拖放到新行 */}
            <div
              style={{
                width: '100%',
                height: '120px',
                display: 'flex',
                justifyContent: 'center',
                alignItems: 'center',
                border: '1px dashed #eee',
                borderRadius: '4px',
                color: '#ccc',
                marginTop: rows.length > 0 ? '8px' : 0,
              }}
            >
              拖放组件到此处创建新行
            </div>
          </SortableContext>

          <DragOverlay>
            {activeId ? (
              <div
                style={{
                  width: getComponentWidth(activeId),
                  height: '100px',
                  backgroundColor: 'rgba(0, 0, 0, 0.1)',
                  border: '2px dashed #ccc',
                  display: 'flex',
                  justifyContent: 'center',
                  alignItems: 'center',
                }}
              >
                {rows.flatMap((row) => row.components).find((c) => c.id === activeId)?.content}
              </div>
            ) : null}
          </DragOverlay>
        </DndContext>
      </div>
    );
  },
);

export default HorizontalDndLayout;
