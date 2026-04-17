/**
 * CcDialog - Task 13 of the OSS BPM closure spec.
 *
 * Carbon-copy (抄送) dialog used by {@link BpmOperationsSection}. Lets the
 * current assignee pick one or more recipient user ids and attach a mandatory
 * comment before the CC notification is dispatched via
 * {@code POST /api/bpm/tasks/{taskId}/cc}.
 *
 * Design decisions:
 *   1. Receiver picker = {@link MemberPicker}. We reuse the same component
 *      already used by {@code TaskActionDialogs.CarbonCopyDialog} so the look
 *      and keyboard affordances match the existing Task Center UX.
 *   2. Comment is REQUIRED. The plan mandates "CC 带评论" to avoid drive-by
 *      notifications that the recipient has no context for. Empty / blank
 *      comment disables the confirm button.
 *   3. We do NOT read or enforce {@code CcPolicy} on the frontend; the backend
 *      {@code CcService} rejects policy violations and the rejection flows
 *      back via the {@link onConfirm} promise, which surfaces the backend
 *      error text in an inline banner without closing the dialog.
 *   4. Receiver ids are passed through as raw strings. {@link ccTask} in
 *      bpmWorkbenchService normalises them to {@code Long} before the POST.
 *      The dialog does not pre-validate integer shape so it can surface any
 *      picker-specific errors uniformly.
 *
 * @since BPM closure spec 1 (Task 13)
 */

import { useCallback, useState } from 'react';
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
import { MemberPicker } from '~/ui/smart/picker/MemberPicker';

type Translator = (
  key: string,
  params?: Record<string, unknown>,
  fallback?: string,
) => string;

export interface CcDialogProps {
  open: boolean;
  /**
   * Task id against which the CC is issued. The task must be a current / open
   * userTask for the instance; {@link ccTask} rejects blank ids. Required.
   */
  taskId: string;
  onConfirm: (receiverUserIds: string[], comment: string) => Promise<void>;
  onCancel: () => void;
  t: Translator;
}

export function CcDialog({ open, taskId, onConfirm, onCancel, t }: CcDialogProps) {
  const [receiverIds, setReceiverIds] = useState<string[]>([]);
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reset = useCallback(() => {
    setReceiverIds([]);
    setComment('');
    setError(null);
  }, []);

  const handleOpenChange = useCallback(
    (next: boolean) => {
      if (!next && !submitting) {
        reset();
        onCancel();
      }
    },
    [onCancel, reset, submitting],
  );

  const handleCancel = useCallback(() => {
    if (submitting) return;
    reset();
    onCancel();
  }, [onCancel, reset, submitting]);

  const handleMemberChange = useCallback((val: string | string[] | undefined) => {
    if (Array.isArray(val)) {
      setReceiverIds(val);
    } else if (typeof val === 'string' && val.length > 0) {
      setReceiverIds([val]);
    } else {
      setReceiverIds([]);
    }
  }, []);

  const commentText = comment.trim();
  const canSubmit = receiverIds.length > 0 && commentText.length > 0 && !submitting;

  const handleConfirm = useCallback(async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      await onConfirm(receiverIds, commentText);
      reset();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSubmitting(false);
    }
  }, [canSubmit, onConfirm, receiverIds, commentText, reset]);

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent data-testid="bpm-cc-dialog">
        <DialogHeader>
          <DialogTitle data-testid="bpm-cc-dialog-title">
            {t('bpm.cc.title', undefined, '抄送流程')}
          </DialogTitle>
          <DialogDescription data-testid="bpm-cc-dialog-description">
            {t(
              'bpm.cc.description',
              { taskId },
              `向相关同事抄送此任务（任务 ${taskId}）。抄送不会修改流程流转。`,
            )}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-2">
          <div className="space-y-1">
            <label className="text-xs font-medium text-gray-700">
              {t('bpm.cc.receivers.label', undefined, '抄送人员')}
              <span className="ml-1 text-red-500">*</span>
            </label>
            <div data-testid="bpm-cc-receivers">
              <MemberPicker
                value={receiverIds}
                onChange={handleMemberChange}
                multiple
                placeholder={t(
                  'bpm.cc.receivers.placeholder',
                  undefined,
                  '搜索并选择抄送人员',
                )}
                disabled={submitting}
              />
            </div>
          </div>

          <div className="space-y-1">
            <label
              htmlFor="bpm-cc-comment"
              className="text-xs font-medium text-gray-700"
            >
              {t('bpm.cc.comment.label', undefined, '抄送说明')}
              <span className="ml-1 text-red-500">*</span>
            </label>
            <Textarea
              id="bpm-cc-comment"
              data-testid="bpm-cc-comment"
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              placeholder={t(
                'bpm.cc.comment.placeholder',
                undefined,
                '请说明抄送原因或需要关注的事项',
              )}
              disabled={submitting}
              rows={3}
            />
          </div>

          {error && (
            <p data-testid="bpm-cc-error" className="text-xs text-red-600">
              {error}
            </p>
          )}
        </div>

        <DialogFooter>
          <Button
            variant="outline"
            onClick={handleCancel}
            disabled={submitting}
            data-testid="bpm-cc-cancel"
          >
            {t('bpm.common.cancel', undefined, '取消')}
          </Button>
          <Button
            onClick={handleConfirm}
            disabled={!canSubmit}
            data-testid="bpm-cc-confirm"
          >
            {submitting
              ? t('bpm.cc.submitting', undefined, '发送中...')
              : t('bpm.cc.confirm', undefined, '确认抄送')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
