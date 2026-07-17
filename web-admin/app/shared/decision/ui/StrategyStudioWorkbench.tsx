import { useEffect, useMemo, useState } from 'react'
import {
  DecisionRuleBindingBlock,
  type DecisionOption,
} from '~/ui/smart/decision/DecisionRuleBindingBlock'
import type {
  ConditionFragment,
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

const SLA_NODE_VALUE_LABELS: Record<string, string> = {
  task_manager_approve: '主管审批节点',
  task_hr_approve: 'HR 审批节点',
};

const PROCESS_NODE_VALUE_LABELS: Record<string, string> = {
  task_manager_approve: '主管审批节点',
  task_hr_approve: 'HR 审批节点',
  gw_manager: '主管审批网关',
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
    label: 'SLA',
    title: '主管审批 SLA',
    consumer: 'SLA / 超时通知',
    trigger: '进入审批节点',
    ruleCode: 'wd_manager_approve_sla',
    decisionCode: 'complaint_sla_deadline',
    fragment: '请假 SLA 节点匹配',
    actionTypes: ['NOTIFY', 'WRITE_AUDIT'],
    blockers: 0,
    modelCodes: ['sla_config', 'wd_leave_request'],
    fields: [
      {
        scope: 'record',
        path: 'data.targetKey',
        label: 'SLA 节点',
        dataType: 'string',
        options: Object.keys(SLA_NODE_VALUE_LABELS),
        valueLabels: SLA_NODE_VALUE_LABELS,
      },
      { scope: 'sla', path: 'deadlineMinutes', label: '截止分钟', dataType: 'integer' },
      { scope: 'sla', path: 'warningBeforeMinutes', label: '提前提醒', dataType: 'integer' },
      {
        scope: 'process',
        path: 'nodeId',
        label: '流程节点',
        dataType: 'string',
        options: Object.keys(PROCESS_NODE_VALUE_LABELS),
        valueLabels: PROCESS_NODE_VALUE_LABELS,
      },
    ],
  },
  {
    key: 'BPM',
    label: 'BPM',
    consumer: 'BPM / 审批人分派',
    title: '请假审批流程',
    trigger: '进入审批节点',
    ruleCode: 'wd_leave_approval',
    decisionCode: 'approval_routing',
    fragment: '请假审批路由条件',
    actionTypes: ['ADD_COMMENT', 'WRITE_AUDIT'],
    blockers: 0,
    modelCodes: ['wd_leave_request'],
    fields: [
      {
        scope: 'process',
        path: 'nodeId',
        label: '流程节点',
        dataType: 'string',
        options: Object.keys(PROCESS_NODE_VALUE_LABELS),
        valueLabels: PROCESS_NODE_VALUE_LABELS,
      },
      { scope: 'record', path: 'data.wd_req_days', label: '请假天数', dataType: 'decimal' },
      { scope: 'actor', path: 'roles', label: '审批角色', dataType: 'collection' },
    ],
  },
  {
    key: 'AUTOMATION',
    label: '自动化',
    title: '请假申请自动通知',
    consumer: '自动化 / 条件触发',
    trigger: '创建请假申请',
    ruleCode: 'wd_leave_high_value_notify',
    decisionCode: 'leave_request_automation',
    fragment: '长假自动通知条件',
    actionTypes: ['NOTIFY', 'WRITE_AUDIT'],
    blockers: 0,
    modelCodes: ['wd_leave_request'],
    fields: [
      { scope: 'record', path: 'data.wd_req_days', label: '请假天数', dataType: 'decimal' },
      { scope: 'record', path: 'pid', label: '申请记录', dataType: 'string' },
      { scope: 'time', path: 'now', label: '触发时间', dataType: 'datetime' },
    ],
  },
  {
    key: 'PERMISSION',
    label: '权限',
    title: '请假可见性权限',
    consumer: '权限 / 行级访问',
    trigger: '查询前校验',
    ruleCode: 'ABAC_LEAVE_VISIBILITY',
    decisionCode: 'leave_visibility_policy',
    fragment: '同部门请假可见',
    actionTypes: ['WRITE_AUDIT'],
    blockers: 0,
    modelCodes: ['wd_leave_request', 'tenant_member', 'department'],
    fields: [
      { scope: 'actor', path: 'orgPath', label: '组织路径', dataType: 'department' },
      { scope: 'record', path: 'data.departmentId', label: '记录部门', dataType: 'department' },
      { scope: 'tenant', path: 'id', label: '租户', dataType: 'string' },
    ],
  },
  {
    key: 'EVENT_POLICY',
    label: '事件策略',
    title: '请假申请事件策略',
    consumer: '事件策略 / 条件 + 动作',
    trigger: '请假申请已创建',
    ruleCode: 'leave_request_event_policy',
    decisionCode: 'leave_request_automation',
    fragment: '请假事件动作条件',
    actionTypes: ['NOTIFY', 'WRITE_AUDIT'],
    blockers: 0,
    modelCodes: ['wd_leave_request'],
    fields: [
      { scope: 'event', path: 'type', label: '事件类型', dataType: 'string' },
      { scope: 'record', path: 'data.wd_req_days', label: '请假天数', dataType: 'decimal' },
      { scope: 'actor', path: 'roles', label: '触发人角色', dataType: 'collection' },
    ],
  },
]

