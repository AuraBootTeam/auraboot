import { useEffect, useMemo, useState } from 'react';
import type {
  DecisionAction,
  DecisionApi,
  EventPolicySummary,
  EventPolicyVersionSummary,
  ScopedContext,
} from '../api/decisionApi';
import { group, type CompareNode, type ConditionNode, type GroupNode } from '../ast/conditionAst';
import { PolicyRulesEditor, type MatchMode, type PolicyRulesValue } from './PolicyRulesEditor';
import type { FieldOption } from './ConditionBuilder';
import type { TestSample } from './ConditionTestRunPanel';
import { ConditionTestRunPanel } from './ConditionTestRunPanel';
import {
  actionFieldInputKind,
  type ActionSchemaField,
  payloadToJson,
  readActionFieldValue,
  writeActionFieldValue,
} from './actionSchemaFields';
import { useI18n } from '~/contexts/I18nContext';
import { createEventPolicyPresentation } from './eventPolicyPresentation';
import { recordOf, stringOr, parsePayload } from './eventPolicyValues';
import {
  DecisionRuleBindingBlock,
  type DecisionOption,
  type RuleConsumerBindingDraft,
} from '~/ui/smart/decision/DecisionRuleBindingBlock';

export type DesignerStep = 'trigger' | 'rules' | 'actions' | 'test' | 'publish' | 'history';
export type PolicyPhase = 'BEFORE_SUBMIT' | 'AFTER_COMMIT' | 'ASYNC_WORKER';
export type ExecutionMode = 'ORDERED' | 'UNORDERED';
export type FailureStrategy =
  | 'FAIL_FAST'
  | 'CONTINUE_ON_ERROR'
  | 'ALL_OR_NOTHING'
  | 'RETRY_ASYNC'
  | 'DEAD_LETTER';
export type ConflictStrategy =
  | 'REJECT_ON_CONFLICT'
  | 'PRIORITY_WINS'
  | 'LAST_WRITE_WINS'
  | 'MERGE_IF_COMPATIBLE';
export type DedupStrategy = 'NONE' | 'BY_IDEMPOTENCY_KEY' | 'BY_ACTION_TYPE_AND_TARGET';
type DecisionBindingValue = NonNullable<RuleConsumerBindingDraft['decisionBinding']>;

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

const DEFAULT_IDEMPOTENCY =
  '${record.entityCode}:${record.recordPid}:${rule.ruleCode}:${action.type}';
const POLICY_PHASES: readonly PolicyPhase[] = ['BEFORE_SUBMIT', 'AFTER_COMMIT', 'ASYNC_WORKER'];
const EXECUTION_MODES: readonly ExecutionMode[] = ['ORDERED', 'UNORDERED'];
const FAILURE_STRATEGIES: readonly FailureStrategy[] = [
  'FAIL_FAST',
  'CONTINUE_ON_ERROR',
  'ALL_OR_NOTHING',
  'RETRY_ASYNC',
  'DEAD_LETTER',
];
const CONFLICT_STRATEGIES: readonly ConflictStrategy[] = [
  'REJECT_ON_CONFLICT',
  'PRIORITY_WINS',
  'LAST_WRITE_WINS',
  'MERGE_IF_COMPATIBLE',
];
const DEDUP_STRATEGIES: readonly DedupStrategy[] = [
  'NONE',
  'BY_IDEMPOTENCY_KEY',
  'BY_ACTION_TYPE_AND_TARGET',
];

function defaultRules(matchMode?: string): PolicyRulesValue {
  return {
    matchMode: (matchMode as MatchMode | undefined) ?? 'COLLECT_ALL',
    rules: [
      {
        ruleCode: 'R-1',
        ruleName: 'Rule 1',
        priority: 100,
        enabled: true,
        condition: group('AND', []),
        actions: [],
      },
    ],
  };
}

function actionsOf(rule: PolicyRulesValue['rules'][number] | undefined): PolicyActionDraft[] {
  return (rule as { actions?: PolicyActionDraft[] } | undefined)?.actions ?? [];
}

function enumOr<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  return typeof value === 'string' && allowed.includes(value as T) ? (value as T) : fallback;
}

