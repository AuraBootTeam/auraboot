/**
 * AI Trace Detail — Waterfall Timeline + Span Inspector + Cost Breakdown
 *
 * Shows a single trace with all its spans in a tree-based waterfall view,
 * a span detail inspector panel, and cost/token breakdown charts.
 */

import { useState, useEffect, useMemo, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';
import { SpanTreeTimeline } from './components/SpanTreeTimeline';
import { SpanDetailPanel } from './components/SpanDetailPanel';
import { TraceStatusBadge } from './components/TraceStatusBadge';
import { JsonViewer } from './components/JsonViewer';

// ============================================================================
// Types
// ============================================================================

interface TraceData {
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

interface SpanData {
  spanId: string;
  parentSpanId: string | null;
  type: string;
  name: string;
  status: string;
  level: string | null;
  statusMessage: string | null;
  startTime: string;
  endTime: string | null;
  durationMs: number | null;
  model: string | null;
  modelParameters: Record<string, any> | null;
  inputTokens: number | null;
  outputTokens: number | null;
  cost: number | null;
  stopReason: string | null;
  completionStartTime: string | null;
  input: any;
  output: any;
  toolDefinitions: any;
  toolCalls: any;
  sequenceOrder: number;
  metadata: Record<string, any> | null;
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

// ============================================================================
// Route meta
// ============================================================================

export function meta() {
  return [
    { title: 'Trace Detail - AuraBot' },
    {
      name: 'description',
      content: 'AI trace detail with waterfall timeline',
    },
  ];
}

// ============================================================================
// Main Component
// ============================================================================

type DetailTab = 'timeline' | 'cost' | 'io';

export default function TraceDetailPage() {
  const { traceId } = useParams();
  const navigate = useNavigate();
  const { locale } = useI18n();
  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const [trace, setTrace] = useState<TraceData | null>(null);
  const [spans, setSpans] = useState<SpanData[]>([]);
  const [selectedSpanId, setSelectedSpanId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<DetailTab>('timeline');

  useEffect(() => {
    async function load() {
      setLoading(true);
      setError(null);
      try {
        const res = await fetch(`/api/ai/traces/${traceId}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        setTrace(data.trace);
        setSpans(data.spans || []);
        if (data.spans?.length > 0) {
          setSelectedSpanId(data.spans[0].spanId);
        }
      } catch (e: any) {
        setError(e.message || 'Failed to load trace');
      } finally {
        setLoading(false);
      }
    }
    load();
  }, [traceId]);

  if (loading) {
    return (
      <div className="flex min-h-[400px] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600 dark:border-blue-400" />
      </div>
    );
  }

  if (error || !trace) {
    return (
      <div className="mx-auto max-w-7xl p-6">
        <button
          onClick={() => navigate('/aurabot/traces')}
          className="mb-4 text-sm text-blue-600 hover:underline dark:text-blue-400"
        >
          {'\u2190'} {l('返回列表', 'Back to list')}
        </button>
        <div className="rounded-lg border border-red-200 bg-red-50 p-6 text-center dark:border-red-800 dark:bg-red-900/20">
          <p className="text-red-700 dark:text-red-300">
            {error || l('追踪记录未找到', 'Trace not found')}
          </p>
        </div>
      </div>
    );
  }

  const selectedSpan = spans.find((s: SpanData) => s.spanId === selectedSpanId);

  const tabs: { key: DetailTab; label: string }[] = [
    { key: 'timeline', label: l('瀑布图', 'Timeline') },
    { key: 'cost', label: l('成本分析', 'Cost Analysis') },
    { key: 'io', label: l('输入/输出', 'Input/Output') },
  ];

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900">
      {/* Header */}
      <div className="border-b border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
        <div className="mx-auto max-w-7xl px-6 py-4">
          <button
            onClick={() => navigate('/aurabot/traces')}
            className="mb-3 inline-block text-sm text-blue-600 hover:underline dark:text-blue-400"
          >
            {'\u2190'} {l('返回列表', 'Back to list')}
          </button>

          <div className="flex items-start justify-between">
            <div>
              <div className="mb-2 flex items-center gap-3">
                <h1 className="text-lg font-bold text-gray-900 dark:text-white">
                  {trace.name || l('追踪详情', 'Trace Detail')}
                </h1>
                <TraceStatusBadge status={trace.status} />
              </div>
              <div className="flex flex-wrap items-center gap-4 text-sm text-gray-600 dark:text-gray-400">
                <span className="font-mono text-xs">{trace.traceId.slice(0, 16)}...</span>
                <span>
                  {l('耗时', 'Duration')}: {fmtDuration(trace.durationMs)}
                </span>
                <span>
                  Tokens:{' '}
                  <span className="text-blue-600 dark:text-blue-400">
                    {fmtTokens(trace.totalInputTokens)}
                  </span>
                  {' / '}
                  <span className="text-emerald-600 dark:text-emerald-400">
                    {fmtTokens(trace.totalOutputTokens)}
                  </span>
                </span>
                {trace.totalCost > 0 && (
                  <span className="font-medium text-gray-900 dark:text-white">
                    {fmtCost(Number(trace.totalCost))}
                  </span>
                )}
                {trace.metadata?.provider_code && (
                  <span className="rounded bg-gray-100 px-1.5 py-0.5 font-mono text-xs dark:bg-gray-700">
                    {trace.metadata.provider_code}
                  </span>
                )}
                <span className="text-xs text-gray-400">
                  {new Date(trace.startTime).toLocaleString()}
                </span>
              </div>
            </div>

            <div className="text-sm text-gray-500 dark:text-gray-400">
              {spans.length} {l('个 Span', 'spans')}
            </div>
          </div>

          {/* Error message */}
          {trace.errorMessage && (
            <div className="mt-3 rounded-lg border border-red-200 bg-red-50 p-3 dark:border-red-800 dark:bg-red-900/20">
              <p className="mb-1 text-xs font-medium text-red-600 dark:text-red-400">
                {l('错误信息', 'Error')}
              </p>
              <pre className="font-mono text-sm break-words whitespace-pre-wrap text-red-800 dark:text-red-300">
                {trace.errorMessage}
              </pre>
            </div>
          )}
        </div>
      </div>

      {/* Tab bar */}
      <div className="border-b border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
        <div className="mx-auto max-w-7xl px-6">
          <div className="flex gap-0">
            {tabs.map((tab) => (
              <button
                key={tab.key}
                onClick={() => setActiveTab(tab.key)}
                className={`border-b-2 px-4 py-3 text-sm font-medium transition-colors ${
                  activeTab === tab.key
                    ? 'border-blue-600 text-blue-600 dark:border-blue-400 dark:text-blue-400'
                    : 'border-transparent text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300'
                }`}
                data-testid={`trace-tab-${tab.key}`}
              >
                {tab.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="mx-auto max-w-7xl px-6 py-6">
        {activeTab === 'timeline' && (
          <TimelineView
            trace={trace}
            spans={spans}
            selectedSpanId={selectedSpanId}
            selectedSpan={selectedSpan || null}
            onSelectSpan={setSelectedSpanId}
          />
        )}
        {activeTab === 'cost' && <CostBreakdownView spans={spans} l={l} />}
        {activeTab === 'io' && <IOView trace={trace} l={l} />}
      </div>
    </div>
  );
}

// ============================================================================
// Timeline View (waterfall + span detail)
// ============================================================================

function TimelineView({
  trace,
  spans,
  selectedSpanId,
  selectedSpan,
  onSelectSpan,
}: {
  trace: TraceData;
  spans: SpanData[];
  selectedSpanId: string | null;
  selectedSpan: SpanData | null;
  onSelectSpan: (id: string) => void;
}) {
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-5" style={{ minHeight: '500px' }}>
      <div className="lg:col-span-3">
        <SpanTreeTimeline
          spans={spans}
          totalDurationMs={trace.durationMs || 1}
          traceStartTime={trace.startTime}
          selectedSpanId={selectedSpanId}
          onSelectSpan={onSelectSpan}
        />
      </div>

      <div className="lg:col-span-2">
        {selectedSpan ? (
          <SpanDetailPanel span={selectedSpan} />
        ) : (
          <div className="rounded-lg border border-gray-200 p-8 text-center text-gray-400 dark:border-gray-700 dark:text-gray-500">
            Select a span to view details
          </div>
        )}
      </div>
    </div>
  );
}

// ============================================================================
// Cost Breakdown View
// ============================================================================

function CostBreakdownView({
  spans,
  l,
}: {
  spans: SpanData[];
  l: (zh: string, en: string) => string;
}) {
  const breakdown = useMemo(() => {
    const byType = new Map<
      string,
      {
        type: string;
        count: number;
        cost: number;
        inputTokens: number;
        outputTokens: number;
        totalDurationMs: number;
      }
    >();

    for (const span of spans) {
      const existing = byType.get(span.type) || {
        type: span.type,
        count: 0,
        cost: 0,
        inputTokens: 0,
        outputTokens: 0,
        totalDurationMs: 0,
      };
      existing.count += 1;
      existing.cost += Number(span.cost) || 0;
      existing.inputTokens += span.inputTokens || 0;
      existing.outputTokens += span.outputTokens || 0;
      existing.totalDurationMs += span.durationMs || 0;
      byType.set(span.type, existing);
    }

    return Array.from(byType.values()).sort((a, b) => b.cost - a.cost);
  }, [spans]);

  const modelBreakdown = useMemo(() => {
    const byModel = new Map<
      string,
      {
        model: string;
        count: number;
        cost: number;
        inputTokens: number;
        outputTokens: number;
      }
    >();

    for (const span of spans) {
      if (!span.model) continue;
      const existing = byModel.get(span.model) || {
        model: span.model,
        count: 0,
        cost: 0,
        inputTokens: 0,
        outputTokens: 0,
      };
      existing.count += 1;
      existing.cost += Number(span.cost) || 0;
      existing.inputTokens += span.inputTokens || 0;
      existing.outputTokens += span.outputTokens || 0;
      byModel.set(span.model, existing);
    }

    return Array.from(byModel.values()).sort((a, b) => b.cost - a.cost);
  }, [spans]);

  const totalCost = breakdown.reduce((s, b) => s + b.cost, 0);
  const totalInputTokens = breakdown.reduce((s, b) => s + b.inputTokens, 0);
  const totalOutputTokens = breakdown.reduce((s, b) => s + b.outputTokens, 0);

  const TYPE_COLORS: Record<string, string> = {
    GENERATION: 'bg-blue-500',
    TOOL: 'bg-emerald-500',
    SPAN: 'bg-purple-500',
    EVENT: 'bg-amber-500',
  };

  return (
    <div className="space-y-6">
      {/* Cost by Span Type */}
      <div className="rounded-lg border border-gray-200 bg-white p-5 dark:border-gray-700 dark:bg-gray-800">
        <h3 className="mb-4 text-sm font-semibold text-gray-900 dark:text-white">
          {l('按 Span 类型分布', 'Cost by Span Type')}
        </h3>

        {/* Visual bar */}
        {totalCost > 0 && (
          <div className="mb-4 flex h-6 overflow-hidden rounded-full">
            {breakdown.map((b) => {
              const pct = (b.cost / totalCost) * 100;
              if (pct < 0.5) return null;
              return (
                <div
                  key={b.type}
                  className={`${TYPE_COLORS[b.type] || 'bg-gray-400'} group relative`}
                  style={{ width: `${pct}%` }}
                  title={`${b.type}: ${fmtCost(b.cost)} (${pct.toFixed(1)}%)`}
                >
                  {pct > 12 && (
                    <span className="absolute inset-0 flex items-center justify-center text-[10px] font-medium text-white">
                      {b.type} {pct.toFixed(0)}%
                    </span>
                  )}
                </div>
              );
            })}
          </div>
        )}

        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-200 text-left text-gray-500 dark:border-gray-700 dark:text-gray-400">
              <th className="py-2 pr-4 font-medium">{l('类型', 'Type')}</th>
              <th className="py-2 pr-4 text-right font-medium">{l('调用次数', 'Calls')}</th>
              <th className="py-2 pr-4 text-right font-medium">{l('输入 Tokens', 'In Tokens')}</th>
              <th className="py-2 pr-4 text-right font-medium">{l('输出 Tokens', 'Out Tokens')}</th>
              <th className="py-2 pr-4 text-right font-medium">{l('耗时', 'Duration')}</th>
              <th className="py-2 text-right font-medium">{l('成本', 'Cost')}</th>
            </tr>
          </thead>
          <tbody>
            {breakdown.map((b) => (
              <tr key={b.type} className="border-b border-gray-100 dark:border-gray-700/50">
                <td className="py-2 pr-4">
                  <span className="flex items-center gap-2">
                    <span
                      className={`h-2.5 w-2.5 rounded-sm ${TYPE_COLORS[b.type] || 'bg-gray-400'}`}
                    />
                    <span className="font-medium text-gray-900 dark:text-white">{b.type}</span>
                  </span>
                </td>
                <td className="py-2 pr-4 text-right text-gray-600 dark:text-gray-400">{b.count}</td>
                <td className="py-2 pr-4 text-right font-mono text-gray-600 dark:text-gray-400">
                  {fmtTokens(b.inputTokens)}
                </td>
                <td className="py-2 pr-4 text-right font-mono text-gray-600 dark:text-gray-400">
                  {fmtTokens(b.outputTokens)}
                </td>
                <td className="py-2 pr-4 text-right font-mono text-gray-600 dark:text-gray-400">
                  {fmtDuration(b.totalDurationMs)}
                </td>
                <td className="py-2 text-right font-mono font-medium text-gray-900 dark:text-white">
                  {fmtCost(b.cost)}
                </td>
              </tr>
            ))}
            <tr className="font-medium">
              <td className="py-2 pr-4 text-gray-900 dark:text-white">{l('合计', 'Total')}</td>
              <td className="py-2 pr-4 text-right text-gray-600 dark:text-gray-400">
                {breakdown.reduce((s, b) => s + b.count, 0)}
              </td>
              <td className="py-2 pr-4 text-right font-mono text-gray-600 dark:text-gray-400">
                {fmtTokens(totalInputTokens)}
              </td>
              <td className="py-2 pr-4 text-right font-mono text-gray-600 dark:text-gray-400">
                {fmtTokens(totalOutputTokens)}
              </td>
              <td className="py-2 pr-4 text-right font-mono text-gray-600 dark:text-gray-400">
                {fmtDuration(breakdown.reduce((s, b) => s + b.totalDurationMs, 0))}
              </td>
              <td className="py-2 text-right font-mono text-gray-900 dark:text-white">
                {fmtCost(totalCost)}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      {/* Cost by Model */}
      {modelBreakdown.length > 0 && (
        <div className="rounded-lg border border-gray-200 bg-white p-5 dark:border-gray-700 dark:bg-gray-800">
          <h3 className="mb-4 text-sm font-semibold text-gray-900 dark:text-white">
            {l('按模型分布', 'Cost by Model')}
          </h3>
          <div className="space-y-3">
            {modelBreakdown.map((m) => {
              const pct = totalCost > 0 ? (m.cost / totalCost) * 100 : 0;
              return (
                <div key={m.model} className="flex items-center gap-3">
                  <span
                    className="w-36 truncate font-mono text-sm text-gray-700 dark:text-gray-300"
                    title={m.model}
                  >
                    {m.model}
                  </span>
                  <div className="relative h-4 flex-1 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-700">
                    <div
                      className="h-full rounded-full bg-indigo-500 transition-all dark:bg-indigo-400"
                      style={{ width: `${Math.max(pct, 2)}%` }}
                    />
                  </div>
                  <span className="w-16 text-right font-mono text-sm text-gray-900 dark:text-white">
                    {fmtCost(m.cost)}
                  </span>
                  <span className="w-14 text-right text-xs text-gray-500 dark:text-gray-400">
                    {m.count} {l('次', 'calls')}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Token Usage: Input vs Output */}
      <div className="rounded-lg border border-gray-200 bg-white p-5 dark:border-gray-700 dark:bg-gray-800">
        <h3 className="mb-4 text-sm font-semibold text-gray-900 dark:text-white">
          {l('Token 使用量', 'Token Usage')}
        </h3>
        <div className="flex items-center gap-4">
          <div className="flex-1">
            <div className="mb-1 flex justify-between text-xs text-gray-500 dark:text-gray-400">
              <span>
                {l('输入', 'Input')}: {fmtTokens(totalInputTokens)}
              </span>
              <span>
                {l('输出', 'Output')}: {fmtTokens(totalOutputTokens)}
              </span>
            </div>
            <div className="flex h-5 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-700">
              {totalInputTokens + totalOutputTokens > 0 && (
                <>
                  <div
                    className="bg-blue-500 dark:bg-blue-400"
                    style={{
                      width: `${(totalInputTokens / (totalInputTokens + totalOutputTokens)) * 100}%`,
                    }}
                  />
                  <div
                    className="bg-emerald-500 dark:bg-emerald-400"
                    style={{
                      width: `${(totalOutputTokens / (totalInputTokens + totalOutputTokens)) * 100}%`,
                    }}
                  />
                </>
              )}
            </div>
            <div className="mt-2 flex items-center gap-4 text-xs text-gray-500 dark:text-gray-400">
              <span className="flex items-center gap-1">
                <span className="h-2 w-2 rounded-sm bg-blue-500" />
                {l('输入', 'Input')}
              </span>
              <span className="flex items-center gap-1">
                <span className="h-2 w-2 rounded-sm bg-emerald-500" />
                {l('输出', 'Output')}
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

// ============================================================================
// I/O View
// ============================================================================

function IOView({ trace, l }: { trace: TraceData; l: (zh: string, en: string) => string }) {
  return (
    <div className="space-y-6">
      <div className="rounded-lg border border-gray-200 bg-white p-5 dark:border-gray-700 dark:bg-gray-800">
        <h3 className="mb-3 text-sm font-semibold text-gray-900 dark:text-white">
          {l('输入', 'Input')}
        </h3>
        <JsonViewer data={trace.input} maxHeight="300px" />
      </div>

      <div className="rounded-lg border border-gray-200 bg-white p-5 dark:border-gray-700 dark:bg-gray-800">
        <h3 className="mb-3 text-sm font-semibold text-gray-900 dark:text-white">
          {l('输出', 'Output')}
        </h3>
        <JsonViewer data={trace.output} maxHeight="300px" />
      </div>

      {trace.metadata && Object.keys(trace.metadata).length > 0 && (
        <div className="rounded-lg border border-gray-200 bg-white p-5 dark:border-gray-700 dark:bg-gray-800">
          <h3 className="mb-3 text-sm font-semibold text-gray-900 dark:text-white">Metadata</h3>
          <JsonViewer data={trace.metadata} maxHeight="200px" />
        </div>
      )}

      {trace.tags && trace.tags.length > 0 && (
        <div className="rounded-lg border border-gray-200 bg-white p-5 dark:border-gray-700 dark:bg-gray-800">
          <h3 className="mb-3 text-sm font-semibold text-gray-900 dark:text-white">Tags</h3>
          <div className="flex flex-wrap gap-2">
            {trace.tags.map((tag, i) => (
              <span
                key={i}
                className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-700 dark:bg-gray-700 dark:text-gray-300"
              >
                {tag}
              </span>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
