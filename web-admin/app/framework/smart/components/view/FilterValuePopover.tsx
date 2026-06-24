/**
 * FilterValuePopover — popover for editing a filter condition (operator + value).
 *
 * Renders via createPortal at the given anchorEl coordinates.
 * Operators are filtered based on fieldType.
 */
import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';

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

type OperatorDef = { value: ViewFilterConfig['operator']; labelKey: string; fallbackZh: string; fallbackEn: string };

const TEXT_OPS: OperatorDef[] = [
  { value: 'eq', labelKey: 'filter.operator.eq', fallbackZh: '等于', fallbackEn: 'Equals' },
  { value: 'ne', labelKey: 'filter.operator.ne', fallbackZh: '不等于', fallbackEn: 'Not equals' },
  { value: 'like', labelKey: 'filter.operator.like', fallbackZh: '包含', fallbackEn: 'Contains' },
  { value: 'isNull', labelKey: 'filter.operator.isNull', fallbackZh: '为空', fallbackEn: 'Is empty' },
  { value: 'isNotNull', labelKey: 'filter.operator.isNotNull', fallbackZh: '不为空', fallbackEn: 'Is not empty' },
];

const NUMBER_OPS: OperatorDef[] = [
  { value: 'eq', labelKey: 'filter.operator.eq', fallbackZh: '等于', fallbackEn: 'Equals' },
  { value: 'ne', labelKey: 'filter.operator.ne', fallbackZh: '不等于', fallbackEn: 'Not equals' },
  { value: 'gt', labelKey: 'filter.operator.gt', fallbackZh: '大于', fallbackEn: 'Greater than' },
  { value: 'gte', labelKey: 'filter.operator.gte', fallbackZh: '大于等于', fallbackEn: 'Greater or equal' },
  { value: 'lt', labelKey: 'filter.operator.lt', fallbackZh: '小于', fallbackEn: 'Less than' },
  { value: 'lte', labelKey: 'filter.operator.lte', fallbackZh: '小于等于', fallbackEn: 'Less or equal' },
  { value: 'isNull', labelKey: 'filter.operator.isNull', fallbackZh: '为空', fallbackEn: 'Is empty' },
  { value: 'isNotNull', labelKey: 'filter.operator.isNotNull', fallbackZh: '不为空', fallbackEn: 'Is not empty' },
];

const DATE_OPS: OperatorDef[] = [
  { value: 'eq', labelKey: 'filter.operator.eq', fallbackZh: '等于', fallbackEn: 'Equals' },
  { value: 'gt', labelKey: 'filter.operator.after', fallbackZh: '晚于', fallbackEn: 'After' },
  { value: 'gte', labelKey: 'filter.operator.onOrAfter', fallbackZh: '不早于', fallbackEn: 'On or after' },
  { value: 'lt', labelKey: 'filter.operator.before', fallbackZh: '早于', fallbackEn: 'Before' },
  { value: 'lte', labelKey: 'filter.operator.onOrBefore', fallbackZh: '不晚于', fallbackEn: 'On or before' },
  { value: 'between', labelKey: 'filter.operator.between', fallbackZh: '介于', fallbackEn: 'Between' },
  { value: 'isNull', labelKey: 'filter.operator.isNull', fallbackZh: '为空', fallbackEn: 'Is empty' },
  { value: 'isNotNull', labelKey: 'filter.operator.isNotNull', fallbackZh: '不为空', fallbackEn: 'Is not empty' },
];

const ENUM_OPS: OperatorDef[] = [
  { value: 'eq', labelKey: 'filter.operator.eq', fallbackZh: '等于', fallbackEn: 'Equals' },
  { value: 'ne', labelKey: 'filter.operator.ne', fallbackZh: '不等于', fallbackEn: 'Not equals' },
  { value: 'in', labelKey: 'filter.operator.in', fallbackZh: '属于', fallbackEn: 'In' },
  { value: 'isNull', labelKey: 'filter.operator.isNull', fallbackZh: '为空', fallbackEn: 'Is empty' },
  { value: 'isNotNull', labelKey: 'filter.operator.isNotNull', fallbackZh: '不为空', fallbackEn: 'Is not empty' },
];

const BOOLEAN_OPS: OperatorDef[] = [
  { value: 'eq', labelKey: 'filter.operator.eq', fallbackZh: '等于', fallbackEn: 'Equals' },
];

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

