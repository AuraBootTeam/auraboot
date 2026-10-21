import { useEffect, useMemo, useRef, useState } from 'react';
import { getApiService } from '~/shared/services/ApiService';
import {
  createDecisionApi,
  type ConditionTrace,
  type DecisionResult,
  type ScopedContext,
} from '../api/decisionApi';
import { UNARY_OPERATORS, type GroupNode, type PathOperand } from '../ast/conditionAst';
import type { FieldOption } from './ConditionBuilder';
import { compatibleFields, conditionLeaves, operandKey, nodeKey } from './conditionTreeEditing';
import { useDecisionConditionText, decisionConditionOperatorLabel } from './decisionConditionText';

export function conditionConfigurationValid(condition: GroupNode, fields: FieldOption[]): boolean {
  const byKey = new Map(fields.map((field) => [operandKey(field), field]));
  const valid = (node: GroupNode['children'][number]): boolean => {
    if (node.type === 'group') return node.children.length > 0 && node.children.every(valid);
    if (node.type === 'not') return valid(node.child);
    if (node.enabled === false) return true;
    if (node.left.type !== 'path') return false;
    const field = byKey.get(operandKey(node.left));
    if (!field || field.masked || field.visible === false) return false;
    if (UNARY_OPERATORS.has(node.operator)) return true;
    const right = node.right;
    if (right?.type === 'path') {
      const rhs = byKey.get(operandKey(right));
      return Boolean(rhs && compatibleFields(field, rhs));
    }
    if (right?.type !== 'literal') return false;
    const values = Array.isArray(right.value) ? right.value : [right.value];
    if (!values.length || values.some((value) => value === '' || value == null)) return false;
    if (
      ['integer', 'decimal'].includes(field.dataType) &&
      values.some((value) => !Number.isFinite(Number(value)))
    )
      return false;
    if (field.options && values.some((value) => !field.options!.includes(String(value))))
      return false;
    if (node.operator === 'BETWEEN')
      return (
        values.length === 2 &&
        (['integer', 'decimal'].includes(field.dataType)
          ? Number(values[0]) <= Number(values[1])
          : String(values[0]) <= String(values[1]))
      );
    return true;
  };
  return condition.children.length > 0 && valid(condition);
}

function putContext(context: ScopedContext, field: FieldOption, value: unknown): void {
  const parts = field.path.split('.');
  if (parts.some((part) => !part || ['__proto__', 'constructor', 'prototype'].includes(part)))
    throw new Error('Invalid context path');
  let target = (context[field.scope] ??= {}) as Record<string, unknown>;
  for (const part of parts.slice(0, -1)) target = (target[part] ??= {}) as Record<string, unknown>;
  target[parts[parts.length - 1]] = value;
}

