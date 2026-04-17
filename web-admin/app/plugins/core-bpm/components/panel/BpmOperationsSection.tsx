/**
 * BpmOperationsSection - Task 13 of the OSS BPM closure spec.
 *
 * Renders the Operations slot of {@link BpmPanelBlock}: four buttons
 * (approve / reject / withdraw / cc) that drive the corresponding
 * {@code POST /api/bpm/tasks/{taskId}/*} endpoints for the detail record's
 * process instance.
 *
 * Rendering rules (from the plan):
 *   - {@code instance === null}  → render nothing; empty state is the
 *     responsibility of {@link BpmStatusSection}.
 *   - {@code instance.status !== 'running'}  → render a small info row
 *     indicating the process has ended; hide all action buttons.
 *   - {@code instance.status === 'running'}  → render the four buttons with
 *     {@code disabled} driven by {@link resolvePermissions}. A disabled
 *     button also surfaces its {@code reasonsBlocked} code via a title
 *     attribute for hover tooltips.
 *
 * Task-id resolution: the approve/reject/withdraw/cc endpoints key off a
 * concrete {@code taskId}, not a node id. The BPM status DTO only carries
 * node ids, so we call {@code GET /api/bpm/tasks/by-process/{instanceId}} on
 * mount to discover the pending tasks. The first task whose assignee matches
 * the current user is the candidate for approve/reject/cc; for withdraw we
 * pick the first pending task regardless of assignee (initiators may not be
 * an assignee on any current node). If no pending task is present the
 * corresponding buttons stay disabled with {@code task.none} reason.
 *
 * All four operation callbacks award the caller an {@code onActionComplete}
 * tick when they succeed; the parent {@link BpmPanelBlock} reloads the
 * instance on that tick so the next render reflects the new state (badge,
 * current nodes, etc.).
 *
 * @since BPM closure spec 1 (Task 13)
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button } from '~/ui/ui/button';
import { Textarea } from '~/ui/ui/textarea';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '~/ui/ui/dialog';
import { useAuth } from '~/contexts/AuthContext';
import {
  approveTask,
  ccTask,
  getTasksByProcessInstance,
  rejectTask,
  terminateProcess,
  withdrawTask,
  type BpmInstanceForRecord,
  type TaskInstance,
} from '~/plugins/core-bpm/services/bpmWorkbenchService';
import {
  resolvePermissions,
  type BpmPermissionAction,
  type BpmPermissionResult,
} from '~/plugins/core-bpm/services/BpmPermissionService';
import { WithdrawDialog, type WithdrawPolicy } from './WithdrawDialog';
import { CcDialog } from './CcDialog';
import { TerminateDialog } from './TerminateDialog';

type Translator = (
  key: string,
  params?: Record<string, unknown>,
  fallback?: string,
) => string;

export interface BpmOperationsSectionProps {
  instance: BpmInstanceForRecord | null;
  /** Invoked after any successful operation so the parent can reload state. */
  onActionComplete?: () => void;
  t: Translator;
}

/** Dialog state machine: at most one dialog visible at a time. */
type DialogState =
  | { type: 'none' }
  | { type: 'approve' }
  | { type: 'reject' }
  | { type: 'withdraw' }
  | { type: 'cc' }
  | { type: 'terminate' };

/** Map blocked-reason codes to human-readable Chinese fallback copy. */
const BLOCKED_REASON_COPY: Record<string, string> = {
  'instance.notRunning': '流程已结束',
  'user.anonymous': '请先登录',
  'user.notInitiator': '仅发起人可撤回',
  'user.notAssignee': '当前节点审批人非您',
  'user.notBpmAdmin': '仅 BPM 管理员可终止',
  'task.none': '暂无待办任务',
};

function resolveBlockedTitle(
  t: Translator,
  reasonCode: string | undefined,
): string | undefined {
  if (!reasonCode) return undefined;
  const fallback = BLOCKED_REASON_COPY[reasonCode] ?? reasonCode;
  return t(`bpm.permission.blocked.${reasonCode}`, undefined, fallback);
}

