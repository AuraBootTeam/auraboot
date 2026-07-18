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
  recordPid?: string;
  status?: string;
  reason?: string;
  fields?: Record<string, unknown>;
}

export interface ExecTraceFactMetadata {
  scope?: string;
  path?: string;
  factKey?: string;
  label?: string;
  dataType?: string;
  modelCode?: string;
  sourceRef?: string;
  dictCode?: string;
  valueLabels?: Record<string, string>;
  masked?: boolean;
}

export interface ExecTraceSnapshot {
  virtualSources?: ExecVirtualSourceTrace[];
  unknownReasons?: string[];
  factMetadata?: Record<string, ExecTraceFactMetadata>;
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

function factMetadata(snapshot: ExecLogEntry['traceSnapshot']): Record<string, ExecTraceFactMetadata> {
  const raw = asRecord(snapshot)?.factMetadata;
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {};
  return Object.fromEntries(
    Object.entries(raw as Record<string, unknown>).filter(([, value]) => Boolean(asRecord(value))),
  ) as Record<string, ExecTraceFactMetadata>;
}

type FactMetadataRow = {
  key: string;
  aliases: Set<string>;
  metadata: ExecTraceFactMetadata;
};

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : undefined;
}

function factAliases(key: string): string[] {
  const cleaned = key
    .split('.')
    .filter((part) => part.trim().length > 0)
    .join('.');
  if (!cleaned) return [];
  const aliases = new Set<string>([cleaned]);
  const parts = cleaned.split('.');
  aliases.add(parts[parts.length - 1]);
  if (!cleaned.includes('.')) {
    aliases.add(`data.${cleaned}`);
    aliases.add(`record.data.${cleaned}`);
  } else if (cleaned.startsWith('data.')) {
    aliases.add(`record.${cleaned}`);
    aliases.add(cleaned.slice('data.'.length));
  } else if (cleaned.startsWith('record.')) {
    aliases.add(cleaned.slice('record.'.length));
  }
  return [...aliases];
}

function metadataAliases(key: string, metadata: ExecTraceFactMetadata): Set<string> {
  const aliases = new Set<string>();
  const add = (value?: string) => {
    if (!value) return;
    factAliases(value).forEach((alias) => aliases.add(alias));
  };
  add(key);
  add(stringValue(metadata.factKey));
  add(stringValue(metadata.path));
  const scope = stringValue(metadata.scope);
  const path = stringValue(metadata.path);
  if (scope && path) add(`${scope}.${path}`);
  if (!aliases.size) aliases.add(key);
  return aliases;
}

function mergeMetadata(
  current: ExecTraceFactMetadata,
  next: ExecTraceFactMetadata,
): ExecTraceFactMetadata {
  return {
    ...current,
    ...next,
    label: stringValue(current.label) ?? stringValue(next.label),
    factKey: stringValue(current.factKey) ?? stringValue(next.factKey),
    path: stringValue(current.path) ?? stringValue(next.path),
    scope: stringValue(current.scope) ?? stringValue(next.scope),
    modelCode: stringValue(current.modelCode) ?? stringValue(next.modelCode),
    sourceRef: stringValue(current.sourceRef) ?? stringValue(next.sourceRef),
    dictCode: stringValue(current.dictCode) ?? stringValue(next.dictCode),
    dataType: stringValue(current.dataType) ?? stringValue(next.dataType),
    valueLabels: {
      ...(next.valueLabels ?? {}),
      ...(current.valueLabels ?? {}),
    },
  };
}

function metadataRows(snapshot: ExecLogEntry['traceSnapshot']): FactMetadataRow[] {
  const rows: FactMetadataRow[] = [];
  const entries = Object.entries(factMetadata(snapshot)).sort(([left], [right]) => {
    const score = (key: string) => (key.includes('.') ? 0 : 1);
    return score(left) - score(right) || left.localeCompare(right);
  });
  for (const [key, metadata] of entries) {
    const aliases = metadataAliases(key, metadata);
    const existing = rows.find((row) => [...aliases].some((alias) => row.aliases.has(alias)));
    if (existing) {
      existing.aliases = new Set([...existing.aliases, ...aliases]);
      existing.metadata = mergeMetadata(existing.metadata, metadata);
      if (!existing.key.includes('.') && key.includes('.')) existing.key = key;
    } else {
      rows.push({ key, aliases, metadata });
    }
  }
  return rows.sort((left, right) =>
    metadataLabel(left).localeCompare(metadataLabel(right), 'zh-CN'),
  );
}

