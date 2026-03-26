/**
 * AI Trace Console — List + Stats
 *
 * Platform admin page for viewing LLM call traces with filtering,
 * statistics, and drill-down to trace detail.
 */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';
import { TraceStatusBadge } from './components/TraceStatusBadge';

// ============================================================================
// Types
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
  metadata: Record<string, any>;
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
// Helpers
// ============================================================================

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

// ============================================================================
// Route meta
// ============================================================================

export function meta() {
  return [
    { title: 'AI Trace Console - AuraBot' },
    {
      name: 'description',
      content: 'View AI/LLM call traces with waterfall timeline',
    },
  ];
}

// ============================================================================
// Main Component
// ============================================================================

export default function TraceListPage() {
  const { locale } = useI18n();
  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);
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
      setTraces(data.records || []);
      setTotal(data.total || 0);
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
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900">
      {/* Header */}
      <div className="border-b border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
        <div className="mx-auto max-w-7xl px-6 py-5">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-gradient-to-br from-violet-500 to-purple-600">
                <span className="text-lg text-white">{'\uD83D\uDD0D'}</span>
              </div>
              <div>
                <h1 className="text-xl font-bold text-gray-900 dark:text-white">
                  {l('AI Trace Console', 'AI Trace Console')}
                </h1>
                <p className="mt-0.5 text-sm text-gray-500 dark:text-gray-400">
                  {l(
                    '查看 LLM 调用追踪、耗时瀑布图和成本分析',
                    'View LLM call traces, waterfall timelines and cost analysis',
                  )}
                </p>
              </div>
            </div>
            <button
              onClick={handleRefresh}
              className="rounded-md border border-gray-300 px-3 py-1.5 text-sm text-gray-700 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
              data-testid="trace-refresh"
            >
              {l('刷新', 'Refresh')}
            </button>
          </div>
        </div>
      </div>

      <div className="mx-auto max-w-7xl space-y-6 px-6 py-6">
        {/* Stats Cards */}
        {stats && <StatsCards stats={stats} l={l} />}

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
                  <td
                    colSpan={7}
                    className="px-4 py-12 text-center text-gray-400 dark:text-gray-500"
                  >
                    {l('暂无追踪记录', 'No traces found')}
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
                      <div className="truncate text-gray-900 dark:text-gray-100">
                        {t.input || '-'}
                      </div>
                      {t.name && (
                        <div className="mt-0.5 truncate text-xs text-gray-400 dark:text-gray-500">
                          {t.name}
                        </div>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <TraceStatusBadge status={t.status} />
                    </td>
                    <td className="px-4 py-3 font-mono text-xs text-gray-600 dark:text-gray-400">
                      {t.metadata?.provider_code || '-'}
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
    </div>
  );
}

// ============================================================================
// Stats Cards
// ============================================================================

function StatsCards({ stats, l }: { stats: TraceStats; l: (zh: string, en: string) => string }) {
  const totalTokens = stats.totalInputTokens + stats.totalOutputTokens;

  return (
    <div className="grid grid-cols-2 gap-4 md:grid-cols-4" data-testid="trace-stats">
      <StatCard
        label={l('总追踪数', 'Total Traces')}
        value={String(stats.totalTraces)}
        sub={`${stats.successCount} ${l('成功', 'success')} / ${stats.errorCount} ${l('失败', 'error')}`}
      />
      <StatCard
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
      <StatCard
        label={l('平均延迟', 'Avg Latency')}
        value={fmtDuration(stats.avgDurationMs != null ? Math.round(stats.avgDurationMs) : null)}
      />
      <StatCard
        label={l('总成本', 'Total Cost')}
        value={fmtCost(Number(stats.totalCost))}
        sub={`${fmtTokens(totalTokens)} tokens`}
      />
    </div>
  );
}

function StatCard({
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
