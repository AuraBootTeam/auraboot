import React, { useState, useCallback, useMemo } from 'react';
import type { FieldAdapter } from '~/ui/field-adapter';
import type { ConditionGroup, FieldOption } from './types';
import { ConditionBuilder } from './ConditionBuilder';
import { serialize, deserialize } from './serializer';
import { CONTEXT_VARIABLES } from './context-variables';
import { BaseFormulaEditor } from '~/ui/base-fields';

export interface ExpressionEditorProps {
  adapter: FieldAdapter<any>;
  name: string;
  label?: string;
  helpText?: string;
  modelFields?: FieldOption[];
}

type EditorMode = 'builder' | 'text';

function normalizeExpressionValue(value: unknown): string {
  if (typeof value === 'string') return value;
  if (value == null) return '';
  if (Array.isArray(value)) {
    return value.map((item) => String(item ?? '')).filter(Boolean).join(', ');
  }
  if (typeof value === 'object') {
    const expr = value as { content?: unknown; expression?: unknown };
    if (typeof expr.content === 'string') return expr.content;
    if (typeof expr.expression === 'string') return expr.expression;
    try {
      return JSON.stringify(value);
    } catch (_err) {
      return String(value);
    }
  }
  return String(value);
}

export function ExpressionEditor({ adapter, name, label, helpText, modelFields }: ExpressionEditorProps) {
  const currentExpr = normalizeExpressionValue(adapter.value);

  const initialGroup = useMemo(() => deserialize(currentExpr), []);
  const [mode, setMode] = useState<EditorMode>(
    currentExpr && !initialGroup ? 'text' : 'builder',
  );
  const [group, setGroup] = useState<ConditionGroup>(
    initialGroup ?? { operator: 'and', conditions: [] },
  );

  const allFields = useMemo<FieldOption[]>(() => {
    const fields: FieldOption[] = (modelFields ?? []).map((f) => ({
      ...f,
      group: 'Fields',
    }));
    return [...fields, ...CONTEXT_VARIABLES];
  }, [modelFields]);

  const handleGroupChange = useCallback(
    (newGroup: ConditionGroup) => {
      setGroup(newGroup);
      const expr = serialize(newGroup);
      adapter.setValue(expr);
    },
    [adapter],
  );

  const handleModeToggle = useCallback(() => {
    if (mode === 'builder') {
      setMode('text');
    } else {
      const parsed = deserialize(currentExpr);
      if (parsed) {
        setGroup(parsed);
        setMode('builder');
      } else if (!currentExpr.trim()) {
        setGroup({ operator: 'and', conditions: [] });
        setMode('builder');
      }
    }
  }, [mode, currentExpr]);

  const canSwitchToBuilder = !currentExpr.trim() || !!deserialize(currentExpr);

  return (
    <div className="mb-4" data-testid="expression-editor">
      {label && (
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {label}
          {adapter.required && <span className="ml-1 text-red-500">*</span>}
        </label>
      )}

      <div className="mb-2 flex items-center gap-2">
        <button
          className={`rounded px-2 py-0.5 text-[11px] font-medium ${
            mode === 'builder'
              ? 'bg-blue-100 text-blue-700'
              : 'bg-gray-100 text-gray-500 hover:bg-gray-200'
          }`}
          onClick={() => mode !== 'builder' && handleModeToggle()}
          disabled={mode === 'builder' || !canSwitchToBuilder}
          title={!canSwitchToBuilder ? 'Expression too complex for condition builder' : undefined}
          data-testid="mode-builder"
        >
          Conditions
        </button>
        <button
          className={`rounded px-2 py-0.5 text-[11px] font-medium ${
            mode === 'text'
              ? 'bg-blue-100 text-blue-700'
              : 'bg-gray-100 text-gray-500 hover:bg-gray-200'
          }`}
          onClick={() => mode !== 'text' && handleModeToggle()}
          data-testid="mode-text"
        >
          Expression
        </button>
      </div>

      {mode === 'builder' ? (
        <ConditionBuilder group={group} fields={allFields} onChange={handleGroupChange} />
      ) : (
        <BaseFormulaEditor
          adapter={adapter}
          name={name}
          placeholder="e.g. status === 'draft' && amount > 1000"
          helpText={helpText}
          fields={allFields.map((f) => ({ code: f.code, name: f.name }))}
        />
      )}

      {/* Field-level validation error (P0-4): the text/formula mode surfaces it via
          BaseFormulaEditor, but the builder mode must render it too so required
          expression fields show an inline error, not just the required marker. */}
      {mode === 'builder' && adapter.error && (
        <p className="mt-1 text-sm text-red-600">{adapter.error}</p>
      )}
      {mode === 'builder' && !adapter.error && helpText && (
        <p className="mt-1 text-xs text-gray-500">{helpText}</p>
      )}
    </div>
  );
}
