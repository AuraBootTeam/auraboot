import React from 'react';
import { Activity, Search } from 'lucide-react';
import {
  BlockShell,
  EmptyBlock,
  ErrorBlock,
  LoadingBlock,
  StatusBadge,
  formatDateTime,
  recordsFrom,
  useDecisionOpsGet,
} from './DecisionOpsBlockUtils';

interface DecisionLog {
  pid?: string;
  traceId?: string;
  decisionCode?: string;
  status?: string;
  matched?: boolean;
  latencyMs?: number;
  createdAt?: string;
}

export function ExecutionLogTraceBlock() {
  const [traceId, setTraceId] = React.useState('');
  const normalizedTraceId = traceId.trim();
  const state = useDecisionOpsGet<unknown>(
    '/decision/logs',
    { traceId: normalizedTraceId },
    { enabled: Boolean(normalizedTraceId) },
  );
  const logs = React.useMemo(() => recordsFrom<DecisionLog>(state.data), [state.data]);

  return (
    <BlockShell
      title="决策执行 Trace"
      description="按 traceId 查询决策运行日志，查看命中状态、耗时和可解释性线索。"
      action={
        <div className="relative">
          <Search className="pointer-events-none absolute top-2 left-2.5 h-4 w-4 text-slate-400" />
          <input
            className="h-8 w-64 rounded-md border border-slate-200 pr-3 pl-8 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
            placeholder="输入 traceId"
            value={traceId}
            onChange={(event) => setTraceId(event.target.value)}
          />
        </div>
      }
    >
      {!normalizedTraceId ? (
        <EmptyBlock message="输入 traceId 后查询执行日志。当前后端仅提供按 traceId 查询，不默认拉取全量日志。" />
      ) : state.loading ? (
        <LoadingBlock label="正在加载执行日志" />
      ) : state.error ? (
        <ErrorBlock message={state.error} />
      ) : logs.length === 0 ? (
        <EmptyBlock message="暂无执行日志。完成一次规则评估后可在这里按 traceId 追踪。" />
      ) : (
        <div className="overflow-hidden rounded-md border border-slate-200">
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50 text-left text-xs font-semibold text-slate-500 uppercase">
              <tr>
                <th className="px-3 py-2">Trace</th>
                <th className="px-3 py-2">决策</th>
                <th className="px-3 py-2">状态</th>
                <th className="px-3 py-2">耗时</th>
                <th className="px-3 py-2">时间</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 bg-white">
              {logs.map((log) => (
                <tr key={log.pid || `${log.traceId}-${log.decisionCode}`}>
                  <td className="px-3 py-2 font-mono text-xs text-slate-700">
                    {log.traceId || '-'}
                  </td>
                  <td className="px-3 py-2 text-slate-900">{log.decisionCode || '-'}</td>
                  <td className="px-3 py-2">
                    <StatusBadge value={log.status || (log.matched ? 'MATCHED' : 'NOT_MATCHED')} />
                  </td>
                  <td className="px-3 py-2 text-slate-600">
                    <span className="inline-flex items-center gap-1">
                      <Activity className="h-3.5 w-3.5 text-slate-400" />
                      {log.latencyMs ?? '-'} ms
                    </span>
                  </td>
                  <td className="px-3 py-2 text-slate-500">{formatDateTime(log.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </BlockShell>
  );
}

export default ExecutionLogTraceBlock;
