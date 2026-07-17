import React, { useState, useCallback, useMemo } from 'react';
import type { FieldAdapter } from '~/ui/field-adapter';
import type { ConditionGroup, FieldOption } from './types';
import { ConditionBuilder } from './ConditionBuilder';
import { serialize, deserialize } from './serializer';
import { CONTEXT_VARIABLES } from './context-variables';
import { BaseFormulaEditor } from '~/ui/base-fields';
import { useSmartText } from '~/utils/i18n';

export interface ExpressionEditorProps {
  adapter: FieldAdapter<any>;
  name: string;
  label?: string;
  helpText?: string;
  modelFields?: FieldOption[];
}

type EditorMode = 'builder' | 'text';

const CONTEXT_VARIABLE_LABEL_KEYS: Record<string, string> = {
  '$user.id': '$i18n:expression.variable.userId',
  '$user.name': '$i18n:expression.variable.userName',
  '$user.email': '$i18n:expression.variable.userEmail',
  '$user.roles': '$i18n:expression.variable.userRoles',
  '$user.permissions': '$i18n:expression.variable.userPermissions',
  '$form.mode': '$i18n:expression.variable.formMode',
  '$page.kind': '$i18n:expression.variable.pageKind',
  '$page.modelCode': '$i18n:expression.variable.pageModelCode',
  '$page.pageKey': '$i18n:expression.variable.pageKey',
  '$page.mode': '$i18n:expression.variable.pageMode',
  '$page.recordPid': '$i18n:expression.variable.currentRecordPid',
  '$record.pid': '$i18n:expression.variable.recordPid',
  '$state.filters': '$i18n:expression.variable.activeFilters',
  '$state.selectedPids': '$i18n:expression.variable.selectedRowPids',
};

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
  const st = useSmartText();
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
      group: f.group || st('$i18n:expression.fieldGroup.fields', '字段'),
    }));
    const contextFields = CONTEXT_VARIABLES.map((field) => ({
      ...field,
      name: st(CONTEXT_VARIABLE_LABEL_KEYS[field.code] || field.name, field.name) || field.name,
    }));
    return dedupeFields([...fields, ...contextFields]);
  }, [modelFields, st]);

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
          title={
            !canSwitchToBuilder
              ? st(
                  '$i18n:expression.tooComplexForBuilder',
                  '当前表达式过于复杂，无法切换为条件构造器',
                )
              : undefined
          }
          data-testid="mode-builder"
        >
          {st('$i18n:expression.mode.conditions', '条件')}
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
          {st('$i18n:expression.mode.expression', '表达式')}
        </button>
      </div>

      {mode === 'builder' ? (
        <ConditionBuilder group={group} fields={allFields} onChange={handleGroupChange} />
      ) : (
        <BaseFormulaEditor
          adapter={adapter}
          name={name}
          placeholder={st(
            '$i18n:expression.placeholder.formula',
            "例如 status === 'draft' && amount > 1000",
          )}
          helpText={helpText}
          fields={allFields.map((f) => ({
            code: f.code,
            name: f.name,
            group: f.group,
            insertion: f.insertion,
          }))}
          showHelp={false}
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

function dedupeFields(fields: FieldOption[]): FieldOption[] {
  const seen = new Set<string>();
  const result: FieldOption[] = [];
  for (const field of fields) {
    if (seen.has(field.code)) continue;
    seen.add(field.code);
    result.push(field);
  }
  return result;
}