export function ConditionServerTrial({
  condition,
  fields,
  onTrace,
}: {
  condition: GroupNode;
  fields: FieldOption[];
  onTrace: (trace?: ConditionTrace) => void;
}) {
  const { locale, text } = useDecisionConditionText();
  const [values, setValues] = useState<Record<string, string>>({});
  const [result, setResult] = useState<DecisionResult>();
  const [error, setError] = useState('');
  const [running, setRunning] = useState(false);
  const [attempted, setAttempted] = useState(false);
  const identity = JSON.stringify({ condition, values });
  const currentIdentity = useRef(identity);
  currentIdentity.current = identity;
  const generation = useRef(0);
  const leaves = conditionLeaves(condition);
  const referencedFields = useMemo(() => {
    const refs = new Set<string>();
    conditionLeaves(condition).forEach(({ node }) =>
      [node.left, node.right].forEach((operand) => {
        if (operand?.type === 'path') refs.add(operandKey(operand));
      }),
    );
    return fields.filter((field) => refs.has(operandKey(field)));
  }, [condition, fields]);
  useEffect(() => {
    setResult(undefined);
    setError('');
    setRunning(false);
    generation.current++;
    onTrace(undefined);
  }, [identity]);
  useEffect(
    () => () => {
      generation.current++;
    },
    [],
  );
  const run = async () => {
    setAttempted(true);
    setError('');
    setResult(undefined);
    onTrace(undefined);
    if (!conditionConfigurationValid(condition, fields)) {
      setError(text('invalidCondition'));
      return;
    }
    const requestIdentity = identity;
    const ticket = ++generation.current;
    setRunning(true);
    try {
      const context: ScopedContext = {};
      referencedFields.forEach((field) => {
        const raw = values[operandKey(field)];
        if (raw === undefined || raw === '') return;
        putContext(
          context,
          field,
          ['integer', 'decimal'].includes(field.dataType)
            ? Number(raw)
            : field.dataType === 'boolean'
              ? raw === 'true'
              : raw,
        );
      });
      const response = await createDecisionApi(getApiService()).testRun({
        kind: 'SIMPLE_CONDITION',
        runtimeAdapter: 'AST_EVALUATOR',
        contentJson: condition,
        context,
        explain: true,
      });
      if (ticket !== generation.current || requestIdentity !== currentIdentity.current) return;
      if (response.status === 'ERROR')
        throw new Error(response.errors?.join('; ') || response.status);
      if (!response.conditionTrace) throw new Error(text('noTrace'));
      setResult(response);
      onTrace(response.conditionTrace);
    } catch (cause) {
      if (ticket === generation.current && requestIdentity === currentIdentity.current)
        setError(
          text('trialFailure', { message: cause instanceof Error ? cause.message : String(cause) }),
        );
    } finally {
      if (ticket === generation.current) setRunning(false);
    }
  };
  const truthLabel = (value: string) =>
    text(
      value === 'TRUE' ? 'trialMatched' : value === 'FALSE' ? 'trialNotMatched' : 'trialUnknown',
    );
  const format = (value: unknown, field?: FieldOption): string =>
    value == null
      ? text('missingInput')
      : Array.isArray(value)
        ? value.map((item) => format(item, field)).join(text('listSeparator'))
        : typeof value === 'boolean'
          ? text(value ? 'trueValue' : 'falseValue')
          : (field?.valueLabels?.[String(value)] ?? String(value));
  return (
    <aside className="ce-trial" data-testid="condition-server-trial">
      <div className="ce-trial-title">
        <strong>{text('serverTrial')}</strong>
        <small>{text('noSideEffects')}</small>
      </div>
      <button
        type="button"
        data-testid="condition-server-run"
        className="ce-primary"
        disabled={running}
        onClick={run}
      >
        {text(running ? 'trialPending' : 'runTrial')}
      </button>
      <div data-testid="condition-server-result" aria-live="polite">
        {error ? (
          <div className="ce-trial-error" role="alert">
            {error}
          </div>
        ) : result?.conditionTrace ? (
          <div className={`ce-trial-result ce-${result.conditionTrace.result.toLowerCase()}`}>
            <strong>{truthLabel(result.conditionTrace.result)}</strong>
            <small>{text('serverEvidence')}</small>
          </div>
        ) : (
          <p className="ce-trial-empty">{text(attempted ? 'trialStale' : 'trialEmpty')}</p>
        )}
      </div>
      <div className="ce-trial-fields">
        {referencedFields.map((field) => (
          <label key={operandKey(field)}>
            <span>{field.label}</span>
            <small>{field.modelName ?? field.modelCode ?? field.scope}</small>
            {field.options || field.dataType === 'boolean' ? (
              <select
                aria-label={`trial-${operandKey(field)}`}
                value={values[operandKey(field)] ?? ''}
                onChange={(event) =>
                  setValues({ ...values, [operandKey(field)]: event.target.value })
                }
              >
                <option value="">{text('missingInput')}</option>
                {field.dataType === 'boolean' ? (
                  <>
                    <option value="true">{text('trueValue')}</option>
                    <option value="false">{text('falseValue')}</option>
                  </>
                ) : (
                  field.options?.map((value) => (
                    <option key={value} value={value}>
                      {field.valueLabels?.[value] ?? value}
                    </option>
                  ))
                )}
              </select>
            ) : (
              <input
                aria-label={`trial-${operandKey(field)}`}
                type={
                  ['integer', 'decimal'].includes(field.dataType)
                    ? 'number'
                    : field.dataType === 'date'
                      ? 'date'
                      : field.dataType === 'datetime'
                        ? 'datetime-local'
                        : 'text'
                }
                step="any"
                placeholder={text('missingInput')}
                value={values[operandKey(field)] ?? ''}
                onChange={(event) =>
                  setValues({ ...values, [operandKey(field)]: event.target.value })
                }
              />
            )}
          </label>
        ))}
      </div>
      {result?.conditionTrace && (
        <details className="ce-evidence" open>
          <summary>{text('serverEvidence')}</summary>
          {leaves.map(({ node, parts }, index) => {
            const field =
              node.left.type === 'path'
                ? fields.find((field) => operandKey(field) === operandKey(node.left as PathOperand))
                : undefined;
            const rightField =
              node.right?.type === 'path'
                ? fields.find(
                    (field) => operandKey(field) === operandKey(node.right as PathOperand),
                  )
                : undefined;
            const step = result.conditionTrace!.steps[index];
            const truth = result.conditionTrace!.nodeResults[nodeKey(parts)];
            return (
              <div key={nodeKey(parts)} className="ce-evidence-row">
                <div className="ce-evidence-heading">
                  <strong>
                    {parts.map((part) => part + 1).join('.')} · {field?.label}
                  </strong>
                  <b className={`ce-${truth?.toLowerCase()}`}>{truthLabel(truth)}</b>
                </div>
                <span>
                  {decisionConditionOperatorLabel(node.operator, locale)}{' '}
                  {rightField?.label ??
                    (node.right?.type === 'literal' ? format(node.right.value, field) : '')}
                </span>
                {step && (
                  <div className="ce-evidence-comparison">
                    <small>{text('actualComparison')}</small>
                    <span>
                      {format(step.left, field)}{' '}
                      {decisionConditionOperatorLabel(node.operator, locale)}{' '}
                      {UNARY_OPERATORS.has(node.operator)
                        ? ''
                        : format(step.right, rightField ?? field)}
                    </span>
                  </div>
                )}
              </div>
            );
          })}
        </details>
      )}
      <p className="ce-scope-hint">{text('trialScopeHint')}</p>
    </aside>
  );
}
