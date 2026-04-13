import React, { useState, useCallback, useMemo } from 'react';
import type { FieldAdapter } from '~/components/field-adapter';
import type { ConditionGroup, FieldOption } from './types';
import { ConditionBuilder } from './ConditionBuilder';
import { serialize, deserialize } from './serializer';
import { CONTEXT_VARIABLES } from './context-variables';
import { BaseFormulaEditor } from '~/components/base-fields';

export interface ExpressionEditorProps {
  adapter: FieldAdapter<any>;
  name: string;
  label?: string;
  helpText?: string;
  modelFields?: FieldOption[];
}

type EditorMode = 'builder' | 'text';

export function ExpressionEditor({ adapter, name, label, helpText, modelFields }: ExpressionEditorProps) {
  const currentExpr = (adapter.value as string) ?? '';

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

      {mode === 'builder' && helpText && (
        <p className="mt-1 text-xs text-gray-500">{helpText}</p>
      )}
    </div>
  );
}
