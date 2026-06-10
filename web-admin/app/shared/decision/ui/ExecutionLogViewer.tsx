import { useMemo, useState } from 'react';

/**
 * DecisionOps execution-log viewer (mockup 执行日志 / F4, docs/1.md §22): a filterable, searchable
 * table of decision/policy execution log entries — status, matched rules, action plans, duration,
 * traceId. Read-only observability; data is fetched by the caller (this is the presentation slice).
 */

export type ExecLogStatus =
  | 'SUCCESS' | 'PARTIAL_SUCCESS' | 'FAILED' | 'FAILED_RETRYING' | 'NOT_RUN'
  | 'MATCHED' | 'NOT_MATCHED' | 'UNKNOWN' | 'ERROR' | 'SKIPPED';

export interface ExecLogEntry {
  traceId: string;
  eventId?: string;
  policyCode?: string;
  decisionCode?: string;
  status: ExecLogStatus;
  matchedRules?: string[];
  actionPlans?: string[];
  durationMs?: number;
  attempts?: number;
  error?: string;
  time?: string;
}

export interface ExecutionLogViewerProps {
  logs: ExecLogEntry[];
  /** Optional pre-selected status filter. */
  initialStatus?: ExecLogStatus | 'ALL';
}

const STATUS_OPTIONS: (ExecLogStatus | 'ALL')[] = ['ALL', 'SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'FAILED_RETRYING', 'ERROR'];

export function ExecutionLogViewer({ logs, initialStatus = 'ALL' }: ExecutionLogViewerProps) {
  const [status, setStatus] = useState<ExecLogStatus | 'ALL'>(initialStatus);
  const [query, setQuery] = useState('');
  const [selectedLog, setSelectedLog] = useState<ExecLogEntry | null>(null);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return logs.filter((l) => (status === 'ALL' || l.status === status))
      .filter((l) => !q
        || (l.traceId?.toLowerCase().includes(q))
        || (l.eventId?.toLowerCase().includes(q))
        || (l.policyCode?.toLowerCase().includes(q))
        || (l.decisionCode?.toLowerCase().includes(q)));
  }, [logs, status, query]);

  return (
    <div data-testid="exec-log-viewer">
      <div className="elv-toolbar">
        <select aria-label="status-filter" value={status} onChange={(e) => setStatus(e.target.value as ExecLogStatus | 'ALL')}>
          {STATUS_OPTIONS.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        <input
          aria-label="log-search"
          placeholder="搜索 traceId / eventId / code"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <span data-testid="elv-count">{filtered.length}</span>
      </div>

      {filtered.length === 0 ? (
        <div data-testid="elv-empty">无匹配日志</div>
      ) : (
        <table className="elv-table">
          <thead>
            <tr><th>traceId</th><th>code</th><th>状态</th><th>命中规则</th><th>动作</th><th>耗时</th><th>时间</th><th>详情</th></tr>
          </thead>
          <tbody>
            {filtered.map((l) => (
              <tr key={l.traceId} data-testid={`elv-row-${l.traceId}`} data-status={l.status}>
                <td className="mono">{l.traceId}</td>
                <td>{l.policyCode ?? l.decisionCode ?? '—'}</td>
                <td><span className={`elv-status elv-${l.status}`}>{l.status}</span></td>
                <td>{(l.matchedRules ?? []).join(', ') || '—'}</td>
                <td>{(l.actionPlans ?? []).join(', ') || '—'}</td>
                <td>{l.durationMs != null ? `${l.durationMs}ms` : '—'}</td>
                <td>{l.time ?? '—'}</td>
                <td>
                  <button
                    type="button"
                    data-testid={`elv-open-${l.traceId}`}
                    onClick={() => setSelectedLog(l)}
                  >详情</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {selectedLog && (
        <aside className="elv-detail-drawer" role="dialog" aria-label="执行详情" data-testid="elv-detail-drawer">
          <div className="drawer-head">
            <h4>执行详情</h4>
            <button type="button" data-testid="elv-detail-close" onClick={() => setSelectedLog(null)}>关闭</button>
          </div>
          <dl>
            <dt>traceId</dt><dd className="mono">{selectedLog.traceId}</dd>
            <dt>eventId</dt><dd className="mono">{selectedLog.eventId ?? '—'}</dd>
            <dt>code</dt><dd>{selectedLog.policyCode ?? selectedLog.decisionCode ?? '—'}</dd>
            <dt>状态</dt><dd>{selectedLog.status}</dd>
            <dt>命中规则</dt><dd>{(selectedLog.matchedRules ?? []).join(', ') || '—'}</dd>
            <dt>动作</dt><dd>{(selectedLog.actionPlans ?? []).join(', ') || '—'}</dd>
            <dt>耗时</dt><dd>{selectedLog.durationMs != null ? `${selectedLog.durationMs}ms` : '—'}</dd>
            <dt>重试次数</dt><dd>{selectedLog.attempts ?? 0}</dd>
            <dt>错误</dt><dd>{selectedLog.error ?? '—'}</dd>
            <dt>时间</dt><dd>{selectedLog.time ?? '—'}</dd>
          </dl>
        </aside>
      )}
    </div>
  );
}

export default ExecutionLogViewer;
