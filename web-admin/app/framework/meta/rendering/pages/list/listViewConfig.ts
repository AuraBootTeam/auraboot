import type {
  SavedView,
  SortConfig,
  ViewConfig,
  ViewFilterConfig,
} from '~/framework/smart/types/savedView';
import type { QuickFilterPresetKey } from './quickFilterPresets';
import { areFiltersEqual, areSortsEqual } from './useListUrlState';

export function findPersonalPresetSavedView(
  savedViews: Array<Pick<SavedView, 'pid' | 'scope' | 'viewConfig' | 'viewType'>>,
  presetKey: QuickFilterPresetKey,
): Pick<SavedView, 'pid' | 'scope' | 'viewConfig' | 'viewType'> | undefined {
  return savedViews.find(
    (view) =>
      String(view.scope).toLowerCase() === 'personal' &&
      view.viewConfig?.meta?.originPresetKey === presetKey,
  );
}

function stableConfigString(value: unknown): string {
  return JSON.stringify(value ?? null);
}

export type ListSortFilterQuery = { activeSorts: SortConfig[]; chipFilters: ViewFilterConfig[] };

export function areListSortFilterQueriesEqual(
  left: ListSortFilterQuery,
  right: ListSortFilterQuery | undefined,
): boolean {
  return Boolean(
    right &&
    areSortsEqual(left.activeSorts, right.activeSorts) &&
    areFiltersEqual(left.chipFilters, right.chipFilters),
  );
}

export function isListQuerySettled(
  current: ListSortFilterQuery,
  debounced: ListSortFilterQuery,
  scheduled: ListSortFilterQuery | undefined,
): boolean {
  return (
    areListSortFilterQueriesEqual(current, debounced) &&
    areListSortFilterQueriesEqual(current, scheduled)
  );
}

function isNoopViewConfigPatchEntry(
  key: keyof ViewConfig,
  value: unknown,
  baseValue: unknown,
): boolean {
  if (value === undefined) {
    return true;
  }
  if (key === 'sorts') {
    return areSortsEqual(
      Array.isArray(baseValue) ? (baseValue as SortConfig[]) : [],
      Array.isArray(value) ? (value as SortConfig[]) : [],
    );
  }
  if (Array.isArray(value)) {
    const normalizedBase = Array.isArray(baseValue) ? baseValue : [];
    return stableConfigString(value) === stableConfigString(normalizedBase);
  }
  return stableConfigString(value) === stableConfigString(baseValue);
}

export function pruneNoopViewConfigPatch(
  base: ViewConfig | Partial<ViewConfig> | null | undefined,
  patch: Partial<ViewConfig> | null | undefined,
): Partial<ViewConfig> | null {
  if (!patch) {
    return null;
  }

  const pruned: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(patch) as Array<[keyof ViewConfig, unknown]>) {
    if (!isNoopViewConfigPatchEntry(key, value, base?.[key])) {
      pruned[key] = value;
    }
  }

  return Object.keys(pruned).length > 0 ? (pruned as Partial<ViewConfig>) : null;
}

export function resolveListSavedViewPageKey(
  schema: { pageKey?: string | null } | null | undefined,
  routeTableName: string,
): string {
  return schema?.pageKey || routeTableName;
}

export function resolveUrlStateSyncAction(
  pendingLocalEncoding: string | null | undefined,
  currentUrlEncoding: string | null,
): 'apply-url' | 'ack-local' | 'wait-for-local' {
  if (pendingLocalEncoding === undefined) return 'apply-url';
  return pendingLocalEncoding === currentUrlEncoding ? 'ack-local' : 'wait-for-local';
}