const WORKSPACE_PANELS: Array<{ key: StrategyWorkspacePanelKey; label: string; summary: string }> = [
  { key: 'rule', label: '规则配置', summary: '条件 / 映射 / 测试' },
  { key: 'facts', label: '事实目录', summary: '字段 / 上下文' },
  { key: 'dmn', label: '决策表', summary: 'DMN / 输出' },
  { key: 'review', label: '片段与发布', summary: '复用 / 动作 / 检查' },
]

const DECISIONS = [
  {
    code: 'complaint_sla_deadline',
    name: '请假审批 SLA 截止时间',
    outputs: [
      { id: 'deadlineMinutes', label: '截止分钟', dataType: 'integer' },
      { id: 'warningBeforeMinutes', label: '提前提醒分钟', dataType: 'integer' },
      { id: 'escalationLevel', label: '升级等级', dataType: 'string' },
    ],
  },
  {
    code: 'approval_routing',
    name: '请假审批分派',
    outputs: [
      { id: 'candidateGroups', label: '候选组', dataType: 'collection' },
      { id: 'assigneeUserId', label: '审批人', dataType: 'string' },
      { id: 'dueHours', label: '任务时限', dataType: 'integer' },
    ],
  },
  {
    code: 'leave_request_automation',
    name: '请假申请自动化策略',
    outputs: [
      { id: 'route', label: '动作路由', dataType: 'string' },
      { id: 'actions', label: '动作列表', dataType: 'collection' },
    ],
  },
  {
    code: 'leave_visibility_policy',
    name: '请假可见性策略',
    outputs: [
      { id: 'allow', label: '是否允许', dataType: 'boolean' },
      { id: 'reason', label: '拒绝原因', dataType: 'string' },
    ],
  },
]

const SAFE_ACTIONS: DecisionAction[] = [
  { actionType: 'NOTIFY', label: '发送通知', handlerAvailable: true, category: 'messaging' },
  { actionType: 'START_PROCESS', label: '启动流程', handlerAvailable: true, category: 'workflow' },
  { actionType: 'ADD_COMMENT', label: '添加评论', handlerAvailable: true, category: 'collaboration' },
  { actionType: 'UPDATE_RECORD', label: '更新记录', handlerAvailable: true, category: 'data' },
  { actionType: 'PATCH_RECORD', label: '修补记录', handlerAvailable: true, category: 'data' },
  { actionType: 'WEBHOOK', label: '发送 Webhook', handlerAvailable: true, category: 'integration' },
  { actionType: 'WRITE_AUDIT', label: '写入审计', handlerAvailable: true, category: 'governance' },
]

const ACTION_LABELS: Record<string, string> = {
  NOTIFY: '发送通知',
  START_PROCESS: '启动流程',
  ADD_COMMENT: '添加评论',
  UPDATE_RECORD: '更新记录',
  PATCH_RECORD: '修补记录',
  WEBHOOK: '发送 Webhook',
  WRITE_AUDIT: '写入审计',
  SEND_SMS: '发送短信',
  SEND_IM: '发送 IM',
  CREATE_TASK: '创建任务',
  CC_TASK: '抄送任务',
}

