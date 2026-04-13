/**
 * InlineEditCell — Renders a single cell in inline edit mode.
 *
 * Supports:
 * - Text input, number input, date input based on column config
 * - Validation error display (red border + tooltip)
 * - Keyboard: Enter=save, Esc=cancel, Tab=next field
 */

import React, { useRef, useEffect, useCallback } from 'react';
import type { ColumnConfig } from '~/framework/meta/schemas/types';

export interface InlineEditCellProps {
  col: ColumnConfig;
  value: any;
  error?: string;
  onChange: (field: string, value: any) => void;
  onSave: () => void;
  onCancel: () => void;
  onTabNext: (field: string, shiftKey: boolean) => void;
  autoFocus?: boolean;
}

function getInputType(col: ColumnConfig): string {
  if (col.valueType === 'date') return 'date';
  if (col.valueType === 'datetime') return 'datetime-local';
  if (col.valueType === 'time') return 'time';
  if (isNumericColumn(col)) return 'number';
  return 'text';
}

function isNumericColumn(col: ColumnConfig): boolean {
  return (
    col.format === 'currency' ||
    col.format === 'number' ||
    col.valueType === 'currency' ||
    col.field.endsWith('_qty') ||
    col.field.endsWith('_price') ||
    col.field.endsWith('_amount') ||
    col.field.endsWith('_count')
  );
}

export const InlineEditCell: React.FC<InlineEditCellProps> = ({
  col,
  value,
  error,
  onChange,
  onSave,
  onCancel,
  onTabNext,
  autoFocus = false,
}) => {
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (autoFocus && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [autoFocus]);

  const handleChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const raw = e.target.value;
      const val = isNumericColumn(col) && raw !== '' ? Number(raw) : raw;
      onChange(col.field, val);
    },
    [col, onChange],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLInputElement>) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        onSave();
      } else if (e.key === 'Escape') {
        e.preventDefault();
        onCancel();
      } else if (e.key === 'Tab') {
        e.preventDefault();
        onTabNext(col.field, e.shiftKey);
      }
    },
    [col.field, onSave, onCancel, onTabNext],
  );

  if (col.editable === false || col.readOnly) {
    return (
      <td
        className={`px-4 py-1.5 text-sm text-gray-500 ${
          col.align === 'right'
            ? 'text-right'
            : col.align === 'center'
              ? 'text-center'
              : 'text-left'
        }`}
      >
        {formatDisplayValue(value, col)}
      </td>
    );
  }

  return (
    <td
      className={`px-3 py-1 ${
        col.align === 'right' ? 'text-right' : col.align === 'center' ? 'text-center' : 'text-left'
      }`}
    >
      <div className="relative">
        <input
          ref={inputRef}
          type={getInputType(col)}
          value={value ?? ''}
          onChange={handleChange}
          onKeyDown={handleKeyDown}
          data-testid={`inline-edit-${col.field}`}
          className={`w-full rounded border px-2 py-1 text-sm transition-colors focus:ring-1 focus:outline-none ${
            error
              ? 'border-red-400 bg-red-50 focus:ring-red-400'
              : 'border-gray-300 focus:border-blue-400 focus:ring-blue-400'
          }`}
          step={isNumericColumn(col) ? 'any' : undefined}
        />
        {error && (
          <div
            className="absolute top-full left-0 z-10 mt-0.5 rounded border border-red-200 bg-red-50 px-2 py-1 text-xs whitespace-nowrap text-red-600 shadow-sm"
            data-testid={`inline-error-${col.field}`}
          >
            {error}
          </div>
        )}
      </div>
    </td>
  );
};

function formatDisplayValue(value: any, col: ColumnConfig): string {
  if (value == null) return '-';
  if (typeof value === 'number') {
    return value.toLocaleString(undefined, {
      minimumFractionDigits: 0,
      maximumFractionDigits: 2,
    });
  }
  return String(value);
}

export default InlineEditCell;
