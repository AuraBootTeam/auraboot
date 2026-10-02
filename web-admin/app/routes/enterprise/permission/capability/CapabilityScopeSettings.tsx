import { useEffect, useRef, useMemo, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import { modelService } from '~/shared/services/modelService';
import { permissionService } from '~/shared/services/permissionService';
import { SCOPE_OPTIONS, scopeOption, isValidScope } from '../scopeConfig';
import type { PermissionMatrixDTO } from '../types';
import type { Capability } from './types';

/** Keeps existing per-action scope authoring next to the business capability, without atomic grants. */
export default function CapabilityScopeSettings({
  rolePid,
  capability,
  matrix,
  onClose,
  onRefresh,
  onBusy,
  onReadFailure,
}: {
  rolePid: string;
  capability: Capability;
  matrix: PermissionMatrixDTO | null;
  onClose: () => void;
  onRefresh: () => Promise<PermissionMatrixDTO>;
  onBusy: (busy: boolean) => void;
  onReadFailure: () => void;
}) {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [pending, setPending] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [modelNames, setModelNames] = useState<Record<string, string>>({});
  const [namesLoading, setNamesLoading] = useState(true);
  const [namesError, setNamesError] = useState(false);
  const dialogRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    dialogRef.current?.querySelector<HTMLElement>('select, button')?.focus();
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !saving) onClose();
      if (event.key !== 'Tab') return;
      const elements = dialogRef.current?.querySelectorAll<HTMLElement>(
        'button:not(:disabled), select:not(:disabled)',
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
  }, [onClose, saving]);

  const rows = useMemo(
    () =>
      (matrix?.modules ?? []).flatMap((module) =>
        module.resources.flatMap((resource) =>
          resource.actions
            .filter(
              (action) =>
                action.granted &&
                capability.includes.includes(action.code) &&
                (action.code.startsWith('model.') || action.scopeType != null),
            )
            .map((action) => ({
              ...action,
              resourceCode: resource.resourceCode,
              resourceName: resource.resourceName,
            })),
        ),
      ),
    [matrix, capability],
  );
  useEffect(() => {
    let current = true;
    setNamesLoading(true);
    setNamesError(false);
    const codes = [...new Set(rows.filter((row) => row.code.startsWith('model.'))
      .map((row) => row.resourceCode))];
    void Promise.all(codes.map(async (code) => {
      const model = await modelService.findByCode(code);
      if (!model.displayName || model.displayName === code)
        throw new Error('Model has no business display name');
      return [code, model.displayName] as const;
    })).then((names) => {
      if (current) setModelNames(Object.fromEntries(names));
    }).catch(() => {
      if (current) setNamesError(true);
    }).finally(() => {
      if (current) setNamesLoading(false);
    });
    return () => { current = false; };
  }, [rows]);
  const actionNames: Record<string, string> = {
    read: t('common.view', undefined, 'View'),
    create: t('action.create', undefined, 'Create'),
    update: t('action.update', undefined, 'Update'),
    delete: t('action.delete', undefined, 'Delete'),
    import: t('action.import', undefined, 'Import'),
    export: t('action.export', undefined, 'Export'),
  };
  const rowLabel = (row: typeof rows[number]) => row.code.startsWith('model.')
    ? `${modelNames[row.resourceCode]} · ${actionNames[row.action] ?? row.label}`
    : row.label;
  const apply = async () => {
    const changes = rows.filter(
      (row) => pending[row.code] != null && pending[row.code] !== row.scopeType,
    );
    if (!changes.length || saving || namesLoading || namesError || changes.some((row) => !isValidScope(pending[row.code])))
      return;
    setSaving(true);
    onBusy(true);
    try {
      // The existing API applies one resource/action at a time. Readback is authoritative;
      // any interrupted batch hides the snapshot rather than claiming an atomic rollback.
      for (const row of changes)
        await permissionService.updateScope(rolePid, {
          resourceCode: row.resourceCode,
          actionCode: row.action,
          scopeType: pending[row.code],
        });
      const refreshed = await onRefresh();
      const actions = refreshed.modules.flatMap((module) =>
        module.resources.flatMap((resource) => resource.actions),
      );
      if (
        changes.some(
          (row) =>
            actions.find((action) => action.code === row.code)?.scopeType !== pending[row.code],
        )
      ) {
        throw new Error('Scope readback did not match the requested values');
      }
      showSuccessToast(t('admin.permission.scope.applySuccess', undefined, 'Data scope updated'));
      onClose();
    } catch {
      onReadFailure();
      showErrorToast(
        t(
          'admin.permission.capability.scopeFailureV2',
          undefined,
          'Scope changes could not be verified. Some changes may have been saved; reload before continuing.',
        ),
      );
    } finally {
      setSaving(false);
      onBusy(false);
    }
  };
  return (
    <div
      className="fixed inset-0 z-[1200] flex items-center justify-center bg-black/40 p-4"
      data-testid="capability-scope-dialog"
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="capability-scope-title"
        className="rounded-card bg-panel max-h-[85vh] w-full max-w-xl overflow-auto p-5"
      >
        <h3 id="capability-scope-title" className="text-text font-semibold">
          {capability.label} · {t('admin.permission.capability.scopeV2', undefined, 'Record scope')}
        </h3>
        <p className="text-text-2 my-3 text-xs">
          {t(
            'admin.permission.capability.scopeNoteV2',
            undefined,
            'Adjust existing grants for this capability. Shared actions also affect other capabilities; granting and revoking remain in the capability checklist.',
          )}
        </p>
        {namesLoading && <p role="status">{t('common.loading', undefined, 'Loading…')}</p>}
        {namesError && <p role="alert">{t('admin.permission.capability.scopeNamesErrorV2', undefined, 'Could not load business names. Close and reopen to try again.')}</p>}
        <div className="flex flex-col gap-3">
          {!namesLoading && !namesError && rows.map((row) => {
            const value = pending[row.code] ?? row.scopeType ?? '';
            const option = scopeOption(pending[row.code] ?? row.scopeType);
            return (
              <label key={row.code} className="flex items-center justify-between gap-3 text-sm">
                <span>{rowLabel(row)}</span>
                <select
                  aria-label={rowLabel(row)}
                  data-testid={`capability-scope-${row.code}`}
                  disabled={saving}
                  value={value}
                  onChange={(event) =>
                    setPending((current) => ({ ...current, [row.code]: event.target.value }))
                  }
                  className="border-border rounded-control border p-2"
                >
                  {!isValidScope(value) && (
                    <option value={value} disabled>
                      {t(option.labelKey, undefined, option.labelFallback)}
                    </option>
                  )}
                  {SCOPE_OPTIONS.map((scope) => (
                    <option key={scope.value} value={scope.value}>
                      {t(scope.labelKey, undefined, scope.labelFallback)}
                    </option>
                  ))}
                </select>
              </label>
            );
          })}
        </div>
        {rows.length === 0 && (
          <p className="text-text-2 my-3 text-sm">
            {t(
              'admin.permission.capability.noScopesV2',
              undefined,
              'No granted record actions to configure.',
            )}
          </p>
        )}
        <div className="mt-4 flex justify-end gap-3">
          <button
            type="button"
            disabled={saving}
            onClick={onClose}
            className="rounded-control border-border border px-3 py-2"
          >
            {t('common.cancel', undefined, 'Cancel')}
          </button>
          <button
            type="button"
            data-testid="capability-scope-apply"
            disabled={
              saving || namesLoading || namesError ||
              !rows.some((row) => pending[row.code] && pending[row.code] !== row.scopeType)
            }
            onClick={() => void apply()}
            className="bg-accent rounded-control px-3 py-2 text-white disabled:opacity-50"
          >
            {t('admin.permission.scope.apply', undefined, 'Apply')}
          </button>
        </div>
      </div>
    </div>
  );
}
