/**
 * InlineEditCell — inline cell editing for table columns
 *
 * When column.editable is true, double-clicking a cell enters edit mode.
 * Blur or Enter saves the change via Command execution.
 *
 * Supports typed editing based on column metadata:
 * - dictCode → dropdown select with dict items
 * - valueType 'date'/'datetime' → date/datetime-local input
 * - valueType 'currency' or numeric fields → number input
 * - default → text input
 *
 * DSL config:
 * { "field": "title", "editable": true }
 * { "field": "status", "editable": true, "dictCode": "lead_status" }
 */

import React, { useState, useRef, useCallback, useEffect } from 'react';
import type { ColumnConfig } from '~/meta/schemas/types';

export interface DictItem {
  label: string;
  value: any;
}

export interface InlineEditCellProps {
  column: ColumnConfig;
  value: any;
  record: Record<string, any>;
  onSave: (field: string, value: any, record: Record<string, any>) => Promise<void>;
  /** Whether this cell is editable (pre-evaluated) */
  editable?: boolean;
  /** Dict items for select columns */
  dictItems?: DictItem[];
  children: React.ReactNode;
}

const inputClass =
  'w-full px-2 py-1 text-sm border border-blue-400 rounded focus:outline-none focus:ring-1 focus:ring-blue-500 bg-white';

export const InlineEditCell: React.FC<InlineEditCellProps> = ({
  column,
  value,
  record,
  onSave,
  editable = false,
  dictItems,
  children,
}) => {
  const [editing, setEditing] = useState(false);
  const [editValue, setEditValue] = useState(value);
  const [saving, setSaving] = useState(false);
  const inputRef = useRef<HTMLInputElement | HTMLSelectElement>(null);

  useEffect(() => {
    if (editing && inputRef.current) {
      inputRef.current.focus();
      if (inputRef.current instanceof HTMLInputElement) {
        inputRef.current.select();
      }
    }
  }, [editing]);

  const handleDoubleClick = useCallback(() => {
    if (!editable) return;
    setEditValue(value);
    setEditing(true);
  }, [editable, value]);

  const handleSave = useCallback(async () => {
    if (editValue === value) {
      setEditing(false);
      return;
    }

    try {
      setSaving(true);
      await onSave(column.field, editValue, record);
      setEditing(false);
    } catch (error) {
      console.error('[InlineEditCell] Save failed:', error);
      setEditValue(value);
      setEditing(false);
    } finally {
      setSaving(false);
    }
  }, [editValue, value, column.field, record, onSave]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter') {
        handleSave();
      } else if (e.key === 'Escape') {
        setEditValue(value);
        setEditing(false);
      }
    },
    [handleSave, value],
  );

  if (!editable) {
    return <>{children}</>;
  }

  if (editing) {
    // Determine field type for input rendering
    const fieldType = getFieldType(column);
    // Wrapper to stop click from propagating to row handler
    const stopClick = (e: React.MouseEvent) => e.stopPropagation();

    if (fieldType === 'select' && dictItems && dictItems.length > 0) {
      return (
        <select
          ref={inputRef as React.RefObject<HTMLSelectElement>}
          value={editValue ?? ''}
          onChange={(e) => {
            setEditValue(e.target.value);
            // Auto-save on select change (no need for Enter)
            setTimeout(() => {
              const newVal = e.target.value;
              if (newVal !== String(value)) {
                onSave(column.field, newVal, record)
                  .then(() => setEditing(false))
                  .catch(() => {
                    setEditValue(value);
                    setEditing(false);
                  });
              } else {
                setEditing(false);
              }
            }, 0);
          }}
          onBlur={() => {
            if (editValue === value) setEditing(false);
          }}
          onKeyDown={handleKeyDown}
          disabled={saving}
          onClick={stopClick}
          className={inputClass}
          data-testid={`inline-edit-select-${column.field}`}
        >
          <option value="">—</option>
          {dictItems.map((item) => (
            <option key={item.value} value={item.value}>
              {item.label}
            </option>
          ))}
        </select>
      );
    }

    if (fieldType === 'number') {
      return (
        <input
          ref={inputRef as React.RefObject<HTMLInputElement>}
          type="number"
          step="any"
          value={editValue ?? ''}
          onChange={(e) => setEditValue(e.target.value === '' ? null : Number(e.target.value))}
          onBlur={handleSave}
          onKeyDown={handleKeyDown}
          disabled={saving}
          onClick={stopClick}
          className={inputClass}
          data-testid={`inline-edit-number-${column.field}`}
        />
      );
    }

    if (fieldType === 'date') {
      return (
        <input
          ref={inputRef as React.RefObject<HTMLInputElement>}
          type="date"
          value={editValue ? String(editValue).slice(0, 10) : ''}
          onChange={(e) => setEditValue(e.target.value || null)}
          onBlur={handleSave}
          onKeyDown={handleKeyDown}
          disabled={saving}
          onClick={stopClick}
          className={inputClass}
          data-testid={`inline-edit-date-${column.field}`}
        />
      );
    }

    if (fieldType === 'datetime') {
      return (
        <input
          ref={inputRef as React.RefObject<HTMLInputElement>}
          type="datetime-local"
          value={editValue ? String(editValue).slice(0, 16) : ''}
          onChange={(e) => setEditValue(e.target.value || null)}
          onBlur={handleSave}
          onKeyDown={handleKeyDown}
          disabled={saving}
          onClick={stopClick}
          className={inputClass}
          data-testid={`inline-edit-datetime-${column.field}`}
        />
      );
    }

    // Default: text input
    return (
      <input
        ref={inputRef as React.RefObject<HTMLInputElement>}
        type="text"
        value={editValue ?? ''}
        onChange={(e) => setEditValue(e.target.value)}
        onBlur={handleSave}
        onKeyDown={handleKeyDown}
        disabled={saving}
        onClick={stopClick}
        className={inputClass}
        data-testid={`inline-edit-text-${column.field}`}
      />
    );
  }

  return (
    <div
      onDoubleClick={(e) => {
        e.stopPropagation();
        handleDoubleClick();
      }}
      onClick={(e) => e.stopPropagation()}
      className="-mx-1 min-h-[24px] cursor-text rounded px-1 transition-colors hover:bg-blue-50"
      title="Double-click to edit"
      data-testid={`inline-edit-cell-${column.field}`}
    >
      {children}
    </div>
  );
};

/** Infer input type from column metadata */
function getFieldType(column: ColumnConfig): 'select' | 'number' | 'date' | 'datetime' | 'text' {
  if (column.dictCode) return 'select';

  const vt = column.valueType;
  if (vt === 'currency') return 'number';
  if (vt === 'date') return 'date';
  if (vt === 'datetime') return 'datetime';

  // Infer from field name conventions
  const field = column.field || '';
  if (
    field.endsWith('_amount') ||
    field.endsWith('_price') ||
    field.endsWith('_qty') ||
    field.endsWith('_quantity') ||
    field.endsWith('_score') ||
    field.endsWith('_rate') ||
    field.endsWith('_cost') ||
    field.endsWith('_weight') ||
    field.endsWith('_count')
  ) {
    return 'number';
  }
  if (field.endsWith('_date')) return 'date';
  if (field.endsWith('_at')) return 'datetime';

  return 'text';
}
