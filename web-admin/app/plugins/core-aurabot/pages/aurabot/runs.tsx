/**
 * AuraBot — Run Log & Call Traces (tabbed)
 *
 * Unified page for:
 *  Tab 1 "Run Log"     — agent run records with expandable tool-call timelines
 *  Tab 2 "Call Traces" — LLM call traces with filtering, stats, and drill-down
 *
 * Previously separate routes; merged into /aurabot/runs with two tabs.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { get, post } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';
import { TraceStatusBadge } from '~/plugins/core-aurabot/pages/ai-trace/components/TraceStatusBadge';

// ============================================================================
// Types — Run Log
// ============================================================================

interface RunRecord {
  pid: string;
  run_status: string;
  run_model: string;
  started_at: string;
  completed_at: string;
  duration_ms: number;
  input_tokens: number;
  output_tokens: number;
  total_cost: number;
  error_message: string;
  task_title: string;
  user_message?: string | null;
  messages?: string | null;
  metadata?: string | Record<string, unknown> | null;
  session_id?: string | null;
  trace_id?: string | null;
  agent_name: string;
  tool_calls: string | ToolCall[] | null;
  execution_plan: string | PlanStep[] | null;
  current_step: number;
}

interface ToolCall {
  tool: string;
  input: Record<string, unknown>;
  output: unknown;
  loop: number;
}

interface PlanStep {
  stepIndex: number;
  description: string;
  toolCode: string | null;
  status: 'pending' | 'running' | 'completed' | 'failed' | 'skipped' | 'awaiting_approval';
  result: unknown;
  error: unknown;
  durationMs: number;
  requiresApproval: boolean;
  planVersion?: number;
  tokens?: number;
}

// ============================================================================
// Types — Traces
// ============================================================================

interface AiTrace {
  traceId: string;
  sessionId: string;
  name: string | null;
  input: string;
  output: string;
  status: string;
  errorMessage: string | null;
  durationMs: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalCost: number;
  metadata: Record<string, unknown>;
  tags: string[] | null;
  startTime: string;
  endTime: string | null;
}

interface TraceStats {
  totalTraces: number;
  successCount: number;
  errorCount: number;
  successRate: number;
  avgDurationMs: number | null;
  totalCost: number;
  totalInputTokens: number;
  totalOutputTokens: number;
}

// ============================================================================
// Helpers — shared
// ============================================================================

function fetchNQ<T>(code: string, extra?: Record<string, string>): Promise<T[]> {
  return get<{ records: T[] }>('/api/datasource/list', {
    datasourceId: `nq:${code}`,
    format: 'records',
    ...extra,
  }).then((res) => (ResultHelper.isSuccess(res) && res.data?.records ? res.data.records : []));
}

function fmtCost(n: number): string {
  if (!n) return '$0';
  if (n >= 100) return `$${n.toFixed(0)}`;
  if (n >= 1) return `$${n.toFixed(2)}`;
  return `$${n.toFixed(4)}`;
}

function fmtDuration(ms: number | null): string {
  if (ms == null || ms === 0) return '-';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60000)}m ${Math.round((ms % 60000) / 1000)}s`;
}

function fmtTokens(n: number): string {
  if (!n) return '0';
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return String(n);
}

function fmtValue(value: unknown, empty = '-'): string {
  if (value == null || value === '') return empty;
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function fmtJsonish(value: unknown, empty = '-'): string {
  if (value == null || value === '') return empty;
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }
  return fmtValue(value, empty);
}

function parseJsonObject(value: unknown): Record<string, unknown> {
  if (!value) return {};
  if (typeof value === 'object' && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value);
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
        ? (parsed as Record<string, unknown>)
        : {};
    } catch {
      return {};
    }
  }
  return {};
}

function summarizeToolOutput(output: unknown, l: (zh: string, en: string) => string): string | null {
  if (!output || typeof output !== 'object' || Array.isArray(output)) return null;
  const obj = output as Record<string, any>;
  if (obj.success === false) {
    return fmtValue(obj.error || obj.message, l('工具执行失败', 'Tool failed'));
  }
  const data = obj.data;
  if (data && typeof data === 'object') {
    if (Array.isArray(data.records)) {
      return l('查询成功', 'Query ok') + ` · ${data.records.length} ${l('条记录', 'records')}`;
    }
    if (typeof data.total === 'number') {
      return l('查询成功', 'Query ok') + ` · ${data.total} ${l('条结果', 'results')}`;
    }
    if (typeof data.message === 'string' && data.message.trim()) {
      return data.message;
    }
  }
  if (typeof obj.message === 'string' && obj.message.trim()) {
    return obj.message;
  }
  return null;
}

function timeAgo(dateStr: string, l: (zh: string, en: string) => string): string {
  if (!dateStr) return '-';
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return l('刚刚', 'just now');
  if (mins < 60) return `${mins}${l('分钟前', 'm ago')}`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}${l('小时前', 'h ago')}`;
  const days = Math.floor(hrs / 24);
  return `${days}${l('天前', 'd ago')}`;
}

/** Hook: subscribe to agent SSE events */
function useAgentSse(): number {
  const [refreshKey, setRefreshKey] = useState(0);
  const esRef = useRef<EventSource | null>(null);

  useEffect(() => {
    const es = new EventSource('/api/agent/events/stream');
    esRef.current = es;

    es.addEventListener('agent-event', () => {
      setRefreshKey((k) => k + 1);
    });

    es.onerror = () => {
      // Auto-reconnect handled by browser EventSource
    };

    return () => {
      es.close();
      esRef.current = null;
    };
  }, []);

  return refreshKey;
}

