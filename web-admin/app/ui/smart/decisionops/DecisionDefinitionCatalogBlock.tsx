import React from 'react';
import { GitBranch, Plus, Table2 } from 'lucide-react';
import {
  BlockShell,
  EmptyBlock,
  ErrorBlock,
  LoadingBlock,
  StatusBadge,
  formatDateTime,
  formatScopeLabel,
  recordsFrom,
  useDecisionOpsGet,
} from './DecisionOpsBlockUtils';

interface DecisionDefinition {
  pid?: string;
  decisionCode?: string;
  decisionName?: string;
  scopeType?: string;
  ownerModule?: string;
  enabled?: boolean;
  updatedAt?: string;
}

export function DecisionDefinitionCatalogBlock() {
  const state = useDecisionOpsGet<unknown>('/decision/definitions', { page: 1, size: 5 });
  const rows = React.useMemo(() => recordsFrom<DecisionDefinition>(state.data), [state.data]);

  return (
    <BlockShell
      title="决策定义目录"
      description="规则中心的决策定义入口。复杂规则进入决策表工作台，发布风险从灰度治理页确认。"
      action={
        <>
          <a
            className="inline-flex items-center rounded-md border border-slate-200 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
            href="/p/decisionops_tables"
          >
            <Table2 className="mr-1.5 h-4 w-4" />
            决策表
          </a>
          <a
            className="inline-flex items-center rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
            href="/p/decisionops_definitions/new"
          >
            <Plus className="mr-1.5 h-4 w-4" />
            新建决策
          </a>
        </>
      }
    >
      {state.loading ? (
        <LoadingBlock label="正在加载决策定义" />
      ) : state.error ? (
        <ErrorBlock message={state.error} />
      ) : rows.length === 0 ? (
        <EmptyBlock message="暂无决策定义。优先从决策表工作台创建可评估的规则版本。" />
      ) : (
        <div className="overflow-hidden rounded-md border border-slate-200">
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50 text-left text-xs font-semibold text-slate-500 uppercase">
              <tr>
                <th className="px-3 py-2">决策编码</th>
                <th className="px-3 py-2">名称</th>
                <th className="px-3 py-2">范围</th>
                <th className="px-3 py-2">状态</th>
                <th className="px-3 py-2">更新时间</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 bg-white">
              {rows.map((row) => (
                <tr key={row.pid || row.decisionCode}>
                  <td className="px-3 py-2 font-medium text-slate-900">
                    <span className="inline-flex items-center gap-1.5">
                      <GitBranch className="h-3.5 w-3.5 text-slate-400" />
                      {row.decisionCode || '-'}
                    </span>
                  </td>
                  <td className="px-3 py-2 text-slate-700">{row.decisionName || '-'}</td>
                  <td className="px-3 py-2 text-slate-600">
                    {formatScopeLabel(row.scopeType)}
                  </td>
                  <td className="px-3 py-2">
                    <StatusBadge value={row.enabled === false ? 'DISABLED' : 'ENABLED'} />
                  </td>
                  <td className="px-3 py-2 text-slate-500">{formatDateTime(row.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </BlockShell>
  );
}

export default DecisionDefinitionCatalogBlock;
