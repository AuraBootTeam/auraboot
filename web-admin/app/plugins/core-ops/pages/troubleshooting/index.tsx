/**
 * Eagle-eye Troubleshooting Console
 *
 * Paste one distributed trace id and see everything correlated to it across
 * domains — command pipeline executions (phase timings + error), LLM cost,
 * behavior events and admin audit events — plus a jump to the AI span tree.
 *
 * Backed by GET /api/observability/correlation/{traceId} (tenant-scoped).
 * The trace id is stamped on every log line ([traceId,spanId]) and returned in
 * the X-Trace-Id response header, so it is easy to grab from a failing request.
 */

import { useCallback, useEffect, useState } from 'react';
import { useSearchParams, useNavigate } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';

// ---------------------------------------------------------------------------
// Types (mirror CorrelationView + its domain entities, serialized raw)
// ---------------------------------------------------------------------------

interface CommandAudit {
  id: number;
  commandCode: string;
  success: boolean;
  errorMessage: string | null;
  executionTimeMs: number | null;
  phaseReached: string | null;
  phaseTimings: string | null;
  traceId: string | null;
  spanId: string | null;
  createdAt: string;
}

interface LlmUsage {
  provider: string | null;
  requestModel: string | null;
  responseModel: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  amount: number | null;
  currency: string | null;
  createdAt: string | null;
}

interface BehaviorEvent {
  eventName: string | null;
  eventCategory: string | null;
  source: string | null;
  pageId: string | null;
  elementCode: string | null;
  occurredAt: string | null;
}

interface AuditEvent {
  actionType: string | null;
  resourceType: string | null;
  success: boolean | null;
  reason: string | null;
  actorType: string | null;
  createdAt: string | null;
}

