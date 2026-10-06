import {
  useDecisionConditionText,
  decisionConditionText,
  decisionConditionOperatorLabel,
  type DecisionConditionTextKey,
} from './decisionConditionText';
import { useMemo, useState } from 'react';
import {
  type ConditionNode,
  type CompareNode,
  type Operand,
  type ScopedContext,
  type Truth,
  type PathOperand,
  evaluatePreview,
  UNARY_OPERATORS,
} from '../ast/conditionAst';
import type { FieldOption } from './ConditionBuilder';
import { valueLabel } from './displayLabels';

/**
 * DecisionOps client-side test-run preview (test-run mockup step, docs/1.md §17.2): evaluate a
 * Condition AST against named sample contexts and show the three-valued result (TRUE/FALSE/UNKNOWN).
 * Preview only — the backend test-run/evaluate remains authoritative (front-end never decides).
 */

export interface TestSample {
  label: string;
  context: ScopedContext;
  executionContext?: () => ScopedContext;
}

export interface ConditionTestRunPanelProps {
  condition: ConditionNode;
  samples: TestSample[];
  fields?: FieldOption[];
  labelOf?: (o: PathOperand) => string;
  emptyPreviewLabel?: string;
}

const TRUTH_LABEL: Record<Truth, DecisionConditionTextKey> = {
  TRUE: 'truthTrue',
  FALSE: 'truthFalse',
  UNKNOWN: 'truthUnknown',
};
const TRUTH_CLASS: Record<Truth, string> = {
  TRUE: 'truth-true',
  FALSE: 'truth-false',
  UNKNOWN: 'truth-unknown',
};

function fieldKey(scope: string, path: string): string {
  return `${scope}:${path}`;
}

function resolvePath(root: unknown, path: string): unknown {
  const parts = path.split('.').filter(Boolean);
  return parts.reduce<unknown>((current, part) => {
    if (current == null || typeof current !== 'object') return undefined;
    return (current as Record<string, unknown>)[part];
  }, root);
}

function sampleValue(sample: TestSample | undefined, field: FieldOption): unknown {
  if (!sample) return undefined;
  const scoped = sample.context[field.scope];
  const normalizedPath = field.path.startsWith(`${field.scope}.`)
    ? field.path.slice(field.scope.length + 1)
    : field.path;
  return resolvePath(scoped, normalizedPath);
}

function operandPreview(
  operand: Operand | undefined,
  fieldByKey: Map<string, FieldOption>,
  labelOf?: (o: PathOperand) => string,
  leftField?: FieldOption,
  locale: string = 'zh-CN',
): string {
  if (!operand) return '';
  if (operand.type === 'path') {
    return (
      labelOf?.(operand) ??
      fieldByKey.get(fieldKey(operand.scope, operand.path))?.label ??
      `${operand.scope}.${operand.path}`
    );
  }
  if (operand.type === 'literal') {
    if (Array.isArray(operand.value)) {
      return operand.value
        .map((item) => valueLabel(item, leftField?.valueLabels))
        .join(decisionConditionText('listSeparator', locale));
    }
    return valueLabel(operand.value, leftField?.valueLabels);
  }
  return `${operand.name}(...)`;
}

function previewNode(
  node: ConditionNode,
  fieldByKey: Map<string, FieldOption>,
  labelOf?: (o: PathOperand) => string,
  locale: string = 'zh-CN',
): string {
  if (node.type === 'compare') {
    const compare = node as CompareNode;
    const leftField =
      compare.left.type === 'path'
        ? fieldByKey.get(fieldKey(compare.left.scope, compare.left.path))
        : undefined;
    const left = operandPreview(compare.left, fieldByKey, labelOf, leftField, locale);
    const right = UNARY_OPERATORS.has(compare.operator)
      ? ''
      : ` ${operandPreview(compare.right, fieldByKey, labelOf, leftField, locale)}`;
    return `【${left} ${decisionConditionOperatorLabel(compare.operator, locale)}${right}】`;
  }
  if (node.type === 'not')
    return decisionConditionText('negated', locale, {
      condition: previewNode(node.child, fieldByKey, labelOf, locale),
    });
  const parts = node.children
    .filter((child) => !(child.type === 'compare' && child.enabled === false))
    .map((child) => previewNode(child, fieldByKey, labelOf, locale));
  return `(${parts.join(node.op === 'AND' ? decisionConditionText('andSeparator', locale) : decisionConditionText('orSeparator', locale))})`;
}

export function ConditionTestRunPanel({
  condition,
  samples,
  fields = [],
  labelOf,
  emptyPreviewLabel,
}: ConditionTestRunPanelProps) {
  const { locale, text } = useDecisionConditionText();
  const [selected, setSelected] = useState(0);
  const sample = samples[selected];
  const fieldByKey = useMemo(() => {
    const next = new Map<string, FieldOption>();
    fields.forEach((field) => next.set(fieldKey(field.scope, field.path), field));
    return next;
  }, [fields]);
  const naturalLanguage = previewNode(condition, fieldByKey, labelOf, locale);
  const useEmptyPreviewLabel = Boolean(emptyPreviewLabel) && naturalLanguage.trim() === '()';
  const result: Truth | null =
    sample && !useEmptyPreviewLabel ? evaluatePreview(condition, sample.context) : null;
  const contextEntries = fields
    .map((field) => ({ field, value: sampleValue(sample, field) }))
    .filter((entry) => entry.value !== undefined);

  return (
    <div data-testid="condition-testrun">
      <div className="trp-samples">
        {samples.map((s, i) => (
          <button
            type="button"
            key={s.label}
            data-testid={`sample-${i}`}
            aria-pressed={i === selected}
            onClick={() => setSelected(i)}
          >
            {s.label}
          </button>
        ))}
      </div>

      <div className="trp-nl" data-testid="trp-nl">
        {useEmptyPreviewLabel ? emptyPreviewLabel : naturalLanguage}
      </div>

      {contextEntries.length > 0 && (
        <dl className="trp-context" data-testid="trp-context">
          {contextEntries.map(({ field, value }) => (
            <div key={fieldKey(field.scope, field.path)}>
              <dt>{field.label}</dt>
              <dd>{valueLabel(value, field.valueLabels)}</dd>
            </div>
          ))}
        </dl>
      )}

      {result && (
        <div
          data-testid="trp-result"
          data-truth={result}
          className={`trp-result ${TRUTH_CLASS[result]}`}
        >
          {text(TRUTH_LABEL[result])}
        </div>
      )}
      <div data-testid="trp-note" className="trp-note">
        {text('previewNote')}
      </div>
    </div>
  );
}

export default ConditionTestRunPanel;
