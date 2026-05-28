/**
 * B2b batch2 — 4 additional BPMN property editors ported onto flow-designer-sdk
 * via the G2 NodePropertyEditorProps contract.
 *
 * Ported editors:
 *   - ExclusiveGatewayEditor (was ExclusiveGatewayEditor.tsx — 100 LOC)
 *   - InclusiveGatewayEditor (was InclusiveGatewayEditor.tsx — 99 LOC)
 *   - ReceiveTaskEditor      (was ReceiveTaskEditor.tsx — 75 LOC)
 *   - UserTaskEditor (simple) (subset of UserTaskEditor.tsx — picks
 *     description / dueDate / priority / skipable + AssigneeConfig type radio.
 *     The remote-data AssigneePicker (267 LOC) is deferred to batch3 — until
 *     then this editor shows an inert "assignee target" text field that
 *     round-trips assignee.expression / assignee.roleIds[0] verbatim.)
 *   - ConditionExpressionEditor (was ConditionExpressionEditor.tsx — 493 LOC
 *     drop-in. Pure controlled component with no external deps beyond
 *     useI18n. We wrap it with a G2 NodePropertyEditorProps adapter that
 *     reads/writes config.condition rather than taking it as a separate
 *     prop — this lets the SDK property panel host it through the same
 *     propertyEditor slot used by all other batch1/2 editors.)
 *
 * Contract diff vs. legacy:
 *   The legacy ExclusiveGateway/InclusiveGateway editors took
 *     { config, onChange(full config), outgoingEdges }
 *   …with the host having to compute outgoingEdges by walking the BPMN store.
 *   G2 NodePropertyEditorProps gives us
 *     { nodeId, config, onChange(patch) }
 *   so we use the G7 useNodeNeighbors(nodeId) hook to derive outgoing edges
 *   directly from the SDK store. This eliminates the prop-drilling round-trip
 *   AND keeps the dropdown live as edges are added/removed.
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import {
  useNodeNeighbors,
  type NodePropertyEditorProps,
} from '~/plugins/core-designer/components/flow-designer-sdk';
import type {
  ExclusiveGatewayConfig,
  InclusiveGatewayConfig,
  ReceiveTaskConfig,
  UserTaskConfig,
  ConditionExpression,
} from '~/plugins/core-designer/components/bpmn-designer/types';

// ===========================================================================
// ExclusiveGatewayEditor — G7 (useNodeNeighbors) replaces outgoingEdges prop
// ===========================================================================

export function ExclusiveGatewayEditor({
  nodeId,
  config,
  onChange,
  readOnly,
}: NodePropertyEditorProps) {
  const { t } = useI18n();
  const c = (config ?? {}) as unknown as ExclusiveGatewayConfig;

  // G7 — derive outgoing edges live from the SDK store (instead of taking
  // them as a prop). This is the call site the G7 API was designed for.
  const neighbors = useNodeNeighbors(nodeId);
  const outgoingEdges = useMemo(
    () =>
      neighbors.outgoing.map((e) => ({
        id: e.id,
        label: (e.data as any)?.label as string | undefined,
        condition: ((e.data as any)?.condition as ConditionExpression | undefined)?.content,
      })),
    [neighbors.outgoing],
  );

  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.common.description')}
        </label>
        <textarea
          value={c.description ?? ''}
          onChange={(e) => onChange({ description: e.target.value })}
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
          rows={2}
          placeholder={t('bpmn.gateway.exclusiveDescPlaceholder')}
          data-testid="bpm-sdk-exclusive-description"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.gateway.defaultFlow')}
        </label>
        <select
          value={c.defaultFlow ?? ''}
          onChange={(e) => onChange({ defaultFlow: e.target.value })}
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
          data-testid="bpm-sdk-exclusive-default-flow"
        >
          <option value="">{t('bpmn.gateway.noDefaultFlow')}</option>
          {outgoingEdges.map((edge) => (
            <option key={edge.id} value={edge.id}>
              {edge.label || edge.id}
            </option>
          ))}
        </select>
        <p className="mt-1 text-xs text-gray-500">{t('bpmn.gateway.defaultFlowHint')}</p>
      </div>

      {outgoingEdges.length > 0 && (
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">
            {t('bpmn.gateway.outgoingConditions')}
          </label>
          <div
            className="rounded-md border border-gray-200 bg-gray-50 p-3"
            data-testid="bpm-sdk-exclusive-conditions-summary"
          >
            {outgoingEdges.map((edge) => {
              const isDefault = c.defaultFlow === edge.id;
              return (
                <div
                  key={edge.id}
                  className="flex items-start gap-2 py-1 text-sm text-gray-700"
                >
                  <span className="shrink-0 text-gray-400">→</span>
                  <div className="min-w-0 flex-1">
                    {isDefault ? (
                      <span className="italic text-gray-500">
                        {t('bpmn.gateway.defaultFlowTag')}
                      </span>
                    ) : (
                      <>
                        <span className="font-medium">{edge.label || edge.id}</span>
                        {edge.condition && (
                          <span className="ml-1 text-gray-500">: {edge.condition}</span>
                        )}
                        {!edge.condition && (
                          <span className="ml-1 text-xs text-amber-600">
                            {t('bpmn.gateway.noConditionSet')}
                          </span>
                        )}
                      </>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </>
  );
}

// ===========================================================================
// InclusiveGatewayEditor — same G7 pattern, plus disabled completionCondition
// ===========================================================================

interface ExtendedInclusiveGatewayConfig extends InclusiveGatewayConfig {
  completionCondition?: string;
}

export function InclusiveGatewayEditor({
  nodeId,
  config,
  onChange,
  readOnly,
}: NodePropertyEditorProps) {
  const { t } = useI18n();
  const c = (config ?? {}) as unknown as ExtendedInclusiveGatewayConfig;

  const neighbors = useNodeNeighbors(nodeId);
  const outgoingEdges = useMemo(
    () =>
      neighbors.outgoing.map((e) => ({
        id: e.id,
        label: (e.data as any)?.label as string | undefined,
      })),
    [neighbors.outgoing],
  );

  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.common.description')}
        </label>
        <textarea
          value={c.description ?? ''}
          onChange={(e) => onChange({ description: e.target.value })}
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
          rows={2}
          placeholder={t('bpmn.gateway.inclusiveDescPlaceholder')}
          data-testid="bpm-sdk-inclusive-description"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.gateway.defaultFlow')}
        </label>
        <select
          value={c.defaultFlow ?? ''}
          onChange={(e) => onChange({ defaultFlow: e.target.value })}
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
          data-testid="bpm-sdk-inclusive-default-flow"
        >
          <option value="">{t('bpmn.gateway.noDefaultFlow')}</option>
          {outgoingEdges.map((edge) => (
            <option key={edge.id} value={edge.id}>
              {edge.label || edge.id}
            </option>
          ))}
        </select>
        <p className="mt-1 text-xs text-gray-500">{t('bpmn.gateway.defaultFlowHint')}</p>
      </div>

      {/*
        GAP-252 (mirrored from legacy InclusiveGatewayEditor): completionCondition
        is unsupported end-to-end in SmartEngine. We keep the disabled textarea
        verbatim so the visual UX matches the legacy editor.
      */}
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.gateway.completionCondition')}
        </label>
        <textarea
          value={c.completionCondition ?? ''}
          disabled
          readOnly
          data-testid="bpm-sdk-inclusive-completion-condition"
          className="w-full rounded-md border border-gray-300 bg-gray-100 px-3 py-2 text-sm text-gray-500"
          rows={2}
          placeholder="${nrOfCompletedInstances >= 1}"
        />
        <p className="mt-1 text-xs text-amber-600">
          {t('bpmn.prop.inclusivegateway.completionConditionUnsupported') ||
            'Unsupported: SmartEngine InclusiveGatewayParser does not read <completionCondition>; default BPMN join semantics apply. Needs runtime support (parser + behavior), not just UI enable.'}
        </p>
      </div>
    </>
  );
}

