/**
 * Mission Control — Memory Promotion review (PR-68 Phase 4)
 *
 * Three tabs:
 *   - Pending Review (DRAFT_PENDING_REVIEW) — reviewer focus mode, keyboard
 *     shortcuts (j/k/a/r/s/e), confidence bar, evidence list, batch approve
 *     drawer, PII warning banner.
 *   - Shadow Observation (PROMOTED_SHADOW) — countdown + retract modal.
 *   - Audit History (ACTIVE / REVIEWED_REJECTED / RETRACTED) — read-only table
 *     + provenance link.
 *
 * Backend: /api/memory/promotions/** (PR-67).
 * Design: docs/plans/2026-04/2026-04-18-memory-promotion-design.md §7.
 */
import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { get, post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';

// ---------------------------------------------------------------------------
// Types (mirror MemoryPromotionController responses)
// ---------------------------------------------------------------------------

interface SourceMemoryPidRef {
  pid: string;
}

interface PromotionRow {
  pid: string;
  tenant_id: number;
  source_scope: string;
  source_memory_pid: string | null;
  source_memory_pids: SourceMemoryPidRef[] | null;
  target_scope: string;
  category: string;
  proposed_title: string;
  proposed_content: string;
  proposed_importance: number;
  reason_code: string;
  reason_detail: Record<string, unknown>;
  confidence_score: number | null;
  similarity_score: number | null;
  ai_rationale: string | null;
  status: string;
  reviewer_id?: number;
  review_comment?: string;
  reject_reason?: string;
  promoted_memory_pid?: string;
  shadow_started_at?: string;
  shadow_ends_at?: string;
  activated_at?: string;
  created_at: string;
  reviewed_at?: string;
}

interface SourceMemory {
  pid: string;
  scope: string;
  scope_key: string | null;
  memory_title: string | null;
  memory_content: string | null;
  importance: number | null;
  created_at: string | null;
  author_user_id?: number | null;
  author_email?: string | null;
  author_user_name?: string | null;
}

interface ProvenancePayload {
  promotion: PromotionRow;
  source_memories: SourceMemory[];
  promoted_memory: {
    pid: string;
    memory_title: string | null;
    memory_content: string | null;
    shadow_mode: boolean;
    created_at: string | null;
  } | null;
  upstream_promotions: Array<{
    pid: string;
    status: string;
    source_scope: string;
    target_scope: string;
    category: string;
    proposed_title: string | null;
    created_at: string | null;
    activated_at: string | null;
  }>;
}

type Tab = 'pending' | 'shadow' | 'audit';

const REJECT_REASONS: Array<{ code: string; zh: string; en: string }> = [
  { code: 'too_specific', zh: '内容过于具体，不适合团队共享', en: 'Too specific — not suitable for team sharing' },
  { code: 'contains_pii', zh: '含有个人信息', en: 'Contains PII' },
  { code: 'outdated', zh: '已过时', en: 'Outdated' },
  { code: 'wrong', zh: '内容有误', en: 'Wrong / incorrect' },
  { code: 'duplicate', zh: '与已有记忆重复', en: 'Duplicate of existing memory' },
  { code: 'other', zh: '其他（必填 comment）', en: 'Other (comment required)' },
];

const BATCH_CONFIDENCE_FLOOR = 0.8;

// ---------------------------------------------------------------------------
// Utility components
// ---------------------------------------------------------------------------

function ConfidenceBar({ value }: { value: number | null }) {
  const pct = value == null ? 0 : Math.round(value * 100);
  const color =
    pct >= 80 ? 'bg-green-500' : pct >= 60 ? 'bg-amber-500' : 'bg-gray-400';
  return (
    <div className="flex items-center gap-2" data-testid="confidence-bar">
      <div className="h-2 w-24 overflow-hidden rounded bg-gray-100 dark:bg-gray-700">
        <div
          className={`h-full ${color}`}
          style={{ width: `${Math.max(2, pct)}%` }}
        />
      </div>
      <span className="text-xs tabular-nums text-gray-700 dark:text-gray-300" data-testid="confidence-value">
        {value == null ? '—' : value.toFixed(2)}
      </span>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const color =
    status === 'ACTIVE'
      ? 'bg-green-100 text-green-800'
      : status === 'PROMOTED_SHADOW'
        ? 'bg-blue-100 text-blue-800'
        : status === 'DRAFT_PENDING_REVIEW'
          ? 'bg-yellow-100 text-yellow-800'
          : status === 'REVIEWED_REJECTED'
            ? 'bg-red-100 text-red-800'
            : status === 'RETRACTED'
              ? 'bg-orange-100 text-orange-800'
              : 'bg-gray-100 text-gray-700';
  return (
    <span className={`inline-block rounded px-2 py-0.5 text-xs ${color}`} data-testid="status-badge">
      {status}
    </span>
  );
}

function formatRemaining(endIso: string | undefined, l: (zh: string, en: string) => string): string {
  if (!endIso) return '—';
  const end = new Date(endIso).getTime();
  const diff = end - Date.now();
  if (diff <= 0) return l('已到期', 'Expired');
  const hours = Math.floor(diff / 3_600_000);
  if (hours >= 24) {
    const days = Math.floor(hours / 24);
    const remH = hours - days * 24;
    return l(`还剩 ${days}天${remH}小时`, `${days}d ${remH}h remaining`);
  }
  return l(`还剩 ${hours}小时`, `${hours}h remaining`);
}

// ---------------------------------------------------------------------------
// Main page
// ---------------------------------------------------------------------------

export default function MemoryPromotionsPage() {
  const { locale } = useI18n();
  const l = useCallback(
    (zh: string, en: string) => (locale === 'zh-CN' ? zh : en),
    [locale],
  );

  const [activeTab, setActiveTab] = useState<Tab>('pending');
  const [toast, setToast] = useState<{ kind: 'ok' | 'err'; msg: string } | null>(null);

  return (
    <div className="p-6 max-w-6xl mx-auto" data-testid="memory-promotions-page">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">
          {l('记忆晋升审核', 'Memory Promotion Review')}
        </h1>
        <span
          className="text-xs text-gray-500"
          title={l(
            '快捷键: j/k 上下, a 批准, r 驳回, s 跳过, e 展开证据',
            'Shortcuts: j/k next/prev, a approve, r reject, s skip, e expand evidence',
          )}
          data-testid="shortcut-hint"
        >
          {l('键盘: j/k/a/r/s/e', 'Keys: j/k/a/r/s/e')}
        </span>
      </div>

      {/* Tab nav */}
      <div className="mb-4 flex border-b border-gray-200" data-testid="tabs">
        {([
          ['pending', l('待审核', 'Pending Review')],
          ['shadow', l('观察中', 'Shadow Observation')],
          ['audit', l('审计历史', 'Audit History')],
        ] as const).map(([key, label]) => (
          <button
            key={key}
            onClick={() => setActiveTab(key as Tab)}
            className={`border-b-2 px-4 py-2 text-sm font-medium ${
              activeTab === key
                ? 'border-blue-600 text-blue-600'
                : 'border-transparent text-gray-500 hover:text-gray-700'
            }`}
            data-testid={`tab-${key}`}
          >
            {label}
          </button>
        ))}
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

      {activeTab === 'pending' && <PendingTab l={l} setToast={setToast} />}
      {activeTab === 'shadow' && <ShadowTab l={l} setToast={setToast} />}
      {activeTab === 'audit' && <AuditTab l={l} />}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Pending tab
// ---------------------------------------------------------------------------

function PendingTab({
  l,
  setToast,
}: {
  l: (zh: string, en: string) => string;
  setToast: (t: { kind: 'ok' | 'err'; msg: string } | null) => void;
}) {
  const [rows, setRows] = useState<PromotionRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedIdx, setSelectedIdx] = useState(0);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const [rejectTarget, setRejectTarget] = useState<PromotionRow | null>(null);
  const [rejectReason, setRejectReason] = useState<string>('too_specific');
  const [rejectComment, setRejectComment] = useState('');
  const [batchOpen, setBatchOpen] = useState(false);
  const [batchComment, setBatchComment] = useState('');
  const [provenancePid, setProvenancePid] = useState<string | null>(null);
  const [inFlight, setInFlight] = useState<string | null>(null);

  const fetchList = useCallback(async () => {
    setLoading(true);
    try {
      const r = await get(
        '/api/memory/promotions?status=DRAFT_PENDING_REVIEW&sort=confidence_desc',
      );
      if (ResultHelper.isSuccess(r)) {
        setRows((r.data as PromotionRow[]) ?? []);
        setSelectedIdx(0);
      } else {
        setToast({ kind: 'err', msg: (r as any)?.message ?? l('加载失败', 'Failed to load') });
      }
    } finally {
      setLoading(false);
    }
  }, [l, setToast]);

  useEffect(() => {
    fetchList();
  }, [fetchList]);

  const approve = useCallback(
    async (pid: string, comment?: string) => {
      setInFlight(pid);
      try {
        const r = await post(`/api/memory/promotions/${pid}/review`, {
          decision: 'approve',
          comment: comment?.trim() || undefined,
        });
        if (ResultHelper.isSuccess(r)) {
          setToast({ kind: 'ok', msg: l('已批准，进入观察期', 'Approved — shadow observation started') });
          fetchList();
        } else {
          setToast({ kind: 'err', msg: (r as any)?.message ?? l('操作失败', 'Operation failed') });
        }
      } finally {
        setInFlight(null);
      }
    },
    [fetchList, l, setToast],
  );

  const submitReject = useCallback(
    async (pid: string, reason: string, comment: string) => {
      if (reason === 'other' && !comment.trim()) {
        setToast({ kind: 'err', msg: l('reason=other 必填 comment', 'reason=other requires a comment') });
        return;
      }
      setInFlight(pid);
      try {
        const r = await post(`/api/memory/promotions/${pid}/review`, {
          decision: 'reject',
          reject_reason: reason,
          comment: comment.trim() || undefined,
        });
        if (ResultHelper.isSuccess(r)) {
          setToast({ kind: 'ok', msg: l('已驳回', 'Rejected') });
          setRejectTarget(null);
          setRejectComment('');
          fetchList();
        } else {
          setToast({ kind: 'err', msg: (r as any)?.message ?? l('操作失败', 'Operation failed') });
        }
      } finally {
        setInFlight(null);
      }
    },
    [fetchList, l, setToast],
  );

  const submitBatch = useCallback(async () => {
    if (checked.size === 0) return;
    setInFlight('__batch__');
    try {
      const r = await post('/api/memory/promotions/batch-approve', {
        pids: [...checked],
        comment: batchComment.trim() || undefined,
      });
      if (ResultHelper.isSuccess(r)) {
        const d = r.data as { approved: string[]; failed: Array<{ pid: string; reason: string }> };
        setToast({
          kind: d.failed.length === 0 ? 'ok' : 'err',
          msg: l(
            `批准 ${d.approved.length} / 失败 ${d.failed.length}`,
            `${d.approved.length} approved / ${d.failed.length} failed`,
          ),
        });
        setChecked(new Set());
        setBatchOpen(false);
        setBatchComment('');
        fetchList();
      } else {
        setToast({ kind: 'err', msg: (r as any)?.message ?? l('批量失败', 'Batch failed') });
      }
    } finally {
      setInFlight(null);
    }
  }, [checked, batchComment, fetchList, l, setToast]);

  // Keyboard shortcuts
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      const tag = target?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target?.isContentEditable) {
        return;
      }
      if (rejectTarget || batchOpen || provenancePid) return; // modal open
      if (rows.length === 0) return;
      const current = rows[selectedIdx];
      if (!current) return;
      if (e.key === 'j') {
        e.preventDefault();
        setSelectedIdx((i) => Math.min(i + 1, rows.length - 1));
      } else if (e.key === 'k') {
        e.preventDefault();
        setSelectedIdx((i) => Math.max(i - 1, 0));
      } else if (e.key === 'a') {
        e.preventDefault();
        approve(current.pid);
      } else if (e.key === 'r') {
        e.preventDefault();
        setRejectTarget(current);
        setRejectReason('too_specific');
        setRejectComment('');
      } else if (e.key === 's') {
        e.preventDefault();
        setSelectedIdx((i) => Math.min(i + 1, rows.length - 1));
      } else if (e.key === 'e') {
        e.preventDefault();
        setExpanded((prev) => {
          const next = new Set(prev);
          if (next.has(current.pid)) next.delete(current.pid);
          else next.add(current.pid);
          return next;
        });
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [rows, selectedIdx, approve, rejectTarget, batchOpen, provenancePid]);

  if (loading) {
    return <div className="text-sm text-gray-500">{l('加载中…', 'Loading…')}</div>;
  }
  if (rows.length === 0) {
    return (
      <div
        className="text-sm text-gray-500 border border-dashed rounded p-6 text-center"
        data-testid="pending-empty"
      >
        {l(
          '暂无待审核晋升提案 — 自动提取器将在下一次运行后补充。',
          'No pending promotion proposals — the extractor will populate them on the next run.',
        )}
      </div>
    );
  }

  return (
    <div data-testid="pending-tab">
      {/* Batch bar */}
      {checked.size > 0 && (
        <div
          className="mb-3 flex items-center justify-between rounded border border-blue-200 bg-blue-50 px-3 py-2 text-sm"
          data-testid="batch-bar"
        >
          <span>
            {l(`已选 ${checked.size} 条`, `${checked.size} selected`)}
          </span>
          <div className="flex gap-2">
            <button
              onClick={() => setBatchOpen(true)}
              disabled={inFlight === '__batch__'}
              className="rounded bg-blue-600 px-3 py-1 text-white disabled:opacity-50"
              data-testid="batch-approve-btn"
            >
              {l('批量批准…', 'Batch approve…')}
            </button>
            <button
              onClick={() => setChecked(new Set())}
              className="rounded border border-gray-300 px-3 py-1"
              data-testid="batch-clear-btn"
            >
              {l('清空', 'Clear')}
            </button>
          </div>
        </div>
      )}

      <ul className="space-y-3" data-testid="pending-list">
        {rows.map((row, idx) => {
          const isSelected = idx === selectedIdx;
          const isExpanded = expanded.has(row.pid);
          const isChecked = checked.has(row.pid);
          const evidence: string[] =
            Array.isArray((row.reason_detail as { user_ids?: unknown })?.user_ids)
              ? ((row.reason_detail as { user_ids: string[] }).user_ids).map(String)
              : [];
          return (
            <li
              key={row.pid}
              className={`rounded border bg-white p-4 ${
                isSelected ? 'border-blue-500 ring-1 ring-blue-300' : 'border-gray-200'
              }`}
              data-testid={`promotion-${row.pid}`}
              data-selected={isSelected ? 'true' : 'false'}
            >
              <div className="flex items-start gap-3">
                <input
                  type="checkbox"
                  className="mt-1"
                  checked={isChecked}
                  onChange={(e) => {
                    const next = new Set(checked);
                    if (e.target.checked) next.add(row.pid);
                    else next.delete(row.pid);
                    setChecked(next);
                  }}
                  data-testid={`check-${row.pid}`}
                  aria-label="select for batch approve"
                />
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-3">
                    <ConfidenceBar value={row.confidence_score} />
                    <span
                      className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-700"
                      data-testid="category-badge"
                    >
                      {row.category}
                    </span>
                    <span className="text-xs text-gray-400" data-testid="reason-code">
                      {row.reason_code}
                    </span>
                  </div>

                  <div className="mt-2 font-medium text-gray-900" data-testid="proposed-title">
                    {row.proposed_title || l('（无标题）', '(no title)')}
                  </div>
                  <div className="mt-1 text-sm text-gray-700" data-testid="proposed-content">
                    {row.proposed_content}
                  </div>

                  {row.ai_rationale && (
                    <div className="mt-2 rounded border-l-2 border-indigo-300 bg-indigo-50 px-2 py-1 text-xs text-indigo-900" data-testid="ai-rationale">
                      <span className="font-medium">{l('AI 说明: ', 'AI rationale: ')}</span>
                      {row.ai_rationale}
                    </div>
                  )}

                  {isExpanded && evidence.length > 0 && (
                    <div className="mt-2 rounded border border-gray-200 bg-gray-50 p-2 text-xs" data-testid="evidence-list">
                      <div className="mb-1 text-gray-500">
                        {l('证据(来源用户)', 'Evidence (source users)')}
                      </div>
                      <ul className="list-disc pl-4 text-gray-700">
                        {evidence.map((u) => (
                          <li key={u}>user_id={u}</li>
                        ))}
                      </ul>
                    </div>
                  )}

                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    <button
                      onClick={() => approve(row.pid)}
                      disabled={inFlight === row.pid}
                      className="rounded bg-green-600 px-3 py-1 text-sm text-white hover:bg-green-700 disabled:opacity-50"
                      data-testid="approve-btn"
                      aria-label="approve promotion"
                    >
                      {l('批准 (a)', 'Approve (a)')}
                    </button>
                    <button
                      onClick={() => {
                        setRejectTarget(row);
                        setRejectReason('too_specific');
                        setRejectComment('');
                      }}
                      disabled={inFlight === row.pid}
                      className="rounded bg-red-600 px-3 py-1 text-sm text-white hover:bg-red-700 disabled:opacity-50"
                      data-testid="reject-btn"
                      aria-label="reject promotion"
                    >
                      {l('驳回 (r)', 'Reject (r)')}
                    </button>
                    <button
                      onClick={() => {
                        setExpanded((prev) => {
                          const next = new Set(prev);
                          if (next.has(row.pid)) next.delete(row.pid);
                          else next.add(row.pid);
                          return next;
                        });
                      }}
                      className="rounded border border-gray-300 px-3 py-1 text-sm hover:bg-gray-50"
                      data-testid="expand-btn"
                    >
                      {isExpanded ? l('收起 (e)', 'Collapse (e)') : l('展开证据 (e)', 'Expand evidence (e)')}
                    </button>
                    <button
                      onClick={() => setProvenancePid(row.pid)}
                      className="text-xs text-blue-600 hover:underline"
                      data-testid="provenance-link"
                    >
                      {l('查看溯源 →', 'View provenance →')}
                    </button>
                  </div>
                </div>
              </div>
            </li>
          );
        })}
      </ul>

      {/* PII warning banner (always visible inside approve-adjacent context) */}
      <PiiWarning l={l} />

      {rejectTarget && (
        <RejectModal
          l={l}
          promotion={rejectTarget}
          reason={rejectReason}
          onReasonChange={setRejectReason}
          comment={rejectComment}
          onCommentChange={setRejectComment}
          onCancel={() => setRejectTarget(null)}
          onSubmit={() => submitReject(rejectTarget.pid, rejectReason, rejectComment)}
          inFlight={inFlight === rejectTarget.pid}
        />
      )}

      {batchOpen && (
        <BatchApproveDrawer
          l={l}
          pids={[...checked]}
          rows={rows}
          comment={batchComment}
          onCommentChange={setBatchComment}
          onCancel={() => setBatchOpen(false)}
          onSubmit={submitBatch}
          inFlight={inFlight === '__batch__'}
        />
      )}

      {provenancePid && (
        <ProvenanceModal
          l={l}
          pid={provenancePid}
          onClose={() => setProvenancePid(null)}
        />
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Shadow tab
// ---------------------------------------------------------------------------

function ShadowTab({
  l,
  setToast,
}: {
  l: (zh: string, en: string) => string;
  setToast: (t: { kind: 'ok' | 'err'; msg: string } | null) => void;
}) {
  const [rows, setRows] = useState<PromotionRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [retractTarget, setRetractTarget] = useState<PromotionRow | null>(null);
  const [retractReason, setRetractReason] = useState('');
  const [inFlight, setInFlight] = useState<string | null>(null);
  const [provenancePid, setProvenancePid] = useState<string | null>(null);

  const fetchList = useCallback(async () => {
    setLoading(true);
    try {
      const r = await get('/api/memory/promotions?status=PROMOTED_SHADOW');
      if (ResultHelper.isSuccess(r)) {
        setRows((r.data as PromotionRow[]) ?? []);
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchList();
  }, [fetchList]);

  const retract = useCallback(
    async (pid: string, reason: string) => {
      if (!reason.trim()) {
        setToast({ kind: 'err', msg: l('请填写撤回原因', 'Please provide a retract reason') });
        return;
      }
      setInFlight(pid);
      try {
        const r = await post(`/api/memory/promotions/${pid}/retract`, {
          reason: reason.trim(),
        });
        if (ResultHelper.isSuccess(r)) {
          setToast({ kind: 'ok', msg: l('已撤回', 'Retracted') });
          setRetractTarget(null);
          setRetractReason('');
          fetchList();
        } else {
          setToast({ kind: 'err', msg: (r as any)?.message ?? l('撤回失败', 'Retract failed') });
        }
      } finally {
        setInFlight(null);
      }
    },
    [fetchList, l, setToast],
  );

  if (loading) return <div className="text-sm text-gray-500">{l('加载中…', 'Loading…')}</div>;
  if (rows.length === 0) {
    return (
      <div className="text-sm text-gray-500 border border-dashed rounded p-6 text-center" data-testid="shadow-empty">
        {l('暂无观察中的晋升记忆。', 'No memories currently in shadow observation.')}
      </div>
    );
  }

  return (
    <div data-testid="shadow-tab">
      <ul className="space-y-3" data-testid="shadow-list">
        {rows.map((row) => (
          <li
            key={row.pid}
            className="rounded border border-blue-200 bg-blue-50/30 p-4"
            data-testid={`shadow-${row.pid}`}
          >
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <StatusBadge status={row.status} />
                  <span className="text-xs text-gray-500" data-testid="shadow-countdown">
                    {formatRemaining(row.shadow_ends_at, l)}
                  </span>
                </div>
                <div className="mt-2 font-medium text-gray-900">{row.proposed_title}</div>
                <div className="mt-1 text-sm text-gray-700" data-testid="shadow-content">
                  {row.proposed_content}
                </div>
                <div className="mt-1 text-xs text-gray-500">
                  {l('审核人 id: ', 'Reviewer id: ')}
                  {row.reviewer_id ?? '—'} ·{' '}
                  {l('观察开始: ', 'Shadow start: ')}
                  {row.shadow_started_at?.slice(0, 16).replace('T', ' ') ?? '—'}
                </div>
              </div>
              <div className="flex flex-col gap-2">
                <button
                  onClick={() => {
                    setRetractTarget(row);
                    setRetractReason('');
                  }}
                  className="rounded bg-orange-600 px-3 py-1 text-sm text-white hover:bg-orange-700"
                  data-testid="retract-btn"
                  aria-label="retract promotion"
                >
                  {l('撤回', 'Retract')}
                </button>
                <button
                  onClick={() => setProvenancePid(row.pid)}
                  className="text-xs text-blue-600 hover:underline"
                  data-testid="provenance-link"
                >
                  {l('溯源 →', 'Provenance →')}
                </button>
              </div>
            </div>
          </li>
        ))}
      </ul>

      {retractTarget && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/30"
          data-testid="retract-modal"
        >
          <div className="w-[480px] rounded bg-white p-5">
            <h3 className="text-lg font-semibold">
              {l('撤回晋升记忆', 'Retract promoted memory')}
            </h3>
            <p className="mt-1 text-sm text-gray-600">
              {l(
                '撤回后对应的 tenant memory 将被软删除。请填写原因以便后续复盘。',
                'Retracting will soft-delete the tenant memory. Please provide a reason for auditing.',
              )}
            </p>
            <textarea
              value={retractReason}
              onChange={(e) => setRetractReason(e.target.value)}
              className="mt-3 w-full rounded border border-gray-300 p-2 text-sm"
              rows={3}
              placeholder={l('原因(必填)', 'Reason (required)')}
              data-testid="retract-reason"
            />
            <div className="mt-3 flex justify-end gap-2">
              <button
                onClick={() => setRetractTarget(null)}
                className="rounded border border-gray-300 px-3 py-1 text-sm"
                data-testid="retract-cancel"
              >
                {l('取消', 'Cancel')}
              </button>
              <button
                onClick={() => retract(retractTarget.pid, retractReason)}
                disabled={inFlight === retractTarget.pid}
                className="rounded bg-orange-600 px-3 py-1 text-sm text-white disabled:opacity-50"
                data-testid="retract-submit"
              >
                {l('确认撤回', 'Confirm retract')}
              </button>
            </div>
          </div>
        </div>
      )}

      {provenancePid && (
        <ProvenanceModal l={l} pid={provenancePid} onClose={() => setProvenancePid(null)} />
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Audit tab
// ---------------------------------------------------------------------------

function AuditTab({ l }: { l: (zh: string, en: string) => string }) {
  const [rows, setRows] = useState<PromotionRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [provenancePid, setProvenancePid] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const fetchAll = async () => {
      setLoading(true);
      try {
        const [a, b, c] = await Promise.all([
          get('/api/memory/promotions?status=ACTIVE'),
          get('/api/memory/promotions?status=REVIEWED_REJECTED'),
          get('/api/memory/promotions?status=RETRACTED'),
        ]);
        const pull = (r: any): PromotionRow[] =>
          ResultHelper.isSuccess(r) ? ((r.data as PromotionRow[]) ?? []) : [];
        const merged = [...pull(a), ...pull(b), ...pull(c)].sort((x, y) =>
          (y.created_at || '').localeCompare(x.created_at || ''),
        );
        if (!cancelled) setRows(merged);
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    fetchAll();
    return () => {
      cancelled = true;
    };
  }, []);

  if (loading) return <div className="text-sm text-gray-500">{l('加载中…', 'Loading…')}</div>;
  if (rows.length === 0) {
    return (
      <div className="text-sm text-gray-500 border border-dashed rounded p-6 text-center" data-testid="audit-empty">
        {l('暂无审计记录。', 'No audit history yet.')}
      </div>
    );
  }

  return (
    <div data-testid="audit-tab">
      <table className="w-full text-sm" data-testid="audit-table">
        <thead>
          <tr className="border-b border-gray-200 text-left text-gray-500">
            <th className="py-2 pr-4">{l('时间', 'Time')}</th>
            <th className="py-2 pr-4">{l('路径', 'Path')}</th>
            <th className="py-2 pr-4">{l('分类', 'Category')}</th>
            <th className="py-2 pr-4">{l('状态', 'Status')}</th>
            <th className="py-2 pr-4">{l('审核人', 'Reviewer')}</th>
            <th className="py-2 pr-4">{l('驳回原因', 'Reject reason')}</th>
            <th className="py-2 pr-4">{l('溯源', 'Provenance')}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.pid} className="border-b border-gray-100" data-testid={`audit-${row.pid}`}>
              <td className="py-2 pr-4 text-xs text-gray-500">
                {row.created_at?.slice(0, 16).replace('T', ' ')}
              </td>
              <td className="py-2 pr-4 text-xs text-gray-700">
                {row.source_scope} → {row.target_scope}
              </td>
              <td className="py-2 pr-4 text-xs text-gray-700">{row.category}</td>
              <td className="py-2 pr-4">
                <StatusBadge status={row.status} />
              </td>
              <td className="py-2 pr-4 text-xs text-gray-700">{row.reviewer_id ?? '—'}</td>
              <td className="py-2 pr-4 text-xs text-gray-700">{row.reject_reason ?? '—'}</td>
              <td className="py-2 pr-4">
                <button
                  onClick={() => setProvenancePid(row.pid)}
                  className="text-xs text-blue-600 hover:underline"
                  data-testid="provenance-link"
                >
                  {l('查看 →', 'View →')}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {provenancePid && (
        <ProvenanceModal l={l} pid={provenancePid} onClose={() => setProvenancePid(null)} />
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Reusable components
// ---------------------------------------------------------------------------

function PiiWarning({ l }: { l: (zh: string, en: string) => string }) {
  return (
    <div
      className="mt-4 rounded border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-900"
      data-testid="pii-warning"
      role="alert"
    >
      ⚠️{' '}
      {l(
        '批准后,此内容对租户内所有成员可见。请确认不含个人信息、客户数据、合规敏感信息。',
        'Approving will expose this content to all members of the tenant. Please verify no PII, customer data, or compliance-sensitive information is included.',
      )}
    </div>
  );
}

function RejectModal({
  l,
  promotion,
  reason,
  onReasonChange,
  comment,
  onCommentChange,
  onCancel,
  onSubmit,
  inFlight,
}: {
  l: (zh: string, en: string) => string;
  promotion: PromotionRow;
  reason: string;
  onReasonChange: (v: string) => void;
  comment: string;
  onCommentChange: (v: string) => void;
  onCancel: () => void;
  onSubmit: () => void;
  inFlight: boolean;
}) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/30"
      data-testid="reject-modal"
    >
      <div className="w-[520px] rounded bg-white p-5">
        <h3 className="text-lg font-semibold">{l('驳回晋升提案', 'Reject promotion')}</h3>
        <div className="mt-2 text-sm text-gray-600" data-testid="reject-target-title">
          {promotion.proposed_title}
        </div>
        <label className="mt-3 block text-sm font-medium text-gray-700">
          {l('原因', 'Reason')}
        </label>
        <select
          value={reason}
          onChange={(e) => onReasonChange(e.target.value)}
          className="mt-1 w-full rounded border border-gray-300 p-2 text-sm"
          data-testid="reject-reason-select"
        >
          {REJECT_REASONS.map((r) => (
            <option key={r.code} value={r.code}>
              {l(r.zh, r.en)}
            </option>
          ))}
        </select>
        <textarea
          value={comment}
          onChange={(e) => onCommentChange(e.target.value)}
          className="mt-3 w-full rounded border border-gray-300 p-2 text-sm"
          rows={3}
          placeholder={l('补充说明(可选)', 'Additional comment (optional)')}
          data-testid="reject-comment"
        />
        <div className="mt-3 flex justify-end gap-2">
          <button
            onClick={onCancel}
            className="rounded border border-gray-300 px-3 py-1 text-sm"
            data-testid="reject-cancel"
          >
            {l('取消', 'Cancel')}
          </button>
          <button
            onClick={onSubmit}
            disabled={inFlight}
            className="rounded bg-red-600 px-3 py-1 text-sm text-white disabled:opacity-50"
            data-testid="reject-submit"
          >
            {l('确认驳回', 'Confirm reject')}
          </button>
        </div>
      </div>
    </div>
  );
}

function BatchApproveDrawer({
  l,
  pids,
  rows,
  comment,
  onCommentChange,
  onCancel,
  onSubmit,
  inFlight,
}: {
  l: (zh: string, en: string) => string;
  pids: string[];
  rows: PromotionRow[];
  comment: string;
  onCommentChange: (v: string) => void;
  onCancel: () => void;
  onSubmit: () => void;
  inFlight: boolean;
}) {
  const pidSet = useMemo(() => new Set(pids), [pids]);
  const selectedRows = rows.filter((r) => pidSet.has(r.pid));
  const belowFloor = selectedRows.filter(
    (r) => r.confidence_score == null || r.confidence_score < BATCH_CONFIDENCE_FLOOR,
  );

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/30"
      data-testid="batch-drawer"
    >
      <div className="w-[560px] rounded bg-white p-5">
        <h3 className="text-lg font-semibold">
          {l('批量批准', 'Batch approve')} ({pids.length})
        </h3>
        <p className="mt-2 text-xs text-gray-600">
          {l(
            `后端仅接受 confidence >= ${BATCH_CONFIDENCE_FLOOR} 的条目,低置信度将在 failures 中返回。`,
            `Backend only forwards entries with confidence >= ${BATCH_CONFIDENCE_FLOOR}; lower-confidence pids come back as failures.`,
          )}
        </p>
        {belowFloor.length > 0 && (
          <div
            className="mt-2 rounded border border-amber-300 bg-amber-50 px-2 py-1 text-xs text-amber-900"
            data-testid="batch-below-floor"
          >
            {l(
              `${belowFloor.length} 条低于阈值,将被过滤。`,
              `${belowFloor.length} entries below threshold will be filtered out.`,
            )}
          </div>
        )}
        <PiiWarning l={l} />
        <textarea
          value={comment}
          onChange={(e) => onCommentChange(e.target.value)}
          className="mt-3 w-full rounded border border-gray-300 p-2 text-sm"
          rows={2}
          placeholder={l('批注(可选)', 'Comment (optional)')}
          data-testid="batch-comment"
        />
        <div className="mt-3 flex justify-end gap-2">
          <button
            onClick={onCancel}
            className="rounded border border-gray-300 px-3 py-1 text-sm"
            data-testid="batch-cancel"
          >
            {l('取消', 'Cancel')}
          </button>
          <button
            onClick={onSubmit}
            disabled={inFlight || pids.length === 0}
            className="rounded bg-blue-600 px-3 py-1 text-sm text-white disabled:opacity-50"
            data-testid="batch-submit"
          >
            {l('确认', 'Submit')}
          </button>
        </div>
      </div>
    </div>
  );
}

function ProvenanceModal({
  l,
  pid,
  onClose,
}: {
  l: (zh: string, en: string) => string;
  pid: string;
  onClose: () => void;
}) {
  const [data, setData] = useState<ProvenancePayload | null>(null);
  const [loading, setLoading] = useState(true);
  const modalRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    get(`/api/memory/promotions/${pid}/provenance`)
      .then((r) => {
        if (!cancelled && ResultHelper.isSuccess(r)) {
          setData(r.data as ProvenancePayload);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [pid]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/30"
      onClick={onClose}
      data-testid="provenance-modal"
    >
      <div
        ref={modalRef}
        className="w-[640px] max-h-[80vh] overflow-auto rounded bg-white p-5"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <h3 className="text-lg font-semibold">{l('溯源时间线', 'Provenance timeline')}</h3>
          <button
            onClick={onClose}
            className="text-sm text-gray-500 hover:text-gray-700"
            data-testid="provenance-close"
          >
            ✕
          </button>
        </div>
        {loading && <div className="mt-3 text-sm text-gray-500">{l('加载中…', 'Loading…')}</div>}
        {!loading && data && (
          <ol className="mt-3 space-y-3" data-testid="provenance-timeline">
            {/* Source memories */}
            {data.source_memories.map((m) => (
              <li key={m.pid} className="rounded border border-gray-200 p-3" data-testid="timeline-source">
                <div className="text-xs text-gray-500">
                  {l('来源记忆 · ', 'Source memory · ')}
                  {m.scope} · {m.created_at?.slice(0, 16).replace('T', ' ') ?? '—'}
                </div>
                <div className="mt-1 text-sm font-medium">{m.memory_title ?? '—'}</div>
                <div className="mt-1 text-xs text-gray-600">{m.memory_content}</div>
                {m.author_email && (
                  <div className="mt-1 text-xs text-gray-400">
                    {l('作者: ', 'Author: ')}
                    {m.author_user_name || m.author_email}
                  </div>
                )}
              </li>
            ))}
            {/* Promotion step */}
            <li className="rounded border border-indigo-200 bg-indigo-50 p-3" data-testid="timeline-promotion">
              <div className="text-xs text-indigo-700">
                {l('晋升提案 · ', 'Promotion · ')}
                {data.promotion.source_scope} → {data.promotion.target_scope} ·{' '}
                {data.promotion.created_at?.slice(0, 16).replace('T', ' ') ?? '—'}
              </div>
              <div className="mt-1 text-sm font-medium">{data.promotion.proposed_title}</div>
              <div className="mt-1 text-xs">
                {l('状态: ', 'Status: ')}
                <StatusBadge status={data.promotion.status} />
              </div>
            </li>
            {/* Promoted memory */}
            {data.promoted_memory && (
              <li className="rounded border border-green-200 bg-green-50 p-3" data-testid="timeline-promoted">
                <div className="text-xs text-green-700">
                  {l('租户记忆 · ', 'Tenant memory · ')}
                  {data.promoted_memory.created_at?.slice(0, 16).replace('T', ' ') ?? '—'}
                  {data.promoted_memory.shadow_mode && ' · ' + l('观察中', 'shadow')}
                </div>
                <div className="mt-1 text-sm font-medium">{data.promoted_memory.memory_title}</div>
                <div className="mt-1 text-xs text-gray-600">{data.promoted_memory.memory_content}</div>
              </li>
            )}
          </ol>
        )}
      </div>
    </div>
  );
}
