import { getApiService } from '~/shared/services/ApiService'
import { createDecisionApi, type HttpClient } from '~/shared/decision/api/decisionApi'
import { DecisionOpsConsole } from '~/shared/decision/ui/DecisionOpsConsole'
import type { FieldOption } from '~/shared/decision/ui/ConditionBuilder'

/**
 * DecisionOps console route page (mockup F1-F8, docs/1.md §22). Builds the typed decision API client
 * over the platform ApiService and renders the {@link DecisionOpsConsole} assembly. The Definitions
 * tab self-fetches via react-query; the Designer tab calls the backend validate. Display surfaces
 * (Dashboard / Logs / Connectors / Data Model / Permissions) render their empty states until their
 * list endpoints are wired (follow-on). The field catalogue is a starter set; wiring it to model
 * metadata is a follow-on.
 */

const DEFAULT_FIELDS: FieldOption[] = [
  { scope: 'record', path: 'data.priority', label: '优先级', dataType: 'enum', options: ['HIGH', 'NORMAL', 'LOW'] },
  { scope: 'record', path: 'data.amount', label: '金额', dataType: 'decimal' },
  { scope: 'record', path: 'data.status', label: '状态', dataType: 'string' },
]

export default function DecisionOpsConsolePage() {
  const svc = getApiService()
  const http: HttpClient = {
    get: <T,>(endpoint: string, params?: Record<string, unknown>) => svc.get<T>(endpoint, params),
    post: <T,>(endpoint: string, body?: unknown) => svc.post<T>(endpoint, body),
  }
  const api = createDecisionApi(http)
  return <DecisionOpsConsole api={api} fields={DEFAULT_FIELDS} />
}
