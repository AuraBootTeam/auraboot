// web-admin/app/framework/meta/runtime/reference-create/ReferenceCreateDialog.tsx
import React, { useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '~/ui/ui/dialog';
import { useDslForm } from '~/framework/meta/hooks/useDslForm';
import { DslFormRenderer } from '~/framework/meta/rendering/DslFormRenderer';
import { useI18n } from '~/contexts/I18nContext';

function pickCreatedRecord(result: any): Record<string, any> | undefined {
  const candidates = [
    result?.data?.data,
    result?.data?.record,
    result?.data?.result,
    result?.data,
    result,
  ];
  return candidates.find(
    (candidate) =>
      candidate &&
      typeof candidate === 'object' &&
      (candidate.pid !== undefined || candidate.recordPid !== undefined),
  );
}

export interface ReferenceCreateDialogProps {
  open: boolean;
  /** target model code, e.g. "customer" — defaults pageKey to `${targetModel}_new` */
  targetModel: string;
  /** DSL form page key to use for quick create. Defaults to `${targetModel}_new`. */
  createPageKey?: string;
  /** create command code, e.g. "customer:create" */
  createCommand: string;
  /** display field used to compute the selected option label */
  displayField?: string;
  /** initial values copied from the parent form into the quick-create form */
  initialValues?: Record<string, any>;
  /** injected from useActionHandler in the parent */
  executeCommand: (
    commandCode: string,
    targetRecordPid: string | undefined,
    payload: Record<string, any>,
    operationType: string,
  ) => Promise<any>;
  /** called with the created record's {value: pid, label} on success */
  onCreated: (selected: { value: string; label: string }) => void;
  /** close the dialog (cancel or after success) */
  onClose: () => void;
}

export function ReferenceCreateDialog({
  open,
  targetModel,
  createPageKey,
  createCommand,
  displayField,
  initialValues,
  executeCommand,
  onCreated,
  onClose,
}: ReferenceCreateDialogProps): React.JSX.Element | null {
  const { t, locale } = useI18n();
  const [submitError, setSubmitError] = useState<string | null>(null);

  const form = useDslForm({
    pageKey: createPageKey || `${targetModel}_new`,
    enabled: open,
    initialValues,
    onSubmit: async ({ values }) => {
      // Errors are caught here so they don't propagate as unhandled rejections.
      // The dialog stays open because we only call onCreated/onClose on success.
      try {
        setSubmitError(null);
        const result = await executeCommand(createCommand, undefined, values, 'create');
        const record = pickCreatedRecord(result);
        const pid = record?.pid ?? record?.recordPid;
        if (!pid) {
          throw new Error(
            `[ReferenceCreateDialog] create command ${createCommand} returned no pid`,
          );
        }
        const label =
          (displayField && record?.[displayField]) ?? values?.[displayField ?? ''] ?? String(pid);
        onCreated({ value: String(pid), label: String(label) });
        onClose();
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        setSubmitError(message);
        // Do NOT re-throw: dialog stays open because onCreated/onClose were not called.
        // useDslForm.submit uses try/finally so submitting resets even without rethrow.
      }
    },
  });

  if (!open) return null;

  const title =
    t('action.createNew') !== 'action.createNew'
      ? t('action.createNew')
      : locale === 'zh-CN'
        ? '新建'
        : 'New';

  return (
    <Dialog
      open={open}
      onOpenChange={(v) => {
        if (!v) onClose();
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        {submitError && (
          <div className="mb-2 rounded-md bg-red-50 px-3 py-2 text-sm text-red-600" role="alert">
            {submitError}
          </div>
        )}
        {form.loading ? (
          <div className="py-8 text-center text-sm text-gray-500">
            {t('common.loading') || '...'}
          </div>
        ) : (
          <DslFormRenderer form={form} />
        )}
      </DialogContent>
    </Dialog>
  );
}

export default ReferenceCreateDialog;
