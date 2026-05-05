/**
 * Replay UI MVP — Agent Runs admin list page.
 *
 * Consumes the AgentRunController REST endpoints (gated by
 * AdminRoleInterceptor → TENANT_ADMIN). Provides:
 *   - Toolbar filters (status / agentCode / parentRunId / keyword)
 *   - Paginated table of runs
 *   - Right-side drawer with full detail (metadata / actions / interrupts /
 *     child runs / BIF)
 *
 * URL state: filters + page + open runId persist via URLSearchParams so the
 * page is shareable and back/forward navigation works as operators expect.
 *
 * Out of scope for MVP (deferred to P1.5/P2):
 *   - time-travel / fork-from-step
 *   - shadow-run comparison view
 *   - WebSocket live refresh
 *   - export / report
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';
import {
  listAgentRuns,
  type AgentRunListItem,
  type AgentRunPage,
  type AgentRunsListParams,
} from '../../services/agentRunsApi';
import AgentRunDetailDrawer, {
  fmtCost,
  fmtDuration,
  shortPid,
  statusColor,
} from '../../components-internal/AgentRunDetailDrawer';

const STATUS_OPTIONS = ['running', 'succeeded', 'failed', 'cancelled', 'timeout'];
const PAGE_SIZE = 20;

function relativeTime(iso: string): string {
  if (!iso) return '-';
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return iso;
  const diff = Date.now() - t;
  const sec = Math.round(diff / 1000);
  if (sec < 60) return `${sec}s ago`;
  const min = Math.round(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.round(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const day = Math.round(hr / 24);
  return `${day}d ago`;
}

export default function AgentRunsPage() {
  const { locale } = useI18n();
  const l = useCallback(
    (zh: string, en: string) => (locale === 'zh-CN' ? zh : en),
    [locale],
  );

  const [searchParams, setSearchParams] = useSearchParams();

  // ----- filters mirrored to URLSearchParams ------------------------------
  const status = searchParams.get('status') ?? '';
  const agentCode = searchParams.get('agentCode') ?? '';
  const parentRunId = searchParams.get('parentRunId') ?? '';
  const keyword = searchParams.get('keyword') ?? '';
  const page = Number(searchParams.get('page') ?? '0') || 0;
  const openRunId = searchParams.get('runId');

  const updateParams = useCallback(
    (patch: Record<string, string | null>) => {
      const next = new URLSearchParams(searchParams);
      for (const [k, v] of Object.entries(patch)) {
        if (v === null || v === '') next.delete(k);
        else next.set(k, v);
      }
      setSearchParams(next);
    },
    [searchParams, setSearchParams],
  );

  // ----- data load --------------------------------------------------------
  const [pageData, setPageData] = useState<AgentRunPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const params = useMemo<AgentRunsListParams>(
    () => ({
      page,
      size: PAGE_SIZE,
      status: status || undefined,
      agentCode: agentCode || undefined,
      parentRunId: parentRunId || undefined,
      keyword: keyword || undefined,
    }),
    [page, status, agentCode, parentRunId, keyword],
  );

  const fetchRuns = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await listAgentRuns(params);
      setPageData(data);
    } catch (e) {
      setError((e as Error).message);
      setPageData({ items: [], total: 0, page, size: PAGE_SIZE });
    } finally {
      setLoading(false);
    }
  }, [params, page]);

  useEffect(() => {
    fetchRuns();
  }, [fetchRuns]);

  // ----- handlers ---------------------------------------------------------
  const openDrawer = useCallback(
    (runId: string) => updateParams({ runId }),
    [updateParams],
  );
  const closeDrawer = useCallback(() => updateParams({ runId: null }), [updateParams]);

  const totalPages = pageData ? Math.max(1, Math.ceil(pageData.total / PAGE_SIZE)) : 1;

  // ----- render -----------------------------------------------------------
  return (
    <div className="p-6 max-w-7xl mx-auto" data-testid="agent-runs-page">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">
          {l('Agent 运行记录', 'Agent Runs')}
        </h1>
        <button
          type="button"
          onClick={fetchRuns}
          className="text-sm px-3 py-1 rounded border border-gray-300 hover:bg-gray-50"
          data-testid="refresh-button"
        >
          {l('刷新', 'Refresh')}
        </button>
      </div>

      {/* Toolbar */}
      <div className="mb-4 grid grid-cols-1 md:grid-cols-4 gap-3" data-testid="filters-toolbar">
        <div>
          <label className="block text-xs text-gray-500 mb-1">
            {l('状态', 'Status')}
          </label>
          <select
            data-testid="filter-status"
            value={status}
            onChange={(e) => updateParams({ status: e.target.value, page: '0' })}
            className="w-full border border-gray-300 rounded px-2 py-1 text-sm"
          >
            <option value="">{l('全部', 'All')}</option>
            {STATUS_OPTIONS.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="block text-xs text-gray-500 mb-1">Agent Code</label>
          <input
            type="text"
            data-testid="filter-agent-code"
            value={agentCode}
            onChange={(e) => updateParams({ agentCode: e.target.value, page: '0' })}
            className="w-full border border-gray-300 rounded px-2 py-1 text-sm"
          />
        </div>
        <div>
          <label className="block text-xs text-gray-500 mb-1">Parent Run ID</label>
          <input
            type="text"
            data-testid="filter-parent-run-id"
            value={parentRunId}
            onChange={(e) => updateParams({ parentRunId: e.target.value, page: '0' })}
            className="w-full border border-gray-300 rounded px-2 py-1 text-sm font-mono"
          />
        </div>
        <div>
          <label className="block text-xs text-gray-500 mb-1">
            {l('关键词', 'Keyword')}
          </label>
          <input
            type="text"
            data-testid="filter-keyword"
            value={keyword}
            onChange={(e) => updateParams({ keyword: e.target.value, page: '0' })}
            className="w-full border border-gray-300 rounded px-2 py-1 text-sm"
          />
        </div>
      </div>

      {error && (
        <div
          className="mb-3 text-sm text-red-700 bg-red-50 border border-red-200 rounded px-3 py-2"
          data-testid="error-banner"
        >
          {error}
        </div>
      )}

      {loading && (
        <div className="text-sm text-gray-500" data-testid="loading-state">
          {l('加载中...', 'Loading...')}
        </div>
      )}

      {!loading && pageData && pageData.items.length === 0 && (
        <div
          className="text-sm text-gray-500 border border-dashed border-gray-300 rounded p-6 text-center"
          data-testid="empty-state"
        >
          {l('暂无 Agent 运行记录', 'No agent runs found')}
        </div>
      )}

      {!loading && pageData && pageData.items.length > 0 && (
        <>
          <div className="overflow-x-auto">
            <table className="w-full text-sm border-collapse" data-testid="runs-table">
              <thead>
                <tr className="border-b border-gray-200 text-left text-gray-600">
                  <th className="py-2 pr-3">Run ID</th>
                  <th className="py-2 pr-3">Agent</th>
                  <th className="py-2 pr-3">{l('状态', 'Status')}</th>
                  <th className="py-2 pr-3">Parent</th>
                  <th className="py-2 pr-3">Origin</th>
                  <th className="py-2 pr-3 text-right">{l('成本', 'Cost')}</th>
                  <th className="py-2 pr-3 text-right">{l('耗时', 'Duration')}</th>
                  <th className="py-2 pr-3">{l('创建于', 'Created')}</th>
                </tr>
              </thead>
              <tbody>
                {pageData.items.map((row: AgentRunListItem) => (
                  <tr
                    key={row.runId}
                    className="border-b border-gray-100 hover:bg-gray-50 cursor-pointer align-top"
                    data-testid={`run-row-${row.runId}`}
                    onClick={() => openDrawer(row.runId)}
                  >
                    <td className="py-2 pr-3 font-mono text-xs">{shortPid(row.runId)}</td>
                    <td className="py-2 pr-3 text-xs">{row.agentCode ?? '-'}</td>
                    <td className="py-2 pr-3">
                      <span
                        className={`inline-block px-2 py-0.5 text-xs rounded ${statusColor(row.runStatus)}`}
                        data-testid={`status-badge-${row.runId}`}
                      >
                        {row.runStatus}
                      </span>
                    </td>
                    <td className="py-2 pr-3 font-mono text-xs">
                      {row.parentRunId ? (
                        <span className="inline-block px-1.5 py-0.5 bg-purple-100 text-purple-800 rounded">
                          {shortPid(row.parentRunId)}
                        </span>
                      ) : (
                        <span className="text-gray-400">-</span>
                      )}
                    </td>
                    <td className="py-2 pr-3 text-xs text-gray-600">
                      {row.subtaskOrigin ?? '-'}
                    </td>
                    <td className="py-2 pr-3 text-xs tabular-nums text-right">
                      {fmtCost(row.costUsd)}
                    </td>
                    <td className="py-2 pr-3 text-xs tabular-nums text-right">
                      {fmtDuration(row.durationMs)}
                    </td>
                    <td className="py-2 pr-3 text-xs text-gray-500">
                      {relativeTime(row.createdAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          <div
            className="mt-4 flex items-center justify-between text-xs text-gray-600"
            data-testid="pagination"
          >
            <div>
              {l('共', 'Total')} {pageData.total} · {l('页', 'Page')} {page + 1} /{' '}
              {totalPages}
            </div>
            <div className="flex gap-2">
              <button
                type="button"
                disabled={page <= 0}
                onClick={() => updateParams({ page: String(page - 1) })}
                className="px-3 py-1 border border-gray-300 rounded disabled:opacity-50"
                data-testid="page-prev"
              >
                {l('上一页', 'Prev')}
              </button>
              <button
                type="button"
                disabled={page + 1 >= totalPages}
                onClick={() => updateParams({ page: String(page + 1) })}
                className="px-3 py-1 border border-gray-300 rounded disabled:opacity-50"
                data-testid="page-next"
              >
                {l('下一页', 'Next')}
              </button>
            </div>
          </div>
        </>
      )}

      <AgentRunDetailDrawer
        runId={openRunId}
        onClose={closeDrawer}
        onSelectRun={openDrawer}
      />
    </div>
  );
}
