import { useState, useEffect, useCallback } from 'react';
import {
  CheckCircleIcon,
  XCircleIcon,
  ClockIcon,
  MagnifyingGlassIcon,
  FunnelIcon,
  ChevronDownIcon,
  ChevronUpIcon,
  ArrowPathIcon,
} from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';

// ─── Types ────────────────────────────────────────────────────────────────────

interface CommandAuditLogDTO {
  id: number;
  commandCode: string;
  commandPid?: string;
  userId?: number;
  requestPayload?: string;
  executionResult?: string;
  success: boolean;
  errorMessage?: string;
  executionTimeMs?: number;
  phaseReached: string;
  phaseTimings?: string;
  createdAt: string;
}

interface PaginationResult<T> {
  records: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

// Pipeline phases in order
const PIPELINE_PHASES = [
  'init',
  'load',
  'schema_validate',
  'idempotency_check',
  'entitlement_check',
  'sod_check',
  'state_check',
  'assert',
  'pre_invariant',
  'auto_set',
  'field_map',
  'computed_fields',
  'handler',
  'side_effect',
  'post_action',
  'effect',
  'domain_event',
  'webhook',
  'post_invariant',
  'completed',
];

// ─── Phase Timeline Component ─────────────────────────────────────────────────

function PhaseTimeline({
  phaseReached,
  phaseTimings,
  success,
}: {
  phaseReached: string;
  phaseTimings?: string;
  success: boolean;
}) {
  const timings: Record<string, number> = phaseTimings ? JSON.parse(phaseTimings) : {};
  const reachedIndex = PIPELINE_PHASES.indexOf(phaseReached);

  return (
    <div className="overflow-x-auto py-2">
      <div className="flex min-w-max items-center gap-1">
        {PIPELINE_PHASES.map((phase, idx) => {
          const isReached = idx <= reachedIndex;
          const isFailed = !success && idx === reachedIndex;
          const isCompleted = success && phase === 'completed';
          const durationMs = timings[phase];

          return (
            <div key={phase} className="flex items-center">
              <div className="flex flex-col items-center gap-0.5">
                <div
                  className={`h-3 w-3 flex-shrink-0 rounded-full ${
                    isFailed
                      ? 'bg-red-500'
                      : isCompleted
                        ? 'bg-green-500'
                        : isReached
                          ? 'bg-blue-500'
                          : 'bg-gray-200'
                  }`}
                  title={`${phase}${durationMs !== undefined ? ` (${durationMs}ms)` : ''}`}
                />
                <span className="max-w-[48px] text-center text-[9px] leading-tight whitespace-nowrap text-gray-400">
                  {phase.replace(/_/g, ' ')}
                </span>
                {durationMs !== undefined && (
                  <span className="text-[9px] text-gray-500">{durationMs}ms</span>
                )}
              </div>
              {idx < PIPELINE_PHASES.length - 1 && (
                <div
                  className={`mb-4 h-0.5 w-3 flex-shrink-0 ${
                    idx < reachedIndex ? 'bg-blue-300' : 'bg-gray-200'
                  }`}
                />
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ─── JSON Viewer ─────────────────────────────────────────────────────────────

function JsonViewer({ json, label }: { json?: string; label: string }) {
  if (!json) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch {
    parsed = json;
  }
  return (
    <div>
      <p className="mb-1 text-xs font-medium text-gray-500">{label}</p>
      <pre className="max-h-40 overflow-auto rounded-md bg-gray-50 p-3 font-mono text-xs text-gray-700">
        {JSON.stringify(parsed, null, 2)}
      </pre>
    </div>
  );
}

// ─── Main Page ────────────────────────────────────────────────────────────────

export default function CommandAuditLogsPage() {
  const { locale } = useI18n();
  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const [logs, setLogs] = useState<CommandAuditLogDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [expandedId, setExpandedId] = useState<number | null>(null);

  // Filters
  const [commandCode, setCommandCode] = useState('');
  const [successFilter, setSuccessFilter] = useState<'all' | 'true' | 'false'>('all');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [pageNum, setPageNum] = useState(1);
  const pageSize = 20;

  const fetchLogs = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({
        pageNum: String(pageNum),
        pageSize: String(pageSize),
      });
      if (commandCode.trim()) params.set('commandCode', commandCode.trim());
      if (successFilter !== 'all') params.set('success', successFilter);
      if (startDate) params.set('startDate', startDate + 'T00:00:00Z');
      if (endDate) params.set('endDate', endDate + 'T23:59:59Z');

      const res = await fetch(`/api/meta/commands/audit-logs?${params}`);
      const data = await res.json();
      if (data.code === 0) {
        setLogs(data.data.records ?? []);
        setTotal(data.data.total ?? 0);
      }
    } finally {
      setLoading(false);
    }
  }, [commandCode, successFilter, startDate, endDate, pageNum]);

  useEffect(() => {
    fetchLogs();
  }, [fetchLogs]);

  const totalPages = Math.max(1, Math.ceil(total / pageSize));

  return (
    <div className="space-y-4 p-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">
            {l('命令执行轨迹', 'Command Execution Trace')}
          </h1>
          <p className="mt-0.5 text-sm text-gray-500">
            {l(
              '查看所有命令执行记录及 20 阶段管道耗时',
              'View all command executions and 20-stage pipeline timing',
            )}
          </p>
        </div>
        <button
          onClick={fetchLogs}
          className="flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-50"
        >
          <ArrowPathIcon className="h-4 w-4" />
          {l('刷新', 'Refresh')}
        </button>
      </div>

      {/* Filters */}
      <div className="flex flex-wrap gap-3 rounded-lg bg-gray-50 p-3">
        <div className="flex items-center gap-1.5">
          <MagnifyingGlassIcon className="h-4 w-4 text-gray-400" />
          <input
            className="w-52 rounded border px-2 py-1 text-sm"
            placeholder={l('命令 Code', 'Command code')}
            value={commandCode}
            onChange={(e) => {
              setCommandCode(e.target.value);
              setPageNum(1);
            }}
          />
        </div>
        <div className="flex items-center gap-1.5">
          <FunnelIcon className="h-4 w-4 text-gray-400" />
          <select
            className="rounded border px-2 py-1 text-sm"
            value={successFilter}
            onChange={(e) => {
              setSuccessFilter(e.target.value as typeof successFilter);
              setPageNum(1);
            }}
          >
            <option value="all">{l('全部状态', 'All status')}</option>
            <option value="true">{l('成功', 'Success')}</option>
            <option value="false">{l('失败', 'Failed')}</option>
          </select>
        </div>
        <div className="flex items-center gap-1.5">
          <ClockIcon className="h-4 w-4 text-gray-400" />
          <input
            type="date"
            className="rounded border px-2 py-1 text-sm"
            value={startDate}
            onChange={(e) => {
              setStartDate(e.target.value);
              setPageNum(1);
            }}
          />
          <span className="text-sm text-gray-400">—</span>
          <input
            type="date"
            className="rounded border px-2 py-1 text-sm"
            value={endDate}
            onChange={(e) => {
              setEndDate(e.target.value);
              setPageNum(1);
            }}
          />
        </div>
      </div>

      {/* Stats bar */}
      <div className="flex items-center gap-4 text-sm text-gray-500">
        <span>
          {l('共', 'Total')} <span className="font-medium text-gray-900">{total}</span>{' '}
          {l('条记录', 'records')}
        </span>
      </div>

      {/* Log list */}
      {loading ? (
        <div className="py-12 text-center text-gray-400">{l('加载中...', 'Loading...')}</div>
      ) : logs.length === 0 ? (
        <div className="py-12 text-center text-gray-400">{l('暂无数据', 'No data')}</div>
      ) : (
        <div className="space-y-2">
          {logs.map((log) => (
            <div key={log.id} className="overflow-hidden rounded-lg border bg-white">
              {/* Row header */}
              <button
                className="flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-gray-50"
                onClick={() => setExpandedId(expandedId === log.id ? null : log.id)}
              >
                {log.success ? (
                  <CheckCircleIcon className="h-5 w-5 flex-shrink-0 text-green-500" />
                ) : (
                  <XCircleIcon className="h-5 w-5 flex-shrink-0 text-red-500" />
                )}
                <span className="w-72 truncate font-mono text-sm font-medium text-gray-900">
                  {log.commandCode}
                </span>
                <span
                  className={`inline-flex flex-shrink-0 items-center rounded px-2 py-0.5 text-xs font-medium ${
                    log.success ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                  }`}
                >
                  {log.phaseReached}
                </span>
                {log.executionTimeMs !== undefined && (
                  <span className="flex-shrink-0 text-xs text-gray-400">
                    {log.executionTimeMs}ms
                  </span>
                )}
                <span className="ml-auto flex-shrink-0 text-xs text-gray-400">
                  {new Date(log.createdAt).toLocaleString(locale === 'zh-CN' ? 'zh-CN' : 'en-US')}
                </span>
                {expandedId === log.id ? (
                  <ChevronUpIcon className="h-4 w-4 flex-shrink-0 text-gray-400" />
                ) : (
                  <ChevronDownIcon className="h-4 w-4 flex-shrink-0 text-gray-400" />
                )}
              </button>

              {/* Expanded detail */}
              {expandedId === log.id && (
                <div className="space-y-4 border-t bg-gray-50/50 px-4 py-4">
                  {/* Phase timeline */}
                  <div>
                    <p className="mb-1 text-xs font-medium text-gray-500">
                      {l('执行阶段时间线', 'Phase Timeline')}
                    </p>
                    <PhaseTimeline
                      phaseReached={log.phaseReached}
                      phaseTimings={log.phaseTimings}
                      success={log.success}
                    />
                  </div>

                  {/* Error message */}
                  {log.errorMessage && (
                    <div className="rounded border border-red-200 bg-red-50 p-3">
                      <p className="mb-1 text-xs font-medium text-red-700">
                        {l('错误信息', 'Error')}
                      </p>
                      <p className="font-mono text-sm text-red-600">{log.errorMessage}</p>
                    </div>
                  )}

                  {/* Payload & Result */}
                  <div className="grid grid-cols-2 gap-4">
                    <JsonViewer
                      json={log.requestPayload}
                      label={l('请求 Payload', 'Request Payload')}
                    />
                    <JsonViewer
                      json={log.executionResult}
                      label={l('执行结果', 'Execution Result')}
                    />
                  </div>

                  {/* Meta */}
                  <div className="flex gap-6 text-xs text-gray-400">
                    <span>ID: {log.id}</span>
                    {log.userId && (
                      <span>
                        {l('用户', 'User')}: {log.userId}
                      </span>
                    )}
                    {log.commandPid && <span>PID: {log.commandPid}</span>}
                  </div>
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 pt-2">
          <button
            disabled={pageNum <= 1}
            onClick={() => setPageNum((p) => p - 1)}
            className="rounded border px-3 py-1 text-sm hover:bg-gray-50 disabled:opacity-40"
          >
            {l('上一页', 'Prev')}
          </button>
          <span className="text-sm text-gray-500">
            {pageNum} / {totalPages}
          </span>
          <button
            disabled={pageNum >= totalPages}
            onClick={() => setPageNum((p) => p + 1)}
            className="rounded border px-3 py-1 text-sm hover:bg-gray-50 disabled:opacity-40"
          >
            {l('下一页', 'Next')}
          </button>
        </div>
      )}
    </div>
  );
}