/** Hook: auto-refresh on interval + SSE events */
function useAutoRefresh(sseKey: number, intervalMs = 15000): number {
  const [tick, setTick] = useState(0);

  useEffect(() => {
    const id = setInterval(() => setTick((t) => t + 1), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);

  return tick + sseKey;
}

// ============================================================================
// Route meta
// ============================================================================

export function meta() {
  return [
    { title: 'Run Log & Traces - AuraBot' },
    {
      name: 'description',
      content: 'View agent run records and LLM call traces with timelines and cost analysis',
    },
  ];
}

// ============================================================================
// Shared small components
// ============================================================================

function Spinner() {
  return (
    <div className="flex min-h-[300px] items-center justify-center">
      <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600 dark:border-blue-400" />
    </div>
  );
}

function EmptyState({ text }: { text: string }) {
  return (
    <div className="py-16 text-center">
      <div className="mb-3 text-4xl">▶️</div>
      <div className="mx-auto max-w-md text-sm text-gray-500 dark:text-gray-400">{text}</div>
    </div>
  );
}

function InfoPanel({
  title,
  value,
  mono = true,
}: {
  title: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white p-3 dark:border-gray-700 dark:bg-gray-900">
      <div className="mb-1 text-xs font-medium text-gray-500 dark:text-gray-400">{title}</div>
      <pre
        className={`break-words whitespace-pre-wrap ${mono ? 'font-mono text-xs' : 'text-sm'} text-gray-800 dark:text-gray-200`}
      >
        {value}
      </pre>
    </div>
  );
}

function RunStatusIcon({ status }: { status: string }) {
  const icons: Record<string, string> = {
    running: '🔄',
    success: '✅',
    failed: '❌',
    cancelled: '⏹️',
    TIMEOUT: '⏰',
    pending: '⏳',
  };
  return <span className="text-lg">{icons[status] || '❓'}</span>;
}

// ============================================================================
// Tabbed Page (top-level export)
// ============================================================================

type TabKey = 'runs' | 'traces';

export default function RunLogsPage() {
  const { locale } = useI18n();
  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const [searchParams, setSearchParams] = useSearchParams();
  const initialTab = (searchParams.get('tab') as TabKey) ?? 'runs';
  const [activeTab, setActiveTab] = useState<TabKey>(initialTab);

  const sseKey = useAgentSse();
  const refreshKey = useAutoRefresh(sseKey);

  const handleTabChange = useCallback(
    (tab: TabKey) => {
      setActiveTab(tab);
      setSearchParams(tab === 'runs' ? {} : { tab });
    },
    [setSearchParams],
  );

  const tabBtn = (tab: TabKey, label: string) => (
    <button
      onClick={() => handleTabChange(tab)}
      className={`rounded-md px-4 py-1.5 text-sm transition-colors ${
        activeTab === tab
          ? 'bg-white font-medium text-gray-900 shadow dark:bg-gray-700 dark:text-white'
          : 'text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200'
      }`}
      data-testid={`tab-${tab}`}
    >
      {label}
    </button>
  );

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900">
      {/* Header */}
      <div className="border-b border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
        <div className="mx-auto max-w-7xl px-6 py-5">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-gradient-to-br from-emerald-500 to-teal-600">
                <span className="text-lg text-white">▶</span>
              </div>
              <div>
                <h1 className="text-xl font-bold text-gray-900 dark:text-white">
                  {l('运行记录', 'Run Logs')}
                </h1>
                <p className="mt-0.5 text-sm text-gray-500 dark:text-gray-400">
                  {l('Agent 运行记录与 LLM 调用追踪', 'Agent run records and LLM call traces')}
                </p>
              </div>
            </div>

            {/* Tab switcher */}
            <div className="flex gap-1 rounded-lg bg-gray-100 p-1 dark:bg-gray-700/50">
              {tabBtn('runs', l('运行记录', 'Run Log'))}
              {tabBtn('traces', l('调用追踪', 'Call Traces'))}
            </div>
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="mx-auto max-w-7xl px-6 py-6">
        {activeTab === 'runs' ? (
          <RunLogContent l={l} refreshKey={refreshKey} />
        ) : (
          <TraceContent l={l} />
        )}
      </div>
    </div>
  );
}

// ============================================================================
// Tab 1: Run Log Content
// ============================================================================

function RunLogContent({
  l,
  refreshKey,
}: {
  l: (zh: string, en: string) => string;
  refreshKey: number;
}) {
  const [runs, setRuns] = useState<RunRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [expandedRun, setExpandedRun] = useState<string | null>(null);
  const [runDetail, setRunDetail] = useState<{
    tool_calls: ToolCall[];
    error_message: string;
  } | null>(null);
  const [retryingRun, setRetryingRun] = useState<string | null>(null);

  const loadRuns = useCallback(() => {
    return fetchNQ<RunRecord>('acp_recent_runs', { maxItems: '50' }).then(setRuns);
  }, []);

  const handleRetry = useCallback(
    async (runPid: string) => {
      setRetryingRun(runPid);
      try {
        await post<unknown>(`/api/agent/run/${runPid}/retry`, {});
        await loadRuns();
      } finally {
        setRetryingRun(null);
      }
    },
    [loadRuns],
  );

  useEffect(() => {
    const isInitial = refreshKey === 0;
    if (isInitial) setLoading(true);
    loadRuns().finally(() => setLoading(false));
  }, [refreshKey, loadRuns]);

  const toggleExpand = useCallback(
    (pid: string) => {
      if (expandedRun === pid) {
        setExpandedRun(null);
        setRunDetail(null);
        return;
      }
      setExpandedRun(pid);
      setRunDetail(null);
      // Fetch full run detail including tool_calls
      get<{ records: Array<{ tool_calls: string | ToolCall[]; error_message: string }> }>(
        '/api/datasource/list',
        {
          datasourceId: `nq:acp_run_detail`,
          format: 'records',
          run_pid: pid,
        },
      ).then((res) => {
        if (ResultHelper.isSuccess(res) && res.data?.records?.length) {
          const row = res.data.records[0];
          let toolCalls: ToolCall[] = [];
          if (row.tool_calls) {
            try {
              toolCalls =
                typeof row.tool_calls === 'string' ? JSON.parse(row.tool_calls) : row.tool_calls;
            } catch {
              toolCalls = [];
            }
          }
          setRunDetail({ tool_calls: toolCalls, error_message: row.error_message });
        }
      });
    },
    [expandedRun],
  );

  if (loading) return <Spinner />;

  return (
    <div data-testid="run-log">
      {runs.length === 0 ? (
        <EmptyState text={l('暂无运行记录。', 'No run records yet.')} />
      ) : (
        <div className="overflow-hidden rounded-lg bg-white shadow-sm dark:bg-gray-800">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800">
                <th className="w-8 px-2"></th>
                <th className="px-4 py-3 text-center font-medium text-gray-500">
                  {l('状态', 'Status')}
                </th>
                <th className="px-4 py-3 text-left font-medium text-gray-500">Agent</th>
                <th className="px-4 py-3 text-left font-medium text-gray-500">
                  {l('任务', 'Task')}
                </th>
                <th className="px-4 py-3 text-left font-medium text-gray-500">
                  {l('模型', 'Model')}
                </th>
                <th className="px-4 py-3 text-right font-medium text-gray-500">
                  {l('时长', 'Duration')}
                </th>
                <th className="px-4 py-3 text-right font-medium text-gray-500">Tokens</th>
                <th className="px-4 py-3 text-right font-medium text-gray-500">
                  {l('成本', 'Cost')}
                </th>
                <th className="px-4 py-3 text-right font-medium text-gray-500">
                  {l('时间', 'Time')}
                </th>
                <th className="w-16 px-2 py-3"></th>
              </tr>
            </thead>
            <tbody>
              {runs.map((run) => (
                <RunRow
                  key={run.pid}
                  run={run}
                  l={l}
                  expanded={expandedRun === run.pid}
                  detail={expandedRun === run.pid ? runDetail : null}
                  onToggle={() => toggleExpand(run.pid)}
                  onRetry={handleRetry}
                  retrying={retryingRun === run.pid}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ============================================================================
// Run Row (expandable)
// ============================================================================

function RunRow({
  run,
  l,
  expanded,
  detail,
  onToggle,
  onRetry,
  retrying,
}: {
  run: RunRecord;
  l: (zh: string, en: string) => string;
  expanded: boolean;
  detail: { tool_calls: ToolCall[]; error_message: string } | null;
  onToggle: () => void;
  onRetry: (pid: string) => void;
  retrying: boolean;
}) {
  const canRetry = ['failed', 'timeout'].includes(run.run_status);
  const metadata = parseJsonObject(run.metadata);
  const taskText = fmtValue(
    run.user_message || run.task_title || metadata.userMessage || metadata.prompt,
  );
  const sessionId = fmtValue(run.session_id || metadata.sessionId, '');
  const traceId = fmtValue(run.trace_id || metadata.traceId, '');

  return (
    <>
      <tr
        className="cursor-pointer border-b border-gray-100 transition-colors hover:bg-gray-50 dark:border-gray-700/50 dark:hover:bg-gray-700/30"
        onClick={onToggle}
      >
        <td className="px-2 text-center text-gray-400">
          <span className={`inline-block transition-transform ${expanded ? 'rotate-90' : ''}`}>
            &#9654;
          </span>
        </td>
        <td className="px-4 py-3 text-center">
          <RunStatusIcon status={run.run_status} />
        </td>
        <td className="px-4 py-3 font-medium text-gray-900 dark:text-white">
          {fmtValue(run.agent_name)}
        </td>
        <td className="max-w-[280px] px-4 py-3 text-gray-600 dark:text-gray-400">
          <div className="truncate">{taskText}</div>
          {(sessionId || traceId) && (
            <div className="mt-1 truncate text-[11px] text-gray-400 dark:text-gray-500">
              {[sessionId ? `session: ${sessionId}` : '', traceId ? `trace: ${traceId}` : '']
                .filter(Boolean)
                .join(' | ')}
            </div>
          )}
        </td>
        <td className="px-4 py-3 text-xs text-gray-500 dark:text-gray-400">
          {fmtValue(run.run_model)}
        </td>
        <td className="px-4 py-3 text-right font-mono text-gray-600 dark:text-gray-400">
          {fmtDuration(run.duration_ms)}
        </td>
        <td className="px-4 py-3 text-right font-mono text-gray-600 dark:text-gray-400">
          {run.input_tokens + run.output_tokens > 0
            ? (run.input_tokens + run.output_tokens).toLocaleString()
            : '-'}
        </td>
        <td className="px-4 py-3 text-right font-mono text-gray-900 dark:text-white">
          {run.total_cost > 0 ? fmtCost(run.total_cost) : '-'}
        </td>
        <td className="px-4 py-3 text-right text-xs text-gray-400">{timeAgo(run.started_at, l)}</td>
        <td className="px-2 text-center">
          {canRetry && (
            <button
              className="rounded bg-amber-50 px-2 py-1 text-xs font-medium text-amber-600 transition-colors hover:bg-amber-100 disabled:opacity-50 dark:bg-amber-900/30 dark:text-amber-400 dark:hover:bg-amber-900/50"
              onClick={(e) => {
                e.stopPropagation();
                onRetry(run.pid);
              }}
              disabled={retrying}
              data-testid={`retry-run-${run.pid}`}
              title={l('重试', 'Retry')}
            >
              {retrying ? '...' : l('重试', 'Retry')}
            </button>
          )}
        </td>
      </tr>
      {expanded && (
        <tr className="bg-gray-50/50 dark:bg-gray-800/50">
          <td colSpan={10} className="px-6 py-4">
            {!detail ? (
              <div className="text-sm text-gray-400">{l('加载中...', 'Loading...')}</div>
            ) : (
              <RunDetailPanel detail={detail} run={run} l={l} />
            )}
          </td>
        </tr>
      )}
    </>
  );
}

// ============================================================================
// Run Detail Panel (tool calls + execution plan)
// ============================================================================

function RunDetailPanel({
  detail,
  run,
  l,
}: {
  detail: { tool_calls: ToolCall[]; error_message: string };
  run: RunRecord;
  l: (zh: string, en: string) => string;
}) {
  const metadata = parseJsonObject(run.metadata);
  const userProblem = fmtValue(
    run.user_message || run.task_title || metadata.userMessage || metadata.prompt,
  );
  const finalResponse = fmtValue(run.messages || metadata.finalResponsePreview, '');
  const sessionId = fmtValue(run.session_id || metadata.sessionId, '');
  const traceId = fmtValue(run.trace_id || metadata.traceId, '');
  const rawPlan = run.execution_plan;
  const plan: PlanStep[] | null =
    typeof rawPlan === 'string'
      ? (() => {
          try {
            return JSON.parse(rawPlan);
          } catch {
            return null;
          }
        })()
      : Array.isArray(rawPlan)
        ? rawPlan
        : null;

  return (
    <div className="space-y-4">
      <div className="grid gap-3 md:grid-cols-2">
        <InfoPanel
          title={l('原始问题', 'Original Problem')}
          value={userProblem}
          mono={false}
        />
        <InfoPanel
          title={l('会话信息', 'Session Context')}
          value={[
            run.pid ? `runPid: ${run.pid}` : '',
            sessionId ? `sessionId: ${sessionId}` : '',
            traceId ? `traceId: ${traceId}` : '',
          ]
            .filter(Boolean)
            .join('\n')}
        />
      </div>

      {finalResponse && (
        <InfoPanel title={l('最终回复', 'Final Response')} value={finalResponse} mono={false} />
      )}

      {traceId && traceId !== '-' && (
        <div className="rounded-lg border border-blue-200 bg-blue-50 p-3 dark:border-blue-800 dark:bg-blue-900/20">
          <div className="mb-2 text-xs font-medium text-blue-700 dark:text-blue-300">
            {l('调用追踪', 'Trace')}
          </div>
          <a
            href={`/aurabot/traces/${traceId}`}
            target="_blank"
            rel="noreferrer"
            className="font-mono text-sm text-blue-600 underline-offset-2 hover:underline dark:text-blue-300"
          >
            {traceId}
          </a>
        </div>
      )}

      {/* Error Message */}
      {detail.error_message && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-3 dark:border-red-800 dark:bg-red-900/20">
          <div className="mb-1 text-xs font-medium text-red-600 dark:text-red-400">
            {l('错误信息', 'Error')}
          </div>
          <pre className="font-mono text-sm break-words whitespace-pre-wrap text-red-800 dark:text-red-300">
            {fmtValue(detail.error_message)}
          </pre>
        </div>
      )}

      {/* Tool Calls Timeline */}
      {detail.tool_calls.length > 0 ? (
        <div>
          <div className="mb-3 text-xs font-medium text-gray-500 dark:text-gray-400">
            {l('工具调用链', 'Tool Call Chain')} ({detail.tool_calls.length} {l('次调用', 'calls')})
          </div>
          <div className="space-y-2">
            {detail.tool_calls.map((tc, i) => (
              <ToolCallItem key={i} tc={tc} index={i} l={l} />
            ))}
          </div>
        </div>
      ) : (
        <div className="text-sm text-gray-400">{l('无工具调用', 'No tool calls')}</div>
      )}

      {/* Execution Plan */}
      {plan && plan.length > 0 && (
        <div className="mt-3 border-t border-gray-200 pt-3 dark:border-gray-700">
          <PlanStepsView plan={plan} l={l} />
        </div>
      )}

      {/* Resume Button */}
      {run.execution_plan && ['failed', 'timeout', 'pending'].includes(run.run_status) && (
        <div className="pt-2">
          <button
            className="rounded bg-blue-100 px-2 py-0.5 text-xs text-blue-700 hover:bg-blue-200 dark:bg-blue-900/30 dark:text-blue-300"
            onClick={async (e) => {
              e.stopPropagation();
              await post('/api/agent/run/' + run.pid + '/resume');
            }}
            data-testid={`resume-run-${run.pid}`}
          >
            {l('恢复执行', 'Resume')}
          </button>
        </div>
      )}
    </div>
  );
}

// ============================================================================
// Tool Call Item (collapsible)
// ============================================================================

function ToolCallItem({
  tc,
  index,
  l,
}: {
  tc: ToolCall;
  index: number;
  l: (zh: string, en: string) => string;
}) {
  const [showDetail, setShowDetail] = useState(false);
  const outputText = fmtJsonish(tc.output, '');
  const isError = outputText.startsWith('Error');

  return (
    <div
      className={`overflow-hidden rounded-lg border ${isError ? 'border-red-200 dark:border-red-800' : 'border-gray-200 dark:border-gray-700'}`}
    >
      <div
        className="flex cursor-pointer items-center gap-3 px-3 py-2 hover:bg-gray-50 dark:hover:bg-gray-700/30"
        onClick={() => setShowDetail(!showDetail)}
      >
        <span className="w-6 font-mono text-xs text-gray-400">#{index + 1}</span>
        <span
          className={`rounded px-1.5 py-0.5 font-mono text-xs ${isError ? 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300' : 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300'}`}
        >
          {fmtValue(tc.tool)}
        </span>
        <span className="ml-auto text-xs text-gray-400">
          {l('循环', 'Loop')} {tc.loop} · {showDetail ? '▼' : '▶'}
        </span>
      </div>
      {showDetail && (
        <div className="space-y-2 border-t border-gray-200 bg-white px-3 py-2 dark:border-gray-700 dark:bg-gray-900">
          <div>
            <div className="mb-1 text-xs font-medium text-gray-500">{l('输入', 'Input')}</div>
            <pre className="max-h-40 overflow-x-auto rounded bg-gray-50 p-2 font-mono text-xs text-gray-700 dark:bg-gray-800 dark:text-gray-300">
              {JSON.stringify(tc.input, null, 2)}
            </pre>
          </div>
          <div>
            <div className="mb-1 text-xs font-medium text-gray-500">{l('输出', 'Output')}</div>
            {summarizeToolOutput(tc.output, l) && (
              <div className="mb-2 rounded border border-emerald-200 bg-emerald-50 px-2 py-1 text-xs text-emerald-700 dark:border-emerald-800 dark:bg-emerald-900/20 dark:text-emerald-300">
                {summarizeToolOutput(tc.output, l)}
              </div>
            )}
            <pre
              className={`max-h-40 overflow-x-auto rounded p-2 font-mono text-xs ${isError ? 'bg-red-50 text-red-700 dark:bg-red-900/20 dark:text-red-300' : 'bg-gray-50 text-gray-700 dark:bg-gray-800 dark:text-gray-300'}`}
            >
              {outputText}
            </pre>
          </div>
        </div>
      )}
    </div>
  );
}

// ============================================================================
// Plan Steps View
// ============================================================================

function PlanStepsView({ plan, l }: { plan: PlanStep[]; l: (zh: string, en: string) => string }) {
  const [expandedSteps, setExpandedSteps] = useState<Set<number>>(new Set());

  const stepStatusIcon: Record<string, string> = {
    completed: '✅',
    failed: '❌',
    running: '🔄',
    pending: '⏳',
    skipped: '⏭️',
    AWAITING_APPROVAL: '🛡️',
  };

  const stepStatusBg: Record<string, string> = {
    completed: 'bg-green-500',
    failed: 'bg-red-500',
    running: 'bg-blue-500',
    pending: 'bg-gray-300 dark:bg-gray-600',
    skipped: 'bg-gray-300 dark:bg-gray-600',
    AWAITING_APPROVAL: 'bg-amber-500',
  };

  const stepCardBorder: Record<string, string> = {
    completed: 'border-green-200 dark:border-green-800/50',
    failed: 'border-red-200 dark:border-red-800/50',
    running: 'border-blue-200 dark:border-blue-800/50',
    pending: 'border-gray-200 dark:border-gray-700',
    skipped: 'border-gray-200 dark:border-gray-700',
    AWAITING_APPROVAL: 'border-amber-200 dark:border-amber-800/50',
  };

  // Progress calculation
  const completed = plan.filter((s) => s.status === 'completed').length;
  const failed = plan.filter((s) => s.status === 'failed').length;
  const running = plan.filter((s) => s.status === 'running').length;
  const total = plan.length;

  // Max duration for proportional bars
  const maxDuration = Math.max(...plan.map((s) => s.durationMs || 0), 1);

  // Detect replan (multiple plan versions)
  const maxPlanVersion = Math.max(...plan.map((s) => s.planVersion || 1));
  const hasReplan = maxPlanVersion > 1;

  const toggleExpand = (stepIndex: number) => {
    setExpandedSteps((prev) => {
      const next = new Set(prev);
      if (next.has(stepIndex)) next.delete(stepIndex);
      else next.add(stepIndex);
      return next;
    });
  };

  return (
    <div data-testid="plan-steps">
      {/* Header with progress */}
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-xs font-medium text-gray-500 dark:text-gray-400">
            {l('执行计划', 'Execution Plan')}
          </span>
          <span className="text-xs text-gray-400 dark:text-gray-500">
            {completed}/{total} {l('完成', 'done')}
          </span>
          {hasReplan && (
            <span className="rounded bg-purple-100 px-1.5 py-0.5 text-[10px] font-medium text-purple-700 dark:bg-purple-900/30 dark:text-purple-300">
              v{maxPlanVersion} {l('重规划', 'Replanned')}
            </span>
          )}
        </div>
        {running > 0 && (
          <span className="animate-pulse rounded-full bg-blue-100 px-1.5 py-0.5 text-[10px] text-blue-700 dark:bg-blue-900/30 dark:text-blue-300">
            {running} {l('运行中', 'running')}
          </span>
        )}
      </div>

      {/* Progress bar */}
      <div className="mb-3 h-1.5 overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
        <div className="flex h-full">
          {completed > 0 && (
            <div
              className="bg-green-500 transition-all duration-300"
              style={{ width: `${(completed / total) * 100}%` }}
            />
          )}
          {running > 0 && (
            <div
              className="animate-pulse bg-blue-500 transition-all duration-300"
              style={{ width: `${(running / total) * 100}%` }}
            />
          )}
          {failed > 0 && (
            <div
              className="bg-red-500 transition-all duration-300"
              style={{ width: `${(failed / total) * 100}%` }}
            />
          )}
        </div>
      </div>

      {/* Timeline steps */}
      <div className="relative">
        {/* Vertical timeline line */}
        <div className="absolute top-2 bottom-2 left-[15px] w-px bg-gray-200 dark:bg-gray-700" />

        <div className="space-y-2">
          {plan.map((step) => {
            const isExpanded = expandedSteps.has(step.stepIndex);
            const durationPct =
              step.durationMs > 0 ? Math.max((step.durationMs / maxDuration) * 100, 3) : 0;

            return (
              <div
                key={step.stepIndex}
                className="relative cursor-pointer pl-9"
                data-testid={`plan-step-${step.stepIndex}`}
                onClick={() => toggleExpand(step.stepIndex)}
              >
                {/* Timeline node */}
                <div
                  className={`absolute top-2.5 left-[10px] z-10 h-3 w-3 rounded-full border-2 border-white dark:border-gray-800 ${stepStatusBg[step.status] || 'bg-gray-300'}`}
                />

                {/* Step card */}
                <div
                  className={`rounded-lg border p-2.5 transition-all hover:shadow-sm ${stepCardBorder[step.status] || 'border-gray-200 dark:border-gray-700'} ${
                    step.status === 'running'
                      ? 'bg-blue-50/50 dark:bg-blue-900/10'
                      : 'bg-white dark:bg-gray-800/50'
                  }`}
                >
                  <div className="flex items-start gap-2">
                    <span className="mt-0.5 flex-shrink-0 text-sm">
                      {stepStatusIcon[step.status] || '⏳'}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="text-sm text-gray-800 dark:text-gray-200">
                          {fmtValue(step.description)}
                        </span>
                        {step.requiresApproval && (
                          <span className="rounded bg-amber-100 px-1 py-0.5 text-[10px] text-amber-700 dark:bg-amber-900/30 dark:text-amber-300">
                            {l('需审批', 'Approval')}
                          </span>
                        )}
                      </div>

                      <div className="mt-1 flex items-center gap-2">
                        {step.toolCode && (
                          <span className="rounded bg-gray-100 px-1.5 py-0.5 font-mono text-[10px] text-gray-600 dark:bg-gray-700 dark:text-gray-400">
                            {fmtValue(step.toolCode)}
                          </span>
                        )}
                        {step.tokens != null && step.tokens > 0 && (
                          <span className="text-[10px] text-gray-400 dark:text-gray-500">
                            {step.tokens.toLocaleString()} tokens
                          </span>
                        )}
                        {step.planVersion != null && step.planVersion > 1 && (
                          <span className="text-[10px] text-purple-500 dark:text-purple-400">
                            v{step.planVersion}
                          </span>
                        )}
                      </div>

                      {/* Duration bar */}
                      {step.durationMs > 0 && (
                        <div className="mt-1.5 flex items-center gap-2">
                          <div className="h-1 flex-1 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-700">
                            <div
                              className={`h-full rounded-full transition-all ${stepStatusBg[step.status] || 'bg-gray-300'}`}
                              style={{ width: `${durationPct}%` }}
                            />
                          </div>
                          <span className="flex-shrink-0 font-mono text-[10px] text-gray-400 tabular-nums dark:text-gray-500">
                            {fmtDuration(step.durationMs)}
                          </span>
                        </div>
                      )}
                    </div>

                    {/* Expand indicator */}
                    {(step.result != null || step.error != null) && (
                      <span className="mt-1 flex-shrink-0 text-[10px] text-gray-400">
                        {isExpanded ? '▼' : '▶'}
                      </span>
                    )}
                  </div>

                  {/* Expanded result/error */}
                  {isExpanded && (step.result != null || step.error != null) && (
                    <div className="mt-2 border-t border-gray-100 pt-2 dark:border-gray-700">
                      {step.result != null && (
                        <pre className="max-h-32 overflow-x-auto rounded bg-green-50 p-2 font-mono text-xs break-words whitespace-pre-wrap text-green-700 dark:bg-green-900/10 dark:text-green-400">
                          {fmtJsonish(step.result)}
                        </pre>
                      )}
                      {step.error != null && (
                        <pre className="max-h-32 overflow-x-auto rounded bg-red-50 p-2 font-mono text-xs break-words whitespace-pre-wrap text-red-700 dark:bg-red-900/10 dark:text-red-300">
                          {fmtJsonish(step.error)}
                        </pre>
                      )}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

// ============================================================================
// Tab 2: Trace Content
// ============================================================================

function TraceContent({ l }: { l: (zh: string, en: string) => string }) {
  const navigate = useNavigate();

  const [traces, setTraces] = useState<AiTrace[]>([]);
  const [loading, setLoading] = useState(true);
  const [pageNum, setPageNum] = useState(1);
  const [total, setTotal] = useState(0);
  const [statusFilter, setStatusFilter] = useState('');
  const [keyword, setKeyword] = useState('');
  const [keywordInput, setKeywordInput] = useState('');
  const [stats, setStats] = useState<TraceStats | null>(null);

  const fetchTraces = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({
        pageNum: String(pageNum),
        pageSize: '20',
      });
      if (statusFilter) params.set('status', statusFilter);
      if (keyword) params.set('keyword', keyword);

      const res = await fetch(`/api/ai/traces?${params}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      setTraces(Array.isArray(data?.records) ? data.records : []);
      setTotal(typeof data?.total === 'number' ? data.total : 0);
    } catch (e) {
      console.error('Failed to fetch traces', e);
      setTraces([]);
    } finally {
      setLoading(false);
    }
  }, [pageNum, statusFilter, keyword]);

  const fetchStats = useCallback(async () => {
    try {
      const res = await fetch('/api/ai/traces/stats');
      if (!res.ok) return;
      const data = await res.json();
      setStats(data);
    } catch {
      /* ignore */
    }
  }, []);

  useEffect(() => {
    fetchTraces();
  }, [fetchTraces]);

  useEffect(() => {
    fetchStats();
  }, [fetchStats]);

  const handleRefresh = useCallback(() => {
    fetchTraces();
    fetchStats();
  }, [fetchTraces, fetchStats]);

  const handleSearch = useCallback(() => {
    setKeyword(keywordInput.trim());
    setPageNum(1);
  }, [keywordInput]);

  const totalPages = Math.ceil(total / 20);

  return (
    <div className="space-y-6" data-testid="trace-content">
      {/* Stats Cards */}
      {stats && <TraceStatsCards stats={stats} l={l} />}

      {/* Filters */}
      <div className="flex flex-wrap items-center gap-3">
        <select
          value={statusFilter}
          onChange={(e) => {
            setStatusFilter(e.target.value);
            setPageNum(1);
          }}
          className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-300"
          data-testid="trace-status-filter"
        >
          <option value="">{l('全部状态', 'All Status')}</option>
          <option value="success">success</option>
          <option value="error">ERROR</option>
          <option value="in_progress">in_progress</option>
        </select>

        <div className="flex">
          <input
            type="text"
            value={keywordInput}
            onChange={(e) => setKeywordInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
            placeholder={l('搜索 input / session...', 'Search input / session...')}
            className="w-64 rounded-l-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 placeholder:text-gray-400 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-300"
            data-testid="trace-keyword-input"
          />
          <button
            onClick={handleSearch}
            className="rounded-r-md border border-l-0 border-gray-300 bg-gray-100 px-3 py-1.5 text-sm text-gray-700 transition-colors hover:bg-gray-200 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
          >
            {l('搜索', 'Search')}
          </button>
        </div>

        <span className="ml-auto text-sm text-gray-500 dark:text-gray-400">
          {l('共', 'Total:')} {total} {l('条', 'traces')}
        </span>

        <button
          onClick={handleRefresh}
          className="rounded-md border border-gray-300 px-3 py-1.5 text-sm text-gray-700 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
          data-testid="trace-refresh"
        >
          {l('刷新', 'Refresh')}
        </button>
      </div>

      {/* Table */}
      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800">
        <table className="w-full text-sm" data-testid="trace-table">
          <thead>
            <tr className="border-b border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800">
              <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">
                {l('时间', 'Time')}
              </th>
              <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">
                {l('输入', 'Input')}
              </th>
              <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">
                {l('状态', 'Status')}
              </th>
              <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">
                {l('提供商', 'Provider')}
              </th>
              <th className="px-4 py-3 text-right font-medium text-gray-500 dark:text-gray-400">
                {l('耗时', 'Duration')}
              </th>
              <th className="px-4 py-3 text-right font-medium text-gray-500 dark:text-gray-400">
                Tokens
              </th>
              <th className="px-4 py-3 text-right font-medium text-gray-500 dark:text-gray-400">
                {l('成本', 'Cost')}
              </th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan={7} className="px-4 py-12">
                  <div className="flex items-center justify-center">
                    <div className="h-6 w-6 animate-spin rounded-full border-b-2 border-blue-600 dark:border-blue-400" />
                  </div>
                </td>
              </tr>
            ) : traces.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center text-gray-400 dark:text-gray-500">
                  <div>{l('暂无追踪记录', 'No traces found')}</div>
                  <div className="mt-1 text-xs">
                    {l(
                      '只有成功进入 AuraBot LLM 主链路的请求才会生成 trace；纯前端网络失败不会落库。',
                      'Only requests that reached the AuraBot LLM pipeline generate traces; browser-side network failures do not.',
                    )}
                  </div>
                </td>
              </tr>
            ) : (
              traces.map((t) => (
                <tr
                  key={t.traceId}
                  onClick={() => navigate(`/aurabot/traces/${t.traceId}`)}
                  className="cursor-pointer border-b border-gray-100 transition-colors hover:bg-blue-50/50 dark:border-gray-700/50 dark:hover:bg-blue-900/10"
                  data-testid={`trace-row-${t.traceId}`}
                >
                  <td className="px-4 py-3 text-xs whitespace-nowrap text-gray-500 dark:text-gray-400">
                    <div>{new Date(t.startTime).toLocaleDateString()}</div>
                    <div className="text-gray-400 dark:text-gray-500">
                      {new Date(t.startTime).toLocaleTimeString()}
                    </div>
                  </td>
                  <td className="max-w-xs px-4 py-3">
                    <div className="truncate font-medium text-gray-900 dark:text-gray-100">
                      {fmtValue(t.input)}
                    </div>
                    {(t.sessionId || t.name) && (
                      <div className="mt-0.5 truncate text-xs text-gray-400 dark:text-gray-500">
                        {[fmtValue(t.name, ''), t.sessionId ? `session: ${t.sessionId}` : '']
                          .filter(Boolean)
                          .join(' · ')}
                      </div>
                    )}
                    <div className="mt-0.5 truncate font-mono text-[11px] text-gray-400 dark:text-gray-500">
                      {`trace: ${t.traceId}`}
                    </div>
                    {t.output && (
                      <div className="mt-1 line-clamp-2 text-xs text-gray-500 dark:text-gray-400">
                        {fmtValue(t.output)}
                      </div>
                    )}
                    {t.errorMessage && (
                      <div className="mt-1 line-clamp-2 text-xs text-red-600 dark:text-red-400">
                        {fmtValue(t.errorMessage)}
                      </div>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <TraceStatusBadge status={t.status} />
                  </td>
                  <td className="px-4 py-3 font-mono text-xs text-gray-600 dark:text-gray-400">
                    {fmtValue(t.metadata?.provider_code)}
                  </td>
                  <td className="px-4 py-3 text-right font-mono text-gray-600 dark:text-gray-400">
                    {fmtDuration(t.durationMs)}
                  </td>
                  <td className="px-4 py-3 text-right font-mono text-xs text-gray-600 dark:text-gray-400">
                    <span className="text-blue-600 dark:text-blue-400">
                      {fmtTokens(t.totalInputTokens)}
                    </span>
                    {' / '}
                    <span className="text-emerald-600 dark:text-emerald-400">
                      {fmtTokens(t.totalOutputTokens)}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right font-mono text-gray-900 dark:text-white">
                    {t.totalCost > 0 ? fmtCost(Number(t.totalCost)) : '-'}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2">
          <button
            disabled={pageNum <= 1}
            onClick={() => setPageNum((p) => p - 1)}
            className="rounded-md border border-gray-300 px-3 py-1.5 text-sm text-gray-700 transition-colors hover:bg-gray-50 disabled:opacity-40 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
          >
            {l('上一页', 'Prev')}
          </button>
          <span className="text-sm text-gray-600 tabular-nums dark:text-gray-400">
            {pageNum} / {totalPages}
          </span>
          <button
            disabled={pageNum >= totalPages}
            onClick={() => setPageNum((p) => p + 1)}
            className="rounded-md border border-gray-300 px-3 py-1.5 text-sm text-gray-700 transition-colors hover:bg-gray-50 disabled:opacity-40 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
          >
            {l('下一页', 'Next')}
          </button>
        </div>
      )}
    </div>
  );
}

// ============================================================================
// Trace Stats Cards
// ============================================================================

function TraceStatsCards({
  stats,
  l,
}: {
  stats: TraceStats;
  l: (zh: string, en: string) => string;
}) {
  const totalTokens = stats.totalInputTokens + stats.totalOutputTokens;

  return (
    <div className="grid grid-cols-2 gap-4 md:grid-cols-4" data-testid="trace-stats">
      <TraceStatCard
        label={l('总追踪数', 'Total Traces')}
        value={String(stats.totalTraces)}
        sub={`${stats.successCount} ${l('成功', 'success')} / ${stats.errorCount} ${l('失败', 'error')}`}
      />
      <TraceStatCard
        label={l('成功率', 'Success Rate')}
        value={`${stats.successRate.toFixed(1)}%`}
        color={
          stats.successRate >= 90
            ? 'text-green-600 dark:text-green-400'
            : stats.successRate >= 70
              ? 'text-amber-600 dark:text-amber-400'
              : 'text-red-600 dark:text-red-400'
        }
      />
      <TraceStatCard
        label={l('平均延迟', 'Avg Latency')}
        value={fmtDuration(stats.avgDurationMs != null ? Math.round(stats.avgDurationMs) : null)}
      />
      <TraceStatCard
        label={l('总成本', 'Total Cost')}
        value={fmtCost(Number(stats.totalCost))}
        sub={`${fmtTokens(totalTokens)} tokens`}
      />
    </div>
  );
}

function TraceStatCard({
  label,
  value,
  sub,
  color,
}: {
  label: string;
  value: string;
  sub?: string;
  color?: string;
}) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white px-4 py-3 dark:border-gray-700 dark:bg-gray-800">
      <p className="mb-1 text-xs text-gray-500 dark:text-gray-400">{label}</p>
      <p className={`text-xl font-semibold ${color || 'text-gray-900 dark:text-white'}`}>{value}</p>
      {sub && <p className="mt-0.5 text-xs text-gray-400 dark:text-gray-500">{sub}</p>}
    </div>
  );
}