// ===========================================================================
// ReceiveTaskEditor — pure G2 port (no G7 needed; no out-edges to enumerate)
// ===========================================================================

export function ReceiveTaskEditor({ config, onChange, readOnly }: NodePropertyEditorProps) {
  const { t } = useI18n();
  const c = (config ?? {}) as unknown as ReceiveTaskConfig;

  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.common.description')}
        </label>
        <textarea
          value={c.description ?? ''}
          onChange={(e) => onChange({ description: e.target.value })}
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          rows={2}
          data-testid="bpm-sdk-receive-description"
        />
      </div>

      {/*
        GAP-252 (mirrored from legacy ReceiveTaskEditor): SmartEngine has no
        <bpmn:message> parser/correlation; messageRef and messageType cannot
        round-trip through the engine. Kept disabled to preserve visual UX.
      */}
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.prop.receivetask.messageRef')}
        </label>
        <input
          type="text"
          value={c.messageRef ?? ''}
          disabled
          readOnly
          data-testid="bpm-sdk-receive-message-ref"
          className="w-full rounded-md border border-gray-300 bg-gray-100 px-3 py-2 text-gray-500"
        />
        <p className="mt-1 text-xs text-amber-600">
          {t('bpmn.prop.receivetask.messageUnsupported') ||
            'Unsupported: SmartEngine has no <bpmn:message> parser/correlation. ReceiveTask only advances via signal() API. Needs runtime support, not just UI enable.'}
        </p>
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.prop.receivetask.messageType')}
        </label>
        <input
          type="text"
          value={c.messageType ?? ''}
          disabled
          readOnly
          data-testid="bpm-sdk-receive-message-type"
          className="w-full rounded-md border border-gray-300 bg-gray-100 px-3 py-2 text-gray-500"
        />
        <p className="mt-1 text-xs text-amber-600">
          {t('bpmn.prop.receivetask.messageUnsupported') ||
            'Unsupported: SmartEngine has no <bpmn:message> parser/correlation. ReceiveTask only advances via signal() API. Needs runtime support, not just UI enable.'}
        </p>
      </div>
    </>
  );
}

