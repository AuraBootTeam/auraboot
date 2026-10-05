import { useEffect, useMemo, useRef, useState } from 'react'
import {
  DecisionRuleBindingBlock,
  type DecisionOption,
  type RuleConsumerBindingDraft,
} from '~/ui/smart/decision/DecisionRuleBindingBlock'
import type {
  ConditionFragment,
  ConditionFragmentUpsertRequest,
  DecisionAction,
  DecisionApi,
  DecisionTableAnalysis,
  DecisionTableDmnXmlResult,
} from '../api/decisionApi'
import type { DecisionTable } from '../table/decisionTable'
import type { FieldOption } from './ConditionBuilder'
import { DecisionTableEditor } from './DecisionTableEditor'
import { group, type GroupNode, type Operator, type Scope } from '../ast/conditionAst'
import { useSmartText } from '~/utils/i18n'
import { dataTypeLabel, scenarioScopeLabel, scopeLabel } from './displayLabels'
import { downloadDmnXml } from './dmnDownload'
import {
  strategyStudioText,
  useStrategyStudioText,
  type StrategyStudioTextFn,
  type StrategyStudioTextKey,
} from './strategyStudioText'

// Default/demo data keeps its zh-CN source values (identical to the previous
// literals) so business payloads stay stable; the catalog is the single home
// for the text so the locale contract tests can resolve every side.
const zhText = (key: StrategyStudioTextKey) => strategyStudioText(key, 'zh-CN')

type StrategyScenarioKey = 'SLA' | 'BPM' | 'AUTOMATION' | 'PERMISSION' | 'EVENT_POLICY'
type StrategyWorkspacePanelKey = 'rule' | 'facts' | 'dmn' | 'review'

interface StrategyScenario {
  key: StrategyScenarioKey
  label: string
  title: string
  consumer: string
  trigger: string
  ruleCode: string
  decisionCode: string
  fragment: string
  actionTypes: string[]
  blockers: number
  modelCodes: string[]
  fields: FieldOption[]
}

type RuntimeDecisionTableInput = Omit<DecisionTable['inputs'][number], 'scope' | 'path' | 'dataType'> & {
  expr: {
    type: 'path'
    scope: Scope
    path: string
    dataType: DecisionTable['inputs'][number]['dataType']
  }
}

type RuntimeDecisionTable = Omit<DecisionTable, 'inputs'> & {
  inputs: RuntimeDecisionTableInput[]
}

const SLA_NODE_VALUE_LABELS: Record<string, string> = {
  task_manager_approve: zhText('valueTaskManagerApprove'),
  task_hr_approve: zhText('valueTaskHrApprove'),
};

const PROCESS_NODE_VALUE_LABELS: Record<string, string> = {
  task_manager_approve: zhText('valueTaskManagerApprove'),
  task_hr_approve: zhText('valueTaskHrApprove'),
  gw_manager: zhText('valueGwManager'),
};

const LEAVE_TYPE_VALUE_LABELS: Record<string, string> = {
  annual: zhText('valueLeaveAnnual'),
  sick: zhText('valueLeaveSick'),
  personal: zhText('valueLeavePersonal'),
};

const LEAVE_TYPE_FIELD: FieldOption = {
  scope: 'record',
  path: 'data.wd_req_type',
  label: zhText('fieldLeaveType'),
  dataType: 'dict',
  options: Object.keys(LEAVE_TYPE_VALUE_LABELS),
  valueLabels: LEAVE_TYPE_VALUE_LABELS,
  modelCode: 'wd_leave_request',
  modelName: zhText('modelNameLeaveRequest'),
  dictCode: 'wd_leave_type',
};

const LEAVE_APPLICANT_REFERENCE_FIELD: FieldOption = {
  scope: 'record',
  path: 'data.wd_req_applicant',
  label: zhText('fieldApplicant'),
  dataType: 'user',
  modelCode: 'wd_leave_request',
  modelName: zhText('modelNameLeaveRequest'),
  reference: {
    targetEntity: 'sys_user',
    valueField: 'pid',
    displayField: 'displayName',
  },
};

export interface StrategyStudioWorkbenchProps {
  fields: FieldOption[]
  decisions?: DecisionOption[]
  api: DecisionApi
  conditionFragments?: ConditionFragment[]
  conditionFragmentsLoading?: boolean
  conditionFragmentsError?: boolean
}

const SCENARIOS: StrategyScenario[] = [
  {
    key: 'SLA',
    label: zhText('scenarioSlaLabel'),
    title: zhText('scenarioSlaTitle'),
    consumer: zhText('scenarioSlaConsumer'),
    trigger: zhText('scenarioTriggerEnterApproval'),
    ruleCode: 'wd_manager_approve_sla',
    decisionCode: 'complaint_sla_deadline',
    fragment: zhText('scenarioSlaFragment'),
    actionTypes: ['NOTIFY', 'WRITE_AUDIT'],
    blockers: 0,
    modelCodes: ['sla_config', 'wd_leave_request'],
    fields: [
      {
        scope: 'record',
        path: 'data.targetKey',
        label: zhText('fieldSlaNode'),
        dataType: 'string',
        options: Object.keys(SLA_NODE_VALUE_LABELS),
        valueLabels: SLA_NODE_VALUE_LABELS,
      },
      { scope: 'sla', path: 'deadlineMinutes', label: zhText('fieldDeadlineMinutes'), dataType: 'integer' },
      { scope: 'sla', path: 'warningBeforeMinutes', label: zhText('fieldAdvanceReminder'), dataType: 'integer' },
      LEAVE_TYPE_FIELD,
      LEAVE_APPLICANT_REFERENCE_FIELD,
      {
        scope: 'process',
        path: 'nodeId',
        label: zhText('fieldProcessNode'),
        dataType: 'string',
        options: Object.keys(PROCESS_NODE_VALUE_LABELS),
        valueLabels: PROCESS_NODE_VALUE_LABELS,
      },
    ],
  },
  {
    key: 'BPM',
    label: zhText('scenarioBpmLabel'),
    consumer: zhText('scenarioBpmConsumer'),
    title: zhText('scenarioBpmTitle'),
    trigger: zhText('scenarioTriggerEnterApproval'),
    ruleCode: 'wd_leave_approval',
    decisionCode: 'approval_routing',
    fragment: zhText('scenarioBpmFragment'),
    actionTypes: ['ADD_COMMENT', 'WRITE_AUDIT'],
    blockers: 0,
    modelCodes: ['wd_leave_request'],
    fields: [
      {
        scope: 'process',
        path: 'nodeId',
        label: zhText('fieldProcessNode'),
        dataType: 'string',
        options: Object.keys(PROCESS_NODE_VALUE_LABELS),
        valueLabels: PROCESS_NODE_VALUE_LABELS,
      },
      { scope: 'record', path: 'data.wd_req_days', label: zhText('fieldLeaveDays'), dataType: 'decimal' },
      LEAVE_TYPE_FIELD,
      LEAVE_APPLICANT_REFERENCE_FIELD,
      { scope: 'actor', path: 'roles', label: zhText('fieldApproverRoles'), dataType: 'collection' },
    ],
  },
  {
    key: 'AUTOMATION',
    label: zhText('scenarioAutomationLabel'),
    title: zhText('scenarioAutomationTitle'),
    consumer: zhText('scenarioAutomationConsumer'),
    trigger: zhText('scenarioTriggerLeaveCreated'),
    ruleCode: 'wd_leave_high_value_notify',
    decisionCode: 'leave_request_automation',
    fragment: zhText('scenarioAutomationFragment'),
    actionTypes: ['NOTIFY', 'WRITE_AUDIT'],
    blockers: 0,
    modelCodes: ['wd_leave_request'],
    fields: [
      { scope: 'record', path: 'data.wd_req_days', label: zhText('fieldLeaveDays'), dataType: 'decimal' },
      LEAVE_TYPE_FIELD,
      LEAVE_APPLICANT_REFERENCE_FIELD,
      { scope: 'record', path: 'pid', label: zhText('fieldLeaveRecord'), dataType: 'string' },
      { scope: 'time', path: 'now', label: zhText('fieldTriggerTime'), dataType: 'datetime' },
    ],
  },
  {
    key: 'PERMISSION',
    label: zhText('scenarioPermissionLabel'),
    title: zhText('scenarioPermissionTitle'),
    consumer: zhText('scenarioPermissionConsumer'),
    trigger: zhText('scenarioTriggerPreQueryCheck'),
    ruleCode: 'ABAC_LEAVE_VISIBILITY',
    decisionCode: 'leave_visibility_policy',
    fragment: zhText('scenarioPermissionFragment'),
    actionTypes: ['WRITE_AUDIT'],
    blockers: 0,
    modelCodes: ['wd_leave_request', 'tenant_member', 'department'],
    fields: [
      { scope: 'actor', path: 'orgPath', label: zhText('fieldOrgPath'), dataType: 'department' },
      { scope: 'record', path: 'data.departmentId', label: zhText('fieldRecordDepartment'), dataType: 'department' },
      LEAVE_TYPE_FIELD,
      LEAVE_APPLICANT_REFERENCE_FIELD,
      { scope: 'tenant', path: 'id', label: zhText('fieldTenant'), dataType: 'string' },
    ],
  },
  {
    key: 'EVENT_POLICY',
    label: zhText('scenarioEventPolicyLabel'),
    title: zhText('scenarioEventPolicyTitle'),
    consumer: zhText('scenarioEventPolicyConsumer'),
    trigger: zhText('scenarioTriggerLeaveCreatedEvent'),
    ruleCode: 'leave_request_event_policy',
    decisionCode: 'leave_request_automation',
    fragment: zhText('scenarioEventPolicyFragment'),
    actionTypes: ['NOTIFY', 'WRITE_AUDIT'],
    blockers: 0,
    modelCodes: ['wd_leave_request'],
    fields: [
      { scope: 'event', path: 'type', label: zhText('fieldEventType'), dataType: 'string' },
      { scope: 'record', path: 'data.wd_req_days', label: zhText('fieldLeaveDays'), dataType: 'decimal' },
      LEAVE_TYPE_FIELD,
      LEAVE_APPLICANT_REFERENCE_FIELD,
      { scope: 'actor', path: 'roles', label: zhText('fieldTriggerActorRoles'), dataType: 'collection' },
    ],
  },
]

