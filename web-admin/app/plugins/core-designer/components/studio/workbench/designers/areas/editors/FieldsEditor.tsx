/**
 * Fields Editor
 *
 * Editor for configuring block fields.
 * Supports drag-and-drop reordering and field property editing.
 * Also supports dropping fields from FieldLibrary.
 */

import React, { useState, useCallback } from 'react';
import { useDroppable } from '@dnd-kit/core';
import type { DslFieldRef, DslFieldOverride } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { parseFieldShorthand, resolveLocalizedText } from '~/plugins/core-designer/components/studio/domain/dsl/types';

export interface FieldsEditorProps {
  fields: DslFieldRef[];
  modelCode?: string;
  blockId?: string;
  onChange: (fields: DslFieldRef[]) => void;
  readonly?: boolean;
  showAdvanced?: boolean;
}

export const FieldsEditor: React.FC<FieldsEditorProps> = ({
  fields,
  modelCode,
  blockId,
  onChange,
  readonly,
  showAdvanced,
}) => {
  const [expandedField, setExpandedField] = useState<string | null>(null);
  const [newFieldCode, setNewFieldCode] = useState('');

  // Droppable for receiving fields from FieldLibrary
  const { setNodeRef, isOver } = useDroppable({
    id: blockId ? `fields-drop:${blockId}` : 'fields-drop:unknown',
    disabled: readonly || !blockId,
  });

  // Add new field
  const handleAddField = useCallback(() => {
    if (!newFieldCode.trim() || readonly) return;
    onChange([...fields, newFieldCode.trim()]);
    setNewFieldCode('');
  }, [fields, newFieldCode, onChange, readonly]);

  // Remove field
  const handleRemoveField = useCallback(
    (index: number) => {
      if (readonly) return;
      const newFields = [...fields];
      newFields.splice(index, 1);
      onChange(newFields);
    },
    [fields, onChange, readonly],
  );

  // Update field
  const handleUpdateField = useCallback(
    (index: number, updates: Partial<DslFieldOverride>) => {
      if (readonly) return;
      const newFields = [...fields];
      const existing = parseFieldShorthand(fields[index]);
      newFields[index] = { ...existing, ...updates };
      onChange(newFields);
    },
    [fields, onChange, readonly],
  );

  // Move field
  const handleMoveField = useCallback(
    (fromIndex: number, toIndex: number) => {
      if (readonly || fromIndex === toIndex) return;
      const newFields = [...fields];
      const [moved] = newFields.splice(fromIndex, 1);
      newFields.splice(toIndex, 0, moved);
      onChange(newFields);
    },
    [fields, onChange, readonly],
  );

  return (
    <div
      ref={setNodeRef}
      className={`space-y-3 rounded-md transition-colors ${
        isOver ? 'bg-blue-50 ring-2 ring-blue-300 ring-inset' : ''
      }`}
    >
      {/* Field list */}
      {fields.length === 0 ? (
        <div
          className={`rounded border border-dashed py-4 text-center text-sm transition-colors ${
            isOver ? 'border-blue-400 bg-blue-50 text-blue-600' : 'border-gray-200 text-gray-400'
          }`}
        >
          {isOver ? '松开以添加字段' : '拖入字段或手动添加'}
        </div>
      ) : (
        <div className="space-y-2">
          {fields.map((fieldRef, index) => {
            const field = parseFieldShorthand(fieldRef);
            const isExpanded = expandedField === field.field;

            return (
              <FieldItem
                key={`${field.field}-${index}`}
                field={field}
                index={index}
                isExpanded={isExpanded}
                onToggle={() => setExpandedField(isExpanded ? null : field.field)}
                onRemove={() => handleRemoveField(index)}
                onUpdate={(updates) => handleUpdateField(index, updates)}
                onMoveUp={() => handleMoveField(index, index - 1)}
                onMoveDown={() => handleMoveField(index, index + 1)}
                canMoveUp={index > 0}
                canMoveDown={index < fields.length - 1}
                showAdvanced={showAdvanced}
                readonly={readonly}
              />
            );
          })}
        </div>
      )}

      {/* Add field input */}
      {!readonly && (
        <div className="flex items-center gap-2">
          <input
            type="text"
            value={newFieldCode}
            onChange={(e) => setNewFieldCode(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleAddField()}
            className="flex-1 rounded-md border border-gray-200 px-3 py-2 text-sm focus:border-transparent focus:ring-2 focus:ring-blue-500"
            placeholder="输入字段代码"
          />
          <button
            onClick={handleAddField}
            disabled={!newFieldCode.trim()}
            className="rounded-md bg-blue-500 px-3 py-2 text-sm text-white transition-colors hover:bg-blue-600 disabled:cursor-not-allowed disabled:bg-gray-300"
          >
            添加
          </button>
        </div>
      )}
    </div>
  );
};

/**
 * Single field item
 */
interface FieldItemProps {
  field: DslFieldOverride;
  index: number;
  isExpanded: boolean;
  onToggle: () => void;
  onRemove: () => void;
  onUpdate: (updates: Partial<DslFieldOverride>) => void;
  onMoveUp: () => void;
  onMoveDown: () => void;
  canMoveUp: boolean;
  canMoveDown: boolean;
  showAdvanced?: boolean;
  readonly?: boolean;
}

const FieldItem: React.FC<FieldItemProps> = ({
  field,
  index,
  isExpanded,
  onToggle,
  onRemove,
  onUpdate,
  onMoveUp,
  onMoveDown,
  canMoveUp,
  canMoveDown,
  showAdvanced,
  readonly,
}) => {
  return (
    <div
      className="overflow-hidden rounded-md border border-gray-200"
      data-testid={`field-item-${field.field}`}
    >
      {/* Field header */}
      <div
        className="flex cursor-pointer items-center gap-2 bg-gray-50 px-3 py-2 hover:bg-gray-100"
        data-testid={`field-item-header-${field.field}`}
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

        {/* Field name */}
        <span className="flex-1 text-sm font-medium text-gray-700">{field.field}</span>

        {/* Badges */}
        {field.required && (
          <span className="rounded bg-red-50 px-1.5 py-0.5 text-[10px] text-red-600">必填</span>
        )}
        {field.span && (
          <span className="rounded bg-blue-50 px-1.5 py-0.5 text-[10px] text-blue-600">
            span:{field.span}
          </span>
        )}
        {showAdvanced && field.advanced && (
          <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] text-gray-600">高级</span>
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
          {/* Span — covers the full 1..12 grid range used by reference forms.
              Reference DSL uses span values 4 / 6 / 12 across 36+ fields, so
              limiting the dropdown to 1-4 prevented the designer from
              expressing those layouts (Task C diff baseline). */}
          <div>
            <label className="mb-1 block text-xs text-gray-500">栅格宽度</label>
            <select
              value={field.span || ''}
              onChange={(e) =>
                onUpdate({ span: e.target.value ? Number(e.target.value) : undefined })
              }
              disabled={readonly}
              className="w-full rounded border border-gray-200 px-2 py-1.5 text-sm"
              data-testid={`field-item-span-${field.field}`}
            >
              <option value="">默认</option>
              {[1, 2, 3, 4, 6, 8, 12].map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </select>
          </div>

          {/* Required */}
          <div className="flex items-center justify-between">
            <label className="text-xs text-gray-500">必填</label>
            <input
              type="checkbox"
              checked={field.required || false}
              onChange={(e) => onUpdate({ required: e.target.checked })}
              disabled={readonly}
              className="rounded border-gray-300"
              data-testid={`field-item-required-${field.field}`}
            />
          </div>

          {/* Readonly (matches reference DSL `readonly` field) */}
          <div className="flex items-center justify-between">
            <label className="text-xs text-gray-500">只读</label>
            <input
              type="checkbox"
              checked={field.readonly || false}
              onChange={(e) => onUpdate({ readonly: e.target.checked })}
              disabled={readonly}
              className="rounded border-gray-300"
              data-testid={`field-item-readonly-${field.field}`}
            />
          </div>

          {/* Placeholder */}
          <div>
            <label className="mb-1 block text-xs text-gray-500">占位文本</label>
            <input
              type="text"
              value={resolveLocalizedText(field.placeholder) || ''}
              onChange={(e) => onUpdate({ placeholder: e.target.value || undefined })}
              disabled={readonly}
              className="w-full rounded border border-gray-200 px-2 py-1.5 text-sm"
              placeholder="请输入..."
            />
          </div>

          {/* Visible */}
          <div>
            <label className="mb-1 block text-xs text-gray-500">显示条件</label>
            <input
              type="text"
              value={field.visible || ''}
              onChange={(e) => onUpdate({ visible: e.target.value || undefined })}
              disabled={readonly}
              className="w-full rounded border border-gray-200 px-2 py-1.5 font-mono text-sm text-xs"
              placeholder="{{ true }}"
              data-testid={`field-item-visible-${field.field}`}
            />
          </div>

          {/* Disabled */}
          <div>
            <label className="mb-1 block text-xs text-gray-500">禁用条件</label>
            <input
              type="text"
              value={field.disabled || ''}
              onChange={(e) => onUpdate({ disabled: e.target.value || undefined })}
              disabled={readonly}
              className="w-full rounded border border-gray-200 px-2 py-1.5 font-mono text-sm text-xs"
              placeholder="{{ false }}"
            />
          </div>

          {/* Advanced (for filters) */}
          {showAdvanced && (
            <div className="flex items-center justify-between">
              <label className="text-xs text-gray-500">高级筛选</label>
              <input
                type="checkbox"
                checked={field.advanced || false}
                onChange={(e) => onUpdate({ advanced: e.target.checked })}
                disabled={readonly}
                className="rounded border-gray-300"
              />
            </div>
          )}

          {/* Remove button */}
          {!readonly && (
            <button
              onClick={onRemove}
              className="w-full rounded bg-red-50 px-3 py-1.5 text-xs text-red-600 transition-colors hover:bg-red-100"
            >
              移除字段
            </button>
          )}
        </div>
      )}
    </div>
  );
};

export default FieldsEditor;
