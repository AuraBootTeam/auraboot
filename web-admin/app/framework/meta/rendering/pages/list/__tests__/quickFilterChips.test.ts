/**
 * quickFilterChips — assembling the unified quick-filter chip row.
 *
 * Merges built-in filter presets with pinned SavedViews (plugin/admin global
 * pins via viewConfig.meta.pinnedAsQuickFilter, plus per-user pins) into one
 * ordered chip list. Pure so the merge/dedupe/order logic is unit-testable
 * without a DOM or the network.
 */
import { describe, expect, it } from 'vitest';
import { assembleQuickFilterChips } from '../quickFilterChips';
import { getQuickFilterPresetDefinitions } from '../quickFilterPresets';
import type { SavedView } from '~/framework/smart/types/savedView';

const t = (_key: string, _vars?: unknown, fallback?: string) => fallback ?? _key;
const presets = getQuickFilterPresetDefinitions();

function view(pid: string, name: string, meta?: Record<string, unknown>): SavedView {
  return {
    pid,
    name,
    modelCode: 'e2et_order',
    scope: 'global',
    viewType: 'table',
    viewConfig: { meta: meta as never },
  } as SavedView;
}

const viewPids = (chips: ReturnType<typeof assembleQuickFilterChips>) =>
  chips.filter((c) => c.kind === 'view').map((c) => (c as { viewPid: string }).viewPid);

describe('assembleQuickFilterChips', () => {
  it('returns only filter-preset chips when there are no views', () => {
    const chips = assembleQuickFilterChips({ presets, t, savedViews: [], pins: [] });
    expect(chips).toHaveLength(presets.length);
    expect(chips.every((c) => c.kind === 'filter-preset')).toBe(true);
    expect(chips[0]).toMatchObject({ kind: 'filter-preset', key: 'my_records', icon: '👤' });
  });

  it('adds a view chip for a pinned global view, after the preset chips', () => {
    const chips = assembleQuickFilterChips({
      presets,
      t,
      savedViews: [view('v1', 'Open Orders', { pinnedAsQuickFilter: true, quickFilterIcon: '📦' })],
      pins: [],
    });
    expect(chips.filter((c) => c.kind === 'view')).toEqual([
      {
        kind: 'view',
        viewPid: 'v1',
        label: 'Open Orders',
        icon: '📦',
        order: Number.MAX_SAFE_INTEGER,
      },
    ]);
    expect(chips.findIndex((c) => c.kind === 'view')).toBe(presets.length);
  });

  it('does not add a view chip for a non-pinned view', () => {
    const chips = assembleQuickFilterChips({
      presets,
      t,
      savedViews: [view('v2', 'Not Pinned', { pinnedAsQuickFilter: false })],
      pins: [],
    });
    expect(chips.some((c) => c.kind === 'view')).toBe(false);
  });

  it('dedupes a view that is both globally pinned and user-pinned', () => {
    const chips = assembleQuickFilterChips({
      presets,
      t,
      savedViews: [view('v1', 'Open Orders', { pinnedAsQuickFilter: true })],
      pins: [{ viewPid: 'v1', order: 5 }],
    });
    expect(chips.filter((c) => c.kind === 'view')).toHaveLength(1);
  });

  it('includes a user-pinned view even when not globally pinned', () => {
    const chips = assembleQuickFilterChips({
      presets,
      t,
      savedViews: [view('v3', 'My Pinned', {})],
      pins: [{ viewPid: 'v3', order: 1 }],
    });
    expect(viewPids(chips)).toEqual(['v3']);
  });

  it('skips a pin whose view is not loaded', () => {
    const chips = assembleQuickFilterChips({
      presets,
      t,
      savedViews: [],
      pins: [{ viewPid: 'missing', order: 1 }],
    });
    expect(chips.some((c) => c.kind === 'view')).toBe(false);
  });

  it('orders view chips by resolved order then name', () => {
    const chips = assembleQuickFilterChips({
      presets,
      t,
      savedViews: [
        view('a', 'Alpha', { pinnedAsQuickFilter: true, quickFilterOrder: 2 }),
        view('b', 'Bravo', { pinnedAsQuickFilter: true, quickFilterOrder: 1 }),
        view('c', 'Charlie', { pinnedAsQuickFilter: true }),
        view('d', 'Delta', { pinnedAsQuickFilter: true }),
      ],
      pins: [],
    });
    expect(viewPids(chips)).toEqual(['b', 'a', 'c', 'd']);
  });
});
