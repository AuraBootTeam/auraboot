/**
 * Dual-mode condition expression editor for BPMN sequence flows.
 *
 * Simple mode: visual rule builder with field/operator/value rows and AND/OR toggle.
 * Advanced mode: raw textarea with type/language selectors.
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ConditionExpression } from '~/plugins/core-designer/components/bpmn-designer/types';
import { useI18n } from '~/contexts/I18nContext';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface ConditionRule {
  field: string;
  operator: string;
  value: string;
}

type LogicalOperator = 'and' | 'or';
type EditorMode = 'simple' | 'advanced';

interface ConditionExpressionEditorProps {
  condition?: ConditionExpression;
  onChange: (condition: ConditionExpression) => void;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const OPERATORS: { value: string; label: string; i18nKey?: string }[] = [
  { value: '==', label: '==' },
  { value: '!=', label: '!=' },
  { value: '>', label: '>' },
  { value: '<', label: '<' },
  { value: '>=', label: '>=' },
  { value: '<=', label: '<=' },
  { value: 'contains', label: 'contains', i18nKey: 'bpmn.condition.op.contains' },
  { value: 'is_empty', label: 'is_empty', i18nKey: 'bpmn.condition.op.isEmpty' },
  { value: 'is_not_empty', label: 'is_not_empty', i18nKey: 'bpmn.condition.op.isNotEmpty' },
];

const UNARY_OPERATORS = new Set(['is_empty', 'is_not_empty']);

const LANGUAGES: { value: string; label: string }[] = [
  { value: 'javascript', label: 'JavaScript' },
  { value: 'groovy', label: 'Groovy' },
  { value: 'juel', label: 'JUEL' },
];

// ---------------------------------------------------------------------------
// Helpers — expression ↔ rules conversion
// ---------------------------------------------------------------------------

/** Build an expression string like `${amount > 1000 && status == 'approved'}` from rules. */
function rulesToExpression(rules: ConditionRule[], logicalOp: LogicalOperator): string {
  if (rules.length === 0) return '';

  const connector = logicalOp === 'and' ? ' && ' : ' || ';

  const parts = rules.map((r) => {
    if (!r.field) return '';
    if (r.operator === 'is_empty') return `empty ${r.field}`;
    if (r.operator === 'is_not_empty') return `!empty ${r.field}`;
    if (r.operator === 'contains') return `${r.field}.contains('${r.value}')`;

    // Wrap value in quotes if it looks non-numeric
    const val = isNumericLike(r.value) ? r.value : `'${r.value}'`;
    return `${r.field} ${r.operator} ${val}`;
  });

  const body = parts.filter(Boolean).join(connector);
  return body ? `\${${body}}` : '';
}

function isNumericLike(v: string): boolean {
  if (!v) return false;
  return /^-?\d+(\.\d+)?$/.test(v);
}

/** Best-effort parse of a simple expression back into rules + logical operator. */
function tryParseRules(
  expr: string,
): { rules: ConditionRule[]; logicalOp: LogicalOperator } | null {
  if (!expr) return null;

  // Strip ${...} wrapper
  let body = expr.trim();
  if (body.startsWith('${') && body.endsWith('}')) {
    body = body.slice(2, -1).trim();
  } else {
    return null;
  }

  if (!body) return null;

  // Determine logical operator
  const hasAnd = body.includes('&&');
  const hasOr = body.includes('||');
  if (hasAnd && hasOr) return null; // mixed operators — too complex

  const logicalOp: LogicalOperator = hasOr ? 'or' : 'and';
  const separator = hasOr ? '||' : '&&';

  const segments = body.split(separator).map((s) => s.trim());
  const rules: ConditionRule[] = [];

  for (const seg of segments) {
    const rule = parseSegment(seg);
    if (!rule) return null;
    rules.push(rule);
  }

  return rules.length > 0 ? { rules, logicalOp } : null;
}

