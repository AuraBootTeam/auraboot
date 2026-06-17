/**
 * PresetViewBar — surfaces the built-in quick-filter presets (T8) as
 * selectable, read-only "system preset" view entries alongside the user's
 * SavedViews in the list header.
 *
 * The three presets (My Records / Created Today / Modified This Week) are the
 * same definitions the toolbar quick filters apply (see ./quickFilterPresets);
 * here they are rendered as discoverable views. Selecting one toggles the
 * active preset (the parent syncs it to `?preset=` so it persists on reload).
 *
 * Visually distinct from user SavedViews: a "lock" glyph + a muted section
 * label mark them as system presets. Token-styled (no hardcoded colors/sizes)
 * and i18n-labelled per the UX design system.
 */
import React from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { cn } from '~/utils/cn';
import {
  type QuickFilterPresetKey,
  QUICK_FILTER_PRESET_KEYS,
  QUICK_FILTER_PRESET_I18N_KEY,
  QUICK_FILTER_PRESET_FALLBACK,
} from './quickFilterPresets';

export interface PresetViewBarProps {
  /** Currently active preset key (null when none selected). */
  activePreset: QuickFilterPresetKey | null;
  /** Toggle a preset on/off. */
  onSelectPreset: (key: QuickFilterPresetKey) => void;
  className?: string;
}

export const PresetViewBar: React.FC<PresetViewBarProps> = ({
  activePreset,
  onSelectPreset,
  className,
}) => {
  const { t } = useI18n();

  return (
    <div
      className={cn('flex items-center gap-1.5', className)}
      data-testid="preset-view-bar"
      role="group"
      aria-label={t('common.preset_views', undefined, 'Preset Views')}
    >
      {/* Section label marks these as system presets, distinct from SavedViews */}
      <span
        className="text-text-3 hidden items-center gap-1 text-xs font-medium select-none sm:inline-flex"
        data-testid="preset-view-label"
      >
        <svg className="h-3 w-3" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden>
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
          />
        </svg>
        {t('common.preset_views', undefined, 'Preset Views')}
      </span>
      {QUICK_FILTER_PRESET_KEYS.map((key) => {
        const active = activePreset === key;
        return (
          <button
            key={key}
            type="button"
            onClick={() => onSelectPreset(key)}
            data-testid={`preset-view-${key}`}
            data-preset-active={active ? 'true' : 'false'}
            aria-pressed={active}
            className={cn(
              'rounded-pill focus-visible:shadow-focus px-3 py-1 text-xs font-medium transition-colors focus:outline-none',
              active
                ? 'bg-accent-weak text-accent ring-accent ring-1'
                : 'bg-subtle text-text-2 hover:bg-hover hover:text-text-2',
            )}
          >
            {t(QUICK_FILTER_PRESET_I18N_KEY[key], undefined, QUICK_FILTER_PRESET_FALLBACK[key])}
          </button>
        );
      })}
    </div>
  );
};

export default PresetViewBar;
