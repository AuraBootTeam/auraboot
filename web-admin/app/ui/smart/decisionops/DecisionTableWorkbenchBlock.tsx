import React from 'react';
import { Play, Save, Table2 } from 'lucide-react';
import { DecisionTableEditor } from '~/shared/decision/ui/DecisionTableEditor';
import {
  evaluateTablePreview,
  validateTable,
  type DecisionTable,
} from '~/shared/decision/table/decisionTable';
import { BlockShell, StatusBadge } from './DecisionOpsBlockUtils';

const INITIAL_TABLE: DecisionTable = {
  hitPolicy: 'FIRST',
  inputs: [
    { id: 'priority', label: '优先级', scope: 'record', path: 'priority', dataType: 'string' },
    { id: 'amount', label: '金额', scope: 'record', path: 'amount', dataType: 'decimal' },
  ],
  outputs: [
    { id: 'result', label: '结果', dataType: 'string' },
    { id: 'deadlineHours', label: '时限(小时)', dataType: 'decimal' },
  ],
  rules: [
    {
      ruleId: 'row-1',
      priority: 10,
      when: {
        priority: { operator: 'EQ', value: 'HIGH' },
      },
      then: { result: 'ESCALATE', deadlineHours: 4 },
    },
  ],
  defaultOutput: { result: 'NORMAL', deadlineHours: 24 },
};

export function DecisionTableWorkbenchBlock() {
  const [table, setTable] = React.useState<DecisionTable>(INITIAL_TABLE);
  const [sample, setSample] = React.useState('{"record":{"priority":"HIGH","amount":1200}}');
  const [preview, setPreview] = React.useState(() =>
    evaluateTablePreview(INITIAL_TABLE, { record: { priority: 'HIGH', amount: 1200 } }),
  );
  const validation = React.useMemo(() => validateTable(table), [table]);

  const runPreview = () => {
    try {
      const parsed = JSON.parse(sample) as Record<string, Record<string, unknown>>;
      setPreview(evaluateTablePreview(table, parsed));
    } catch (error) {
      setPreview({
        status: 'ERROR',
        matchedRuleId: null,
        outputs: {},
        errors: [error instanceof Error ? error.message : 'Invalid JSON'],
      });
    }
  };

  return (
    <BlockShell
      title="DMN 决策表工作台"
      description="以 DSL 页面承载的 typed custom block。用于编辑规则行、命中策略，并做前端预览；后端 DMN 评估仍是权威。"
      action={
        <div className="flex gap-2">
          <button
            type="button"
            className="inline-flex items-center rounded-md border border-slate-200 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
            onClick={runPreview}
          >
            <Play className="mr-1.5 h-4 w-4" />
            预览
          </button>
          <button
            type="button"
            className="inline-flex items-center rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
            disabled
            title="保存到版本草稿由后续 DMN 深化切片接入"
          >
            <Save className="mr-1.5 h-4 w-4" />
            保存草稿
          </button>
        </div>
      }
    >
      <div className="grid gap-5 xl:grid-cols-[minmax(0,2fr)_minmax(320px,1fr)]">
        <div className="overflow-x-auto rounded-md border border-slate-200 p-4">
          <div className="mb-3 flex items-center gap-2 text-sm font-medium text-slate-700">
            <Table2 className="h-4 w-4 text-blue-600" />
            规则行
          </div>
          <DecisionTableEditor value={table} onChange={setTable} />
        </div>
        <aside className="space-y-4">
          <div className="rounded-md border border-slate-200 p-4">
            <label className="text-sm font-medium text-slate-700" htmlFor="decision-table-sample">
              测试上下文
            </label>
            <textarea
              id="decision-table-sample"
              className="mt-2 h-32 w-full rounded-md border border-slate-200 px-3 py-2 font-mono text-xs text-slate-800 focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
              value={sample}
              onChange={(event) => setSample(event.target.value)}
            />
          </div>
          <div className="rounded-md border border-slate-200 p-4">
            <div className="mb-3 flex items-center justify-between">
              <span className="text-sm font-medium text-slate-700">预览结果</span>
              <StatusBadge value={preview.status} />
            </div>
            <dl className="space-y-2 text-sm">
              <Row label="命中规则" value={preview.matchedRuleId || '-'} />
              <Row label="输出" value={<code>{JSON.stringify(preview.outputs)}</code>} />
            </dl>
            {preview.errors.length > 0 ? (
              <div className="mt-3 rounded border border-red-200 bg-red-50 p-2 text-xs text-red-700">
                {preview.errors.join('; ')}
              </div>
            ) : null}
          </div>
          <div className="rounded-md border border-slate-200 p-4">
            <div className="text-sm font-medium text-slate-700">结构校验</div>
            {validation.length === 0 ? (
              <p className="mt-2 text-sm text-emerald-700">当前表结构可预览。</p>
            ) : (
              <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-red-700">
                {validation.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            )}
          </div>
        </aside>
      </div>
    </BlockShell>
  );
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex justify-between gap-4 border-b border-slate-100 pb-2 last:border-0">
      <dt className="text-slate-500">{label}</dt>
      <dd className="text-right font-medium text-slate-900">{value}</dd>
    </div>
  );
}

export default DecisionTableWorkbenchBlock;
