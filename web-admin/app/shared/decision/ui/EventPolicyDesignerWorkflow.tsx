import { useEffect, useMemo, useState } from 'react';
import type { DecisionApi, EventPolicySummary, EventPolicyVersionSummary, ScopedContext } from '../api/decisionApi';
import { group, type CompareNode, type ConditionNode, type GroupNode } from '../ast/conditionAst';
import { PolicyRulesEditor, type MatchMode, type PolicyRulesValue } from './PolicyRulesEditor';
import type { FieldOption } from './ConditionBuilder';
import type { TestSample } from './ConditionTestRunPanel';
import { ConditionTestRunPanel } from './ConditionTestRunPanel';

type DesignerStep = 'trigger' | 'rules' | 'actions' | 'test' | 'publish' | 'history';
type PolicyPhase = 'BEFORE_SUBMIT' | 'AFTER_COMMIT' | 'ASYNC_WORKER';
type ExecutionMode = 'ORDERED' | 'UNORDERED';
type FailureStrategy = 'FAIL_FAST' | 'CONTINUE_ON_ERROR' | 'ALL_OR_NOTHING' | 'RETRY_ASYNC' | 'DEAD_LETTER';
type ConflictStrategy = 'REJECT_ON_CONFLICT' | 'PRIORITY_WINS' | 'LAST_WRITE_WINS' | 'MERGE_IF_COMPATIBLE';
type DedupStrategy = 'NONE' | 'BY_IDEMPOTENCY_KEY' | 'BY_ACTION_TYPE_AND_TARGET';

export interface EventPolicyDesignerWorkflowProps {
  api: DecisionApi;
  fields: FieldOption[];
  selectedPolicy?: EventPolicySummary | null;
  samples?: TestSample[];
}

export interface PolicyActionDraft {
  type: string;
  target: string;
  order: number;
  payloadJson: string;
  idempotencyKeyTemplate: string;
}

const STEPS: { key: DesignerStep; label: string }[] = [
  { key: 'trigger', label: 'Trigger' },
  { key: 'rules', label: 'Rules' },
  { key: 'actions', label: 'Actions' },
  { key: 'test', label: 'Test' },
  { key: 'publish', label: 'Publish' },
  { key: 'history', label: 'History' },
];

const DEFAULT_IDEMPOTENCY = '${record.entityCode}:${record.recordId}:${rule.ruleCode}:${action.type}';
const POLICY_PHASES: readonly PolicyPhase[] = ['BEFORE_SUBMIT', 'AFTER_COMMIT', 'ASYNC_WORKER'];
const EXECUTION_MODES: readonly ExecutionMode[] = ['ORDERED', 'UNORDERED'];
const FAILURE_STRATEGIES: readonly FailureStrategy[] = [
  'FAIL_FAST', 'CONTINUE_ON_ERROR', 'ALL_OR_NOTHING', 'RETRY_ASYNC', 'DEAD_LETTER',
];
const CONFLICT_STRATEGIES: readonly ConflictStrategy[] = [
  'REJECT_ON_CONFLICT', 'PRIORITY_WINS', 'LAST_WRITE_WINS', 'MERGE_IF_COMPATIBLE',
];
const DEDUP_STRATEGIES: readonly DedupStrategy[] = ['NONE', 'BY_IDEMPOTENCY_KEY', 'BY_ACTION_TYPE_AND_TARGET'];

function defaultRules(matchMode?: string): PolicyRulesValue {
  return {
    matchMode: (matchMode as MatchMode | undefined) ?? 'COLLECT_ALL',
    rules: [{
      ruleCode: 'R-1',
      ruleName: 'Rule 1',
      priority: 100,
      enabled: true,
      condition: group('AND', []),
      actions: [],
    }],
  };
}

function actionsOf(rule: PolicyRulesValue['rules'][number] | undefined): PolicyActionDraft[] {
  return ((rule as { actions?: PolicyActionDraft[] } | undefined)?.actions ?? []);
}

function recordOf(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function enumOr<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  return typeof value === 'string' && allowed.includes(value as T) ? value as T : fallback;
}

