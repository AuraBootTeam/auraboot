import type { Capability } from './types';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/utils/i18n';
import text from './domainLevels.i18n.json';
import { hasLegacyDomainGrants } from './domainLevels';

export default function DomainLevelSelector({
  levels,
  selected,
  revoked,
  disabled,
  onSelect,
}: {
  levels: Capability[];
  selected: string[];
  revoked: string[];
  disabled: boolean;
  onSelect: (levels: Capability[], code: string | null) => void;
}) {
  const { t, locale } = useI18n();
  const active = [...levels].reverse().find((cap) => selected.includes(cap.code));
  const legacy = hasLegacyDomainGrants(levels, selected, revoked);
  const label = (value: Record<string, string>) => getLocalizedText(value, locale, t);
  return (
    <div data-testid={`domain-levels-${levels[0].code}`} className="space-y-3 py-3">
      <div className="flex flex-wrap gap-5" role="radiogroup">
        <label className="flex items-center gap-2">
          <input
            type="radio"
            name={levels[0].code}
            disabled={disabled}
            data-testid={`domain-level-none-${levels[0].code}`}
            checked={!active && !legacy}
            onChange={() => onSelect(levels, null)}
          />
          {label(text.none)}
        </label>
        {levels.map((cap) => (
          <label key={cap.code} className="flex items-center gap-2">
            <input
              type="radio"
              name={levels[0].code}
              disabled={disabled}
              data-testid={`domain-level-${cap.code}`}
              checked={!legacy && active?.code === cap.code}
              onChange={() => onSelect(levels, cap.code)}
            />
            {getLocalizedText(cap.localizedLabels, locale, t) || cap.label}
          </label>
        ))}
      </div>
      <p className="text-text-2 text-xs leading-5">{label(text.guidance)}</p>
      {active && (
        <p className="text-text-2 text-xs leading-5">
          {getLocalizedText(active.localizedDescriptions, locale, t) || active.description}
        </p>
      )}
      {legacy && (
        <p data-testid={`domain-legacy-${levels[0].code}`} className="text-xs text-amber-700">
          {label(text.legacy)}
        </p>
      )}
    </div>
  );
}
