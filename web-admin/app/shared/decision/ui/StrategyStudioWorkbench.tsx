import { useEffect, useMemo, useState } from 'react'
import {
  DecisionRuleBindingBlock,
  type DecisionOption,
} from '~/ui/smart/decision/DecisionRuleBindingBlock'
import type {
  DecisionAction,
  DecisionApi,
  DecisionTableAnalysis,
  DecisionTableDmnXmlResult,
} from '../api/decisionApi'
import type { DecisionTable } from '../table/decisionTable'
import type { FieldOption } from './ConditionBuilder'
import { DecisionTableEditor } from './DecisionTableEditor'

type StrategyScenarioKey = 'SLA' | 'BPM' | 'AUTOMATION' | 'PERMISSION'

interface StrategyScenario {
  key: StrategyScenarioKey
  label: string
  consumer: string
  trigger: string
  ruleCode: string
  decisionCode: string
  fragment: string
  actionTypes: string[]
  blockers: number
  fields: FieldOption[]
}

export interface StrategyStudioWorkbenchProps {
  fields: FieldOption[]
  decisions?: DecisionOption[]
  api: DecisionApi
}

const SCENARIOS: StrategyScenario[] = [
  {
    key: 'SLA',
    label: 'SLA',
    consumer: 'SLA / warningRules',
    trigger: 'breach.warning.30m',
    ruleCode: 'SLA_ESCALATE_HIGH_VALUE',
    decisionCode: 'complaint_sla_deadline',
    fragment: '高价值紧急客诉 v3',
    actionTypes: ['NOTIFY', 'PATCH_RECORD', 'WRITE_AUDIT'],
    blockers: 1,
    fields: [
      {
        scope: 'record',
        path: 'data.customerTier',
        label: '客户等级',
        dataType: 'enum',
        options: ['VIP', 'ENTERPRISE', 'STANDARD'],
      },
      { scope: 'sla', path: 'overdueMinutes', label: '超时分钟', dataType: 'integer' },
      { scope: 'record', path: 'data.ownerUserId', label: '责任人', dataType: 'user' },
    ],
  },
  {
    key: 'BPM',
    label: 'BPM',
    consumer: 'BPM / gateway + assignee',
    trigger: 'task.enter.approval',
    ruleCode: 'BPM_APPROVER_ROUTE',
    decisionCode: 'approval_routing',
    fragment: '高金额审批升级 v5',
    actionTypes: ['START_PROCESS', 'ADD_COMMENT', 'WRITE_AUDIT'],
    blockers: 0,
    fields: [
      { scope: 'process', path: 'taskKey', label: '流程节点', dataType: 'string' },
      { scope: 'record', path: 'data.amount', label: '申请金额', dataType: 'decimal' },
      { scope: 'actor', path: 'roles', label: '操作者角色', dataType: 'collection' },
    ],
  },
  {
    key: 'AUTOMATION',
    label: 'Automation',
    consumer: 'Automation / trigger',
    trigger: 'record.updated',
    ruleCode: 'AUTO_TICKET_ESCALATION',
    decisionCode: 'ticket_escalation_action',
    fragment: '逾期未响应工单 v2',
    actionTypes: ['WEBHOOK', 'NOTIFY', 'PATCH_RECORD'],
    blockers: 0,
    fields: [
      { scope: 'event', path: 'changedFields', label: '变更字段', dataType: 'collection' },
      { scope: 'record', path: 'data.status', label: '记录状态', dataType: 'string' },
      { scope: 'time', path: 'now', label: '当前时间', dataType: 'datetime' },
    ],
  },
  {
    key: 'PERMISSION',
    label: 'Permission',
    consumer: 'Permission / row access',
    trigger: 'query.precheck',
    ruleCode: 'ABAC_TICKET_VISIBILITY',
    decisionCode: 'ticket_visibility_policy',
    fragment: '同组织负责人可见 v2',
    actionTypes: ['WRITE_AUDIT'],
    blockers: 0,
    fields: [
      { scope: 'actor', path: 'orgPath', label: '组织路径', dataType: 'department' },
      { scope: 'record', path: 'data.departmentId', label: '记录部门', dataType: 'department' },
      { scope: 'tenant', path: 'id', label: '租户', dataType: 'string' },
    ],
  },
]

const DECISIONS = [
  { code: 'complaint_sla_deadline', name: '投诉 SLA 截止时间' },
  { code: 'approval_routing', name: '审批路由' },
  { code: 'ticket_escalation_action', name: '工单升级动作' },
  { code: 'ticket_visibility_policy', name: '工单可见性策略' },
]

