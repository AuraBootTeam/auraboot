import { getLocalizedText } from '~/utils/i18n';
import { useI18n } from '~/contexts/I18nContext';
import type { CapabilityGroup, Capability } from './types';
import type { PermissionMatrixDTO } from '../types';
import CapabilityPermissionDetails from './CapabilityPermissionDetails';

interface CapabilityChecklistProps {
  groups: CapabilityGroup[];
  catalog?: CapabilityGroup[];
  matrix?: PermissionMatrixDTO | null;
  projectedMatrix?: PermissionMatrixDTO | null;
  /** Currently selected capability codes. */
  selected: string[];
  /** Called with a capability code when its checkbox is toggled. */
  onToggle: (code: string) => void;
  disabled?: boolean;
  revokedPartial?: string[];
  onRevokePartial?: (code: string) => void;
  onConfigureScope?: (capability: Capability) => void;
  scopeConfigurableCodes?: Set<string>;
}

/**
 * Permission v2 capability checklist: business-language capabilities folded by group, each a
 * checkbox, sensitive ones marked with a lock. Presentational — selection state and persistence
 * live in the parent (role editor). Replaces the raw resource x action matrix as the primary view;
 * atomic actions remain available as read-only diagnostics.
 */
export default function CapabilityChecklist({
  groups,
  catalog = groups,
  matrix,
  projectedMatrix,
  selected,
  onToggle,
  disabled = false,
  revokedPartial = [],
  onRevokePartial,
  onConfigureScope,
  scopeConfigurableCodes,
}: CapabilityChecklistProps) {
  const { t, locale } = useI18n();
  const selectedSet = new Set(selected);

  return (
    <div data-testid="capability-checklist" className="flex flex-col gap-5">
      <p className="text-text-2 text-xs leading-5">
        {t(
          'permission.capability.details.guidance',
          undefined,
          'Select duties for authorization. Permission counts describe configuration coverage; actual access also depends on record scope, fields and runtime conditions.',
        )}
      </p>
      {groups.some((group) =>
        group.capabilities.some(
          (cap) =>
            cap.authorizationState === 'partial' &&
            !selectedSet.has(cap.code) &&
            !revokedPartial.includes(cap.code),
        ),
      ) && (
        <p
          data-testid="capability-partial-guidance"
          className="text-text-2 col-span-full text-xs leading-5"
        >
          {t(
            'permission.capability.details.partialGuidance',
            undefined,
            'Permission items may be shared by other duties. Partial configuration does not grant the complete duty. Select to complete; review revocation effects before saving.',
          )}
        </p>
      )}
      {groups.map((group) => {
        const total = group.capabilities.length;
        const granted = group.capabilities.filter((cap) => selectedSet.has(cap.code)).length;
        return (
          <fieldset
            key={group.group}
            data-testid={`capability-group-${group.group}`}
            className="border-border bg-panel rounded-control border px-4 py-3"
          >
            <legend className="px-1 text-sm font-medium text-gray-900">
              {/* Declared groups carry a business bucket name (e.g. 客户管理) — t() misses and falls
                  back to it. Convention-derived groups carry the raw module code (billing/ai/iot) —
                  localized here via the existing permission.module.<code> i18n. */}
              {group.group === null
                ? t('permission.capability.ungrouped', undefined, 'Other capabilities')
                : t(`permission.module.${group.group}`, undefined, group.group)}
              <span className="ml-2 text-xs font-normal text-gray-400">
                {t(
                  'permission.capability.details.groupSummary',
                  { granted, total },
                  `Selected duties ${granted}/${total}`,
                )}
              </span>
            </legend>
            <div className="divide-border flex flex-col divide-y">
              {group.capabilities.map((cap) => (
                <div
                  key={cap.code}
                  data-testid={`capability-${cap.code}`}
                  title={
                    getLocalizedText(cap.localizedDescriptions, locale, t) ||
                    cap.description ||
                    undefined
                  }
                  className="text-text-1 flex cursor-pointer items-start gap-3 py-3 text-sm"
                >
                  <input
                    type="checkbox"
                    id={`capability-input-${cap.code}`}
                    className="mt-1"
                    data-testid={`capability-checkbox-${cap.code}`}
                    checked={selectedSet.has(cap.code)}
                    ref={(input) => {
                      if (input)
                        input.indeterminate =
                          cap.authorizationState === 'partial' &&
                          !selectedSet.has(cap.code) &&
                          !revokedPartial.includes(cap.code);
                    }}
                    aria-checked={
                      cap.authorizationState === 'partial' &&
                      !selectedSet.has(cap.code) &&
                      !revokedPartial.includes(cap.code)
                        ? 'mixed'
                        : selectedSet.has(cap.code)
                    }
                    disabled={disabled}
                    aria-label={getLocalizedText(cap.localizedLabels, locale, t) || cap.label}
                    onChange={() => onToggle(cap.code)}
                  />
                  <div className="flex min-w-0 flex-1 flex-col gap-1">
                    <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
                      <label htmlFor={`capability-input-${cap.code}`} className="cursor-pointer">
                        {getLocalizedText(cap.localizedLabels, locale, t) || cap.label}
                      </label>
                      {cap.authorizationState === 'partial' &&
                        !selectedSet.has(cap.code) &&
                        !revokedPartial.includes(cap.code) && (
                          <span
                            data-testid={`capability-partial-${cap.code}`}
                            className="text-text-2 text-xs"
                          >
                            {t(
                              'permission.capability.details.coverage',
                              {
                                granted: cap.includes.length - (cap.missingCodes?.length ?? 0),
                                total: cap.includes.length,
                              },
                              `Permission items ${cap.includes.length - (cap.missingCodes?.length ?? 0)}/${cap.includes.length}`,
                            )}
                          </span>
                        )}
                      {cap.sensitive && (
                        <span
                          data-testid={`capability-sensitive-${cap.code}`}
                          className="rounded bg-amber-50 px-1.5 py-0.5 text-[11px] text-amber-800"
                          title={t('permission.capability.sensitive', undefined, 'Sensitive')}
                          aria-label={t('permission.capability.sensitive', undefined, '敏感')}
                        >
                          {t('permission.capability.sensitive', undefined, 'Sensitive')}
                        </span>
                      )}
                      {cap.authorizationState === 'partial' &&
                        onRevokePartial &&
                        !selectedSet.has(cap.code) && (
                          <button
                            type="button"
                            data-testid={`capability-revoke-partial-${cap.code}`}
                            disabled={disabled}
                            onClick={() => onRevokePartial(cap.code)}
                            className={
                              revokedPartial.includes(cap.code)
                                ? 'text-left text-xs text-amber-700 hover:underline'
                                : 'text-left text-xs text-gray-500 hover:text-red-700 hover:underline'
                            }
                          >
                            {revokedPartial.includes(cap.code)
                              ? t(
                                  'admin.permission.capability.undoRevokeV2',
                                  undefined,
                                  'Pending revocation · undo',
                                )
                              : t(
                                  'admin.permission.capability.revokePartialV2',
                                  undefined,
                                  'Revoke existing grants',
                                )}
                          </button>
                        )}
                    </span>
                    {(getLocalizedText(cap.localizedDescriptions, locale, t) ||
                      cap.description) && (
                      <p className="text-text-2 text-xs leading-5">
                        {getLocalizedText(cap.localizedDescriptions, locale, t) || cap.description}
                      </p>
                    )}
                    {onConfigureScope && scopeConfigurableCodes?.has(cap.code) && (
                      <button
                        type="button"
                        data-testid={`capability-scope-configure-${cap.code}`}
                        disabled={disabled}
                        onClick={() => onConfigureScope(cap)}
                        className="text-accent text-left text-xs hover:underline"
                      >
                        {t('admin.permission.capability.scopeV2', undefined, 'Record scope')}
                      </button>
                    )}
                    <CapabilityPermissionDetails
                      capability={cap}
                      catalog={catalog}
                      matrix={matrix}
                      projectedMatrix={projectedMatrix}
                    />
                    {cap.unlockedMenus && cap.unlockedMenus.length > 0 && (
                      <details
                        data-testid={`capability-menus-${cap.code}`}
                        className="text-xs text-gray-500"
                      >
                        <summary className="cursor-pointer hover:text-gray-700">
                          {t(
                            'roleNavigation.relatedPages',
                            undefined,
                            'Related pages (reference only)',
                          )}
                          {' · '}
                          {cap.unlockedMenus.length}
                        </summary>
                        <ul className="mt-1 flex flex-wrap gap-1">
                          {cap.unlockedMenus.map((m) => (
                            <li key={m} className="rounded bg-gray-100 px-1.5 py-0.5">
                              {m}
                            </li>
                          ))}
                        </ul>
                      </details>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </fieldset>
        );
      })}
    </div>
  );
}