function numberOr(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
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

function hydrateDecisionBinding(value: unknown): DecisionBindingValue | undefined {
  const binding = recordOf(value);
  const fallbackPolicy = recordOf(binding?.fallbackPolicy);
  if (!binding || typeof binding.decisionCode !== 'string' || !binding.decisionCode.trim()) {
    return undefined;
  }
  return {
    decisionCode: binding.decisionCode,
    versionPolicy: enumOr(
      binding.versionPolicy,
      ['LATEST_PUBLISHED', 'FIXED_VERSION', 'VERSION_TAG', 'ROLLOUT'],
      'LATEST_PUBLISHED',
    ),
    inputMappings: Array.isArray(binding.inputMappings)
      ? (binding.inputMappings as DecisionBindingValue['inputMappings'])
      : [],
    outputMappings: Array.isArray(binding.outputMappings)
      ? (binding.outputMappings as DecisionBindingValue['outputMappings'])
      : [],
    fallbackPolicy: {
      mode: enumOr(
        fallbackPolicy?.mode,
        ['FAIL_CLOSED', 'FAIL_OPEN', 'DEFAULT_VALUE'],
        'FAIL_CLOSED',
      ),
    },
    traceMode: enumOr(binding.traceMode, ['SAMPLED', 'ALWAYS', 'NONE'], 'SAMPLED'),
    enabled: typeof binding.enabled === 'boolean' ? binding.enabled : true,
  };
}

function hydrateRules(value: unknown, matchMode?: string): PolicyRulesValue {
  if (!Array.isArray(value) || value.length === 0) return defaultRules(matchMode);
  return {
    matchMode: enumOr(
      matchMode,
      ['FIRST_MATCH', 'COLLECT_ALL', 'UNIQUE', 'PRIORITY_FIRST'],
      'COLLECT_ALL',
    ),
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
        decisionBinding: hydrateDecisionBinding(rule.decisionBinding),
      };
    }),
  };
}

function latestVersion(
  versions: EventPolicyVersionSummary[],
  latestPid?: string,
): EventPolicyVersionSummary | undefined {
  return (
    versions.find((version) => latestPid && version.pid === latestPid) ??
    versions.slice().sort((a, b) => (b.version ?? 0) - (a.version ?? 0))[0]
  );
}

function buildRulesJson(value: PolicyRulesValue) {
  return value.rules.map((rule) => {
    const ruleJson: Record<string, unknown> = {
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
    };
    if (rule.decisionBinding) {
      ruleJson.decisionBinding = rule.decisionBinding;
    }
    return ruleJson;
  });
}

function selectedRuleIndex(value: PolicyRulesValue, code: string): number {
  const idx = value.rules.findIndex((rule) => rule.ruleCode === code);
  return idx >= 0 ? idx : 0;
}

function enumOption<T extends string>(value: T, labels: Record<T, string>) {
  return (
    <option key={value} value={value}>
      {labels[value]}
    </option>
  );
}

function eventPolicyBindingValue(
  rule: PolicyRulesValue['rules'][number],
  selectedPolicy?: EventPolicySummary | null,
): RuleConsumerBindingDraft {
  return {
    consumerType: 'EVENT_POLICY',
    consumerCode: selectedPolicy?.policyCode,
    consumerNodeId: rule.ruleCode,
    bindingKind: 'DECISION_REF',
    decisionBinding: rule.decisionBinding,
    enabled: true,
  };
}

function executionRecord(value: unknown): Record<string, unknown> | null {
  const result = recordOf(value);
  return recordOf(result?.execution);
}

function policyRecord(value: unknown): Record<string, unknown> | null {
  const result = recordOf(value);
  return recordOf(result?.policy) ?? recordOf(value);
}

function runCorrelationId(value: unknown): string {
  const policy = policyRecord(value);
  return stringOr(policy?.correlationId, '');
}

function eventPolicyTraceHref(value: unknown, selectedPolicy?: EventPolicySummary | null): string {
  const policyCode = stringOr(
    selectedPolicy?.policyCode,
    stringOr(policyRecord(value)?.policyCode, ''),
  );
  const correlationId = runCorrelationId(value);
  if (!policyCode || !correlationId) return '';
  const params = new URLSearchParams({
    policyCode,
    correlationId,
    callerType: 'EVENT_POLICY',
    callerRef: policyCode,
  });
  return `/p/decisionops_execution_logs?${params.toString()}`;
}

function actionExecutionRows(value: unknown): Record<string, unknown>[] {
  const actions = executionRecord(value)?.actions;
  if (!Array.isArray(actions)) return [];
  return actions
    .map((action) => recordOf(action))
    .filter((action): action is Record<string, unknown> => Boolean(action));
}

function isFailedExecutionAction(action: Record<string, unknown>): boolean {
  if (action.error) return true;
  const status = String(action.status ?? '').toUpperCase();
  if (!status) return false;
  return !['SUCCESS', 'SKIPPED', 'NOT_EXECUTED'].includes(status);
}

function executionContextForSample(sample: TestSample | undefined): ScopedContext {
  return (sample?.executionContext?.() ??
    sample?.context ?? { record: { data: {} } }) as ScopedContext;
}