const SAFE_ACTIONS: DecisionAction[] = [
  { actionType: 'NOTIFY', label: 'NOTIFY', handlerAvailable: true, category: 'messaging' },
  { actionType: 'START_PROCESS', label: 'START_PROCESS', handlerAvailable: true, category: 'workflow' },
  { actionType: 'ADD_COMMENT', label: 'ADD_COMMENT', handlerAvailable: true, category: 'collaboration' },
  { actionType: 'UPDATE_RECORD', label: 'UPDATE_RECORD', handlerAvailable: true, category: 'data' },
  { actionType: 'PATCH_RECORD', label: 'PATCH_RECORD', handlerAvailable: true, category: 'data' },
  { actionType: 'WEBHOOK', label: 'WEBHOOK', handlerAvailable: true, category: 'integration' },
  { actionType: 'WRITE_AUDIT', label: 'WRITE_AUDIT', handlerAvailable: true, category: 'governance' },
]

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
    label: actionType,
    handlerAvailable: false,
  })
}

function actionOutputSchema(actions: DecisionAction[]) {
  return actions
    .filter((action) => action.handlerAvailable !== false)
    .map((action) => ({
      actionType: action.actionType,
      label: action.label ?? action.actionType,
      category: action.category,
      inputSchema: action.inputSchema,
    }))
}

function formatFieldPath(field: FieldOption): string {
  return `${field.scope}.${field.path}`
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
      },
    },
    actor: {
      orgPath: '/hq/sales',
      roles: ['department_manager'],
    },
    event: { changedFields: ['status', 'ownerUserId'] },
    process: { taskKey: 'approval' },
    sla: { overdueMinutes: 45 },
    tenant: { id: 'tenant-demo' },
    time: { now: '2026-07-03T12:00:00Z' },
  }
}

