import { useEffect, useRef, useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  content: string;
  confirmText?: string;
  cancelText?: string;
  variant?: 'default' | 'danger';
  onConfirm: () => void;
  onCancel: () => void;
}

export default function ConfirmDialog({
  open,
  title,
  content,
  confirmText,
  cancelText,
  variant = 'default',
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const { t } = useI18n();
  const confirmBtnRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);

  const resolvedConfirmText = confirmText || t('common.confirm') || 'Confirm';
  const resolvedCancelText = cancelText || t('common.cancel') || 'Cancel';

  // Focus trap: keep Tab cycling within the dialog (22.1 fix)
  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onCancel();
        return;
      }

      if (e.key === 'Tab' && dialogRef.current) {
        const focusable = dialogRef.current.querySelectorAll<HTMLElement>(
          'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
        );
        if (focusable.length === 0) return;

        const first = focusable[0];
        const last = focusable[focusable.length - 1];

        if (e.shiftKey) {
          if (document.activeElement === first) {
            e.preventDefault();
            last.focus();
          }
        } else {
          if (document.activeElement === last) {
            e.preventDefault();
            first.focus();
          }
        }
      }
    },
    [onCancel],
  );

  useEffect(() => {
    if (!open) return;

    confirmBtnRef.current?.focus();
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [open, handleKeyDown]);

  if (!open) return null;

  const confirmBtnClass =
    variant === 'danger'
      ? 'bg-red-600 hover:bg-red-700 focus:ring-red-500 text-white'
      : 'bg-accent hover:bg-accent-hover focus:ring-blue-500 text-white';

  const titleId = 'confirm-dialog-title';
  const descId = 'confirm-dialog-desc';

  return (
    <div
      className="fixed inset-0 z-[1200] flex items-center justify-center"
      data-testid="confirm-dialog"
    >
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/50 transition-opacity duration-200"
        onClick={onCancel}
      />
      {/* Dialog — ARIA attributes added (22.2 fix) */}
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descId}
        className="rounded-card bg-panel relative mx-4 w-full max-w-md scale-100 transform opacity-100 shadow-xl transition-all duration-200 dark:bg-gray-800"
      >
        <div className="p-6">
          <h3 id={titleId} className="text-text mb-2 text-lg font-semibold dark:text-white">
            {title}
          </h3>
          <p id={descId} className="text-text-2 text-sm whitespace-pre-wrap dark:text-gray-300">
            {content}
          </p>
        </div>
        <div className="flex justify-end gap-3 px-6 pb-6">
          <button
            data-testid="confirm-cancel"
            onClick={onCancel}
            className="rounded-card border-border-strong bg-panel text-text-2 hover:bg-subtle border px-4 py-2 text-sm font-medium transition-colors focus:ring-2 focus:ring-gray-400 focus:ring-offset-2 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
          >
            {resolvedCancelText}
          </button>
          <button
            ref={confirmBtnRef}
            data-testid="confirm-ok"
            onClick={onConfirm}
            className={`rounded-card px-4 py-2 text-sm font-medium transition-colors focus:ring-2 focus:ring-offset-2 focus:outline-none ${confirmBtnClass}`}
          >
            {resolvedConfirmText}
          </button>
        </div>
      </div>
    </div>
  );
}
