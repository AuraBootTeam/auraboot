import { useState } from 'react';
import { GlobeAltIcon, XMarkIcon } from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import { permissionService } from '~/shared/services/permissionService';
import type { PermissionMatrixDTO } from '../types';
import { SCOPE_OPTIONS, scopeOption } from '../scopeConfig';
import { deriveRoleScope, grantedActions } from '../scopeHelpers';

interface DataScopeBarProps {
  rolePid: string;
  matrix: PermissionMatrixDTO | null;
  /** Called after a bulk scope apply so the parent can refetch the matrix. */
  onScopeApplied: () => void;
}

/**
 * ② Data-scope dimension, pulled out of the matrix cells into its own top bar + drawer. The bar
 * summarizes the role's data scope (uniform value, or "mixed"); the drawer applies a chosen scope to
 * every permission the role currently holds. Per-permission overrides live in the ③ advanced table.
 */
export default function DataScopeBar({ rolePid, matrix, onScopeApplied }: DataScopeBarProps) {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [open, setOpen] = useState(false);
  const [applying, setApplying] = useState(false);

  const current = deriveRoleScope(matrix);
  const granted = grantedActions(matrix);
  const isMixed = current === 'mixed';
  const currentLabel = isMixed
    ? t('admin.permission.scope.mixed', undefined, '多种范围')
    : t(scopeOption(current).labelKey, undefined, scopeOption(current).labelFallback);

  const [pending, setPending] = useState<string>(isMixed ? 'dept_and_sub' : current);

  const openDrawer = () => {
    setPending(isMixed ? 'dept_and_sub' : current);
    setOpen(true);
  };

  const apply = async () => {
    if (granted.length === 0) {
      showErrorToast(t('admin.permission.scope.noGrants', undefined, 'Grant permissions first, then set the data scope'));
      return;
    }
    setApplying(true);
    try {
      await Promise.all(
        granted.map((g) =>
          permissionService.updateScope(rolePid, {
            resourceCode: g.resourceCode,
            actionCode: g.actionCode,
            scopeType: pending,
          }),
        ),
      );
      showSuccessToast(t('admin.permission.scope.applySuccess', undefined, 'Data scope updated'));
      setOpen(false);
      onScopeApplied();
    } catch {
      showErrorToast(t('admin.permission.scope.applyError', undefined, 'Failed to update data scope'));
    } finally {
      setApplying(false);
    }
  };

  return (
    <>
      <div
        data-testid="data-scope-bar"
        className="flex items-center gap-3 rounded-md border border-blue-100 bg-blue-50/50 px-4 py-2.5 dark:border-blue-900/40 dark:bg-blue-900/10"
      >
        <GlobeAltIcon className="h-4 w-4 flex-shrink-0 text-blue-600 dark:text-blue-400" />
        <span className="text-xs text-gray-700 dark:text-gray-200">
          {t('admin.permission.scope.barLabel', undefined, 'Management scope (data scope)')}:{' '}
          <span data-testid="data-scope-current" className="font-medium">
            {currentLabel}
          </span>
        </span>
        <span className="hidden text-[11px] text-gray-400 sm:inline">
          {t('admin.permission.scope.barHint', undefined, 'Which records can be managed — separate from what can be done')}
        </span>
        <button
          type="button"
          data-testid="data-scope-modify-btn"
          onClick={openDrawer}
          className="ml-auto text-xs font-medium text-blue-600 hover:underline dark:text-blue-400"
        >
          {t('admin.permission.scope.modify', undefined, 'Modify scope')} →
        </button>
      </div>

      {open && (
        <div className="fixed inset-0 z-50 flex justify-end" data-testid="data-scope-drawer">
          <div className="absolute inset-0 bg-black/30" onClick={() => !applying && setOpen(false)} />
          <div className="relative flex h-full w-[360px] max-w-[90vw] flex-col bg-white shadow-xl dark:bg-gray-900">
            <div className="flex items-center justify-between border-b border-gray-200 px-5 py-3 dark:border-gray-700">
              <h3 className="text-sm font-semibold text-gray-900 dark:text-white">
                {t('admin.permission.scope.drawerTitle', undefined, 'Modify data scope')}
              </h3>
              <button
                type="button"
                data-testid="data-scope-drawer-close"
                onClick={() => setOpen(false)}
                className="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-800"
              >
                <XMarkIcon className="h-5 w-5" />
              </button>
            </div>

            <div className="flex-1 overflow-y-auto px-5 py-4">
              <p className="mb-3 text-xs text-gray-500">
                {t(
                  'admin.permission.scope.drawerNote',
                  undefined,
                  'Applies to all permissions the role currently holds.',
                )}
              </p>
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
                className="rounded-md border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300"
              >
                {t('common.cancel', undefined, 'Cancel')}
              </button>
              <button
                type="button"
                data-testid="data-scope-apply"
                disabled={applying}
                onClick={() => void apply()}
                className="rounded-md bg-blue-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                {applying
                  ? t('admin.permission.scope.applying', undefined, 'Applying…')
                  : t('admin.permission.scope.apply', undefined, 'Apply')}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
