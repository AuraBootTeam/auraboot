/**
 * Visual WHERE condition builder.
 * Generates JSON structure for NamedQuery test panel.
 * Supports fallback to raw JSON editing via toggle.
 */

import React, { useState, useCallback } from 'react';
import type { NamedQueryFieldDTO } from '~/shared/services/namedQueryService';
import { OPERATORS_BY_TYPE, OPERATOR_LABELS } from './constants';

interface Condition {
  id: string;
  field: string;
  op: string;
  value: string;
  valueTo?: string; // for BETWEEN
}

interface ConditionBuilderProps {
  fields: NamedQueryFieldDTO[];
  value: string; // raw JSON
  onChange: (json: string) => void;
}

let nextId = 1;
function genId() {
  return `cond_${nextId++}`;
}

function parseConditions(json: string): Condition[] {
  if (!json.trim()) return [];
  try {
    const parsed = JSON.parse(json);
    // Support single condition or array
    const items = Array.isArray(parsed) ? parsed : [parsed];
    return items.map((item) => ({
      id: genId(),
      field: item.field || '',
      op: item.op || 'eq',
      value:
        item.op === 'between' && Array.isArray(item.value)
          ? String(item.value[0] ?? '')
          : Array.isArray(item.value)
            ? item.value.join(', ')
            : String(item.value ?? ''),
      valueTo:
        item.op === 'between' && Array.isArray(item.value)
          ? String(item.value[1] ?? '')
          : undefined,
    }));
  } catch {
    return [];
  }
}

function toJson(conditions: Condition[]): string {
  if (conditions.length === 0) return '';
  const items = conditions
    .filter((c) => c.field)
    .map((c) => {
      const base: any = { field: c.field, op: c.op };
      if (c.op === 'is_null' || c.op === 'is_not_null') {
        // No value needed
      } else if (c.op === 'between') {
        base.value = [c.value, c.valueTo || ''];
      } else if (c.op === 'in' || c.op === 'not_in') {
        base.value = c.value
          .split(',')
          .map((v) => v.trim())
          .filter(Boolean);
      } else {
        base.value = c.value;
      }
      return base;
    });
  if (items.length === 0) return '';
  return JSON.stringify(items.length === 1 ? items[0] : items, null, 2);
}

