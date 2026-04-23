/**
 * InlineEditableCell — Renders a table cell that switches between
 * display mode and edit mode for inline sub-table editing.
 *
 * Edit mode renders an appropriate input based on column type:
 * - text/default: text input
 * - number/currency: number input
 * - date/datetime: date input
 * - tag/dict: select dropdown
 *
 * Supports keyboard navigation: Tab (next field), Enter (save), Esc (cancel).
 */

import React, { useRef, useEffect } from 'react';
import type { ColumnConfig } from '~/framework/meta/schemas/types';

export interface InlineEditableCellProps {
  col: ColumnConfig;
  value: any;
  displayValue: string;
  isEditing: boolean;
  error?: string;
  onChange: (value: any) => void;
  onKeyDown: (e: React.KeyboardEvent) => void;
  dictOptions?: Array<{ value: string; label: string }>;
  autoFocus?: boolean;
  t?: (key: string) => string;
}

export const InlineEditableCell: React.FC<InlineEditableCellProps> = ({
  col,
  value,
  displayValue,
  isEditing,
  error,
  onChange,
  onKeyDown,
  dictOptions,
  autoFocus = false,
}) => {
  const inputRef = useRef<HTMLInputElement | HTMLSelectElement>(null);

  useEffect(() => {
    if (isEditing && autoFocus && inputRef.current) {
      inputRef.current.focus();
    }
  }, [isEditing, autoFocus]);

  if (!isEditing) {
    return (
      <span className="block truncate" title={displayValue}>
        {displayValue}
      </span>
    );
  }

  const baseInputClass = `w-full px-2 py-1 text-sm border rounded transition-colors
    focus:outline-none focus:ring-1 ${
      error
        ? 'border-red-300 focus:ring-red-400 bg-red-50/30'
        : 'border-gray-300 focus:ring-blue-400'
    }`;

  // Dict / tag select
  if (col.dictCode && dictOptions && dictOptions.length > 0) {
    return (
      <div className="relative">
        <select
          ref={inputRef as React.RefObject<HTMLSelectElement>}
          value={value ?? ''}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={onKeyDown}
          className={baseInputClass}
          data-testid={`inline-edit-${col.field}`}
        >
          <option value="">—</option>
          {dictOptions.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
        {error && <ErrorTooltip message={error} />}
      </div>
    );
  }

  // Date input
  if (col.valueType === 'date' || col.valueType === 'datetime') {
    return (
      <div className="relative">
        <input
          ref={inputRef as React.RefObject<HTMLInputElement>}
          type={col.valueType === 'datetime' ? 'datetime-local' : 'date'}
          value={value ?? ''}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={onKeyDown}
          className={baseInputClass}
          data-testid={`inline-edit-${col.field}`}
        />
        {error && <ErrorTooltip message={error} />}
      </div>
    );
  }

  // Number input
  if (isNumericColumn(col)) {
    return (
      <div className="relative">
        <input
          ref={inputRef as React.RefObject<HTMLInputElement>}
          type="number"
          value={value ?? ''}
          onChange={(e) => {
            const v = e.target.value;
            onChange(v === '' ? '' : Number(v));
          }}
          onKeyDown={onKeyDown}
          min={col.min}
          max={col.max}
          step="any"
          className={baseInputClass}
          data-testid={`inline-edit-${col.field}`}
        />
        {error && <ErrorTooltip message={error} />}
      </div>
    );
  }

  // Default: text input
  return (
    <div className="relative">
      <input
        ref={inputRef as React.RefObject<HTMLInputElement>}
        type="text"
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={onKeyDown}
        className={baseInputClass}
        data-testid={`inline-edit-${col.field}`}
      />
      {error && <ErrorTooltip message={error} />}
    </div>
  );
};

/**
 * ErrorTooltip — small red tooltip below the input showing validation error.
 */
const ErrorTooltip: React.FC<{ message: string }> = ({ message }) => (
  <div
    className="absolute top-full left-0 z-10 mt-1 rounded bg-red-500 px-2 py-1 text-xs whitespace-nowrap text-white shadow-lg"
    data-testid="inline-edit-error"
    role="alert"
  >
    {message}
    <div className="absolute -top-1 left-3 h-2 w-2 rotate-45 bg-red-500" />
  </div>
);

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
