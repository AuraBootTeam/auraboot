import { useMemo, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type { CapabilityGroup } from './types';
import type { PermissionMatrixDTO } from '../types';
import { scopeOption } from '../scopeConfig';

/** Read-only role-local diagnostics. Capability membership does not identify historical grant origin. */
export default function CapabilityDiagnostics({
  matrix,
  groups,
}: {
  matrix: PermissionMatrixDTO | null;
  groups: CapabilityGroup[];
}) {
  const { t } = useI18n();
  const [query, setQuery] = useState('');
  const [expanded, setExpanded] = useState(false);
  const capabilities = groups
    .flatMap((group) => group.capabilities)
    .filter((cap) => !cap.conventionDerived);
  const rows = useMemo(
    () =>
      (matrix?.modules ?? []).flatMap((module) =>
        module.resources.flatMap((resource) =>
          resource.actions
            .filter((action) => action.supported)
            .map((action) => ({
              ...action,
              label: t(`permission.${action.code}`, undefined, action.label),
              resourceName: resource.resourceName,
            })),
        ),
      ),
    [matrix, t],
  );
  const filtered = rows.filter((row) =>
    `${row.label} ${row.code} ${row.resourceName}`.toLowerCase().includes(query.toLowerCase()),
  );
  const uncovered = rows.filter(
    (row) => row.granted && !capabilities.some((cap) => cap.includes.includes(row.code)),
  ).length;
  return (
    <details
      data-testid="permission-diagnostics"
      onToggle={(event) => setExpanded(event.currentTarget.open)}
      className="rounded-card border-border border p-4"
    >
      <summary className="text-text cursor-pointer text-sm font-medium">
        {t('admin.permission.diagnostics.titleV2', undefined, 'Permission diagnostics · read only')}
      </summary>
      {expanded && (
        <div className="mt-3 flex flex-col gap-3">
          <p className="text-text-2 text-xs">
            {t(
              'admin.permission.diagnostics.noteV2',
              { total: rows.length, uncovered },
              `${rows.length} actions; ${uncovered} granted actions without declared capability coverage. This is declaration coverage, not grant origin or effective user access.`,
            )}
          </p>
          <input
            aria-label={t(
              'admin.permission.diagnostics.searchV2',
              undefined,
              'Search permission diagnostics',
            )}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            className="rounded-control border-border border p-2 text-sm"
          />
          <div className="max-h-96 overflow-auto">
            <table className="w-full text-left text-xs">
              <thead>
                <tr>
                  <th className="p-2">
                    {t('admin.permission.diagnostics.actionV2', undefined, 'Action')}
                  </th>
                  <th className="p-2">
                    {t('admin.permission.diagnostics.stateV2', undefined, 'Role grant')}
                  </th>
                  <th className="p-2">
                    {t('admin.permission.diagnostics.scopeV2', undefined, 'Record scope')}
                  </th>
                  <th className="p-2">
                    {t(
                      'admin.permission.diagnostics.capabilitiesV2',
                      undefined,
                      'Related capabilities',
                    )}
                  </th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((row) => {
                  const related = capabilities.filter((cap) => cap.includes.includes(row.code));
                  const scope = scopeOption(row.scopeType);
                  return (
                    <tr
                      key={row.code}
                      data-testid={`diagnostic-action-${row.code}`}
                      className="border-border border-t"
                    >
                      <td className="p-2">
                        <div>{row.label}</div>
                        <details>
                          <summary className="text-text-2 cursor-pointer">
                            {t(
                              'admin.permission.diagnostics.codeV2',
                              undefined,
                              'Technical identifier',
                            )}
                          </summary>
                          <code>{row.code}</code>
                        </details>
                      </td>
                      <td className="p-2">
                        {row.granted
                          ? t('admin.permission.diagnostics.grantedV2', undefined, 'Granted')
                          : t('admin.permission.diagnostics.noneV2', undefined, 'Not granted')}
                      </td>
                      <td className="p-2">
                        {row.granted ? t(scope.labelKey, undefined, scope.labelFallback) : '—'}
                      </td>
                      <td className="p-2">
                        {related.length
                          ? related.map((cap) => cap.label).join(' / ')
                          : t(
                              'admin.permission.diagnostics.unmappedV2',
                              undefined,
                              'No declared capability',
                            )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {filtered.length === 0 && (
              <p className="text-text-2 p-3">
                {t('admin.permission.diagnostics.emptyV2', undefined, 'No matching actions')}
              </p>
            )}
          </div>
        </div>
      )}
    </details>
  );
}