// ===========================================================================
// UserTaskEditor (simple subset) — AssigneePicker deferred to batch3
// ===========================================================================

const ASSIGNEE_TYPES = ['user', 'role', 'dept', 'starter', 'expression'] as const;
type AssigneeType = (typeof ASSIGNEE_TYPES)[number];

/**
 * Batch2 port: covers description / dueDate / priority / skipable +
 * an inert assignee.type radio + a free-text "target" that round-trips
 * verbatim into the matching assignee shape. The full AssigneePicker
 * (267 LOC remote data) lands in batch3.
 */
export function UserTaskEditor({ config, onChange, readOnly }: NodePropertyEditorProps) {
  const { t } = useI18n();
  const c = (config ?? {}) as unknown as UserTaskConfig;
  const assignee = c.assignee;
  const assigneeType: AssigneeType = (assignee?.type as AssigneeType | undefined) ?? 'user';

  // Render a single editable "target" string for whichever assignee.type is
  // selected. We map it back into the canonical AssigneeConfig shape so the
  // JSON written through onChange round-trips through the legacy editor too.
  const targetValue = useMemo(() => {
    if (!assignee) return '';
    switch (assignee.type) {
      case 'user':
        return (assignee.userIds ?? []).join(',');
      case 'role':
        return (assignee.roleIds ?? []).join(',');
      case 'dept':
        return (assignee.deptIds ?? []).join(',');
      case 'starter':
        return '';
      case 'expression':
        return assignee.expression ?? '';
      default:
        return '';
    }
  }, [assignee]);

  const writeAssignee = useCallback(
    (nextType: AssigneeType, nextTarget: string) => {
      const ids = nextTarget
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean);
      let next: Record<string, unknown> = { type: nextType };
      switch (nextType) {
        case 'user':
          next = { type: 'user', userIds: ids };
          break;
        case 'role':
          next = { type: 'role', roleIds: ids };
          break;
        case 'dept':
          next = { type: 'dept', deptIds: ids };
          break;
        case 'starter':
          next = { type: 'starter' };
          break;
        case 'expression':
          next = { type: 'expression', expression: nextTarget };
          break;
      }
      onChange({ assignee: next });
    },
    [onChange],
  );

  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.common.description')}
        </label>
        <textarea
          value={c.description ?? ''}
          onChange={(e) => onChange({ description: e.target.value })}
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          rows={2}
          data-testid="bpm-sdk-user-description"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.prop.usertask.assigneeType')}
        </label>
        <select
          value={assigneeType}
          onChange={(e) => writeAssignee(e.target.value as AssigneeType, targetValue)}
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          data-testid="bpm-sdk-user-assignee-type"
        >
          {ASSIGNEE_TYPES.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
      </div>

      {assigneeType !== 'starter' && (
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">
            {t('bpmn.prop.usertask.assigneeTarget')}
          </label>
          <input
            type="text"
            value={targetValue}
            onChange={(e) => writeAssignee(assigneeType, e.target.value)}
            disabled={readOnly}
            className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-xs"
            placeholder={
              assigneeType === 'expression'
                ? '${variable.expression}'
                : 'id1,id2,id3'
            }
            data-testid="bpm-sdk-user-assignee-target"
          />
          <p className="mt-1 text-xs text-gray-500">
            {/* TODO(batch3): replace with full AssigneePicker (remote data) */}
            Batch3 will replace this free-text field with the live AssigneePicker.
          </p>
        </div>
      )}

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.prop.usertask.dueDate')}
        </label>
        <input
          type="text"
          value={c.dueDate ?? ''}
          onChange={(e) => onChange({ dueDate: e.target.value })}
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-xs"
          placeholder="P1D / 2026-01-01T10:00:00"
          data-testid="bpm-sdk-user-due-date"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t('bpmn.prop.usertask.priority')}
        </label>
        <input
          type="number"
          value={c.priority ?? ''}
          onChange={(e) =>
            onChange({
              priority: e.target.value === '' ? undefined : Number(e.target.value),
            })
          }
          disabled={readOnly}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          data-testid="bpm-sdk-user-priority"
        />
      </div>

      <div className="mb-4">
        <label className="flex items-center">
          <input
            type="checkbox"
            checked={c.skipable ?? false}
            onChange={(e) => onChange({ skipable: e.target.checked })}
            disabled={readOnly}
            className="mr-2"
            data-testid="bpm-sdk-user-skipable"
          />
          <span className="text-sm font-medium text-gray-700">
            {t('bpmn.prop.usertask.skipable')}
          </span>
        </label>
      </div>

      {/*
        NOTE: multiInstance / formBindings / hooks / aura policies all live
        inside shared.tsx (627 LOC — MultiInstanceSection / FormBindingSection /
        HookConfigSection). Those land alongside the shared.tsx port in batch3.
      */}
    </>
  );
}