interface CorrelationView {
  traceId: string;
  commandAudits: CommandAudit[];
  llmUsage: LlmUsage[];
  behaviorEvents: BehaviorEvent[];
  auditEvents: AuditEvent[];
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function parsePhaseTimings(json: string | null): Array<[string, number]> {
  if (!json) return [];
  try {
    const obj = JSON.parse(json) as Record<string, number>;
    return Object.entries(obj).map(([k, v]) => [k, Number(v)] as [string, number]);
  } catch {
    return [];
  }
}

function fmtTime(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

function fmtCost(n: number | null, currency: string | null): string {
  if (n == null) return '—';
  const cur = currency || 'USD';
  return `${n < 1 ? n.toFixed(4) : n.toFixed(2)} ${cur}`;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function TroubleshootingPage() {
  const { locale } = useI18n();
  const l = useCallback(
    (zh: string, en: string) => (locale === 'zh-CN' ? zh : en),
    [locale],
  );
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  const [input, setInput] = useState(searchParams.get('traceId') || '');
  const [view, setView] = useState<CorrelationView | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [queried, setQueried] = useState(false);

  const runQuery = useCallback(async (traceId: string) => {
    const tid = traceId.trim();
    if (!tid) return;
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/observability/correlation/${encodeURIComponent(tid)}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = (await res.json()) as CorrelationView;
      setView({
        traceId: data.traceId ?? tid,
        commandAudits: data.commandAudits ?? [],
        llmUsage: data.llmUsage ?? [],
        behaviorEvents: data.behaviorEvents ?? [],
        auditEvents: data.auditEvents ?? [],
      });
      setQueried(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setView(null);
      setQueried(true);
    } finally {
      setLoading(false);
    }
  }, []);

  // Auto-run when arriving with ?traceId= (e.g. from the error board).
  useEffect(() => {
    const tid = searchParams.get('traceId');
    if (tid) {
      setInput(tid);
      runQuery(tid);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSubmit = useCallback(() => {
    const tid = input.trim();
    if (!tid) return;
    setSearchParams(tid ? { traceId: tid } : {});
    runQuery(tid);
  }, [input, runQuery, setSearchParams]);

  const totalCorrelated =
    (view?.commandAudits.length ?? 0) +
    (view?.llmUsage.length ?? 0) +
    (view?.behaviorEvents.length ?? 0) +
    (view?.auditEvents.length ?? 0);

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900" data-testid="troubleshooting-page">
      {/* Header */}
      <div className="border-b border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
        <div className="mx-auto max-w-7xl px-6 py-5">
          <h1 className="text-xl font-semibold text-gray-900 dark:text-gray-100">
            {l('鹰眼排障台', 'Eagle-eye Troubleshooting')}
          </h1>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
            {l(
              '粘贴一个 traceId(报错提示、X-Trace-Id 响应头或日志里都能拿到),关联查看命令执行、LLM 成本、行为与审计事件。',
              'Paste a traceId (from an error toast, the X-Trace-Id header, or logs) to correlate command executions, LLM cost, behavior and audit events.',
            )}
          </p>

          {/* Search bar */}
          <div className="mt-4 flex gap-2">
            <input
              type="text"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') handleSubmit(); }}
              placeholder={l('输入 traceId,例如 3a1f...', 'Enter traceId, e.g. 3a1f...')}
              data-testid="trace-id-input"
              className="w-full max-w-xl rounded-md border border-gray-300 bg-white px-3 py-2 font-mono text-sm text-gray-900 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100"
            />
            <button
              type="button"
              onClick={handleSubmit}
              disabled={loading || !input.trim()}
              data-testid="trace-query-btn"
              className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {loading ? l('查询中…', 'Querying…') : l('查询', 'Query')}
            </button>
          </div>
        </div>
      </div>

      {/* Body */}
      <div className="mx-auto max-w-7xl px-6 py-6">
        {error && (
          <div
            data-testid="troubleshooting-error"
            className="mb-4 rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-900/40 dark:bg-red-900/20 dark:text-red-300"
          >
            {l('查询失败:', 'Query failed: ')}{error}
          </div>
        )}

        {queried && !error && view && totalCorrelated === 0 && (
          <div
            data-testid="troubleshooting-empty"
            className="rounded-md border border-gray-200 bg-white px-6 py-10 text-center text-sm text-gray-500 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-400"
          >
            {l(
              '该 traceId 没有关联到任何命令、LLM、行为或审计记录。',
              'No command, LLM, behavior or audit records correlate to this traceId.',
            )}
          </div>
        )}

        {view && totalCorrelated > 0 && (
          <div className="space-y-6">
            {/* Summary + span tree link */}
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-gray-200 bg-white px-4 py-3 dark:border-gray-700 dark:bg-gray-800">
              <div className="flex items-center gap-2 text-sm">
                <span className="text-gray-500 dark:text-gray-400">traceId</span>
                <span className="font-mono text-gray-900 dark:text-gray-100" data-testid="result-trace-id">
                  {view.traceId}
                </span>
              </div>
              <button
                type="button"
                onClick={() => navigate(`/aurabot/traces/${encodeURIComponent(view.traceId)}`)}
                data-testid="view-span-tree"
                className="text-sm font-medium text-blue-600 hover:text-blue-700 dark:text-blue-400"
              >
                {l('查看 AI Span 树 →', 'View AI span tree →')}
              </button>
            </div>

            {/* Command audits */}
            <Section
              title={l('命令执行', 'Command Executions')}
              count={view.commandAudits.length}
              testid="section-commands"
            >
              {view.commandAudits.length === 0 ? (
                <EmptyRow text={l('无命令执行记录', 'No command executions')} />
              ) : (
                <div className="overflow-x-auto">
                  <table className="min-w-full text-sm">
                    <thead>
                      <tr className="border-b border-gray-200 text-left text-xs uppercase text-gray-500 dark:border-gray-700 dark:text-gray-400">
                        <th className="px-3 py-2">{l('命令', 'Command')}</th>
                        <th className="px-3 py-2">{l('结果', 'Result')}</th>
                        <th className="px-3 py-2">{l('阶段', 'Phase')}</th>
                        <th className="px-3 py-2">{l('耗时', 'Duration')}</th>
                        <th className="px-3 py-2">{l('阶段耗时 / 错误', 'Phase timings / Error')}</th>
                        <th className="px-3 py-2">{l('时间', 'Time')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {view.commandAudits.map((c) => (
                        <tr key={c.id} className="border-b border-gray-100 dark:border-gray-700/50" data-testid="command-row">
                          <td className="px-3 py-2 font-mono text-gray-900 dark:text-gray-100">{c.commandCode}</td>
                          <td className="px-3 py-2">
                            <span
                              className={`inline-flex rounded px-2 py-0.5 text-xs font-medium ${
                                c.success
                                  ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300'
                                  : 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'
                              }`}
                            >
                              {c.success ? l('成功', 'success') : l('失败', 'failed')}
                            </span>
                          </td>
                          <td className="px-3 py-2 text-gray-600 dark:text-gray-300">{c.phaseReached || '—'}</td>
                          <td className="px-3 py-2 text-gray-600 dark:text-gray-300">
                            {c.executionTimeMs != null ? `${c.executionTimeMs} ms` : '—'}
                          </td>
                          <td className="px-3 py-2">
                            {!c.success && c.errorMessage ? (
                              <span className="text-red-600 dark:text-red-400" data-testid="command-error">
                                {c.errorMessage}
                              </span>
                            ) : (
                              <div className="flex flex-wrap gap-1">
                                {parsePhaseTimings(c.phaseTimings).map(([phase, ms]) => (
                                  <span
                                    key={phase}
                                    className="inline-flex items-center gap-1 rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600 dark:bg-gray-700 dark:text-gray-300"
                                  >
                                    <span className="font-medium">{phase}</span>
                                    <span className="text-gray-400">{ms}ms</span>
                                  </span>
                                ))}
                              </div>
                            )}
                          </td>
                          <td className="px-3 py-2 whitespace-nowrap text-gray-500 dark:text-gray-400">{fmtTime(c.createdAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Section>

            {/* LLM usage */}
            <Section
              title={l('LLM 调用与成本', 'LLM Usage & Cost')}
              count={view.llmUsage.length}
              testid="section-llm"
            >
              {view.llmUsage.length === 0 ? (
                <EmptyRow text={l('无 LLM 调用记录', 'No LLM calls')} />
              ) : (
                <div className="overflow-x-auto">
                  <table className="min-w-full text-sm">
                    <thead>
                      <tr className="border-b border-gray-200 text-left text-xs uppercase text-gray-500 dark:border-gray-700 dark:text-gray-400">
                        <th className="px-3 py-2">{l('厂商', 'Provider')}</th>
                        <th className="px-3 py-2">{l('模型', 'Model')}</th>
                        <th className="px-3 py-2">Tokens (in/out)</th>
                        <th className="px-3 py-2">{l('成本', 'Cost')}</th>
                        <th className="px-3 py-2">{l('时间', 'Time')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {view.llmUsage.map((u, i) => (
                        <tr key={i} className="border-b border-gray-100 dark:border-gray-700/50" data-testid="llm-row">
                          <td className="px-3 py-2 text-gray-600 dark:text-gray-300">{u.provider || '—'}</td>
                          <td className="px-3 py-2 font-mono text-gray-900 dark:text-gray-100">{u.responseModel || u.requestModel || '—'}</td>
                          <td className="px-3 py-2 text-gray-600 dark:text-gray-300">
                            {(u.inputTokens ?? 0)} / {(u.outputTokens ?? 0)}
                          </td>
                          <td className="px-3 py-2 text-gray-600 dark:text-gray-300">{fmtCost(u.amount, u.currency)}</td>
                          <td className="px-3 py-2 whitespace-nowrap text-gray-500 dark:text-gray-400">{fmtTime(u.createdAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Section>

            {/* Behavior events */}
            <Section
              title={l('行为事件', 'Behavior Events')}
              count={view.behaviorEvents.length}
              testid="section-behavior"
            >
              {view.behaviorEvents.length === 0 ? (
                <EmptyRow text={l('无行为事件', 'No behavior events')} />
              ) : (
                <div className="overflow-x-auto">
                  <table className="min-w-full text-sm">
                    <thead>
                      <tr className="border-b border-gray-200 text-left text-xs uppercase text-gray-500 dark:border-gray-700 dark:text-gray-400">
                        <th className="px-3 py-2">{l('事件', 'Event')}</th>
                        <th className="px-3 py-2">{l('类别', 'Category')}</th>
                        <th className="px-3 py-2">{l('来源', 'Source')}</th>
                        <th className="px-3 py-2">{l('页面/元素', 'Page/Element')}</th>
                        <th className="px-3 py-2">{l('时间', 'Time')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {view.behaviorEvents.map((b, i) => (
                        <tr key={i} className="border-b border-gray-100 dark:border-gray-700/50" data-testid="behavior-row">
                          <td className="px-3 py-2 font-mono text-gray-900 dark:text-gray-100">{b.eventName || '—'}</td>
                          <td className="px-3 py-2 text-gray-600 dark:text-gray-300">{b.eventCategory || '—'}</td>
                          <td className="px-3 py-2 text-gray-600 dark:text-gray-300">{b.source || '—'}</td>
                          <td className="px-3 py-2 text-gray-600 dark:text-gray-300">{[b.pageId, b.elementCode].filter(Boolean).join(' / ') || '—'}</td>
                          <td className="px-3 py-2 whitespace-nowrap text-gray-500 dark:text-gray-400">{fmtTime(b.occurredAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Section>

            {/* Audit events */}
            <Section
              title={l('管理审计事件', 'Admin Audit Events')}
              count={view.auditEvents.length}
              testid="section-audit"
            >
              {view.auditEvents.length === 0 ? (
                <EmptyRow text={l('无审计事件', 'No audit events')} />
              ) : (
                <div className="overflow-x-auto">
                  <table className="min-w-full text-sm">
                    <thead>
                      <tr className="border-b border-gray-200 text-left text-xs uppercase text-gray-500 dark:border-gray-700 dark:text-gray-400">
                        <th className="px-3 py-2">{l('动作', 'Action')}</th>
                        <th className="px-3 py-2">{l('资源', 'Resource')}</th>
                        <th className="px-3 py-2">{l('结果', 'Result')}</th>
                        <th className="px-3 py-2">{l('原因', 'Reason')}</th>
                        <th className="px-3 py-2">{l('时间', 'Time')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {view.auditEvents.map((a, i) => (
                        <tr key={i} className="border-b border-gray-100 dark:border-gray-700/50" data-testid="audit-row">
                          <td className="px-3 py-2 font-mono text-gray-900 dark:text-gray-100">{a.actionType || '—'}</td>
                          <td className="px-3 py-2 text-gray-600 dark:text-gray-300">{a.resourceType || '—'}</td>
                          <td className="px-3 py-2">
                            <span
                              className={`inline-flex rounded px-2 py-0.5 text-xs font-medium ${
                                a.success === false
                                  ? 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'
                                  : 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300'
                              }`}
                            >
                              {a.success === false ? l('失败', 'failed') : l('成功', 'success')}
                            </span>
                          </td>
                          <td className="px-3 py-2 text-gray-600 dark:text-gray-300">{a.reason || '—'}</td>
                          <td className="px-3 py-2 whitespace-nowrap text-gray-500 dark:text-gray-400">{fmtTime(a.createdAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Section>
          </div>
        )}

        {!queried && !loading && (
          <div className="rounded-md border border-dashed border-gray-300 bg-white px-6 py-12 text-center text-sm text-gray-400 dark:border-gray-600 dark:bg-gray-800">
            {l('输入一个 traceId 开始排查。', 'Enter a traceId to start troubleshooting.')}
          </div>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Small presentational helpers
// ---------------------------------------------------------------------------

function Section({
  title,
  count,
  testid,
  children,
}: {
  title: string;
  count: number;
  testid: string;
  children: React.ReactNode;
}) {
  return (
    <div className="rounded-md border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800" data-testid={testid}>
      <div className="flex items-center gap-2 border-b border-gray-100 px-4 py-3 dark:border-gray-700">
        <h2 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{title}</h2>
        <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-600 dark:bg-gray-700 dark:text-gray-300">
          {count}
        </span>
      </div>
      <div className="px-4 py-3">{children}</div>
    </div>
  );
}

function EmptyRow({ text }: { text: string }) {
  return <div className="py-2 text-sm text-gray-400 dark:text-gray-500">{text}</div>;
}
