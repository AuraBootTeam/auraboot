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
  view: Pick<SavedView, 'scope' | 'viewConfig' | 'isImplicit'> | null | undefined,
): SavedViewPersistenceMode {
  if (!view || isImplicitSavedView(view)) {
    return 'implicit-autosave';
  }
  if (isSavedViewLockedPreset(view)) {
    return 'shared-draft';
  }
  return view.scope === 'personal' ? 'personal-persist' : 'shared-draft';
}

export function isImplicitSavedView(
  view: Pick<SavedView, 'isImplicit'> | null | undefined,
): boolean {
  return view?.isImplicit === true;
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
  view: Pick<SavedView, 'viewConfig' | 'actions' | 'isImplicit'> | null | undefined,
): boolean {
  if (isImplicitSavedView(view) || isSavedViewLockedPreset(view)) {
    return false;
  }
  const actionAllowed = resolveSavedViewAction(view, 'manage');
  return actionAllowed ?? true;
}

export function canSetDefaultSavedView(
  view: Pick<SavedView, 'viewConfig' | 'actions' | 'isDefault' | 'isImplicit'> | null | undefined,
): boolean {
  if (!view || view.isDefault || isImplicitSavedView(view) || isSavedViewLockedPreset(view)) {
    return false;
  }
  const actionAllowed = resolveSavedViewAction(view, 'setDefault');
  return actionAllowed ?? true;
}

export function canDeleteSavedView(
  view: Pick<SavedView, 'viewConfig' | 'actions' | 'scope' | 'isImplicit'> | null | undefined,
): boolean {
  if (!view || isImplicitSavedView(view) || isSavedViewLockedPreset(view)) {
    return false;
  }
  const actionAllowed = resolveSavedViewAction(view, 'delete');
  return actionAllowed ?? (view.scope === 'personal' || view.scope === 'team');
}

export function canShareSavedView(
  view: Pick<SavedView, 'viewConfig' | 'actions' | 'isImplicit'> | null | undefined,
): boolean {
  if (!view || isImplicitSavedView(view) || isSavedViewLockedPreset(view)) {
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
  return `${trimmed || '视图'} 副本`;
}

export function summarizeViewConfigPatch(patch: Partial<ViewConfig> | null | undefined): string[] {
  if (!patch) {
    return [];
  }

  const summary: string[] = [];
  if (Array.isArray(patch.filters)) {
    summary.push(`筛选 ${patch.filters.length} 项`);
  }
  if (Array.isArray(patch.sorts)) {
    summary.push(`排序 ${patch.sorts.length} 项`);
  }
  if (Array.isArray(patch.columns)) {
    summary.push(`字段 ${patch.columns.length} 项`);
  }
  if (Array.isArray(patch.groupBy)) {
    summary.push(`分组 ${patch.groupBy.length} 项`);
  }
  if (patch.rowHeight) {
    summary.push('行高');
  }
  if (patch.density) {
    summary.push('密度');
  }
  if (Array.isArray(patch.conditionalFormats)) {
    summary.push(`条件格式 ${patch.conditionalFormats.length} 项`);
  }
  if (Array.isArray(patch.toolbarActions)) {
    summary.push(`工具栏动作 ${patch.toolbarActions.length} 项`);
  }
  return summary;
}
