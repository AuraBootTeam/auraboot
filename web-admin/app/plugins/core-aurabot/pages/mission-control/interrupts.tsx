/**
 * Mission Control — Interrupt audit log (PR-38)
 *
 * Shows tenant-wide interrupt classifications from ab_agent_interrupt_log
 * (via GET /api/aurabot/sessions/interrupts). Operators use this to
 * spot runs being frequently replaced vs. appended, and to sanity-check
 * the classifier's sub_policy + confidence outputs in production.
 *
 * Read-only view. No actions — the classifier runs at ingest time and
 * the log is immutable.
 */
import { useCallback, useEffect, useState } from 'react';
import { get } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

interface InterruptRow {
  pid: string;
  session_id: string;
  active_run_id?: string;
  new_message_excerpt: string;
  sub_policy: string;
  classifier_tier: string;
  confidence: number;
  reason: string;
  action_taken: string;
  created_at: string;
}

const POLICY_LABEL: Record<string, string> = {
  replace_intent: '替换意图',
  append_context: '追加上下文',
  insert_subtask: '插入子任务',
};

const POLICY_OPTIONS = ['replace_intent', 'append_context', 'insert_subtask'];

function policyColor(policy: string): string {
  switch (policy) {
    case 'replace_intent':
      return 'bg-red-100 text-red-800';
    case 'insert_subtask':
      return 'bg-blue-100 text-blue-800';
    case 'append_context':
      return 'bg-gray-100 text-gray-800';
    default:
      return 'bg-gray-100 text-gray-600';
  }
}

export default function InterruptsPage() {
  const [rows, setRows] = useState<InterruptRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [policyFilter, setPolicyFilter] = useState('');

  const fetchRows = useCallback(async () => {
    setLoading(true);
    try {
      const q = policyFilter ? `?subPolicy=${policyFilter}` : '';
      const r = await get(`/api/aurabot/sessions/interrupts${q}`);
      if (ResultHelper.isSuccess(r)) {
        setRows((r.data as InterruptRow[]) ?? []);
      }
    } finally {
      setLoading(false);
    }
  }, [policyFilter]);

  useEffect(() => {
    fetchRows();
  }, [fetchRows]);

  return (
    <div className="p-6 max-w-6xl mx-auto" data-testid="interrupts-page">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">中断审计</h1>
        <div className="flex items-center gap-3">
          <label className="text-sm text-gray-600">策略</label>
          <select
            data-testid="policy-filter"
            value={policyFilter}
            onChange={(e) => setPolicyFilter(e.target.value)}
            className="border border-gray-300 rounded px-2 py-1 text-sm"
          >
            <option value="">全部</option>
            {POLICY_OPTIONS.map((p) => (
              <option key={p} value={p}>
                {POLICY_LABEL[p] ?? p}
              </option>
            ))}
          </select>
          <button
            onClick={fetchRows}
            className="text-sm px-3 py-1 rounded border border-gray-300 hover:bg-gray-50"
          >
            刷新
          </button>
        </div>
      </div>

      {loading && <div className="text-sm text-gray-500">加载中...</div>}

      {!loading && rows.length === 0 && (
        <div
          className="text-sm text-gray-500 border border-dashed border-gray-300 rounded p-6 text-center"
          data-testid="empty-state"
        >
          暂无中断记录 — 会话侧收到新消息时，分类器会在此留下记录。
        </div>
      )}

      {!loading && rows.length > 0 && (
        <table
          className="w-full text-sm border-collapse"
          data-testid="interrupts-table"
        >
          <thead>
            <tr className="border-b border-gray-200 text-left text-gray-600">
              <th className="py-2 pr-3">时间</th>
              <th className="py-2 pr-3">会话</th>
              <th className="py-2 pr-3">策略</th>
              <th className="py-2 pr-3">消息摘要</th>
              <th className="py-2 pr-3">分类器</th>
              <th className="py-2 pr-3">置信度</th>
              <th className="py-2 pr-3">动作</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr
                key={r.pid}
                className="border-b border-gray-100 align-top"
                data-testid={`interrupt-${r.pid}`}
              >
                <td className="py-2 pr-3 whitespace-nowrap text-gray-500">
                  {new Date(r.created_at).toLocaleString()}
                </td>
                <td className="py-2 pr-3 font-mono text-xs text-gray-700">
                  {r.session_id?.slice(0, 10)}…
                </td>
                <td className="py-2 pr-3">
                  <span
                    className={`inline-block px-2 py-0.5 text-xs rounded ${policyColor(r.sub_policy)}`}
                    data-testid="policy-badge"
                  >
                    {POLICY_LABEL[r.sub_policy] ?? r.sub_policy}
                  </span>
                </td>
                <td className="py-2 pr-3 max-w-xs truncate" title={r.new_message_excerpt}>
                  {r.new_message_excerpt}
                </td>
                <td className="py-2 pr-3 text-xs text-gray-600">{r.classifier_tier}</td>
                <td className="py-2 pr-3 text-xs tabular-nums">
                  {Math.round((r.confidence ?? 0) * 100)}%
                </td>
                <td className="py-2 pr-3 text-xs text-gray-600">{r.action_taken}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
