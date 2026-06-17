/**
 * quickFilterPresets — pure preset-filter definitions (T8).
 *
 * The list-page quick filters ("My Records" / "Created Today" / "Modified This
 * Week") are real, named preset views: a fixed mapping from a preset key to the
 * filter object the list applies. Keeping the computation pure (clock + user
 * injected as arguments) lets the list page and the view switcher share one
 * source of truth and makes the date math deterministically testable.
 *
 * Spec: T8 — "快捷筛选 → 真预设视图" (UX design-system backlog).
 */

/** Keys of the built-in preset views, in display order. */
export const QUICK_FILTER_PRESET_KEYS = [
  'my_records',
  'created_today',
  'modified_this_week',
] as const;

export type QuickFilterPresetKey = (typeof QUICK_FILTER_PRESET_KEYS)[number];

/** The i18n key (under `common.`) that labels each preset. */
export const QUICK_FILTER_PRESET_I18N_KEY: Record<QuickFilterPresetKey, string> = {
  my_records: 'common.my_records',
  created_today: 'common.created_today',
  modified_this_week: 'common.modified_this_week',
};

/** English fallback label per preset (used when no translation is loaded). */
export const QUICK_FILTER_PRESET_FALLBACK: Record<QuickFilterPresetKey, string> = {
  my_records: 'My Records',
  created_today: 'Created Today',
  modified_this_week: 'Modified This Week',
};

export interface BuildQuickFilterPresetContext {
  /** Current user id (numeric or numeric-string); undefined when anonymous. */
  userId?: string | number;
  /** Reference clock — pass `new Date()` in production, a fixed Date in tests. */
  now: Date;
}

/** Type guard: is `key` one of the known preset keys? */
export function isQuickFilterPresetKey(key: unknown): key is QuickFilterPresetKey {
  return typeof key === 'string' && (QUICK_FILTER_PRESET_KEYS as readonly string[]).includes(key);
}

/** Local `YYYY-MM-DD` for a Date (not UTC — matches the user's wall clock). */
function toLocalDateString(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/**
 * Compute the filter object for a preset view.
 *
 * @returns the filter map to apply, or `null` for an unknown key.
 *          A known preset with no applicable filter (e.g. `my_records` with no
 *          user) returns `{}` so the caller can still mark the preset active.
 */
export function buildQuickFilterPreset(
  key: QuickFilterPresetKey,
  ctx: BuildQuickFilterPresetContext,
): Record<string, unknown> | null {
  if (!isQuickFilterPresetKey(key)) return null;

  const today = toLocalDateString(ctx.now);

  switch (key) {
    case 'my_records': {
      if (ctx.userId == null) return {};
      // Keep the full-precision string id — user ids are snowflakes (>2^53), so
      // Number()/parseInt would silently corrupt them (AGENTS.md snowflake red line).
      const userId = String(ctx.userId).trim();
      return userId ? { created_by: userId } : {};
    }
    case 'created_today':
      return { created_at: { start: today, end: `${today}T23:59:59` } };
    case 'modified_this_week': {
      const weekAgo = toLocalDateString(new Date(ctx.now.getTime() - 7 * 86400000));
      return { updated_at: { start: weekAgo, end: `${today}T23:59:59` } };
    }
    default:
      return null;
  }
}
