/**
 * Field List Manager Component
 * Manages model fields with drag-and-drop sorting, configuration, and unbinding
 */

import React, { useState, useEffect } from 'react';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
  useSortable,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import type { ModelFieldBinding } from '~/types/model';
import { FieldSelectionDialog } from './FieldSelectionDialog';

interface FieldListManagerProps {
  fields: ModelFieldBinding[];
  modelPid: string;
  modelCode: string;
  onFieldsReorder: (fields: ModelFieldBinding[]) => Promise<void>;
  onFieldConfigure: (field: ModelFieldBinding) => void;
  onFieldUnbind: (field: ModelFieldBinding) => Promise<void>;
  onFieldBound: () => void;
  onDictConfig?: (field: ModelFieldBinding) => void;
}

interface SortableFieldItemProps {
  field: ModelFieldBinding;
  onConfigure: () => void;
  onUnbind: () => void;
  onDictConfig?: () => void;
}

/**
 * Sortable Field Item Component
 */
function SortableFieldItem({ field, onConfigure, onUnbind, onDictConfig }: SortableFieldItemProps) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: field.id,
  });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  };

  return (
    <tr
      ref={setNodeRef}
      style={style}
      className={`${isDragging ? 'bg-accent-weak' : 'bg-panel'} hover:bg-subtle`}
    >
      {/* Drag Handle */}
      <td className="px-4 py-4 whitespace-nowrap">
        <button
          {...attributes}
          {...listeners}
          className="text-text-3 hover:text-text-2 cursor-move"
          title="拖动排序"
        >
          <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M4 8h16M4 16h16"
            />
          </svg>
        </button>
      </td>

      {/* Field Code */}
      <td className="text-text px-6 py-4 font-mono text-sm whitespace-nowrap">
        {field.code || field.fieldCode}
      </td>

      {/* Data Type */}
      <td className="text-text px-6 py-4 text-sm whitespace-nowrap">{field.dataType}</td>

      {/* Required */}
      <td className="text-text px-6 py-4 text-sm whitespace-nowrap">
        {field.required ? (
          <span className="rounded-pill inline-flex bg-red-100 px-2 py-1 text-xs font-medium text-red-800">
            必填
          </span>
        ) : (
          <span className="text-text-3">-</span>
        )}
      </td>

      {/* Readonly */}
      <td className="text-text px-6 py-4 text-sm whitespace-nowrap">
        {field.readonly ? (
          <span className="rounded-pill bg-subtle text-text inline-flex px-2 py-1 text-xs font-medium">
            只读
          </span>
        ) : (
          <span className="text-text-3">-</span>
        )}
      </td>

      {/* Visible */}
      <td className="text-text px-6 py-4 text-sm whitespace-nowrap">
        {field.visible !== false ? (
          <span className="rounded-pill inline-flex bg-green-100 px-2 py-1 text-xs font-medium text-green-800">
            显示
          </span>
        ) : (
          <span className="rounded-pill bg-subtle text-text inline-flex px-2 py-1 text-xs font-medium">
            隐藏
          </span>
        )}
      </td>

      {/* Dictionary */}
      <td className="px-6 py-4 text-sm whitespace-nowrap">
        {field.dictCode ? (
          <div className="flex items-center gap-2">
            <span className="text-text font-mono">{field.dictCode}</span>
            {onDictConfig && (
              <button
                onClick={onDictConfig}
                className="text-accent hover:text-blue-800"
                title="配置字典"
              >
                <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"
                  />
                </svg>
              </button>
            )}
          </div>
        ) : (
          <div className="flex items-center gap-2">
            <span className="text-text-3">-</span>
            {onDictConfig && (
              <button
                onClick={onDictConfig}
                className="text-accent text-xs hover:text-blue-800"
                title="配置字典"
              >
                配置
              </button>
            )}
          </div>
        )}
      </td>

      {/* Display Order */}
      <td className="text-text-2 px-6 py-4 text-sm whitespace-nowrap">{field.displayOrder}</td>

      {/* Actions */}
      <td className="px-6 py-4 text-sm whitespace-nowrap">
        <button onClick={onConfigure} className="text-accent mr-3 hover:text-blue-900">
          配置
        </button>
        <button onClick={onUnbind} className="text-status-red hover:text-red-900">
          移除
        </button>
      </td>
    </tr>
  );
}

/**
 * Field List Manager Component
 */