const ACTION_CATEGORY_LABELS: Record<string, string> = {
  messaging: '消息',
  workflow: '流程',
  collaboration: '协作',
  data: '数据',
  integration: '集成',
  governance: '治理',
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

function actionCategoryLabel(action: DecisionAction): string {
  const category = action.category?.trim()
  if (!category) return action.handlerAvailable === false ? '未接入处理器' : '平台动作'
  return ACTION_CATEGORY_LABELS[category] ?? category
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

function fieldDisplayLabel(field: FieldOption): string {
  const label = field.label?.trim()
  const path = formatFieldPath(field)
  if (label && label !== path) return label
  return `${fieldContextLabel(field)}字段`
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
  if (scope === 'BPM' || scope === 'BPM_PROCESS' || scope === 'WORKFLOW') return 'BPM'
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

function latestConditionFragments(fragments: ConditionFragment[]): ConditionFragment[] {
  const byCode = new Map<string, ConditionFragment>()
  fragments.forEach((fragment) => {
    const current = byCode.get(fragment.fragmentCode)
    if (!current || (fragment.version ?? 0) > (current.version ?? 0)) {
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
) {
  return {
    consumerType: scenario.key,
    consumerCode: scenario.ruleCode,
    bindingKind: 'DECISION_REF' as const,
    conditionSpec: {
      root: conditionRootFromFragment(fragment),
      decisionBindings: [],
    },
    decisionBinding: {
      decisionCode: fragment?.decisionRefs?.[0] || scenario.decisionCode,
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

function tableInputRefs(table: DecisionTable): string[] {
  return table.inputs.map((input) => `${input.scope}.${input.path}`)
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '操作失败'
}

function validationMessage(result: Awaited<ReturnType<DecisionApi['validateVersion']>>): string {
  return result?.errors?.[0]?.message ?? '版本校验未通过'
}

function formatDmnError(result: DecisionTableDmnXmlResult): string {
  const first = result.errors?.[0]
  return first ? `${first.code}: ${first.message ?? 'DMN XML 处理失败'}` : 'DMN XML 处理失败'
}

function sampleContext() {
  return {
    record: {
      data: {
        amount: 120000,
        customerTier: 'VIP',
        departmentId: 'dept-sales',
        ownerUserId: 'user-owner',
        priority: 'HIGH',
        status: 'OPEN',
        wd_req_days: 3,
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
  const [scenarioKey, setScenarioKey] = useState<StrategyScenarioKey>('SLA')
  const [selectedFragmentCode, setSelectedFragmentCode] = useState<string | null>(null)
  const [operationStatus, setOperationStatus] = useState<string | null>(null)
  const [draftVersionPids, setDraftVersionPids] = useState<Record<string, string>>({})
  const [catalogActions, setCatalogActions] = useState<DecisionAction[]>([])
  const [tableDrafts, setTableDrafts] = useState<Record<StrategyScenarioKey, DecisionTable>>(
    initialScenarioTables,
  )
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
  const compatibleFragments = useMemo(
    () => latestConditionFragments(conditionFragments),
    [conditionFragments],
  )
  const selectedFragment = useMemo(
    () =>
      compatibleFragments.find((fragment) => fragment.fragmentCode === selectedFragmentCode) ??
      compatibleFragments.find((fragment) => scenarioForFragment(fragment)?.key === scenario.key),
    [compatibleFragments, scenario.key, selectedFragmentCode],
  )
  const activeScenario = useMemo<StrategyScenario>(
    () => ({
      ...scenario,
      decisionCode: selectedFragment?.decisionRefs?.[0] || scenario.decisionCode,
      fragment: selectedFragment ? fragmentLabel(selectedFragment) : '未选择条件片段',
    }),
    [scenario, selectedFragment],
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

  const selectScenario = (next: StrategyScenario, fragment?: ConditionFragment) => {
    setScenarioKey(next.key)
    setSelectedFragmentCode(fragment?.fragmentCode ?? null)
    setActiveWorkspacePanel('rule')
    setOperationStatus(
      fragment ? `已加载共享片段 · ${fragmentLabel(fragment)}` : '请选择共享条件片段',
    )
  }

  const selectFragment = (fragment: ConditionFragment) => {
    const nextScenario = scenarioForFragment(fragment)
    if (!nextScenario) {
      setOperationStatus(`片段无法匹配消费场景 · ${fragmentLabel(fragment)}`)
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
    setTableDrafts((current) => ({ ...current, [key]: next }))
    clearTableFeedback(key)
  }

  const getScenarioTable = (target: StrategyScenario): DecisionTable =>
    tableDrafts[target.key] ?? buildScenarioTable(target)

  const publishStatus =
    activeScenario.blockers > 0
      ? `发布被阻断 · ${activeScenario.blockers} 项待处理`
      : `发布检查通过 · ${activeScenario.consumer}`
  const activeDecisionName = decisionDisplayName(
    activeDecisionOptions,
    activeScenario.decisionCode,
    activeScenario.title,
  )

  const refreshImpact = async () => {
    setOperationStatus('影响面查询中...')
    try {
      const impact = await api.getDecisionImpact(activeScenario.decisionCode)
      const refCount = (impact.incoming?.length ?? 0) + (impact.outgoing?.length ?? 0)
      setOperationStatus(`${impact.risk?.summary ?? '影响面已更新'} · ${refCount} 个引用`)
    } catch (error) {
      setOperationStatus(`影响面失败 · ${errorMessage(error)}`)
    }
  }

  const runTest = async () => {
    setOperationStatus('测试运行中...')
    try {
      const result = await api.evaluate({
        decisionCode: activeScenario.decisionCode,
        binding: 'LATEST',
        callerType: activeScenario.key,
        callerRef: activeScenario.ruleCode,
        context: sampleContext(),
      })
      if (!result) {
        throw new Error('决策执行无返回结果')
      }
      setOperationStatus(
        `${result.matched ? '测试通过' : '测试未命中'} · ${result.traceId ?? result.status}`,
      )
    } catch (error) {
      setOperationStatus(`测试失败 · ${errorMessage(error)}`)
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
      setTableAnalysisErrors((current) => ({ ...current, [target.key]: errorMessage(error) }))
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
      throw new Error(formatDmnError(result))
    }
    const warningCount = result.warnings?.length ?? 0
    setTableDmnStatuses((current) => ({
      ...current,
      [key]: warningCount > 0 ? `${status} · 警告 ${warningCount}` : status,
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
      applyDmnResult(target.key, result, 'DMN XML 已导出', false)
    } catch (error) {
      setTableDmnErrors((current) => ({ ...current, [target.key]: errorMessage(error) }))
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
      applyDmnResult(target.key, result, 'DMN XML 已导入', true)
    } catch (error) {
      setTableDmnErrors((current) => ({ ...current, [target.key]: errorMessage(error) }))
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
      applyDmnResult(target.key, result, 'Round-trip 通过', true)
    } catch (error) {
      setTableDmnErrors((current) => ({ ...current, [target.key]: errorMessage(error) }))
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

  const saveDraft = async (target: StrategyScenario = activeScenario): Promise<string | null> => {
    setOperationStatus('草稿保存中...')
    try {
      await ensureDefinition(target)
      const table = getScenarioTable(target)
      const draft = await api.createDraftVersion(target.decisionCode, {
        kind: 'DECISION_TABLE',
        runtimeAdapter: 'PLATFORM_DECISION_TABLE',
        versionTag: `studio-${target.key.toLowerCase()}`,
        contentJson: table,
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
      setOperationStatus(`草稿已保存 · ${target.title}`)
      return draft.pid ?? null
    } catch (error) {
      setOperationStatus(`保存失败 · ${errorMessage(error)}`)
      return null
    }
  }

  const publish = async () => {
    if (activeScenario.blockers > 0) {
      setOperationStatus(publishStatus)
      return
    }
    setOperationStatus('发布中...')
    const pid = draftVersionPids[activeScenario.key] ?? (await saveDraft())
    if (!pid) return
    try {
      const validation = await api.validateVersion(pid)
      if (!validation) {
        throw new Error('版本校验无返回结果')
      }
      if (!validation.valid) {
        setOperationStatus(`发布失败 · ${validationMessage(validation)}`)
        return
      }
      const published = await api.publishVersion(pid, {
        impactAcknowledged: true,
        note: `Published from Strategy Studio for ${activeScenario.consumer}`,
      })
      if (!published) {
        throw new Error('发布接口未返回版本结果')
      }
      setOperationStatus(`发布成功 · ${activeScenario.consumer}`)
    } catch (error) {
      setOperationStatus(`发布失败 · ${errorMessage(error)}`)
    }
  }

  return (
    <section id="strategy-workbench" className="strategy-studio" data-testid="strategy-studio">
      <header className="strategy-studio-header">
        <div>
          <p>{studioEyebrow}</p>
          <h3>{activeScenario.title}</h3>
        </div>
        <div className="strategy-studio-actions">
          <button
            type="button"
            data-testid="strategy-impact-preview"
            onClick={() => void refreshImpact()}
          >
            影响面
          </button>
          <button
            type="button"
            data-testid="strategy-run-test"
            onClick={() => void runTest()}
          >
            测试运行
          </button>
          <button
            type="button"
            data-testid="strategy-save-draft"
            onClick={() => void saveDraft()}
          >
            保存草稿
          </button>
          <button
            type="button"
            data-testid="strategy-publish"
            className="strategy-studio-primary"
            onClick={() => void publish()}
          >
            发布
          </button>
        </div>
      </header>
      {operationStatus && (
        <div className="strategy-operation-status" data-testid="strategy-operation-status">
          {operationStatus}
        </div>
      )}

      <div className="strategy-scenarios" aria-label="规则消费场景">
        {SCENARIOS.map((candidate) => (
          <button
            key={candidate.key}
            type="button"
            data-testid={`strategy-scenario-${candidate.key}`}
            aria-pressed={candidate.key === activeScenario.key}
            onClick={() => selectScenario(candidate)}
          >
            <span>{candidate.label}</span>
            <strong>{candidate.trigger}</strong>
          </button>
        ))}
      </div>

      <div className="strategy-studio-metrics">
        <div data-testid="strategy-consumer-summary">
          <span>消费方</span>
          <strong>{activeScenario.consumer}</strong>
          <small>{activeScenario.trigger}</small>
        </div>
        <div>
          <span>字段事实</span>
          <strong>{scenarioFields.length}</strong>
          <small>来自模型 / 虚拟模型 / 参与人 / 系统</small>
        </div>
        <div>
          <span>动作</span>
          <strong>{scenarioActions.length}</strong>
          <small>命中后统一执行</small>
        </div>
        <div>
          <span>阻断项</span>
          <strong>{activeScenario.blockers}</strong>
          <small>{activeScenario.blockers > 0 ? '发布前需处理' : '可进入发布检查'}</small>
        </div>
      </div>

      <div className="strategy-workspace-tabs" aria-label="策略工作区视图">
        {WORKSPACE_PANELS.map((panel) => (
          <button
            key={panel.key}
            type="button"
            data-testid={`strategy-workspace-tab-${panel.key}`}
            aria-pressed={activeWorkspacePanel === panel.key}
            onClick={() => setActiveWorkspacePanel(panel.key)}
          >
            <span>{panel.label}</span>
            <strong>{panel.summary}</strong>
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
            <strong>事实目录</strong>
            <span>{scenarioFields.length}</span>
          </div>
          <ul className="strategy-fact-list">
            {scenarioFields.slice(0, 8).map((field) => (
              <li key={fieldKey(field)} title={formatFieldPath(field)}>
                <span>{fieldDisplayLabel(field)}</span>
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
              <strong>规则配置</strong>
              <span>{activeScenario.fragment}</span>
            </div>
            <DecisionRuleBindingBlock
              key={`${activeScenario.key}:${selectedFragment?.fragmentCode ?? 'scenario'}`}
              block={{
                props: {
                  mode: 'combined',
                  consumerType: activeScenario.key,
                  consumerCode: activeScenario.ruleCode,
                  fieldCatalogMode: 'disabled',
                  showImpactPreview: true,
                  showTestRunner: true,
                  initialDecisionCode: activeScenario.decisionCode,
                  initialValue: buildFragmentInitialValue(activeScenario, selectedFragment),
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
            />
          </div>

          <div
            className="strategy-studio-panel strategy-workspace-panel"
            data-testid="strategy-dmn-panel"
            data-workspace-panel="dmn"
            data-active={activeWorkspacePanel === 'dmn' ? 'true' : 'false'}
          >
            <div className="strategy-studio-panel-head">
              <strong>DMN 决策输出</strong>
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
              <strong>条件片段库</strong>
              <span>最新兼容</span>
            </div>
            <ul className="strategy-fragment-list">
              {conditionFragmentsLoading && (
                <li>
                  <span>加载中...</span>
                </li>
              )}
              {conditionFragmentsError && !conditionFragmentsLoading && (
                <li>
                  <span>条件片段加载失败</span>
                </li>
              )}
              {!conditionFragmentsLoading && !conditionFragmentsError && compatibleFragments.length === 0 && (
                <li>
                  <span>暂无条件片段</span>
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
                        {fragmentScenario?.label ?? scenarioScopeLabel(fragment.scopeType)}
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
              <strong>动作输出</strong>
              <span>{scenarioActions.length}</span>
            </div>
            <ol className="strategy-action-list">
              {scenarioActions.map((action) => (
                <li key={action.actionType} data-testid={`strategy-action-${action.actionType}`}>
                  <div className="strategy-action-copy">
                    <strong>{actionLabel(action)}</strong>
                    <span>{actionCategoryLabel(action)}</span>
                  </div>
                </li>
              ))}
            </ol>
          </section>

          <section className="strategy-studio-panel">
            <div className="strategy-studio-panel-head">
              <strong>发布检查</strong>
              <span>{activeScenario.blockers > 0 ? '已阻断' : '就绪'}</span>
            </div>
            <div className="strategy-check-list">
              <span data-state="ok">字段可解析</span>
              <span data-state="ok">片段版本可用</span>
              <span data-state={activeScenario.blockers > 0 ? 'warn' : 'ok'}>
                影响面已确认
              </span>
            </div>
          </section>
        </aside>
      </div>
    </section>
  )
}

export default StrategyStudioWorkbench
