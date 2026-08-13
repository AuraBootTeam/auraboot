import React, { useCallback, useEffect, useState } from 'react';
import { ControlledFieldRenderer } from '~/framework/meta/rendering/ControlledFieldRenderer';
import type { ExpressionContext } from '~/framework/meta/runtime/expression/context';
import type { FieldConfig } from '~/framework/meta/schemas/types';
import { cn } from '~/utils/cn';

export interface BulkFieldCommandModalProps {
  open: boolean;
  actionLabel: string;
  selectedCount: number;
  field: FieldConfig;
  context: ExpressionContext;
  locale?: string;
  t?: (key: string) => string;
  onClose: () => void;
  onSubmit: (value: unknown) => Promise<void>;
}

function isEmptyValue(value: unknown): boolean {
  return (
    value === undefined ||
    value === null ||
    value === '' ||
    (Array.isArray(value) && value.length === 0)
  );
}

export const BulkFieldCommandModal: React.FC<BulkFieldCommandModalProps> = ({
  open,
  actionLabel,
  selectedCount,
  field,
  context,
  locale = 'zh-CN',
  t,
  onClose,
  onSubmit,
}) => {
  const [value, setValue] = useState<unknown>();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string>();
  const isZh = locale.toLowerCase().startsWith('zh');
  const tr = useCallback(
    (key: string, zh: string, en: string) => {
      const translated = t?.(key);
      return translated && translated !== key ? translated : isZh ? zh : en;
    },
    [isZh, t],
  );

  useEffect(() => {
    if (!open) return;
    setValue(undefined);
    setError(undefined);
    setSubmitting(false);
  }, [field.field, open]);

  const handleSubmit = useCallback(async () => {
    if (field.required && isEmptyValue(value)) {
      setError(tr('list.bulk.fieldRequired', '请选择一个值', 'Select a value'));
      return;
    }
    setSubmitting(true);
    setError(undefined);
    try {
      await onSubmit(value);
    } catch (submitError) {
      setError(
        submitError instanceof Error
          ? submitError.message
          : tr('list.bulk.commandFailed', '批量操作失败', 'Bulk action failed'),
      );
      setSubmitting(false);
    }
  }, [field.required, onSubmit, tr, value]);

  if (!open) return null;

  return (
    <>
      <div className="fixed inset-0 z-50 bg-black/45 backdrop-blur-[1px]" onClick={onClose} />
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="bulk-field-command-title"
          data-testid="bulk-field-command-dialog"
          className="rounded-card bg-panel border-border w-full max-w-lg border shadow-2xl"
          onClick={(event) => event.stopPropagation()}
        >
          <div className="border-border border-b px-6 py-5">
            <div className="flex items-start justify-between gap-4">
              <div>
                <h2 id="bulk-field-command-title" className="text-text text-lg font-semibold">
                  {actionLabel}
                </h2>
                <p className="text-text-2 mt-1 text-sm">
                  {isZh
                    ? `将所选值应用到 ${selectedCount} 条记录；每条记录都会独立校验权限和业务规则。`
                    : `Apply the selected value to ${selectedCount} records. Permissions and business rules are checked per record.`}
                </p>
              </div>
              <span className="bg-accent-weak text-accent rounded-pill px-3 py-1 text-sm font-semibold">
                {selectedCount}
              </span>
            </div>
          </div>

          <div className="px-6 py-5">
            <ControlledFieldRenderer
              field={field}
              value={value}
              onChange={(nextValue) => {
                setValue(nextValue);
                setError(undefined);
              }}
              context={context}
              error={error}
            />
          </div>

          <div className="border-border bg-subtle flex justify-end gap-3 border-t px-6 py-4">
            <button
              type="button"
              onClick={onClose}
              disabled={submitting}
              className="rounded-control border-border-strong bg-panel text-text-2 hover:bg-hover border px-4 py-2 text-sm font-medium disabled:opacity-50"
            >
              {tr('common.cancel', '取消', 'Cancel')}
            </button>
            <button
              type="button"
              data-testid="bulk-field-command-submit"
              onClick={handleSubmit}
              disabled={submitting || (field.required && isEmptyValue(value))}
              className={cn(
                'rounded-control bg-accent px-4 py-2 text-sm font-medium text-white',
                'hover:bg-accent-hover focus:ring-accent focus:ring-2 focus:outline-none',
                'disabled:cursor-not-allowed disabled:opacity-50',
              )}
            >
              {submitting
                ? tr('list.bulk.executing', '正在执行…', 'Applying…')
                : isZh
                  ? `应用到 ${selectedCount} 条记录`
                  : `Apply to ${selectedCount} records`}
            </button>
          </div>
        </div>
      </div>
    </>
  );
};

export default BulkFieldCommandModal;