export function FieldListManager({
  fields,
  modelPid,
  modelCode,
  onFieldsReorder,
  onFieldConfigure,
  onFieldUnbind,
  onFieldBound,
  onDictConfig,
}: FieldListManagerProps) {
  const [localFields, setLocalFields] = useState(fields);
  const [isReordering, setIsReordering] = useState(false);
  const [isDialogOpen, setIsDialogOpen] = useState(false);

  // Sync local state when parent fields prop changes (e.g., after binding new field)
  useEffect(() => {
    setLocalFields(fields);
  }, [fields]);

  const sensors = useSensors(
    useSensor(PointerSensor),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    }),
  );

  const handleOpenDialog = () => {
    setIsDialogOpen(true);
  };

  const handleCloseDialog = () => {
    setIsDialogOpen(false);
  };

  const handleFieldBound = () => {
    onFieldBound();
  };

  const handleDragEnd = async (event: DragEndEvent) => {
    const { active, over } = event;

    if (over && active.id !== over.id) {
      const oldIndex = localFields.findIndex((f) => f.id === active.id);
      const newIndex = localFields.findIndex((f) => f.id === over.id);

      const reorderedFields = arrayMove(localFields, oldIndex, newIndex);

      // Update display order
      const updatedFields = reorderedFields.map((field, index) => ({
        ...field,
        displayOrder: index + 1,
      }));

      setLocalFields(updatedFields);
      setIsReordering(true);

      try {
        await onFieldsReorder(updatedFields);
      } catch (error) {
        // Revert on error
        setLocalFields(fields);
        console.error('Failed to reorder fields:', error);
      } finally {
        setIsReordering(false);
      }
    }
  };

  if (localFields.length === 0) {
    return (
      <>
        <div className="py-12 text-center">
          <svg
            className="text-text-3 mx-auto h-12 w-12"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
            />
          </svg>
          <h3 className="text-text mt-2 text-sm font-medium">暂无字段</h3>
          <p className="text-text-2 mt-1 text-sm">开始为模型添加字段</p>
          <div className="mt-6">
            <button
              data-testid="model-fields-add-button"
              onClick={handleOpenDialog}
              className="rounded-control bg-accent hover:bg-accent-hover focus-visible:shadow-focus inline-flex items-center border border-transparent px-4 py-2 text-sm font-medium text-white shadow-sm focus:outline-none"
            >
              <svg
                className="mr-2 -ml-1 h-5 w-5"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 4v16m8-8H4"
                />
              </svg>
              添加字段
            </button>
          </div>
        </div>

        <FieldSelectionDialog
          isOpen={isDialogOpen}
          modelPid={modelPid}
          modelCode={modelCode}
          onClose={handleCloseDialog}
          onFieldBound={handleFieldBound}
        />
      </>
    );
  }

  return (
    <>
      <div>
        {/* Header */}
        <div className="mb-4 flex items-center justify-between">
          <div>
            <h3 className="text-text text-lg font-medium">字段列表</h3>
            <p className="text-text-2 mt-1 text-sm">拖动字段可调整显示顺序</p>
          </div>
          <button
            data-testid="model-fields-add-button"
            onClick={handleOpenDialog}
            className="rounded-control bg-accent hover:bg-accent-hover focus-visible:shadow-focus inline-flex items-center border border-transparent px-4 py-2 text-sm font-medium text-white shadow-sm focus:outline-none"
          >
            <svg
              className="mr-2 -ml-1 h-5 w-5"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 4v16m8-8H4"
              />
            </svg>
            添加字段
          </button>
        </div>

        {/* Loading Indicator */}
        {isReordering && (
          <div className="rounded-control bg-accent-weak mb-4 border border-blue-200 p-3">
            <div className="flex items-center">
              <div className="rounded-pill border-accent mr-3 h-4 w-4 animate-spin border-b-2"></div>
              <span className="text-sm text-blue-800">正在保存排序...</span>
            </div>
          </div>
        )}

        {/* Field Table */}
        <div className="ring-opacity-5 rounded-card overflow-x-auto shadow ring-1 ring-black">
          <DndContext
            sensors={sensors}
            collisionDetection={closestCenter}
            onDragEnd={handleDragEnd}
          >
            <table className="divide-border min-w-full divide-y">
              <thead className="bg-subtle">
                <tr>
                  <th className="text-text-2 px-4 py-3 text-left text-xs font-medium tracking-wider uppercase">
                    排序
                  </th>
                  <th className="text-text-2 px-6 py-3 text-left text-xs font-medium tracking-wider uppercase">
                    字段编码
                  </th>
                  <th className="text-text-2 px-6 py-3 text-left text-xs font-medium tracking-wider uppercase">
                    数据类型
                  </th>
                  <th className="text-text-2 px-6 py-3 text-left text-xs font-medium tracking-wider uppercase">
                    必填
                  </th>
                  <th className="text-text-2 px-6 py-3 text-left text-xs font-medium tracking-wider uppercase">
                    只读
                  </th>
                  <th className="text-text-2 px-6 py-3 text-left text-xs font-medium tracking-wider uppercase">
                    可见
                  </th>
                  <th className="text-text-2 px-6 py-3 text-left text-xs font-medium tracking-wider uppercase">
                    字典
                  </th>
                  <th className="text-text-2 px-6 py-3 text-left text-xs font-medium tracking-wider uppercase">
                    顺序
                  </th>
                  <th className="text-text-2 px-6 py-3 text-left text-xs font-medium tracking-wider uppercase">
                    操作
                  </th>
                </tr>
              </thead>
              <tbody className="divide-border bg-panel divide-y">
                <SortableContext
                  items={localFields.map((f) => f.id)}
                  strategy={verticalListSortingStrategy}
                >
                  {localFields.map((field) => (
                    <SortableFieldItem
                      key={field.id}
                      field={field}
                      onConfigure={() => onFieldConfigure(field)}
                      onUnbind={() => onFieldUnbind(field)}
                      onDictConfig={onDictConfig ? () => onDictConfig(field) : undefined}
                    />
                  ))}
                </SortableContext>
              </tbody>
            </table>
          </DndContext>
        </div>

        <FieldSelectionDialog
          isOpen={isDialogOpen}
          modelPid={modelPid}
          modelCode={modelCode}
          onClose={handleCloseDialog}
          onFieldBound={handleFieldBound}
        />
      </div>
    </>
  );
}
