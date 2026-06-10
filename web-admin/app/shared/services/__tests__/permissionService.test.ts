/**
 * Unit tests for permissionService
 *
 * permissionService uses `~/shared/services/http-client` (get, post, put, del, fetchResult).
 * ResultHelper.isSuccess: code === '0'.
 *
 * handleResponse in this file expects { code, desc, data } — same pattern as templateService.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { getMock, postMock, putMock, delMock, fetchResultMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
  putMock: vi.fn(),
  delMock: vi.fn(),
  fetchResultMock: vi.fn(),
}));

vi.mock('~/shared/services/http-client', () => ({
  get: getMock,
  post: postMock,
  put: putMock,
  del: delMock,
  fetchResult: fetchResultMock,
}));

import { permissionService } from '../permissionService';

const ok = <T>(data: T) => ({ code: '0', desc: 'OK', data });
const fail = (desc = 'error') => ({ code: '500', desc, data: null });

describe('permissionService', () => {
  beforeEach(() => {
    getMock.mockReset();
    postMock.mockReset();
    putMock.mockReset();
    delMock.mockReset();
    fetchResultMock.mockReset();
  });

  // ── getModelPermissions ─────────────────────────────────────────────────────

  describe('getModelPermissions', () => {
    it('GETs /api/permissions/model/:modelCode and returns permissions', async () => {
      const perms = [{ id: '1', code: 'order:read', name: 'Read Order' }];
      getMock.mockResolvedValue(ok(perms));

      const result = await permissionService.getModelPermissions('order');

      expect(getMock).toHaveBeenCalledWith('/api/permissions/model/order', undefined, undefined, undefined);
      expect(result).toEqual(perms);
    });

    it('throws on failure', async () => {
      getMock.mockResolvedValue(fail('Not found'));

      await expect(permissionService.getModelPermissions('order')).rejects.toThrow('Not found');
    });
  });

  // ── getAllPermissions ────────────────────────────────────────────────────────

  describe('getAllPermissions', () => {
    it('GETs /api/permissions and returns permission map', async () => {
      const map = { order: [{ id: '1', code: 'order:read' }] };
      getMock.mockResolvedValue(ok(map));

      const result = await permissionService.getAllPermissions();

      expect(getMock).toHaveBeenCalledWith('/api/permissions', undefined, undefined, undefined);
      expect(result).toEqual(map);
    });
  });

  // ── getRolePermissions ───────────────────────────────────────────────────────

  describe('getRolePermissions', () => {
    it('GETs /api/permissions/role/:roleId', async () => {
      const perms = [{ id: '1', code: 'order:read' }];
      getMock.mockResolvedValue(ok(perms));

      const result = await permissionService.getRolePermissions('role-1');

      expect(getMock).toHaveBeenCalledWith('/api/permissions/role/role-1', undefined, undefined, undefined);
      expect(result).toEqual(perms);
    });

    it('throws default message when desc is empty', async () => {
      getMock.mockResolvedValue({ code: '500', desc: '', data: null });

      await expect(permissionService.getRolePermissions('role-1')).rejects.toThrow('Failed to fetch role permissions');
    });
  });

  // ── bindPermissionToRole ────────────────────────────────────────────────────

  describe('bindPermissionToRole', () => {
    it('POSTs to /api/permissions/role/:roleId/bind with permissionId', async () => {
      postMock.mockResolvedValue(ok(null));

      await permissionService.bindPermissionToRole('role-1', 'perm-1');

      expect(postMock).toHaveBeenCalledWith(
        '/api/permissions/role/role-1/bind',
        { permissionId: 'perm-1' },
        undefined,
        undefined,
      );
    });

    it('throws when not success', async () => {
      postMock.mockResolvedValue(fail('Bind failed'));

      await expect(permissionService.bindPermissionToRole('role-1', 'perm-1')).rejects.toThrow('Bind failed');
    });
  });

  // ── unbindPermissionFromRole ────────────────────────────────────────────────

  describe('unbindPermissionFromRole', () => {
    it('POSTs to /api/permissions/role/:roleId/unbind with permissionId', async () => {
      postMock.mockResolvedValue(ok(null));

      await permissionService.unbindPermissionFromRole('role-1', 'perm-1');

      expect(postMock).toHaveBeenCalledWith(
        '/api/permissions/role/role-1/unbind',
        { permissionId: 'perm-1' },
        undefined,
        undefined,
      );
    });

    it('throws when not success', async () => {
      postMock.mockResolvedValue(fail('Unbind failed'));

      await expect(permissionService.unbindPermissionFromRole('role-1', 'perm-1')).rejects.toThrow('Unbind failed');
    });
  });

  // ── getPermissionReferences ─────────────────────────────────────────────────

  describe('getPermissionReferences', () => {
    it('GETs /api/permissions/:permId/references', async () => {
      const refs = [{ type: 'role', id: 'role-1', name: 'Admin' }];
      getMock.mockResolvedValue(ok(refs));

      const result = await permissionService.getPermissionReferences('perm-1');

      expect(getMock).toHaveBeenCalledWith('/api/permissions/perm-1/references', undefined, undefined, undefined);
      expect(result).toEqual(refs);
    });
  });

  // ── getMatrixForRole ────────────────────────────────────────────────────────

  describe('getMatrixForRole', () => {
    it('GETs /api/permissions/matrix/:rolePid', async () => {
      const matrix = { rolePid: 'role-1', permissions: [] };
      getMock.mockResolvedValue(ok(matrix));

      const result = await permissionService.getMatrixForRole('role-1');

      expect(getMock).toHaveBeenCalledWith('/api/permissions/matrix/role-1', undefined, undefined, undefined);
      expect(result).toEqual(matrix);
    });
  });

  // ── batchUpdateRolePermissions ──────────────────────────────────────────────

  describe('batchUpdateRolePermissions', () => {
    it('calls fetchResult PUT /api/permissions/matrix/:rolePid/batch', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      const grants = [{ permissionPid: 'p1', granted: true }];
      await permissionService.batchUpdateRolePermissions('role-1', grants as any);

      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/permissions/matrix/role-1/batch',
        expect.objectContaining({ method: 'put' }),
        undefined,
      );
    });

    it('throws when fetchResult indicates failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Update failed'));

      await expect(permissionService.batchUpdateRolePermissions('role-1', [])).rejects.toThrow('Update failed');
    });
  });

  // ── updateScope ──────────────────────────────────────────────────────────────

  describe('updateScope', () => {
    it('PUTs /api/permissions/matrix/:rolePid/scope with data', async () => {
      putMock.mockResolvedValue(ok(null));

      const data = { resourceCode: 'order', actionCode: 'read', scopeType: 'all' };
      await permissionService.updateScope('role-1', data);

      expect(putMock).toHaveBeenCalledWith('/api/permissions/matrix/role-1/scope', data, undefined, undefined);
    });

    it('throws on failure', async () => {
      putMock.mockResolvedValue(fail('Scope update failed'));

      await expect(
        permissionService.updateScope('role-1', { resourceCode: 'order', actionCode: 'read', scopeType: 'own' }),
      ).rejects.toThrow('Scope update failed');
    });
  });

  // ── getPolicy ────────────────────────────────────────────────────────────────

  describe('getPolicy', () => {
    it('GETs /api/permissions/matrix/:rolePid/policy/:permPid', async () => {
      const policy = { maxAmount: 10000 };
      getMock.mockResolvedValue(ok(policy));

      const result = await permissionService.getPolicy('role-1', 'perm-1');

      expect(getMock).toHaveBeenCalledWith(
        '/api/permissions/matrix/role-1/policy/perm-1',
        undefined,
        undefined,
        undefined,
      );
      expect(result).toEqual(policy);
    });
  });

  // ── setPolicy ────────────────────────────────────────────────────────────────

  describe('setPolicy', () => {
    it('PUTs /api/permissions/matrix/:rolePid/policy/:permPid with values', async () => {
      putMock.mockResolvedValue(ok(null));

      await permissionService.setPolicy('role-1', 'perm-1', { maxAmount: 5000 });

      expect(putMock).toHaveBeenCalledWith(
        '/api/permissions/matrix/role-1/policy/perm-1',
        { maxAmount: 5000 },
        undefined,
        undefined,
      );
    });

    it('throws on failure', async () => {
      putMock.mockResolvedValue(fail('Policy save failed'));

      await expect(permissionService.setPolicy('role-1', 'perm-1', {})).rejects.toThrow('Policy save failed');
    });
  });

  // ── getRoleMembers ───────────────────────────────────────────────────────────

  describe('getRoleMembers', () => {
    it('GETs /api/roles/:rolePid/members with pagination', async () => {
      const page = { records: [], total: 0, current: 1, size: 20 };
      getMock.mockResolvedValue(ok(page));

      const result = await permissionService.getRoleMembers('role-1', { pageNum: 1, pageSize: 20 });

      expect(getMock).toHaveBeenCalledWith(
        '/api/roles/role-1/members',
        { pageNum: 1, pageSize: 20 },
        undefined,
        undefined,
      );
      expect(result).toEqual(page);
    });
  });

  // ── addRoleMembers ───────────────────────────────────────────────────────────

  describe('addRoleMembers', () => {
    it('calls fetchResult POST /api/roles/:rolePid/members', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await permissionService.addRoleMembers('role-1', ['user-1', 'user-2']);

      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/roles/role-1/members',
        expect.objectContaining({ method: 'post' }),
        undefined,
      );
    });

    it('throws on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Add members failed'));

      await expect(permissionService.addRoleMembers('role-1', ['user-1'])).rejects.toThrow('Add members failed');
    });
  });

  // ── removeRoleMembers ─────────────────────────────────────────────────────────

  describe('removeRoleMembers', () => {
    it('calls fetchResult POST /api/roles/:rolePid/members/remove', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await permissionService.removeRoleMembers('role-1', ['user-1']);

      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/roles/role-1/members/remove',
        expect.objectContaining({ method: 'post' }),
        undefined,
      );
    });

    it('throws on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Remove members failed'));

      await expect(permissionService.removeRoleMembers('role-1', ['user-1'])).rejects.toThrow('Remove members failed');
    });
  });

  // ── getRoleMemberCandidates ───────────────────────────────────────────────────

  describe('getRoleMemberCandidates', () => {
    it('GETs /api/roles/:rolePid/members/candidates without keyword', async () => {
      const candidates = [{ id: 'user-1', name: 'Alice' }];
      getMock.mockResolvedValue(ok(candidates));

      const result = await permissionService.getRoleMemberCandidates('role-1');

      expect(getMock).toHaveBeenCalledWith('/api/roles/role-1/members/candidates', {}, undefined, undefined);
      expect(result).toEqual(candidates);
    });

    it('passes keyword when provided', async () => {
      getMock.mockResolvedValue(ok([]));

      await permissionService.getRoleMemberCandidates('role-1', 'alice');

      expect(getMock).toHaveBeenCalledWith(
        '/api/roles/role-1/members/candidates',
        { keyword: 'alice' },
        undefined,
        undefined,
      );
    });
  });
});