// ===========================================================================
// ConditionExpressionEditor — drop-in port (493 LOC), wrapped with G2 adapter
// ===========================================================================

/**
 * NOTE: ConditionExpression lives on EDGES not nodes (BPMNEdgeData.condition).
 * G2 NodePropertyEditorProps is the contract for NODE editors. Sequence-flow
 * editing in the SDK goes through G2's edge-equivalent (EdgePropertyEditorProps),
 * but the underlying ConditionExpressionEditor body has zero awareness of where
 * the condition lives — it's a pure controlled `{condition, onChange}` UI.
 *
 * To keep the batch2 surface honest:
 *   - We port the body verbatim as a pure component (`ConditionExpressionBody`)
 *     that exactly mirrors the legacy editor, so consumers can use it from
 *     either NodePropertyEditor or EdgePropertyEditor adapters.
 *   - We additionally expose a G2 adapter (`ConditionExpressionEditor`) that
 *     reads/writes `config.condition` for the (rare) case a future node type
 *     embeds a condition directly.
 *
 * The body is byte-equivalent to the legacy editor — same operators, same
 * parser, same simple/advanced mode switching, same parse warning behaviour.
 */

interface ConditionRule {
  field: string;
  operator: string;
  value: string;
}

type LogicalOperator = 'and' | 'or';
type EditorMode = 'simple' | 'advanced';

