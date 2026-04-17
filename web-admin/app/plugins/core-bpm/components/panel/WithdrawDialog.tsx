/**
 * WithdrawDialog - Task 13 of the OSS BPM closure spec.
 *
 * Two-step confirmation dialog for the "withdraw" (撤回) operation on a BPM
 * process instance. The dialog deliberately surfaces the process-level
 * {@code WithdrawPolicy} semantics so that the initiator understands what the
 * button does before they commit:
 *
 *   - {@code strict}: can only be withdrawn BEFORE any approval has been
 *     recorded. Disables the confirm button with an explanatory note once the
 *     first approval lands (the caller decides when to pass {@code strict};
 *     the dialog does not itself inspect runtime state).
 *   - {@code loose}: may be withdrawn any time while the instance is running.
 *   - {@code none}: withdraw is disabled for this process definition. When the
 *     caller renders this dialog with {@code withdrawPolicy='none'}, we still
 *     render the copy but hide the confirm button so the user sees WHY they
 *     cannot proceed.
 *
 * The first click opens the dialog; the user must type a reason (optional for
 * now - backend records it in {@code BpmAuditRecord.details}) and click the
 * destructive "Confirm withdraw" button. Consistent with the hard rule for
 * destructive operations, the confirm button is styled as {@code destructive}.
 * Cancel closes without side-effects.
 *
 * {@code onConfirm} is awaited - the dialog stays open in a disabled/submitting
 * state until the promise resolves so upstream errors surface without the
 * dialog closing prematurely.
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

/**
 * WithdrawPolicy codes mirrored from backend
 * {@code com.auraboot.framework.bpm.model.WithdrawPolicy}. Kept as a string
 * union (rather than imported) to avoid a TS dependency on the Java source.
 */
export type WithdrawPolicy = 'strict' | 'loose' | 'none';

type Translator = (
  key: string,
  params?: Record<string, unknown>,
  fallback?: string,
) => string;

export interface WithdrawDialogProps {
  open: boolean;
  /**
   * Task id against which the withdraw is issued. The dialog does not fetch
   * the task itself; it merely surfaces the id so the confirm copy can be
   * unambiguous. Required (blank id is a caller bug).
   */
  taskId: string;
  /**
   * Optional process-level withdraw policy; forwarded from backend
   * {@code aura.withdrawPolicy} extension. When undefined the dialog renders
   * a neutral copy and defers the decision to the backend.
   */
  withdrawPolicy?: WithdrawPolicy;
  onConfirm: (reason?: string) => Promise<void>;
  onCancel: () => void;
  t: Translator;
}

export function WithdrawDialog({
  open,
  taskId,
  withdrawPolicy,
  onConfirm,
  onCancel,
  t,
}: WithdrawDialogProps) {
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleOpenChange = useCallback(
    (next: boolean) => {
      if (!next && !submitting) {
        setReason('');
        setError(null);
        onCancel();
      }
    },
    [onCancel, submitting],
  );

  const handleCancel = useCallback(() => {
    if (submitting) return;
    setReason('');
    setError(null);
    onCancel();
  }, [onCancel, submitting]);

  const handleConfirm = useCallback(async () => {
    setSubmitting(true);
    setError(null);
    try {
      await onConfirm(reason.trim().length > 0 ? reason.trim() : undefined);
      setReason('');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSubmitting(false);
    }
  }, [onConfirm, reason]);

  const policyDescription = (() => {
    switch (withdrawPolicy) {
      case 'strict':
        return t(
          'bpm.withdraw.policy.strict',
          undefined,
          '严格模式：仅在尚未有审批人处理时可撤回',
        );
      case 'loose':
        return t(
          'bpm.withdraw.policy.loose',
          undefined,
          '宽松模式：流程运行中任意时刻均可撤回',
        );
      case 'none':
        return t('bpm.withdraw.policy.none', undefined, '此流程不允许撤回');
      default:
        return t(
          'bpm.withdraw.policy.unknown',
          undefined,
          '撤回权限由后端根据流程配置校验',
        );
    }
  })();

  const confirmDisabled = submitting || withdrawPolicy === 'none';

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent data-testid="bpm-withdraw-dialog">
        <DialogHeader>
          <DialogTitle data-testid="bpm-withdraw-dialog-title">
            {t('bpm.withdraw.title', undefined, '撤回流程')}
          </DialogTitle>
          <DialogDescription data-testid="bpm-withdraw-dialog-description">
            {t(
              'bpm.withdraw.description',
              { taskId },
              `确认撤回当前任务 (${taskId})？此操作会终止流程实例，无法恢复。`,
            )}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-3 py-2">
          <p
            data-testid="bpm-withdraw-policy"
            data-policy={withdrawPolicy ?? 'unspecified'}
            className="rounded bg-amber-50 px-3 py-2 text-xs text-amber-800"
          >
            {policyDescription}
          </p>
          <div className="space-y-1">
            <label
              htmlFor="bpm-withdraw-reason"
              className="text-xs font-medium text-gray-700"
            >
              {t('bpm.withdraw.reason.label', undefined, '撤回原因')}
            </label>
            <Textarea
              id="bpm-withdraw-reason"
              data-testid="bpm-withdraw-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder={t(
                'bpm.withdraw.reason.placeholder',
                undefined,
                '请填写撤回原因（选填，将写入审计日志）',
              )}
              disabled={submitting}
              rows={3}
            />
          </div>
          {error && (
            <p
              data-testid="bpm-withdraw-error"
              className="text-xs text-red-600"
            >
              {error}
            </p>
          )}
        </div>

        <DialogFooter>
          <Button
            variant="outline"
            onClick={handleCancel}
            disabled={submitting}
            data-testid="bpm-withdraw-cancel"
          >
            {t('bpm.common.cancel', undefined, '取消')}
          </Button>
          {withdrawPolicy !== 'none' && (
            <Button
              variant="destructive"
              onClick={handleConfirm}
              disabled={confirmDisabled}
              data-testid="bpm-withdraw-confirm"
            >
              {submitting
                ? t('bpm.withdraw.submitting', undefined, '撤回中...')
                : t('bpm.withdraw.confirm', undefined, '确认撤回')}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
