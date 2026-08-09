import React, { useState } from 'react';
import { CheckCircle2, GitPullRequest, Loader2, XCircle } from 'lucide-react';
import type { AuthoringGovernanceAction, AuthoringSession } from './types';

export function AuthoringGovernanceNotice({
  session,
  currentUserId,
  canManage,
  canReview,
  canPublish,
  pendingAction,
  error,
  onAction,
}: {
  session: AuthoringSession;
  currentUserId?: string | number | null;
  canManage: boolean;
  canReview: boolean;
  canPublish: boolean;
  pendingAction: AuthoringGovernanceAction | null;
  error?: string | null;
  onAction: (action: AuthoringGovernanceAction, reason: string) => Promise<void> | void;
}) {
  const [reason, setReason] = useState('');
  const isOwner = currentUserId != null && String(currentUserId) === String(session.ownerUserId);
  const status = session.changeSetStatus;
  if (!['IN_REVIEW', 'APPROVED', 'REJECTED', 'PUBLISHED'].includes(status)) return null;

  const pending = pendingAction !== null;
  const canWithdraw = status === 'IN_REVIEW' && isOwner && canManage;
  const canDecide = status === 'IN_REVIEW' && !isOwner && canReview;
  const canReopen = status === 'APPROVED' && isOwner && canManage;
  const canActivate = status === 'APPROVED' && session.publishState === 'READY' && canPublish;
  const requiresReason = canWithdraw || canReopen || canDecide;
  const reasonMissing = reason.trim().length === 0;
  const tone = governanceTone(status);

  const run = async (action: AuthoringGovernanceAction) => {
    await onAction(action, reason.trim());
  };

  return (
    <section
      className={`rounded-md border px-3 py-3 text-sm ${tone.container}`}
      data-testid="authoring-governance-notice"
      aria-label="ChangeSet 评审状态"
    >
      <div className="flex items-start gap-2">
        <GovernanceIcon status={status} className="mt-0.5 h-4 w-4 shrink-0" />
        <div className="min-w-0 flex-1">
          <div className="font-semibold">{governanceTitle(session)}</div>
          <div className="mt-1 text-xs leading-5">{governanceDescription(session)}</div>
        </div>
      </div>

      {requiresReason ? (
        <div className="mt-3 flex flex-col gap-2 lg:flex-row lg:items-end">
          <label className="min-w-0 flex-1 text-xs font-medium">
            {canDecide ? '评审意见' : '继续编辑原因'}
            <textarea
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              rows={2}
              maxLength={1000}
              disabled={pending}
              className="border-border bg-panel mt-1 block min-h-16 w-full resize-y rounded-md border px-2 py-2 text-sm text-slate-800"
              data-testid="authoring-governance-reason"
              placeholder={
                canDecide ? '驳回时必填；批准时可作为审计意见' : '必填，将记录到 revision 审计'
              }
            />
          </label>
          <div className="flex flex-wrap gap-2">
            {canWithdraw ? (
              <GovernanceButton
                action="withdraw"
                label="撤回评审并继续编辑"
                pendingAction={pendingAction}
                disabled={pending || reasonMissing}
                onClick={run}
              />
            ) : null}
            {canReopen ? (
              <GovernanceButton
                action="reopen"
                label={
                  session.approvalState === 'APPROVED'
                    ? '使批准失效并继续编辑'
                    : '继续编辑并生成新 revision'
                }
                pendingAction={pendingAction}
                disabled={pending || reasonMissing}
                onClick={run}
              />
            ) : null}
            {canDecide ? (
              <>
                <GovernanceButton
                  action="reject"
                  label="驳回到新 revision"
                  pendingAction={pendingAction}
                  disabled={pending || reasonMissing}
                  onClick={run}
                />
                <GovernanceButton
                  action="approve"
                  label="批准当前 revision"
                  pendingAction={pendingAction}
                  disabled={pending}
                  onClick={run}
                  primary
                />
              </>
            ) : null}
          </div>
        </div>
      ) : null}

      {canActivate ? (
        <div className="mt-3 rounded-md border border-emerald-200 bg-white/70 px-3 py-3">
          <div className="text-xs leading-5">
            发布将原子切换当前环境的活动 Release。失败时旧版本保持可见，当前 ChangeSet 仍为
            READY，可在确认后重试。
          </div>
          <div className="mt-2 flex justify-end">
            <GovernanceButton
              action="publish"
              label="发布当前 revision"
              pendingAction={pendingAction}
              disabled={pending}
              onClick={run}
              primary
            />
          </div>
        </div>
      ) : null}

      {status === 'APPROVED' && !canActivate && !canReopen ? (
        <div className="mt-2 text-xs">
          当前 revision 已批准，等待具备发布管理权限的人员切换活动版本。
        </div>
      ) : null}

      {status === 'IN_REVIEW' && !canWithdraw && !canDecide ? (
        <div className="mt-2 text-xs">
          当前工作区只提供冻结 revision 查看。Owner 请在原地配置撤回；Reviewer
          请从评审任务进入专用工作区。
        </div>
      ) : null}
      {error ? (
        <div
          className="mt-2 rounded border border-red-200 bg-red-50 px-2 py-2 text-xs text-red-800"
          role="alert"
        >
          {error}
        </div>
      ) : null}
    </section>
  );
}

