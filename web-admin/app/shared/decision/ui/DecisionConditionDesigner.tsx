import { useState } from 'react';
import { ConditionBuilder, type FieldOption } from './ConditionBuilder';
import { ConditionTestRunPanel, type TestSample } from './ConditionTestRunPanel';
import { group, serialize, type GroupNode, type PathOperand } from '../ast/conditionAst';
import type { DecisionApi, EventPolicySummary, ValidateResult } from '../api/decisionApi';

/**
 * DecisionOps condition designer (mockup 策略设计器 / F3, docs/1.md §14, §17): composes the
 * ConditionBuilder (author the AST), client-side ConditionTestRunPanel (preview against samples),
 * and a backend Validate action (authoritative). The capstone tying the authoring stack to the API.
 */

export interface DecisionConditionDesignerProps {
  api: DecisionApi;
  fields: FieldOption[];
  samples?: TestSample[];
  initial?: GroupNode;
  labelOf?: (o: PathOperand) => string;
  selectedPolicy?: EventPolicySummary | null;
}

export function DecisionConditionDesigner({
  api,
  fields,
  samples = [],
  initial,
  labelOf,
  selectedPolicy,
}: DecisionConditionDesignerProps) {
  const [condition, setCondition] = useState<GroupNode>(initial ?? group('AND', []));
  const [validation, setValidation] = useState<ValidateResult | null>(null);
  const [validating, setValidating] = useState(false);

  const onValidate = async () => {
    setValidating(true);
    try {
      const result = await api.validate('SIMPLE_CONDITION', 'AST_EVALUATOR', condition);
      setValidation(result);
    } finally {
      setValidating(false);
    }
  };

  return (
    <div data-testid="condition-designer">
      {selectedPolicy && (
        <div className="dcd-policy-context" data-testid="dcd-policy-context">
          <strong>{selectedPolicy.policyName ?? selectedPolicy.policyCode}</strong>
          <span className="mono">{selectedPolicy.policyCode}</span>
          <span>{selectedPolicy.eventType ?? '-'}</span>
          <span>{selectedPolicy.targetType ?? '-'}:{selectedPolicy.targetKey ?? '-'}</span>
          {selectedPolicy.version != null && <span>v{selectedPolicy.version}</span>}
          {selectedPolicy.status && <span>{selectedPolicy.status}</span>}
        </div>
      )}

      <ConditionBuilder value={condition} fields={fields} onChange={setCondition} />

      <div className="dcd-actions">
        <button type="button" data-testid="dcd-validate" disabled={validating} onClick={onValidate}>
          {validating ? '校验中…' : '后端校验'}
        </button>
      </div>

      {validation && (
        <div data-testid="dcd-validation" data-valid={validation.valid}>
          {validation.valid
            ? <span data-testid="dcd-valid">校验通过</span>
            : <ul data-testid="dcd-errors">
                {(validation.errors ?? []).map((e, i) => <li key={i}>{e.code}: {e.message}</li>)}
              </ul>}
          {validation.fieldRefs && validation.fieldRefs.length > 0 && (
            <div data-testid="dcd-fieldrefs">{validation.fieldRefs.join(', ')}</div>
          )}
        </div>
      )}

      {samples.length > 0 && (
        <ConditionTestRunPanel condition={condition} samples={samples} labelOf={labelOf} />
      )}

      <pre data-testid="dcd-ast" className="dcd-ast">{serialize(condition)}</pre>
    </div>
  );
}

export default DecisionConditionDesigner;