function hasMeaningfulFilterValue(value: unknown, operator: string): boolean {
  if (isNullishOp(operator)) return true;
  if (Array.isArray(value)) {
    return value.some((item) => item !== null && item !== undefined && String(item).trim() !== '');
  }
  return value !== null && value !== undefined && String(value).trim() !== '';
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
  const { t, locale } = useI18n();
  const [operator, setOperator] = useState(initialOperator);
  const [value, setValue] = useState<unknown>(initialValue);
  const containerRef = useRef<HTMLDivElement>(null);
  const [dictOptions, setDictOptions] = useState<DictOption[]>([]);
  const zh = locale === 'zh-CN' || locale.startsWith('zh');
  const l = (key: string, zhFallback: string, enFallback: string) =>
    t(key, undefined, zh ? zhFallback : enFallback);

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
  const canApply = hasMeaningfulFilterValue(value, operator);

  // ---- Value input renderer ----
  function renderValueInput() {
    if (!showValue) return null;

    // Boolean
    if (ft === 'BOOLEAN') {
      return (
        <select
          className="border-border bg-panel text-text focus:border-accent w-full rounded border px-2 py-1.5 text-sm outline-none"
          value={String(value ?? '')}
          onChange={(e) => setValue(e.target.value === 'true')}
        >
          <option value="">{l('common.select_placeholder', '请选择', '-- Select --')}</option>
          <option value="true">{l('common.yes', '是', 'Yes')}</option>
          <option value="false">{l('common.no', '否', 'No')}</option>
        </select>
      );
    }

    // Number / Money
    if (['NUMBER', 'INTEGER', 'DECIMAL', 'MONEY', 'CURRENCY'].includes(ft)) {
      return (
        <input
          type="number"
          className="border-border bg-panel text-text placeholder:text-text-3 focus:border-accent w-full rounded border px-2 py-1.5 text-sm outline-none"
          placeholder={l('filter.value.placeholder', '请输入筛选值', 'Enter value...')}
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
            className="border-border bg-panel text-text focus:border-accent flex-1 rounded border px-2 py-1.5 text-sm outline-none"
            value={range[0] || ''}
            onChange={(e) => setValue([e.target.value || '', range[1] || ''])}
          />
          <span className="text-text-3 text-xs">{l('filter.range.to', '至', 'to')}</span>
          <input
            type="date"
            className="border-border bg-panel text-text focus:border-accent flex-1 rounded border px-2 py-1.5 text-sm outline-none"
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
          className="border-border bg-panel text-text focus:border-accent w-full rounded border px-2 py-1.5 text-sm outline-none"
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
              className="hover:bg-hover flex cursor-pointer items-center gap-2 rounded px-1 py-0.5 text-sm"
            >
              <input
                type="checkbox"
                checked={selected.includes(opt.value)}
                className="border-border-strong text-accent h-3.5 w-3.5 rounded"
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
          className="border-border bg-panel text-text focus:border-accent w-full rounded border px-2 py-1.5 text-sm outline-none"
          value={value != null ? String(value) : ''}
          onChange={(e) => setValue(e.target.value || null)}
        >
          <option value="">{l('common.select_placeholder', '请选择', '-- Select --')}</option>
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
          placeholder={l('filter.reference.placeholder', '按名称或 ID 搜索', 'Search by name or ID...')}
          className="border-border bg-panel text-text placeholder:text-text-3 focus:border-accent w-full rounded border px-2 py-1.5 text-sm outline-none"
          value={value != null ? String(value) : ''}
          onChange={(e) => setValue(e.target.value || null)}
        />
      );
    }

    // Default — text input
    return (
      <input
        type="text"
        className="border-border bg-panel text-text placeholder:text-text-3 focus:border-accent w-full rounded border px-2 py-1.5 text-sm outline-none"
        placeholder={l('filter.value.placeholder', '请输入筛选值', 'Enter value...')}
        value={value != null ? String(value) : ''}
        onChange={(e) => setValue(e.target.value || null)}
      />
    );
  }

  const content = (
    <div
      ref={containerRef}
      className="border-border bg-panel shadow-pop fixed z-[9999] min-w-[260px] rounded-lg border p-3"
      style={{ left: anchorEl.x, top: anchorEl.y }}
    >
      {/* Row 1: field label + operator */}
      <div className="mb-2 flex items-center gap-2">
        <span className="text-text flex-shrink-0 text-sm font-medium">{fieldLabel}</span>
        <select
          className="border-border bg-panel text-text focus:border-accent flex-1 rounded border px-2 py-1 text-sm outline-none"
          value={operator}
          onChange={(e) => {
            setOperator(e.target.value);
            // Clear value when switching to nullish operator
            if (isNullishOp(e.target.value)) setValue(null);
          }}
        >
          {ops.map((op) => (
            <option key={op.value} value={op.value}>
              {l(op.labelKey, op.fallbackZh, op.fallbackEn)}
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
          className="text-text-2 hover:bg-hover rounded px-3 py-1 text-sm"
          onClick={onCancel}
        >
          {l('common.cancel', '取消', 'Cancel')}
        </button>
        <button
          type="button"
          className="bg-accent hover:bg-accent-hover disabled:bg-disabled disabled:text-text-3 rounded px-3 py-1 text-sm text-white disabled:cursor-not-allowed"
          disabled={!canApply}
          onClick={() => {
            if (!canApply) return;
            onApply(operator, isNullishOp(operator) ? null : value);
          }}
        >
          {l('common.apply', '应用', 'Apply')}
        </button>
      </div>
    </div>
  );

  return createPortal(content, document.body);
}
