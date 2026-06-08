import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { DecisionOpsConsole } from '~/shared/decision/ui/DecisionOpsConsole'
import type { DecisionApi } from '~/shared/decision/api/decisionApi'
import type { FieldOption } from '~/shared/decision/ui/ConditionBuilder'

// Mock api: Definitions self-fetches via react-query; validate for the designer.
const api = {
  listDefinitions: async () => [
    { decisionCode: 'big_amount_route', decisionName: '大额路由', scopeType: 'AUTOMATION', ownerModule: 'decision', enabled: true },
    { decisionCode: 'sla_deadline', decisionName: 'SLA 截止', scopeType: 'SLA', ownerModule: 'decision', enabled: true },
    { decisionCode: 'vip_priority', decisionName: 'VIP 优先', scopeType: 'AUTOMATION', ownerModule: 'crm', enabled: false },
  ],
  validate: async () => ({ valid: true, fieldRefs: ['record.data.priority'] }),
} as unknown as DecisionApi

const fields: FieldOption[] = [
  { scope: 'record', path: 'data.priority', label: '优先级', dataType: 'enum', options: ['HIGH', 'NORMAL', 'LOW'] },
  { scope: 'record', path: 'data.amount', label: '金额', dataType: 'decimal' },
]

const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })

createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={client}>
    <h2 style={{ margin: '0 0 12px' }}>DecisionOps 控制台(golden harness)</h2>
    <DecisionOpsConsole
      api={api}
      fields={fields}
      modelFields={[
        { entityCode: 'complaint', path: 'priority', label: '优先级', dataType: 'enum', refs: 18, masked: false, permission: '业务可见' },
        { entityCode: 'complaint', path: 'amount', label: '影响金额', dataType: 'decimal', refs: 12, masked: true, permission: '经理可见' },
      ]}
      samples={[{ label: '高优大额', context: { record: { data: { priority: 'HIGH', amount: 20000 } } } }]}
      logs={[
        { traceId: 'trace-aaa', policyCode: 'complaint_form', status: 'SUCCESS', matchedRules: ['R-101'], actionPlans: ['NOTIFY'], durationMs: 120, time: '09:42' },
        { traceId: 'trace-bbb', policyCode: 'vip_case', status: 'FAILED_RETRYING', matchedRules: ['R-501'], actionPlans: ['WEBHOOK'], durationMs: 921, time: '09:39' },
      ]}
      connectors={[
        { code: 'crm_webhook', name: 'CRM Webhook', type: 'WEBHOOK', endpoint: 'https://crm/hook', authMode: 'HMAC', health: 'HEALTHY', enabled: true },
        { code: 'sms_gw', name: 'SMS 网关', type: 'REST', endpoint: 'https://sms/api', authMode: 'APIKEY', health: 'DEGRADED', enabled: true },
      ]}
      permissionGrants={[
        { role: '流程管理员', caps: { view: true, test: true, publish: true, approve: true, field: true } },
        { role: '服务运营', caps: { view: true, test: true } },
        { role: '审计员', caps: { view: true } },
      ]}
      dashboard={{
        summary: { definitions: 42, policies: 12, evaluationsToday: 200, matched: 150, failed: 3, retrying: 2, p95LatencyMs: 87 },
        exceptions: [{ traceId: 't-1', code: 'vip_case', status: 'FAILED_RETRYING', error: 'connector timeout', time: '09:40' }],
      }}
    />
  </QueryClientProvider>,
)
