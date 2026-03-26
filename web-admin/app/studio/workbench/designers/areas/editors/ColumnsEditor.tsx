/**
 * Columns Editor
 *
 * Editor for configuring data-table columns.
 * Also supports dropping fields from FieldLibrary.
 */

import React, { useState, useCallback } from 'react';
import { useDroppable } from '@dnd-kit/core';
import type { DslColumnRef, DslColumnConfig } from '~/studio/domain/dsl/types';
import { parseColumnShorthand } from '~/studio/domain/dsl/types';
import { ActionButtonEditor } from './ActionButtonEditor';

export interface ColumnsEditorProps {
  columns: DslColumnRef[];
  modelCode?: string;
  blockId?: string;
  onChange: (columns: DslColumnRef[]) => void;
  readonly?: boolean;
}

export const ColumnsEditor: React.FC<ColumnsEditorProps> = ({
  columns,
  modelCode,
  blockId,
  onChange,
  readonly,
}) => {
  const [expandedColumn, setExpandedColumn] = useState<string | null>(null);
  const [newColumnCode, setNewColumnCode] = useState('');

  // Droppable for receiving fields from FieldLibrary
  const { setNodeRef, isOver } = useDroppable({
    id: blockId ? `columns-drop:${blockId}` : 'columns-drop:unknown',
    disabled: readonly || !blockId,
  });

  // Add new column
  const handleAddColumn = useCallback(() => {
    if (!newColumnCode.trim() || readonly) return;
    onChange([...columns, newColumnCode.trim()]);
    setNewColumnCode('');
  }, [columns, newColumnCode, onChange, readonly]);

  // Add actions column
  const handleAddActionsColumn = useCallback(() => {
    if (readonly) return;
    const hasActions = columns.some((col) => {
      const parsed = parseColumnShorthand(col);
      return parsed.field === '$actions';
    });
    if (hasActions) return;

    onChange([
      ...columns,
      {
        field: '$actions',
        width: 160,
        fixed: 'right',
        actions: [{ action: 'view' }, { action: 'edit' }, { action: 'delete' }],
      },
    ]);
  }, [columns, onChange, readonly]);

  // Remove column
  const handleRemoveColumn = useCallback(
    (index: number) => {
      if (readonly) return;
      const newColumns = [...columns];
      newColumns.splice(index, 1);
      onChange(newColumns);
    },
    [columns, onChange, readonly],
  );

  // Update column
  const handleUpdateColumn = useCallback(
    (index: number, updates: Partial<DslColumnConfig>) => {
      if (readonly) return;
      const newColumns = [...columns];
      const existing = parseColumnShorthand(columns[index]);
      newColumns[index] = { ...existing, ...updates };
      onChange(newColumns);
    },
    [columns, onChange, readonly],
  );

  // Move column
  const handleMoveColumn = useCallback(
    (fromIndex: number, toIndex: number) => {
      if (readonly || fromIndex === toIndex) return;
      const newColumns = [...columns];
      const [moved] = newColumns.splice(fromIndex, 1);
      newColumns.splice(toIndex, 0, moved);
      onChange(newColumns);
    },
    [columns, onChange, readonly],
  );

  return (
    <div
      ref={setNodeRef}
      className={`space-y-3 rounded-md transition-colors ${
        isOver ? 'bg-blue-50 ring-2 ring-blue-300 ring-inset' : ''
      }`}
    >
      {/* Column list */}
      {columns.length === 0 ? (
        <div
          className={`rounded border border-dashed py-4 text-center text-sm transition-colors ${
            isOver ? 'border-blue-400 bg-blue-50 text-blue-600' : 'border-gray-200 text-gray-400'
          }`}
        >
          {isOver ? '松开以添加列' : '拖入字段或手动添加'}
        </div>
      ) : (
        <div className="space-y-2">
          {columns.map((colRef, index) => {
            const column = parseColumnShorthand(colRef);
            const isExpanded = expandedColumn === column.field;

            return (
              <ColumnItem
                key={`${column.field}-${index}`}
                column={column}
                index={index}
                isExpanded={isExpanded}
                onToggle={() => setExpandedColumn(isExpanded ? null : column.field)}
                onRemove={() => handleRemoveColumn(index)}
                onUpdate={(updates) => handleUpdateColumn(index, updates)}
                onMoveUp={() => handleMoveColumn(index, index - 1)}
                onMoveDown={() => handleMoveColumn(index, index + 1)}
                canMoveUp={index > 0}
                canMoveDown={index < columns.length - 1}
                readonly={readonly}
              />
            );
          })}
        </div>
      )}

      {/* Add column */}
      {!readonly && (
        <div className="space-y-2">
          <div className="flex items-center gap-2">
            <input
              type="text"
              value={newColumnCode}
              onChange={(e) => setNewColumnCode(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleAddColumn()}
              className="flex-1 rounded-md border border-gray-200 px-3 py-2 text-sm focus:border-transparent focus:ring-2 focus:ring-blue-500"
              placeholder="输入字段代码"
            />
            <button
              onClick={handleAddColumn}
              disabled={!newColumnCode.trim()}
              className="rounded-md bg-blue-500 px-3 py-2 text-sm text-white transition-colors hover:bg-blue-600 disabled:cursor-not-allowed disabled:bg-gray-300"
            >
              添加
            </button>
          </div>
          <button
            onClick={handleAddActionsColumn}
            className="w-full rounded-md bg-blue-50 px-3 py-2 text-sm text-blue-600 transition-colors hover:bg-blue-100"
          >
            + 添加操作列
          </button>
        </div>
      )}
    </div>
  );
};

/**
 * Single column item
 */
interface ColumnItemProps {
  column: DslColumnConfig;
  index: number;
  isExpanded: boolean;
  onToggle: () => void;
  onRemove: () => void;
  onUpdate: (updates: Partial<DslColumnConfig>) => void;
  onMoveUp: () => void;
  onMoveDown: () => void;
  canMoveUp: boolean;
  canMoveDown: boolean;
  readonly?: boolean;
}

const ColumnItem: React.FC<ColumnItemProps> = ({
  column,
  index,
  isExpanded,
  onToggle,
  onRemove,
  onUpdate,
  onMoveUp,
  onMoveDown,
  canMoveUp,
  canMoveDown,
  readonly,
}) => {
  const isActionsColumn = column.field === '$actions';

  return (
    <div className="overflow-hidden rounded-md border border-gray-200">
      {/* Column header */}
      <div
        className={`flex cursor-pointer items-center gap-2 px-3 py-2 hover:bg-gray-100 ${
          isActionsColumn ? 'bg-purple-50' : 'bg-gray-50'
        }`}
        onClick={onToggle}
      >
        {/* Reorder buttons */}
        {!readonly && (
          <div className="-my-1 flex flex-col">
            <button
              onClick={(e) => {
                e.stopPropagation();
                onMoveUp();
              }}
              disabled={!canMoveUp}
              className="text-gray-400 hover:text-gray-600 disabled:opacity-30"
            >
              <svg className="h-3 w-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M5 15l7-7 7 7"
                />
              </svg>
            </button>
            <button
              onClick={(e) => {
                e.stopPropagation();
                onMoveDown();
              }}
              disabled={!canMoveDown}
              className="text-gray-400 hover:text-gray-600 disabled:opacity-30"
            >
              <svg className="h-3 w-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M19 9l-7 7-7-7"
                />
              </svg>
            </button>
          </div>
        )}

        {/* Column name */}
        <span
          className={`flex-1 text-sm font-medium ${isActionsColumn ? 'text-purple-700' : 'text-gray-700'}`}
        >
          {isActionsColumn ? '操作列' : column.field}
        </span>

        {/* Badges */}
        {column.sortable && (
          <span className="rounded bg-green-50 px-1.5 py-0.5 text-[10px] text-green-600">排序</span>
        )}
        {column.fixed && (
          <span className="rounded bg-blue-50 px-1.5 py-0.5 text-[10px] text-blue-600">
            固定:{column.fixed}
          </span>
        )}
        {column.width && (
          <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] text-gray-600">
            {column.width}px
          </span>
        )}

        {/* Expand icon */}
        <svg
          className={`h-4 w-4 text-gray-400 transition-transform ${isExpanded ? 'rotate-180' : ''}`}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </div>

      {/* Expanded content */}
      {isExpanded && (
        <div className="space-y-3 border-t border-gray-100 p-3">
          {/* Width */}
          <div>
            <label className="mb-1 block text-xs text-gray-500">列宽 (px)</label>
            <input
              type="number"
              value={column.width || ''}
              onChange={(e) =>
                onUpdate({ width: e.target.value ? Number(e.target.value) : undefined })
              }
              disabled={readonly}
              className="w-full rounded border border-gray-200 px-2 py-1.5 text-sm"
              placeholder="自动"
            />
          </div>

          {/* Action button editor for $actions column */}
          {isActionsColumn && (
            <ActionButtonEditor
              buttons={(column as any).actions || (column as any).buttons || []}
              onChange={(buttons) => onUpdate({ actions: buttons } as any)}
              readonly={readonly}
            />
          )}

          {!isActionsColumn && (
            <>
              {/* Sortable */}
              <div className="flex items-center justify-between">
                <label className="text-xs text-gray-500">可排序</label>
                <input
                  type="checkbox"
                  checked={column.sortable || false}
                  onChange={(e) => onUpdate({ sortable: e.target.checked })}
                  disabled={readonly}
                  className="rounded border-gray-300"
                />
              </div>

              {/* Copyable */}
              <div className="flex items-center justify-between">
                <label className="text-xs text-gray-500">可复制</label>
                <input
                  type="checkbox"
                  checked={column.copyable || false}
                  onChange={(e) => onUpdate({ copyable: e.target.checked })}
                  disabled={readonly}
                  className="rounded border-gray-300"
                />
              </div>

              {/* Ellipsis */}
              <div className="flex items-center justify-between">
                <label className="text-xs text-gray-500">文本省略</label>
                <input
                  type="checkbox"
                  checked={column.ellipsis || false}
                  onChange={(e) => onUpdate({ ellipsis: e.target.checked })}
                  disabled={readonly}
                  className="rounded border-gray-300"
                />
              </div>

              {/* Render type */}
              <div>
                <label className="mb-1 block text-xs text-gray-500">渲染类型</label>
                <select
                  value={column.render || ''}
                  onChange={(e) =>
                    onUpdate({
                      render: (e.target.value || undefined) as DslColumnConfig['render'],
                    })
                  }
                  disabled={readonly}
                  className="w-full rounded border border-gray-200 px-2 py-1.5 text-sm"
                >
                  <option value="">默认</option>
                  <option value="tag">标签</option>
                  <option value="datetime">日期时间</option>
                  <option value="currency">货币</option>
                  <option value="link">链接</option>
                  <option value="image">图片</option>
                </select>
              </div>
            </>
          )}

          {/* Fixed */}
          <div>
            <label className="mb-1 block text-xs text-gray-500">固定</label>
            <select
              value={column.fixed || ''}
              onChange={(e) =>
                onUpdate({
                  fixed: (e.target.value || undefined) as DslColumnConfig['fixed'],
                })
              }
              disabled={readonly}
              className="w-full rounded border border-gray-200 px-2 py-1.5 text-sm"
            >
              <option value="">不固定</option>
              <option value="left">左侧</option>
              <option value="right">右侧</option>
            </select>
          </div>

          {/* Remove button */}
          {!readonly && (
            <button
              onClick={onRemove}
              className="w-full rounded bg-red-50 px-3 py-1.5 text-xs text-red-600 transition-colors hover:bg-red-100"
            >
              移除列
            </button>
          )}
        </div>
      )}
    </div>
  );
};

export default ColumnsEditor;