const WORKSPACE_PANELS: Array<{ key: StrategyWorkspacePanelKey }> = [
  { key: 'rule' },
  { key: 'facts' },
  { key: 'dmn' },
  { key: 'review' },
]

// Chrome copy for the workspace tabs; resolved at render time via the catalog.
const WORKSPACE_PANEL_TEXT: Record<
  StrategyWorkspacePanelKey,
  { label: StrategyStudioTextKey; summary: StrategyStudioTextKey }
> = {
  rule: { label: 'panelRuleLabel', summary: 'panelRuleSummary' },
  facts: { label: 'panelFactsLabel', summary: 'panelFactsSummary' },
  dmn: { label: 'panelDmnLabel', summary: 'panelDmnSummary' },
  review: { label: 'panelReviewLabel', summary: 'panelReviewSummary' },
}

// Scenario-level vocabulary keyed by the stable scenario enum so the chrome
// localizes while the zh-CN constants above keep feeding business payloads.
const SCENARIO_TEXT: Record<
  StrategyScenarioKey,
  {
    label: StrategyStudioTextKey
    title: StrategyStudioTextKey
    consumer: StrategyStudioTextKey
    trigger: StrategyStudioTextKey
  }
> = {
  SLA: {
    label: 'scenarioSlaLabel',
    title: 'scenarioSlaTitle',
    consumer: 'scenarioSlaConsumer',
    trigger: 'scenarioTriggerEnterApproval',
  },
  BPM: {
    label: 'scenarioBpmLabel',
    title: 'scenarioBpmTitle',
    consumer: 'scenarioBpmConsumer',
    trigger: 'scenarioTriggerEnterApproval',
  },
  AUTOMATION: {
    label: 'scenarioAutomationLabel',
    title: 'scenarioAutomationTitle',
    consumer: 'scenarioAutomationConsumer',
    trigger: 'scenarioTriggerLeaveCreated',
  },
  PERMISSION: {
    label: 'scenarioPermissionLabel',
    title: 'scenarioPermissionTitle',
    consumer: 'scenarioPermissionConsumer',
    trigger: 'scenarioTriggerPreQueryCheck',
  },
  EVENT_POLICY: {
    label: 'scenarioEventPolicyLabel',
    title: 'scenarioEventPolicyTitle',
    consumer: 'scenarioEventPolicyConsumer',
    trigger: 'scenarioTriggerLeaveCreatedEvent',
  },
}

const NO_FRAGMENT_LABEL = zhText('noFragmentSelected')

const DECISIONS = [
  {
    code: 'complaint_sla_deadline',
    name: zhText('decisionComplaintSlaDeadline'),
    outputs: [
      { id: 'deadlineMinutes', label: zhText('fieldDeadlineMinutes'), dataType: 'integer' },
      { id: 'warningBeforeMinutes', label: zhText('outputAdvanceReminderMinutes'), dataType: 'integer' },
      { id: 'escalationLevel', label: zhText('outputEscalationLevel'), dataType: 'string' },
    ],
  },
  {
    code: 'approval_routing',
    name: zhText('decisionApprovalRouting'),
    outputs: [
      { id: 'candidateGroups', label: zhText('outputCandidateGroups'), dataType: 'collection' },
      { id: 'assigneeUserId', label: zhText('outputAssignee'), dataType: 'string' },
      { id: 'dueHours', label: zhText('outputDueHours'), dataType: 'integer' },
    ],
  },
  {
    code: 'leave_request_automation',
    name: zhText('decisionLeaveRequestAutomation'),
    outputs: [
      { id: 'route', label: zhText('outputRoute'), dataType: 'string' },
      { id: 'actions', label: zhText('outputActions'), dataType: 'collection' },
    ],
  },
  {
    code: 'leave_visibility_policy',
    name: zhText('decisionLeaveVisibilityPolicy'),
    outputs: [
      { id: 'allow', label: zhText('outputAllow'), dataType: 'boolean' },
      { id: 'reason', label: zhText('outputReason'), dataType: 'string' },
    ],
  },
]

const SAFE_ACTIONS: DecisionAction[] = [
  { actionType: 'NOTIFY', label: zhText('actionNotify'), handlerAvailable: true, category: 'messaging' },
  { actionType: 'START_PROCESS', label: zhText('actionStartProcess'), handlerAvailable: true, category: 'workflow' },
  { actionType: 'ADD_COMMENT', label: zhText('actionAddComment'), handlerAvailable: true, category: 'collaboration' },
  { actionType: 'UPDATE_RECORD', label: zhText('actionUpdateRecord'), handlerAvailable: true, category: 'data' },
  { actionType: 'PATCH_RECORD', label: zhText('actionPatchRecord'), handlerAvailable: true, category: 'data' },
  { actionType: 'WEBHOOK', label: zhText('actionWebhook'), handlerAvailable: true, category: 'integration' },
  { actionType: 'WRITE_AUDIT', label: zhText('actionWriteAudit'), handlerAvailable: true, category: 'governance' },
]

const ACTION_LABELS: Record<string, string> = {
  NOTIFY: zhText('actionNotify'),
  START_PROCESS: zhText('actionStartProcess'),
  ADD_COMMENT: zhText('actionAddComment'),
  UPDATE_RECORD: zhText('actionUpdateRecord'),
  PATCH_RECORD: zhText('actionPatchRecord'),
  WEBHOOK: zhText('actionWebhook'),
  WRITE_AUDIT: zhText('actionWriteAudit'),
  SEND_SMS: zhText('actionSendSms'),
  SEND_IM: zhText('actionSendIm'),
  CREATE_TASK: zhText('actionCreateTask'),
  CC_TASK: zhText('actionCcTask'),
}

// Render-side catalog keys for the platform action vocabulary; payload paths
// keep consuming ACTION_LABELS so stored schemas stay locale-independent.
const ACTION_LABEL_KEYS: Record<string, StrategyStudioTextKey> = {
  NOTIFY: 'actionNotify',
  START_PROCESS: 'actionStartProcess',
  ADD_COMMENT: 'actionAddComment',
  UPDATE_RECORD: 'actionUpdateRecord',
  PATCH_RECORD: 'actionPatchRecord',
  WEBHOOK: 'actionWebhook',
  WRITE_AUDIT: 'actionWriteAudit',
  SEND_SMS: 'actionSendSms',
  SEND_IM: 'actionSendIm',
  CREATE_TASK: 'actionCreateTask',
  CC_TASK: 'actionCcTask',
}

const ACTION_CATEGORY_KEYS: Record<string, StrategyStudioTextKey> = {
  messaging: 'categoryMessaging',
  workflow: 'categoryWorkflow',
  collaboration: 'categoryCollaboration',
  data: 'categoryData',
  integration: 'categoryIntegration',
  governance: 'categoryGovernance',
}

// Built-in decision names are platform vocabulary; a server-provided custom
// name still wins through decisionDisplayName for codes outside this map.
const DECISION_NAME_KEYS: Record<string, StrategyStudioTextKey> = {
  complaint_sla_deadline: 'decisionComplaintSlaDeadline',
  approval_routing: 'decisionApprovalRouting',
  leave_request_automation: 'decisionLeaveRequestAutomation',
  leave_visibility_policy: 'decisionLeaveVisibilityPolicy',
}

