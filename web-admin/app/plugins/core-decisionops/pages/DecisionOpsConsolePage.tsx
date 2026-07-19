import { useMemo } from 'react'
import { useLocation } from 'react-router'
import {
  createDecisionApi,
  type HttpClient,
} from '~/shared/decision/api/decisionApi'
import type { FieldOption } from '~/shared/decision/ui/ConditionBuilder'
import { DecisionOpsConsole, type ConsoleTab } from '~/shared/decision/ui/DecisionOpsConsole'
import { getApiService } from '~/shared/services/ApiService'

/**
 * Integrated Strategy Studio entry.
 * Governance asset pages stay DSL-first under /p/decisionops_*; this route is
 * the cross-module authoring surface for rule consumers such as SLA, BPM,
 * Automation, and Permission.
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

const CONSOLE_TABS = new Set<ConsoleTab>([
  'studio',
  'dashboard',
  'policies',
  'definitions',
  'designer',
  'tables',
  'rollouts',
  'logs',
  'model',
  'permissions',
  'connectors',
])

function tabFromSearch(value: string | null): ConsoleTab {
  return value && CONSOLE_TABS.has(value as ConsoleTab) ? (value as ConsoleTab) : 'studio'
}

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
  const location = useLocation()
  const browserSearch = typeof window !== 'undefined' ? window.location.search : ''
  const searchParams = new URLSearchParams(location.search || browserSearch)
  return (
    <DecisionOpsConsole
      api={api}
      fields={DEFAULT_FIELDS}
      initialTab={tabFromSearch(searchParams.get('tab'))}
    />
  )
}
