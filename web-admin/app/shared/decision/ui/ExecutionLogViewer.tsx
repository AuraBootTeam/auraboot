import { useMemo, useState } from 'react';
import { decisionStatusLabel } from './statusLabels';

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
  outputSnapshot?: Record<string, unknown>;
  traceSnapshot?: ExecTraceSnapshot | Record<string, unknown>;
}

export interface ExecVirtualSourceTrace {
  sourceRef?: string;
  modelCode?: string;
  recordId?: string;
  status?: string;
  reason?: string;
  fields?: Record<string, unknown>;
}

export interface ExecTraceSnapshot {
  virtualSources?: ExecVirtualSourceTrace[];
  unknownReasons?: string[];
}

export interface ExecutionLogViewerProps {
  logs: ExecLogEntry[];
  /** Optional pre-selected status filter. */
  initialStatus?: ExecLogStatus | 'ALL';
}

const STATUS_OPTIONS: (ExecLogStatus | 'ALL')[] = ['ALL', 'SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'FAILED_RETRYING', 'ERROR'];
const HIDDEN_TRACE_FIELDS = new Set(['id', 'tenant_id', 'tenantId', 'deleted_flag']);

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function virtualSources(snapshot: ExecLogEntry['traceSnapshot']): ExecVirtualSourceTrace[] {
  const raw = asRecord(snapshot)?.virtualSources;
  return Array.isArray(raw)
    ? raw.filter((item): item is ExecVirtualSourceTrace => Boolean(asRecord(item)))
    : [];
}

function unknownReasons(snapshot: ExecLogEntry['traceSnapshot']): string[] {
  const raw = asRecord(snapshot)?.unknownReasons;
  return Array.isArray(raw)
    ? raw.filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
    : [];
}

function fieldEntries(fields?: Record<string, unknown>) {
  if (!fields) return [];
  return Object.entries(fields)
    .filter(([key, value]) => value !== undefined && !HIDDEN_TRACE_FIELDS.has(key))
    .sort(([left], [right]) => left.localeCompare(right));
}

function displayValue(value: unknown): string {
  if (value == null) return '—';
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

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
          {STATUS_OPTIONS.map((s) => (
            <option key={s} value={s}>
              {s === 'ALL' ? '全部' : decisionStatusLabel(s)}
            </option>
          ))}
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
                <td><span className={`elv-status elv-${l.status}`} title={l.status}>{decisionStatusLabel(l.status)}</span></td>
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
            <dt>状态</dt><dd title={selectedLog.status}>{decisionStatusLabel(selectedLog.status)}</dd>
            <dt>命中规则</dt><dd>{(selectedLog.matchedRules ?? []).join(', ') || '—'}</dd>
            <dt>动作</dt><dd>{(selectedLog.actionPlans ?? []).join(', ') || '—'}</dd>
            <dt>耗时</dt><dd>{selectedLog.durationMs != null ? `${selectedLog.durationMs}ms` : '—'}</dd>
            <dt>重试次数</dt><dd>{selectedLog.attempts ?? 0}</dd>
            <dt>错误</dt><dd>{selectedLog.error ?? '—'}</dd>
            <dt>时间</dt><dd>{selectedLog.time ?? '—'}</dd>
          </dl>
          {virtualSources(selectedLog.traceSnapshot).length ? (
            <section className="elv-trace-section" data-testid="elv-virtual-sources">
              <h5>虚拟源</h5>
              {virtualSources(selectedLog.traceSnapshot).map((source, index) => (
                <article
                  key={`${source.sourceRef ?? source.modelCode ?? index}-${index}`}
                  className="elv-virtual-source"
                  data-testid={`elv-virtual-source-${index}`}
                >
                  <div className="elv-virtual-source-head">
                    <strong>{source.sourceRef ?? source.modelCode ?? '—'}</strong>
                    <span>{source.status ?? 'UNKNOWN'}</span>
                  </div>
                  <dl>
                    <dt>sourceRef</dt><dd className="mono">{source.sourceRef ?? '—'}</dd>
                    <dt>model</dt><dd>{source.modelCode ?? '—'}</dd>
                    <dt>record</dt><dd className="mono">{source.recordId ?? '—'}</dd>
                    {source.reason ? <><dt>原因</dt><dd>{source.reason}</dd></> : null}
                  </dl>
                  {fieldEntries(source.fields).length ? (
                    <dl className="elv-trace-fields">
                      {fieldEntries(source.fields).map(([key, value]) => (
                        <div key={key}>
                          <dt>{key}</dt>
                          <dd>{displayValue(value)}</dd>
                        </div>
                      ))}
                    </dl>
                  ) : null}
                </article>
              ))}
            </section>
          ) : null}
          {unknownReasons(selectedLog.traceSnapshot).length ? (
            <section className="elv-trace-section" data-testid="elv-unknown-reasons">
              <h5>未知原因</h5>
              <ul>
                {unknownReasons(selectedLog.traceSnapshot).map((reason) => (
                  <li key={reason}>{reason}</li>
                ))}
              </ul>
            </section>
          ) : null}
        </aside>
      )}
    </div>
  );
}

export default ExecutionLogViewer;
