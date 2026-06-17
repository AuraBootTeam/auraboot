/**
 * quickFilterPresets — pure preset-filter logic (T8).
 *
 * The 3 quick-filter presets (my_records / created_today / modified_this_week)
 * compute their filter object deterministically from the current user + clock.
 * Extracting them as a pure function lets us unit-test the date math + the
 * userId coercion without a DOM, and lets the list page and the view switcher
 * share the exact same preset definitions.
 */
import { describe, expect, it } from 'vitest';
import {
  QUICK_FILTER_PRESET_KEYS,
  buildQuickFilterPreset,
  isQuickFilterPresetKey,
} from '../quickFilterPresets';

// Fixed clock so range assertions are stable: 2026-06-17T14:32:10 local.
const NOW = new Date(2026, 5, 17, 14, 32, 10);

describe('buildQuickFilterPreset', () => {
  describe('my_records', () => {
    it('filters by created_by when a numeric userId is present', () => {
      expect(buildQuickFilterPreset('my_records', { userId: 42, now: NOW })).toEqual({
        created_by: 42,
      });
    });

    it('coerces a numeric-string userId to an integer', () => {
      expect(buildQuickFilterPreset('my_records', { userId: '42', now: NOW })).toEqual({
        created_by: 42,
      });
    });

    it('returns an empty filter when userId is missing', () => {
      expect(buildQuickFilterPreset('my_records', { userId: undefined, now: NOW })).toEqual({});
    });

    it('returns an empty filter when userId is non-numeric', () => {
      expect(buildQuickFilterPreset('my_records', { userId: 'abc', now: NOW })).toEqual({});
    });
  });

  describe('created_today', () => {
    it('builds a created_at range spanning the current day', () => {
      expect(buildQuickFilterPreset('created_today', { userId: 1, now: NOW })).toEqual({
        created_at: { start: '2026-06-17', end: '2026-06-17T23:59:59' },
      });
    });
  });

  describe('modified_this_week', () => {
    it('builds an updated_at range from 7 days ago to end of today', () => {
      expect(buildQuickFilterPreset('modified_this_week', { userId: 1, now: NOW })).toEqual({
        updated_at: { start: '2026-06-10', end: '2026-06-17T23:59:59' },
      });
    });
  });

  describe('unknown key', () => {
    it('returns null', () => {
      // @ts-expect-error — intentionally passing an invalid key
      expect(buildQuickFilterPreset('not_a_preset', { userId: 1, now: NOW })).toBeNull();
    });
  });
});

describe('QUICK_FILTER_PRESET_KEYS', () => {
  it('lists exactly the three preset keys in order', () => {
    expect(QUICK_FILTER_PRESET_KEYS).toEqual(['my_records', 'created_today', 'modified_this_week']);
  });
});

describe('isQuickFilterPresetKey', () => {
  it('accepts valid preset keys', () => {
    expect(isQuickFilterPresetKey('my_records')).toBe(true);
    expect(isQuickFilterPresetKey('created_today')).toBe(true);
    expect(isQuickFilterPresetKey('modified_this_week')).toBe(true);
  });

  it('rejects anything else', () => {
    expect(isQuickFilterPresetKey('nope')).toBe(false);
    expect(isQuickFilterPresetKey('')).toBe(false);
    expect(isQuickFilterPresetKey(null)).toBe(false);
    expect(isQuickFilterPresetKey(undefined)).toBe(false);
  });
});
