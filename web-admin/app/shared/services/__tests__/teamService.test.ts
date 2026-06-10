/**
 * Unit tests for teamService
 * Validates URL construction, payload forwarding, and response handling.
 * teamService uses ResultHelper.isSuccess (code==='0') and result.desc.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { getMock, postMock, putMock, delMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
  putMock: vi.fn(),
  delMock: vi.fn(),
}));

vi.mock('~/shared/services/http-client', () => ({
  get: getMock,
  post: postMock,
  put: putMock,
  del: delMock,
}));

import {
  fetchTeams,
  fetchTeam,
  createTeam,
  updateTeam,
  deleteTeam,
  fetchTeamMembers,
  addTeamMember,
  removeTeamMember,
  fetchCurrentUserTeams,
} from '../teamService';

function ok<T>(data: T) {
  return { code: '0', desc: '', data };
}

function fail(desc = 'Server error') {
  return { code: '1', desc, data: null };
}

const TEAM = {
  pid: 't1',
  code: 'SALES',
  name: 'Sales Team',
  description: null,
  leaderId: null,
  leaderName: null,
  status: 'active',
  memberCount: 5,
  createdAt: '2024-01-01',
  updatedAt: '2024-01-01',
};

const MEMBER = {
  pid: 'm1',
  userId: 42,
  userName: 'Alice',
  userEmail: 'alice@example.com',
  role: 'member',
  joinedAt: '2024-01-01',
};

describe('teamService', () => {
  beforeEach(() => {
    getMock.mockReset();
    postMock.mockReset();
    putMock.mockReset();
    delMock.mockReset();
  });

  // ── fetchTeams ───────────────────────────────────────────────────────────────

  describe('fetchTeams', () => {
    it('GETs /api/org/teams and returns array', async () => {
      getMock.mockResolvedValue(ok([TEAM]));

      const result = await fetchTeams();

      expect(getMock).toHaveBeenCalledWith('/api/org/teams', undefined, undefined, undefined);
      expect(result).toHaveLength(1);
      expect(result[0].pid).toBe('t1');
    });

    it('throws when result code is not 0', async () => {
      getMock.mockResolvedValue(fail('Forbidden'));

      await expect(fetchTeams()).rejects.toThrow('Forbidden');
    });

    it('throws when data is null', async () => {
      getMock.mockResolvedValue({ code: '0', desc: '', data: null });

      await expect(fetchTeams()).rejects.toThrow('Failed to fetch teams');
    });
  });

  // ── fetchTeam ────────────────────────────────────────────────────────────────

  describe('fetchTeam', () => {
    it('GETs /api/org/teams/:pid', async () => {
      getMock.mockResolvedValue(ok(TEAM));

      const result = await fetchTeam('t1');

      expect(getMock).toHaveBeenCalledWith('/api/org/teams/t1', undefined, undefined, undefined);
      expect(result.pid).toBe('t1');
    });

    it('throws on failure', async () => {
      getMock.mockResolvedValue(fail('Not found'));

      await expect(fetchTeam('t1')).rejects.toThrow('Not found');
    });
  });

  // ── createTeam ───────────────────────────────────────────────────────────────

  describe('createTeam', () => {
    it('POSTs to /api/org/teams with team data', async () => {
      postMock.mockResolvedValue(ok(TEAM));

      const result = await createTeam({ code: 'SALES', name: 'Sales Team' });

      expect(postMock).toHaveBeenCalledWith(
        '/api/org/teams',
        { code: 'SALES', name: 'Sales Team' },
        undefined,
        undefined,
      );
      expect(result.code).toBe('SALES');
    });

    it('throws on failure', async () => {
      postMock.mockResolvedValue(fail('Code already exists'));

      await expect(createTeam({ code: 'SALES', name: 'Sales Team' })).rejects.toThrow(
        'Code already exists',
      );
    });
  });

  // ── updateTeam ───────────────────────────────────────────────────────────────

  describe('updateTeam', () => {
    it('PUTs to /api/org/teams/:pid', async () => {
      const updated = { ...TEAM, name: 'New Name' };
      putMock.mockResolvedValue(ok(updated));

      const result = await updateTeam('t1', { name: 'New Name' });

      expect(putMock).toHaveBeenCalledWith(
        '/api/org/teams/t1',
        { name: 'New Name' },
        undefined,
        undefined,
      );
      expect(result.name).toBe('New Name');
    });

    it('throws on failure', async () => {
      putMock.mockResolvedValue(fail('Bad request'));

      await expect(updateTeam('t1', {})).rejects.toThrow('Bad request');
    });
  });

  // ── deleteTeam ───────────────────────────────────────────────────────────────

  describe('deleteTeam', () => {
    it('DELs /api/org/teams/:pid and resolves void', async () => {
      delMock.mockResolvedValue(ok(true));

      await expect(deleteTeam('t1')).resolves.toBeUndefined();
      expect(delMock).toHaveBeenCalledWith('/api/org/teams/t1', undefined, undefined, undefined);
    });

    it('throws on failure', async () => {
      delMock.mockResolvedValue(fail('Has members'));

      await expect(deleteTeam('t1')).rejects.toThrow('Has members');
    });
  });

  // ── fetchTeamMembers ──────────────────────────────────────────────────────────

  describe('fetchTeamMembers', () => {
    it('GETs /api/org/teams/:teamPid/members', async () => {
      getMock.mockResolvedValue(ok([MEMBER]));

      const result = await fetchTeamMembers('t1');

      expect(getMock).toHaveBeenCalledWith(
        '/api/org/teams/t1/members',
        undefined,
        undefined,
        undefined,
      );
      expect(result).toHaveLength(1);
      expect(result[0].userName).toBe('Alice');
    });

    it('throws on failure', async () => {
      getMock.mockResolvedValue(fail('Forbidden'));

      await expect(fetchTeamMembers('t1')).rejects.toThrow('Forbidden');
    });
  });

  // ── addTeamMember ─────────────────────────────────────────────────────────────

  describe('addTeamMember', () => {
    it('POSTs to /api/org/teams/:teamPid/members', async () => {
      postMock.mockResolvedValue(ok(MEMBER));

      const result = await addTeamMember('t1', { userId: 42, role: 'member' });

      expect(postMock).toHaveBeenCalledWith(
        '/api/org/teams/t1/members',
        { userId: 42, role: 'member' },
        undefined,
        undefined,
      );
      expect(result.userId).toBe(42);
    });

    it('throws on failure', async () => {
      postMock.mockResolvedValue(fail('Already member'));

      await expect(addTeamMember('t1', { userId: 42 })).rejects.toThrow('Already member');
    });
  });

  // ── removeTeamMember ──────────────────────────────────────────────────────────

  describe('removeTeamMember', () => {
    it('DELs /api/org/teams/:teamPid/members/:memberPid and resolves void', async () => {
      delMock.mockResolvedValue(ok(true));

      await expect(removeTeamMember('t1', 'm1')).resolves.toBeUndefined();
      expect(delMock).toHaveBeenCalledWith(
        '/api/org/teams/t1/members/m1',
        undefined,
        undefined,
        undefined,
      );
    });

    it('throws on failure', async () => {
      delMock.mockResolvedValue(fail('Member not found'));

      await expect(removeTeamMember('t1', 'x')).rejects.toThrow('Member not found');
    });
  });

  // ── fetchCurrentUserTeams ─────────────────────────────────────────────────────

  describe('fetchCurrentUserTeams', () => {
    it('GETs /api/org/teams/current-user and maps to TeamOption', async () => {
      getMock.mockResolvedValue(ok([TEAM]));

      const result = await fetchCurrentUserTeams();

      expect(getMock).toHaveBeenCalledWith(
        '/api/org/teams/current-user',
        undefined,
        undefined,
        undefined,
      );
      expect(result).toEqual([{ id: 't1', name: 'Sales Team' }]);
    });

    it('throws on failure', async () => {
      getMock.mockResolvedValue(fail('Unauthorized'));

      await expect(fetchCurrentUserTeams()).rejects.toThrow('Unauthorized');
    });

    it('throws when data is not array', async () => {
      getMock.mockResolvedValue({ code: '0', desc: '', data: null });

      await expect(fetchCurrentUserTeams()).rejects.toThrow('Failed to fetch current user teams');
    });
  });
});
