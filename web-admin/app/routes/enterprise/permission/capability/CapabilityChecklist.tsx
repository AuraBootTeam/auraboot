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
              {group.group}
              <span className="ml-2 text-xs font-normal text-gray-400">
                {t('permission.capability.groupSummary', { granted, total }, `${granted}/${total}`)}
              </span>
            </legend>
            <div className="flex flex-col gap-1.5">
              {group.capabilities.map((cap) => (
                <label
                  key={cap.code}
                  data-testid={`capability-${cap.code}`}
                  className="flex items-center gap-2 text-sm text-gray-700 cursor-pointer"
                >
                  <input
                    type="checkbox"
                    data-testid={`capability-checkbox-${cap.code}`}
                    checked={selectedSet.has(cap.code)}
                    onChange={() => onToggle(cap.code)}
                  />
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
                </label>
              ))}
            </div>
          </fieldset>
        );
      })}
    </div>
  );
}
