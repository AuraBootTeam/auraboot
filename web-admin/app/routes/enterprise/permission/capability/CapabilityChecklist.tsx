import { useI18n } from '~/contexts/I18nContext';
import type { CapabilityGroup } from './types';
import { groupSummary } from './capabilityHelpers';

interface CapabilityChecklistProps {
  groups: CapabilityGroup[];
  /** Currently selected capability codes. */
  selected: string[];
  /** Called with a capability code when its checkbox is toggled. */
  onToggle: (code: string) => void;
}

/**
 * Permission v2 capability checklist: business-language capabilities folded by group, each a
 * checkbox, sensitive ones marked with a lock. Presentational — selection state and persistence
 * live in the parent (role editor). Replaces the raw resource x action matrix as the primary view;
 * the matrix stays as an advanced "escape hatch".
 */
export default function CapabilityChecklist({ groups, selected, onToggle }: CapabilityChecklistProps) {
  const { t } = useI18n();
  const selectedSet = new Set(selected);

  return (
    <div data-testid="capability-checklist" className="flex flex-col gap-4">
      {groups.map((group) => {
        const { granted, total } = groupSummary(group);
        return (
          <fieldset
            key={group.group}
            data-testid={`capability-group-${group.group}`}
            className="border border-gray-200 rounded-md p-3"
          >
            <legend className="px-1 text-sm font-medium text-gray-900">
              {/* Declared groups carry a business bucket name (e.g. 客户管理) — t() misses and falls
                  back to it. Convention-derived groups carry the raw module code (billing/ai/iot) —
                  localized here via the existing permission.module.<code> i18n. */}
              {t(`permission.module.${group.group}`, undefined, group.group)}
              <span className="ml-2 text-xs font-normal text-gray-400">
                {t('permission.capability.groupSummary', { granted, total }, `${granted}/${total}`)}
              </span>
            </legend>
            <div className="flex flex-col gap-1.5">
              {group.capabilities.map((cap) => (
                <label
                  key={cap.code}
                  data-testid={`capability-${cap.code}`}
                  className="flex items-start gap-2 text-sm text-gray-700 cursor-pointer"
                >
                  <input
                    type="checkbox"
                    className="mt-1"
                    data-testid={`capability-checkbox-${cap.code}`}
                    checked={selectedSet.has(cap.code)}
                    onChange={() => onToggle(cap.code)}
                  />
                  <span className="flex flex-col gap-1">
                    <span className="flex items-center gap-2">
                      <span>{cap.label}</span>
                      {cap.sensitive && (
                        <span
                          data-testid={`capability-sensitive-${cap.code}`}
                          title={t('permission.capability.sensitive', undefined, '敏感')}
                          aria-label={t('permission.capability.sensitive', undefined, '敏感')}
                        >
                          🔒
                        </span>
                      )}
                    </span>
                    {cap.unlockedMenus && cap.unlockedMenus.length > 0 && (
                      <span
                        data-testid={`capability-menus-${cap.code}`}
                        className="flex flex-wrap items-center gap-1 text-xs text-gray-400"
                      >
                        <span>{t('permission.capability.unlocksMenus', undefined, '解锁菜单')}:</span>
                        {cap.unlockedMenus.map((m) => (
                          <span
                            key={m}
                            className="rounded bg-gray-100 px-1.5 py-0.5 text-gray-500"
                          >
                            {m}
                          </span>
                        ))}
                      </span>
                    )}
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
        );
      })}
    </div>
  );
}
