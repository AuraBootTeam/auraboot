/**
 * selectionModel — pure cross-page selection state logic (T9).
 *
 * Backs the list table's row-selection feature. Two modes:
 *
 *  - `explicit` (default): `ids` holds the finite set of hand-picked record ids.
 *    Paging + checking more rows accumulates ids across pages, so the selection
 *    is no longer bounded by the current page.
 *
 *  - `allMatching`: the user opted into "select all N matching the current
 *    filter". The effective selection is then the *whole* filtered result set
 *    minus an exclusion set — here `ids` is reused to hold the **excluded** ids
 *    the user un-checks. This lets us represent "all 1,000 except these 3"
 *    without ever materialising 1,000 ids client-side.
 *
 * The React layer stores the returned immutable `SelectionState` and renders
 * purely from the selectors (`isSelected` / `selectedCount` /
 * `isPageFullySelected`). The export path reads `isAllMatching` +
 * `getExplicitIds` to decide between an `IN pid (...)` condition and the
 * current filter set.
 */

export type SelectionMode = 'explicit' | 'allMatching';

export interface SelectionState {
  readonly mode: SelectionMode;
  /**
   * In `explicit` mode: the selected ids.
   * In `allMatching` mode: the excluded ids (un-checked rows).
   */
  readonly ids: ReadonlySet<string>;
}

export function createSelectionModel(): SelectionState {
  return { mode: 'explicit', ids: new Set() };
}

export function isAllMatching(state: SelectionState): boolean {
  return state.mode === 'allMatching';
}

/** Toggle a single row. Meaning depends on mode (select vs. exclude). */
export function toggleRow(state: SelectionState, id: string): SelectionState {
  const next = new Set(state.ids);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  return { mode: state.mode, ids: next };
}

/**
 * "Check the header checkbox" for the visible page.
 * - explicit: add every page id to the selection.
 * - allMatching: remove every page id from the exclusion set (re-include them).
 */
export function selectPage(state: SelectionState, pageIds: string[]): SelectionState {
  const next = new Set(state.ids);
  if (state.mode === 'explicit') {
    for (const id of pageIds) if (id) next.add(id);
  } else {
    for (const id of pageIds) next.delete(id);
  }
  return { mode: state.mode, ids: next };
}

/**
 * "Un-check the header checkbox" for the visible page.
 * - explicit: drop every page id from the selection.
 * - allMatching: add every page id to the exclusion set.
 */
export function clearPage(state: SelectionState, pageIds: string[]): SelectionState {
  const next = new Set(state.ids);
  if (state.mode === 'explicit') {
    for (const id of pageIds) next.delete(id);
  } else {
    for (const id of pageIds) if (id) next.add(id);
  }
  return { mode: state.mode, ids: next };
}

/**
 * Enter "select all N matching" mode. The exclusion set starts empty so the
 * effective selection is the entire filtered result set.
 */
export function enterAllMatching(_state: SelectionState): SelectionState {
  return { mode: 'allMatching', ids: new Set() };
}

/** Leave all-matching mode, dropping back to an empty explicit selection. */
export function exitAllMatching(_state: SelectionState): SelectionState {
  return createSelectionModel();
}

/** Clear everything back to an empty explicit selection. */
export function clearSelection(_state: SelectionState): SelectionState {
  return createSelectionModel();
}

/** Is this id part of the effective selection? */
export function isSelected(state: SelectionState, id: string): boolean {
  return state.mode === 'explicit' ? state.ids.has(id) : !state.ids.has(id);
}

/**
 * Effective number of selected records given the total matching count.
 * - explicit: the size of the picked set (independent of `total`).
 * - allMatching: total minus the exclusion set, clamped at 0.
 */
export function selectedCount(state: SelectionState, total: number): number {
  if (state.mode === 'explicit') return state.ids.size;
  return Math.max(0, total - state.ids.size);
}

/**
 * Whether every id on the given page is currently selected — drives the
 * header checkbox checked-state and the "select all N matching" banner.
 * An empty page is never "fully selected".
 */
export function isPageFullySelected(state: SelectionState, pageIds: string[]): boolean {
  if (pageIds.length === 0) return false;
  return pageIds.every((id) => isSelected(state, id));
}

/**
 * The explicit selected ids, for callers that need a concrete list (e.g. the
 * export-selected `IN pid (...)` condition). Only meaningful in explicit mode;
 * returns an empty array in all-matching mode (callers should branch on
 * `isAllMatching` and export by filter instead).
 */
export function getExplicitIds(state: SelectionState): string[] {
  return state.mode === 'explicit' ? Array.from(state.ids) : [];
}

/** The excluded ids while in all-matching mode (empty otherwise). */
export function getExcludedIds(state: SelectionState): string[] {
  return state.mode === 'allMatching' ? Array.from(state.ids) : [];
}
