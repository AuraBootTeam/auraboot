/**
 * Error Board — failed command executions + uncaught front-end errors.
 *
 * Two sources, one board:
 *  - 命令错误 (command): failed command-pipeline executions
 *    (GET /api/meta/commands/audit-logs?success=false, perm meta.command.read).
 *  - 前端错误 (client): uncaught JS errors / unhandled rejections captured by the
 *    global reporter (GET /api/client-errors, perm meta.command.read).
 *
 * Each row that carries a traceId links to the eagle-eye troubleshooting console.
 */

import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';

type Tab = 'command' | 'client';

interface CommandAuditLog {
  id: number;
  commandCode: string;
  success: boolean;
  errorMessage: string | null;
  executionTimeMs: number | null;
  phaseReached: string | null;
  traceId: string | null;
  createdAt: string;
}

interface ClientError {
  id: number;
  errorType: string | null;
  message: string | null;
  pageUrl: string | null;
  userAgent: string | null;
  traceId: string | null;
  createdAt: string;
}

const PAGE_SIZE = 20;

function fmtTime(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

export default function ErrorBoardPage() {
  const { locale } = useI18n();
  const l = useCallback(
    (zh: string, en: string) => (locale === 'zh-CN' ? zh : en),
    [locale],
  );
  const navigate = useNavigate();

  const [tab, setTab] = useState<Tab>('command');
  const [cmdRows, setCmdRows] = useState<CommandAuditLog[]>([]);
  const [clientRows, setClientRows] = useState<ClientError[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [failedOnly, setFailedOnly] = useState(true);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchRows = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      if (tab === 'command') {
        const params = new URLSearchParams({ pageNum: String(pageNum), pageSize: String(PAGE_SIZE) });
        if (failedOnly) params.set('success', 'false');
        const res = await fetch(`/api/meta/commands/audit-logs?${params}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const json = await res.json();
        const data = json?.data ?? json;
        setCmdRows(data?.records ?? []);
        setTotal(data?.total ?? 0);
      } else {
        const params = new URLSearchParams({ pageNum: String(pageNum), pageSize: String(PAGE_SIZE) });
        const res = await fetch(`/api/client-errors?${params}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const json = await res.json();
        const data = json?.data ?? json;
        setClientRows(data?.records ?? []);
        setTotal(data?.total ?? 0);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setCmdRows([]);
      setClientRows([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [tab, pageNum, failedOnly]);

  useEffect(() => {
    fetchRows();
  }, [fetchRows]);

  const switchTab = useCallback((t: Tab) => {
    setTab(t);
    setPageNum(1);
  }, []);

  const openTrace = useCallback(
    (traceId: string | null) => {
      if (!traceId) return;
      navigate(`/ops/troubleshooting?traceId=${encodeURIComponent(traceId)}`);
    },
    [navigate],
  );

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900" data-testid="error-board-page">
      {/* Header */}
      <div className="border-b border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
        <div className="mx-auto max-w-7xl px-6 py-5">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-xl font-semibold text-gray-900 dark:text-gray-100">
                {l('错误看板', 'Error Board')}
              </h1>
              <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
                {l(
                  '失败命令与未捕获前端错误。点带 traceId 的行跳到鹰眼排障台。',
                  'Failed commands and uncaught front-end errors. Click a row with a traceId to open the eagle-eye console.',
                )}
              </p>
            </div>
            <button
              type="button"
              onClick={fetchRows}
              data-testid="error-refresh-btn"
              className="rounded-md border border-gray-300 px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-200 dark:hover:bg-gray-700"
            >
              {l('刷新', 'Refresh')}
            </button>
          </div>

          {/* Source tabs */}
          <div className="mt-4 flex items-center gap-2">
            <button
              type="button"
              onClick={() => switchTab('command')}
              data-testid="tab-command"
              className={`rounded-md px-3 py-1.5 text-sm font-medium ${
                tab === 'command'
                  ? 'bg-blue-600 text-white'
                  : 'border border-gray-300 text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-200 dark:hover:bg-gray-700'
              }`}
            >
              {l('命令错误', 'Command Errors')}
            </button>
            <button
              type="button"
              onClick={() => switchTab('client')}
              data-testid="tab-client"
              className={`rounded-md px-3 py-1.5 text-sm font-medium ${
                tab === 'client'
                  ? 'bg-blue-600 text-white'
                  : 'border border-gray-300 text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-200 dark:hover:bg-gray-700'
              }`}
            >
              {l('前端错误', 'Front-end Errors')}
            </button>
            {tab === 'command' && (
              <label className="ml-3 flex items-center gap-2 text-sm text-gray-700 dark:text-gray-200">
                <input
                  type="checkbox"
                  checked={failedOnly}
                  onChange={(e) => { setFailedOnly(e.target.checked); setPageNum(1); }}
                  data-testid="failed-only-toggle"
                  className="h-4 w-4 rounded border-gray-300"
                />
                {l('仅失败', 'Failures only')}
              </label>
            )}
          </div>
        </div>
      </div>

      {/* Body */}
      <div className="mx-auto max-w-7xl px-6 py-6">
        {error && (
          <div
            data-testid="error-board-error"
            className="mb-4 rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-900/40 dark:bg-red-900/20 dark:text-red-300"
          >
            {l('加载失败:', 'Failed to load: ')}{error}
          </div>
        )}

        <div className="rounded-md border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
          <div className="overflow-x-auto">
            {tab === 'command' ? (
              <table className="min-w-full text-sm">
                <thead>
                  <tr className="border-b border-gray-200 text-left text-xs uppercase text-gray-500 dark:border-gray-700 dark:text-gray-400">
                    <th className="px-4 py-3">{l('命令', 'Command')}</th>
                    <th className="px-4 py-3">{l('结果', 'Result')}</th>
                    <th className="px-4 py-3">{l('阶段', 'Phase')}</th>
                    <th className="px-4 py-3">{l('耗时', 'Duration')}</th>
                    <th className="px-4 py-3">{l('错误信息', 'Error')}</th>
                    <th className="px-4 py-3">traceId</th>
                    <th className="px-4 py-3">{l('时间', 'Time')}</th>
                  </tr>
                </thead>
                <tbody>
                  {loading ? (
                    <tr><td colSpan={7} className="px-4 py-10 text-center text-gray-400" data-testid="error-board-loading">{l('加载中…', 'Loading…')}</td></tr>
                  ) : cmdRows.length === 0 ? (
                    <tr><td colSpan={7} className="px-4 py-10 text-center text-gray-400" data-testid="error-board-empty">
                      {failedOnly
                        ? l('没有失败的命令执行 —— 一切正常。', 'No failed command executions — all clear.')
                        : l('暂无命令执行记录。', 'No command executions yet.')}
                    </td></tr>
                  ) : (
                    cmdRows.map((r) => (
                      <tr
                        key={r.id}
                        onClick={() => openTrace(r.traceId)}
                        data-testid="error-row"
                        className={`border-b border-gray-100 dark:border-gray-700/50 ${r.traceId ? 'cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-700/40' : ''}`}
                      >
                        <td className="px-4 py-2.5 font-mono text-gray-900 dark:text-gray-100">{r.commandCode}</td>
                        <td className="px-4 py-2.5">
                          <span className={`inline-flex rounded px-2 py-0.5 text-xs font-medium ${
                            r.success ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300' : 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'
                          }`}>
                            {r.success ? l('成功', 'success') : l('失败', 'failed')}
                          </span>
                        </td>
                        <td className="px-4 py-2.5 text-gray-600 dark:text-gray-300">{r.phaseReached || '—'}</td>
                        <td className="px-4 py-2.5 text-gray-600 dark:text-gray-300">{r.executionTimeMs != null ? `${r.executionTimeMs} ms` : '—'}</td>
                        <td className="px-4 py-2.5 max-w-md truncate text-red-600 dark:text-red-400" title={r.errorMessage || ''}>{r.errorMessage || '—'}</td>
                        <td className="px-4 py-2.5 font-mono text-xs text-blue-600 dark:text-blue-400">{r.traceId ? `${r.traceId.slice(0, 12)}…` : '—'}</td>
                        <td className="px-4 py-2.5 whitespace-nowrap text-gray-500 dark:text-gray-400">{fmtTime(r.createdAt)}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            ) : (
              <table className="min-w-full text-sm">
                <thead>
                  <tr className="border-b border-gray-200 text-left text-xs uppercase text-gray-500 dark:border-gray-700 dark:text-gray-400">
                    <th className="px-4 py-3">{l('类型', 'Type')}</th>
                    <th className="px-4 py-3">{l('错误信息', 'Message')}</th>
                    <th className="px-4 py-3">{l('页面', 'Page')}</th>
                    <th className="px-4 py-3">traceId</th>
                    <th className="px-4 py-3">{l('时间', 'Time')}</th>
                  </tr>
                </thead>
                <tbody>
                  {loading ? (
                    <tr><td colSpan={5} className="px-4 py-10 text-center text-gray-400" data-testid="error-board-loading">{l('加载中…', 'Loading…')}</td></tr>
                  ) : clientRows.length === 0 ? (
                    <tr><td colSpan={5} className="px-4 py-10 text-center text-gray-400" data-testid="client-error-empty">
                      {l('没有前端错误上报 —— 一切正常。', 'No front-end errors reported — all clear.')}
                    </td></tr>
                  ) : (
                    clientRows.map((r) => (
                      <tr
                        key={r.id}
                        onClick={() => openTrace(r.traceId)}
                        data-testid="client-error-row"
                        className={`border-b border-gray-100 dark:border-gray-700/50 ${r.traceId ? 'cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-700/40' : ''}`}
                      >
                        <td className="px-4 py-2.5">
                          <span className="inline-flex rounded bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-700 dark:bg-amber-900/30 dark:text-amber-300">
                            {r.errorType || 'error'}
                          </span>
                        </td>
                        <td className="px-4 py-2.5 max-w-lg truncate text-red-600 dark:text-red-400" title={r.message || ''}>{r.message || '—'}</td>
                        <td className="px-4 py-2.5 max-w-xs truncate text-gray-600 dark:text-gray-300" title={r.pageUrl || ''}>{r.pageUrl || '—'}</td>
                        <td className="px-4 py-2.5 font-mono text-xs text-blue-600 dark:text-blue-400">{r.traceId ? `${r.traceId.slice(0, 12)}…` : '—'}</td>
                        <td className="px-4 py-2.5 whitespace-nowrap text-gray-500 dark:text-gray-400">{fmtTime(r.createdAt)}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            )}
          </div>

          {/* Pagination */}
          <div className="flex items-center justify-between border-t border-gray-100 px-4 py-3 text-sm dark:border-gray-700">
            <span className="text-gray-500 dark:text-gray-400" data-testid="error-total">
              {l('共', 'Total')} {total} {l('条', '')}
            </span>
            <div className="flex items-center gap-2">
              <button type="button" onClick={() => setPageNum((p) => Math.max(1, p - 1))} disabled={pageNum <= 1}
                className="rounded border border-gray-300 px-2 py-1 disabled:opacity-40 dark:border-gray-600">{l('上一页', 'Prev')}</button>
              <span className="text-gray-600 dark:text-gray-300">{pageNum} / {totalPages}</span>
              <button type="button" onClick={() => setPageNum((p) => Math.min(totalPages, p + 1))} disabled={pageNum >= totalPages}
                className="rounded border border-gray-300 px-2 py-1 disabled:opacity-40 dark:border-gray-600">{l('下一页', 'Next')}</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
