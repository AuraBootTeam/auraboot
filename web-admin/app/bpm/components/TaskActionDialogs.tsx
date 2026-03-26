/**
 * TaskActionDialogs - All task/process action dialogs
 * Includes: Complete, Approve, Reject, Delegate, Transfer, Terminate,
 *           AddSign, RemoveSign, Rollback, CarbonCopy
 */

import { useState, useCallback, useEffect } from 'react';
import { Button } from '~/components/ui/button';
import { Textarea } from '~/components/ui/textarea';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '~/components/ui/dialog';
import { MemberPicker } from '~/components/smart/picker/MemberPicker';
import type { DialogState } from '../hooks/useTaskCenter';
import type { RollbackTarget } from '../services/bpmWorkbenchService';
import * as workbenchService from '../services/bpmWorkbenchService';

// ==================== Props ====================

interface TaskActionDialogsProps {
  dialog: DialogState;
  onClose: () => void;
  onComplete: (comment: string) => Promise<void>;
  onApprove: (comment: string) => Promise<void>;
  onReject: (comment: string) => Promise<void>;
  onDelegate: (userId: string, comment: string) => Promise<void>;
  onTransfer: (userId: string, comment: string) => Promise<void>;
  onTerminate: (comment: string) => Promise<void>;
  onAddSign: (userId: string, reason: string) => Promise<void>;
  onRemoveSign: (userId: string, reason: string) => Promise<void>;
  onRollback: (targetActivityId: string, reason: string) => Promise<void>;
  onCarbonCopy: (userIds: string[], content: string) => Promise<void>;
}

// ==================== Component ====================

export function TaskActionDialogs({
  dialog,
  onClose,
  onComplete,
  onApprove,
  onReject,
  onDelegate,
  onTransfer,
  onTerminate,
  onAddSign,
  onRemoveSign,
  onRollback,
  onCarbonCopy,
}: TaskActionDialogsProps) {
  return (
    <>
      <CommentDialog
        open={dialog.type === 'complete'}
        title="完成任务"
        description={`确认完成任务：${dialog.task?.taskName || dialog.task?.title || ''}`}
        confirmLabel="确认完成"
        placeholder="请输入审批意见（选填）"
        onClose={onClose}
        onConfirm={onComplete}
      />
      <CommentDialog
        open={dialog.type === 'approve'}
        title="通过审批"
        description={`确认通过：${dialog.task?.taskName || dialog.task?.title || ''}`}
        confirmLabel="确认通过"
        confirmClassName="bg-green-600 hover:bg-green-700"
        placeholder="请输入审批意见（选填）"
        onClose={onClose}
        onConfirm={onApprove}
      />
      <CommentDialog
        open={dialog.type === 'reject'}
        title="驳回审批"
        description={`确认驳回：${dialog.task?.taskName || dialog.task?.title || ''}`}
        confirmLabel="确认驳回"
        confirmVariant="destructive"
        placeholder="请输入驳回原因"
        onClose={onClose}
        onConfirm={onReject}
      />
      <CommentDialog
        open={dialog.type === 'terminate'}
        title="终止流程"
        description="确认终止流程？此操作不可撤销。"
        confirmLabel="确认终止"
        confirmVariant="destructive"
        placeholder="请输入终止原因"
        onClose={onClose}
        onConfirm={onTerminate}
      />
      <UserSelectDialog
        open={dialog.type === 'delegate'}
        title="委托任务"
        description="将任务暂时委托给其他用户处理，处理完毕后任务将返回给您。"
        confirmLabel="确认委托"
        onClose={onClose}
        onConfirm={onDelegate}
      />
      <UserSelectDialog
        open={dialog.type === 'transfer'}
        title="转办任务"
        description="将任务永久转交给其他用户处理。"
        confirmLabel="确认转办"
        onClose={onClose}
        onConfirm={onTransfer}
      />
      <UserSelectDialog
        open={dialog.type === 'addSign'}
        title="加签"
        description="添加额外的审批人到当前任务节点。"
        confirmLabel="确认加签"
        commentLabel="加签原因"
        onClose={onClose}
        onConfirm={onAddSign}
      />
      <UserSelectDialog
        open={dialog.type === 'removeSign'}
        title="减签"
        description="移除当前任务节点的候选审批人。"
        confirmLabel="确认减签"
        commentLabel="减签原因"
        onClose={onClose}
        onConfirm={onRemoveSign}
      />
      <RollbackDialog
        open={dialog.type === 'rollback'}
        processInstanceId={dialog.task?.processInstanceId}
        onClose={onClose}
        onConfirm={onRollback}
      />
      <CarbonCopyDialog
        open={dialog.type === 'carbonCopy'}
        onClose={onClose}
        onConfirm={onCarbonCopy}
      />
    </>
  );
}