function fieldKey(field: Pick<FieldOption, 'scope' | 'path'>): string {
  return `${field.scope}:${field.path}`
}

function mergeFields(primary: FieldOption[], secondary: FieldOption[]): FieldOption[] {
  const seen = new Set<string>()
  return [...primary, ...secondary].filter((field) => {
    const key = fieldKey(field)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

function mergeDecisions(primary: DecisionOption[], secondary: DecisionOption[]): DecisionOption[] {
  const byCode = new Map<string, DecisionOption>()
  secondary.forEach((decision) => byCode.set(decision.code, decision))
  primary.forEach((decision) => byCode.set(decision.code, decision))
  return Array.from(byCode.values())
}

function filterScenarioFields(
  scenario: StrategyScenario,
  fragmentFields: FieldOption[],
  catalogFields: FieldOption[],
): FieldOption[] {
  const explicitKeys = new Set(
    [...scenario.fields, ...fragmentFields].map((field) => fieldKey(field)),
  )
  const modelCodes = new Set(scenario.modelCodes)
  return catalogFields.filter((field) => {
    if (explicitKeys.has(fieldKey(field))) return true
    if (field.scope !== 'record') return true
    return Boolean(field.modelCode && modelCodes.has(field.modelCode))
  })
}

function actionMap(actions: DecisionAction[]): Map<string, DecisionAction> {
  const map = new Map<string, DecisionAction>()
  actions
    .filter((action) => action.actionType && action.handlerAvailable !== false)
    .forEach((action) => map.set(action.actionType, action))
  return map
}

function resolveScenarioActions(
  scenario: StrategyScenario,
  actionsByType: Map<string, DecisionAction>,
): DecisionAction[] {
  return scenario.actionTypes.map((actionType) => actionsByType.get(actionType) ?? {
    actionType,
    label: ACTION_LABELS[actionType] ?? actionType,
    handlerAvailable: false,
  })
}

function actionLabel(action: DecisionAction): string {
  const label = action.label?.trim()
  if (label && label !== action.actionType) {
    return ACTION_LABELS[action.actionType] ?? label
  }
  return ACTION_LABELS[action.actionType] ?? action.actionType
}

function actionDisplayLabel(action: DecisionAction, text: StrategyStudioTextFn): string {
  const key = ACTION_LABEL_KEYS[action.actionType]
  if (key) return text(key)
  const label = action.label?.trim()
  if (label && label !== action.actionType) return label
  return action.actionType
}

function actionCategoryLabel(action: DecisionAction, text: StrategyStudioTextFn): string {
  const category = action.category?.trim()
  if (!category) {
    return text(action.handlerAvailable === false ? 'categoryHandlerMissing' : 'categoryPlatformAction')
  }
  const key = ACTION_CATEGORY_KEYS[category]
  return key ? text(key) : category
}

function actionOutputSchema(actions: DecisionAction[]) {
  return actions
    .filter((action) => action.handlerAvailable !== false)
    .map((action) => ({
      actionType: action.actionType,
      label: actionLabel(action),
      category: action.category,
      inputSchema: action.inputSchema,
    }))
}

function tableOutputSchema(table: DecisionTable) {
  return table.outputs.map((output) => ({
    id: output.id,
    label: output.label,
    dataType: output.dataType,
    allowedValues: output.allowedValues,
    valueLabels: output.valueLabels,
  }))
}

function scenarioDecisionOptions(
  decisions: DecisionOption[],
  scenario: StrategyScenario,
  table: DecisionTable,
): DecisionOption[] {
  const outputs = tableOutputSchema(table)
  return decisions.map((decision) =>
    decision.code === scenario.decisionCode
      ? {
          ...decision,
          outputs,
          outputSchemaJson: {
            ...(typeof decision.outputSchemaJson === 'object' && decision.outputSchemaJson
              ? decision.outputSchemaJson
              : {}),
            outputs,
          },
        }
      : decision,
  )
}

function formatFieldPath(field: FieldOption): string {
  return `${field.scope}.${field.path}`
}

function fieldContextLabel(field: FieldOption): string {
  return field.modelName?.trim() || scopeLabel(field.scope)
}

function fieldDisplayLabel(field: FieldOption, text: StrategyStudioTextFn): string {
  const label = field.label?.trim()
  const path = formatFieldPath(field)
  if (label && label !== path) return label
  return text('fieldContextSuffix', { context: fieldContextLabel(field) })
}

function decisionDisplayName(
  decisions: DecisionOption[],
  decisionCode: string,
  fallback: string,
): string {
  const decisionName = decisions.find((decision) => decision.code === decisionCode)?.name?.trim()
  return decisionName && decisionName !== decisionCode ? decisionName : fallback
}

const FIELD_REF_SCOPES = new Set<Scope>([
  'meta',
  'event',
  'record',
  'before',
  'after',
  'process',
  'task',
  'sla',
  'actor',
  'tenant',
  'time',
  'env',
])

const CONDITION_OPERATORS = new Set<Operator>([
  'EQ',
  'NE',
  'GT',
  'GTE',
  'LT',
  'LTE',
  'IN',
  'NOT_IN',
  'BETWEEN',
  'CONTAINS_TEXT',
  'CONTAINS_ELEMENT',
  'STARTS_WITH',
  'ENDS_WITH',
  'IS_NULL',
  'IS_NOT_NULL',
  'IS_EMPTY',
  'IS_NOT_EMPTY',
  'CHANGED',
  'MATCHES',
])

function scenarioKeyForFragment(fragment: ConditionFragment): StrategyScenarioKey | null {
  const scope = String(fragment.scopeType ?? '').trim().toUpperCase()
  if (scope === 'SLA' || scope === 'SLA_RULE') return 'SLA'
  if (scope === 'BPM' || scope === 'WORKFLOW_PROCESS' || scope === 'WORKFLOW') return 'BPM'
  if (scope === 'AUTOMATION') return 'AUTOMATION'
  if (scope === 'PERMISSION' || scope === 'ABAC') return 'PERMISSION'
  if (scope === 'EVENT_POLICY' || scope === 'EVENTPOLICY' || scope === 'POLICY') {
    return 'EVENT_POLICY'
  }
  return null
}

function scenarioForFragment(fragment: ConditionFragment): StrategyScenario | null {
  const key = scenarioKeyForFragment(fragment)
  return key ? SCENARIOS.find((candidate) => candidate.key === key) ?? null : null
}

function fragmentLabel(fragment: ConditionFragment): string {
  return fragment.fragmentName || fragment.fragmentCode
}

function fragmentListKey(fragment: ConditionFragment): string {
  return [
    fragment.fragmentCode,
    fragment.version ?? 'latest',
    fragment.pid ?? fragment.status ?? 'fragment',
  ].join(':')
}

const FRAGMENT_STATUS_ORDER: Record<string, number> = {
  DRAFT: 10,
  REJECTED: 15,
  VALIDATED: 20,
  PENDING_APPROVAL: 25,
  PUBLISHED: 30,
  DEPRECATED: 35,
  RETIRED: 40,
}

function fragmentStatusOrder(status?: string): number {
  return FRAGMENT_STATUS_ORDER[String(status ?? '').toUpperCase()] ?? 0
}

function fragmentTime(fragment: ConditionFragment): number {
  const raw = fragment.updatedAt ?? fragment.publishedAt ?? fragment.createdAt
  if (!raw) return 0
  const time = Date.parse(raw)
  return Number.isFinite(time) ? time : 0
}

function shouldPreferConditionFragment(
  candidate: ConditionFragment,
  current: ConditionFragment,
): boolean {
  const candidateVersion = candidate.version ?? 0
  const currentVersion = current.version ?? 0
  if (candidateVersion !== currentVersion) return candidateVersion > currentVersion

  const candidateStatus = fragmentStatusOrder(candidate.status)
  const currentStatus = fragmentStatusOrder(current.status)
  if (candidateStatus !== currentStatus) return candidateStatus > currentStatus

  const candidateTime = fragmentTime(candidate)
  const currentTime = fragmentTime(current)
  if (candidateTime !== currentTime) return candidateTime > currentTime

  return true
}

function latestConditionFragments(fragments: ConditionFragment[]): ConditionFragment[] {
  const byCode = new Map<string, ConditionFragment>()
  fragments.forEach((fragment) => {
    const current = byCode.get(fragment.fragmentCode)
    if (!current || shouldPreferConditionFragment(fragment, current)) {
      byCode.set(fragment.fragmentCode, fragment)
    }
  })
  return Array.from(byCode.values())
}

function fieldFromRef(ref: string): FieldOption | null {
  const parts = ref.split('.')
  const scope = parts[0] as Scope
  if (!FIELD_REF_SCOPES.has(scope) || parts.length < 2) return null
  return {
    scope,
    path: parts.slice(1).join('.'),
    label: ref,
    dataType: 'string',
  }
}

function fieldsFromFragment(fragment?: ConditionFragment): FieldOption[] {
  return (fragment?.fieldRefs ?? [])
    .map(fieldFromRef)
    .filter((field): field is FieldOption => Boolean(field))
}

function conditionOperand(raw: unknown) {
  if (!raw || typeof raw !== 'object') return undefined
  const candidate = raw as Record<string, unknown>
  if (candidate.type === 'path') return candidate
  if (candidate.type === 'field') {
    const scope = String(candidate.scope ?? 'record') as Scope
    const path = String(candidate.path ?? '')
    if (FIELD_REF_SCOPES.has(scope) && path) {
      return { type: 'path', scope, path }
    }
  }
  if (candidate.type === 'literal') {
    return { type: 'literal', value: candidate.value }
  }
  return undefined
}

function conditionRootFromFragment(fragment?: ConditionFragment): GroupNode {
  const spec = fragment?.conditionSpec
  const rawRoot = spec && typeof spec === 'object' ? (spec as Record<string, unknown>).root : null
  if (!rawRoot || typeof rawRoot !== 'object') return group('AND', [])
  const root = rawRoot as Record<string, unknown>
  if (root.type === 'group') return root as unknown as GroupNode
  if (root.type === 'compare') return group('AND', [root as never])
  if (root.type === 'predicate') {
    const left = conditionOperand(root.left)
    const operator = String(root.operator ?? 'EQ') as Operator
    if (!left || !CONDITION_OPERATORS.has(operator)) return group('AND', [])
    const right = conditionOperand(root.right)
    return group('AND', [
      {
        type: 'compare',
        enabled: true,
        left: left as never,
        operator,
        right: right as never,
      },
    ])
  }
  return group('AND', [])
}

function buildFragmentInitialValue(
  scenario: StrategyScenario,
  fragment?: ConditionFragment,
): RuleConsumerBindingDraft {
  return {
    consumerType: scenario.key,
    consumerCode: scenario.ruleCode,
    bindingKind: 'DECISION_REF' as const,
    conditionSpec: {
      root: conditionRootFromFragment(fragment),
      decisionBindings: [],
    },
    decisionBinding: {
      decisionCode: scenario.decisionCode,
      versionPolicy: 'LATEST_PUBLISHED' as const,
      inputMappings: [],
      outputMappings: [],
      fallbackPolicy: { mode: 'FAIL_CLOSED' as const },
      traceMode: 'SAMPLED' as const,
      enabled: true,
    },
    enabled: true,
  }
}

function ruleBindingKey(scenario: StrategyScenario, fragment?: ConditionFragment): string {
  return `${scenario.key}:${fragment?.fragmentCode ?? scenario.ruleCode}`
}

function editableFragmentStatus(status?: string): boolean {
  const normalized = String(status ?? '').toUpperCase()
  return normalized === 'DRAFT' || normalized === 'VALIDATED' || normalized === 'REJECTED'
}

function conditionSpecFromBinding(binding: RuleConsumerBindingDraft | undefined, fallbackRoot: GroupNode) {
  return {
    root: binding?.conditionSpec?.root ?? fallbackRoot,
    decisionBindings: decisionBindingsFromRuleBinding(binding),
  }
}

function decisionBindingsFromRuleBinding(binding: RuleConsumerBindingDraft | undefined): unknown[] {
  const decisionBindings = binding?.conditionSpec?.decisionBindings ?? []
  const decisionBinding = binding?.decisionBinding
  if (!decisionBinding?.decisionCode) return decisionBindings

  const alreadyIncluded = decisionBindings.some((candidate) => {
    if (!candidate || typeof candidate !== 'object') return false
    return (candidate as { decisionCode?: unknown }).decisionCode === decisionBinding.decisionCode
  })
  return alreadyIncluded ? decisionBindings : [...decisionBindings, decisionBinding]
}

function normalizeFragmentCode(value: string): string {
  return value
    .trim()
    .replace(/[^A-Za-z0-9_:-]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 100) || 'strategy_condition'
}

function defaultFragmentCode(scenario: StrategyScenario): string {
  return normalizeFragmentCode(`${scenario.ruleCode}_condition`)
}

function buildConditionFragmentRequest(
  scenario: StrategyScenario,
  fragment: ConditionFragment | undefined,
  binding: RuleConsumerBindingDraft,
): ConditionFragmentUpsertRequest & { fragmentName: string } {
  const fallbackRoot = conditionRootFromFragment(fragment)
  const scenarioDefaultFragment =
    SCENARIOS.find((candidate) => candidate.key === scenario.key)?.fragment
    || strategyStudioText('fragmentNameFallback', 'zh-CN', { title: scenario.title })
  const fragmentName =
    fragment?.fragmentName || (scenario.fragment === NO_FRAGMENT_LABEL ? scenarioDefaultFragment : scenario.fragment)
  return {
    fragmentName,
    description: fragment?.description,
    scopeType: fragment?.scopeType || scenario.key,
    scopeRef: fragment?.scopeRef || scenario.ruleCode,
    ownerModule: fragment?.ownerModule || scenario.key,
    enabled: fragment?.enabled ?? true,
    conditionSpec: conditionSpecFromBinding(binding, fallbackRoot),
  }
}

function upsertConditionFragmentDraft(
  rows: Record<string, ConditionFragment>,
  fragment: ConditionFragment,
): Record<string, ConditionFragment> {
  return {
    ...rows,
    [fragment.fragmentCode]: fragment,
  }
}

function sanitizeTableId(value: string): string {
  return value
    .replace(/[^A-Za-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '') || 'field'
}

function tableInputId(field: FieldOption): string {
  return `${field.scope}_${sanitizeTableId(field.path)}`
}

function scenarioRouteValues(scenario: StrategyScenario): string[] {
  if (scenario.key === 'BPM') return ['director', 'manager', 'fallback']
  if (scenario.key === 'SLA') return ['escalate', 'notify', 'fallback']
  if (scenario.key === 'AUTOMATION') return ['webhook', 'notify', 'fallback']
  return ['allow', 'deny', 'audit']
}

function buildScenarioTable(scenario: StrategyScenario): DecisionTable {
  const routeValues = scenarioRouteValues(scenario)
  return {
    hitPolicy: 'FIRST',
    inputs: scenario.fields.map((field) => ({
      id: tableInputId(field),
      label: field.label,
      scope: field.scope,
      path: field.path,
      dataType: field.dataType,
      allowedValues: field.options,
      valueLabels: field.valueLabels,
    })),
    outputs: [
      { id: 'route', label: 'Route', dataType: 'string', allowedValues: routeValues },
      {
        id: 'actions',
        label: 'Actions',
        dataType: 'collection',
        allowedValues: scenario.actionTypes,
      },
    ],
    rules: [],
    defaultOutput: {
      route: routeValues[routeValues.length - 1] ?? 'fallback',
      actions: ['WRITE_AUDIT'],
    },
  }
}

function initialScenarioTables(): Record<StrategyScenarioKey, DecisionTable> {
  return Object.fromEntries(
    SCENARIOS.map((scenario) => [scenario.key, buildScenarioTable(scenario)]),
  ) as Record<StrategyScenarioKey, DecisionTable>
}

function isDecisionTable(value: unknown): value is DecisionTable {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<DecisionTable>
  return Array.isArray(candidate.inputs)
    && Array.isArray(candidate.outputs)
    && Array.isArray(candidate.rules)
}

function latestRestorableTableVersion(
  versions: Awaited<ReturnType<DecisionApi['listVersions']>>,
) {
  return versions
    .filter((version) => {
      const status = String(version.status ?? '').toUpperCase()
      return version.kind === 'DECISION_TABLE'
        && (!version.runtimeAdapter || version.runtimeAdapter === 'PLATFORM_DECISION_TABLE')
        && ['DRAFT', 'VALIDATED', 'PENDING_APPROVAL', 'PUBLISHED'].includes(status)
        && isDecisionTable(version.contentJson)
    })
    .sort((a, b) => (b.version ?? 0) - (a.version ?? 0))[0]
}

function restoredOutputsMatchScenario(outputs: DecisionTable['outputs'], fallback: DecisionTable): boolean {
  const restoredIds = new Set(outputs.map((output) => output.id))
  return fallback.outputs.some((output) => restoredIds.has(output.id))
}

function normalizeRestoredTable(table: DecisionTable, fallback: DecisionTable): DecisionTable {
  const inputs = table.inputs
    .map((input) => {
      const restored = input as typeof input & {
        expr?: { scope?: unknown; path?: unknown; dataType?: unknown }
      }
      const scope = restored.scope ?? restored.expr?.scope
      const path = restored.path ?? restored.expr?.path
      if (!scope || !path) return null
      return {
        ...restored,
        scope: scope as typeof input.scope,
        path: String(path),
        dataType: (restored.dataType ?? restored.expr?.dataType ?? 'string') as typeof input.dataType,
      }
    })
    .filter((input): input is DecisionTable['inputs'][number] => Boolean(input))
  const outputs = table.outputs.filter((output) => output.id && output.label && output.dataType)

  if (inputs.length === 0 || outputs.length === 0) return fallback
  const outputsMatchScenario = restoredOutputsMatchScenario(outputs, fallback)
  return {
    ...fallback,
    ...table,
    inputs,
    outputs: outputsMatchScenario ? outputs : fallback.outputs,
    rules: outputsMatchScenario && Array.isArray(table.rules) ? table.rules : [],
    defaultOutput: outputsMatchScenario ? table.defaultOutput ?? fallback.defaultOutput : fallback.defaultOutput,
  }
}

function tableInputRefs(table: DecisionTable): string[] {
  return table.inputs.map((input) => `${input.scope}.${input.path}`)
}

function toRuntimeDecisionTable(table: DecisionTable): RuntimeDecisionTable {
  return {
    ...table,
    inputs: table.inputs.map((input) => {
      const { scope, path, dataType, ...rest } = input
      return {
        ...rest,
        expr: {
          type: 'path',
          scope,
          path,
          dataType,
        },
      }
    }),
  }
}

function errorMessage(error: unknown, text: StrategyStudioTextFn): string {
  return error instanceof Error ? error.message : text('operationFailed')
}

function validationMessage(
  result: Awaited<ReturnType<DecisionApi['validateVersion']>>,
  text: StrategyStudioTextFn,
): string {
  return result?.errors?.[0]?.message ?? text('validationFailedDefault')
}

function formatDmnError(result: DecisionTableDmnXmlResult, text: StrategyStudioTextFn): string {
  const first = result.errors?.[0]
  return first
    ? `${first.code}: ${first.message ?? text('dmnProcessingFailed')}`
    : text('dmnProcessingFailed')
}

function sampleContext() {
  return {
    record: {
      modelCode: 'wd_leave_request',
      pid: 'wd-leave-demo-001',
      data: {
        amount: 120000,
        customerTier: 'VIP',
        departmentId: 'dept-sales',
        ownerUserId: 'user-owner',
        priority: 'HIGH',
        status: 'OPEN',
        wd_req_applicant: 'user-owner',
        wd_req_days: 3,
        wd_req_type: 'annual',
      },
    },
    actor: {
      orgPath: '/hq/sales',
      roles: ['department_manager'],
    },
    event: { changedFields: ['status', 'ownerUserId'], type: 'LEAVE_REQUEST_CREATED' },
    process: { taskKey: 'approval', nodeId: 'task_manager_approve' },
    sla: { deadlineMinutes: 30, warningBeforeMinutes: 10, overdueMinutes: 45 },
    tenant: { id: 'tenant-demo' },
    time: { now: '2026-07-03T12:00:00Z' },
  }
}

export function StrategyStudioWorkbench({
  fields,
  decisions = [],
  api,
  conditionFragments = [],
  conditionFragmentsLoading = false,
  conditionFragmentsError = false,
}: StrategyStudioWorkbenchProps) {
  const st = useSmartText()
  const { text } = useStrategyStudioText()
  const [scenarioKey, setScenarioKey] = useState<StrategyScenarioKey>('SLA')
  const [selectedFragmentCode, setSelectedFragmentCode] = useState<string | null>(null)
  const [operationStatus, setOperationStatus] = useState<string | null>(null)
  const [draftVersionPids, setDraftVersionPids] = useState<Record<string, string>>({})
  const [conditionFragmentDrafts, setConditionFragmentDrafts] =
    useState<Record<string, ConditionFragment>>({})
  const [ruleBindingDrafts, setRuleBindingDrafts] =
    useState<Record<string, RuleConsumerBindingDraft>>({})
  const [catalogActions, setCatalogActions] = useState<DecisionAction[]>([])
  const [tableDrafts, setTableDrafts] = useState<Record<StrategyScenarioKey, DecisionTable>>(
    initialScenarioTables,
  )
  const tableDraftsRef = useRef(tableDrafts)
  const dirtyScenarioTablesRef = useRef<Set<StrategyScenarioKey>>(new Set())
  const restoredScenarioTablesRef = useRef<Set<string>>(new Set())
  const [tableAnalyses, setTableAnalyses] = useState<Partial<Record<StrategyScenarioKey, DecisionTableAnalysis | null>>>({})
  const [tableAnalysisErrors, setTableAnalysisErrors] = useState<Partial<Record<StrategyScenarioKey, string | null>>>({})
  const [tableAnalyzing, setTableAnalyzing] = useState(false)
  const [tableDmnXmls, setTableDmnXmls] = useState<Partial<Record<StrategyScenarioKey, string>>>({})
  const [tableDmnErrors, setTableDmnErrors] = useState<Partial<Record<StrategyScenarioKey, string | null>>>({})
  const [tableDmnStatuses, setTableDmnStatuses] = useState<Partial<Record<StrategyScenarioKey, string | null>>>({})
  const [tableDmnBusy, setTableDmnBusy] = useState(false)
  const [activeWorkspacePanel, setActiveWorkspacePanel] =
    useState<StrategyWorkspacePanelKey>('rule')
  const scenario = SCENARIOS.find((candidate) => candidate.key === scenarioKey) ?? SCENARIOS[0]
  const conditionFragmentRows = useMemo(
    () => [...conditionFragments, ...Object.values(conditionFragmentDrafts)],
    [conditionFragments, conditionFragmentDrafts],
  )
  const compatibleFragments = useMemo(
    () => latestConditionFragments(conditionFragmentRows),
    [conditionFragmentRows],
  )
  const explicitSelectedFragment = useMemo(
    () =>
      selectedFragmentCode
        ? compatibleFragments.find((fragment) =>
          fragment.fragmentCode === selectedFragmentCode && scenarioForFragment(fragment)?.key === scenario.key,
        )
        : undefined,
    [compatibleFragments, scenario.key, selectedFragmentCode],
  )
  const selectedFragment = useMemo(
    () =>
      explicitSelectedFragment ??
      compatibleFragments.find((fragment) => scenarioForFragment(fragment)?.key === scenario.key),
    [compatibleFragments, explicitSelectedFragment, scenario.key],
  )
  const activeScenario = useMemo<StrategyScenario>(
    () => ({
      ...scenario,
      decisionCode: explicitSelectedFragment?.decisionRefs?.[0] || scenario.decisionCode,
      fragment: selectedFragment ? fragmentLabel(selectedFragment) : NO_FRAGMENT_LABEL,
    }),
    [scenario, explicitSelectedFragment, selectedFragment],
  )
  const studioEyebrow = st('$i18n:decisionops.header.eyebrow', 'Strategy Studio')
  const decisionOptions = useMemo(() => mergeDecisions(decisions, DECISIONS), [decisions])
  const actionsByType = useMemo(
    () => actionMap(catalogActions.length > 0 ? catalogActions : SAFE_ACTIONS),
    [catalogActions],
  )
  const scenarioActions = useMemo(
    () => resolveScenarioActions(activeScenario, actionsByType),
    [actionsByType, activeScenario],
  )
  const scenarioFields = useMemo(() => {
    const fragmentFields = fieldsFromFragment(selectedFragment)
    const catalogBackedFields = mergeFields(
      activeScenario.fields,
      filterScenarioFields(activeScenario, fragmentFields, fields),
    )
    return mergeFields(catalogBackedFields, fragmentFields)
  }, [activeScenario, fields, selectedFragment])
  const scenarioTable = tableDrafts[activeScenario.key] ?? buildScenarioTable(activeScenario)
  const activeDecisionOptions = useMemo(
    () => scenarioDecisionOptions(decisionOptions, activeScenario, scenarioTable),
    [activeScenario, decisionOptions, scenarioTable],
  )

  useEffect(() => {
    let cancelled = false
    api.getActionCatalog()
      .then((catalog) => {
        if (!cancelled) setCatalogActions(catalog.actions ?? [])
      })
      .catch(() => {
        if (!cancelled) setCatalogActions([])
      })
    return () => { cancelled = true }
  }, [api])

  useEffect(() => {
    if (typeof api.listVersions !== 'function') return
    const restoreKey = `${activeScenario.key}:${activeScenario.decisionCode}`
    if (restoredScenarioTablesRef.current.has(restoreKey)) return
    restoredScenarioTablesRef.current.add(restoreKey)

    let cancelled = false
    api.listVersions(activeScenario.decisionCode)
      .then((versions) => {
        if (cancelled) return
        if (dirtyScenarioTablesRef.current.has(activeScenario.key)) return
        const latest = latestRestorableTableVersion(versions)
        if (!latest || !isDecisionTable(latest.contentJson)) return
        const restoredTable = normalizeRestoredTable(latest.contentJson, buildScenarioTable(activeScenario))
        const drafts = {
          ...tableDraftsRef.current,
          [activeScenario.key]: restoredTable,
        }
        tableDraftsRef.current = drafts
        setTableDrafts(drafts)
        if (String(latest.status ?? '').toUpperCase() === 'DRAFT') {
          setDraftVersionPids((current) => ({ ...current, [activeScenario.key]: latest.pid }))
        }
      })
      .catch(() => {
        // Strategy Studio can still work from the scenario template when no saved table exists.
      })
    return () => { cancelled = true }
  }, [api, activeScenario.key, activeScenario.decisionCode])

  const selectScenario = (next: StrategyScenario, fragment?: ConditionFragment) => {
    setScenarioKey(next.key)
    setSelectedFragmentCode(fragment?.fragmentCode ?? null)
    setActiveWorkspacePanel('rule')
    setOperationStatus(
      fragment
        ? text('fragmentLoaded', { name: fragmentLabel(fragment) })
        : text('selectFragmentHint'),
    )
  }

  const selectFragment = (fragment: ConditionFragment) => {
    const nextScenario = scenarioForFragment(fragment)
    if (!nextScenario) {
      setOperationStatus(text('fragmentScenarioMismatch', { name: fragmentLabel(fragment) }))
      return
    }
    selectScenario(nextScenario, fragment)
  }

  const clearTableFeedback = (key: StrategyScenarioKey) => {
    setTableAnalyses((current) => ({ ...current, [key]: null }))
    setTableAnalysisErrors((current) => ({ ...current, [key]: null }))
    setTableDmnErrors((current) => ({ ...current, [key]: null }))
    setTableDmnStatuses((current) => ({ ...current, [key]: null }))
  }

  const updateScenarioTable = (key: StrategyScenarioKey, next: DecisionTable) => {
    dirtyScenarioTablesRef.current.add(key)
    const drafts = { ...tableDraftsRef.current, [key]: next }
    tableDraftsRef.current = drafts
    setTableDrafts(drafts)
    clearTableFeedback(key)
  }

  const getScenarioTable = (target: StrategyScenario): DecisionTable =>
    tableDraftsRef.current[target.key] ?? buildScenarioTable(target)

  const publishStatus =
    activeScenario.blockers > 0
      ? text('publishBlockedCount', { count: activeScenario.blockers })
      : text('publishCheckPassed', { consumer: text(SCENARIO_TEXT[activeScenario.key].consumer) })
  const activeDecisionName = DECISION_NAME_KEYS[activeScenario.decisionCode]
    ? text(DECISION_NAME_KEYS[activeScenario.decisionCode])
    : decisionDisplayName(
      activeDecisionOptions,
      activeScenario.decisionCode,
      text(SCENARIO_TEXT[activeScenario.key].title),
    )
  const activeRuleBindingKey = ruleBindingKey(activeScenario, selectedFragment)
  const activeRuleBinding = useMemo(
    () =>
      ruleBindingDrafts[activeRuleBindingKey] ??
      buildFragmentInitialValue(activeScenario, selectedFragment),
    [activeRuleBindingKey, activeScenario, ruleBindingDrafts, selectedFragment],
  )

  const updateRuleBindingDraft = (next: RuleConsumerBindingDraft) => {
    setRuleBindingDrafts((current) => ({
      ...current,
      [activeRuleBindingKey]: next,
    }))
  }

  const refreshImpact = async () => {
    setOperationStatus(text('impactQuerying'))
    try {
      const impact = await api.getDecisionImpact(activeScenario.decisionCode)
      const refCount = (impact.incoming?.length ?? 0) + (impact.outgoing?.length ?? 0)
      setOperationStatus(
        text('impactUpdated', {
          summary: impact.risk?.summary ?? text('impactDefaultSummary'),
          count: refCount,
        }),
      )
    } catch (error) {
      setOperationStatus(text('impactFailed', { message: errorMessage(error, text) }))
    }
  }

  const runTest = async () => {
    setOperationStatus(text('testRunning'))
    try {
      const result = await api.evaluate({
        decisionCode: activeScenario.decisionCode,
        binding: 'LATEST',
        callerType: activeScenario.key,
        callerRef: activeScenario.ruleCode,
        context: sampleContext(),
      })
      if (!result) {
        throw new Error(text('evaluateNoResult'))
      }
      setOperationStatus(
        text('testResult', {
          result: result.matched ? text('testMatched') : text('testNotMatched'),
          id: result.traceId ?? result.status,
        }),
      )
    } catch (error) {
      setOperationStatus(text('testFailed', { message: errorMessage(error, text) }))
    }
  }

  const analyzeScenarioTable = async () => {
    const target = activeScenario
    setTableAnalyzing(true)
    setTableAnalysisErrors((current) => ({ ...current, [target.key]: null }))
    try {
      const result = await api.analyzeTable(
        getScenarioTable(target),
        target.decisionCode,
        draftVersionPids[target.key],
      )
      setTableAnalyses((current) => ({ ...current, [target.key]: result }))
    } catch (error) {
      setTableAnalyses((current) => ({ ...current, [target.key]: null }))
      setTableAnalysisErrors((current) => ({ ...current, [target.key]: errorMessage(error, text) }))
    } finally {
      setTableAnalyzing(false)
    }
  }

  const setScenarioDmnXml = (key: StrategyScenarioKey, xml: string) => {
    setTableDmnXmls((current) => ({ ...current, [key]: xml }))
    setTableDmnErrors((current) => ({ ...current, [key]: null }))
    setTableDmnStatuses((current) => ({ ...current, [key]: null }))
  }

  const applyDmnResult = (
    key: StrategyScenarioKey,
    result: DecisionTableDmnXmlResult,
    status: string,
    updateModel: boolean,
  ) => {
    if (result.dmnXml !== undefined) {
      setTableDmnXmls((current) => ({ ...current, [key]: result.dmnXml ?? '' }))
    }
    if (updateModel && result.model) {
      updateScenarioTable(key, result.model)
    }
    if (!result.valid) {
      throw new Error(formatDmnError(result, text))
    }
    const warningCount = result.warnings?.length ?? 0
    setTableDmnStatuses((current) => ({
      ...current,
      [key]: warningCount > 0
        ? text('dmnStatusWithWarnings', { status, count: warningCount })
        : status,
    }))
  }

  const exportScenarioDmn = async () => {
    const target = activeScenario
    setTableDmnBusy(true)
    setTableDmnErrors((current) => ({ ...current, [target.key]: null }))
    try {
      const result = await api.exportTableDmn(
        getScenarioTable(target),
        target.ruleCode,
        target.decisionCode,
      )
      applyDmnResult(target.key, result, text('dmnExported'), false)
      if (result.valid && result.dmnXml) {
        downloadDmnXml(target.ruleCode, result.dmnXml)
      }
    } catch (error) {
      setTableDmnErrors((current) => ({ ...current, [target.key]: errorMessage(error, text) }))
      setTableDmnStatuses((current) => ({ ...current, [target.key]: null }))
    } finally {
      setTableDmnBusy(false)
    }
  }

  const importScenarioDmn = async () => {
    const target = activeScenario
    setTableDmnBusy(true)
    setTableDmnErrors((current) => ({ ...current, [target.key]: null }))
    try {
      const result = await api.importTableDmn(tableDmnXmls[target.key] ?? '')
      applyDmnResult(target.key, result, text('dmnImported'), true)
    } catch (error) {
      setTableDmnErrors((current) => ({ ...current, [target.key]: errorMessage(error, text) }))
      setTableDmnStatuses((current) => ({ ...current, [target.key]: null }))
    } finally {
      setTableDmnBusy(false)
    }
  }

  const roundTripScenarioDmn = async () => {
    const target = activeScenario
    setTableDmnBusy(true)
    setTableDmnErrors((current) => ({ ...current, [target.key]: null }))
    try {
      const result = await api.roundTripTableDmn(
        getScenarioTable(target),
        target.ruleCode,
        target.decisionCode,
      )
      applyDmnResult(target.key, result, text('roundTripPassed'), true)
    } catch (error) {
      setTableDmnErrors((current) => ({ ...current, [target.key]: errorMessage(error, text) }))
      setTableDmnStatuses((current) => ({ ...current, [target.key]: null }))
    } finally {
      setTableDmnBusy(false)
    }
  }

  const ensureDefinition = async (target: StrategyScenario) => {
    const decisionName =
      decisionOptions.find((decision) => decision.code === target.decisionCode)?.name ??
      target.title
    try {
      const existing = await api.getDefinition(target.decisionCode)
      if (existing) return
    } catch {
      // Missing definitions are created below; other API failures still surface through create.
    }
    await api.createDefinition({
      decisionCode: target.decisionCode,
      decisionName,
      scopeType: target.consumer,
      ownerModule: target.key,
    })
  }

  const saveDecisionTableDraft = async (target: StrategyScenario): Promise<string | null> => {
    await ensureDefinition(target)
    const table = getScenarioTable(target)
    const runtimeTable = toRuntimeDecisionTable(table)
    const draft = await api.createDraftVersion(target.decisionCode, {
      kind: 'DECISION_TABLE',
      runtimeAdapter: 'PLATFORM_DECISION_TABLE',
      versionTag: `studio-${target.key.toLowerCase()}`,
      contentJson: runtimeTable,
      inputSchemaJson: { fields: tableInputRefs(table) },
      outputSchemaJson: {
        outputs: tableOutputSchema(table),
        actions: actionOutputSchema(resolveScenarioActions(target, actionsByType)),
      },
      contextSchemaJson: { sample: sampleContext() },
    })
    if (draft.pid) {
      setDraftVersionPids((current) => ({ ...current, [target.key]: draft.pid }))
    }
    return draft.pid ?? null
  }

  const saveConditionFragmentDraft = async (
    target: StrategyScenario,
  ): Promise<string | null> => {
    const fragment = target.key === activeScenario.key ? selectedFragment : undefined
    const defaultCode = defaultFragmentCode(target)
    let existingDefaultFragment =
      conditionFragmentRows.find((candidate) => candidate.fragmentCode === defaultCode) ??
      (fragment?.fragmentCode === defaultCode ? fragment : undefined)
    if (!existingDefaultFragment) {
      const remoteFragments = await api.listConditionFragments({
        keyword: defaultCode,
        scopeType: target.key,
        page: 1,
        size: 20,
      })
      existingDefaultFragment = remoteFragments.records.find(
        (candidate) => candidate.fragmentCode === defaultCode,
      )
    }
    const bindingKey = ruleBindingKey(target, fragment)
    const binding =
      ruleBindingDrafts[bindingKey] ?? buildFragmentInitialValue(target, fragment)
    const request = buildConditionFragmentRequest(target, existingDefaultFragment ?? fragment, binding)
    const updateExistingDraftVersion = async (
      code: string,
      originalError: unknown,
    ): Promise<ConditionFragment> => {
      const versions = await api.listConditionFragmentVersions(code).catch(() => [])
      const draft = versions.find((candidate) => candidate.pid && editableFragmentStatus(candidate.status))
      if (draft?.pid) {
        return api.updateConditionFragmentDraft(draft.pid, request)
      }
      throw originalError instanceof Error ? originalError : new Error(text('fragmentVersionCreateFailed'))
    }
    const saveExistingFragmentDraft = async (candidate: ConditionFragment): Promise<ConditionFragment> => {
      if (candidate.pid && editableFragmentStatus(candidate.status)) {
        try {
          return await api.updateConditionFragmentDraft(candidate.pid, request)
        } catch {
          if (!candidate.fragmentCode) throw new Error(text('fragmentDraftUpdateFailed'))
        }
      }
      if (candidate.fragmentCode) {
        try {
          return await api.createConditionFragmentVersion(candidate.fragmentCode, request)
        } catch (error) {
          return updateExistingDraftVersion(candidate.fragmentCode, error)
        }
      }
      throw new Error(text('fragmentMissingCode'))
    }
    let saved: ConditionFragment
    if (existingDefaultFragment) {
      saved = await saveExistingFragmentDraft(existingDefaultFragment)
    } else if (fragment) {
      saved = await saveExistingFragmentDraft(fragment)
    } else {
      try {
        saved = await api.createConditionFragment({
          ...request,
          fragmentCode: defaultCode,
          fragmentName: request.fragmentName,
        })
      } catch {
        try {
          saved = await api.createConditionFragmentVersion(defaultCode, request)
        } catch (error) {
          saved = await updateExistingDraftVersion(defaultCode, error)
        }
      }
    }
    setConditionFragmentDrafts((current) => upsertConditionFragmentDraft(current, saved))
    setSelectedFragmentCode(saved.fragmentCode)
    setRuleBindingDrafts((current) => ({
      ...current,
      [ruleBindingKey(target, saved)]: binding,
    }))
    return saved.pid ?? null
  }

  const saveDraft = async (
    target: StrategyScenario = activeScenario,
  ): Promise<{ decisionPid: string | null; conditionFragmentPid: string | null } | null> => {
    setOperationStatus(text('draftSaving'))
    try {
      const conditionFragmentPid = await saveConditionFragmentDraft(target)
      const decisionPid = await saveDecisionTableDraft(target)
      setOperationStatus(
        text('draftSaved', { title: text(SCENARIO_TEXT[target.key].title) }),
      )
      return { decisionPid, conditionFragmentPid }
    } catch (error) {
      setOperationStatus(text('saveFailed', { message: errorMessage(error, text) }))
      return null
    }
  }

  const publish = async () => {
    if (activeScenario.blockers > 0) {
      setOperationStatus(publishStatus)
      return
    }
    setOperationStatus(text('publishing'))
    const saved = await saveDraft()
    const pid = saved?.decisionPid ?? draftVersionPids[activeScenario.key]
    if (!pid) return
    try {
      if (saved?.conditionFragmentPid) {
        const validatedFragment = await api.validateConditionFragmentVersion(saved.conditionFragmentPid)
        setConditionFragmentDrafts((current) =>
          upsertConditionFragmentDraft(current, validatedFragment),
        )
        const publishedFragment = await api.publishConditionFragmentVersion(saved.conditionFragmentPid, {
          impactAcknowledged: true,
          note: `Published from Strategy Studio for ${activeScenario.consumer}`,
        })
        setConditionFragmentDrafts((current) =>
          upsertConditionFragmentDraft(current, publishedFragment),
        )
        setSelectedFragmentCode(publishedFragment.fragmentCode)
      }
      const validation = await api.validateVersion(pid)
      if (!validation) {
        throw new Error(text('versionValidationNoResult'))
      }
      if (!validation.valid) {
        setOperationStatus(text('publishFailed', { message: validationMessage(validation, text) }))
        return
      }
      const published = await api.publishVersion(pid, {
        impactAcknowledged: true,
        note: `Published from Strategy Studio for ${activeScenario.consumer}`,
      })
      if (!published) {
        throw new Error(text('publishNoResult'))
      }
      setOperationStatus(
        text('publishSucceeded', { consumer: text(SCENARIO_TEXT[activeScenario.key].consumer) }),
      )
    } catch (error) {
      setOperationStatus(text('publishFailed', { message: errorMessage(error, text) }))
    }
  }

  return (
    <section id="strategy-workbench" className="strategy-studio" data-testid="strategy-studio">
      <header className="strategy-studio-header">
        <div>
          <p>{studioEyebrow}</p>
          <h3>{text(SCENARIO_TEXT[activeScenario.key].title)}</h3>
        </div>
        <div className="strategy-studio-actions">
          <button
            type="button"
            data-testid="strategy-impact-preview"
            onClick={() => void refreshImpact()}
          >
            {text('impactAction')}
          </button>
          <button
            type="button"
            data-testid="strategy-run-test"
            onClick={() => void runTest()}
          >
            {text('runTestAction')}
          </button>
          <button
            type="button"
            data-testid="strategy-save-draft"
            onClick={() => void saveDraft()}
          >
            {text('saveDraftAction')}
          </button>
          <button
            type="button"
            data-testid="strategy-publish"
            className="strategy-studio-primary"
            onClick={() => void publish()}
          >
            {text('publishAction')}
          </button>
        </div>
      </header>
      {operationStatus && (
        <div className="strategy-operation-status" data-testid="strategy-operation-status">
          {operationStatus}
        </div>
      )}

      <div className="strategy-scenarios" aria-label={text('scenarioGroupLabel')}>
        {SCENARIOS.map((candidate) => (
          <button
            key={candidate.key}
            type="button"
            data-testid={`strategy-scenario-${candidate.key}`}
            aria-pressed={candidate.key === activeScenario.key}
            onClick={() => selectScenario(candidate)}
          >
            <span>{text(SCENARIO_TEXT[candidate.key].label)}</span>
            <strong>{text(SCENARIO_TEXT[candidate.key].trigger)}</strong>
          </button>
        ))}
      </div>

      <div className="strategy-studio-metrics">
        <div data-testid="strategy-consumer-summary">
          <span>{text('metricConsumer')}</span>
          <strong>{text(SCENARIO_TEXT[activeScenario.key].consumer)}</strong>
          <small>{text(SCENARIO_TEXT[activeScenario.key].trigger)}</small>
        </div>
        <div>
          <span>{text('metricFields')}</span>
          <strong>{scenarioFields.length}</strong>
          <small>{text('metricFieldsHint')}</small>
        </div>
        <div>
          <span>{text('metricActions')}</span>
          <strong>{scenarioActions.length}</strong>
          <small>{text('metricActionsHint')}</small>
        </div>
        <div>
          <span>{text('metricBlockers')}</span>
          <strong>{activeScenario.blockers}</strong>
          <small>{activeScenario.blockers > 0 ? text('blockersPending') : text('blockersReady')}</small>
        </div>
      </div>

      <div className="strategy-workspace-tabs" aria-label={text('workspaceGroupLabel')}>
        {WORKSPACE_PANELS.map((panel) => (
          <button
            key={panel.key}
            type="button"
            data-testid={`strategy-workspace-tab-${panel.key}`}
            aria-pressed={activeWorkspacePanel === panel.key}
            onClick={() => setActiveWorkspacePanel(panel.key)}
          >
            <span>{text(WORKSPACE_PANEL_TEXT[panel.key].label)}</span>
            <strong>{text(WORKSPACE_PANEL_TEXT[panel.key].summary)}</strong>
          </button>
        ))}
      </div>

      <div className="strategy-studio-grid">
        <aside
          className="strategy-studio-panel strategy-workspace-panel"
          data-testid="strategy-fact-catalog"
          data-workspace-panel="facts"
          data-active={activeWorkspacePanel === 'facts' ? 'true' : 'false'}
        >
          <div className="strategy-studio-panel-head">
            <strong>{text('panelFactsLabel')}</strong>
            <span>{scenarioFields.length}</span>
          </div>
          <ul className="strategy-fact-list">
            {scenarioFields.slice(0, 8).map((field) => (
              <li
                key={fieldKey(field)}
                title={formatFieldPath(field)}
                data-testid={`strategy-fact-${field.scope}-${sanitizeTableId(field.path)}`}
                data-scope={field.scope}
                data-path={field.path}
                data-model-code={field.modelCode ?? ''}
                data-data-type={field.dataType}
              >
                <span>{fieldDisplayLabel(field, text)}</span>
                <small>{fieldContextLabel(field)} · {dataTypeLabel(field.dataType)}</small>
              </li>
            ))}
          </ul>
        </aside>

        <main className="strategy-studio-center">
          <div
            className="strategy-studio-panel strategy-workspace-panel"
            data-testid="strategy-workspace-panel-rule"
            data-workspace-panel="rule"
            data-active={activeWorkspacePanel === 'rule' ? 'true' : 'false'}
          >
            <div className="strategy-studio-panel-head">
              <strong>{text('panelRuleLabel')}</strong>
              <span>
                {selectedFragment ? fragmentLabel(selectedFragment) : text('noFragmentSelected')}
              </span>
            </div>
            <DecisionRuleBindingBlock
              key={activeRuleBindingKey}
              block={{
                props: {
                  mode: 'combined',
                  consumerType: activeScenario.key,
                  consumerCode: activeScenario.ruleCode,
                  fieldCatalogMode: 'disabled',
                  showImpactPreview: true,
                  showTestRunner: true,
                  initialDecisionCode: activeScenario.decisionCode,
                  initialValue: activeRuleBinding,
                  initialContextJson: JSON.stringify(
                    sampleContext(),
                    null,
                    2,
                  ),
                  fields: scenarioFields,
                  decisions: activeDecisionOptions,
                },
              }}
              api={api}
              onChange={updateRuleBindingDraft}
            />
          </div>

          <div
            className="strategy-studio-panel strategy-workspace-panel"
            data-testid="strategy-dmn-panel"
            data-workspace-panel="dmn"
            data-active={activeWorkspacePanel === 'dmn' ? 'true' : 'false'}
          >
            <div className="strategy-studio-panel-head">
              <strong>{text('dmnOutputTitle')}</strong>
              <span title={activeScenario.decisionCode}>{activeDecisionName}</span>
            </div>
            <div className="strategy-table-panel">
              <DecisionTableEditor
                value={scenarioTable}
                onChange={(next) => updateScenarioTable(activeScenario.key, next)}
                analysis={tableAnalyses[activeScenario.key] ?? null}
                analyzing={tableAnalyzing}
                analysisError={tableAnalysisErrors[activeScenario.key] ?? null}
                onAnalyze={analyzeScenarioTable}
                dmnXml={tableDmnXmls[activeScenario.key] ?? ''}
                dmnBusy={tableDmnBusy}
                dmnError={tableDmnErrors[activeScenario.key] ?? null}
                dmnStatus={tableDmnStatuses[activeScenario.key] ?? null}
                onDmnXmlChange={(xml) => setScenarioDmnXml(activeScenario.key, xml)}
                onExportDmnXml={exportScenarioDmn}
                onImportDmnXml={importScenarioDmn}
                onRoundTripDmnXml={roundTripScenarioDmn}
                fieldOptions={scenarioFields}
              />
            </div>
          </div>
        </main>

        <aside
          className="strategy-studio-side strategy-workspace-panel"
          data-testid="strategy-workspace-panel-review"
          data-workspace-panel="review"
          data-active={activeWorkspacePanel === 'review' ? 'true' : 'false'}
        >
          <section className="strategy-studio-panel" data-testid="strategy-fragment-library">
            <div className="strategy-studio-panel-head">
              <strong>{text('fragmentLibraryTitle')}</strong>
              <span>{text('fragmentLibraryHint')}</span>
            </div>
            <ul className="strategy-fragment-list">
              {conditionFragmentsLoading && (
                <li>
                  <span>{text('loading')}</span>
                </li>
              )}
              {conditionFragmentsError && !conditionFragmentsLoading && (
                <li>
                  <span>{text('fragmentLoadFailed')}</span>
                </li>
              )}
              {!conditionFragmentsLoading && !conditionFragmentsError && compatibleFragments.length === 0 && (
                <li>
                  <span>{text('noFragments')}</span>
                </li>
              )}
              {!conditionFragmentsLoading && !conditionFragmentsError && compatibleFragments.map((fragment) => {
                const fragmentScenario = scenarioForFragment(fragment)
                const active = fragment.fragmentCode === selectedFragment?.fragmentCode
                return (
                  <li key={fragmentListKey(fragment)} data-active={active}>
                    <button
                      type="button"
                      data-testid={`strategy-fragment-${fragment.fragmentCode}`}
                      aria-pressed={active}
                      onClick={() => selectFragment(fragment)}
                    >
                      <span>{fragmentLabel(fragment)}</span>
                      <small>
                        {fragmentScenario
                          ? text(SCENARIO_TEXT[fragmentScenario.key].label)
                          : scenarioScopeLabel(fragment.scopeType)}
                        {fragment.version ? ` · v${fragment.version}` : ''}
                      </small>
                    </button>
                  </li>
                )
              })}
            </ul>
          </section>

          <section className="strategy-studio-panel" data-testid="strategy-action-plan">
            <div className="strategy-studio-panel-head">
              <strong>{text('actionOutputTitle')}</strong>
              <span>{scenarioActions.length}</span>
            </div>
            <ol className="strategy-action-list">
              {scenarioActions.map((action) => (
                <li key={action.actionType} data-testid={`strategy-action-${action.actionType}`}>
                  <div className="strategy-action-copy">
                    <strong>{actionDisplayLabel(action, text)}</strong>
                    <span>{actionCategoryLabel(action, text)}</span>
                  </div>
                </li>
              ))}
            </ol>
          </section>

          <section className="strategy-studio-panel">
            <div className="strategy-studio-panel-head">
              <strong>{text('publishCheckTitle')}</strong>
              <span>
                {activeScenario.blockers > 0 ? text('publishBlocked') : text('publishReady')}
              </span>
            </div>
            <div className="strategy-check-list">
              <span data-state="ok">{text('checkFieldsResolvable')}</span>
              <span data-state="ok">{text('checkFragmentVersionAvailable')}</span>
              <span data-state={activeScenario.blockers > 0 ? 'warn' : 'ok'}>
                {text('checkImpactConfirmed')}
              </span>
            </div>
          </section>
        </aside>
      </div>
    </section>
  )
}

export default StrategyStudioWorkbench