export function BpmOperationsSection({
  instance,
  onActionComplete,
  t,
}: BpmOperationsSectionProps) {
  const { user, hasPermission } = useAuth();

  const [pendingTasks, setPendingTasks] = useState<TaskInstance[]>([]);
  const [tasksLoaded, setTasksLoaded] = useState(false);
  const [tasksError, setTasksError] = useState<string | null>(null);
  const [dialog, setDialog] = useState<DialogState>({ type: 'none' });
  const [approveComment, setApproveComment] = useState('');
  const [rejectComment, setRejectComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  // Fetch pending tasks for the running instance. Non-running instances skip
  // this call entirely - none of the buttons render.
  useEffect(() => {
    if (!instance || instance.status !== 'running') {
      setPendingTasks([]);
      setTasksLoaded(true);
      setTasksError(null);
      return;
    }
    let cancelled = false;
    setTasksLoaded(false);
    setTasksError(null);
    getTasksByProcessInstance(instance.instanceId)
      .then((tasks) => {
        if (cancelled) return;
        setPendingTasks(tasks);
        setTasksLoaded(true);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setPendingTasks([]);
        setTasksError(err instanceof Error ? err.message : String(err));
        setTasksLoaded(true);
      });
    return () => {
      cancelled = true;
    };
  }, [instance?.instanceId, instance?.status]);

  const currentUserId = user?.pid ?? null;
  const currentUserPermissions = useMemo<readonly string[]>(() => {
    // We do NOT have raw access to the permission code list via useAuth() - it
    // only exposes `hasPermission`. For the permission service we resolve the
    // one code we care about (bpm.admin) and forward a single-element array
    // when present. This keeps the service pure and avoids coupling it to the
    // AuthContext runtime shape.
    return hasPermission('bpm.admin') ? ['bpm.admin'] : [];
  }, [hasPermission]);

  const permission: BpmPermissionResult | null = instance
    ? resolvePermissions(instance, {
        id: currentUserId,
        permissions: currentUserPermissions,
      })
    : null;

  // Resolve which task id each action targets. We pick:
  //   - assignee-matching task for approve/reject/cc (the one the viewer owns);
  //   - first pending task for withdraw (initiator may have no pending task of
  //     their own, but the backend only needs any open task to locate the
  //     instance).
  const assigneeTaskId = useMemo(() => {
    if (!currentUserId) return null;
    const match = pendingTasks.find(
      (task) => task.assignee === currentUserId || task.claimUserId === currentUserId,
    );
    return match?.taskId ?? null;
  }, [pendingTasks, currentUserId]);

  const firstPendingTaskId = pendingTasks[0]?.taskId ?? null;

  // Derive the effective disabled + reason for each button. "task.none" covers
  // the case where the permission service says OK but we still have no task id
  // to hand to the endpoint.
  const buttonState = useMemo(() => {
    const derive = (
      action: BpmPermissionAction,
      canByPolicy: boolean,
      targetTaskId: string | null,
    ) => {
      if (!canByPolicy) {
        const reasonCode = permission?.reasonsBlocked?.[action];
        return {
          disabled: true,
          reasonCode,
        };
      }
      if (!tasksLoaded) {
        return { disabled: true, reasonCode: undefined };
      }
      if (!targetTaskId) {
        return { disabled: true, reasonCode: 'task.none' };
      }
      return { disabled: false, reasonCode: undefined };
    };
    // Terminate keys off the processInstanceId directly (no task lookup), so
    // it is disabled/enabled purely on permission.
    const terminateCanByPolicy = permission?.canTerminate ?? false;
    const terminateReason = terminateCanByPolicy
      ? undefined
      : permission?.reasonsBlocked?.terminate;
    return {
      approve: derive('approve', permission?.canApprove ?? false, assigneeTaskId),
      reject: derive('reject', permission?.canReject ?? false, assigneeTaskId),
      withdraw: derive('withdraw', permission?.canWithdraw ?? false, firstPendingTaskId),
      cc: derive('cc', permission?.canCc ?? false, assigneeTaskId),
      terminate: {
        disabled: !terminateCanByPolicy,
        reasonCode: terminateReason,
      },
    };
  }, [permission, assigneeTaskId, firstPendingTaskId, tasksLoaded]);

  const closeDialog = useCallback(() => {
    if (submitting) return;
    setDialog({ type: 'none' });
    setApproveComment('');
    setRejectComment('');
    setSubmitError(null);
  }, [submitting]);

  const runApprove = useCallback(async () => {
    if (!assigneeTaskId) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      await approveTask(assigneeTaskId, approveComment.trim() || undefined);
      setDialog({ type: 'none' });
      setApproveComment('');
      onActionComplete?.();
    } catch (e) {
      setSubmitError(e instanceof Error ? e.message : String(e));
    } finally {
      setSubmitting(false);
    }
  }, [assigneeTaskId, approveComment, onActionComplete]);

  const runReject = useCallback(async () => {
    if (!assigneeTaskId) return;
    const reason = rejectComment.trim();
    if (reason.length === 0) {
      setSubmitError(t('bpm.reject.reasonRequired', undefined, '驳回原因必填'));
      return;
    }
    setSubmitting(true);
    setSubmitError(null);
    try {
      await rejectTask(assigneeTaskId, reason);
      setDialog({ type: 'none' });
      setRejectComment('');
      onActionComplete?.();
    } catch (e) {
      setSubmitError(e instanceof Error ? e.message : String(e));
    } finally {
      setSubmitting(false);
    }
  }, [assigneeTaskId, rejectComment, onActionComplete, t]);

  const runWithdraw = useCallback(
    async (reason?: string) => {
      if (!firstPendingTaskId) {
        throw new Error(t('bpm.withdraw.noTask', undefined, '没有可用的流程任务'));
      }
      await withdrawTask(firstPendingTaskId, reason);
      setDialog({ type: 'none' });
      onActionComplete?.();
    },
    [firstPendingTaskId, onActionComplete, t],
  );

  const runCc = useCallback(
    async (receivers: string[], comment: string) => {
      if (!assigneeTaskId) {
        throw new Error(t('bpm.cc.noTask', undefined, '没有可用的流程任务'));
      }
      await ccTask(assigneeTaskId, receivers, comment);
      setDialog({ type: 'none' });
      onActionComplete?.();
    },
    [assigneeTaskId, onActionComplete, t],
  );

  const runTerminate = useCallback(
    async (reason: string) => {
      if (!instance) {
        throw new Error(t('bpm.terminate.noInstance', undefined, '没有可终止的流程实例'));
      }
      await terminateProcess(instance.instanceId, reason);
      setDialog({ type: 'none' });
      onActionComplete?.();
    },
    [instance, onActionComplete, t],
  );

  // ---------- Render ----------

  if (instance === null) {
    // Status section owns the empty UI.
    return null;
  }

  if (instance.status !== 'running') {
    return (
      <div
        data-testid="bpm-operations-closed"
        data-status={instance.status}
        className="rounded border border-gray-200 bg-gray-50 p-3 text-xs text-gray-500"
      >
        {t(
          'bpm.operations.closed',
          { status: instance.status },
          '流程已结束，无可用操作。',
        )}
      </div>
    );
  }

  return (
    <div
      data-testid="bpm-operations-card"
      className="rounded border border-gray-200 bg-white p-4"
    >
      <div className="text-xs font-medium text-gray-500">
        {t('bpm.operations.title', undefined, '审批操作')}
      </div>

      {tasksError && (
        <p
          data-testid="bpm-operations-tasks-error"
          className="mt-2 text-xs text-red-600"
        >
          {t(
            'bpm.operations.tasksError',
            { message: tasksError },
            `获取任务失败：${tasksError}`,
          )}
        </p>
      )}

      <div className="mt-3 flex flex-wrap gap-2">
        <Button
          data-testid="bpm-operations-approve"
          variant="default"
          disabled={buttonState.approve.disabled}
          title={resolveBlockedTitle(t, buttonState.approve.reasonCode)}
          onClick={() => setDialog({ type: 'approve' })}
        >
          {t('bpm.operations.approve', undefined, '通过')}
        </Button>
        <Button
          data-testid="bpm-operations-reject"
          variant="destructive"
          disabled={buttonState.reject.disabled}
          title={resolveBlockedTitle(t, buttonState.reject.reasonCode)}
          onClick={() => setDialog({ type: 'reject' })}
        >
          {t('bpm.operations.reject', undefined, '驳回')}
        </Button>
        <Button
          data-testid="bpm-operations-withdraw"
          variant="outline"
          disabled={buttonState.withdraw.disabled}
          title={resolveBlockedTitle(t, buttonState.withdraw.reasonCode)}
          onClick={() => setDialog({ type: 'withdraw' })}
        >
          {t('bpm.operations.withdraw', undefined, '撤回')}
        </Button>
        <Button
          data-testid="bpm-operations-cc"
          variant="outline"
          disabled={buttonState.cc.disabled}
          title={resolveBlockedTitle(t, buttonState.cc.reasonCode)}
          onClick={() => setDialog({ type: 'cc' })}
        >
          {t('bpm.operations.cc', undefined, '抄送')}
        </Button>
        <Button
          data-testid="bpm-operations-terminate"
          variant="destructive"
          disabled={buttonState.terminate.disabled}
          title={resolveBlockedTitle(t, buttonState.terminate.reasonCode)}
          onClick={() => setDialog({ type: 'terminate' })}
        >
          {t('bpm.operations.terminate', undefined, '终止')}
        </Button>
      </div>

      {/* Approve dialog - inline simple comment collector */}
      <Dialog
        open={dialog.type === 'approve'}
        onOpenChange={(next) => {
          if (!next) closeDialog();
        }}
      >
        <DialogContent data-testid="bpm-approve-dialog">
          <DialogHeader>
            <DialogTitle>{t('bpm.approve.title', undefined, '通过审批')}</DialogTitle>
            <DialogDescription>
              {t(
                'bpm.approve.description',
                undefined,
                '请确认通过此审批。您可填写可选的审批意见。',
              )}
            </DialogDescription>
          </DialogHeader>
          <div className="py-2">
            <Textarea
              data-testid="bpm-approve-comment"
              value={approveComment}
              onChange={(e) => setApproveComment(e.target.value)}
              placeholder={t(
                'bpm.approve.commentPlaceholder',
                undefined,
                '请输入审批意见（选填）',
              )}
              disabled={submitting}
              rows={3}
            />
            {submitError && (
              <p data-testid="bpm-approve-error" className="mt-2 text-xs text-red-600">
                {submitError}
              </p>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={closeDialog} disabled={submitting}>
              {t('bpm.common.cancel', undefined, '取消')}
            </Button>
            <Button
              data-testid="bpm-approve-confirm"
              onClick={runApprove}
              disabled={submitting}
            >
              {submitting
                ? t('bpm.approve.submitting', undefined, '提交中...')
                : t('bpm.approve.confirm', undefined, '确认通过')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Reject dialog - reason REQUIRED */}
      <Dialog
        open={dialog.type === 'reject'}
        onOpenChange={(next) => {
          if (!next) closeDialog();
        }}
      >
        <DialogContent data-testid="bpm-reject-dialog">
          <DialogHeader>
            <DialogTitle>{t('bpm.reject.title', undefined, '驳回审批')}</DialogTitle>
            <DialogDescription>
              {t(
                'bpm.reject.description',
                undefined,
                '请填写驳回原因，驳回后流程会回到起点或上一节点。',
              )}
            </DialogDescription>
          </DialogHeader>
          <div className="py-2">
            <Textarea
              data-testid="bpm-reject-comment"
              value={rejectComment}
              onChange={(e) => setRejectComment(e.target.value)}
              placeholder={t('bpm.reject.commentPlaceholder', undefined, '请输入驳回原因')}
              disabled={submitting}
              rows={3}
            />
            {submitError && (
              <p data-testid="bpm-reject-error" className="mt-2 text-xs text-red-600">
                {submitError}
              </p>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={closeDialog} disabled={submitting}>
              {t('bpm.common.cancel', undefined, '取消')}
            </Button>
            <Button
              data-testid="bpm-reject-confirm"
              variant="destructive"
              onClick={runReject}
              disabled={submitting || rejectComment.trim().length === 0}
            >
              {submitting
                ? t('bpm.reject.submitting', undefined, '提交中...')
                : t('bpm.reject.confirm', undefined, '确认驳回')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Withdraw dialog - destructive, two-step */}
      <WithdrawDialog
        open={dialog.type === 'withdraw'}
        taskId={firstPendingTaskId ?? ''}
        withdrawPolicy={extractWithdrawPolicy(instance)}
        onConfirm={runWithdraw}
        onCancel={closeDialog}
        t={t}
      />

      {/* CC dialog - MemberPicker + required comment */}
      <CcDialog
        open={dialog.type === 'cc'}
        taskId={assigneeTaskId ?? ''}
        onConfirm={runCc}
        onCancel={closeDialog}
        t={t}
      />

      {/* Terminate dialog - bpm.admin only; required reason + confirm checkbox */}
      <TerminateDialog
        open={dialog.type === 'terminate'}
        processInstanceId={instance.instanceId}
        onConfirm={runTerminate}
        onCancel={closeDialog}
        t={t}
      />
    </div>
  );
}

/**
 * Best-effort extraction of the process-level {@code WithdrawPolicy} from the
 * instance variables. Backend stores the policy in BPMN extensions and does
 * NOT currently surface it on {@link BpmInstanceForRecord}; however some
 * historical callers echo it into the variables map, and we honour that when
 * present. Unknown values degrade to {@code undefined}, which causes
 * {@link WithdrawDialog} to render a neutral description.
 */
function extractWithdrawPolicy(
  instance: BpmInstanceForRecord,
): WithdrawPolicy | undefined {
  const raw = instance.variables?.withdrawPolicy;
  if (typeof raw !== 'string') return undefined;
  const code = raw.trim().toLowerCase();
  if (code === 'strict' || code === 'loose' || code === 'none') {
    return code;
  }
  return undefined;
}
