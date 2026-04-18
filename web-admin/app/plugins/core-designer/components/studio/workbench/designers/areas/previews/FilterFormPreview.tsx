/**
 * Filter Form Preview
 *
 * Preview component for filters blocks.
 * Supports drag-and-drop reordering of filter fields.
 */

import React, { useCallback } from 'react';
import type { DragEndEvent } from '@dnd-kit/core';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
} from '@dnd-kit/core';
import {
  SortableContext,
  sortableKeyboardCoordinates,
  rectSortingStrategy,
} from '@dnd-kit/sortable';
import type { DslBlock, DslFieldRef } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { parseFieldShorthand, resolveLocalizedText } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { SortableFilterField } from './SortableFilterField';

export interface FilterFormPreviewProps {
  block: DslBlock;
  selectedFieldIndex?: number;
  onFieldReorder?: (blockId: string, oldIndex: number, newIndex: number) => void;
  onFieldSelect?: (blockId: string, fieldIndex: number, fieldRef: DslFieldRef) => void;
  readonly?: boolean;
}

export const FilterFormPreview: React.FC<FilterFormPreviewProps> = ({
  block,
  selectedFieldIndex,
  onFieldReorder,
  onFieldSelect,
  readonly,
}) => {
  const fields = block.fields || [];
  const actions = block.actions || [];

  // Sensors for drag-and-drop
  const sensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: {
        distance: 8,
      },
    }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    }),
  );

  // Handle field reorder
  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;

      if (over && active.id !== over.id) {
        const oldIndex = fields.findIndex((f) => {
          const parsed = parseFieldShorthand(f);
          return `filter-${parsed.field}` === active.id;
        });
        const newIndex = fields.findIndex((f) => {
          const parsed = parseFieldShorthand(f);
          return `filter-${parsed.field}` === over.id;
        });

        if (oldIndex !== -1 && newIndex !== -1 && onFieldReorder) {
          onFieldReorder(block.id, oldIndex, newIndex);
        }
      }
    },
    [fields, block.id, onFieldReorder],
  );

  // Generate sortable IDs for fields
  const fieldIds = fields.slice(0, 8).map((fieldRef) => {
    const field = parseFieldShorthand(fieldRef);
    return `filter-${field.field}`;
  });

  return (
    <div className="bg-gray-50/50 p-4">
      {/* Filter fields grid with drag-and-drop */}
      <div className="mb-3">
        {fields.length === 0 ? (
          <div className="rounded border border-dashed border-gray-200 py-4 text-center text-sm text-gray-400">
            点击右侧面板添加筛选字段
          </div>
        ) : (
          <DndContext
            sensors={sensors}
            collisionDetection={closestCenter}
            onDragEnd={handleDragEnd}
          >
            <SortableContext items={fieldIds} strategy={rectSortingStrategy}>
              <div className="grid grid-cols-4 gap-3 pl-4">
                {fields.slice(0, 8).map((fieldRef, index) => {
                  const field = parseFieldShorthand(fieldRef);
                  const fieldId = `filter-${field.field}`;

                  return (
                    <SortableFilterField
                      key={fieldId}
                      id={fieldId}
                      fieldName={field.field || `字段${index + 1}`}
                      placeholder={resolveLocalizedText(field.placeholder) || undefined}
                      isAdvanced={field.advanced}
                      isSelected={selectedFieldIndex === index}
                      disabled={readonly}
                      onSelect={() => onFieldSelect?.(block.id, index, fieldRef)}
                    />
                  );
                })}
              </div>
            </SortableContext>
          </DndContext>
        )}
        {fields.length > 8 && (
          <div className="mt-2 text-center text-xs text-gray-400">
            +{fields.length - 8} 更多字段
          </div>
        )}
      </div>

      {/* Action buttons */}
      {actions.length > 0 && (
        <div className="flex justify-end gap-2">
          {actions.map((action) => (
            <button
              key={action}
              className={`rounded px-3 py-1.5 text-xs transition-colors ${
                action === 'search'
                  ? 'bg-blue-500 text-white'
                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}
            >
              {getActionLabel(action)}
            </button>
          ))}
        </div>
      )}
    </div>
  );
};

/**
 * Get display label for action
 */
function getActionLabel(action: string): string {
  const labels: Record<string, string> = {
    search: '查询',
    reset: '重置',
    export: '导出',
  };
  return labels[action] || action;
}

export default FilterFormPreview;
