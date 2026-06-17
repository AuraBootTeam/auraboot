/**
 * selectionModel — pure cross-page selection state logic (T9).
 *
 * The list table supports two selection modes:
 *  - `explicit`: the user has hand-picked a finite set of record ids (which may
 *    span pages as they page + check more rows).
 *  - `allMatching`: the user opted into "select all N matching the current
 *    filter". The selection is then the entire filtered result set *minus* an
 *    exclusion set the user builds by un-checking individual rows.
 *
 * Extracting this as a pure, framework-free reducer lets us unit-test the mode
 * transitions, the exclusion bookkeeping, the effective count, and the
 * per-id membership test without a DOM — the React layer only stores the
 * returned immutable state and renders from these selectors.
 */
import { describe, expect, it } from 'vitest';
import {
  createSelectionModel,
  toggleRow,
  selectPage,
  clearPage,
  enterAllMatching,
  exitAllMatching,
  clearSelection,
  isSelected,
  selectedCount,
  isPageFullySelected,
  getExplicitIds,
  isAllMatching,
  type SelectionState,
} from '../selectionModel';

const PAGE_1 = ['a1', 'a2', 'a3'];
const PAGE_2 = ['b1', 'b2', 'b3'];

describe('selectionModel', () => {
  describe('initial state', () => {
    it('starts empty in explicit mode', () => {
      const s = createSelectionModel();
      expect(isAllMatching(s)).toBe(false);
      expect(selectedCount(s, 100)).toBe(0);
      expect(getExplicitIds(s)).toEqual([]);
      expect(isSelected(s, 'a1')).toBe(false);
    });
  });

  describe('explicit mode — per-row + per-page', () => {
    it('toggles a single row on and off', () => {
      let s = createSelectionModel();
      s = toggleRow(s, 'a1');
      expect(isSelected(s, 'a1')).toBe(true);
      expect(selectedCount(s, 100)).toBe(1);
      s = toggleRow(s, 'a1');
      expect(isSelected(s, 'a1')).toBe(false);
      expect(selectedCount(s, 100)).toBe(0);
    });

    it('selectPage adds every id on the current page (cross-page accumulation)', () => {
      let s = createSelectionModel();
      s = selectPage(s, PAGE_1);
      s = selectPage(s, PAGE_2);
      expect(selectedCount(s, 100)).toBe(6);
      expect(getExplicitIds(s).sort()).toEqual([...PAGE_1, ...PAGE_2].sort());
      expect(isPageFullySelected(s, PAGE_1)).toBe(true);
      expect(isPageFullySelected(s, PAGE_2)).toBe(true);
    });

    it('isPageFullySelected is false when only some page rows are selected', () => {
      let s = createSelectionModel();
      s = toggleRow(s, 'a1');
      expect(isPageFullySelected(s, PAGE_1)).toBe(false);
    });

    it('isPageFullySelected is false for an empty page', () => {
      const s = createSelectionModel();
      expect(isPageFullySelected(s, [])).toBe(false);
    });

    it('clearPage removes only the current page ids, keeping other pages', () => {
      let s = createSelectionModel();
      s = selectPage(s, PAGE_1);
      s = selectPage(s, PAGE_2);
      s = clearPage(s, PAGE_1);
      expect(isPageFullySelected(s, PAGE_1)).toBe(false);
      expect(isPageFullySelected(s, PAGE_2)).toBe(true);
      expect(getExplicitIds(s).sort()).toEqual([...PAGE_2].sort());
      expect(selectedCount(s, 100)).toBe(3);
    });
  });

  describe('allMatching mode', () => {
    it('enterAllMatching switches mode; effective count is the total', () => {
      let s = createSelectionModel();
      s = selectPage(s, PAGE_1);
      s = enterAllMatching(s);
      expect(isAllMatching(s)).toBe(true);
      expect(selectedCount(s, 250)).toBe(250);
    });

    it('every id reads as selected in allMatching mode (even ids never seen)', () => {
      let s = enterAllMatching(createSelectionModel());
      expect(isSelected(s, 'a1')).toBe(true);
      expect(isSelected(s, 'never-loaded-id')).toBe(true);
    });

    it('toggleRow de-selects into an exclusion set (count decremented from total)', () => {
      let s = enterAllMatching(createSelectionModel());
      s = toggleRow(s, 'a2');
      expect(isSelected(s, 'a2')).toBe(false);
      expect(isSelected(s, 'a1')).toBe(true);
      expect(selectedCount(s, 250)).toBe(249);
    });

    it('re-checking an excluded id removes it from the exclusion set', () => {
      let s = enterAllMatching(createSelectionModel());
      s = toggleRow(s, 'a2'); // exclude
      s = toggleRow(s, 'a2'); // re-include
      expect(isSelected(s, 'a2')).toBe(true);
      expect(selectedCount(s, 250)).toBe(250);
    });

    it('multiple exclusions accumulate and never go below zero', () => {
      let s = enterAllMatching(createSelectionModel());
      s = toggleRow(s, 'a1');
      s = toggleRow(s, 'a2');
      s = toggleRow(s, 'a3');
      expect(selectedCount(s, 3)).toBe(0);
      // Excluding a 4th id when total is 3 must clamp, not go negative.
      s = toggleRow(s, 'phantom');
      expect(selectedCount(s, 3)).toBe(0);
    });

    it('clearPage in allMatching mode excludes the page ids', () => {
      let s = enterAllMatching(createSelectionModel());
      s = clearPage(s, PAGE_1);
      expect(isSelected(s, 'a1')).toBe(false);
      expect(isSelected(s, 'b1')).toBe(true);
      expect(selectedCount(s, 250)).toBe(247);
    });

    it('selectPage in allMatching mode re-includes excluded page ids', () => {
      let s = enterAllMatching(createSelectionModel());
      s = toggleRow(s, 'a1');
      s = toggleRow(s, 'a2');
      s = selectPage(s, PAGE_1);
      expect(isSelected(s, 'a1')).toBe(true);
      expect(isSelected(s, 'a2')).toBe(true);
      expect(selectedCount(s, 250)).toBe(250);
    });

    it('isPageFullySelected is true while no page id is excluded', () => {
      let s = enterAllMatching(createSelectionModel());
      expect(isPageFullySelected(s, PAGE_1)).toBe(true);
      s = toggleRow(s, 'a1');
      expect(isPageFullySelected(s, PAGE_1)).toBe(false);
    });

    it('exitAllMatching falls back to explicit mode with an empty selection', () => {
      let s = enterAllMatching(createSelectionModel());
      s = toggleRow(s, 'a1'); // exclusion
      s = exitAllMatching(s);
      expect(isAllMatching(s)).toBe(false);
      expect(selectedCount(s, 250)).toBe(0);
      expect(getExplicitIds(s)).toEqual([]);
    });
  });

  describe('clearSelection', () => {
    it('resets explicit selection to empty', () => {
      let s = selectPage(createSelectionModel(), PAGE_1);
      s = clearSelection(s);
      expect(selectedCount(s, 100)).toBe(0);
      expect(isAllMatching(s)).toBe(false);
    });

    it('resets allMatching mode + exclusions to empty', () => {
      let s = enterAllMatching(createSelectionModel());
      s = toggleRow(s, 'a1');
      s = clearSelection(s);
      expect(isAllMatching(s)).toBe(false);
      expect(selectedCount(s, 250)).toBe(0);
    });
  });

  describe('immutability', () => {
    it('reducers return new state objects (no in-place mutation)', () => {
      const s0 = createSelectionModel();
      const s1 = toggleRow(s0, 'a1');
      expect(s1).not.toBe(s0);
      expect(selectedCount(s0, 100)).toBe(0);
      const s2: SelectionState = enterAllMatching(s1);
      expect(s2).not.toBe(s1);
    });
  });
});
