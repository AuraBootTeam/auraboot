import { renderHook, act } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';

const showErrorToast = vi.fn();

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showErrorToast }),
}));

vi.mock('~/shared/services/http-client', () => ({
  get: vi.fn(),
  put: vi.fn(),
}));

vi.mock('~/utils/type', () => ({
  ResultHelper: {
    isSuccess: (r: { code: string }) => r.code === '0',
  },
}));

import { useNotificationPreferences } from '../useNotificationPreferences';
import { get, put } from '~/shared/services/http-client';

const mockGet = get as ReturnType<typeof vi.fn>;
const mockPut = put as ReturnType<typeof vi.fn>;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const ok = (data: any) => ({ code: '0', data, success: true, desc: '' });
const err = (desc = 'fail') => ({ code: '1', data: null, success: false, desc });

describe('useNotificationPreferences', () => {
  beforeEach(() => vi.clearAllMocks());

  it('initial state', () => {
    const { result } = renderHook(() => useNotificationPreferences());
    expect(result.current.preferences).toEqual([]);
    expect(result.current.loading).toBe(false);
    expect(result.current.updating).toBeNull();
  });

  it('fetchPreferences loads preferences on success', async () => {
    const prefs = [{ id: 1, channel: 'email', category: 'business', enabled: false }];
    mockGet.mockResolvedValue(ok(prefs));

    const { result } = renderHook(() => useNotificationPreferences());
    await act(async () => {
      await result.current.fetchPreferences();
    });

    expect(mockGet).toHaveBeenCalledWith('/api/notifications/preferences');
    expect(result.current.preferences).toEqual(prefs);
    expect(result.current.loading).toBe(false);
  });

  it('fetchPreferences shows error on failure', async () => {
    mockGet.mockResolvedValue(err('load failed'));

    const { result } = renderHook(() => useNotificationPreferences());
    await act(async () => {
      await result.current.fetchPreferences();
    });

    expect(showErrorToast).toHaveBeenCalledWith('load failed');
    expect(result.current.preferences).toEqual([]);
  });

  it('fetchPreferences shows error on exception', async () => {
    mockGet.mockRejectedValue(new Error('network'));

    const { result } = renderHook(() => useNotificationPreferences());
    await act(async () => {
      await result.current.fetchPreferences();
    });

    expect(showErrorToast).toHaveBeenCalled();
  });

  it('updatePreference does optimistic update then confirms', async () => {
    // Start with no prefs (empty)
    mockPut.mockResolvedValue(ok(null));

    const { result } = renderHook(() => useNotificationPreferences());
    await act(async () => {
      await result.current.updatePreference('email', 'business', false);
    });

    expect(mockPut).toHaveBeenCalledWith('/api/notifications/preferences', {
      channel: 'email',
      category: 'business',
      enabled: false,
    });
    // After optimistic update, new pref should be in the list
    const pref = result.current.preferences.find(
      (p) => p.channel === 'email' && p.category === 'business',
    );
    expect(pref).toBeDefined();
    expect(pref?.enabled).toBe(false);
    expect(result.current.updating).toBeNull();
  });

  it('updatePreference reverts on API error', async () => {
    // Prime with an existing enabled preference
    const prefs = [{ id: 1, channel: 'email', category: 'business', enabled: true }];
    mockGet.mockResolvedValue(ok(prefs));

    const { result } = renderHook(() => useNotificationPreferences());
    await act(async () => {
      await result.current.fetchPreferences();
    });

    // Try to disable, API says no
    mockPut.mockResolvedValue(err('update failed'));

    await act(async () => {
      await result.current.updatePreference('email', 'business', false);
    });

    // Should revert to original enabled=true
    const pref = result.current.preferences.find(
      (p) => p.channel === 'email' && p.category === 'business',
    );
    expect(pref?.enabled).toBe(true);
    expect(showErrorToast).toHaveBeenCalledWith('update failed');
  });

  it('updatePreference reverts on exception', async () => {
    const prefs = [{ id: 1, channel: 'slack', category: 'alert', enabled: true }];
    mockGet.mockResolvedValue(ok(prefs));

    const { result } = renderHook(() => useNotificationPreferences());
    await act(async () => {
      await result.current.fetchPreferences();
    });

    mockPut.mockRejectedValue(new Error('timeout'));

    await act(async () => {
      await result.current.updatePreference('slack', 'alert', false);
    });

    const pref = result.current.preferences.find(
      (p) => p.channel === 'slack' && p.category === 'alert',
    );
    expect(pref?.enabled).toBe(true);
    expect(showErrorToast).toHaveBeenCalled();
  });

  describe('isEnabled', () => {
    it('returns true for in_app + system (forced on)', () => {
      const { result } = renderHook(() => useNotificationPreferences());
      expect(result.current.isEnabled('in_app', 'system')).toBe(true);
    });

    it('returns true for unknown combos (opt-out default)', () => {
      const { result } = renderHook(() => useNotificationPreferences());
      // email+business not in prefs → defaults to enabled
      expect(result.current.isEnabled('email', 'business')).toBe(true);
    });

    it('reflects explicit pref when loaded', async () => {
      const prefs = [{ id: 1, channel: 'email', category: 'business', enabled: false }];
      mockGet.mockResolvedValue(ok(prefs));

      const { result } = renderHook(() => useNotificationPreferences());
      await act(async () => {
        await result.current.fetchPreferences();
      });

      // email:business explicitly disabled
      expect(result.current.isEnabled('email', 'business')).toBe(false);
      // email:approval not in prefs → default true
      expect(result.current.isEnabled('email', 'approval')).toBe(true);
    });
  });

  describe('isForced', () => {
    it('returns true only for in_app + system', () => {
      const { result } = renderHook(() => useNotificationPreferences());
      expect(result.current.isForced('in_app', 'system')).toBe(true);
      expect(result.current.isForced('in_app', 'business')).toBe(false);
      expect(result.current.isForced('email', 'system')).toBe(false);
    });
  });
});
