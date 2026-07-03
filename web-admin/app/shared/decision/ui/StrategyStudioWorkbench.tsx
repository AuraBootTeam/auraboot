import { useMemo, useState } from 'react'
import {
  DecisionRuleBindingBlock,
  type DecisionOption,
} from '~/ui/smart/decision/DecisionRuleBindingBlock'
import type { DecisionApi } from '../api/decisionApi'
import type { FieldOption } from './ConditionBuilder'

type StrategyScenarioKey = 'SLA' | 'BPM' | 'AUTOMATION' | 'PERMISSION'

interface StrategyScenario {
  key: StrategyScenarioKey
  label: string
  consumer: string
  trigger: string
  ruleCode: string
  decisionCode: string
  fragment: string
  actions: string[]
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
    actions: ['发送 IM / Email', '升级负责人', '更新 SLA 风险等级'],
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
    actions: ['分配审批人', '抄送任务', '写入流程变量'],
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
    actions: ['调用 Webhook', '发送短信', '创建跟进任务'],
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
    actions: ['限制行级数据', '注入权限上下文', '记录审计事件'],
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

function formatFieldPath(field: FieldOption): string {
  return `${field.scope}.${field.path}`
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '操作失败'
}

function validationMessage(result: Awaited<ReturnType<DecisionApi['validateVersion']>>): string {
  return result?.errors?.[0]?.message ?? '版本校验未通过'
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

function draftCondition(fields: FieldOption[]) {
  const first = fields[0]
  return {
    type: 'group',
    op: 'AND',
    children: first
      ? [
          {
            type: 'compare',
            enabled: true,
            left: {
              type: 'path',
              scope: first.scope,
              path: first.path,
              dataType: first.dataType,
            },
            operator: 'IS_NOT_NULL',
          },
        ]
      : [],
  }
}

export function StrategyStudioWorkbench({ fields, decisions = [], api }: StrategyStudioWorkbenchProps) {
  const [scenarioKey, setScenarioKey] = useState<StrategyScenarioKey>('SLA')
  const [operationStatus, setOperationStatus] = useState<string | null>(null)
  const [draftVersionPids, setDraftVersionPids] = useState<Record<string, string>>({})
  const scenario = SCENARIOS.find((candidate) => candidate.key === scenarioKey) ?? SCENARIOS[0]
  const decisionOptions = useMemo(() => mergeDecisions(decisions, DECISIONS), [decisions])
  const scenarioFields = useMemo(
    () => mergeFields(scenario.fields, fields),
    [fields, scenario.fields],
  )
  const selectScenario = (next: StrategyScenario) => {
    setScenarioKey(next.key)
    setOperationStatus(`已加载共享片段 · ${next.fragment}`)
  }

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
      const targetFields = mergeFields(target.fields, fields)
      const draft = await api.createDraftVersion(target.decisionCode, {
        kind: 'SIMPLE_CONDITION',
        runtimeAdapter: 'AST_EVALUATOR',
        versionTag: `studio-${target.key.toLowerCase()}`,
        contentJson: draftCondition(targetFields),
        inputSchemaJson: { fields: targetFields.map(formatFieldPath) },
        outputSchemaJson: { actions: target.actions },
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
          <strong>{scenario.actions.length}</strong>
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
            <div className="strategy-dmn-table">
              <div>priority</div>
              <div>amount</div>
              <div>route</div>
              <div>actions</div>
              <div>HIGH</div>
              <div>&gt; 100000</div>
              <div>{scenario.key === 'BPM' ? 'director' : 'escalate'}</div>
              <div>{scenario.actions.slice(0, 2).join(' + ')}</div>
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
              <span>{scenario.actions.length}</span>
            </div>
            <ol className="strategy-action-list">
              {scenario.actions.map((action) => (
                <li key={action}>{action}</li>
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
