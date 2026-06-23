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
import { ArrowPathIcon, CheckCircleIcon, LockClosedIcon, PlusIcon } from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import { cn } from '~/utils/cn';
import {
  type QuickFilterPresetKey,
  getQuickFilterPresetDefinitions,
} from './quickFilterPresets';

export interface PresetViewBarProps {
  /** Currently active preset key (null when none selected). */
  activePreset: QuickFilterPresetKey | null;
  /** Toggle a preset on/off. */
  onSelectPreset: (key: QuickFilterPresetKey) => void;
  /** Save the active system preset as a personal SavedView. */
  onSaveActivePreset?: () => void;
  /** Presets that already have a personal SavedView copy. */
  savedPresetKeys?: QuickFilterPresetKey[];
  /** Origin preset key of the active personal SavedView copy. */
  activeSavedPresetKey?: QuickFilterPresetKey | null;
  /** Whether the active personal copy differs from the current system preset definition. */
  activeSavedPresetEdited?: boolean;
  /** Reset the active personal copy to the current system preset definition. */
  onResetActiveSavedPreset?: () => void;
  className?: string;
}

export const PresetViewBar: React.FC<PresetViewBarProps> = ({
  activePreset,
  onSelectPreset,
  onSaveActivePreset,
  savedPresetKeys = [],
  activeSavedPresetKey = null,
  activeSavedPresetEdited = false,
  onResetActiveSavedPreset,
  className,
}) => {
  const { t } = useI18n();
  const definitions = getQuickFilterPresetDefinitions();
  const savedPresetSet = new Set(savedPresetKeys);
  const savePresetLabel = t(
    'common.saved_view_save_preset_to_personal',
    undefined,
    'Save preset as my view',
  );
  const savedBadgeLabel = t('common.saved_view_preset_saved_badge', undefined, 'Saved');
  const editedBadgeLabel = t('common.saved_view_preset_edited_badge', undefined, 'Edited');
  const resetPresetLabel = t(
    'common.saved_view_preset_reset',
    undefined,
    'Reset saved preset',
  );

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
        <LockClosedIcon className="h-3 w-3" aria-hidden />
        {t('common.preset_views', undefined, 'Preset Views')}
      </span>
      {definitions.map((definition) => {
        const key = definition.key;
        const active = activePreset === key || activeSavedPresetKey === key;
        const saved = savedPresetSet.has(key);
        const edited = activeSavedPresetKey === key && activeSavedPresetEdited;
        return (
          <button
            key={key}
            type="button"
            onClick={() => onSelectPreset(key)}
            data-testid={`preset-view-${key}`}
            data-preset-active={active ? 'true' : 'false'}
            data-preset-saved={saved ? 'true' : 'false'}
            data-preset-edited={edited ? 'true' : 'false'}
            aria-pressed={active}
            className={cn(
              'rounded-pill focus-visible:shadow-focus inline-flex items-center gap-1.5 px-3 py-1 text-xs font-medium transition-colors focus:outline-none',
              active
                ? 'bg-accent-weak text-accent ring-accent ring-1'
                : 'bg-subtle text-text-2 hover:bg-hover hover:text-text-2',
            )}
          >
            <span>{t(definition.i18nKey, undefined, definition.fallbackLabel)}</span>
            {saved && (
              <span
                className="text-success inline-flex items-center gap-0.5 text-[10px] font-semibold"
                data-testid={`preset-view-${key}-saved`}
                title={savedBadgeLabel}
              >
                <CheckCircleIcon className="h-3 w-3" aria-hidden />
                {edited ? editedBadgeLabel : savedBadgeLabel}
              </span>
            )}
          </button>
        );
      })}
      {activePreset && onSaveActivePreset && (
        <button
          type="button"
          onClick={onSaveActivePreset}
          data-testid="preset-view-save-as-personal"
          aria-label={savePresetLabel}
          title={savePresetLabel}
          className="rounded-control text-text-2 hover:bg-hover hover:text-text focus-visible:shadow-focus inline-flex h-7 w-7 items-center justify-center transition-colors focus:outline-none"
        >
          <PlusIcon className="h-3.5 w-3.5" aria-hidden />
        </button>
      )}
      {activeSavedPresetKey && activeSavedPresetEdited && onResetActiveSavedPreset && (
        <button
          type="button"
          onClick={onResetActiveSavedPreset}
          data-testid="preset-view-reset-saved"
          aria-label={resetPresetLabel}
          title={resetPresetLabel}
          className="rounded-control text-text-2 hover:bg-hover hover:text-text focus-visible:shadow-focus inline-flex h-7 w-7 items-center justify-center transition-colors focus:outline-none"
        >
          <ArrowPathIcon className="h-3.5 w-3.5" aria-hidden />
        </button>
      )}
    </div>
  );
};

export default PresetViewBar;
