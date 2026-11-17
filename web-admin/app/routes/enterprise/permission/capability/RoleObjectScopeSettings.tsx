import { useEffect, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { modelService } from '~/shared/services/modelService';
import { SCOPE_OPTIONS, scopeOption } from '../scopeConfig';
import type { PermissionMatrixDTO } from '../types';
import type { ScopeChange } from './authorizationDraftService';

interface Props {
  model: string;
  label: string;
  permissionCodes: string[];
  matrix: PermissionMatrixDTO | null;
  disabled: boolean;
  onStage: (changes: ScopeChange[], labels: Record<string, string>) => void;
}

/** Edits only granted actions for the selected object, within the shared role draft. */
export default function RoleObjectScopeSettings({
  model,
  label,
  permissionCodes,
  matrix,
  disabled,
  onStage,
}: Props) {
  const { t } = useI18n();
  const [action, setAction] = useState('all');
  const [team, setTeam] = useState(false);
  const [metadataLoading, setMetadataLoading] = useState(true);
  const [metadataError, setMetadataError] = useState(false);
  useEffect(() => {
    let current = true;
    setAction('all');
    setTeam(false);
    setMetadataLoading(true);
    setMetadataError(false);
    void modelService
      .findByCode(model)
      .then((definition) => {
        const scope = definition.extension?.dataScope;
        const field =
          scope && typeof scope === 'object' && !Array.isArray(scope)
            ? (scope as Record<string, unknown>).teamField
            : null;
        if (current) setTeam(typeof field === 'string' && field.trim().length > 0);
      })
      .catch(() => {
        if (current) setMetadataError(true);
      })
      .finally(() => {
        if (current) setMetadataLoading(false);
      });
    return () => {
      current = false;
    };
  }, [model]);
  const rows = (matrix?.modules ?? []).flatMap((group) =>
    group.resources.flatMap((resource) =>
      resource.actions
        .filter(
          (entry) =>
            entry.granted &&
            entry.code !== 'meta.command.execute' &&
            (resource.resourceCode === model || permissionCodes.includes(entry.code)),
        )
        .map((entry) => ({ ...entry, resourceCode: resource.resourceCode })),
    ),
  );
  const selected = action === 'all' ? rows : rows.filter((row) => row.action === action);
  const scopes = new Set(selected.map((row) => row.scopeType ?? ''));
  const value = scopes.size === 1 ? [...scopes][0] : scopes.size > 1 ? 'mixed' : '';
  const actionNames: Record<string, string> = {
    read: t('common.view', undefined, 'View'),
    create: t('action.create', undefined, 'Create'),
    update: t('action.update', undefined, 'Update'),
    delete: t('action.delete', undefined, 'Delete'),
    import: t('action.import', undefined, 'Import'),
    export: t('action.export', undefined, 'Export'),
    manage: t('rbacExperience.maintain', undefined, 'Maintain'),
  };
  const actions = [...new Set(rows.map((row) => row.action))];
  const actionLabel = (code: string) =>
    actionNames[code] ?? t('rbacExperience.businessAction', undefined, 'Business operation');
  const choice = scopeOption(value || null);
  return (
    <section data-testid="role-object-scope" className="border-border space-y-3 border-b pb-5">
      <h3 className="text-text-1 text-sm font-semibold">
        {label} · {t('rbacExperience.recordScope', undefined, 'Record scope')}
      </h3>
      <p className="text-text-2 text-xs">
        {t(
          'rbacExperience.scopeHelp',
          undefined,
          'Record scope restricts existing business access. Changing the object or action does not change grants; edits are reviewed with this role draft.',
        )}
      </p>
      {metadataLoading && (
        <p role="status" className="text-text-2 text-xs">
          {t('common.loading', undefined, 'Loading…')}
        </p>
      )}
      {metadataError && (
        <p role="alert" className="text-xs text-red-700">
          {t(
            'rbacExperience.scopeMetadataError',
            undefined,
            'Object metadata could not be verified. Existing scope is preserved; reload before editing.',
          )}
        </p>
      )}
      {!rows.length ? (
        <p className="text-text-2 text-sm">
          {t(
            'rbacExperience.noScopeActions',
            undefined,
            'No granted record actions for this object. Assign the required business duty first.',
          )}
        </p>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2">
          <label className="text-text-2 space-y-1 text-xs">
            <span>{t('rbacExperience.accessibleRecords', undefined, 'Accessible records')}</span>
            <select
              data-testid="role-object-scope-type"
              className="border-border bg-panel text-text-1 rounded-control block w-full border px-3 py-2 text-sm"
              value={value}
              disabled={disabled || metadataLoading || metadataError}
              onChange={(event) => {
                const type = event.target.value;
                if (
                  !SCOPE_OPTIONS.some((item) => item.value === type) ||
                  (type === 'team' && !team)
                )
                  return;
                onStage(
                  selected.map((row) => ({
                    resourceCode: row.resourceCode,
                    actionCode: row.action,
                    scopeType: type,
                    mergeStrategy: row.mergeStrategy,
                  })),
                  Object.fromEntries(
                    selected.map((row) => [
                      `${row.resourceCode}:${row.action}`,
                      `${label} · ${actionLabel(row.action)}`,
                    ]),
                  ),
                );
              }}
            >
              {!SCOPE_OPTIONS.some((item) => item.value === value) && (
                <option value={value} disabled>
                  {value === 'mixed'
                    ? t('rbacExperience.mixedScopes', undefined, 'Different scopes by action')
                    : t(choice.labelKey, undefined, choice.labelFallback)}
                </option>
              )}
              {SCOPE_OPTIONS.map((item) => (
                <option
                  key={item.value}
                  value={item.value}
                  disabled={item.value === 'team' && !team}
                >
                  {t(item.labelKey, undefined, item.labelFallback)}
                </option>
              ))}
            </select>
          </label>
          <label className="text-text-2 space-y-1 text-xs">
            <span>{t('rbacExperience.appliesToActions', undefined, 'Applies to actions')}</span>
            <select
              data-testid="role-object-scope-action"
              className="border-border bg-panel text-text-1 rounded-control block w-full border px-3 py-2 text-sm"
              value={action}
              onChange={(event) => setAction(event.target.value)}
            >
              <option value="all">
                {t(
                  'rbacExperience.allGrantedActions',
                  undefined,
                  'All granted actions for this object',
                )}
              </option>
              {actions.map((code) => (
                <option key={code} value={code}>
                  {actionLabel(code)}
                </option>
              ))}
            </select>
          </label>
        </div>
      )}
      {!team && !metadataLoading && (
        <p className="text-text-2 text-xs">
          {t(
            'rbacExperience.teamUnavailable',
            undefined,
            'This object has no team ownership field; team scope is unavailable.',
          )}
        </p>
      )}
      <p className="text-text-2 text-xs">
        {t(
          'rbacExperience.existingConditions',
          undefined,
          'Existing grant conditions remain effective. This control does not replace them with a fixed example or remove them.',
        )}
      </p>
    </section>
  );
}
