import { useMemo, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/utils/i18n';
import type { PermissionMatrixDTO } from '../types';
import type { Capability, CapabilityGroup } from './types';

interface Props {
  capability: Capability;
  catalog: CapabilityGroup[];
  matrix?: PermissionMatrixDTO | null;
  projectedMatrix?: PermissionMatrixDTO | null;
}

/** Read-only configuration diagnostics; neither declarations nor this projection prove access. */
export default function CapabilityPermissionDetails({
  capability,
  catalog,
  matrix,
  projectedMatrix,
}: Props) {
  const { t, locale } = useI18n();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const rows = useMemo(() => {
    if (!open) return [];
    const index = (value?: PermissionMatrixDTO | null) =>
      new Map(
        value?.modules.flatMap((module) =>
          module.resources.flatMap((resource) =>
            resource.actions.map(
              (action) =>
                [action.code, { ...action, resourceName: resource.resourceName }] as const,
            ),
          ),
        ) ?? [],
      );
    const saved = index(matrix);
    const projected = index(projectedMatrix);
    const declarations = catalog
      .flatMap((group) => group.capabilities)
      .filter((cap) => !cap.conventionDerived && cap.code !== capability.code);
    return capability.includes.map((code) => ({
      code,
      definition: saved.get(code),
      saved: capability.missingCodes
        ? !capability.missingCodes.includes(code)
        : saved.get(code)?.granted,
      projected: projected.get(code)?.granted,
      shared: declarations.filter((cap) => cap.includes.includes(code)),
    }));
  }, [open, capability, catalog, matrix, projectedMatrix]);
  const normalized = query.trim().toLocaleLowerCase(locale);
  const filtered = rows.filter((row) =>
    [row.code, row.definition?.label, row.definition?.resourceName].some((value) =>
      value?.toLocaleLowerCase(locale).includes(normalized),
    ),
  );
  return (
    <details
      data-testid={`capability-permissions-${capability.code}`}
      className="text-text-2 min-w-0 text-xs"
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary className="text-accent cursor-pointer py-1">
        {t('permission.capability.details.title', undefined, 'Permission details')}
        {' · '}
        {capability.includes.length}
      </summary>
      {open && (
        <div className="border-border bg-subtle rounded-control mt-1 border p-3">
          <p className="mb-3 leading-5">
            {t(
              'permission.capability.details.draftNote',
              undefined,
              'Saved configuration and local draft are shown separately. The server confirms draft changes before saving; these counts do not prove runtime access.',
            )}
          </p>
          <label className="mb-3 flex flex-col gap-1">
            {t(
              'permission.capability.details.search',
              undefined,
              'Filter by name or permission code',
            )}
            <input
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              className="border-border bg-panel rounded-control text-text-1 border px-2 py-1.5"
            />
          </label>
          <ul
            className="divide-border max-h-80 divide-y overflow-y-auto"
            aria-label={t('permission.capability.details.title', undefined, 'Permission details')}
          >
            {filtered.map((row) => (
              <li
                key={row.code}
                data-testid={`capability-permission-${capability.code}-${row.code}`}
                className="space-y-1 py-2"
              >
                <p className="text-text-1 font-medium">
                  {(row.definition?.label &&
                  !/[a-z0-9]+_[a-z0-9_]+/i.test(row.definition.label) &&
                  row.definition.label !== row.code
                    ? row.definition.label
                    : undefined) ||
                    t(
                      'permission.capability.details.undefined',
                      undefined,
                      'Definition unavailable; verify this permission',
                    )}
                </p>
                <code className="block font-mono text-[11px] break-all">{row.code}</code>
                <div className="flex flex-wrap gap-x-4 gap-y-1">
                  <span>
                    {t('permission.capability.details.saved', undefined, 'Saved')}
                    {': '}
                    {row.saved === undefined
                      ? t('permission.capability.details.unknown', undefined, 'Unresolved')
                      : row.saved
                        ? t('permission.capability.details.configured', undefined, 'Configured')
                        : t('permission.capability.details.missing', undefined, 'Not configured')}
                  </span>
                  <span>
                    {t('permission.capability.details.draft', undefined, 'Draft')}
                    {': '}
                    {row.projected === undefined
                      ? t('permission.capability.details.unknown', undefined, 'Unresolved')
                      : row.saved === undefined
                        ? t('permission.capability.details.unknown', undefined, 'Unresolved')
                        : row.projected === row.saved
                          ? t('permission.capability.details.unchanged', undefined, 'Unchanged')
                          : row.projected
                            ? t('permission.capability.details.add', undefined, 'Will grant')
                            : t('permission.capability.details.remove', undefined, 'Will revoke')}
                  </span>
                </div>
                {row.shared.length > 0 && (
                  <details className="pt-1">
                    <summary className="cursor-pointer">
                      {t(
                        'permission.capability.details.shared',
                        { count: row.shared.length },
                        `Also included by ${row.shared.length} duties (declarations, not grant provenance)`,
                      )}
                    </summary>
                    <ul className="mt-1 max-h-32 list-inside list-disc overflow-y-auto">
                      {row.shared.map((cap) => (
                        <li key={cap.code}>
                          {getLocalizedText(cap.localizedLabels, locale, t) || cap.label}
                        </li>
                      ))}
                    </ul>
                  </details>
                )}
              </li>
            ))}
          </ul>
          {filtered.length === 0 && (
            <p role="status" className="py-2">
              {t('permission.capability.details.empty', undefined, 'No matching permission items')}
            </p>
          )}
        </div>
      )}
    </details>
  );
}
