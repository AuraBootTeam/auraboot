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
import type {
  SavedViewCreateRequest,
  ViewFilterConfig,
} from '~/framework/smart/types/savedView';

/** Keys of the built-in preset views, in display order. */
export const BUILT_IN_QUICK_FILTER_PRESET_KEYS = [
  'my_records',
  'created_today',
  'modified_this_week',
] as const;

export type BuiltInQuickFilterPresetKey = (typeof BUILT_IN_QUICK_FILTER_PRESET_KEYS)[number];
export type QuickFilterPresetKey = string;

export interface QuickFilterPresetDefinition {
  key: QuickFilterPresetKey;
  i18nKey: string;
  fallbackLabel: string;
  /** Icon (emoji or known token) rendered on the chip. */
  icon?: string;
  buildFilters: (ctx: BuildQuickFilterPresetContext) => Record<string, unknown> | null;
}

export interface QuickFilterPresetProvider {
  id: string;
  getPresets: () => QuickFilterPresetDefinition[];
}

/** The i18n key (under `common.`) that labels each preset. */
export const QUICK_FILTER_PRESET_I18N_KEY: Record<BuiltInQuickFilterPresetKey, string> = {
  my_records: 'common.my_records',
  created_today: 'common.created_today',
  modified_this_week: 'common.modified_this_week',
};

/** English fallback label per preset (used when no translation is loaded). */
export const QUICK_FILTER_PRESET_FALLBACK: Record<BuiltInQuickFilterPresetKey, string> = {
  my_records: 'My Records',
  created_today: 'Created Today',
  modified_this_week: 'Modified This Week',
};

/** Default emoji icon per built-in preset — source of truth for the chip icon. */
export const QUICK_FILTER_PRESET_ICON: Record<BuiltInQuickFilterPresetKey, string> = {
  my_records: '👤',
  created_today: '📅',
  modified_this_week: '🕐',
};

export interface BuildQuickFilterPresetContext {
  /** Current user id (numeric or numeric-string); undefined when anonymous. */
  userId?: string | number;
  /** Reference clock — pass `new Date()` in production, a fixed Date in tests. */
  now: Date;
}

export interface BuildQuickFilterPresetViewRequestOptions {
  modelCode: string;
  pageKey?: string;
  name?: string;
}

function buildBuiltInQuickFilterPreset(
  key: BuiltInQuickFilterPresetKey,
  ctx: BuildQuickFilterPresetContext,
): Record<string, unknown> | null {
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

const builtInQuickFilterPresetProvider: QuickFilterPresetProvider = {
  id: 'built-in',
  getPresets: () =>
    BUILT_IN_QUICK_FILTER_PRESET_KEYS.map((key) => ({
      key,
      i18nKey: QUICK_FILTER_PRESET_I18N_KEY[key],
      fallbackLabel: QUICK_FILTER_PRESET_FALLBACK[key],
      icon: QUICK_FILTER_PRESET_ICON[key],
      buildFilters: (ctx) => buildBuiltInQuickFilterPreset(key, ctx),
    })),
};

const quickFilterPresetProviders: QuickFilterPresetProvider[] = [builtInQuickFilterPresetProvider];

export function registerQuickFilterPresetProvider(provider: QuickFilterPresetProvider): () => void {
  if (!provider?.id) {
    throw new Error('Quick filter preset provider id is required');
  }
  if (quickFilterPresetProviders.some((existing) => existing.id === provider.id)) {
    throw new Error(`Quick filter preset provider already registered: ${provider.id}`);
  }
  quickFilterPresetProviders.push(provider);
  return () => {
    const index = quickFilterPresetProviders.findIndex((existing) => existing.id === provider.id);
    if (index >= 0) {
      quickFilterPresetProviders.splice(index, 1);
    }
  };
}

export function getQuickFilterPresetDefinitions(): QuickFilterPresetDefinition[] {
  const definitions: QuickFilterPresetDefinition[] = [];
  const seenKeys = new Set<string>();
  for (const provider of quickFilterPresetProviders) {
    for (const definition of provider.getPresets()) {
      if (!definition?.key) continue;
      if (seenKeys.has(definition.key)) {
        console.warn(
          `[quickFilterPresets] duplicate preset key "${definition.key}" from provider "${provider.id}" ignored`,
        );
        continue;
      }
      seenKeys.add(definition.key);
      definitions.push(definition);
    }
  }
  return definitions;
}

export function getQuickFilterPresetDefinition(
  key: unknown,
): QuickFilterPresetDefinition | undefined {
  if (typeof key !== 'string') return undefined;
  return getQuickFilterPresetDefinitions().find((definition) => definition.key === key);
}

/** Keys of all currently registered preset views, in display order. */
export const QUICK_FILTER_PRESET_KEYS = BUILT_IN_QUICK_FILTER_PRESET_KEYS;

/** Type guard: is `key` one of the known preset keys? */
export function isQuickFilterPresetKey(key: unknown): key is QuickFilterPresetKey {
  return getQuickFilterPresetDefinition(key) != null;
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
  return getQuickFilterPresetDefinition(key)?.buildFilters(ctx) ?? null;
}

function isRangeFilterValue(value: unknown): value is { start?: unknown; end?: unknown } {
  return (
    value != null &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    ('start' in value || 'end' in value)
  );
}

export function buildQuickFilterPresetViewFilters(
  filters: Record<string, unknown> | null,
): ViewFilterConfig[] {
  if (!filters) return [];
  return Object.entries(filters)
    .filter(([, value]) => value != null && value !== '')
    .map(([fieldCode, value]) => {
      if (isRangeFilterValue(value)) {
        return {
          fieldCode,
          operator: 'between',
          value,
        };
      }
      if (Array.isArray(value)) {
        return {
          fieldCode,
          operator: 'in',
          value,
        };
      }
      return {
        fieldCode,
        operator: 'eq',
        value,
      };
    });
}

export function buildQuickFilterPresetViewRequest(
  key: QuickFilterPresetKey,
  ctx: BuildQuickFilterPresetContext,
  options: BuildQuickFilterPresetViewRequestOptions,
): SavedViewCreateRequest | null {
  const definition = getQuickFilterPresetDefinition(key);
  if (!definition) return null;
  const presetFilters = buildQuickFilterPreset(key, ctx);
  if (!presetFilters) return null;

  return {
    name: options.name || definition.fallbackLabel,
    modelCode: options.modelCode,
    pageKey: options.pageKey,
    scope: 'personal',
    viewType: 'table',
    viewConfig: {
      filters: buildQuickFilterPresetViewFilters(presetFilters),
      meta: {
        managedBy: 'user',
        originPresetKey: key,
      },
    },
  };
}
