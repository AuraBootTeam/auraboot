/**
 * Runtime Metrics Snapshot
 *
 * A point-in-time view of the backend runtime — JVM heap / uptime, HTTP latency
 * (mean / p99 / max), active sessions and business counters (commands, LLM
 * requests / tokens). Fills the gap where /api/observability/snapshot had no UI.
 *
 * For middleware connectivity (Postgres / Redis / MQ / storage) see the
 * Infrastructure page (/admin/infrastructure); this page links to it.
 *
 * Backed by GET /api/observability/snapshot (ApiResponse, tenant-agnostic runtime metrics).
 */

import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';

interface Snapshot {
  jvm: {
    heapUsedMb: number;
    heapMaxMb: number;
    nonHeapUsedMb: number;
    uptimeSeconds: number;
    availableProcessors: number;
  };
  activeSessions: number;
  http: {
    totalRequests: number;
    meanLatencyMs: number;
    p99LatencyMs: number;
    maxLatencyMs: number;
  };
  business: {
    commandExecutions: number;
    pluginInstalls: number;
    llmRequests: number;
    llmTokensTotal: number;
  };
  endpoints: Record<string, string>;
}

function fmtUptime(seconds: number): string {
  if (!seconds || seconds < 0) return '—';
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

export default function RuntimeMetricsPage() {
  const { locale } = useI18n();
  const l = useCallback(
    (zh: string, en: string) => (locale === 'zh-CN' ? zh : en),
    [locale],
  );
  const navigate = useNavigate();

  const [snap, setSnap] = useState<Snapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [fetchedAt, setFetchedAt] = useState<string>('');

  const fetchSnap = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch('/api/observability/snapshot');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const json = await res.json();
      setSnap((json?.data ?? json) as Snapshot);
      setFetchedAt(new Date().toLocaleTimeString());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setSnap(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchSnap();
  }, [fetchSnap]);

  const heapPct =
    snap && snap.jvm.heapMaxMb > 0
      ? Math.round((snap.jvm.heapUsedMb / snap.jvm.heapMaxMb) * 100)
      : 0;

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900" data-testid="runtime-metrics-page">
      {/* Header */}
      <div className="border-b border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
        <div className="mx-auto max-w-7xl px-6 py-5">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-xl font-semibold text-gray-900 dark:text-gray-100">
                {l('运行时指标', 'Runtime Metrics')}
              </h1>
              <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
                {l('后端 JVM / HTTP / 业务计数快照。', 'A snapshot of backend JVM / HTTP / business counters.')}
                {fetchedAt && <span className="ml-2 text-gray-400">{l('采集于', 'at')} {fetchedAt}</span>}
              </p>
            </div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => navigate('/admin/infrastructure')}
                className="rounded-md border border-gray-300 px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-200 dark:hover:bg-gray-700"
              >
                {l('基础设施健康 →', 'Infrastructure →')}
              </button>
              <button
                type="button"
                onClick={fetchSnap}
                data-testid="runtime-refresh-btn"
                className="rounded-md bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700"
              >
                {l('刷新', 'Refresh')}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Body */}
      <div className="mx-auto max-w-7xl px-6 py-6">
        {error && (
          <div
            data-testid="runtime-error"
            className="mb-4 rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-900/40 dark:bg-red-900/20 dark:text-red-300"
          >
            {l('加载失败:', 'Failed to load: ')}{error}
          </div>
        )}

        {loading && !snap ? (
          <div className="rounded-md border border-gray-200 bg-white px-6 py-12 text-center text-sm text-gray-400 dark:border-gray-700 dark:bg-gray-800">
            {l('加载中…', 'Loading…')}
          </div>
        ) : snap ? (
          <div className="space-y-6">
            {/* JVM */}
            <MetricGroup title={l('JVM', 'JVM')} testid="group-jvm">
              <MetricCard label={l('堆内存', 'Heap')} value={`${snap.jvm.heapUsedMb} / ${snap.jvm.heapMaxMb} MB`} sub={`${heapPct}%`} accent={heapPct >= 85 ? 'red' : heapPct >= 70 ? 'amber' : 'green'} testid="metric-heap" />
              <MetricCard label={l('非堆', 'Non-heap')} value={`${snap.jvm.nonHeapUsedMb} MB`} testid="metric-nonheap" />
              <MetricCard label={l('运行时长', 'Uptime')} value={fmtUptime(snap.jvm.uptimeSeconds)} testid="metric-uptime" />
              <MetricCard label={l('CPU 核', 'CPU cores')} value={String(snap.jvm.availableProcessors)} testid="metric-cpu" />
              <MetricCard label={l('活跃会话', 'Active sessions')} value={String(snap.activeSessions)} testid="metric-sessions" />
            </MetricGroup>

            {/* HTTP */}
            <MetricGroup title={l('HTTP 请求', 'HTTP Requests')} testid="group-http">
              <MetricCard label={l('总请求数', 'Total requests')} value={snap.http.totalRequests.toLocaleString()} testid="metric-http-total" />
              <MetricCard label={l('平均延迟', 'Mean latency')} value={`${snap.http.meanLatencyMs} ms`} testid="metric-http-mean" />
              <MetricCard label="p99" value={`${snap.http.p99LatencyMs} ms`} accent={snap.http.p99LatencyMs >= 1000 ? 'amber' : 'green'} testid="metric-http-p99" />
              <MetricCard label={l('最大延迟', 'Max latency')} value={`${snap.http.maxLatencyMs} ms`} testid="metric-http-max" />
            </MetricGroup>

            {/* Business */}
            <MetricGroup title={l('业务计数', 'Business Counters')} testid="group-business">
              <MetricCard label={l('命令执行', 'Command executions')} value={snap.business.commandExecutions.toLocaleString()} testid="metric-commands" />
              <MetricCard label={l('插件安装', 'Plugin installs')} value={snap.business.pluginInstalls.toLocaleString()} testid="metric-plugins" />
              <MetricCard label={l('LLM 请求', 'LLM requests')} value={snap.business.llmRequests.toLocaleString()} testid="metric-llm-req" />
              <MetricCard label={l('LLM tokens', 'LLM tokens')} value={snap.business.llmTokensTotal.toLocaleString()} testid="metric-llm-tokens" />
            </MetricGroup>

            {/* Endpoints */}
            {snap.endpoints && Object.keys(snap.endpoints).length > 0 && (
              <div className="rounded-md border border-gray-200 bg-white px-4 py-3 dark:border-gray-700 dark:bg-gray-800" data-testid="group-endpoints">
                <h2 className="mb-2 text-sm font-semibold text-gray-900 dark:text-gray-100">{l('监控端点', 'Monitoring Endpoints')}</h2>
                <ul className="space-y-1 text-sm">
                  {Object.entries(snap.endpoints).map(([k, v]) => (
                    <li key={k} className="flex gap-2">
                      <span className="text-gray-500 dark:text-gray-400">{k}</span>
                      <span className="font-mono text-gray-700 dark:text-gray-300">{v}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        ) : null}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Presentational helpers
// ---------------------------------------------------------------------------

const ACCENT: Record<string, string> = {
  green: 'text-green-600 dark:text-green-400',
  amber: 'text-amber-600 dark:text-amber-400',
  red: 'text-red-600 dark:text-red-400',
};

function MetricGroup({ title, testid, children }: { title: string; testid: string; children: React.ReactNode }) {
  return (
    <div data-testid={testid}>
      <h2 className="mb-2 text-sm font-semibold text-gray-700 dark:text-gray-300">{title}</h2>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">{children}</div>
    </div>
  );
}

function MetricCard({
  label,
  value,
  sub,
  accent,
  testid,
}: {
  label: string;
  value: string;
  sub?: string;
  accent?: 'green' | 'amber' | 'red';
  testid: string;
}) {
  return (
    <div className="rounded-md border border-gray-200 bg-white px-4 py-3 dark:border-gray-700 dark:bg-gray-800" data-testid={testid}>
      <div className="text-xs text-gray-500 dark:text-gray-400">{label}</div>
      <div className={`mt-1 text-lg font-semibold ${accent ? ACCENT[accent] : 'text-gray-900 dark:text-gray-100'}`}>
        {value}
        {sub && <span className="ml-1 text-xs font-normal text-gray-400">{sub}</span>}
      </div>
    </div>
  );
}
