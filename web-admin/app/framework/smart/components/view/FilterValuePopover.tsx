/**
 * FilterValuePopover — popover for editing a filter condition (operator + value).
 *
 * Renders via createPortal at the given anchorEl coordinates.
 * Operators are filtered based on fieldType.
 */
import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';

import type { ViewFilterConfig } from '~/framework/smart/types/savedView';

interface DictOption {
  value: string;
  label: string;
}

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface FilterValuePopoverProps {
  open: boolean;
  anchorEl?: { x: number; y: number };
  fieldCode: string;
  fieldLabel: string;
  /** TEXT, NUMBER, DATE, ENUM, REFERENCE, BOOLEAN, MONEY */
  fieldType: string;
  dictCode?: string;
  referenceModelCode?: string;
  token?: string;
  operator: string;
  value: unknown;
  onApply: (operator: string, value: unknown) => void;
  onCancel: () => void;
}

// ---------------------------------------------------------------------------
// Operator definitions per field type
// ---------------------------------------------------------------------------

type OperatorDef = { value: ViewFilterConfig['operator']; label: string };

const TEXT_OPS: OperatorDef[] = [
  { value: 'eq', label: 'Equals' },
  { value: 'ne', label: 'Not equals' },
  { value: 'like', label: 'Contains' },
  { value: 'isNull', label: 'Is empty' },
  { value: 'isNotNull', label: 'Is not empty' },
];

const NUMBER_OPS: OperatorDef[] = [
  { value: 'eq', label: 'Equals' },
  { value: 'ne', label: 'Not equals' },
  { value: 'gt', label: 'Greater than' },
  { value: 'gte', label: 'Greater or equal' },
  { value: 'lt', label: 'Less than' },
  { value: 'lte', label: 'Less or equal' },
  { value: 'isNull', label: 'Is empty' },
  { value: 'isNotNull', label: 'Is not empty' },
];

const DATE_OPS: OperatorDef[] = [
  { value: 'eq', label: 'Equals' },
  { value: 'gt', label: 'After' },
  { value: 'gte', label: 'On or after' },
  { value: 'lt', label: 'Before' },
  { value: 'lte', label: 'On or before' },
  { value: 'between', label: 'Between' },
  { value: 'isNull', label: 'Is empty' },
  { value: 'isNotNull', label: 'Is not empty' },
];

const ENUM_OPS: OperatorDef[] = [
  { value: 'eq', label: 'Equals' },
  { value: 'ne', label: 'Not equals' },
  { value: 'in', label: 'In' },
  { value: 'isNull', label: 'Is empty' },
  { value: 'isNotNull', label: 'Is not empty' },
];

const BOOLEAN_OPS: OperatorDef[] = [{ value: 'eq', label: 'Equals' }];

function operatorsForType(fieldType: string): OperatorDef[] {
  const t = fieldType.toUpperCase();
  switch (t) {
    case 'NUMBER':
    case 'INTEGER':
    case 'DECIMAL':
    case 'MONEY':
    case 'CURRENCY':
      return NUMBER_OPS;
    case 'DATE':
    case 'DATETIME':
      return DATE_OPS;
    case 'ENUM':
    case 'DICT':
      return ENUM_OPS;
    case 'BOOLEAN':
      return BOOLEAN_OPS;
    case 'REFERENCE':
    case 'USER':
      return TEXT_OPS;
    default:
      return TEXT_OPS;
  }
}