function numberOr(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function stringOr(value: unknown, fallback: string): string {
  return typeof value === 'string' && value.trim() ? value : fallback;
}

function isCompareNode(value: unknown): value is CompareNode {
  const record = recordOf(value);
  return record?.type === 'compare';
}

function isGroupNode(value: unknown): value is GroupNode {
  const record = recordOf(value);
  return record?.type === 'group' && Array.isArray(record.children);
}

function isConditionNode(value: unknown): value is ConditionNode {
  const record = recordOf(value);
  return isCompareNode(value) || isGroupNode(value) || record?.type === 'not';
}

function conditionGroup(value: unknown): GroupNode {
  if (isGroupNode(value)) return value;
  if (isConditionNode(value)) return group('AND', [value]);
  return group('AND', []);
}

function hydrateActions(value: unknown): PolicyActionDraft[] {
  if (!Array.isArray(value)) return [];
  return value.map((raw, idx) => {
    const action = recordOf(raw) ?? {};
    const payload = action.payload && typeof action.payload === 'object' ? action.payload : {};
    return {
      type: stringOr(action.type, 'NOTIFY'),
      target: stringOr(action.target, ''),
      order: numberOr(action.order, idx + 1),
      payloadJson: JSON.stringify(payload, null, 2),
      idempotencyKeyTemplate: stringOr(action.idempotencyKeyTemplate, DEFAULT_IDEMPOTENCY),
    };
  });
}

function hydrateRules(value: unknown, matchMode?: string): PolicyRulesValue {
  if (!Array.isArray(value) || value.length === 0) return defaultRules(matchMode);
  return {
    matchMode: enumOr(matchMode, ['FIRST_MATCH', 'COLLECT_ALL', 'UNIQUE', 'PRIORITY_FIRST'], 'COLLECT_ALL'),
    rules: value.map((raw, idx) => {
      const rule = recordOf(raw) ?? {};
      const ruleCode = stringOr(rule.ruleCode, `R-${idx + 1}`);
      return {
        ruleCode,
        ruleName: stringOr(rule.ruleName, ruleCode),
        priority: numberOr(rule.priority, (idx + 1) * 100),
        enabled: typeof rule.enabled === 'boolean' ? rule.enabled : true,
        condition: conditionGroup(rule.condition),
        actions: hydrateActions(rule.actions),
      };
    }),
  };
}

function latestVersion(versions: EventPolicyVersionSummary[], latestPid?: string): EventPolicyVersionSummary | undefined {
  return versions.find((version) => latestPid && version.pid === latestPid)
    ?? versions.slice().sort((a, b) => (b.version ?? 0) - (a.version ?? 0))[0];
}

function parsePayload(json: string): Record<string, unknown> {
  if (!json.trim()) return {};
  const parsed = JSON.parse(json) as unknown;
  return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
    ? parsed as Record<string, unknown>
    : {};
}

function buildRulesJson(value: PolicyRulesValue) {
  return value.rules.map((rule) => ({
    ruleCode: rule.ruleCode,
    ruleName: rule.ruleName,
    priority: rule.priority,
    enabled: rule.enabled,
    condition: rule.condition,
    actions: actionsOf(rule).map((action) => ({
      type: action.type,
      target: action.target,
      order: action.order,
      payload: parsePayload(action.payloadJson),
      idempotencyKeyTemplate: action.idempotencyKeyTemplate,
    })),
  }));
}

function selectedRuleIndex(value: PolicyRulesValue, code: string): number {
  const idx = value.rules.findIndex((rule) => rule.ruleCode === code);
  return idx >= 0 ? idx : 0;
}

export function EventPolicyDesignerWorkflow({
  api,
  fields,
  selectedPolicy,
  samples = [],
}: EventPolicyDesignerWorkflowProps) {
  const [step, setStep] = useState<DesignerStep>('trigger');
  const [phase, setPhase] = useState<PolicyPhase>((selectedPolicy?.phase as PolicyPhase | undefined) ?? 'AFTER_COMMIT');
  const [executionMode, setExecutionMode] = useState<ExecutionMode>('ORDERED');
  const [failureStrategy, setFailureStrategy] = useState<FailureStrategy>('FAIL_FAST');
  const [conflictStrategy, setConflictStrategy] = useState<ConflictStrategy>('REJECT_ON_CONFLICT');
  const [dedupStrategy, setDedupStrategy] = useState<DedupStrategy>('BY_IDEMPOTENCY_KEY');
  const [rulesValue, setRulesValue] = useState<PolicyRulesValue>(() => defaultRules(selectedPolicy?.matchMode));
  const [selectedRuleCode, setSelectedRuleCode] = useState('R-1');
  const [draftPid, setDraftPid] = useState(selectedPolicy?.latestVersionPid ?? '');
  const [publishStatus, setPublishStatus] = useState(selectedPolicy?.status ?? 'UNSAVED');
  const [error, setError] = useState('');
  const [versionLoading, setVersionLoading] = useState(false);
  const [versionError, setVersionError] = useState('');
  const [runResult, setRunResult] = useState<unknown>(null);

  const currentRuleIdx = selectedRuleIndex(rulesValue, selectedRuleCode);
  const currentRule = rulesValue.rules[currentRuleIdx];
  const currentActions = actionsOf(currentRule);
  const firstSampleContext = samples[0]?.context ?? { record: { data: {} } };

  const draftJson = useMemo(() => ({
    phase,
    matchMode: rulesValue.matchMode,
    executionMode,
    failureStrategy,
    conflictStrategy,
    dedupStrategy,
    rules: buildRulesJson(rulesValue),
  }), [conflictStrategy, dedupStrategy, executionMode, failureStrategy, phase, rulesValue]);

  useEffect(() => {
    const policyCode = selectedPolicy?.policyCode;
    const fallbackPhase = enumOr(selectedPolicy?.phase, POLICY_PHASES, 'AFTER_COMMIT');
    const fallbackRules = defaultRules(selectedPolicy?.matchMode);
    let cancelled = false;

    setPhase(fallbackPhase);
    setExecutionMode('ORDERED');
    setFailureStrategy('FAIL_FAST');
    setConflictStrategy('REJECT_ON_CONFLICT');
    setDedupStrategy('BY_IDEMPOTENCY_KEY');
    setRulesValue(fallbackRules);
    setSelectedRuleCode(fallbackRules.rules[0]?.ruleCode ?? '');
    setDraftPid(selectedPolicy?.latestVersionPid ?? '');
    setPublishStatus(selectedPolicy?.status ?? 'UNSAVED');
    setError('');
    setVersionError('');
    setRunResult(null);

    if (!policyCode) {
      setVersionLoading(false);
      return () => { cancelled = true; };
    }

    setVersionLoading(true);
    api.listPolicyVersions(policyCode)
      .then((versions) => {
        if (cancelled) return;
        const version = latestVersion(versions, selectedPolicy?.latestVersionPid);
        if (!version) return;
        const hydratedRules = hydrateRules(version.rulesJson, version.matchMode ?? selectedPolicy?.matchMode);
        setPhase(enumOr(version.phase, POLICY_PHASES, fallbackPhase));
        setExecutionMode(enumOr(version.executionMode, EXECUTION_MODES, 'ORDERED'));
        setFailureStrategy(enumOr(version.failureStrategy, FAILURE_STRATEGIES, 'FAIL_FAST'));
        setConflictStrategy(enumOr(version.conflictStrategy, CONFLICT_STRATEGIES, 'REJECT_ON_CONFLICT'));
        setDedupStrategy(enumOr(version.dedupStrategy, DEDUP_STRATEGIES, 'BY_IDEMPOTENCY_KEY'));
        setRulesValue(hydratedRules);
        setSelectedRuleCode(hydratedRules.rules[0]?.ruleCode ?? '');
        setDraftPid(version.pid);
        setPublishStatus(version.status ?? selectedPolicy?.status ?? 'UNSAVED');
      })
      .catch((e) => {
        if (!cancelled) setVersionError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (!cancelled) setVersionLoading(false);
      });

    return () => { cancelled = true; };
  }, [
    api,
    selectedPolicy?.latestVersionPid,
    selectedPolicy?.matchMode,
    selectedPolicy?.phase,
    selectedPolicy?.policyCode,
    selectedPolicy?.status,
  ]);

  const patchRuleActions = (actions: PolicyActionDraft[]) => {
    const rules = rulesValue.rules.slice();
    rules[currentRuleIdx] = { ...rules[currentRuleIdx], actions };
    setRulesValue({ ...rulesValue, rules });
  };

  const addAction = () => {
    patchRuleActions([...currentActions, {
      type: 'NOTIFY',
      target: '',
      order: currentActions.length + 1,
      payloadJson: '{}',
      idempotencyKeyTemplate: DEFAULT_IDEMPOTENCY,
    }]);
  };

  const updateAction = (idx: number, patch: Partial<PolicyActionDraft>) => {
    const actions = currentActions.slice();
    actions[idx] = { ...actions[idx], ...patch };
    patchRuleActions(actions);
  };

  const createDraft = async () => {
    if (!selectedPolicy?.policyCode) return;
    setError('');
    try {
      const result = await api.createPolicyDraftVersion(selectedPolicy.policyCode, {
        phase,
        matchMode: rulesValue.matchMode,
        executionMode,
        failureStrategy,
        conflictStrategy,
        dedupStrategy,
        rulesJson: buildRulesJson(rulesValue),
      });
      setDraftPid(result.pid);
      setPublishStatus(result.status ?? 'DRAFT');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const validateDraft = async () => {
    if (!draftPid) return;
    const result = await api.validatePolicyVersion(draftPid);
    setPublishStatus(result.status ?? 'VALIDATED');
  };

  const publishDraft = async () => {
    if (!draftPid) return;
    const result = await api.publishPolicyVersion(draftPid);
    setPublishStatus(result.status ?? 'PUBLISHED');
  };

  const runPublishedPolicy = async () => {
    if (!selectedPolicy?.eventType || !selectedPolicy.targetType || !selectedPolicy.targetKey) return;
    const result = await api.runPolicy({
      eventType: selectedPolicy.eventType,
      targetType: selectedPolicy.targetType,
      targetKey: selectedPolicy.targetKey,
      context: firstSampleContext as ScopedContext,
    });
    setRunResult(result);
  };

  return (
    <div data-testid="epd-workflow">
      <nav className="epd-steps" role="tablist">
        {STEPS.map((s) => (
          <button
            key={s.key}
            type="button"
            role="tab"
            data-testid={`epd-step-${s.key}`}
            aria-selected={step === s.key}
            onClick={() => setStep(s.key)}
          >
            {s.label}
          </button>
        ))}
      </nav>

      {step === 'trigger' && (
        <section data-testid="epd-trigger-panel">
          <div data-testid="epd-trigger-context">
            <strong>{selectedPolicy?.policyName ?? selectedPolicy?.policyCode ?? 'No policy selected'}</strong>
            <span className="mono">{selectedPolicy?.policyCode ?? '-'}</span>
            <span>{selectedPolicy?.eventType ?? '-'}</span>
            <span>{selectedPolicy?.targetType ?? '-'}:{selectedPolicy?.targetKey ?? '-'}</span>
          </div>
          {versionLoading && <div data-testid="epd-version-loading">Loading version...</div>}
          {versionError && <div data-testid="epd-version-error">{versionError}</div>}
          <label htmlFor="epd-phase">Phase</label>
          <select id="epd-phase" value={phase} onChange={(e) => setPhase(e.target.value as PolicyPhase)}>
            <option value="BEFORE_SUBMIT">BEFORE_SUBMIT</option>
            <option value="AFTER_COMMIT">AFTER_COMMIT</option>
            <option value="ASYNC_WORKER">ASYNC_WORKER</option>
          </select>
        </section>
      )}

      {step === 'rules' && (
        <PolicyRulesEditor
          value={rulesValue}
          fields={fields}
          onChange={(next) => {
            setRulesValue(next);
            if (!next.rules.some((rule) => rule.ruleCode === selectedRuleCode)) {
              setSelectedRuleCode(next.rules[0]?.ruleCode ?? '');
            }
          }}
        />
      )}

      {step === 'actions' && (
        <section data-testid="epd-actions-panel">
          <label htmlFor="epd-rule-select">Rule</label>
          <select id="epd-rule-select" value={currentRule?.ruleCode ?? ''} onChange={(e) => setSelectedRuleCode(e.target.value)}>
            {rulesValue.rules.map((rule) => <option key={rule.ruleCode} value={rule.ruleCode}>{rule.ruleCode}</option>)}
          </select>
          {currentActions.map((action, idx) => (
            <div key={idx} data-testid={`epd-action-${idx}`}>
              <select
                aria-label={`action-type-${idx}`}
                value={action.type}
                onChange={(e) => updateAction(idx, { type: e.target.value })}
              >
                <option value="NOTIFY">NOTIFY</option>
                <option value="START_PROCESS">START_PROCESS</option>
                <option value="CREATE_TASK">CREATE_TASK</option>
                <option value="ADD_COMMENT">ADD_COMMENT</option>
                <option value="UPDATE_RECORD">UPDATE_RECORD</option>
                <option value="WEBHOOK">WEBHOOK</option>
              </select>
              <input
                aria-label={`action-target-${idx}`}
                value={action.target}
                onChange={(e) => updateAction(idx, { target: e.target.value })}
              />
              <input
                aria-label={`action-order-${idx}`}
                type="number"
                value={action.order}
                onChange={(e) => updateAction(idx, { order: Number(e.target.value) })}
              />
              <textarea
                aria-label={`action-payload-${idx}`}
                value={action.payloadJson}
                onChange={(e) => updateAction(idx, { payloadJson: e.target.value })}
              />
            </div>
          ))}
          <button type="button" data-testid="epd-add-action" onClick={addAction}>添加动作</button>
        </section>
      )}

      {step === 'test' && (
        <section data-testid="epd-test-panel">
          {currentRule && samples.length > 0 && (
            <ConditionTestRunPanel condition={currentRule.condition} samples={samples} />
          )}
          <button type="button" data-testid="epd-run-published" onClick={runPublishedPolicy}>运行已发布策略</button>
          {runResult !== null && <pre data-testid="epd-run-result">{JSON.stringify(runResult) ?? String(runResult)}</pre>}
        </section>
      )}

      {step === 'publish' && (
        <section data-testid="epd-publish-panel">
          <label htmlFor="epd-execution-mode">Execution</label>
          <select id="epd-execution-mode" value={executionMode} onChange={(e) => setExecutionMode(e.target.value as ExecutionMode)}>
            <option value="ORDERED">ORDERED</option>
            <option value="UNORDERED">UNORDERED</option>
          </select>
          <label htmlFor="epd-failure">Failure</label>
          <select id="epd-failure" value={failureStrategy} onChange={(e) => setFailureStrategy(e.target.value as FailureStrategy)}>
            <option value="FAIL_FAST">FAIL_FAST</option>
            <option value="CONTINUE_ON_ERROR">CONTINUE_ON_ERROR</option>
            <option value="ALL_OR_NOTHING">ALL_OR_NOTHING</option>
            <option value="RETRY_ASYNC">RETRY_ASYNC</option>
            <option value="DEAD_LETTER">DEAD_LETTER</option>
          </select>
          <label htmlFor="epd-conflict">Conflict</label>
          <select id="epd-conflict" value={conflictStrategy} onChange={(e) => setConflictStrategy(e.target.value as ConflictStrategy)}>
            <option value="REJECT_ON_CONFLICT">REJECT_ON_CONFLICT</option>
            <option value="PRIORITY_WINS">PRIORITY_WINS</option>
            <option value="LAST_WRITE_WINS">LAST_WRITE_WINS</option>
            <option value="MERGE_IF_COMPATIBLE">MERGE_IF_COMPATIBLE</option>
          </select>
          <label htmlFor="epd-dedup">Dedup</label>
          <select id="epd-dedup" value={dedupStrategy} onChange={(e) => setDedupStrategy(e.target.value as DedupStrategy)}>
            <option value="NONE">NONE</option>
            <option value="BY_IDEMPOTENCY_KEY">BY_IDEMPOTENCY_KEY</option>
            <option value="BY_ACTION_TYPE_AND_TARGET">BY_ACTION_TYPE_AND_TARGET</option>
          </select>
          <button type="button" data-testid="epd-save-draft" onClick={createDraft}>保存草稿</button>
          <button type="button" data-testid="epd-validate-version" disabled={!draftPid} onClick={validateDraft}>校验版本</button>
          <button type="button" data-testid="epd-publish-version" disabled={!draftPid} onClick={publishDraft}>发布版本</button>
          <div data-testid="epd-publish-status">{publishStatus}</div>
          {error && <div data-testid="epd-error">{error}</div>}
        </section>
      )}

      {step === 'history' && (
        <section data-testid="epd-history-panel">
          <div>Current: {selectedPolicy?.status ?? '-'}</div>
          <div>Version: {selectedPolicy?.version ?? '-'}</div>
          <div>Draft: {draftPid || '-'}</div>
        </section>
      )}

      <pre data-testid="epd-draft-json">{JSON.stringify(draftJson)}</pre>
    </div>
  );
}

export default EventPolicyDesignerWorkflow;
