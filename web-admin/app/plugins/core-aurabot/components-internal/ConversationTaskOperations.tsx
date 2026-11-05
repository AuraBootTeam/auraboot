import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { get } from '~/shared/services/http-client';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/utils/i18n';
import TEXT from './ConversationTaskOperations.i18n.json';

type Task = {
  taskPid: string;
  title: string;
  status: string;
  agentCode: string;
  agentPid?: string;
  agentName?: string;
  conversationId?: string;
  turnId?: string;
  traceId?: string;
  traceStatus?: string;
  traceError?: string;
  createdAt?: string;
  inputTokens?: number;
  outputTokens?: number;
  usageCalls?: number;
  costStatus: string;
  knownAmount?: number;
  currency?: string;
  approvalCount: number;
};
type Page = {
  records: Task[];
  total: number;
  limit: number;
  offset: number;
  unparseableMetadataCount?: number;
};
type Detail = {
  task: Task;
  traces: { traceId: string; status: string; durationMs?: number }[];
  approvals: { pid: string; type: string; status: string }[];
  actions: { pid: string; command: string; status: string; model?: string }[];
  truncated: boolean;
};
const BUTTON =
  'rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text hover:bg-hover disabled:opacity-50';

/** Read-only composition over existing task, trace, action, approval and usage evidence. */
export function ConversationTaskOperations({ allowed }: { allowed: boolean }) {
  const { locale } = useI18n();
  const text = (key: keyof typeof TEXT) => getLocalizedText(TEXT[key], locale);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('');
  const [offset, setOffset] = useState(0);
  const [nonce, setNonce] = useState(0);
  const [page, setPage] = useState<Page>();
  const [detail, setDetail] = useState<Detail>();
  const [selected, setSelected] = useState('');
  const [error, setError] = useState(false);
  const [detailError, setDetailError] = useState(false);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    if (!allowed) return;
    let current = true;
    setLoading(true);
    setError(false);
    setPage(undefined);
    setDetail(undefined);
    setSelected('');
    void get<Page>('/api/admin/agent-runs/conversation-tasks', {
      query: filter || undefined,
      offset,
      limit: 20,
    })
      .then((result) => {
        if (!current) return;
        if (String(result.code) !== '0' || !Array.isArray(result.data?.records))
          throw new Error('invalid');
        setPage(result.data);
      })
      .catch(() => {
        if (current) setError(true);
      })
      .finally(() => {
        if (current) setLoading(false);
      });
    return () => {
      current = false;
    };
  }, [allowed, filter, offset, nonce]);
  useEffect(() => {
    if (!allowed || !selected) return;
    let current = true;
    setDetail(undefined);
    setDetailError(false);
    void get<Detail>(`/api/admin/agent-runs/conversation-tasks/${encodeURIComponent(selected)}`)
      .then((result) => {
        if (!current) return;
        if (String(result.code) !== '0' || !result.data?.task) throw new Error('invalid');
        setDetail(result.data);
      })
      .catch(() => {
        if (current) setDetailError(true);
      });
    return () => {
      current = false;
    };
  }, [selected, allowed]);
  const status = (task: Task) =>
    task.status === 'in_progress' && task.traceStatus === 'pending'
      ? text('pending')
      : ({
          completed: text('turnCompleted'),
          failed: text('failed'),
          in_progress: text('running'),
          cancelled: text('cancelled'),
        }[task.status] ?? text('unknown'));
  const cost = (task: Task) =>
    task.costStatus === 'known' && typeof task.knownAmount === 'number'
      ? `${task.currency ?? 'USD'} ${task.knownAmount.toFixed(6)}`
      : text('unknownCost');
  if (!allowed)
    return (
      <p role="status" className="rounded-card border-border bg-panel border p-5">
        {text('permission')}
      </p>
    );
  return (
    <section className="space-y-4" data-testid="conversation-task-operations">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-text text-lg font-semibold">{text('title')}</h2>
          <p className="text-text-2 mt-1 max-w-3xl text-sm">{text('scope')}</p>
        </div>
        <button
          type="button"
          className={BUTTON}
          disabled={loading}
          onClick={() => setNonce((n) => n + 1)}
        >
          {text('refresh')}
        </button>
      </div>
      <div className="flex flex-wrap gap-2">
        <input
          aria-label={text('search')}
          placeholder={text('search')}
          value={query}
          className="rounded-control border-border-strong bg-panel min-w-64 border px-3 py-2 text-sm"
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              setFilter(query.trim());
              setOffset(0);
            }
          }}
        />
        <button
          type="button"
          className={BUTTON}
          onClick={() => {
            setFilter(query.trim());
            setOffset(0);
          }}
        >
          {text('searchButton')}
        </button>
      </div>
      {error && (
        <p role="alert" className="text-danger">
          {text('loadFailed')}
        </p>
      )}
      {loading && <p role="status">{text('loading')}</p>}
      {page && (
        <>
          {(page.unparseableMetadataCount ?? 0) > 0 && (
            <p role="status" className="text-warning text-sm">
              {text('unparseable')} {page.unparseableMetadataCount}
            </p>
          )}
          {page.records.length === 0 ? (
            <p role="status" className="rounded-card border-border bg-panel border p-5">
              {text('empty')}
            </p>
          ) : (
            <div className="rounded-card border-border bg-panel overflow-x-auto border">
              <table className="w-full text-sm" data-testid="conversation-task-table">
                <thead>
                  <tr className="border-border bg-subtle text-text-2 border-b text-left">
                    {(
                      ['task', 'agent', 'status', 'usage', 'cost', 'decisions', 'details'] as const
                    ).map((key) => (
                      <th key={key} className="px-3 py-3 font-medium">
                        {text(key)}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {page.records.map((task) => (
                    <tr
                      key={task.taskPid}
                      className="border-border border-b last:border-0"
                      data-testid={`conversation-task-${task.taskPid}`}
                    >
                      <td className="max-w-sm px-3 py-3">
                        <p className="text-text line-clamp-2">{task.title}</p>
                        <p className="text-text-2 mt-1 text-xs">
                          {task.createdAt
                            ? new Date(task.createdAt).toLocaleString(locale)
                            : text('unknown')}
                        </p>
                      </td>
                      <td className="px-3 py-3">{task.agentName ?? task.agentCode}</td>
                      <td className="px-3 py-3 whitespace-nowrap">{status(task)}</td>
                      <td className="px-3 py-3">
                        {task.usageCalls
                          ? `${task.inputTokens ?? '?'} / ${task.outputTokens ?? '?'}`
                          : text('notRecorded')}
                      </td>
                      <td className="px-3 py-3 whitespace-nowrap">{cost(task)}</td>
                      <td className="px-3 py-3">{task.approvalCount}</td>
                      <td className="px-3 py-3">
                        <button
                          type="button"
                          className={BUTTON}
                          onClick={() => setSelected(task.taskPid)}
                        >
                          {text('inspect')}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <div className="text-text-2 flex items-center justify-between text-sm">
            <span>
              {text('total')} {page.total}
            </span>
            <div className="flex gap-2">
              <button
                type="button"
                className={BUTTON}
                disabled={offset === 0 || loading}
                onClick={() => setOffset((n) => Math.max(0, n - 20))}
              >
                {text('previous')}
              </button>
              <button
                type="button"
                className={BUTTON}
                disabled={offset + page.records.length >= page.total || loading}
                onClick={() => setOffset((n) => n + 20)}
              >
                {text('next')}
              </button>
            </div>
          </div>
        </>
      )}
      {selected && (
        <section
          className="rounded-card border-border bg-panel space-y-3 border p-5"
          data-testid="conversation-task-detail"
        >
          <div className="flex items-center justify-between gap-3">
            <h3 className="font-semibold">{text('detailTitle')}</h3>
            <button type="button" className={BUTTON} onClick={() => setSelected('')}>
              {text('close')}
            </button>
          </div>
          {detailError && (
            <p role="alert" className="text-danger">
              {text('detailFailed')}
            </p>
          )}
          {!detail && !detailError && <p role="status">{text('loading')}</p>}
          {detail && (
            <>
              <p className="font-medium">{detail.task.title}</p>
              <p className="text-text-2 text-sm">
                {status(detail.task)} · {text('cost')}：{cost(detail.task)}
              </p>
              <p className="text-text-2 text-sm">{text('costHelp')}</p>
              {detail.task.agentPid && detail.task.conversationId && (
                <Link
                  className="text-accent text-sm underline"
                  to={`/p/c/ai_colleague_chat?agentPid=${encodeURIComponent(detail.task.agentPid)}&conversationId=${encodeURIComponent(detail.task.conversationId)}`}
                >
                  {text('conversation')}
                </Link>
              )}
              <h4 className="text-sm font-semibold">{text('traces')}</h4>
              {!detail.traces.length ? (
                <p className="text-text-2 text-sm">{text('noTrace')}</p>
              ) : (
                <ul className="space-y-2">
                  {detail.traces.map((trace, index) => (
                    <li key={trace.traceId} className="text-sm">
                      <Link
                        className="text-accent underline"
                        to={`/aurabot/traces/${encodeURIComponent(trace.traceId)}`}
                      >
                        {text('trace')} {index + 1}
                      </Link>{' '}
                      ·{' '}
                      {trace.status === 'success'
                        ? text('completed')
                        : trace.status === 'pending'
                          ? text('pending')
                          : trace.status === 'error'
                            ? text('failed')
                            : text('running')}{' '}
                      · {trace.durationMs == null ? text('notRecorded') : `${trace.durationMs} ms`}
                    </li>
                  ))}
                </ul>
              )}
              <h4 className="text-sm font-semibold">{text('decisions')}</h4>
              {!detail.approvals.length ? (
                <p className="text-text-2 text-sm">{text('noApproval')}</p>
              ) : (
                <ul>
                  {detail.approvals.map((approval) => (
                    <li key={approval.pid} className="text-sm">
                      {text('approval')} ·{' '}
                      {approval.status === 'approved'
                        ? text('approved')
                        : approval.status === 'rejected'
                          ? text('rejected')
                          : approval.status === 'pending'
                            ? text('pending')
                            : text('unknown')}
                    </li>
                  ))}
                </ul>
              )}
              <h4 className="text-sm font-semibold">{text('actions')}</h4>
              {!detail.actions.length ? (
                <p className="text-text-2 text-sm">{text('noAction')}</p>
              ) : (
                <ul>
                  {detail.actions.map((action) => (
                    <li key={action.pid} className="text-sm">
                      {action.status === 'success' ||
                      action.status === 'completed' ||
                      action.status === 'succeeded'
                        ? text('completed')
                        : action.status === 'failed'
                          ? text('failed')
                          : text('unknown')}{' '}
                      · <code>{action.command}</code>
                    </li>
                  ))}
                </ul>
              )}
              {detail.truncated && (
                <p role="status" className="text-text-2 text-sm">
                  {text('truncated')}
                </p>
              )}
              <details>
                <summary className="text-text-2 cursor-pointer text-sm">{text('identity')}</summary>
                <pre className="text-xs break-all whitespace-pre-wrap">
                  {JSON.stringify(
                    {
                      taskPid: detail.task.taskPid,
                      turnId: detail.task.turnId,
                      conversationId: detail.task.conversationId,
                      costStatus: detail.task.costStatus,
                    },
                    null,
                    2,
                  )}
                </pre>
              </details>
            </>
          )}
        </section>
      )}
    </section>
  );
}
