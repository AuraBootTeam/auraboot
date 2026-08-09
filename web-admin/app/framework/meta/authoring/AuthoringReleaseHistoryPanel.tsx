import { History, RefreshCw, RotateCcw, ShieldCheck, TriangleAlert } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { loadAuthoringReleaseHistory, rollbackAuthoringRelease } from './authoringService';
import type { AuthoringReleaseHistory, AuthoringRollbackEligibility } from './types';

const PAGE_SIZE = 10;

export function AuthoringReleaseHistoryPanel({
  changeSetPid,
  canRollback,
  refreshKey,
  onRolledBack,
}: {
  changeSetPid: string;
  canRollback: boolean;
  refreshKey: string | number;
  onRolledBack?: () => Promise<void> | void;
}) {
  const [history, setHistory] = useState<AuthoringReleaseHistory | null>(null);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [reason, setReason] = useState('');
  const [rollbackPending, setRollbackPending] = useState(false);
  const [success, setSuccess] = useState<string | null>(null);
  const loadSequence = useRef(0);

  const load = useCallback(
    async (nextPage: number) => {
      const sequence = ++loadSequence.current;
      setLoading(true);
      setError(null);
      try {
        const nextHistory = await loadAuthoringReleaseHistory(changeSetPid, nextPage, PAGE_SIZE);
        if (sequence !== loadSequence.current) return false;
        setHistory(nextHistory);
        setPage(nextPage);
        return true;
      } catch (failure) {
        if (sequence !== loadSequence.current) return false;
        setError(failure instanceof Error ? failure.message : '无法加载发布历史');
        return false;
      } finally {
        if (sequence === loadSequence.current) setLoading(false);
      }
    },
    [changeSetPid],
  );

  useEffect(() => {
    loadSequence.current += 1;
    setHistory(null);
    setPage(1);
    setConfirming(false);
    setReason('');
    setSuccess(null);
  }, [changeSetPid]);

  useEffect(() => {
    void load(1);
  }, [load, refreshKey]);

  useEffect(
    () => () => {
      loadSequence.current += 1;
    },
    [],
  );

  const totalPages = useMemo(
    () => Math.max(1, Math.ceil((history?.total ?? 0) / PAGE_SIZE)),
    [history?.total],
  );

  const handleRollback = async () => {
    const activeReleasePid = history?.activeReleasePid;
    if (!activeReleasePid || !history.rollbackEligibility.eligible || reason.trim().length === 0) {
      return;
    }
    setRollbackPending(true);
    setError(null);
    setSuccess(null);
    let rolledBack = false;
    try {
      await rollbackAuthoringRelease(activeReleasePid, history.channelVersion, reason.trim());
      rolledBack = true;
      setConfirming(false);
      setReason('');
      setHistory(null);
      const historyReloaded = await load(1);
      setSuccess(
        historyReloaded
          ? '回滚已完成，活动 Release 已原子切换。'
          : '回滚已完成；发布历史尚未刷新，请手动重试。',
      );
    } catch (failure) {
      setError(
        failure instanceof Error ? failure.message : '回滚失败；活动版本可能已变化，请刷新后重试',
      );
    } finally {
      setRollbackPending(false);
    }
    if (rolledBack) {
      try {
        await onRolledBack?.();
      } catch {
        setError('回滚已完成，但 Studio 会话刷新失败；请手动刷新页面。');
      }
    }
  };

  return (
    <section
      className="mt-3 rounded-lg border border-slate-200 bg-white px-4 py-4 shadow-sm"
      data-testid="authoring-release-history"
      aria-labelledby="authoring-release-history-title"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-2">
          <History className="mt-0.5 h-4 w-4 text-slate-600" aria-hidden="true" />
          <div>
            <h3
              id="authoring-release-history-title"
              className="text-sm font-semibold text-slate-950"
            >
              发布历史
            </h3>
            <p className="mt-0.5 text-xs leading-5 text-slate-600">
              Release 是不可变发布结果；活动指针与回滚资格由服务端裁决。
            </p>
          </div>
        </div>
        <button
          type="button"
          className="inline-flex items-center gap-1 rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-700 hover:bg-slate-50 disabled:opacity-50"
          onClick={() => void load(page)}
          disabled={loading || rollbackPending}
          data-testid="authoring-release-refresh"
        >
          <RefreshCw
            className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`}
            aria-hidden="true"
          />
          刷新
        </button>
      </div>

      {history ? (
        <div className="mt-3 grid gap-2 text-xs sm:grid-cols-3">
          <ReleaseFact
            label="活动 Release"
            value={shortPid(history.activeReleasePid) || '尚未发布'}
          />
          <ReleaseFact label="Channel version" value={`v${history.channelVersion}`} />
          <ReleaseFact label="历史数量" value={`${history.total}`} />
        </div>
      ) : null}

      {history ? (
        <RollbackEligibility
          eligibility={history.rollbackEligibility}
          canRollback={canRollback}
          confirming={confirming}
          reason={reason}
          pending={rollbackPending}
          onPrepare={() => {
            setConfirming(true);
            setError(null);
            setSuccess(null);
          }}
          onReasonChange={setReason}
          onCancel={() => {
            setConfirming(false);
            setReason('');
          }}
          onConfirm={() => void handleRollback()}
        />
      ) : null}

      {error ? (
        <div
          className="mt-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-800"
          role="alert"
        >
          {error}
        </div>
      ) : null}
      {success ? (
        <div
          className="mt-3 rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs text-emerald-800"
          role="status"
        >
          {success}
        </div>
      ) : null}

      <div className="mt-3 space-y-2" aria-busy={loading}>
        {loading && !history ? (
          <div className="rounded-md border border-dashed border-slate-300 px-3 py-5 text-center text-xs text-slate-500">
            正在加载发布历史…
          </div>
        ) : null}
        {!loading && history?.items.length === 0 ? (
          <div className="rounded-md border border-dashed border-slate-300 px-3 py-5 text-center text-xs text-slate-500">
            尚无 Release。当前 ChangeSet 仍是隔离草稿。
          </div>
        ) : null}
        {history?.items.map((item) => (
          <article
            key={item.releasePid}
            className="rounded-md border border-slate-200 px-3 py-2.5"
            data-testid={`authoring-release-${item.releasePid}`}
          >
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-mono text-xs font-semibold text-slate-900">
                  {shortPid(item.releasePid)}
                </span>
                <StatusBadge status={item.status} />
                <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[11px] text-slate-700">
                  revision r{item.changeSetRevision}
                </span>
                <span className="text-[11px] text-slate-500">
                  {reversibilityLabel(item.reversibility)}
                </span>
              </div>
              <time
                className="text-[11px] text-slate-500"
                dateTime={item.activatedAt || item.createdAt}
              >
                {formatDateTime(item.activatedAt || item.createdAt)}
              </time>
            </div>
          </article>
        ))}
      </div>

      {history && history.total > PAGE_SIZE ? (
        <div className="mt-3 flex items-center justify-between text-xs text-slate-600">
          <span>
            第 {page} / {totalPages} 页
          </span>
          <div className="flex gap-2">
            <button
              type="button"
              className="rounded border border-slate-300 px-2 py-1 disabled:opacity-40"
              disabled={page <= 1 || loading}
              onClick={() => void load(Math.max(1, page - 1))}
            >
              上一页
            </button>
            <button
              type="button"
              className="rounded border border-slate-300 px-2 py-1 disabled:opacity-40"
              disabled={page >= totalPages || loading}
              onClick={() => void load(Math.min(totalPages, page + 1))}
            >
              下一页
            </button>
          </div>
        </div>
      ) : null}
    </section>
  );
}

function RollbackEligibility({
  eligibility,
  canRollback,
  confirming,
  reason,
  pending,
  onPrepare,
  onReasonChange,
  onCancel,
  onConfirm,
}: {
  eligibility: AuthoringRollbackEligibility;
  canRollback: boolean;
  confirming: boolean;
  reason: string;
  pending: boolean;
  onPrepare: () => void;
  onReasonChange: (reason: string) => void;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const explanation = rollbackExplanation(eligibility);
  const eligible = eligibility.eligible;
  return (
    <div
      className={`mt-3 rounded-md border px-3 py-3 ${
        eligible ? 'border-emerald-200 bg-emerald-50' : 'border-amber-200 bg-amber-50'
      }`}
      data-testid="authoring-rollback-eligibility"
    >
      <div className="flex items-start gap-2">
        {eligible ? (
          <ShieldCheck className="mt-0.5 h-4 w-4 text-emerald-700" aria-hidden="true" />
        ) : (
          <TriangleAlert className="mt-0.5 h-4 w-4 text-amber-700" aria-hidden="true" />
        )}
        <div className="min-w-0 flex-1">
          <div className="text-xs font-semibold text-slate-900">
            {eligible ? '可回滚到 immediate previous Release' : '当前不可一键回滚'}
          </div>
          <p className="mt-1 text-xs leading-5 text-slate-700">{explanation}</p>
          {eligible && !canRollback ? (
            <p className="mt-1 text-xs text-slate-600">等待具备发布管理权限的人员执行。</p>
          ) : null}
        </div>
        {eligible && canRollback && !confirming ? (
          <button
            type="button"
            className="inline-flex shrink-0 items-center gap-1 rounded-md border border-amber-300 bg-white px-2 py-1 text-xs font-medium text-amber-900 hover:bg-amber-50"
            onClick={onPrepare}
            data-testid="authoring-rollback-prepare"
          >
            <RotateCcw className="h-3.5 w-3.5" aria-hidden="true" />
            准备回滚
          </button>
        ) : null}
      </div>

      {eligible && canRollback && confirming ? (
        <div
          className="mt-3 border-t border-amber-200 pt-3"
          data-testid="authoring-rollback-confirmation"
        >
          <label
            className="block text-xs font-medium text-slate-900"
            htmlFor="authoring-rollback-reason"
          >
            回滚原因（必填，写入审计）
          </label>
          <textarea
            id="authoring-rollback-reason"
            className="mt-1 min-h-20 w-full rounded-md border border-slate-300 bg-white px-2 py-1.5 text-xs text-slate-900"
            value={reason}
            maxLength={1000}
            disabled={pending}
            onChange={(event) => onReasonChange(event.target.value)}
          />
          <p className="mt-1 text-[11px] leading-4 text-slate-600">
            确认后只原子切换当前资源的活动 Release；不会伪称撤销外部副作用。
          </p>
          <div className="mt-2 flex justify-end gap-2">
            <button
              type="button"
              className="rounded-md border border-slate-300 bg-white px-2 py-1 text-xs text-slate-700"
              disabled={pending}
              onClick={onCancel}
            >
              取消
            </button>
            <button
              type="button"
              className="rounded-md bg-amber-700 px-2 py-1 text-xs font-semibold text-white disabled:opacity-50"
              disabled={pending || reason.trim().length === 0}
              onClick={onConfirm}
              data-testid="authoring-rollback-confirm"
            >
              {pending ? '正在回滚…' : '确认回滚活动 Release'}
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function ReleaseFact({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md bg-slate-50 px-2.5 py-2">
      <div className="text-[11px] text-slate-500">{label}</div>
      <div className="mt-0.5 font-mono text-xs font-medium text-slate-900">{value}</div>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const active = status === 'ACTIVE';
  return (
    <span
      className={`rounded-full px-1.5 py-0.5 text-[11px] font-medium ${
        active ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-100 text-slate-600'
      }`}
    >
      {releaseStatusLabel(status)}
    </span>
  );
}

function rollbackExplanation(eligibility: AuthoringRollbackEligibility): string {
  switch (eligibility.reasonCode) {
    case 'ELIGIBLE':
      return `只能切回 ${shortPid(eligibility.targetReleasePid)}；执行时会重判 channelVersion 和可逆性。`;
    case 'NO_ACTIVE_RELEASE':
      return '当前资源尚无活动 Release。';
    case 'NO_PREVIOUS_RELEASE':
      return '这是首个发布版本，没有可回滚的前序 Release。';
    case 'PREVIOUS_RELEASE_UNAVAILABLE':
      return '前序 Release 已回滚或状态不可用，禁止再次反向切换。';
    case 'CONTAINS_COMPENSATABLE_CHANGES':
      return `当前 Release 包含 ${eligibility.compensatableItemCount} 项需补偿变更，必须走补偿方案。`;
    case 'CONTAINS_FORWARD_ONLY_CHANGES':
      return `当前 Release 包含 ${eligibility.forwardOnlyItemCount} 项仅前向变更，不能伪造一键回滚。`;
  }
}

function releaseStatusLabel(status: string): string {
  if (status === 'ACTIVE') return '当前活动';
  if (status === 'SUPERSEDED') return '已被取代';
  if (status === 'ROLLED_BACK') return '已回滚';
  if (status === 'FAILED') return '失败';
  return '准备中';
}

function reversibilityLabel(reversibility: string): string {
  if (reversibility === 'FORWARD_ONLY') return '仅前向';
  if (reversibility === 'COMPENSATABLE') return '需补偿';
  return '可逆';
}

function shortPid(pid?: string | null): string {
  if (!pid) return '';
  return pid.length <= 12 ? pid : `${pid.slice(0, 6)}…${pid.slice(-4)}`;
}

function formatDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}