/** Whether the operator requires no value input */
function isNullishOp(op: string): boolean {
  return op === 'isNull' || op === 'isNotNull';
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function FilterValuePopover({
  open,
  anchorEl,
  fieldLabel,
  fieldType,
  dictCode,
  referenceModelCode,
  operator: initialOperator,
  value: initialValue,
  onApply,
  onCancel,
}: FilterValuePopoverProps) {
  const [operator, setOperator] = useState(initialOperator);
  const [value, setValue] = useState<unknown>(initialValue);
  const containerRef = useRef<HTMLDivElement>(null);
  const [dictOptions, setDictOptions] = useState<DictOption[]>([]);

  // Load dict options when dictCode is provided
  useEffect(() => {
    if (!dictCode || !open) return;
    let cancelled = false;
    (async () => {
      const result = await fetchResult<any>(`/api/meta/dict/by-code/${dictCode}/data`, {
        token: undefined,
      });
      if (cancelled) return;
      if (ResultHelper.isSuccess(result) && result.data) {
        const items = Array.isArray(result.data) ? result.data : result.data.items || [];
        setDictOptions(items.map((i: any) => ({ value: i.value, label: i.label || i.value })));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [dictCode, open]);

  // Sync from props when popover opens
  useEffect(() => {
    if (open) {
      setOperator(initialOperator);
      setValue(initialValue);
    }
  }, [open, initialOperator, initialValue]);

  // Click-outside detection
  useEffect(() => {
    if (!open) return;
    function handleClick(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        onCancel();
      }
    }
    const id = requestAnimationFrame(() => {
      document.addEventListener('mousedown', handleClick);
    });
    return () => {
      cancelAnimationFrame(id);
      document.removeEventListener('mousedown', handleClick);
    };
  }, [open, onCancel]);

  if (!open || !anchorEl) return null;

  // If dictCode is present, treat as ENUM regardless of declared fieldType
  const effectiveType = dictCode ? 'ENUM' : fieldType;
  const ops = operatorsForType(effectiveType);
  const showValue = !isNullishOp(operator);
  const ft = effectiveType.toUpperCase();

  // ---- Value input renderer ----
  function renderValueInput() {
    if (!showValue) return null;

    // Boolean
    if (ft === 'BOOLEAN') {
      return (
        <select
          className="w-full rounded border border-gray-200 px-2 py-1.5 text-sm outline-none focus:border-blue-400"
          value={String(value ?? '')}
          onChange={(e) => setValue(e.target.value === 'true')}
        >
          <option value="">-- Select --</option>
          <option value="true">Yes</option>
          <option value="false">No</option>
        </select>
      );
    }

    // Number / Money
    if (['NUMBER', 'INTEGER', 'DECIMAL', 'MONEY', 'CURRENCY'].includes(ft)) {
      return (
        <input
          type="number"
          className="w-full rounded border border-gray-200 px-2 py-1.5 text-sm outline-none focus:border-blue-400"
          placeholder="Enter value..."
          value={value != null ? String(value) : ''}
          onChange={(e) => {
            const v = e.target.value;
            setValue(v === '' ? null : Number(v));
          }}
        />
      );
    }

    // Date — between (range)
    if ((ft === 'DATE' || ft === 'DATETIME') && operator === 'between') {
      const range = Array.isArray(value) ? value : ['', ''];
      return (
        <div className="flex items-center gap-2">
          <input
            type="date"
            className="flex-1 rounded border border-gray-200 px-2 py-1.5 text-sm outline-none focus:border-blue-400"
            value={range[0] || ''}
            onChange={(e) => setValue([e.target.value || '', range[1] || ''])}
          />
          <span className="text-xs text-gray-400">to</span>
          <input
            type="date"
            className="flex-1 rounded border border-gray-200 px-2 py-1.5 text-sm outline-none focus:border-blue-400"
            value={range[1] || ''}
            onChange={(e) => setValue([range[0] || '', e.target.value || ''])}
          />
        </div>
      );
    }

    // Date — single value
    if ((ft === 'DATE' || ft === 'DATETIME') && operator !== 'between') {
      return (
        <input
          type="date"
          className="w-full rounded border border-gray-200 px-2 py-1.5 text-sm outline-none focus:border-blue-400"
          value={value != null ? String(value) : ''}
          onChange={(e) => setValue(e.target.value || null)}
        />
      );
    }

    // Enum / Dict — multiselect checkboxes for 'in' operator
    if ((ft === 'ENUM' || ft === 'DICT') && operator === 'in' && dictOptions.length > 0) {
      const selected = Array.isArray(value) ? value : value ? [value] : [];
      return (
        <div className="max-h-40 space-y-1 overflow-y-auto">
          {dictOptions.map((opt) => (
            <label
              key={opt.value}
              className="flex cursor-pointer items-center gap-2 rounded px-1 py-0.5 text-sm hover:bg-gray-50"
            >
              <input
                type="checkbox"
                checked={selected.includes(opt.value)}
                className="h-3.5 w-3.5 rounded border-gray-300 text-blue-600"
                onChange={(e) => {
                  const next = e.target.checked
                    ? [...selected, opt.value]
                    : selected.filter((v: string) => v !== opt.value);
                  setValue(next);
                }}
              />
              {opt.label}
            </label>
          ))}
        </div>
      );
    }

    // Enum / Dict — render <select> with dict options (single value)
    if ((ft === 'ENUM' || ft === 'DICT') && dictOptions.length > 0 && operator !== 'in') {
      return (
        <select
          className="w-full rounded border border-gray-200 px-2 py-1.5 text-sm outline-none focus:border-blue-400"
          value={value != null ? String(value) : ''}
          onChange={(e) => setValue(e.target.value || null)}
        >
          <option value="">-- Select --</option>
          {dictOptions.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      );
    }

    // Reference / User — text search input
    if ((ft === 'REFERENCE' || ft === 'USER') && referenceModelCode) {
      return (
        <input
          type="text"
          placeholder="Search by name or ID..."
          className="w-full rounded border border-gray-200 px-2 py-1.5 text-sm outline-none focus:border-blue-400"
          value={value != null ? String(value) : ''}
          onChange={(e) => setValue(e.target.value || null)}
        />
      );
    }

    // Default — text input
    return (
      <input
        type="text"
        className="w-full rounded border border-gray-200 px-2 py-1.5 text-sm outline-none focus:border-blue-400"
        placeholder="Enter value..."
        value={value != null ? String(value) : ''}
        onChange={(e) => setValue(e.target.value || null)}
      />
    );
  }

  const content = (
    <div
      ref={containerRef}
      className="fixed z-[9999] min-w-[260px] rounded-lg border border-gray-200 bg-white p-3 shadow-lg"
      style={{ left: anchorEl.x, top: anchorEl.y }}
    >
      {/* Row 1: field label + operator */}
      <div className="mb-2 flex items-center gap-2">
        <span className="flex-shrink-0 text-sm font-medium text-gray-700">{fieldLabel}</span>
        <select
          className="flex-1 rounded border border-gray-200 px-2 py-1 text-sm outline-none focus:border-blue-400"
          value={operator}
          onChange={(e) => {
            setOperator(e.target.value);
            // Clear value when switching to nullish operator
            if (isNullishOp(e.target.value)) setValue(null);
          }}
        >
          {ops.map((op) => (
            <option key={op.value} value={op.value}>
              {op.label}
            </option>
          ))}
        </select>
      </div>

      {/* Row 2: value input */}
      {showValue && <div className="mb-3">{renderValueInput()}</div>}

      {/* Row 3: action buttons */}
      <div className="flex items-center justify-end gap-2">
        <button
          type="button"
          className="rounded px-3 py-1 text-sm text-gray-500 hover:bg-gray-100"
          onClick={onCancel}
        >
          Cancel
        </button>
        <button
          type="button"
          className="rounded bg-blue-600 px-3 py-1 text-sm text-white hover:bg-blue-700"
          onClick={() => onApply(operator, isNullishOp(operator) ? null : value)}
        >
          Apply
        </button>
      </div>
    </div>
  );

  return createPortal(content, document.body);
}
