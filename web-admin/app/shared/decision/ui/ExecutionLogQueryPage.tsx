import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import type { DecisionApi, DecisionLogRecord } from '../api/decisionApi';
import { ExecutionLogViewer, type ExecLogEntry, type ExecLogStatus } from './ExecutionLogViewer';

export interface ExecutionLogQueryPageProps {
  api: DecisionApi;
  initialLogs?: ExecLogEntry[];
}

function ruleLabel(value: unknown): string | null {
  if (typeof value === 'string') return value;
  if (!value || typeof value !== 'object') return null;
  const item = value as Record<string, unknown>;
  const candidate = item.ruleId ?? item.ruleCode ?? item.id ?? item.ruleName ?? item.name ?? item.reason;
  return typeof candidate === 'string' && candidate.trim() ? candidate : null;
}

function matchedRules(raw: unknown): string[] {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw.map(ruleLabel).filter((v): v is string => Boolean(v));
  if (typeof raw === 'object') {
    const obj = raw as Record<string, unknown>;
    if (Array.isArray(obj.rules)) return matchedRules(obj.rules);
    if (Array.isArray(obj.matchedRules)) return matchedRules(obj.matchedRules);
  }
  const single = ruleLabel(raw);
  return single ? [single] : [];
}

function toExecLogEntry(record: DecisionLogRecord): ExecLogEntry {
  return {
    traceId: record.traceId ?? '-',
    decisionCode: record.decisionCode,
    status: (record.status ?? 'UNKNOWN') as ExecLogStatus,
    matchedRules: matchedRules(record.matchedRulesJson),
    durationMs: record.durationMs,
    error: record.errorMessage,
    time: record.createdAt,
  };
}

export function ExecutionLogQueryPage({ api, initialLogs = [] }: ExecutionLogQueryPageProps) {
  const [traceId, setTraceId] = useState('');
  const [logs, setLogs] = useState<ExecLogEntry[]>(initialLogs);

  const queryMutation = useMutation({
    mutationFn: (value: string) => api.getLogs(value),
    onSuccess: (records) => setLogs((records ?? []).map(toExecLogEntry)),
  });

  const submit = () => {
    const value = traceId.trim();
    if (!value) return;
    queryMutation.mutate(value);
  };

  return (
    <div data-testid="execution-log-query-page">
      <div className="elq-toolbar">
        <input
          aria-label="log-trace-id"
          placeholder="traceId"
          value={traceId}
          onChange={(e) => setTraceId(e.target.value)}
        />
        <button
          type="button"
          data-testid="elq-fetch"
          disabled={!traceId.trim() || queryMutation.isPending}
          onClick={submit}
        >
          查询
        </button>
        {queryMutation.isPending ? <span data-testid="elq-loading">查询中...</span> : null}
        {queryMutation.isError ? <span data-testid="elq-error">日志查询失败</span> : null}
      </div>
      <ExecutionLogViewer logs={logs} />
    </div>
  );
}

export default ExecutionLogQueryPage;
