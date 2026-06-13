import React from 'react';
import { GitCompare, ShieldCheck, Split } from 'lucide-react';
import {
  BlockShell,
  EmptyBlock,
  ErrorBlock,
  LoadingBlock,
  StatusBadge,
  formatDateTime,
  formatPercent,
  recordsFrom,
  useDecisionOpsGet,
} from './DecisionOpsBlockUtils';

interface RolloutPolicy {
  pid?: string;
  decisionCode?: string;
  baselineVersion?: number;
  candidateVersion?: number;
  status?: string;
  percentage?: number;
  routingKeyExpr?: string;
  startedAt?: string;
  updatedAt?: string;
}

export interface DecisionRolloutMonitorBlockProps {
  block?: {
    props?: {
      initialDecisionCode?: string;
    };
  };
}

export function DecisionRolloutMonitorBlock({ block }: DecisionRolloutMonitorBlockProps) {
  const initialDecisionCode = block?.props?.initialDecisionCode;
  const state = useDecisionOpsGet<unknown>('/decision/rollouts', {
    page: 1,
    size: 5,
    decisionCode: initialDecisionCode,
  });
  const rows = React.useMemo(() => recordsFrom<RolloutPolicy>(state.data), [state.data]);
  const active = rows.find((row) => row.status === 'ACTIVE') ?? rows[0];
  const totalTraffic = rows.reduce((sum, row) => sum + (Number(row.percentage) || 0), 0);

  return (
    <BlockShell
      title="灰度发布监控"
      description="展示决策版本分流、候选版本和最近灰度策略，用于发布前风险确认。"
      action={
        <a
          className="inline-flex items-center rounded-md border border-slate-200 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
          href="/p/decisionops_rollouts"
        >
          <Split className="mr-1.5 h-4 w-4" />
          发布治理
        </a>
      }
    >
      {state.loading ? (
        <LoadingBlock label="正在加载灰度策略" />
      ) : state.error ? (
        <ErrorBlock message={state.error} />
      ) : rows.length === 0 ? (
        <EmptyBlock message="暂无灰度策略。发布新版本前可在发布治理页创建分流策略。" />
      ) : (
        <div className="grid gap-4 lg:grid-cols-[1.1fr_1.9fr]">
          <div className="rounded-md border border-slate-200 bg-slate-50 p-4">
            <div className="flex items-center gap-2 text-sm font-medium text-slate-700">
              <ShieldCheck className="h-4 w-4 text-blue-600" />
              当前策略
            </div>
            <div className="mt-3 text-lg font-semibold text-slate-950">
              {active?.decisionCode || '-'}
            </div>
            <div className="mt-3 grid grid-cols-2 gap-3 text-sm">
              <Metric label="基线版本" value={active?.baselineVersion ?? '-'} />
              <Metric label="候选版本" value={active?.candidateVersion ?? '-'} />
              <Metric label="灰度比例" value={formatPercent(active?.percentage)} />
              <Metric label="策略数" value={rows.length} />
            </div>
            <div className="mt-4 flex items-center justify-between text-sm">
              <span className="text-slate-500">状态</span>
              <StatusBadge value={active?.status} />
            </div>
          </div>
          <div className="overflow-hidden rounded-md border border-slate-200">
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <thead className="bg-slate-50 text-left text-xs font-semibold text-slate-500 uppercase">
                <tr>
                  <th className="px-3 py-2">决策编码</th>
                  <th className="px-3 py-2">版本</th>
                  <th className="px-3 py-2">流量</th>
                  <th className="px-3 py-2">状态</th>
                  <th className="px-3 py-2">更新时间</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 bg-white">
                {rows.map((row) => (
                  <tr key={row.pid || `${row.decisionCode}-${row.candidateVersion}`}>
                    <td className="px-3 py-2 font-medium text-slate-900">
                      {row.decisionCode || '-'}
                    </td>
                    <td className="px-3 py-2 text-slate-600">
                      <span className="inline-flex items-center gap-1">
                        <GitCompare className="h-3.5 w-3.5 text-slate-400" />
                        {`v${row.baselineVersion ?? '-'} -> v${row.candidateVersion ?? '-'}`}
                      </span>
                    </td>
                    <td className="px-3 py-2 text-slate-600">{formatPercent(row.percentage)}</td>
                    <td className="px-3 py-2">
                      <StatusBadge value={row.status} />
                    </td>
                    <td className="px-3 py-2 text-slate-500">
                      {formatDateTime(row.updatedAt || row.startedAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="border-t border-slate-100 bg-slate-50 px-3 py-2 text-xs text-slate-500">
              最近 {rows.length} 条策略，当前样本灰度流量合计 {formatPercent(totalTraffic)}
            </div>
          </div>
        </div>
      )}
    </BlockShell>
  );
}

function Metric({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="rounded border border-slate-200 bg-white px-3 py-2">
      <div className="text-xs text-slate-500">{label}</div>
      <div className="mt-1 font-semibold text-slate-900">{value}</div>
    </div>
  );
}

export default DecisionRolloutMonitorBlock;