export default function ConditionBuilder({ fields, value, onChange }: ConditionBuilderProps) {
  const [mode, setMode] = useState<'visual' | 'json'>('visual');
  const [conditions, setConditions] = useState<Condition[]>(() => parseConditions(value));

  const fieldMap = new Map(fields.map((f) => [f.fieldCode, f]));

  const syncToJson = useCallback(
    (conds: Condition[]) => {
      setConditions(conds);
      onChange(toJson(conds));
    },
    [onChange],
  );

  const addCondition = () => {
    const firstField = fields[0]?.fieldCode || '';
    syncToJson([...conditions, { id: genId(), field: firstField, op: 'eq', value: '' }]);
  };

  const removeCondition = (id: string) => {
    syncToJson(conditions.filter((c) => c.id !== id));
  };

  const updateCondition = (id: string, patch: Partial<Condition>) => {
    const updated = conditions.map((c) => {
      if (c.id !== id) return c;
      const merged = { ...c, ...patch };
      // Reset value when switching to is_null/is_not_null
      if (patch.op && (patch.op === 'is_null' || patch.op === 'is_not_null')) {
        merged.value = '';
        merged.valueTo = undefined;
      }
      return merged;
    });
    syncToJson(updated);
  };

  const getOperatorsForField = (fieldCode: string): string[] => {
    const f = fieldMap.get(fieldCode);
    if (f?.operators && f.operators.length > 0) return f.operators;
    return OPERATORS_BY_TYPE[f?.dataType || 'string'] || OPERATORS_BY_TYPE.STRING;
  };

  const getFieldType = (fieldCode: string): string => {
    return fieldMap.get(fieldCode)?.dataType || 'string';
  };

  const handleModeSwitch = (newMode: 'visual' | 'json') => {
    if (newMode === 'visual') {
      setConditions(parseConditions(value));
    }
    setMode(newMode);
  };

  const noValue = (op: string) => op === 'is_null' || op === 'is_not_null';

  return (
    <div>
      <div className="mb-2 flex items-center justify-between">
        <label className="block text-sm font-medium text-gray-700">WHERE 条件</label>
        <div className="flex rounded-md bg-gray-100 p-0.5">
          <button
            type="button"
            onClick={() => handleModeSwitch('visual')}
            className={`rounded px-2.5 py-1 text-xs ${mode === 'visual' ? 'bg-white text-gray-900 shadow' : 'text-gray-500 hover:text-gray-700'}`}
          >
            可视化
          </button>
          <button
            type="button"
            onClick={() => handleModeSwitch('json')}
            className={`rounded px-2.5 py-1 text-xs ${mode === 'json' ? 'bg-white text-gray-900 shadow' : 'text-gray-500 hover:text-gray-700'}`}
          >
            JSON
          </button>
        </div>
      </div>

      {mode === 'json' ? (
        <textarea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          rows={4}
          className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          placeholder='{"field": "user_name", "op": "like", "value": "%test%"}'
        />
      ) : (
        <div className="space-y-2">
          {conditions.map((cond) => {
            const operators = getOperatorsForField(cond.field);
            const fieldType = getFieldType(cond.field);
            const inputType =
              fieldType === 'number' ? 'number' : fieldType === 'date' ? 'date' : 'text';

            return (
              <div
                key={cond.id}
                className="flex items-center gap-2 rounded-md border border-gray-200 bg-gray-50 p-2"
              >
                {/* Field */}
                <select
                  value={cond.field}
                  onChange={(e) =>
                    updateCondition(cond.id, { field: e.target.value, op: 'eq', value: '' })
                  }
                  className="w-40 rounded border border-gray-300 px-2 py-1.5 text-sm focus:ring-1 focus:ring-blue-500 focus:outline-none"
                >
                  <option value="">选择字段</option>
                  {fields.map((f) => (
                    <option key={f.fieldCode} value={f.fieldCode}>
                      {f.displayName || f.fieldCode}
                    </option>
                  ))}
                </select>

                {/* Operator */}
                <select
                  value={cond.op}
                  onChange={(e) => updateCondition(cond.id, { op: e.target.value })}
                  className="w-36 rounded border border-gray-300 px-2 py-1.5 text-sm focus:ring-1 focus:ring-blue-500 focus:outline-none"
                >
                  {operators.map((op) => (
                    <option key={op} value={op}>
                      {OPERATOR_LABELS[op] || op}
                    </option>
                  ))}
                </select>

                {/* Value(s) */}
                {!noValue(cond.op) &&
                  (cond.op === 'between' ? (
                    <div className="flex flex-1 items-center gap-1">
                      <input
                        type={inputType}
                        value={cond.value}
                        onChange={(e) => updateCondition(cond.id, { value: e.target.value })}
                        placeholder="起始值"
                        className="flex-1 rounded border border-gray-300 px-2 py-1.5 text-sm focus:ring-1 focus:ring-blue-500 focus:outline-none"
                      />
                      <span className="text-xs text-gray-500">~</span>
                      <input
                        type={inputType}
                        value={cond.valueTo || ''}
                        onChange={(e) => updateCondition(cond.id, { valueTo: e.target.value })}
                        placeholder="结束值"
                        className="flex-1 rounded border border-gray-300 px-2 py-1.5 text-sm focus:ring-1 focus:ring-blue-500 focus:outline-none"
                      />
                    </div>
                  ) : fieldType === 'boolean' ? (
                    <select
                      value={cond.value}
                      onChange={(e) => updateCondition(cond.id, { value: e.target.value })}
                      className="flex-1 rounded border border-gray-300 px-2 py-1.5 text-sm focus:ring-1 focus:ring-blue-500 focus:outline-none"
                    >
                      <option value="">选择</option>
                      <option value="true">true</option>
                      <option value="false">false</option>
                    </select>
                  ) : (
                    <input
                      type={inputType}
                      value={cond.value}
                      onChange={(e) => updateCondition(cond.id, { value: e.target.value })}
                      placeholder={
                        cond.op === 'in' || cond.op === 'not_in' ? '逗号分隔多个值' : '输入值'
                      }
                      className="flex-1 rounded border border-gray-300 px-2 py-1.5 text-sm focus:ring-1 focus:ring-blue-500 focus:outline-none"
                    />
                  ))}

                {/* Remove */}
                <button
                  type="button"
                  onClick={() => removeCondition(cond.id)}
                  className="p-1 text-gray-400 hover:text-red-500"
                  title="删除条件"
                >
                  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M6 18L18 6M6 6l12 12"
                    />
                  </svg>
                </button>
              </div>
            );
          })}

          <button
            type="button"
            onClick={addCondition}
            className="inline-flex items-center rounded-md px-3 py-1.5 text-sm text-blue-600 hover:bg-blue-50 hover:text-blue-800"
          >
            <svg className="mr-1 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 4v16m8-8H4"
              />
            </svg>
            添加条件
          </button>
        </div>
      )}
    </div>
  );
}