function GovernanceButton({
  action,
  label,
  pendingAction,
  disabled,
  onClick,
  primary = false,
}: {
  action: AuthoringGovernanceAction;
  label: string;
  pendingAction: AuthoringGovernanceAction | null;
  disabled: boolean;
  onClick: (action: AuthoringGovernanceAction) => Promise<void>;
  primary?: boolean;
}) {
  const active = pendingAction === action;
  return (
    <button
      type="button"
      onClick={() => void onClick(action)}
      disabled={disabled}
      className={
        primary
          ? 'inline-flex min-h-9 items-center gap-1.5 rounded-md bg-emerald-700 px-3 font-semibold text-white hover:bg-emerald-800 disabled:cursor-not-allowed disabled:bg-slate-300'
          : 'border-border bg-panel inline-flex min-h-9 items-center gap-1.5 rounded-md border px-3 font-medium text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60'
      }
      data-testid={`authoring-governance-${action}`}
    >
      {active ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
      {active ? '处理中…' : label}
    </button>
  );
}

function GovernanceIcon({ status, className }: { status: string; className: string }) {
  if (status === 'APPROVED' || status === 'PUBLISHED') {
    return <CheckCircle2 className={className} />;
  }
  if (status === 'REJECTED') return <XCircle className={className} />;
  return <GitPullRequest className={className} />;
}

function governanceTitle(session: AuthoringSession): string {
  if (session.changeSetStatus === 'IN_REVIEW')
    return `评审中 · revision r${session.revision} 已冻结`;
  if (session.changeSetStatus === 'APPROVED') return `revision r${session.revision} 已批准`;
  if (session.changeSetStatus === 'PUBLISHED') return `revision r${session.revision} 已发布`;
  return `评审已驳回 · 已进入可编辑 revision r${session.revision}`;
}

function governanceDescription(session: AuthoringSession): string {
  if (session.changeSetStatus === 'IN_REVIEW') {
    return '冻结期间不能直接修改。Owner 可撤回并生成新 revision；其他 reviewer 只能批准或驳回当前精确 revision。';
  }
  if (session.changeSetStatus === 'APPROVED') {
    return session.approvalState === 'APPROVED'
      ? '发布资格只绑定当前 revision。继续编辑会先把本次批准标记为 STALE，再生成新的未校验 revision。'
      : '当前 revision 已具备直发资格。继续编辑会生成新的未校验 revision。';
  }
  if (session.changeSetStatus === 'PUBLISHED') {
    return '该 revision 已成为当前环境的活动 Release。后续修改必须从活动版本创建新的隔离 ChangeSet。';
  }
  return '驳回决策保留在上一 revision；当前 revision 已取消发布资格，可修改后重新提交。';
}

function governanceTone(status: string): { container: string } {
  if (status === 'APPROVED' || status === 'PUBLISHED') {
    return { container: 'border-emerald-300 bg-emerald-50 text-emerald-950' };
  }
  if (status === 'REJECTED') {
    return { container: 'border-red-300 bg-red-50 text-red-950' };
  }
  return { container: 'border-amber-300 bg-amber-50 text-amber-950' };
}
