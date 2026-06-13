/**
 * Unit tests for userService
 *
 * Pure utility functions (hasPermission, hasRole, hasAnyPermission, hasAllPermissions)
 * are tested directly.
 *
 * fetchUserInfo / getUserInfo use native fetch + session + process.env; we mock
 * both getTokenFromRequest and global fetch to exercise the network paths.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getTokenMock } = vi.hoisted(() => ({
  getTokenMock: vi.fn(),
}));

vi.mock('~/shared/services/session', () => ({
  getTokenFromRequest: getTokenMock,
}));

import {
  fetchUserInfo,
  getUserInfo,
  hasPermission,
  hasRole,
  hasAnyPermission,
  hasAllPermissions,
} from '../userService';

import type { UserPermissions } from '~/utils/type';

const FAKE_REQUEST = new Request('http://localhost/');
const TOKEN = 'test-jwt';
type PermissionFixture = NonNullable<UserPermissions['permissions']>[number];
type RoleFixture = UserPermissions['roles'][number];
const permission = (code: string, name: string): PermissionFixture => ({ id: 1, code, name, type: 'model' });
const role = (code: string, name: string): RoleFixture => ({ id: 1, code, name, type: 'system' });

// ── hasPermission ─────────────────────────────────────────────────────────────

describe('hasPermission', () => {
  it('returns false when permissions is undefined', () => {
    expect(hasPermission(undefined, 'some.code')).toBe(false);
  });

  it('returns true when permissionCodes array contains the code', () => {
    const perms: UserPermissions = {
      roles: [],
      permissions: [],
      permissionCodes: ['order.view', 'order.edit'],
    };
    expect(hasPermission(perms, 'order.view')).toBe(true);
  });

  it('returns true when permissions array has matching code', () => {
    const perms: UserPermissions = {
      roles: [],
      permissions: [permission('order.delete', 'Delete Order')],
    };
    expect(hasPermission(perms, 'order.delete')).toBe(true);
  });

  it('returns false when code is not in either list', () => {
    const perms: UserPermissions = {
      roles: [],
      permissions: [permission('order.view', 'View Order')],
      permissionCodes: ['order.view'],
    };
    expect(hasPermission(perms, 'order.admin')).toBe(false);
  });

  it('returns false when both lists are empty', () => {
    const perms: UserPermissions = { roles: [], permissions: [] };
    expect(hasPermission(perms, 'some.code')).toBe(false);
  });
});

// ── hasRole ───────────────────────────────────────────────────────────────────

describe('hasRole', () => {
  it('returns false when permissions is undefined', () => {
    expect(hasRole(undefined, 'admin')).toBe(false);
  });

  it('returns true when roles contains matching code', () => {
    const perms: UserPermissions = {
      roles: [role('admin', 'Admin')],
      permissions: [],
    };
    expect(hasRole(perms, 'admin')).toBe(true);
  });

  it('returns false when role code does not match', () => {
    const perms: UserPermissions = {
      roles: [role('viewer', 'Viewer')],
      permissions: [],
    };
    expect(hasRole(perms, 'admin')).toBe(false);
  });
});

// ── hasAnyPermission ──────────────────────────────────────────────────────────

describe('hasAnyPermission', () => {
  const perms: UserPermissions = {
    roles: [],
    permissions: [permission('order.view', 'View Order')],
    permissionCodes: ['report.export'],
  };

  it('returns true when at least one permission matches', () => {
    expect(hasAnyPermission(perms, ['admin.all', 'order.view'])).toBe(true);
  });

  it('returns false when none match', () => {
    expect(hasAnyPermission(perms, ['admin.all', 'order.edit'])).toBe(false);
  });

  it('returns false for empty permission code list', () => {
    expect(hasAnyPermission(perms, [])).toBe(false);
  });
});

// ── hasAllPermissions ─────────────────────────────────────────────────────────

describe('hasAllPermissions', () => {
  const perms: UserPermissions = {
    roles: [],
    permissions: [permission('order.view', 'View')],
    permissionCodes: ['order.edit'],
  };

  it('returns true when all permissions match', () => {
    expect(hasAllPermissions(perms, ['order.view', 'order.edit'])).toBe(true);
  });

  it('returns false when any permission is missing', () => {
    expect(hasAllPermissions(perms, ['order.view', 'order.delete'])).toBe(false);
  });

  it('returns true for empty list (vacuously true)', () => {
    expect(hasAllPermissions(perms, [])).toBe(true);
  });
});

// ── fetchUserInfo ─────────────────────────────────────────────────────────────

describe('fetchUserInfo', () => {
  let fetchSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    getTokenMock.mockReset();
    fetchSpy = vi.spyOn(global, 'fetch');
  });

  afterEach(() => {
    fetchSpy.mockRestore();
  });

  it('returns null when no token is available', async () => {
    getTokenMock.mockResolvedValue(null);

    const result = await fetchUserInfo(FAKE_REQUEST);

    expect(result).toBeNull();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('calls /api/auth/me with Bearer token and returns user info', async () => {
    getTokenMock.mockResolvedValue(TOKEN);

    const userData = {
      user: { id: 42, name: 'Alice' },
      permissions: { roles: [], permissions: [] },
      preferences: null,
    };

    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => ({ code: '0', data: userData }),
    } as any);

    const result = await fetchUserInfo(FAKE_REQUEST);

    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/auth/me'),
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: `Bearer ${TOKEN}` }),
      }),
    );
    expect(result!.user).toEqual({ id: 42, name: 'Alice' });
    expect(result!.permissions).toEqual({ roles: [], permissions: [] });
    expect(result!.preferences).toBeNull();
  });

  it('returns null when response is not ok', async () => {
    getTokenMock.mockResolvedValue(TOKEN);
    fetchSpy.mockResolvedValue({ ok: false, statusText: 'Unauthorized' } as any);

    const result = await fetchUserInfo(FAKE_REQUEST);

    expect(result).toBeNull();
  });

  it('returns null when result code is not 0', async () => {
    getTokenMock.mockResolvedValue(TOKEN);
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => ({ code: '401', data: null }),
    } as any);

    const result = await fetchUserInfo(FAKE_REQUEST);

    expect(result).toBeNull();
  });

  it('returns null and does not throw on fetch error', async () => {
    getTokenMock.mockResolvedValue(TOKEN);
    fetchSpy.mockRejectedValue(new Error('Network error'));

    const result = await fetchUserInfo(FAKE_REQUEST);

    expect(result).toBeNull();
  });

  it('defaults permissions to empty arrays when not provided', async () => {
    getTokenMock.mockResolvedValue(TOKEN);
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0',
        data: { user: { id: 1 }, permissions: undefined, preferences: undefined },
      }),
    } as any);

    const result = await fetchUserInfo(FAKE_REQUEST);

    expect(result!.permissions).toEqual({ roles: [], permissions: [] });
    expect(result!.preferences).toBeNull();
  });
});

// ── getUserInfo ───────────────────────────────────────────────────────────────

describe('getUserInfo', () => {
  let fetchSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    getTokenMock.mockReset();
    fetchSpy = vi.spyOn(global, 'fetch');
  });

  afterEach(() => {
    fetchSpy.mockRestore();
  });

  it('returns nulls when no token', async () => {
    getTokenMock.mockResolvedValue(null);

    const result = await getUserInfo(FAKE_REQUEST);

    expect(result).toEqual({ user: null, permissions: null, preferences: null });
  });

  it('returns full info on success', async () => {
    getTokenMock.mockResolvedValue(TOKEN);
    const userData = {
      user: { id: 42, name: 'Alice' },
      permissions: { roles: [{ code: 'admin', name: 'Admin' }], permissions: [] },
      preferences: { theme: 'dark' },
    };
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => ({ code: '0', data: userData }),
    } as any);

    const result = await getUserInfo(FAKE_REQUEST);

    expect(result.user).toEqual(userData.user);
    expect(result.permissions?.roles).toHaveLength(1);
    expect(result.preferences).toEqual({ theme: 'dark' });
  });
});
