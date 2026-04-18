/**
 * Mission Control — SkillDraft Review (Learning Loop HITL)
 *
 * Lists auto-generated Skill drafts from ab_agent_skill_draft (via
 * /api/learning/drafts) and lets an operator approve / reject / auto-
 * rename each. Matches the Learning Loop lifecycle:
 *
 *   DRAFT_PENDING_REVIEW  ─ approve ──→ REVIEWED_OK
 *                         ─ reject  ──→ REVIEWED_REJECTED
 *   PROMOTED_PENDING_HUMAN ─ approve ─→ ACTIVE
 *
 * Keeps it tight: a single page with a status filter, an expanded row
 * showing contract_yaml + shadow metrics, and two action buttons.
 * Full detail drill-down (shadow_run timeseries, pattern signature)
 * lands as an on-demand follow-up.
 */
import { useState, useEffect, useCallback } from 'react';
import { get, post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

interface DraftRow {
  pid: string;
  draft_skill_code: string;
  source_pattern_hash: string;
  status: string;
  reviewer_id?: number;
  review_comment?: string;
  created_at: string;
  reviewed_at?: string;
  shadow_started_at?: string;
  promoted_at?: string;
  shadow_metrics_json?: string;
}

interface ShadowRunRow {
  pid: string;
  original_run_id: string;
  shadow_status: string;
  shadow_duration_ms?: number;
  shadow_cost_usd?: number;
  original_duration_ms?: number;
  original_cost_usd?: number;
  output_match?: boolean;
  fidelity_match?: boolean;
  created_at: string;
}

interface DraftDetail extends DraftRow {
  contract_yaml: string;
  derived_from_runs_json?: string;
  source_pattern?: {
    invocation_count: number;
    success_rate: number;
    status: string;
  };
  recent_shadow_runs?: Array<{
    pid: string;
    shadow_status: string;
    output_match: boolean;
    fidelity_match: boolean;
    shadow_duration_ms?: number;
    original_duration_ms?: number;
  }>;
}

const STATUS_LABEL: Record<string, string> = {
  DRAFT_PENDING_REVIEW: '待审核',
  REVIEWED_OK: '审核通过',
  REVIEWED_REJECTED: '已驳回',
  SHADOW_RUNNING: '影子运行中',
  PROMOTED_PENDING_HUMAN: '待最终批准',
  ACTIVE: '已启用',
  DISCARDED: '已丢弃',
};

const STATUS_OPTIONS = [
  'DRAFT_PENDING_REVIEW',
  'REVIEWED_OK',
  'REVIEWED_REJECTED',
  'SHADOW_RUNNING',
  'PROMOTED_PENDING_HUMAN',
  'ACTIVE',
];

function statusColor(status: string): string {
  switch (status) {
    case 'DRAFT_PENDING_REVIEW':
    case 'PROMOTED_PENDING_HUMAN':
      return 'bg-yellow-100 text-yellow-800';
    case 'REVIEWED_OK':
    case 'ACTIVE':
      return 'bg-green-100 text-green-800';
    case 'REVIEWED_REJECTED':
    case 'DISCARDED':
      return 'bg-red-100 text-red-800';
    default:
      return 'bg-gray-100 text-gray-700';
  }
}

export default function LearningDraftsPage() {
  const [drafts, setDrafts] = useState<DraftRow[]>([]);
  const [statusFilter, setStatusFilter] = useState<string>('DRAFT_PENDING_REVIEW');
  const [loading, setLoading] = useState(false);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [detail, setDetail] = useState<DraftDetail | null>(null);
  const [comment, setComment] = useState('');
  const [actionInFlight, setActionInFlight] = useState<string | null>(null);
  const [toast, setToast] = useState<{ kind: 'ok' | 'err'; msg: string } | null>(null);
  const [shadowRuns, setShadowRuns] = useState<ShadowRunRow[] | null>(null);

  const fetchList = useCallback(async () => {
    setLoading(true);
    try {
      const qs = statusFilter ? `?status=${encodeURIComponent(statusFilter)}` : '';
      const r = await get(`/api/learning/drafts${qs}`);
      if (ResultHelper.isSuccess(r)) {
        setDrafts((r.data as DraftRow[]) ?? []);
      } else {
        setToast({ kind: 'err', msg: (r as any)?.message ?? '加载失败' });
      }
    } finally {
      setLoading(false);
    }
  }, [statusFilter]);

  useEffect(() => {
    fetchList();
  }, [fetchList]);

  const loadDetail = async (pid: string) => {
    const r = await get(`/api/learning/drafts/${pid}`);
    if (ResultHelper.isSuccess(r)) {
      setDetail(r.data as DraftDetail);
    } else {
      setDetail(null);
    }
  };

  const loadShadowRuns = async (pid: string) => {
    const r = await get(`/api/learning/drafts/${pid}/shadow-runs`);
    if (ResultHelper.isSuccess(r)) {
      setShadowRuns((r.data as ShadowRunRow[]) ?? []);
    } else {
      setShadowRuns([]);
    }
  };

  const toggleRow = async (pid: string) => {
    if (expanded === pid) {
      setExpanded(null);
      setDetail(null);
      setShadowRuns(null);
      setComment('');
      return;
    }
    setExpanded(pid);
    setDetail(null);
    setShadowRuns(null);
    await Promise.all([loadDetail(pid), loadShadowRuns(pid)]);
  };

  const review = async (pid: string, decision: 'approve' | 'reject') => {
    setActionInFlight(pid);
    try {
      const r = await post(`/api/learning/drafts/${pid}/review`, {
        decision,
        comment: comment.trim() || undefined,
      });
      if (ResultHelper.isSuccess(r)) {
        setToast({
          kind: 'ok',
          msg: `${decision === 'approve' ? '批准' : '驳回'}成功 → ${(r.data as any)?.status}`,
        });
        setComment('');
        setExpanded(null);
        setDetail(null);
        fetchList();
      } else {
        setToast({ kind: 'err', msg: (r as any)?.message ?? '操作失败' });
      }
    } finally {
      setActionInFlight(null);
    }
  };

  const evaluatePromotion = async (pid: string) => {
    setActionInFlight(pid);
    try {
      const r = await post(`/api/learning/drafts/${pid}/evaluate-promotion`, {});
      if (ResultHelper.isSuccess(r)) {
        const d = r.data as {
          decision: string;
          shadow_runs: number;
          output_match_rate: number;
          fidelity_match_rate: number;
        };
        const labelMap: Record<string, string> = {
          PROMOTE: '已晋升至人工最终审核',
          BELOW_THRESHOLD: '未达到阈值',
          INSUFFICIENT_RUNS: '影子运行次数不足',
          NOT_FOUND: '草稿未找到',
        };
        setToast({
          kind: d.decision === 'PROMOTE' ? 'ok' : 'err',
          msg: `${labelMap[d.decision] ?? d.decision}（runs=${d.shadow_runs}, match=${Math.round((d.output_match_rate ?? 0) * 100)}%）`,
        });
        fetchList();
        loadDetail(pid);
      } else {
        setToast({ kind: 'err', msg: (r as any)?.message ?? '评估失败' });
      }
    } finally {
      setActionInFlight(null);
    }
  };

  const autoRename = async (pid: string) => {
    setActionInFlight(pid);
    try {
      const r = await post(`/api/learning/drafts/${pid}/auto-rename`, {});
      if (ResultHelper.isSuccess(r)) {
        const d = r.data as { renamed: boolean; new_code?: string };
        if (d.renamed) {
          setToast({ kind: 'ok', msg: `已重命名 → ${d.new_code}` });
          fetchList();
          loadDetail(pid);
        } else {
          setToast({ kind: 'err', msg: '重命名未生效(LLM 未配置或提议不合规)' });
        }
      }
    } finally {
      setActionInFlight(null);
    }
  };

  return (
    <div className="p-6 max-w-6xl mx-auto" data-testid="learning-drafts-page">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Skill 草稿审核</h1>
        <div className="flex items-center gap-3">
          <label className="text-sm text-gray-600">状态</label>
          <select
            data-testid="status-filter"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="border border-gray-300 rounded px-2 py-1 text-sm"
          >
            <option value="">全部</option>
            {STATUS_OPTIONS.map((s) => (
              <option key={s} value={s}>
                {STATUS_LABEL[s] ?? s}
              </option>
            ))}
          </select>
          <button
            onClick={fetchList}
            className="text-sm px-3 py-1 rounded border border-gray-300 hover:bg-gray-50"
          >
            刷新
          </button>
        </div>
      </div>

      {toast && (
        <div
          className={`mb-3 px-3 py-2 rounded text-sm ${
            toast.kind === 'ok' ? 'bg-green-50 text-green-800' : 'bg-red-50 text-red-800'
          }`}
          data-testid="toast"
          onClick={() => setToast(null)}
        >
          {toast.msg}
        </div>
      )}

      {loading && <div className="text-sm text-gray-500">加载中…</div>}
      {!loading && drafts.length === 0 && (
        <div className="text-sm text-gray-500 border border-dashed rounded p-6 text-center">
          当前筛选下暂无草稿。
        </div>
      )}

      <ul className="space-y-2" data-testid="drafts-list">
        {drafts.map((d) => {
          const isExpanded = expanded === d.pid;
          const canApprove =
            d.status === 'DRAFT_PENDING_REVIEW' || d.status === 'PROMOTED_PENDING_HUMAN';
          const canReject = d.status !== 'REVIEWED_REJECTED' && d.status !== 'DISCARDED' && d.status !== 'ACTIVE';
          return (
            <li
              key={d.pid}
              className="border border-gray-200 rounded bg-white"
              data-testid={`draft-${d.pid}`}
            >
              <button
                onClick={() => toggleRow(d.pid)}
                className="w-full px-4 py-3 flex items-center justify-between text-left hover:bg-gray-50"
              >
                <div className="flex items-center gap-3 min-w-0">
                  <span
                    className={`inline-block text-xs px-2 py-0.5 rounded ${statusColor(d.status)}`}
                  >
                    {STATUS_LABEL[d.status] ?? d.status}
                  </span>
                  <span className="font-mono text-sm truncate" data-testid="draft-code">
                    {d.draft_skill_code}
                  </span>
                </div>
                <span className="text-xs text-gray-500">{d.created_at?.slice(0, 16).replace('T', ' ')}</span>
              </button>

              {isExpanded && (
                <div className="border-t border-gray-200 p-4 space-y-3" data-testid="draft-detail">
                  {!detail && <div className="text-sm text-gray-500">加载详情…</div>}
                  {detail && (
                    <>
                      {detail.source_pattern && (
                        <div className="text-xs text-gray-600">
                          来源模式:调用 {detail.source_pattern.invocation_count} 次,成功率{' '}
                          {(detail.source_pattern.success_rate * 100).toFixed(0)}%
                        </div>
                      )}

                      <div>
                        <div className="text-xs font-medium text-gray-700 mb-1">Contract YAML</div>
                        <pre
                          className="text-xs bg-gray-50 border border-gray-200 rounded p-2 overflow-x-auto"
                          data-testid="contract-yaml"
                        >
                          {detail.contract_yaml}
                        </pre>
                      </div>

                      {shadowRuns !== null && (
                        <div data-testid="shadow-runs-section">
                          <div className="text-xs font-medium text-gray-700 mb-1">
                            影子运行历史({shadowRuns.length})
                          </div>
                          {shadowRuns.length === 0 ? (
                            <div className="text-xs text-gray-500 italic">
                              暂无影子运行 — ShadowRunScheduler 尚未为此草稿重放原始调用
                            </div>
                          ) : (
                            <table className="text-xs w-full" data-testid="shadow-runs-table">
                              <thead>
                                <tr className="text-left text-gray-500 border-b border-gray-200">
                                  <th className="px-1 py-1">时间</th>
                                  <th className="px-1 py-1">状态</th>
                                  <th className="px-1 py-1">输出匹配</th>
                                  <th className="px-1 py-1">Fidelity</th>
                                  <th className="px-1 py-1 text-right">耗时差 ms</th>
                                  <th className="px-1 py-1 text-right">成本差 $</th>
                                </tr>
                              </thead>
                              <tbody>
                                {shadowRuns.slice(0, 20).map((r) => {
                                  const durDelta =
                                    r.shadow_duration_ms != null && r.original_duration_ms != null
                                      ? r.shadow_duration_ms - r.original_duration_ms
                                      : null;
                                  const costDelta =
                                    r.shadow_cost_usd != null && r.original_cost_usd != null
                                      ? r.shadow_cost_usd - r.original_cost_usd
                                      : null;
                                  return (
                                    <tr
                                      key={r.pid}
                                      className="border-b border-gray-100 align-top"
                                      data-testid={`shadow-run-${r.pid}`}
                                    >
                                      <td className="px-1 py-1 text-gray-500 whitespace-nowrap">
                                        {r.created_at?.slice(5, 16).replace('T', ' ')}
                                      </td>
                                      <td className="px-1 py-1">{r.shadow_status}</td>
                                      <td
                                        className={`px-1 py-1 ${r.output_match ? 'text-green-700' : 'text-red-700'}`}
                                      >
                                        {r.output_match ? '✓' : '✗'}
                                      </td>
                                      <td
                                        className={`px-1 py-1 ${r.fidelity_match ? 'text-green-700' : 'text-amber-700'}`}
                                      >
                                        {r.fidelity_match ? '✓' : '✗'}
                                      </td>
                                      <td
                                        className={`px-1 py-1 text-right tabular-nums ${
                                          durDelta == null
                                            ? 'text-gray-400'
                                            : durDelta > 0
                                              ? 'text-amber-700'
                                              : 'text-green-700'
                                        }`}
                                      >
                                        {durDelta == null ? '-' : (durDelta > 0 ? `+${durDelta}` : durDelta)}
                                      </td>
                                      <td
                                        className={`px-1 py-1 text-right tabular-nums ${
                                          costDelta == null
                                            ? 'text-gray-400'
                                            : costDelta > 0
                                              ? 'text-amber-700'
                                              : 'text-green-700'
                                        }`}
                                      >
                                        {costDelta == null
                                          ? '-'
                                          : costDelta > 0
                                            ? `+${costDelta.toFixed(4)}`
                                            : costDelta.toFixed(4)}
                                      </td>
                                    </tr>
                                  );
                                })}
                              </tbody>
                            </table>
                          )}
                        </div>
                      )}

                      <div className="pt-2 border-t border-gray-100 space-y-2">
                        <textarea
                          placeholder="审核意见(可选)"
                          value={comment}
                          onChange={(e) => setComment(e.target.value)}
                          className="w-full border border-gray-300 rounded text-sm p-2 resize-none"
                          rows={2}
                          data-testid="review-comment"
                        />
                        <div className="flex items-center gap-2">
                          {canApprove && (
                            <button
                              onClick={() => review(d.pid, 'approve')}
                              disabled={actionInFlight === d.pid}
                              data-testid="approve-btn"
                              className="px-3 py-1 text-sm rounded bg-green-600 text-white hover:bg-green-700 disabled:opacity-50"
                            >
                              批准
                            </button>
                          )}
                          {canReject && (
                            <button
                              onClick={() => review(d.pid, 'reject')}
                              disabled={actionInFlight === d.pid}
                              data-testid="reject-btn"
                              className="px-3 py-1 text-sm rounded bg-red-600 text-white hover:bg-red-700 disabled:opacity-50"
                            >
                              驳回
                            </button>
                          )}
                          {d.draft_skill_code?.startsWith('auto.') && (
                            <button
                              onClick={() => autoRename(d.pid)}
                              disabled={actionInFlight === d.pid}
                              data-testid="rename-btn"
                              className="px-3 py-1 text-sm rounded border border-gray-300 hover:bg-gray-50 disabled:opacity-50"
                            >
                              让 LLM 命名
                            </button>
                          )}
                          {(d.status === 'REVIEWED_OK' || d.status === 'SHADOW_RUNNING') && (
                            <button
                              onClick={() => evaluatePromotion(d.pid)}
                              disabled={actionInFlight === d.pid}
                              data-testid="evaluate-promotion-btn"
                              className="px-3 py-1 text-sm rounded border border-blue-300 text-blue-700 hover:bg-blue-50 disabled:opacity-50"
                            >
                              评估晋升
                            </button>
                          )}
                        </div>
                      </div>
                    </>
                  )}
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
