import React from 'react';
import type { ConditionRow as ConditionRowType, FieldOption, FieldCategory } from './types';
import { OPERATORS_BY_CATEGORY } from './types';

interface ConditionRowProps {
  row: ConditionRowType;
  fields: FieldOption[];
  onChange: (updated: ConditionRowType) => void;
  onDelete: () => void;
}

export function ConditionRow({ row, fields, onChange, onDelete }: ConditionRowProps) {
  const selectedField = fields.find((f) => f.code === row.field);
  const category: FieldCategory = selectedField?.category ?? 'string';
  const operators = OPERATORS_BY_CATEGORY[category];

  // If current operator is not valid for this category, reset to first valid
  const validOp = operators.find((o) => o.value === row.operator) ? row.operator : operators[0].value;
  if (validOp !== row.operator) {
    onChange({ ...row, operator: validOp });
  }

  return (
    <div className="flex items-center gap-1.5 mb-1.5" data-testid={`condition-row-${row.id}`}>
      <select
        className="h-7 flex-1 min-w-0 rounded border border-gray-300 bg-white px-1.5 text-xs focus:border-blue-500 focus:outline-none"
        value={row.field}
        onChange={(e) => onChange({ ...row, field: e.target.value })}
        data-testid="condition-field"
      >
        <option value="">Select field...</option>
        {groupedOptions(fields)}
      </select>

      <select
        className="h-7 w-20 shrink-0 rounded border border-gray-300 bg-white px-1 text-xs focus:border-blue-500 focus:outline-none"
        value={row.operator}
        onChange={(e) => onChange({ ...row, operator: e.target.value as ConditionRowType['operator'] })}
        data-testid="condition-operator"
      >
        {operators.map((op) => (
          <option key={op.value} value={op.value}>{op.label}</option>
        ))}
      </select>

      <input
        className="h-7 flex-1 min-w-0 rounded border border-gray-300 bg-white px-1.5 text-xs focus:border-blue-500 focus:outline-none"
        value={row.value}
        onChange={(e) => onChange({ ...row, value: e.target.value })}
        placeholder="Value..."
        data-testid="condition-value"
      />

      <button
        className="flex h-7 w-7 shrink-0 items-center justify-center rounded text-gray-400 hover:bg-red-50 hover:text-red-500"
        onClick={onDelete}
        title="Delete condition"
        data-testid="condition-delete"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <line x1="18" y1="6" x2="6" y2="18" />
          <line x1="6" y1="6" x2="18" y2="18" />
        </svg>
      </button>
    </div>
  );
}

function groupedOptions(fields: FieldOption[]) {
  const groups = new Map<string, FieldOption[]>();
  for (const f of fields) {
    const g = f.group ?? 'Fields';
    if (!groups.has(g)) groups.set(g, []);
    groups.get(g)!.push(f);
  }

  if (groups.size <= 1) {
    return fields.map((f) => (
      <option key={f.code} value={f.code}>{f.name} ({f.code})</option>
    ));
  }

  return Array.from(groups.entries()).map(([groupName, items]) => (
    <optgroup key={groupName} label={groupName}>
      {items.map((f) => (
        <option key={f.code} value={f.code}>{f.name} ({f.code})</option>
      ))}
    </optgroup>
  ));
}
