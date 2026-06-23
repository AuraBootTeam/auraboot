import type { SavedView, ViewConfig } from '~/framework/smart/types/savedView';

export type SavedViewPersistenceMode = 'implicit-autosave' | 'personal-persist' | 'shared-draft';
type SavedViewAction = NonNullable<SavedView['actions']>[number];

function resolveSavedViewAction(
  view: Pick<SavedView, 'actions'> | null | undefined,
  action: SavedViewAction,
): boolean | null {
  if (!Array.isArray(view?.actions)) {
    return null;
  }
  return view.actions.includes(action);
}

export function getSavedViewPersistenceMode(
  view: Pick<SavedView, 'scope' | 'viewConfig'> | null | undefined,
): SavedViewPersistenceMode {
  if (!view) {
    return 'implicit-autosave';
  }
  if (isSavedViewLockedPreset(view)) {
    return 'shared-draft';
  }
  return view.scope === 'personal' ? 'personal-persist' : 'shared-draft';
}

export function isSavedViewLockedPreset(
  view: Pick<SavedView, 'viewConfig'> | null | undefined,
): boolean {
  const meta = view?.viewConfig?.meta;
  if (!meta) {
    return false;
  }
  return Boolean(meta.locked) || meta.managedBy?.toLowerCase() === 'plugin';
}

export function canCopySavedView(
  view: Pick<SavedView, 'viewConfig' | 'actions'> | null | undefined,
): boolean {
  const actionAllowed = resolveSavedViewAction(view, 'copy');
  if (actionAllowed !== null) {
    return actionAllowed;
  }
  return view?.viewConfig?.meta?.allowUserCopy !== false;
}

export function canManageSavedView(
  view: Pick<SavedView, 'viewConfig' | 'actions'> | null | undefined,
): boolean {
  if (isSavedViewLockedPreset(view)) {
    return false;
  }
  const actionAllowed = resolveSavedViewAction(view, 'manage');
  return actionAllowed ?? true;
}

export function canSetDefaultSavedView(
  view: Pick<SavedView, 'viewConfig' | 'actions' | 'isDefault'> | null | undefined,
): boolean {
  if (!view || view.isDefault || isSavedViewLockedPreset(view)) {
    return false;
  }
  const actionAllowed = resolveSavedViewAction(view, 'setDefault');
  return actionAllowed ?? true;
}

export function canDeleteSavedView(
  view: Pick<SavedView, 'viewConfig' | 'actions' | 'scope'> | null | undefined,
): boolean {
  if (!view || isSavedViewLockedPreset(view)) {
    return false;
  }
  const actionAllowed = resolveSavedViewAction(view, 'delete');
  return actionAllowed ?? (view.scope === 'personal' || view.scope === 'team');
}

export function canShareSavedView(
  view: Pick<SavedView, 'viewConfig' | 'actions'> | null | undefined,
): boolean {
  if (!view || isSavedViewLockedPreset(view)) {
    return false;
  }
  const actionAllowed = resolveSavedViewAction(view, 'share');
  return actionAllowed ?? true;
}

export function mergeViewConfigPatch(
  base: ViewConfig | Partial<ViewConfig> | null | undefined,
  patch: Partial<ViewConfig> | null | undefined,
): ViewConfig {
  return {
    ...(base ?? {}),
    ...(patch ?? {}),
  };
}

export function buildPersonalCopyName(name: string | null | undefined): string {
  const trimmed = name?.trim();
  return `${trimmed || 'View'} - My Copy`;
}

export function summarizeViewConfigPatch(patch: Partial<ViewConfig> | null | undefined): string[] {
  if (!patch) {
    return [];
  }

  const summary: string[] = [];
  if (Array.isArray(patch.filters)) {
    summary.push(`filters ${patch.filters.length}`);
  }
  if (Array.isArray(patch.sorts)) {
    summary.push(`sorts ${patch.sorts.length}`);
  }
  if (Array.isArray(patch.columns)) {
    summary.push(`columns ${patch.columns.length}`);
  }
  if (Array.isArray(patch.groupBy)) {
    summary.push(`groups ${patch.groupBy.length}`);
  }
  if (patch.rowHeight) {
    summary.push('row height');
  }
  if (patch.density) {
    summary.push('density');
  }
  if (Array.isArray(patch.conditionalFormats)) {
    summary.push(`conditional formats ${patch.conditionalFormats.length}`);
  }
  if (Array.isArray(patch.toolbarActions)) {
    summary.push(`toolbar actions ${patch.toolbarActions.length}`);
  }
  return summary;
}