function parseSegment(seg: string): ConditionRule | null {
  // empty field
  const emptyMatch = seg.match(/^empty\s+(\w+)$/);
  if (emptyMatch) return { field: emptyMatch[1], operator: 'is_empty', value: '' };

  const notEmptyMatch = seg.match(/^!empty\s+(\w+)$/);
  if (notEmptyMatch) return { field: notEmptyMatch[1], operator: 'is_not_empty', value: '' };

  // contains
  const containsMatch = seg.match(/^(\w+)\.contains\(\s*'([^']*)'\s*\)$/);
  if (containsMatch) return { field: containsMatch[1], operator: 'contains', value: containsMatch[2] };

  // comparison operators (longest first to match >= before >)
  const cmpMatch = seg.match(/^(\w+)\s*(>=|<=|!=|==|>|<)\s*(.+)$/);
  if (cmpMatch) {
    let val = cmpMatch[3].trim();
    // Strip surrounding quotes
    if ((val.startsWith("'") && val.endsWith("'")) || (val.startsWith('"') && val.endsWith('"'))) {
      val = val.slice(1, -1);
    }
    return { field: cmpMatch[1], operator: cmpMatch[2], value: val };
  }

  return null;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function ConditionExpressionEditor({ condition, onChange }: ConditionExpressionEditorProps) {
  const { t } = useI18n();

  // Determine initial mode: if we can parse existing expression into rules, use simple; otherwise advanced
  const initialParse = useMemo(
    () => tryParseRules(condition?.content || ''),
    // Only compute on mount — not reactive to condition changes
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  const [mode, setMode] = useState<EditorMode>(
    initialParse || !condition?.content ? 'simple' : 'advanced',
  );

  // Simple mode state
  const [rules, setRules] = useState<ConditionRule[]>(
    initialParse?.rules || [{ field: '', operator: '==', value: '' }],
  );
  const [logicalOp, setLogicalOp] = useState<LogicalOperator>(initialParse?.logicalOp || 'and');

  // Advanced mode state
  const [content, setContent] = useState(condition?.content || '');
  const [type, setType] = useState<ConditionExpression['type']>(condition?.type || 'expression');
  const [language, setLanguage] = useState<ConditionExpression['language']>(
    condition?.language || 'javascript',
  );

  // Parse warning when switching from advanced to simple fails
  const [parseWarning, setParseWarning] = useState(false);

  // Sync simple mode changes to parent
  const syncSimple = useCallback(
    (nextRules: ConditionRule[], nextOp: LogicalOperator) => {
      const expr = rulesToExpression(nextRules, nextOp);
      onChange({
        type: 'expression',
        content: expr,
        language: undefined,
        ruleCode: condition?.ruleCode,
      });
    },
    [onChange, condition?.ruleCode],
  );

  // Sync advanced mode changes to parent
  const syncAdvanced = useCallback(
    (nextContent: string, nextType: ConditionExpression['type'], nextLang?: ConditionExpression['language']) => {
      onChange({
        type: nextType,
        content: nextContent,
        language: nextType === 'script' ? nextLang : undefined,
        ruleCode: condition?.ruleCode,
      });
    },
    [onChange, condition?.ruleCode],
  );

  // ---------------------------------------------------------------------------
  // Mode switching
  // ---------------------------------------------------------------------------

  const switchToAdvanced = useCallback(() => {
    const expr = rulesToExpression(rules, logicalOp);
    setContent(expr);
    setType('expression');
    setParseWarning(false);
    setMode('advanced');
  }, [rules, logicalOp]);

  const switchToSimple = useCallback(() => {
    const parsed = tryParseRules(content);
    if (parsed) {
      setRules(parsed.rules);
      setLogicalOp(parsed.logicalOp);
      setParseWarning(false);
      setMode('simple');
    } else {
      setParseWarning(true);
    }
  }, [content]);

  // ---------------------------------------------------------------------------
  // Rule handlers
  // ---------------------------------------------------------------------------

  const updateRule = useCallback(
    (index: number, field: keyof ConditionRule, value: string) => {
      const next = rules.map((r, i) => (i === index ? { ...r, [field]: value } : r));
      setRules(next);
      syncSimple(next, logicalOp);
    },
    [rules, logicalOp, syncSimple],
  );

  const addRule = useCallback(() => {
    const next = [...rules, { field: '', operator: '==', value: '' }];
    setRules(next);
    syncSimple(next, logicalOp);
  }, [rules, logicalOp, syncSimple]);

  const removeRule = useCallback(
    (index: number) => {
      const next = rules.filter((_, i) => i !== index);
      const final = next.length === 0 ? [{ field: '', operator: '==', value: '' }] : next;
      setRules(final);
      syncSimple(final, logicalOp);
    },
    [rules, logicalOp, syncSimple],
  );

  const toggleLogicalOp = useCallback(() => {
    const next: LogicalOperator = logicalOp === 'and' ? 'or' : 'and';
    setLogicalOp(next);
    syncSimple(rules, next);
  }, [logicalOp, rules, syncSimple]);

  // ---------------------------------------------------------------------------
  // Advanced handlers
  // ---------------------------------------------------------------------------

  const handleContentChange = useCallback(
    (val: string) => {
      setContent(val);
      setParseWarning(false);
      syncAdvanced(val, type, language);
    },
    [type, language, syncAdvanced],
  );

  const handleTypeChange = useCallback(
    (val: ConditionExpression['type']) => {
      setType(val);
      syncAdvanced(content, val, language);
    },
    [content, language, syncAdvanced],
  );

  const handleLanguageChange = useCallback(
    (val: ConditionExpression['language']) => {
      setLanguage(val);
      syncAdvanced(content, type, val);
    },
    [content, type, syncAdvanced],
  );

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <div className="space-y-3">
      {/* Mode tabs */}
      <div className="flex rounded-md border border-gray-300">
        <button
          type="button"
          onClick={mode === 'simple' ? undefined : switchToSimple}
          className={`flex-1 px-3 py-1.5 text-xs font-medium transition-colors ${
            mode === 'simple'
              ? 'bg-blue-50 text-blue-700'
              : 'bg-white text-gray-600 hover:bg-gray-50'
          } rounded-l-md`}
        >
          {t('bpmn.condition.simpleMode')}
        </button>
        <button
          type="button"
          onClick={mode === 'advanced' ? undefined : switchToAdvanced}
          className={`flex-1 px-3 py-1.5 text-xs font-medium transition-colors ${
            mode === 'advanced'
              ? 'bg-blue-50 text-blue-700'
              : 'bg-white text-gray-600 hover:bg-gray-50'
          } rounded-r-md border-l border-gray-300`}
        >
          {t('bpmn.condition.advancedMode')}
        </button>
      </div>

      {/* Parse warning */}
      {parseWarning && (
        <div className="rounded-md bg-amber-50 px-3 py-2 text-xs text-amber-700">
          {t('bpmn.condition.parseWarning')}
        </div>
      )}

      {/* Simple mode */}
      {mode === 'simple' && (
        <div className="space-y-2">
          {rules.map((rule, index) => (
            <div key={index}>
              {/* Logical operator connector between rules */}
              {index > 0 && (
                <div className="flex items-center justify-center py-1">
                  <button
                    type="button"
                    onClick={toggleLogicalOp}
                    className="rounded-full bg-gray-100 px-3 py-0.5 text-xs font-medium text-gray-600 hover:bg-gray-200"
                  >
                    {logicalOp === 'and' ? 'AND' : 'OR'}
                  </button>
                </div>
              )}

              {/* Rule row */}
              <div className="flex items-start gap-1">
                <div className="min-w-0 flex-1 space-y-1">
                  {/* Field */}
                  <input
                    type="text"
                    value={rule.field}
                    onChange={(e) => updateRule(index, 'field', e.target.value)}
                    className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-xs"
                    placeholder={t('bpmn.condition.fieldPlaceholder')}
                  />

                  {/* Operator */}
                  <select
                    value={rule.operator}
                    onChange={(e) => updateRule(index, 'operator', e.target.value)}
                    className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-xs"
                  >
                    {OPERATORS.map((op) => (
                      <option key={op.value} value={op.value}>
                        {op.i18nKey ? t(op.i18nKey) : op.label}
                      </option>
                    ))}
                  </select>

                  {/* Value (hidden for unary operators) */}
                  {!UNARY_OPERATORS.has(rule.operator) && (
                    <input
                      type="text"
                      value={rule.value}
                      onChange={(e) => updateRule(index, 'value', e.target.value)}
                      className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-xs"
                      placeholder={t('bpmn.condition.valuePlaceholder')}
                    />
                  )}
                </div>

                {/* Remove button */}
                <button
                  type="button"
                  onClick={() => removeRule(index)}
                  className="mt-1 shrink-0 rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-red-500"
                  title={t('bpmn.condition.removeRule')}
                >
                  <svg
                    xmlns="http://www.w3.org/2000/svg"
                    viewBox="0 0 16 16"
                    fill="currentColor"
                    className="h-3.5 w-3.5"
                  >
                    <path d="M5.28 4.22a.75.75 0 0 0-1.06 1.06L6.94 8l-2.72 2.72a.75.75 0 1 0 1.06 1.06L8 9.06l2.72 2.72a.75.75 0 1 0 1.06-1.06L9.06 8l2.72-2.72a.75.75 0 0 0-1.06-1.06L8 6.94 5.28 4.22Z" />
                  </svg>
                </button>
              </div>
            </div>
          ))}

          {/* Add rule button */}
          <button
            type="button"
            onClick={addRule}
            className="w-full rounded-md border border-dashed border-blue-300 py-1.5 text-xs text-blue-600 hover:bg-blue-50 hover:text-blue-800"
          >
            {t('bpmn.condition.addRule')}
          </button>

          {/* Preview */}
          {rules.some((r) => r.field) && (
            <div className="rounded-md bg-gray-50 px-2 py-1.5">
              <p className="mb-0.5 text-[10px] font-medium text-gray-500">{t('bpmn.condition.preview')}</p>
              <code className="block break-all text-[11px] text-gray-700">
                {rulesToExpression(rules, logicalOp)}
              </code>
            </div>
          )}
        </div>
      )}

      {/* Advanced mode */}
      {mode === 'advanced' && (
        <div className="space-y-2">
          {/* Type + Language selectors */}
          <div className="flex gap-2">
            <div className="flex-1">
              <label className="mb-0.5 block text-[10px] font-medium text-gray-500">{t('bpmn.condition.type')}</label>
              <select
                value={type}
                onChange={(e) => handleTypeChange(e.target.value as ConditionExpression['type'])}
                className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-xs"
              >
                <option value="expression">{t('bpmn.condition.typeExpression')}</option>
                <option value="script">{t('bpmn.condition.typeScript')}</option>
              </select>
            </div>

            {type === 'script' && (
              <div className="flex-1">
                <label className="mb-0.5 block text-[10px] font-medium text-gray-500">{t('bpmn.condition.language')}</label>
                <select
                  value={language || 'javascript'}
                  onChange={(e) =>
                    handleLanguageChange(e.target.value as ConditionExpression['language'])
                  }
                  className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-xs"
                >
                  {LANGUAGES.map((lang) => (
                    <option key={lang.value} value={lang.value}>
                      {lang.label}
                    </option>
                  ))}
                </select>
              </div>
            )}
          </div>

          {/* Textarea */}
          <textarea
            value={content}
            onChange={(e) => handleContentChange(e.target.value)}
            className="w-full rounded-md border border-gray-300 px-2 py-2 font-mono text-xs leading-relaxed"
            rows={4}
            placeholder={t('bpmn.condition.advancedPlaceholder')}
          />

          {/* Help text */}
          <p className="text-[10px] leading-relaxed text-gray-400">
            {t('bpmn.condition.helpText')}
          </p>
        </div>
      )}
    </div>
  );
}