function metadataLabel(row: FactMetadataRow): string {
  return stringValue(row.metadata.label) ?? row.key;
}

function metadataPath(row: FactMetadataRow): string {
  const factKey = stringValue(row.metadata.factKey);
  if (factKey) return factKey;
  const scope = stringValue(row.metadata.scope);
  const path = stringValue(row.metadata.path);
  if (scope && path) return `${scope}.${path}`;
  return path ?? row.key;
}

function metadataBadges(row: FactMetadataRow): string[] {
  return [
    row.metadata.modelCode ? `模型 ${row.metadata.modelCode}` : '',
    row.metadata.dataType ? `类型 ${row.metadata.dataType}` : '',
    row.metadata.dictCode ? `字典 ${row.metadata.dictCode}` : '',
    row.metadata.sourceRef ? `来源 ${row.metadata.sourceRef}` : '',
    row.metadata.masked ? '已脱敏' : '',
  ].filter((item) => item.trim().length > 0);
}

function metadataValueLabels(row: FactMetadataRow): Array<[string, string]> {
  const labels = row.metadata.valueLabels;
  if (!labels || typeof labels !== 'object' || Array.isArray(labels)) return [];
  return Object.entries(labels)
    .filter(
      ([value, label]) =>
        typeof value === 'string' &&
        value.trim().length > 0 &&
        typeof label === 'string' &&
        label.trim().length > 0,
    )
    .sort(([left], [right]) => left.localeCompare(right));
}

function metadataForKey(
  snapshot: ExecLogEntry['traceSnapshot'],
  key: string,
): ExecTraceFactMetadata | undefined {
  const metadata = factMetadata(snapshot);
  return factAliases(key).map((alias) => metadata[alias]).find(Boolean);
}

function fieldLabel(snapshot: ExecLogEntry['traceSnapshot'], key: string): string {
  const label = metadataForKey(snapshot, key)?.label;
  return typeof label === 'string' && label.trim() ? label : key;
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

function displayFieldValue(snapshot: ExecLogEntry['traceSnapshot'], key: string, value: unknown): string {
  if (typeof value !== 'string') return displayValue(value);
  const label = metadataForKey(snapshot, key)?.valueLabels?.[value];
  return typeof label === 'string' && label.trim() ? label : displayValue(value);
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
                    <dt>record</dt><dd className="mono">{source.recordPid ?? '—'}</dd>
                    {source.reason ? <><dt>原因</dt><dd>{source.reason}</dd></> : null}
                  </dl>
                  {fieldEntries(source.fields).length ? (
                    <dl className="elv-trace-fields">
                      {fieldEntries(source.fields).map(([key, value]) => (
                        <div key={key}>
                          <dt>{fieldLabel(selectedLog.traceSnapshot, key)}</dt>
                          <dd>{displayFieldValue(selectedLog.traceSnapshot, key, value)}</dd>
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
          {metadataRows(selectedLog.traceSnapshot).length ? (
            <section className="elv-trace-section" data-testid="elv-fact-metadata">
              <h5>事实快照</h5>
              <div className="elv-fact-list">
                {metadataRows(selectedLog.traceSnapshot).map((row) => (
                  <article className="elv-fact-card" key={metadataPath(row)}>
                    <strong>{metadataLabel(row)}</strong>
                    <span className="mono">{metadataPath(row)}</span>
                    {metadataBadges(row).length ? (
                      <small>{metadataBadges(row).join(' / ')}</small>
                    ) : null}
                    {metadataValueLabels(row).length ? (
                      <div>
                        {metadataValueLabels(row).map(([value, label]) => (
                          <span key={value}>
                            <code>{value}</code>
                            {label}
                          </span>
                        ))}
                      </div>
                    ) : null}
                  </article>
                ))}
              </div>
            </section>
          ) : null}
        </aside>
      )}
    </div>
  );
}

export default ExecutionLogViewer;
