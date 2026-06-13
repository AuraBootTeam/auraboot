import React from 'react';
import { Database, GitBranch } from 'lucide-react';
import {
  BlockShell,
  EmptyBlock,
  ErrorBlock,
  LoadingBlock,
  formatFieldTypeLabel,
  recordsFrom,
  useDecisionOpsGet,
} from './DecisionOpsBlockUtils';

interface DecisionModelField {
  entityCode?: string;
  path?: string;
  label?: string;
  dataType?: string;
  refs?: number;
  masked?: boolean;
  permission?: string;
  decisionCodes?: string[] | string;
}

export function DecisionModelFieldCatalogBlock() {
  const state = useDecisionOpsGet<unknown>('/decision/model/fields', { page: 1, size: 8 });
  const rows = React.useMemo(() => recordsFrom<DecisionModelField>(state.data), [state.data]);

  return (
    <BlockShell
      title="决策字段目录"
      description="展示规则可引用字段、字段权限和影响面入口，用于避免规则条件与后端模型脱节。"
      action={
        <a
          className="inline-flex items-center rounded-md border border-slate-200 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
          href="/p/decisionops_definitions"
        >
          <GitBranch className="mr-1.5 h-4 w-4" />
          决策定义
        </a>
      }
    >
      {state.loading ? (
        <LoadingBlock label="正在加载字段目录" />
      ) : state.error ? (
        <ErrorBlock message={state.error} />
      ) : rows.length === 0 ? (
        <EmptyBlock message="暂无字段引用。发布决策版本后，字段引用会进入影响分析和字段目录。" />
      ) : (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          {rows.map((row) => (
            <div
              key={`${row.entityCode || '-'}:${row.path || row.label || '-'}`}
              className="rounded-md border border-slate-200 bg-slate-50 p-4"
            >
              <div className="flex items-start justify-between gap-3">
                <div>
                  <div className="text-sm font-semibold text-slate-950">{row.label || row.path}</div>
                  <div className="mt-1 text-xs text-slate-500">
                    {row.entityCode || '-'} · {row.path || '-'}
                  </div>
                </div>
                <Database className="h-4 w-4 text-blue-600" />
              </div>
              <div className="mt-3 flex flex-wrap gap-2 text-xs">
                <span className="rounded-full border border-slate-200 bg-white px-2 py-0.5 text-slate-600">
                  {formatFieldTypeLabel(row.dataType)}
                </span>
                <span className="rounded-full border border-slate-200 bg-white px-2 py-0.5 text-slate-600">
                  引用 {row.refs ?? 0}
                </span>
                {row.masked ? (
                  <span className="rounded-full border border-amber-200 bg-amber-50 px-2 py-0.5 text-amber-700">
                    已脱敏
                  </span>
                ) : null}
              </div>
              <div className="mt-3 truncate text-xs text-slate-500">
                {formatDecisionCodes(row.decisionCodes)}
              </div>
            </div>
          ))}
        </div>
      )}
    </BlockShell>
  );
}

function formatDecisionCodes(value: DecisionModelField['decisionCodes']): string {
  if (Array.isArray(value)) return value.length > 0 ? value.join(', ') : '未关联决策';
  if (typeof value === 'string' && value.trim()) return value;
  return '未关联决策';
}

export default DecisionModelFieldCatalogBlock;
