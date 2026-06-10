/**
 * Unit tests for profile service
 * Validates URL construction, payload forwarding, and response handling.
 * profile.ts uses fetchResult(url, options, request) — 3-arg form; throws on failure.
 * uploadAvatar uses native fetch (too many env deps) — not tested here.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { fetchResultMock } = vi.hoisted(() => ({
  fetchResultMock: vi.fn(),
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: fetchResultMock,
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

// profile.ts dynamically imports session only in uploadAvatar; no top-level session import
// to mock for getUserProfile / updateUserProfile. We don't need to mock session here.

import { getUserProfile, updateUserProfile } from '../profile';

function ok<T>(data: T) {
  return { code: '0', desc: '', data };
}

function fail(desc = 'Failed', message = '') {
  return { code: '1', desc, message, data: null };
}

const FAKE_REQUEST = new Request('http://localhost/');

const PROFILE = {
  id: 42,
  userId: 42,
  displayName: 'Alice',
  email: 'alice@example.com',
  avatarUrl: null,
  bio: null,
  createdAt: '2024-01-01',
  updatedAt: '2024-01-01',
};

describe('profile service', () => {
  beforeEach(() => {
    fetchResultMock.mockReset();
  });

  // ── getUserProfile ─────────────────────────────────────────────────────────────

  describe('getUserProfile', () => {
    it('GETs /api/user/profile and returns profile data', async () => {
      fetchResultMock.mockResolvedValue(ok(PROFILE));

      const result = await getUserProfile(FAKE_REQUEST);

      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/user/profile',
        { method: 'get' },
        FAKE_REQUEST,
      );
      expect(result).toEqual(PROFILE);
    });

    it('throws when result code is not 0', async () => {
      fetchResultMock.mockResolvedValue(fail('Unauthorized'));

      await expect(getUserProfile(FAKE_REQUEST)).rejects.toThrow('Unauthorized');
    });

    it('throws when data is null', async () => {
      fetchResultMock.mockResolvedValue({ code: '0', desc: '', message: '', data: null });

      await expect(getUserProfile(FAKE_REQUEST)).rejects.toThrow();
    });

    it('uses desc from response as error message when present', async () => {
      fetchResultMock.mockResolvedValue(fail('Token expired'));

      await expect(getUserProfile(FAKE_REQUEST)).rejects.toThrow('Token expired');
    });
  });

  // ── updateUserProfile ──────────────────────────────────────────────────────────

  describe('updateUserProfile', () => {
    it('PUTs /api/user/profile with profile data and returns updated profile', async () => {
      const updated = { ...PROFILE, displayName: 'Alice B.' };
      fetchResultMock.mockResolvedValue(ok(updated));

      const updateData = { displayName: 'Alice B.' };
      const result = await updateUserProfile(FAKE_REQUEST, updateData);

      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/user/profile',
        { method: 'put', params: updateData },
        FAKE_REQUEST,
      );
      expect(result?.displayName).toBe('Alice B.');
    });

    it('throws on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Validation error'));

      await expect(
        updateUserProfile(FAKE_REQUEST, { displayName: '' }),
      ).rejects.toThrow('Validation error');
    });

    it('throws with fallback message when desc is empty', async () => {
      fetchResultMock.mockResolvedValue({ code: '1', desc: '', data: null });

      await expect(updateUserProfile(FAKE_REQUEST, {})).rejects.toThrow('更新失败');
    });
  });
});
