import { useState, useEffect, useRef } from 'react';
import { GlobeAltIcon, XMarkIcon } from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import type { PermissionMatrixDTO } from '../types';
import { SCOPE_OPTIONS, scopeOption, isValidScope } from '../scopeConfig';
import { deriveRoleScope } from '../scopeHelpers';

interface DataScopeBarProps {
  matrix: PermissionMatrixDTO | null;
  defaultScope: string | null;
  applyToCurrent: boolean;
  onStage: (scope: string, applyToCurrent: boolean) => void;
  onOpenChange?: (open: boolean) => void;
  disabled?: boolean;
}

/** Scope edits join the parent role draft; closing this drawer never writes authorization. */
export default function DataScopeBar({
  matrix,
  defaultScope: storedDefault,
  applyToCurrent,
  onStage,
  onOpenChange,
  disabled = false,
}: DataScopeBarProps) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState<string | null>(null);
  const [pendingApply, setPendingApply] = useState(applyToCurrent);
  const drawerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    onOpenChange?.(open);
  }, [open, onOpenChange]);

  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement as HTMLElement | null;
    drawerRef.current?.querySelector<HTMLElement>('button:not(:disabled)')?.focus();
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
      if (event.key !== 'Tab') return;
      const elements = drawerRef.current?.querySelectorAll<HTMLElement>(
        'button:not(:disabled), input:not(:disabled), [tabindex="0"]',
      );
      if (!elements?.length) {
        event.preventDefault();
        return;
      }
      const first = elements[0];
      const last = elements[elements.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', handleKey);
    return () => {
      document.removeEventListener('keydown', handleKey);
      previous?.focus();
    };
  }, [open]);

  const derived = deriveRoleScope(matrix);
  const currentOption = scopeOption(derived);
  const defaultOption = scopeOption(storedDefault);

  const openDrawer = () => {
    setPending(
      isValidScope(storedDefault) ? storedDefault : isValidScope(derived) ? derived : null,
    );
    setPendingApply(applyToCurrent);
    setOpen(true);
  };

  const apply = () => {
    if (!isValidScope(pending) || disabled) return;
    onStage(pending, pendingApply);
    setOpen(false);
  };

  return (
    <>
      <div
        data-testid="data-scope-bar"
        className="rounded-card border-border bg-subtle flex flex-wrap items-center gap-3 border px-4 py-3 dark:border-gray-700 dark:bg-gray-800"
      >
        <GlobeAltIcon className="h-4 w-4 flex-shrink-0 text-blue-600 dark:text-blue-400" />
        <span className="text-xs text-gray-700 dark:text-gray-200">
          {t('admin.permission.scope.draftActions', undefined, 'Draft action scopes')}:{' '}
          <span data-testid="data-scope-current" className="font-medium">
            {t(currentOption.labelKey, undefined, currentOption.labelFallback)}
          </span>
        </span>
        <span className="text-xs text-gray-500">
          {t('admin.permission.scope.default', undefined, 'Role default')}:{' '}
          <span data-testid="data-scope-default">
            {t(defaultOption.labelKey, undefined, defaultOption.labelFallback)}
          </span>
        </span>
        <button
          type="button"
          data-testid="data-scope-modify-btn"
          onClick={openDrawer}
          disabled={disabled}
          className="text-accent ml-auto text-xs font-medium hover:underline disabled:opacity-50"
        >
          {t('admin.permission.scope.modify', undefined, 'Modify scope')} →
        </button>
      </div>

      {open && (
        <div className="fixed inset-0 z-50 flex justify-end" data-testid="data-scope-drawer">
          <div className="absolute inset-0 bg-black/30" onClick={() => setOpen(false)} />
          <div
            ref={drawerRef}
            role="dialog"
            aria-modal="true"
            aria-labelledby="data-scope-title"
            className="relative flex h-full w-[360px] max-w-[90vw] flex-col bg-white shadow-xl dark:bg-gray-900"
          >
            <div className="flex items-center justify-between border-b border-gray-200 px-5 py-3 dark:border-gray-700">
              <h3
                id="data-scope-title"
                className="text-sm font-semibold text-gray-900 dark:text-white"
              >
                {t('admin.permission.scope.drawerTitle', undefined, 'Modify data scope')}
              </h3>
              <button
                type="button"
                data-testid="data-scope-drawer-close"
                onClick={() => setOpen(false)}
                disabled={disabled}
                aria-label={t('common.close', undefined, 'Close')}
                className="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-800"
              >
                <XMarkIcon className="h-5 w-5" />
              </button>
            </div>

            <div className="flex-1 overflow-y-auto px-5 py-4">
              <p className="mb-3 text-xs text-gray-500">
                {t(
                  'admin.permission.scope.draftNote',
                  undefined,
                  'Stage the role default for new grants. Review and save all role changes together.',
                )}
              </p>
              <label className="mb-4 flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  data-testid="data-scope-apply-current"
                  checked={pendingApply}
                  onChange={(event) => setPendingApply(event.target.checked)}
                />
                {t(
                  'admin.permission.scope.applyCurrentDraft',
                  undefined,
                  'Also apply to current grants',
                )}
              </label>
              <div className="flex flex-col gap-1">
                {SCOPE_OPTIONS.map((opt) => (
                  <label
                    key={opt.value}
                    data-testid={`data-scope-option-${opt.value}`}
                    className={`flex cursor-pointer items-center gap-3 rounded-md border px-3 py-2 text-sm ${
                      pending === opt.value
                        ? 'border-blue-400 bg-blue-50 dark:border-blue-500 dark:bg-blue-900/20'
                        : 'border-gray-200 hover:bg-gray-50 dark:border-gray-700 dark:hover:bg-gray-800'
                    }`}
                  >
                    <input
                      type="radio"
                      name="data-scope"
                      value={opt.value}
                      checked={pending === opt.value}
                      disabled={disabled}
                      onChange={() => setPending(opt.value)}
                    />
                    <span
                      className={`inline-flex w-7 items-center justify-center rounded px-0.5 py-0.5 text-[10px] font-bold ${opt.color}`}
                    >
                      {opt.badge}
                    </span>
                    <span className="text-gray-800 dark:text-gray-200">
                      {t(opt.labelKey, undefined, opt.labelFallback)}
                    </span>
                  </label>
                ))}
              </div>
            </div>

            <div className="flex items-center justify-end gap-2 border-t border-gray-200 px-5 py-3 dark:border-gray-700">
              <button
                type="button"
                onClick={() => setOpen(false)}
                disabled={disabled}
                className="rounded-md border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300"
              >
                {t('common.cancel', undefined, 'Cancel')}
              </button>
              <button
                type="button"
                data-testid="data-scope-apply"
                disabled={disabled || !isValidScope(pending)}
                onClick={() => void apply()}
                className="rounded-md bg-blue-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                {t('admin.permission.scope.stageDraft', undefined, 'Add to draft')}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
