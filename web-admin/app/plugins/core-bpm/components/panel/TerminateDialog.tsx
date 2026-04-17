/**
 * TerminateDialog - Fix B of the OSS BPM closure spec 1.
 *
 * Destructive two-step confirmation for the "terminate" (终止) operation on
 * a running BPM process instance. Only {@code bpm.admin} users may reach this
 * dialog (see {@link BpmPermissionService.resolvePermissions}'s
 * {@code canTerminate}); the dialog enforces a second layer of safety via
 * UX:
 *
 *   1. A red warning header communicating irreversibility.
 *   2. A *required* reason textarea (written to {@code BpmAuditRecord.details}
 *      by the backend for forensic traceability).
 *   3. A "I confirm" checkbox that must be ticked before the destructive
 *      confirm button enables (hazardous-action red line §
 *      "危险操作二次确认").
 *
 * The submit button stays disabled unless BOTH the reason is non-blank AND
 * the confirm checkbox is checked; this matches the platform rule that
 * dangerous operations must not be single-click-reachable.
 *
 * {@code onConfirm} is awaited so that backend errors keep the dialog open
 * in a disabled/submitting state, consistent with the other BPM dialogs.
 *
 * @since BPM closure spec 1 (Fix B)
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

type Translator = (
  key: string,
  params?: Record<string, unknown>,
  fallback?: string,
) => string;

export interface TerminateDialogProps {
  open: boolean;
  /**
   * Process instance id against which the terminate call is issued.
   * Surfaced in the description copy so the admin sees exactly which
   * instance they're ending. Required (blank id is a caller bug).
   */
  processInstanceId: string;
  /**
   * Invoked with the trimmed reason when the admin confirms.
   * Awaited: the dialog stays in submitting/disabled state until the
   * promise resolves so backend errors can surface inline.
   */
  onConfirm: (reason: string) => Promise<void>;
  onCancel: () => void;
  t: Translator;
}

export function TerminateDialog({
  open,
  processInstanceId,
  onConfirm,
  onCancel,
  t,
}: TerminateDialogProps) {
  const [reason, setReason] = useState('');
  const [confirmChecked, setConfirmChecked] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const resetState = useCallback(() => {
    setReason('');
    setConfirmChecked(false);
    setError(null);
  }, []);

  const handleOpenChange = useCallback(
    (next: boolean) => {
      if (!next && !submitting) {
        resetState();
        onCancel();
      }
    },
    [onCancel, resetState, submitting],
  );

  const handleCancel = useCallback(() => {
    if (submitting) return;
    resetState();
    onCancel();
  }, [onCancel, resetState, submitting]);

  const trimmedReason = reason.trim();
  const submitDisabled =
    submitting || trimmedReason.length === 0 || !confirmChecked;

  const handleConfirm = useCallback(async () => {
    if (submitDisabled) return;
    setSubmitting(true);
    setError(null);
    try {
      await onConfirm(trimmedReason);
      resetState();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSubmitting(false);
    }
  }, [onConfirm, resetState, submitDisabled, trimmedReason]);

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent data-testid="bpm-terminate-dialog">
        <DialogHeader>
          <DialogTitle
            data-testid="bpm-terminate-dialog-title"
            className="text-red-600"
          >
            {t(
              'bpm.operations.terminate.warning',
              undefined,
              '⚠️ 终止流程不可撤销',
            )}
          </DialogTitle>
          <DialogDescription data-testid="bpm-terminate-dialog-description">
            {t(
              'bpm.operations.terminate.explain',
              { processInstanceId },
              `流程将立即终止，所有 pending task 作废，审计记录保留 (${processInstanceId})。`,
            )}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-3 py-2">
          <div className="space-y-1">
            <label
              htmlFor="bpm-terminate-reason-input"
              className="text-xs font-medium text-gray-700"
            >
              {t(
                'bpm.operations.terminate.reasonLabel',
                undefined,
                '终止原因（必填）',
              )}
              <span className="ml-1 text-red-600">*</span>
            </label>
            <Textarea
              id="bpm-terminate-reason-input"
              data-testid="bpm-terminate-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder={t(
                'bpm.operations.terminate.reasonPlaceholder',
                undefined,
                '请填写终止原因（必填，写入审计日志）',
              )}
              disabled={submitting}
              required
              rows={3}
            />
          </div>

          <label className="flex items-start gap-2 text-xs text-gray-700">
            <input
              type="checkbox"
              data-testid="bpm-terminate-confirm-checkbox"
              checked={confirmChecked}
              onChange={(e) => setConfirmChecked(e.target.checked)}
              disabled={submitting}
              className="mt-0.5"
            />
            <span>
              {t(
                'bpm.operations.terminate.confirmCheckbox',
                undefined,
                '我确认要终止该流程',
              )}
            </span>
          </label>

          {error && (
            <p
              data-testid="bpm-terminate-error"
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
            data-testid="bpm-terminate-cancel"
          >
            {t('bpm.common.cancel', undefined, '取消')}
          </Button>
          <Button
            variant="destructive"
            onClick={handleConfirm}
            disabled={submitDisabled}
            data-testid="bpm-terminate-submit"
          >
            {submitting
              ? t('bpm.operations.terminate.submitting', undefined, '终止中...')
              : t('bpm.operations.terminate.confirm', undefined, '确认终止')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
