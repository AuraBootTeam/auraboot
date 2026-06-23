import { describe, expect, it } from 'vitest';
import type { SavedView, ViewConfig } from '~/framework/smart/types/savedView';
import {
  buildPersonalCopyName,
  canCopySavedView,
  getSavedViewPersistenceMode,
  isSavedViewLockedPreset,
  mergeViewConfigPatch,
  summarizeViewConfigPatch,
} from '../savedViewPersistence';

function makeView(scope: SavedView['scope'], name = 'Shared View'): SavedView {
  return {
    pid: `${scope}-view`,
    name,
    modelCode: 'order',
    scope,
    viewType: 'table',
    viewConfig: {},
  };
}

describe('savedViewPersistence', () => {
  it('only persists directly to personal views', () => {
    expect(getSavedViewPersistenceMode(null)).toBe('implicit-autosave');
    expect(getSavedViewPersistenceMode(makeView('personal'))).toBe('personal-persist');
    expect(getSavedViewPersistenceMode(makeView('team'))).toBe('shared-draft');
    expect(getSavedViewPersistenceMode(makeView('global'))).toBe('shared-draft');
  });

  it('treats locked plugin presets as draft-only and controls copy availability', () => {
    const pluginPreset = makeView('global');
    pluginPreset.viewConfig = {
      meta: {
        viewKey: 'crm.pipeline',
        managedBy: 'plugin',
        locked: true,
        allowUserCopy: true,
      },
    };

    expect(isSavedViewLockedPreset(pluginPreset)).toBe(true);
    expect(getSavedViewPersistenceMode(pluginPreset)).toBe('shared-draft');
    expect(canCopySavedView(pluginPreset)).toBe(true);

    pluginPreset.viewConfig.meta!.allowUserCopy = false;
    expect(canCopySavedView(pluginPreset)).toBe(false);
  });

  it('uses server actions as the authoritative copy permission when present', () => {
    expect(canCopySavedView({ ...makeView('team'), actions: ['view'] })).toBe(false);
    expect(canCopySavedView({ ...makeView('team'), actions: ['view', 'copy'] })).toBe(true);
  });

  it('merges pending local config over the source view config', () => {
    const base: ViewConfig = {
      rowHeight: 'medium',
      columns: [{ fieldCode: 'name', visible: true }],
    };
    const patch: Partial<ViewConfig> = {
      rowHeight: 'tall',
      filters: [{ fieldCode: 'status', operator: 'eq', value: 'active' }],
    };

    expect(mergeViewConfigPatch(base, patch)).toEqual({
      rowHeight: 'tall',
      columns: [{ fieldCode: 'name', visible: true }],
      filters: [{ fieldCode: 'status', operator: 'eq', value: 'active' }],
    });
  });

  it('builds a stable default name for a personal copy', () => {
    expect(buildPersonalCopyName('Team Board')).toBe('Team Board - My Copy');
    expect(buildPersonalCopyName('')).toBe('View - My Copy');
  });

  it('summarizes local shared-view changes by config section', () => {
    expect(
      summarizeViewConfigPatch({
        filters: [{ fieldCode: 'status', operator: 'eq', value: 'active' }],
        sorts: [{ fieldCode: 'created_at', direction: 'desc' }],
        columns: [
          { fieldCode: 'name', width: 180 },
          { fieldCode: 'status', visible: false },
        ],
        rowHeight: 'tall',
      }),
    ).toEqual(['filters 1', 'sorts 1', 'columns 2', 'row height']);
  });
});
