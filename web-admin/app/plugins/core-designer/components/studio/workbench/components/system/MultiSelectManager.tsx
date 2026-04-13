import React, { useState, useEffect, useCallback, useRef } from 'react';
import { getCommandManager, eventDomainManager } from '~/plugins/core-designer/components/studio/services/managers';
import { useDesignerStore } from '~/plugins/core-designer/components/studio/hooks/store/useDesignerStore';
import {
  BatchRemoveComponentsCommand,
  UpdateComponentPropsCommand,
} from '~/plugins/core-designer/components/studio/services/actions/command/DesignerCommands';

export interface SelectionArea {
  startX: number;
  startY: number;
  endX: number;
  endY: number;
}

export interface MultiSelectManagerProps {
  containerRef: React.RefObject<HTMLElement>;
  selectedIds: string[];
  onSelectionChange: (selectedIds: string[]) => void;
  enableBoxSelect?: boolean;
  enableMultiSelect?: boolean;
  children: React.ReactNode;
}

export const MultiSelectManager: React.FC<MultiSelectManagerProps> = ({
  containerRef,
  selectedIds,
  onSelectionChange,
  enableBoxSelect = true,
  enableMultiSelect = true,
  children,
}) => {
  const [isBoxSelecting, setIsBoxSelecting] = useState(false);
  const [selectionArea, setSelectionArea] = useState<SelectionArea | null>(null);
  const [dragStartPos, setDragStartPos] = useState<{ x: number; y: number } | null>(null);

  const selectionBoxRef = useRef<HTMLDivElement>(null);
  const multiSelectRef = useRef<HTMLDivElement>(null);

  const { selectComponent } = useDesignerStore();

  const handleMouseDown = useCallback(
    (event: React.MouseEvent) => {
      if (!enableBoxSelect || event.button !== 0) return;

      const target = event.target as HTMLElement;
      const isInputElement =
        target &&
        (target.tagName === 'input' ||
          target.tagName === 'textarea' ||
          target.tagName === 'select' ||
          target.isContentEditable);

      if (isInputElement) {
        return;
      }

      const componentElement = target.closest('[data-component-id]');
      if (componentElement) {
        const componentId = componentElement.getAttribute('data-component-id');
        if (componentId) {
          handleComponentClick(componentId, event.ctrlKey || event.metaKey);
        }
        return;
      }

      const rect = containerRef.current?.getBoundingClientRect();
      if (!rect) return;

      const startX = event.clientX - rect.left;
      const startY = event.clientY - rect.top;

      setDragStartPos({ x: startX, y: startY });
      setIsBoxSelecting(true);
      setSelectionArea({
        startX,
        startY,
        endX: startX,
        endY: startY,
      });

      event.preventDefault();
    },
    [enableBoxSelect, containerRef],
  );

  const handleMouseMove = useCallback(
    (event: MouseEvent) => {
      if (!isBoxSelecting || !dragStartPos) return;

      const rect = containerRef.current?.getBoundingClientRect();
      if (!rect) return;

      const currentX = event.clientX - rect.left;
      const currentY = event.clientY - rect.top;

      setSelectionArea({
        startX: Math.min(dragStartPos.x, currentX),
        startY: Math.min(dragStartPos.y, currentY),
        endX: Math.max(dragStartPos.x, currentX),
        endY: Math.max(dragStartPos.y, currentY),
      });
    },
    [isBoxSelecting, dragStartPos, containerRef],
  );

  const handleMouseUp = useCallback(() => {
    if (!isBoxSelecting || !selectionArea) return;
    const selectedComponents = findComponentsInArea(selectionArea);
    onSelectionChange(selectedComponents);
    setIsBoxSelecting(false);
    setSelectionArea(null);
    setDragStartPos(null);
  }, [isBoxSelecting, selectionArea, onSelectionChange]);

  const handleComponentClick = useCallback(
    (componentId: string, isMultiSelect: boolean) => {
      if (!enableMultiSelect || !isMultiSelect) {
        onSelectionChange([componentId]);
        selectComponent(componentId);
        eventDomainManager.dispatchEvent({
          eventType: 'component:selected',
          targetDomain: 'canvas',
          data: { componentId, isMultiSelect: false },
        });
      } else {
        if (selectedIds.includes(componentId)) {
          onSelectionChange(selectedIds.filter((id) => id !== componentId));
          if (selectedIds.length === 1) {
            selectComponent(null);
          }
          eventDomainManager.dispatchEvent({
            eventType: 'component:deselected',
            targetDomain: 'canvas',
            data: { componentId },
          });
        } else {
          onSelectionChange([...selectedIds, componentId]);
          selectComponent(componentId);
          eventDomainManager.dispatchEvent({
            eventType: 'component:selected',
            targetDomain: 'canvas',
            data: { componentId, isMultiSelect: true },
          });
        }
      }
    },
    [enableMultiSelect, onSelectionChange, selectComponent, selectedIds],
  );

  const findComponentsInArea = useCallback(
    (area: SelectionArea): string[] => {
      if (!containerRef.current) return [];

      const components = containerRef.current.querySelectorAll('[data-component-id]');
      const selectedComponents: string[] = [];

      components.forEach((element) => {
        const rect = element.getBoundingClientRect();
        const containerRect = containerRef.current!.getBoundingClientRect();

        const elementX = rect.left - containerRect.left;
        const elementY = rect.top - containerRect.top;
        const elementRight = elementX + rect.width;
        const elementBottom = elementY + rect.height;

        if (
          elementX < area.endX &&
          elementRight > area.startX &&
          elementY < area.endY &&
          elementBottom > area.startY
        ) {
          const componentId = element.getAttribute('data-component-id');
          if (componentId) {
            selectedComponents.push(componentId);
          }
        }
      });

      return selectedComponents;
    },
    [containerRef],
  );

  useEffect(() => {
    if (isBoxSelecting && multiSelectRef.current) {
      const element = multiSelectRef.current;
      element.addEventListener('mousemove', handleMouseMove);
      element.addEventListener('mouseup', handleMouseUp);

      return () => {
        element.removeEventListener('mousemove', handleMouseMove);
        element.removeEventListener('mouseup', handleMouseUp);
      };
    }
  }, [isBoxSelecting, handleMouseMove, handleMouseUp]);

  const handleBatchDelete = useCallback(async () => {
    if (selectedIds.length === 0) return;
    if (confirm(`确定要删除选中的 ${selectedIds.length} 个组件吗？`)) {
      const command = new BatchRemoveComponentsCommand(selectedIds);
      await getCommandManager().executeCommand(command);
      onSelectionChange([]);
    }
  }, [selectedIds, onSelectionChange]);

  const handleSelectAll = useCallback(() => {
    if (!containerRef.current) return;
    const components = containerRef.current.querySelectorAll('[data-component-id]');
    const allIds = Array.from(components)
      .map((element) => element.getAttribute('data-component-id'))
      .filter(Boolean) as string[];

    onSelectionChange(allIds);
    eventDomainManager.dispatchEvent({
      eventType: 'canvas:selection-changed',
      targetDomain: 'canvas',
      data: { selectedIds: allIds },
    });
  }, [containerRef, onSelectionChange]);

  useEffect(() => {
    const multiSelectDomain = {
      name: 'multi-select',
      element: multiSelectRef.current,
      isActive: true,
      handlers: {
        keydown: (event: KeyboardEvent) => {
          const target = event.target as HTMLElement;
          const isInputElement =
            target &&
            (target.tagName === 'input' ||
              target.tagName === 'textarea' ||
              target.tagName === 'select' ||
              target.isContentEditable ||
              target.closest('.property-editor'));

          if (isInputElement) {
            return;
          }

          if (event.key === 'Escape') {
            onSelectionChange([]);
            eventDomainManager.dispatchEvent({
              eventType: 'canvas:selection-cleared',
              targetDomain: 'canvas',
              data: { selectedIds: [] },
            });
          }

          if (event.key === 'Delete' && selectedIds.length > 0) {
            handleBatchDelete();
          }

          if (event.ctrlKey && event.key === 'a') {
            event.preventDefault();
            handleSelectAll();
          }
        },
      },
    };

    eventDomainManager.registerDomain(multiSelectDomain);

    return () => {
      eventDomainManager.unregisterDomain('multi-select');
    };
  }, [selectedIds, onSelectionChange, handleBatchDelete, handleSelectAll]);

  return (
    <div
      ref={multiSelectRef}
      className="relative h-full w-full"
      onMouseDown={handleMouseDown}
      data-domain="multi-select"
    >
      {children}

      {isBoxSelecting && selectionArea && (
        <div
          ref={selectionBoxRef}
          className="pointer-events-none absolute z-50 border-2 border-blue-500 bg-blue-100/20"
          style={{
            left: selectionArea.startX,
            top: selectionArea.startY,
            width: selectionArea.endX - selectionArea.startX,
            height: selectionArea.endY - selectionArea.startY,
          }}
        />
      )}
    </div>
  );
};

