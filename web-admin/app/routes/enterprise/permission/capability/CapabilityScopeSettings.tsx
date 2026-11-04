import { getLocalizedText } from '~/utils/i18n';
import { useEffect, useRef, useMemo, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { modelService } from '~/shared/services/modelService';
import { SCOPE_OPTIONS, scopeOption, isValidScope } from '../scopeConfig';
import type { PermissionMatrixDTO } from '../types';
import type { Capability } from './types';
import type { ScopeChange } from './authorizationDraftService';

/** Keeps existing per-action scope authoring next to the business capability, without atomic grants. */
export default function CapabilityScopeSettings({
  capability,
  matrix,
  onClose,
  onStage,
}: {
  capability: Capability;
  matrix: PermissionMatrixDTO | null;
  onClose: () => void;
  onStage: (changes: ScopeChange[], labels: Record<string, string>) => void;
}) {
  const { t, locale } = useI18n();
  const [pending, setPending] = useState<Record<string, string>>({});
  const [modelNames, setModelNames] = useState<Record<string, string>>({});
  const [modelTeams, setModelTeams] = useState<Record<string, boolean>>({});
  const [namesLoading, setNamesLoading] = useState(true);
  const [namesError, setNamesError] = useState(false);
  const dialogRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    dialogRef.current?.querySelector<HTMLElement>('select, button')?.focus();
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
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
  }, [onClose]);

  const rows = useMemo(
    () =>
      (matrix?.modules ?? []).flatMap((module) =>
        module.resources.flatMap((resource) =>
          resource.actions
            .filter(
              (action) =>
                action.granted &&
                capability.includes.includes(action.code) &&
                // The generic command entry checks RBAC only; target models own record scopes.
                action.code !== 'meta.command.execute' &&
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
    const codes = [
      ...new Set(
        rows.filter((row) => row.code.startsWith('model.')).map((row) => row.resourceCode),
      ),
    ];
    void Promise.all(
      codes.map(async (code) => {
        const model = await modelService.findByCode(code);
        if (!model.displayName || model.displayName === code)
          throw new Error('Model has no business display name');
        const scope = model.extension?.dataScope;
        const teamField =
          scope && typeof scope === 'object' && !Array.isArray(scope)
            ? (scope as Record<string, unknown>).teamField
            : null;
        return {
          code,
          name: model.displayName,
          teamSupported: teamField != null && String(teamField).trim() !== '',
        };
      }),
    )
      .then((models) => {
        if (current) {
          setModelNames(Object.fromEntries(models.map((model) => [model.code, model.name])));
          setModelTeams(
            Object.fromEntries(models.map((model) => [model.code, model.teamSupported])),
          );
        }
      })
      .catch(() => {
        if (current) setNamesError(true);
      })
      .finally(() => {
        if (current) setNamesLoading(false);
      });
    return () => {
      current = false;
    };
  }, [rows]);
  const actionNames: Record<string, string> = {
    read: t('common.view', undefined, 'View'),
    create: t('action.create', undefined, 'Create'),
    update: t('action.update', undefined, 'Update'),
    delete: t('action.delete', undefined, 'Delete'),
    import: t('action.import', undefined, 'Import'),
    export: t('action.export', undefined, 'Export'),
  };
  const rowLabel = (row: (typeof rows)[number]) =>
    row.code.startsWith('model.')
      ? `${modelNames[row.resourceCode]} · ${actionNames[row.action] ?? row.label}`
      : `${getLocalizedText(capability.localizedLabels, locale, t) || capability.label} · ${actionNames[row.action] ?? row.label}`;
  const teamUnavailable = (row: (typeof rows)[number]) =>
    row.code.startsWith('model.') && !modelTeams[row.resourceCode];
  const apply = () => {
    const changes = rows.filter(
      (row) => pending[row.code] != null && pending[row.code] !== row.scopeType,
    );
    if (
      !changes.length ||
      namesLoading ||
      namesError ||
      changes.some(
        (row) =>
          !isValidScope(pending[row.code]) ||
          (pending[row.code] === 'team' && teamUnavailable(row)),
      )
    )
      return;
    onStage(
      changes.map((row) => ({
        resourceCode: row.resourceCode,
        actionCode: row.action,
        scopeType: pending[row.code],
        mergeStrategy: row.mergeStrategy,
      })),
      Object.fromEntries(
        changes.map((row) => [`${row.resourceCode}:${row.action}`, rowLabel(row)]),
      ),
    );
    onClose();
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
          {getLocalizedText(capability.localizedLabels, locale, t) || capability.label} ·{' '}
          {t('admin.permission.capability.scopeV2', undefined, 'Record scope')}
        </h3>
        <p className="text-text-2 my-3 text-xs">
          {t(
            'admin.permission.capability.scopeDraftNote',
            undefined,
            'Stage record scopes for this duty. Shared actions affect related duties. All changes are reviewed and saved together.',
          )}
        </p>
        {namesLoading && <p role="status">{t('common.loading', undefined, 'Loading…')}</p>}
        {namesError && (
          <p role="alert">
            {t(
              'admin.permission.capability.scopeNamesErrorV2',
              undefined,
              'Could not load business names. Close and reopen to try again.',
            )}
          </p>
        )}
        <div className="flex flex-col gap-3">
          {!namesLoading &&
            !namesError &&
            rows.map((row) => {
              const value = pending[row.code] ?? row.scopeType ?? '';
              const option = scopeOption(pending[row.code] ?? row.scopeType);
              return (
                <div key={row.code} className="text-sm">
                  <div className="flex items-center justify-between gap-3">
                    <span>{rowLabel(row)}</span>
                    <select
                      aria-label={rowLabel(row)}
                      aria-describedby={
                        teamUnavailable(row) ? `scope-team-note-${row.code}` : undefined
                      }
                      data-testid={`capability-scope-${row.code}`}
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
                        <option
                          key={scope.value}
                          value={scope.value}
                          disabled={scope.value === 'team' && teamUnavailable(row)}
                        >
                          {t(scope.labelKey, undefined, scope.labelFallback)}
                        </option>
                      ))}
                    </select>
                  </div>
                  {teamUnavailable(row) && (
                    <p
                      id={`scope-team-note-${row.code}`}
                      data-testid={`capability-scope-team-note-${row.code}`}
                      className="text-text-2 mt-1 text-xs"
                    >
                      {t(
                        'admin.permission.capability.teamUnavailableV2',
                        undefined,
                        'This record type has no team association configured, so My teams cannot be selected. An existing My teams scope grants no records by itself; other roles or valid shares may still allow access.',
                      )}
                    </p>
                  )}
                </div>
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
            onClick={onClose}
            className="rounded-control border-border border px-3 py-2"
          >
            {t('common.cancel', undefined, 'Cancel')}
          </button>
          <button
            type="button"
            data-testid="capability-scope-apply"
            disabled={
              namesLoading ||
              namesError ||
              !rows.some((row) => pending[row.code] && pending[row.code] !== row.scopeType)
            }
            onClick={() => void apply()}
            className="bg-accent rounded-control px-3 py-2 text-white disabled:opacity-50"
          >
            {t('admin.permission.scope.stageDraft', undefined, 'Add to draft')}
          </button>
        </div>
      </div>
    </div>
  );
}