interface ConditionExpressionBodyProps {
  condition?: ConditionExpression;
  onChange: (condition: ConditionExpression) => void;
}

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
  { value: 'mvel', label: 'MVEL' },
  { value: 'juel', label: 'JUEL' },
];

function isNumericLike(v: string): boolean {
  if (!v) return false;
  return /^-?\d+(\.\d+)?$/.test(v);
}

function rulesToExpression(rules: ConditionRule[], logicalOp: LogicalOperator): string {
  if (rules.length === 0) return '';
  const connector = logicalOp === 'and' ? ' && ' : ' || ';
  const parts = rules.map((r) => {
    if (!r.field) return '';
    if (r.operator === 'is_empty') return `empty ${r.field}`;
    if (r.operator === 'is_not_empty') return `!empty ${r.field}`;
    if (r.operator === 'contains') return `${r.field}.contains('${r.value}')`;
    const val = isNumericLike(r.value) ? r.value : `'${r.value}'`;
    return `${r.field} ${r.operator} ${val}`;
  });
  const body = parts.filter(Boolean).join(connector);
  return body ? `\${${body}}` : '';
}

function parseSegment(seg: string): ConditionRule | null {
  const emptyMatch = seg.match(/^empty\s+(\w+)$/);
  if (emptyMatch) return { field: emptyMatch[1], operator: 'is_empty', value: '' };
  const notEmptyMatch = seg.match(/^!empty\s+(\w+)$/);
  if (notEmptyMatch) return { field: notEmptyMatch[1], operator: 'is_not_empty', value: '' };
  const containsMatch = seg.match(/^(\w+)\.contains\(\s*'([^']*)'\s*\)$/);
  if (containsMatch)
    return { field: containsMatch[1], operator: 'contains', value: containsMatch[2] };
  const cmpMatch = seg.match(/^(\w+)\s*(>=|<=|!=|==|>|<)\s*(.+)$/);
  if (cmpMatch) {
    let val = cmpMatch[3].trim();
    if (
      (val.startsWith("'") && val.endsWith("'")) ||
      (val.startsWith('"') && val.endsWith('"'))
    ) {
      val = val.slice(1, -1);
    }
    return { field: cmpMatch[1], operator: cmpMatch[2], value: val };
  }
  return null;
}

