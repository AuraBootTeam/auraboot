import { useMemo } from 'react'
import {
  createDecisionApi,
  type HttpClient,
} from '~/shared/decision/api/decisionApi'
import type { FieldOption } from '~/shared/decision/ui/ConditionBuilder'
import { DecisionOpsConsole } from '~/shared/decision/ui/DecisionOpsConsole'
import { getApiService } from '~/shared/services/ApiService'

/**
 * Integrated DecisionOps console preview.
 * Production governance pages remain DSL-first under /p/decisionops_*; this
 * route keeps the earlier all-in-one console available for design comparison.
 */
const DEFAULT_FIELDS: FieldOption[] = [
  {
    scope: 'record',
    path: 'data.priority',
    label: '优先级',
    dataType: 'enum',
    options: ['HIGH', 'NORMAL', 'LOW'],
  },
  { scope: 'record', path: 'data.amount', label: '金额', dataType: 'decimal' },
  { scope: 'record', path: 'data.status', label: '状态', dataType: 'string' },
]

function createApi() {
  const service = getApiService()
  const http: HttpClient = {
    get: <T,>(endpoint: string, params?: Record<string, unknown>) =>
      service.get<T>(endpoint, params),
    post: <T,>(endpoint: string, body?: unknown) => service.post<T>(endpoint, body),
    delete: <T,>(endpoint: string) => service.delete<T>(endpoint),
  }
  return createDecisionApi(http)
}

export default function DecisionOpsConsolePage() {
  const api = useMemo(() => createApi(), [])
  return <DecisionOpsConsole api={api} fields={DEFAULT_FIELDS} initialTab="dashboard" />
}