// ==================== CommentDialog (approve/reject/complete/terminate) ====================

function CommentDialog({
  open,
  title,
  description,
  confirmLabel,
  confirmClassName,
  confirmVariant,
  placeholder,
  onClose,
  onConfirm,
}: {
  open: boolean;
  title: string;
  description: string;
  confirmLabel: string;
  confirmClassName?: string;
  confirmVariant?: 'destructive' | 'default';
  placeholder: string;
  onClose: () => void;
  onConfirm: (comment: string) => Promise<void>;
}) {
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = useCallback(async () => {
    setSubmitting(true);
    try {
      await onConfirm(comment);
      setComment('');
    } finally {
      setSubmitting(false);
    }
  }, [onConfirm, comment]);

  const handleOpenChange = useCallback(
    (isOpen: boolean) => {
      if (!isOpen) {
        setComment('');
        onClose();
      }
    },
    [onClose],
  );

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-4">
          <div className="space-y-2">
            <label className="text-sm font-medium">审批意见</label>
            <Textarea
              placeholder={placeholder}
              value={comment}
              onChange={(e) => setComment(e.target.value)}
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={submitting}>
            取消
          </Button>
          <Button
            variant={confirmVariant}
            className={confirmClassName}
            onClick={handleSubmit}
            disabled={submitting}
          >
            {submitting ? '处理中...' : confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ==================== UserSelectDialog (delegate/transfer/addSign/removeSign) ====================

function UserSelectDialog({
  open,
  title,
  description,
  confirmLabel,
  commentLabel = '备注',
  onClose,
  onConfirm,
}: {
  open: boolean;
  title: string;
  description: string;
  confirmLabel: string;
  commentLabel?: string;
  onClose: () => void;
  onConfirm: (userId: string, comment: string) => Promise<void>;
}) {
  const [userId, setUserId] = useState<string | undefined>();
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = useCallback(async () => {
    if (!userId) return;
    setSubmitting(true);
    try {
      await onConfirm(userId, comment);
      setUserId(undefined);
      setComment('');
    } finally {
      setSubmitting(false);
    }
  }, [onConfirm, userId, comment]);

  const handleOpenChange = useCallback(
    (isOpen: boolean) => {
      if (!isOpen) {
        setUserId(undefined);
        setComment('');
        onClose();
      }
    },
    [onClose],
  );

  const handleMemberChange = useCallback((val: string | string[] | undefined) => {
    setUserId(typeof val === 'string' ? val : val?.[0]);
  }, []);

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-4">
          <div className="space-y-2">
            <label className="text-sm font-medium">选择用户</label>
            <MemberPicker
              value={userId}
              onChange={handleMemberChange}
              placeholder="搜索并选择用户..."
            />
          </div>
          <div className="space-y-2">
            <label className="text-sm font-medium">{commentLabel}</label>
            <Textarea
              placeholder={`请输入${commentLabel}（选填）`}
              value={comment}
              onChange={(e) => setComment(e.target.value)}
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={submitting}>
            取消
          </Button>
          <Button onClick={handleSubmit} disabled={!userId || submitting}>
            {submitting ? '处理中...' : confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ==================== RollbackDialog ====================

function RollbackDialog({
  open,
  processInstanceId,
  onClose,
  onConfirm,
}: {
  open: boolean;
  processInstanceId?: string;
  onClose: () => void;
  onConfirm: (targetActivityId: string, reason: string) => Promise<void>;
}) {
  const [targetActivityId, setTargetActivityId] = useState('');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [targets, setTargets] = useState<RollbackTarget[]>([]);
  const [loadingTargets, setLoadingTargets] = useState(false);

  // Load rollback targets when dialog opens
  useEffect(() => {
    if (!open || !processInstanceId) {
      setTargets([]);
      return;
    }
    let cancelled = false;
    setLoadingTargets(true);
    workbenchService
      .getRollbackTargets(processInstanceId)
      .then((nodes) => {
        if (!cancelled) setTargets(nodes);
      })
      .catch(() => {
        if (!cancelled) setTargets([]);
      })
      .finally(() => {
        if (!cancelled) setLoadingTargets(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, processInstanceId]);

  const handleSubmit = useCallback(async () => {
    if (!targetActivityId) return;
    setSubmitting(true);
    try {
      await onConfirm(targetActivityId, reason);
      setTargetActivityId('');
      setReason('');
    } finally {
      setSubmitting(false);
    }
  }, [onConfirm, targetActivityId, reason]);

  const handleOpenChange = useCallback(
    (isOpen: boolean) => {
      if (!isOpen) {
        setTargetActivityId('');
        setReason('');
        onClose();
      }
    },
    [onClose],
  );

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>回退任务</DialogTitle>
          <DialogDescription>选择要回退到的目标流程节点。</DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-4">
          <div className="space-y-2">
            <label className="text-sm font-medium">目标节点</label>
            {loadingTargets ? (
              <div className="py-2 text-sm text-gray-500">加载节点列表...</div>
            ) : targets.length === 0 ? (
              <div className="py-2 text-sm text-gray-400">暂无可回退的节点</div>
            ) : (
              <select
                className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
                value={targetActivityId}
                onChange={(e) => setTargetActivityId(e.target.value)}
              >
                <option value="">请选择回退目标节点</option>
                {targets.map((t) => (
                  <option key={t.nodeId} value={t.nodeId}>
                    {t.name || t.nodeId}
                    {t.completedAt ? ` (${t.completedAt})` : ''}
                  </option>
                ))}
              </select>
            )}
          </div>
          <div className="space-y-2">
            <label className="text-sm font-medium">回退原因</label>
            <Textarea
              placeholder="请输入回退原因"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={submitting}>
            取消
          </Button>
          <Button onClick={handleSubmit} disabled={!targetActivityId || submitting}>
            {submitting ? '处理中...' : '确认回退'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ==================== CarbonCopyDialog ====================

function CarbonCopyDialog({
  open,
  onClose,
  onConfirm,
}: {
  open: boolean;
  onClose: () => void;
  onConfirm: (userIds: string[], content: string) => Promise<void>;
}) {
  const [userIds, setUserIds] = useState<string[]>([]);
  const [content, setContent] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = useCallback(async () => {
    if (userIds.length === 0) return;
    setSubmitting(true);
    try {
      await onConfirm(userIds, content);
      setUserIds([]);
      setContent('');
    } finally {
      setSubmitting(false);
    }
  }, [onConfirm, userIds, content]);

  const handleOpenChange = useCallback(
    (isOpen: boolean) => {
      if (!isOpen) {
        setUserIds([]);
        setContent('');
        onClose();
      }
    },
    [onClose],
  );

  const handleMemberChange = useCallback((val: string | string[] | undefined) => {
    if (Array.isArray(val)) setUserIds(val);
    else if (val) setUserIds([val]);
    else setUserIds([]);
  }, []);

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>抄送</DialogTitle>
          <DialogDescription>将此任务信息抄送给相关人员。</DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-4">
          <div className="space-y-2">
            <label className="text-sm font-medium">抄送人员</label>
            <MemberPicker
              value={userIds}
              onChange={handleMemberChange}
              multiple
              placeholder="搜索并选择抄送人员..."
            />
          </div>
          <div className="space-y-2">
            <label className="text-sm font-medium">抄送内容</label>
            <Textarea
              placeholder="请输入抄送内容（选填）"
              value={content}
              onChange={(e) => setContent(e.target.value)}
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={submitting}>
            取消
          </Button>
          <Button onClick={handleSubmit} disabled={userIds.length === 0 || submitting}>
            {submitting ? '发送中...' : '确认抄送'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