export function EventPolicyDesignerWorkflow({
  api,
  fields,
  selectedPolicy,
  samples = [],
}: EventPolicyDesignerWorkflowProps) {
  const { locale } = useI18n();
  const presentation = useMemo(() => createEventPolicyPresentation(locale), [locale]);
  const {
    copy,
    STEPS,
    SAFE_ACTIONS,
    PHASE_LABELS,
    EXECUTION_MODE_LABELS,
    FAILURE_STRATEGY_LABELS,
    CONFLICT_STRATEGY_LABELS,
    DEDUP_STRATEGY_LABELS,
    MATCH_MODE_LABELS,
    actionOptionLabel,
    actionAvailabilityForType,
    actionTypeLabel,
    editableActionFields,
    uniqueDecisions,
    payloadTitle,
    runStatus,
    executionStatus,
    resultPayloadRows,
    idempotencyEvidence,
    idempotencyTitle,
    policyStatusLabel,
  } = presentation;
  const [step, setStep] = useState<DesignerStep>('trigger');
  const [phase, setPhase] = useState<PolicyPhase>(
    (selectedPolicy?.phase as PolicyPhase | undefined) ?? 'AFTER_COMMIT',
  );
  const [executionMode, setExecutionMode] = useState<ExecutionMode>('ORDERED');
  const [failureStrategy, setFailureStrategy] = useState<FailureStrategy>('FAIL_FAST');
  const [conflictStrategy, setConflictStrategy] = useState<ConflictStrategy>('REJECT_ON_CONFLICT');
  const [dedupStrategy, setDedupStrategy] = useState<DedupStrategy>('BY_IDEMPOTENCY_KEY');
  const [rulesValue, setRulesValue] = useState<PolicyRulesValue>(() =>
    defaultRules(selectedPolicy?.matchMode),
  );
  const [selectedRuleCode, setSelectedRuleCode] = useState('R-1');
  const [draftPid, setDraftPid] = useState(selectedPolicy?.latestVersionPid ?? '');
  const [publishStatus, setPublishStatus] = useState(selectedPolicy?.status ?? 'UNSAVED');
  const [error, setError] = useState('');
  const [versionLoading, setVersionLoading] = useState(false);
  const [versionError, setVersionError] = useState('');
  const [runResult, setRunResult] = useState<unknown>(null);
  const [catalogActions, setCatalogActions] = useState<DecisionAction[]>([]);
  const [catalogError, setCatalogError] = useState('');

  const currentRuleIdx = selectedRuleIndex(rulesValue, selectedRuleCode);
  const currentRule = rulesValue.rules[currentRuleIdx];
  const currentActions = actionsOf(currentRule);
  const firstSampleContext = samples[0]?.context ?? { record: { data: {} } };
  const actionOptions = useMemo(() => {
    const runtimeActions = catalogActions.filter((action) => action.actionType);
    return runtimeActions.length > 0 ? runtimeActions : SAFE_ACTIONS;
  }, [catalogActions, SAFE_ACTIONS]);
  const decisionOptions = useMemo(
    () => uniqueDecisions(rulesValue.rules),
    [rulesValue.rules, uniqueDecisions],
  );
  const configuredActions = useMemo(
    () =>
      rulesValue.rules.flatMap((rule) =>
        actionsOf(rule).map((action) => ({
          ...action,
          ruleCode: rule.ruleCode,
          ruleName: rule.ruleName,
        })),
      ),
    [rulesValue.rules],
  );
  const decisionBindingCount = useMemo(
    () => rulesValue.rules.filter((rule) => Boolean(rule.decisionBinding?.decisionCode)).length,
    [rulesValue.rules],
  );
  const missingHandlerActions = useMemo(
    () =>
      configuredActions.filter((action) => {
        const option = actionOptions.find((candidate) => candidate.actionType === action.type);
        return option?.handlerAvailable === false;
      }),
    [actionOptions, configuredActions],
  );
  const executionRows = useMemo(() => actionExecutionRows(runResult), [runResult]);
  const failedExecutionActions = useMemo(
    () => executionRows.filter(isFailedExecutionAction),
    [executionRows],
  );
  const abnormalActionCount = missingHandlerActions.length + failedExecutionActions.length;
  const runExecutionStatus = executionRecord(runResult)?.overallStatus;
  const runSummary =
    runResult === null
      ? copy('sample_run_pending')
      : `${runStatus(runResult)} / ${executionStatus(runExecutionStatus)}`;
  const runDetail =
    runResult === null
      ? (samples[0]?.label ?? copy('no_sample_facts'))
      : executionRows.length > 0
        ? copy('actions_with_execution_evidence', { count: executionRows.length })
        : copy('no_actions_to_execute_for_this_run');
  const abnormalSummary =
    abnormalActionCount === 0
      ? copy('no_abnormal_actions')
      : copy('items_requiring_attention', { count: abnormalActionCount });
  const abnormalDetail =
    abnormalActionCount === 0
      ? copy('action_handlers_and_execution_status_are_healthy')
      : [
          missingHandlerActions.length > 0
            ? copy('currently_unavailable_actions', { count: missingHandlerActions.length })
            : '',
          failedExecutionActions.length > 0
            ? copy('actions_with_execution_errors', { count: failedExecutionActions.length })
            : '',
        ]
          .filter(Boolean)
          .join('，');

  const draftJson = useMemo(
    () => ({
      phase,
      matchMode: rulesValue.matchMode,
      executionMode,
      failureStrategy,
      conflictStrategy,
      dedupStrategy,
      rules: buildRulesJson(rulesValue),
    }),
    [conflictStrategy, dedupStrategy, executionMode, failureStrategy, phase, rulesValue],
  );

  useEffect(() => {
    let cancelled = false;
    setCatalogError('');
    api
      .getActionCatalog()
      .then((catalog) => {
        if (!cancelled) setCatalogActions(catalog.actions ?? []);
      })
      .catch((e) => {
        if (!cancelled) {
          setCatalogActions([]);
          setCatalogError(e instanceof Error ? e.message : String(e));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

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
      return () => {
        cancelled = true;
      };
    }

    setVersionLoading(true);
    api
      .listPolicyVersions(policyCode)
      .then((versions) => {
        if (cancelled) return;
        const version = latestVersion(versions, selectedPolicy?.latestVersionPid);
        if (!version) return;
        const hydratedRules = hydrateRules(
          version.rulesJson,
          version.matchMode ?? selectedPolicy?.matchMode,
        );
        setPhase(enumOr(version.phase, POLICY_PHASES, fallbackPhase));
        setExecutionMode(enumOr(version.executionMode, EXECUTION_MODES, 'ORDERED'));
        setFailureStrategy(enumOr(version.failureStrategy, FAILURE_STRATEGIES, 'FAIL_FAST'));
        setConflictStrategy(
          enumOr(version.conflictStrategy, CONFLICT_STRATEGIES, 'REJECT_ON_CONFLICT'),
        );
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

    return () => {
      cancelled = true;
    };
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

  const patchRuleDecisionBinding = (
    idx: number,
    decisionBinding: RuleConsumerBindingDraft['decisionBinding'],
  ) => {
    const rules = rulesValue.rules.slice();
    if (!rules[idx]) return;
    rules[idx] = { ...rules[idx], decisionBinding };
    setRulesValue({ ...rulesValue, rules });
  };

  const addAction = () => {
    patchRuleActions([
      ...currentActions,
      {
        type: actionOptions[0]?.actionType ?? 'NOTIFY',
        target: '',
        order: currentActions.length + 1,
        payloadJson: '{}',
        idempotencyKeyTemplate: DEFAULT_IDEMPOTENCY,
      },
    ]);
  };

  const updateAction = (idx: number, patch: Partial<PolicyActionDraft>) => {
    const actions = currentActions.slice();
    actions[idx] = { ...actions[idx], ...patch };
    patchRuleActions(actions);
  };

  const updateActionField = (idx: number, field: ActionSchemaField, value: string) => {
    const action = currentActions[idx];
    if (!action) return;
    try {
      const payload = parsePayload(action.payloadJson);
      const next = writeActionFieldValue(action.target, payload, field, value);
      updateAction(idx, { target: next.target ?? '', payloadJson: payloadToJson(next.payload) });
    } catch {
      // Keep the previous structured payload until the JSON field becomes valid again.
    }
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
    if (!selectedPolicy?.eventType || !selectedPolicy.targetType || !selectedPolicy.targetKey)
      return;
    const result = await api.runAndExecutePolicy({
      eventType: selectedPolicy.eventType,
      targetType: selectedPolicy.targetType,
      targetKey: selectedPolicy.targetKey,
      context: executionContextForSample(samples[0]),
    });
    setRunResult(result);
  };

  return (
    <div className="epd-workflow" data-testid="epd-workflow">
      <section className="epd-command-center" data-testid="epd-command-center">
        <div className="epd-command-main" data-testid="epd-strategy-summary">
          <div className="epd-panel-heading">
            <div>
              <span className="epd-eyebrow">{copy('policy_summary')}</span>
              <strong>
                {selectedPolicy?.policyName ??
                  selectedPolicy?.policyCode ??
                  copy('no_policy_selected')}
              </strong>
              <div className="epd-context-meta">
                <span>{selectedPolicy?.policyCode ?? '-'}</span>
                <span>{selectedPolicy?.eventType ?? '-'}</span>
                <span>
                  {selectedPolicy?.targetType ?? '-'} / {selectedPolicy?.targetKey ?? '-'}
                </span>
              </div>
            </div>
            <span className={`epd-status epd-status-${String(publishStatus).toLowerCase()}`}>
              {policyStatusLabel(publishStatus)}
            </span>
          </div>
          <div className="epd-command-metrics">
            <span>{copy('rules', { count: rulesValue.rules.length })}</span>
            <span>{copy('actions_sentence', { count: configuredActions.length })}</span>
            <span>{copy('decision_references', { count: decisionBindingCount })}</span>
            <span>{MATCH_MODE_LABELS[rulesValue.matchMode]}</span>
          </div>
        </div>

        <div className="epd-command-card" data-testid="epd-run-summary">
          <span>{copy('latest_execution')}</span>
          <strong>{runSummary}</strong>
          <small>{runDetail}</small>
          <button type="button" onClick={() => setStep('test')}>
            {copy('test_run')}
          </button>
        </div>

        <div className="epd-command-card" data-testid="epd-abnormal-actions">
          <span>{copy('abnormal_actions')}</span>
          <strong>{abnormalSummary}</strong>
          <small>{abnormalDetail}</small>
          <button type="button" onClick={() => setStep('actions')}>
            {copy('inspect_actions')}
          </button>
        </div>
      </section>

      <nav className="epd-steps" role="tablist">
        {STEPS.map((s) => (
          <button
            key={s.key}
            type="button"
            role="tab"
            data-testid={`epd-step-${s.key}`}
            aria-selected={step === s.key}
            className={step === s.key ? 'is-active' : ''}
            onClick={() => setStep(s.key)}
          >
            {s.label}
          </button>
        ))}
      </nav>

      {step === 'trigger' && (
        <section className="epd-panel" data-testid="epd-trigger-panel">
          <div className="epd-panel-heading" data-testid="epd-trigger-context">
            <div>
              <span className="epd-eyebrow">{copy('event_policy')}</span>
              <strong>
                {selectedPolicy?.policyName ??
                  selectedPolicy?.policyCode ??
                  copy('no_policy_selected')}
              </strong>
              <div className="epd-context-meta">
                <span>{selectedPolicy?.policyCode ?? '-'}</span>
                <span>{selectedPolicy?.eventType ?? '-'}</span>
                <span>
                  {selectedPolicy?.targetType ?? '-'} / {selectedPolicy?.targetKey ?? '-'}
                </span>
              </div>
            </div>
            <span className={`epd-status epd-status-${String(publishStatus).toLowerCase()}`}>
              {policyStatusLabel(publishStatus)}
            </span>
          </div>
          {versionLoading && (
            <div className="epd-state" data-testid="epd-version-loading">
              {copy('loading_versions')}
            </div>
          )}
          {versionError && (
            <div className="epd-state is-error" data-testid="epd-version-error">
              {versionError}
            </div>
          )}
          <div className="epd-summary-grid">
            <div className="epd-summary-card">
              <span>{copy('policy_code')}</span>
              <strong>{selectedPolicy?.policyCode ?? '-'}</strong>
            </div>
            <div className="epd-summary-card">
              <span>{copy('trigger_event')}</span>
              <strong>{selectedPolicy?.eventType ?? '-'}</strong>
            </div>
            <div className="epd-summary-card">
              <span>{copy('target_object')}</span>
              <strong>
                {selectedPolicy?.targetType ?? '-'} / {selectedPolicy?.targetKey ?? '-'}
              </strong>
            </div>
            <div className="epd-summary-card">
              <span>{copy('match_mode')}</span>
              <strong>{MATCH_MODE_LABELS[rulesValue.matchMode]}</strong>
            </div>
          </div>
          <div className="epd-field-row">
            <label htmlFor="epd-phase">{copy('execution_phase')}</label>
            <select
              id="epd-phase"
              value={phase}
              onChange={(e) => setPhase(e.target.value as PolicyPhase)}
            >
              {POLICY_PHASES.map((value) => enumOption(value, PHASE_LABELS))}
            </select>
          </div>
        </section>
      )}

      {step === 'rules' && (
        <section className="epd-panel">
          <div className="epd-panel-heading">
            <div>
              <span className="epd-eyebrow">{copy('condition_setup')}</span>
              <strong>{copy('actions_execute_only_after_a_rule_matches')}</strong>
            </div>
            <span className="epd-chip">{copy('rules', { count: rulesValue.rules.length })}</span>
          </div>
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
          <div className="epd-rule-binding-list">
            {rulesValue.rules.map((rule, idx) => (
              <div
                className="epd-rule-binding-card"
                data-testid={`epd-rule-binding-${idx}`}
                key={rule.ruleCode}
              >
                <div className="epd-panel-heading">
                  <div>
                    <span className="epd-eyebrow">{copy('reuse_a_decision')}</span>
                    <strong>{rule.ruleName || rule.ruleCode}</strong>
                    <div className="epd-context-meta">
                      <span>{rule.decisionBinding?.decisionCode ?? copy('no_decision_bound')}</span>
                      <span>
                        {rule.decisionBinding?.inputMappings
                          ?.map((mapping) => mapping.input)
                          .filter(Boolean)
                          .join(', ') || copy('no_input_mappings_configured')}
                      </span>
                    </div>
                  </div>
                </div>
                <DecisionRuleBindingBlock
                  value={eventPolicyBindingValue(rule, selectedPolicy)}
                  onChange={(next) => patchRuleDecisionBinding(idx, next.decisionBinding)}
                  block={{
                    props: {
                      mode: 'decision',
                      consumerType: 'EVENT_POLICY',
                      consumerCode: selectedPolicy?.policyCode,
                      consumerNodeId: rule.ruleCode,
                      fields,
                      decisions: decisionOptions,
                      initialDecisionCode:
                        rule.decisionBinding?.decisionCode ?? decisionOptions[0]?.code,
                      initialContextJson: JSON.stringify(firstSampleContext, null, 2),
                      showImpactPreview: true,
                      showTestRunner: true,
                    },
                  }}
                />
              </div>
            ))}
          </div>
        </section>
      )}

      {step === 'actions' && (
        <section className="epd-panel" data-testid="epd-actions-panel">
          <div className="epd-panel-heading">
            <div>
              <span className="epd-eyebrow">{copy('action_setup')}</span>
              <strong>{copy('actions_after_a_rule_matches')}</strong>
            </div>
            <button type="button" data-testid="epd-add-action" onClick={addAction}>
              {copy('add_action')}
            </button>
          </div>
          <div className="epd-field-row">
            <label htmlFor="epd-rule-select">{copy('current_rule')}</label>
            <select
              id="epd-rule-select"
              value={currentRule?.ruleCode ?? ''}
              onChange={(e) => setSelectedRuleCode(e.target.value)}
            >
              {rulesValue.rules.map((rule) => (
                <option key={rule.ruleCode} value={rule.ruleCode}>
                  {rule.ruleName || rule.ruleCode}
                </option>
              ))}
            </select>
          </div>
          {catalogError && (
            <div className="epd-state is-warning" data-testid="epd-action-catalog-error">
              {copy('action_catalog_unavailable_using_built_in_action_types')}
            </div>
          )}
          <div className="epd-action-grid">
            {currentActions.length === 0 && (
              <div className="epd-empty">
                {copy(
                  'no_actions_yet_matching_this_rule_will_not_send_notifications_start_processes_or_update_records',
                )}
              </div>
            )}
            {currentActions.map((action, idx) => {
              const fields = editableActionFields(action, actionOptions);
              const availability = actionAvailabilityForType(action.type, actionOptions);
              const payload = (() => {
                try {
                  return parsePayload(action.payloadJson);
                } catch {
                  return {};
                }
              })();
              return (
                <div className="epd-action-card" key={idx} data-testid={`epd-action-${idx}`}>
                  <div className="epd-action-title">
                    <strong>{actionTypeLabel(action.type)}</strong>
                    {availability.unavailable && (
                      <span className="epd-action-availability-badge">{copy('unavailable')}</span>
                    )}
                    <span>#{action.order}</span>
                  </div>
                  {availability.unavailable && (
                    <div
                      className="epd-action-availability"
                      data-testid={`epd-action-availability-${idx}`}
                    >
                      <div>{availability.reason}</div>
                      {availability.providerSummary && (
                        <div className="mt-1" data-testid={`epd-action-provider-${idx}`}>
                          {availability.providerSummary}
                        </div>
                      )}
                    </div>
                  )}
                  <div className="epd-field-row">
                    <label htmlFor={`epd-action-type-${idx}`}>{copy('action_type')}</label>
                    <select
                      id={`epd-action-type-${idx}`}
                      aria-label={`action-type-${idx}`}
                      value={action.type}
                      onChange={(e) => updateAction(idx, { type: e.target.value })}
                    >
                      {actionOptions.map((option) => (
                        <option key={option.actionType} value={option.actionType}>
                          {actionOptionLabel(option)}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="epd-inline-fields">
                    <div className="epd-field-row">
                      <label htmlFor={`epd-action-order-${idx}`}>{copy('order')}</label>
                      <input
                        id={`epd-action-order-${idx}`}
                        aria-label={`action-order-${idx}`}
                        type="number"
                        value={action.order}
                        onChange={(e) => updateAction(idx, { order: Number(e.target.value) })}
                      />
                    </div>
                    <div className="epd-payload-summary">
                      <span>{copy('payload_summary')}</span>
                      <strong>{payloadTitle(action.payloadJson)}</strong>
                    </div>
                  </div>
                  <div className="epd-action-schema" data-testid={`epd-action-schema-${idx}`}>
                    {fields.map((field) => {
                      const inputKind = actionFieldInputKind(field);
                      const ariaLabel =
                        field.path === 'target'
                          ? `action-target-${idx}`
                          : `action-field-${idx}-${field.path}`;
                      const fieldValue = readActionFieldValue(action.target, payload, field);
                      return (
                        <label key={field.path} className="epd-field-row">
                          <span>
                            {field.label}
                            {field.required && <em>{copy('required')}</em>}
                          </span>
                          {inputKind === 'textarea' || inputKind === 'json' ? (
                            <textarea
                              aria-label={ariaLabel}
                              value={fieldValue}
                              onChange={(e) => updateActionField(idx, field, e.target.value)}
                            />
                          ) : (
                            <input
                              aria-label={ariaLabel}
                              value={fieldValue}
                              onChange={(e) => updateActionField(idx, field, e.target.value)}
                              placeholder={
                                field.path === 'target'
                                  ? copy('for_example_role_wd_manager')
                                  : undefined
                              }
                            />
                          )}
                        </label>
                      );
                    })}
                  </div>
                  <details className="epd-advanced">
                    <summary>{copy('advanced_payload')}</summary>
                    <textarea
                      aria-label={`action-payload-${idx}`}
                      value={action.payloadJson}
                      onChange={(e) => updateAction(idx, { payloadJson: e.target.value })}
                    />
                  </details>
                </div>
              );
            })}
          </div>
        </section>
      )}

      {step === 'test' && (
        <section className="epd-panel" data-testid="epd-test-panel">
          <div className="epd-panel-heading">
            <div>
              <span className="epd-eyebrow">{copy('test_run')}</span>
              <strong>
                {copy('verify_policy_matches_and_action_execution_with_sample_facts')}
              </strong>
            </div>
            <button type="button" data-testid="epd-run-published" onClick={runPublishedPolicy}>
              {copy('run_and_execute_actions')}
            </button>
          </div>
          {currentRule && samples.length > 0 && (
            <ConditionTestRunPanel
              condition={currentRule.condition}
              samples={samples}
              fields={fields}
              emptyPreviewLabel={copy('this_version_uses_the_published_policy_conditions')}
            />
          )}
          {samples.length === 0 && (
            <div className="epd-empty">
              {copy(
                'no_sample_facts_run_the_published_policy_directly_or_provide_a_test_sample_from_the_caller',
              )}
            </div>
          )}
          {runResult !== null && (
            <div className="epd-result" data-testid="epd-run-result">
              <span>{copy('run_result')}</span>
              <strong>{runStatus(runResult)}</strong>
              {runCorrelationId(runResult) ? (
                <span className="epd-correlation" data-testid="epd-correlation-id">
                  Correlation {runCorrelationId(runResult)}
                </span>
              ) : null}
              {eventPolicyTraceHref(runResult, selectedPolicy) ? (
                <a
                  data-testid="epd-open-trace"
                  href={eventPolicyTraceHref(runResult, selectedPolicy)}
                >
                  {copy('open_unified_trace')}
                </a>
              ) : null}
            </div>
          )}
          {runResult !== null && executionRecord(runResult) && (
            <div className="epd-action-execution" data-testid="epd-action-execution-results">
              <div className="epd-result">
                <span>{copy('action_execution')}</span>
                <strong>{executionStatus(executionRecord(runResult)?.overallStatus)}</strong>
              </div>
              <div className="epd-action-grid">
                {actionExecutionRows(runResult).length === 0 ? (
                  <div className="epd-empty">
                    {copy('no_actions_to_execute_for_this_run_sentence')}
                  </div>
                ) : (
                  actionExecutionRows(runResult).map((action, idx) => {
                    const payloadRows = resultPayloadRows(action);
                    return (
                      <div
                        className="epd-action-card"
                        key={idx}
                        data-testid={`epd-action-execution-${idx}`}
                      >
                        <div className="epd-action-title">
                          <strong>{actionTypeLabel(stringOr(action.type, '-'))}</strong>
                          <span>{executionStatus(action.status)}</span>
                        </div>
                        <div className="epd-context-meta">
                          <span>{String(action.ruleCode ?? '-')}</span>
                          <span title={idempotencyTitle(action.idempotencyKey)}>
                            {idempotencyEvidence(action.idempotencyKey)}
                          </span>
                        </div>
                        {action.error ? (
                          <div className="epd-state is-error">{String(action.error)}</div>
                        ) : null}
                        {payloadRows.length > 0 ? (
                          <dl
                            className="epd-action-result-payload"
                            data-testid={`epd-action-result-payload-${idx}`}
                          >
                            {payloadRows.map((row) => (
                              <div key={row.key}>
                                <dt>{row.label}</dt>
                                <dd>{row.value}</dd>
                              </div>
                            ))}
                          </dl>
                        ) : null}
                      </div>
                    );
                  })
                )}
              </div>
            </div>
          )}
        </section>
      )}

      {step === 'publish' && (
        <section className="epd-panel" data-testid="epd-publish-panel">
          <div className="epd-panel-heading">
            <div>
              <span className="epd-eyebrow">{copy('publishing')}</span>
              <strong>{copy('save_validate_and_publish_policy_versions')}</strong>
            </div>
            <div className="epd-publish-actions">
              <button type="button" data-testid="epd-save-draft" onClick={createDraft}>
                {copy('save_draft')}
              </button>
              <button
                type="button"
                data-testid="epd-validate-version"
                disabled={!draftPid}
                onClick={validateDraft}
              >
                {copy('validate_version')}
              </button>
              <button
                type="button"
                data-testid="epd-publish-version"
                disabled={!draftPid}
                onClick={publishDraft}
              >
                {copy('publish_version')}
              </button>
            </div>
          </div>
          <div className="epd-summary-grid">
            <div className="epd-field-row">
              <label htmlFor="epd-execution-mode">{copy('execution_mode')}</label>
              <select
                id="epd-execution-mode"
                value={executionMode}
                onChange={(e) => setExecutionMode(e.target.value as ExecutionMode)}
              >
                {EXECUTION_MODES.map((value) => enumOption(value, EXECUTION_MODE_LABELS))}
              </select>
            </div>
            <div className="epd-field-row">
              <label htmlFor="epd-failure">{copy('failure_strategy')}</label>
              <select
                id="epd-failure"
                value={failureStrategy}
                onChange={(e) => setFailureStrategy(e.target.value as FailureStrategy)}
              >
                {FAILURE_STRATEGIES.map((value) => enumOption(value, FAILURE_STRATEGY_LABELS))}
              </select>
            </div>
            <div className="epd-field-row">
              <label htmlFor="epd-conflict">{copy('conflict_strategy')}</label>
              <select
                id="epd-conflict"
                value={conflictStrategy}
                onChange={(e) => setConflictStrategy(e.target.value as ConflictStrategy)}
              >
                {CONFLICT_STRATEGIES.map((value) => enumOption(value, CONFLICT_STRATEGY_LABELS))}
              </select>
            </div>
            <div className="epd-field-row">
              <label htmlFor="epd-dedup">{copy('deduplication_strategy')}</label>
              <select
                id="epd-dedup"
                value={dedupStrategy}
                onChange={(e) => setDedupStrategy(e.target.value as DedupStrategy)}
              >
                {DEDUP_STRATEGIES.map((value) => enumOption(value, DEDUP_STRATEGY_LABELS))}
              </select>
            </div>
          </div>
          <div className="epd-result">
            <span>{copy('current_status')}</span>
            <strong data-testid="epd-publish-status">{policyStatusLabel(publishStatus)}</strong>
          </div>
          {error && (
            <div className="epd-state is-error" data-testid="epd-error">
              {error}
            </div>
          )}
        </section>
      )}

      {step === 'history' && (
        <section className="epd-panel" data-testid="epd-history-panel">
          <div className="epd-panel-heading">
            <div>
              <span className="epd-eyebrow">{copy('version_history')}</span>
              <strong>{copy('current_policy_version')}</strong>
            </div>
          </div>
          <div className="epd-summary-grid">
            <div className="epd-summary-card">
              <span>{copy('current_status')}</span>
              <strong>{policyStatusLabel(selectedPolicy?.status)}</strong>
            </div>
            <div className="epd-summary-card">
              <span>{copy('current_version')}</span>
              <strong>v{selectedPolicy?.version ?? '-'}</strong>
            </div>
            <div className="epd-summary-card">
              <span>{copy('draft_status')}</span>
              <strong>{draftPid ? copy('created') : copy('not_created')}</strong>
            </div>
          </div>
        </section>
      )}

      <pre hidden data-testid="epd-draft-json">
        {JSON.stringify(draftJson)}
      </pre>
    </div>
  );
}

export default EventPolicyDesignerWorkflow;
