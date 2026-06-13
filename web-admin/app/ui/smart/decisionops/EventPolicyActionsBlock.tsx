import React from 'react';
import { Bolt, FileText, Workflow } from 'lucide-react';
import {
  BlockShell,
  EmptyBlock,
  ErrorBlock,
  LoadingBlock,
  StatusBadge,
  formatEventTypeLabel,
  formatTargetTypeLabel,
  recordsFrom,
  useDecisionOpsGet,
} from './DecisionOpsBlockUtils';

interface PolicyDefinition {
  pid?: string;
  policyCode?: string;
  policyName?: string;
  eventType?: string;
  targetType?: string;
  targetKey?: string;
  enabled?: boolean;
  status?: string;
}

export function EventPolicyActionsBlock() {
  const state = useDecisionOpsGet<unknown>('/event-policy/definitions', {
    eventType: 'FORM_SUBMITTED',
    targetType: 'FORM',
    targetKey: 'complaint',
  });
  const policies = React.useMemo(() => recordsFrom<PolicyDefinition>(state.data), [state.data]);
  const enabled = policies.filter((policy) => policy.enabled !== false).length;

  return (
    <BlockShell
      title="Event Policy 快速视图"
      description="规则中心统一承载事件策略入口；高级设计继续复用自动化 / BPM / 规则运行时能力。"
      action={
        <div className="flex gap-2">
          <a
            className="inline-flex items-center rounded-md border border-slate-200 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
            href="/automation"
          >
            <Workflow className="mr-1.5 h-4 w-4" />
            自动化
          </a>
          <a
            className="inline-flex items-center rounded-md border border-slate-200 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
            href="/p/decisionops_execution_logs"
          >
            <FileText className="mr-1.5 h-4 w-4" />
            日志
          </a>
        </div>
      }
    >
      {state.loading ? (
        <LoadingBlock label="正在加载事件策略" />
      ) : state.error ? (
        <ErrorBlock message={state.error} />
      ) : policies.length === 0 ? (
        <EmptyBlock message="暂无投诉表单事件策略。可从 Event Policy 或自动化设计器配置触发条件。" />
      ) : (
        <div className="grid gap-4 lg:grid-cols-[220px_1fr]">
          <div className="rounded-md border border-slate-200 bg-slate-50 p-4">
            <div className="flex items-center gap-2 text-sm font-medium text-slate-700">
              <Bolt className="h-4 w-4 text-blue-600" />
              策略概览
            </div>
            <div className="mt-4 grid grid-cols-2 gap-3">
              <Metric label="策略数" value={policies.length} />
              <Metric label="启用" value={enabled} />
            </div>
          </div>
          <div className="overflow-hidden rounded-md border border-slate-200">
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <thead className="bg-slate-50 text-left text-xs font-semibold text-slate-500 uppercase">
                <tr>
                  <th className="px-3 py-2">策略</th>
                  <th className="px-3 py-2">事件</th>
                  <th className="px-3 py-2">目标</th>
                  <th className="px-3 py-2">状态</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 bg-white">
                {policies.map((policy) => (
                  <tr key={policy.pid || policy.policyCode}>
                    <td className="px-3 py-2">
                      <div className="font-medium text-slate-900">{policy.policyName || '-'}</div>
                      <div className="text-xs text-slate-500">{policy.policyCode || '-'}</div>
                    </td>
                    <td className="px-3 py-2 text-slate-600">
                      {formatEventTypeLabel(policy.eventType)}
                    </td>
                    <td className="px-3 py-2 text-slate-600">
                      {[formatTargetTypeLabel(policy.targetType), policy.targetKey]
                        .filter(Boolean)
                        .join(':') || '-'}
                    </td>
                    <td className="px-3 py-2">
                      <StatusBadge
                        value={policy.status || (policy.enabled === false ? 'DISABLED' : 'ACTIVE')}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
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
      <div className="mt-1 text-lg font-semibold text-slate-900">{value}</div>
    </div>
  );
}

export default EventPolicyActionsBlock;
