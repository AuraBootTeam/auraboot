/**
 * quickFilterPresets — pure preset-filter logic (T8).
 *
 * The 3 quick-filter presets (my_records / created_today / modified_this_week)
 * compute their filter object deterministically from the current user + clock.
 * Extracting them as a pure function lets us unit-test the date math + the
 * userId coercion without a DOM, and lets the list page and the view switcher
 * share the exact same preset definitions.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  QUICK_FILTER_PRESET_KEYS,
  buildQuickFilterPreset,
  buildQuickFilterPresetViewFilters,
  buildQuickFilterPresetViewRequest,
  getQuickFilterPresetDefinitions,
  isQuickFilterPresetKey,
  registerQuickFilterPresetProvider,
} from '../quickFilterPresets';

// Fixed clock so range assertions are stable: 2026-06-17T14:32:10 local.
const NOW = new Date(2026, 5, 17, 14, 32, 10);

describe('buildQuickFilterPreset', () => {
  describe('my_records', () => {
    it('filters by created_by as a full-precision string id', () => {
      expect(buildQuickFilterPreset('my_records', { userId: 42, now: NOW })).toEqual({
        created_by: '42',
      });
    });

    it('preserves a snowflake id without precision loss (no Number()/parseInt)', () => {
      // > 2^53 — parseInt/Number would corrupt this; the string must survive intact.
      expect(
        buildQuickFilterPreset('my_records', { userId: '1850000000000000123', now: NOW }),
      ).toEqual({
        created_by: '1850000000000000123',
      });
    });

    it('returns an empty filter when userId is missing', () => {
      expect(buildQuickFilterPreset('my_records', { userId: undefined, now: NOW })).toEqual({});
    });

    it('returns an empty filter when userId is an empty string', () => {
      expect(buildQuickFilterPreset('my_records', { userId: '  ', now: NOW })).toEqual({});
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
      expect(buildQuickFilterPreset('not_a_preset', { userId: 1, now: NOW })).toBeNull();
    });
  });
});

describe('buildQuickFilterPresetViewFilters', () => {
  it('converts scalar, array, and range preset filters to SavedView filters', () => {
    expect(
      buildQuickFilterPresetViewFilters({
        created_by: '42',
        status: ['open', 'won'],
        created_at: { start: '2026-06-17', end: '2026-06-17T23:59:59' },
      }),
    ).toEqual([
      { fieldCode: 'created_by', operator: 'eq', value: '42' },
      { fieldCode: 'status', operator: 'in', value: ['open', 'won'] },
      {
        fieldCode: 'created_at',
        operator: 'between',
        value: { start: '2026-06-17', end: '2026-06-17T23:59:59' },
      },
    ]);
  });
});

describe('buildQuickFilterPresetViewRequest', () => {
  it('builds a personal SavedView create request from a preset', () => {
    const request = buildQuickFilterPresetViewRequest(
      'created_today',
      { userId: 1, now: NOW },
      { modelCode: 'e2et_order', pageKey: 'e2et_order', name: '今日新建' },
    );

    expect(request).toMatchObject({
      name: '今日新建',
      modelCode: 'e2et_order',
      pageKey: 'e2et_order',
      scope: 'personal',
      viewType: 'table',
      viewConfig: {
        meta: {
          managedBy: 'user',
          originPresetKey: 'created_today',
        },
      },
    });
    expect(request?.viewConfig?.filters).toEqual([
      {
        fieldCode: 'created_at',
        operator: 'between',
        value: { start: '2026-06-17', end: '2026-06-17T23:59:59' },
      },
    ]);
  });
});

describe('QUICK_FILTER_PRESET_KEYS', () => {
  it('lists exactly the three preset keys in order', () => {
    expect(QUICK_FILTER_PRESET_KEYS).toEqual(['my_records', 'created_today', 'modified_this_week']);
  });
});

describe('quick filter preset icons', () => {
  it('gives every built-in preset a default icon', () => {
    for (const preset of getQuickFilterPresetDefinitions()) {
      expect(preset.icon, `preset ${preset.key} should have an icon`).toBeTruthy();
    }
  });

  it('uses the expected default icon for each built-in preset', () => {
    const byKey = Object.fromEntries(
      getQuickFilterPresetDefinitions().map((preset) => [preset.key, preset.icon]),
    );
    expect(byKey.my_records).toBe('👤');
    expect(byKey.created_today).toBe('📅');
    expect(byKey.modified_this_week).toBe('🕐');
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

describe('quick filter preset provider registry', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('allows plugins to register additional preset providers', () => {
    const unregister = registerQuickFilterPresetProvider({
      id: 'unit-test-presets',
      getPresets: () => [
        {
          key: 'unit_test_recent',
          i18nKey: 'common.unit_test_recent',
          fallbackLabel: 'Recent Test Records',
          buildFilters: () => ({ status: 'recent' }),
        },
      ],
    });

    try {
      expect(getQuickFilterPresetDefinitions().map((preset) => preset.key)).toContain(
        'unit_test_recent',
      );
      expect(buildQuickFilterPreset('unit_test_recent', { userId: 1, now: NOW })).toEqual({
        status: 'recent',
      });
      expect(isQuickFilterPresetKey('unit_test_recent')).toBe(true);
    } finally {
      unregister();
    }

    expect(isQuickFilterPresetKey('unit_test_recent')).toBe(false);
  });

  it('rejects duplicate provider ids', () => {
    const unregister = registerQuickFilterPresetProvider({
      id: 'duplicate-provider-id',
      getPresets: () => [],
    });

    try {
      expect(() =>
        registerQuickFilterPresetProvider({
          id: 'duplicate-provider-id',
          getPresets: () => [],
        }),
      ).toThrow(/already registered/);
    } finally {
      unregister();
    }
  });

  it('keeps the first preset definition when provider keys conflict', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const unregister = registerQuickFilterPresetProvider({
      id: 'duplicate-preset-key',
      getPresets: () => [
        {
          key: 'created_today',
          i18nKey: 'common.conflicting_created_today',
          fallbackLabel: 'Conflicting Created Today',
          buildFilters: () => ({ conflicting: true }),
        },
      ],
    });

    try {
      const createdToday = getQuickFilterPresetDefinitions().find(
        (preset) => preset.key === 'created_today',
      );
      expect(createdToday?.fallbackLabel).toBe('Created Today');
      expect(buildQuickFilterPreset('created_today', { userId: 1, now: NOW })).toEqual({
        created_at: { start: '2026-06-17', end: '2026-06-17T23:59:59' },
      });
      expect(warn).toHaveBeenCalledWith(expect.stringContaining('duplicate preset key'));
    } finally {
      unregister();
    }
  });
});
