/**
 * quickFilterChips — assemble the unified quick-filter chip row.
 *
 * The chip row surfaces two kinds of one-click entries:
 *   - filter-preset chips: the built-in presets (my_records / created_today /
 *     modified_this_week). Clicking one applies a computed filter set.
 *   - view chips: pinned SavedViews. A view is pinned either globally
 *     (viewConfig.meta.pinnedAsQuickFilter, set by plugin import or an admin) or
 *     per-user (a chip-pin row). Clicking one switches to that SavedView.
 *
 * This module is the single source of truth for merge + dedupe + ordering, kept
 * pure so it can be unit-tested without a DOM or the network.
 */
import type { QuickFilterPresetDefinition } from './quickFilterPresets';
import type { SavedView } from '~/framework/smart/types/savedView';

export type QuickFilterChip =
  | { kind: 'filter-preset'; key: string; label: string; icon?: string }
  | { kind: 'view'; viewPid: string; label: string; icon?: string; order: number };

/** A per-user pin of a SavedView to the quick-filter row. */
export interface QuickFilterViewPin {
  viewPid: string;
  order: number;
}

export interface AssembleQuickFilterChipsInput {
  presets: QuickFilterPresetDefinition[];
  /** i18n resolver: (key, vars?, fallback?) => localized string. */
  t: (key: string, vars?: unknown, fallback?: string) => string;
  /** Views the current user can see (already loaded for the view switcher). */
  savedViews: SavedView[];
  /** Per-user chip pins for this model. Empty until Half B (M2). */
  pins?: QuickFilterViewPin[];
}

/**
 * Merge presets + pinned views into one ordered chip list.
 *
 * Filter-preset chips come first (in preset order). View chips follow, sorted by
 * resolved order (meta.quickFilterOrder ?? pin.order ?? +∞) then by label. A view
 * is deduped by pid, so being both globally pinned and user-pinned yields one
 * chip. A pin whose view is not loaded is skipped (no name/config to render).
 */
export function assembleQuickFilterChips(input: AssembleQuickFilterChipsInput): QuickFilterChip[] {
  const { presets, t, savedViews, pins = [] } = input;

  const presetChips: QuickFilterChip[] = presets.map((preset) => ({
    kind: 'filter-preset',
    key: preset.key,
    label: t(preset.i18nKey, undefined, preset.fallbackLabel),
    icon: preset.icon,
  }));

  const pinOrderByPid = new Map<string, number>();
  for (const pin of pins) {
    if (pin?.viewPid && !pinOrderByPid.has(pin.viewPid)) {
      pinOrderByPid.set(pin.viewPid, pin.order);
    }
  }

  const seen = new Set<string>();
  const viewChips: Array<Extract<QuickFilterChip, { kind: 'view' }>> = [];
  for (const view of savedViews) {
    if (!view?.pid || seen.has(view.pid)) continue;
    const meta = view.viewConfig?.meta;
    const globallyPinned = meta?.pinnedAsQuickFilter === true;
    const userPinned = pinOrderByPid.has(view.pid);
    if (!globallyPinned && !userPinned) continue;
    seen.add(view.pid);
    const order =
      meta?.quickFilterOrder ??
      (userPinned ? pinOrderByPid.get(view.pid) : undefined) ??
      Number.MAX_SAFE_INTEGER;
    viewChips.push({
      kind: 'view',
      viewPid: view.pid,
      label: view.name,
      icon: meta?.quickFilterIcon,
      order,
    });
  }

  viewChips.sort((a, b) => (a.order !== b.order ? a.order - b.order : a.label.localeCompare(b.label)));

  return [...presetChips, ...viewChips];
}