function tryParseRules(
  expr: string,
): { rules: ConditionRule[]; logicalOp: LogicalOperator } | null {
  if (!expr) return null;
  let body = expr.trim();
  if (body.startsWith('${') && body.endsWith('}')) {
    body = body.slice(2, -1).trim();
  } else {
    return null;
  }
  if (!body) return null;
  const hasAnd = body.includes('&&');
  const hasOr = body.includes('||');
  if (hasAnd && hasOr) return null;
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

// Exposed for tests + downstream consumers — keeps parser logic referenceable.
export const __conditionInternals = {
  rulesToExpression,
  tryParseRules,
  isNumericLike,
};

export function ConditionExpressionBody({ condition, onChange }: ConditionExpressionBodyProps) {
  const { t } = useI18n();

  const initialParse = useMemo(
    () => tryParseRules(condition?.content || ''),
    // mount-only; mirrors legacy editor behaviour to avoid surprise mode flips
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  const [mode, setMode] = useState<EditorMode>(
    initialParse || !condition?.content ? 'simple' : 'advanced',
  );
  const [rules, setRules] = useState<ConditionRule[]>(
    initialParse?.rules || [{ field: '', operator: '==', value: '' }],
  );
  const [logicalOp, setLogicalOp] = useState<LogicalOperator>(initialParse?.logicalOp || 'and');
  const [content, setContent] = useState(condition?.content || '');
  const [type, setType] = useState<ConditionExpression['type']>(condition?.type || 'expression');
  const [language, setLanguage] = useState<ConditionExpression['language']>(
    condition?.language || 'mvel',
  );
  const [parseWarning, setParseWarning] = useState(false);

  // Keep ruleCode pass-through stable across all writes.
  const ruleCodeRef = condition?.ruleCode;
  useEffect(() => {
    // no-op — ruleCode is preserved on every onChange below
  }, [ruleCodeRef]);

  const syncSimple = useCallback(
    (nextRules: ConditionRule[], nextOp: LogicalOperator) => {
      const expr = rulesToExpression(nextRules, nextOp);
      onChange({
        type: 'expression',
        content: expr,
        language: undefined,
        ruleCode: ruleCodeRef,
      });
    },
    [onChange, ruleCodeRef],
  );

  const syncAdvanced = useCallback(
    (
      nextContent: string,
      nextType: ConditionExpression['type'],
      nextLang?: ConditionExpression['language'],
    ) => {
      onChange({
        type: nextType,
        content: nextContent,
        language: nextType === 'script' ? nextLang : undefined,
        ruleCode: ruleCodeRef,
      });
    },
    [onChange, ruleCodeRef],
  );

  const switchToAdvanced = useCallback(() => {
    const expr = rulesToExpression(rules, logicalOp);
    setContent(expr);
    setType('expression');
    setParseWarning(false);
    setMode('advanced');
  }, [rules, logicalOp]);

  const switchToSimple = useCallback(() => {
    if (!content.trim()) {
      setParseWarning(false);
      setMode('simple');
      return;
    }
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

  return (
    <div className="space-y-3" data-testid="bpm-sdk-condition-editor">
      <div className="flex rounded-md border border-gray-300">
        <button
          type="button"
          onClick={mode === 'simple' ? undefined : switchToSimple}
          className={`flex-1 px-3 py-1.5 text-xs font-medium transition-colors ${
            mode === 'simple'
              ? 'bg-blue-50 text-blue-700'
              : 'bg-white text-gray-600 hover:bg-gray-50'
          } rounded-l-md`}
          data-testid="bpm-sdk-condition-mode-simple"
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
          data-testid="bpm-sdk-condition-mode-advanced"
        >
          {t('bpmn.condition.advancedMode')}
        </button>
      </div>

      {parseWarning && (
        <div
          className="rounded-md bg-amber-50 px-3 py-2 text-xs text-amber-700"
          data-testid="bpm-sdk-condition-parse-warning"
        >
          {t('bpmn.condition.parseWarning')}
        </div>
      )}

      {mode === 'simple' && (
        <div className="space-y-2">
          {rules.map((rule, index) => (
            <div key={index}>
              {index > 0 && (
                <div className="flex items-center justify-center py-1">
                  <button
                    type="button"
                    onClick={toggleLogicalOp}
                    className="rounded-full bg-gray-100 px-3 py-0.5 text-xs font-medium text-gray-600 hover:bg-gray-200"
                    data-testid="bpm-sdk-condition-logical-op"
                  >
                    {logicalOp === 'and' ? 'AND' : 'OR'}
                  </button>
                </div>
              )}
              <div className="flex items-start gap-1">
                <div className="min-w-0 flex-1 space-y-1">
                  <input
                    type="text"
                    value={rule.field}
                    onChange={(e) => updateRule(index, 'field', e.target.value)}
                    className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-xs"
                    placeholder={t('bpmn.condition.fieldPlaceholder')}
                    data-testid={`bpm-sdk-condition-field-${index}`}
                  />
                  <select
                    value={rule.operator}
                    onChange={(e) => updateRule(index, 'operator', e.target.value)}
                    className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-xs"
                    data-testid={`bpm-sdk-condition-op-${index}`}
                  >
                    {OPERATORS.map((op) => (
                      <option key={op.value} value={op.value}>
                        {op.i18nKey ? t(op.i18nKey) : op.label}
                      </option>
                    ))}
                  </select>
                  {!UNARY_OPERATORS.has(rule.operator) && (
                    <input
                      type="text"
                      value={rule.value}
                      onChange={(e) => updateRule(index, 'value', e.target.value)}
                      className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-xs"
                      placeholder={t('bpmn.condition.valuePlaceholder')}
                      data-testid={`bpm-sdk-condition-value-${index}`}
                    />
                  )}
                </div>
                <button
                  type="button"
                  onClick={() => removeRule(index)}
                  className="mt-1 shrink-0 rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-red-500"
                  title={t('bpmn.condition.removeRule')}
                  data-testid={`bpm-sdk-condition-remove-${index}`}
                >
                  ×
                </button>
              </div>
            </div>
          ))}

          <button
            type="button"
            onClick={addRule}
            className="w-full rounded-md border border-dashed border-blue-300 py-1.5 text-xs text-blue-600 hover:bg-blue-50 hover:text-blue-800"
            data-testid="bpm-sdk-condition-add-rule"
          >
            {t('bpmn.condition.addRule')}
          </button>

          {rules.some((r) => r.field) && (
            <div className="rounded-md bg-gray-50 px-2 py-1.5">
              <p className="mb-0.5 text-[10px] font-medium text-gray-500">
                {t('bpmn.condition.preview')}
              </p>
              <code
                className="block break-all text-[11px] text-gray-700"
                data-testid="bpm-sdk-condition-preview"
              >
                {rulesToExpression(rules, logicalOp)}
              </code>
            </div>
          )}
        </div>
      )}

      {mode === 'advanced' && (
        <div className="space-y-2">
          <div className="flex gap-2">
            <div className="flex-1">
              <label className="mb-0.5 block text-[10px] font-medium text-gray-500">
                {t('bpmn.condition.type')}
              </label>
              <select
                value={type}
                onChange={(e) =>
                  handleTypeChange(e.target.value as ConditionExpression['type'])
                }
                className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-xs"
                data-testid="bpm-sdk-condition-type"
              >
                <option value="expression">{t('bpmn.condition.typeExpression')}</option>
                <option value="script">{t('bpmn.condition.typeScript')}</option>
              </select>
            </div>
            {type === 'script' && (
              <div className="flex-1">
                <label className="mb-0.5 block text-[10px] font-medium text-gray-500">
                  {t('bpmn.condition.language')}
                </label>
                <select
                  value={language || 'mvel'}
                  onChange={(e) =>
                    handleLanguageChange(e.target.value as ConditionExpression['language'])
                  }
                  className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-xs"
                  data-testid="bpm-sdk-condition-language"
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
          <textarea
            value={content}
            onChange={(e) => handleContentChange(e.target.value)}
            className="w-full rounded-md border border-gray-300 px-2 py-2 font-mono text-xs leading-relaxed"
            rows={4}
            placeholder={t('bpmn.condition.advancedPlaceholder')}
            data-testid="bpm-sdk-condition-advanced-content"
          />
          <p className="text-[10px] leading-relaxed text-gray-400">
            {t('bpmn.condition.helpText')}
          </p>
        </div>
      )}
    </div>
  );
}

/**
 * G2 adapter that reads/writes `config.condition`. Most BPM sequence-flow use
 * cases will host ConditionExpressionBody directly through an EdgePropertyEditor
 * adapter in B2c, but this lets a node type opt-in to embedding a condition too.
 */
export function ConditionExpressionEditor({ config, onChange }: NodePropertyEditorProps) {
  const condition = (config?.condition as ConditionExpression | undefined) ?? undefined;
  return (
    <ConditionExpressionBody
      condition={condition}
      onChange={(next) => onChange({ condition: next })}
    />
  );
}