export interface BatchOperationToolbarProps {
  selectedIds: string[];
  onClearSelection: () => void;
  className?: string;
}

export const BatchOperationToolbar: React.FC<BatchOperationToolbarProps> = ({
  selectedIds,
  onClearSelection,
  className = '',
}) => {
  const [showStylePanel, setShowStylePanel] = useState(false);

  const handleBatchDelete = useCallback(async () => {
    if (selectedIds.length === 0) return;

    if (confirm(`确定要删除选中的 ${selectedIds.length} 个组件吗？`)) {
      const command = new BatchRemoveComponentsCommand(selectedIds);
      await getCommandManager().executeCommand(command);
      onClearSelection();
      eventDomainManager.dispatchEvent({
        eventType: 'component:deleted',
        targetDomain: 'canvas',
        data: { componentIds: selectedIds },
      });
    }
  }, [selectedIds, onClearSelection]);

  const handleBatchSetStyle = useCallback(
    async (styles: Record<string, any>) => {
      if (selectedIds.length === 0) return;
      const commandManager = getCommandManager();
      commandManager.startBatch(`batch_style_${Date.now()}`);

      try {
        for (const componentId of selectedIds) {
          const command = new UpdateComponentPropsCommand(componentId, { styles });
          await commandManager.executeCommand(command);
        }
      } finally {
        commandManager.endBatch();
      }

      eventDomainManager.dispatchEvent({
        eventType: 'property:updated',
        targetDomain: 'canvas',
        data: { componentIds: selectedIds, styles },
      });
    },
    [selectedIds],
  );

  return (
    <div
      className={`fixed bottom-6 left-1/2 flex -translate-x-1/2 items-center gap-3 rounded-xl border border-gray-200 bg-white px-4 py-2 shadow-xl ${className}`}
    >
      <div className="text-sm text-gray-600">已选择 {selectedIds.length} 个组件</div>

      <button
        onClick={handleBatchDelete}
        className="rounded-lg bg-red-50 px-3 py-1.5 text-sm text-red-600 transition-colors hover:bg-red-100"
      >
        批量删除
      </button>

      <button
        onClick={() => setShowStylePanel((prev) => !prev)}
        className="rounded-lg bg-gray-100 px-3 py-1.5 text-sm text-gray-700 transition-colors hover:bg-gray-200"
      >
        批量样式
      </button>

      <button
        onClick={onClearSelection}
        className="rounded-lg bg-gray-100 px-3 py-1.5 text-sm text-gray-700 transition-colors hover:bg-gray-200"
      >
        清除选择
      </button>

      {showStylePanel && (
        <div className="absolute bottom-14 left-1/2 min-w-[260px] -translate-x-1/2 rounded-lg border border-gray-200 bg-white p-4 shadow-lg">
          <div className="mb-3 text-sm font-medium text-gray-900">应用样式</div>
          <div className="space-y-2">
            <button
              className="w-full rounded px-3 py-2 text-left text-sm hover:bg-gray-100"
              onClick={() => handleBatchSetStyle({ borderRadius: '12px' })}
            >
              圆角卡片
            </button>
            <button
              className="w-full rounded px-3 py-2 text-left text-sm hover:bg-gray-100"
              onClick={() => handleBatchSetStyle({ boxShadow: '0 4px 12px rgba(0,0,0,0.15)' })}
            >
              阴影卡片
            </button>
            <button
              className="w-full rounded px-3 py-2 text-left text-sm hover:bg-gray-100"
              onClick={() =>
                handleBatchSetStyle({
                  backgroundColor: '#1d4ed8',
                  color: '#ffffff',
                  borderRadius: '9999px',
                })
              }
            >
              主要按钮
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default MultiSelectManager;