export function StrategyStudioWorkbench({ fields, decisions = [], api }: StrategyStudioWorkbenchProps) {
  const [scenarioKey, setScenarioKey] = useState<StrategyScenarioKey>('SLA')
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
  const scenario = SCENARIOS.find((candidate) => candidate.key === scenarioKey) ?? SCENARIOS[0]
  const decisionOptions = useMemo(() => mergeDecisions(decisions, DECISIONS), [decisions])
  const actionsByType = useMemo(
    () => actionMap(catalogActions.length > 0 ? catalogActions : SAFE_ACTIONS),
    [catalogActions],
  )
  const scenarioActions = useMemo(
    () => resolveScenarioActions(scenario, actionsByType),
    [actionsByType, scenario],
  )
  const scenarioFields = useMemo(
    () => mergeFields(scenario.fields, fields),
    [fields, scenario.fields],
  )
  const scenarioTable = tableDrafts[scenario.key] ?? buildScenarioTable(scenario)

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

  const selectScenario = (next: StrategyScenario) => {
    setScenarioKey(next.key)
    setOperationStatus(`已加载共享片段 · ${next.fragment}`)
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
    scenario.blockers > 0
      ? `发布被阻断 · ${scenario.blockers} 项待处理`
      : `发布检查通过 · ${scenario.consumer}`

  const refreshImpact = async () => {
    setOperationStatus('影响面查询中...')
    try {
      const impact = await api.getDecisionImpact(scenario.decisionCode)
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
        decisionCode: scenario.decisionCode,
        binding: 'LATEST',
        callerType: scenario.key,
        callerRef: scenario.ruleCode,
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
    const target = scenario
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
      [key]: warningCount > 0 ? `${status} · warnings ${warningCount}` : status,
    }))
  }

  const exportScenarioDmn = async () => {
    const target = scenario
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
    const target = scenario
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
    const target = scenario
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
      target.ruleCode
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

  const saveDraft = async (target: StrategyScenario = scenario): Promise<string | null> => {
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
        outputSchemaJson: { actions: actionOutputSchema(resolveScenarioActions(target, actionsByType)) },
        contextSchemaJson: { sample: sampleContext() },
      })
      if (draft.pid) {
        setDraftVersionPids((current) => ({ ...current, [target.key]: draft.pid }))
      }
      setOperationStatus(`草稿已保存 · ${target.ruleCode}`)
      return draft.pid ?? null
    } catch (error) {
      setOperationStatus(`保存失败 · ${errorMessage(error)}`)
      return null
    }
  }

  const publish = async () => {
    if (scenario.blockers > 0) {
      setOperationStatus(publishStatus)
      return
    }
    setOperationStatus('发布中...')
    const pid = draftVersionPids[scenario.key] ?? (await saveDraft())
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
        note: `Published from Strategy Studio for ${scenario.consumer}`,
      })
      if (!published) {
        throw new Error('发布接口未返回版本结果')
      }
      setOperationStatus(`发布成功 · ${scenario.consumer}`)
    } catch (error) {
      setOperationStatus(`发布失败 · ${errorMessage(error)}`)
    }
  }

  return (
    <section className="strategy-studio" data-testid="strategy-studio">
      <header className="strategy-studio-header">
        <div>
          <p>Strategy Studio</p>
          <h3>{scenario.ruleCode}</h3>
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
            aria-pressed={candidate.key === scenario.key}
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
          <strong>{scenario.consumer}</strong>
          <small>{scenario.trigger}</small>
        </div>
        <div>
          <span>字段事实</span>
          <strong>{scenarioFields.length}</strong>
          <small>来自 model / virtual / actor / system</small>
        </div>
        <div>
          <span>动作</span>
          <strong>{scenarioActions.length}</strong>
          <small>命中后统一执行</small>
        </div>
        <div>
          <span>阻断项</span>
          <strong>{scenario.blockers}</strong>
          <small>{scenario.blockers > 0 ? '发布前需处理' : '可进入发布检查'}</small>
        </div>
      </div>

      <div className="strategy-studio-grid">
        <aside className="strategy-studio-panel" data-testid="strategy-fact-catalog">
          <div className="strategy-studio-panel-head">
            <strong>事实目录</strong>
            <span>{scenarioFields.length}</span>
          </div>
          <ul className="strategy-fact-list">
            {scenarioFields.slice(0, 8).map((field) => (
              <li key={fieldKey(field)}>
                <span>{field.label}</span>
                <code>{formatFieldPath(field)}</code>
                <small>{field.dataType}</small>
              </li>
            ))}
          </ul>
        </aside>

        <main className="strategy-studio-center">
          <div className="strategy-studio-panel">
            <div className="strategy-studio-panel-head">
              <strong>规则配置</strong>
              <span>{scenario.fragment}</span>
            </div>
            <DecisionRuleBindingBlock
              key={scenario.key}
              block={{
                props: {
                  mode: 'combined',
                  consumerType: scenario.key,
                  consumerCode: scenario.ruleCode,
                  fieldCatalogMode: 'disabled',
                  showImpactPreview: true,
                  showTestRunner: true,
                  initialDecisionCode: scenario.decisionCode,
                  initialContextJson: JSON.stringify(
                    sampleContext(),
                    null,
                    2,
                  ),
                  fields: scenarioFields,
                  decisions: decisionOptions,
                },
              }}
              api={api}
            />
          </div>

          <div className="strategy-studio-panel" data-testid="strategy-dmn-panel">
            <div className="strategy-studio-panel-head">
              <strong>DMN 决策输出</strong>
              <span>{scenario.decisionCode}</span>
            </div>
            <div className="strategy-table-panel">
              <DecisionTableEditor
                value={scenarioTable}
                onChange={(next) => updateScenarioTable(scenario.key, next)}
                analysis={tableAnalyses[scenario.key] ?? null}
                analyzing={tableAnalyzing}
                analysisError={tableAnalysisErrors[scenario.key] ?? null}
                onAnalyze={analyzeScenarioTable}
                dmnXml={tableDmnXmls[scenario.key] ?? ''}
                dmnBusy={tableDmnBusy}
                dmnError={tableDmnErrors[scenario.key] ?? null}
                dmnStatus={tableDmnStatuses[scenario.key] ?? null}
                onDmnXmlChange={(xml) => setScenarioDmnXml(scenario.key, xml)}
                onExportDmnXml={exportScenarioDmn}
                onImportDmnXml={importScenarioDmn}
                onRoundTripDmnXml={roundTripScenarioDmn}
              />
            </div>
          </div>
        </main>

        <aside className="strategy-studio-side">
          <section className="strategy-studio-panel" data-testid="strategy-fragment-library">
            <div className="strategy-studio-panel-head">
              <strong>条件片段库</strong>
              <span>latest compatible</span>
            </div>
            <ul className="strategy-fragment-list">
              {SCENARIOS.map((candidate) => (
                <li key={candidate.key} data-active={candidate.key === scenario.key}>
                  <button
                    type="button"
                    data-testid={`strategy-fragment-${candidate.key}`}
                    aria-pressed={candidate.key === scenario.key}
                    onClick={() => selectScenario(candidate)}
                  >
                    <span>{candidate.fragment}</span>
                    <small>{candidate.consumer}</small>
                  </button>
                </li>
              ))}
            </ul>
          </section>

          <section className="strategy-studio-panel" data-testid="strategy-action-plan">
            <div className="strategy-studio-panel-head">
              <strong>动作输出</strong>
              <span>{scenarioActions.length}</span>
            </div>
            <ol className="strategy-action-list">
              {scenarioActions.map((action) => (
                <li key={action.actionType}>
                  <strong>{action.actionType}</strong>
                  <span>{action.label ?? action.actionType}</span>
                </li>
              ))}
            </ol>
          </section>

          <section className="strategy-studio-panel">
            <div className="strategy-studio-panel-head">
              <strong>发布检查</strong>
              <span>{scenario.blockers > 0 ? 'blocked' : 'ready'}</span>
            </div>
            <div className="strategy-check-list">
              <span data-state="ok">字段可解析</span>
              <span data-state="ok">片段版本可用</span>
              <span data-state={scenario.blockers > 0 ? 'warn' : 'ok'}>
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
