import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { InternalResponseData } from '../http-client/internal-api';
const { call } = vi.hoisted(() => ({ call: vi.fn() }));
vi.mock('~/shared/services/http-client/internal-api', () => ({ internalRequest: call }));
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
const team = {
  pid: 'team-pid',
  code: 'SALES',
  name: 'Sales',
  description: null,
  leaderId: null,
  leaderName: null,
  status: 'active',
  memberCount: 1,
  createdAt: '2026-10-18T00:00:00Z',
  updatedAt: '2026-10-18T00:00:00Z',
} satisfies InternalResponseData<'get', '/api/org/teams/{pid}'>;
const member = {
  pid: 'team-membership-pid',
  userId: 42,
  userPid: 'user-pid',
  memberPid: 'tenant-member-pid',
  userName: 'Alice',
  userEmail: null,
  role: 'member',
  joinedAt: '2026-10-18T00:00:00Z',
} satisfies InternalResponseData<'post', '/api/org/teams/{teamPid}/members'>;
const { userId: _legacyUserId, ...memberView } = member;
const create = { code: 'SALES', name: 'Sales' },
  update = { name: 'Updated' },
  add = { memberPid: 'tenant-member-pid', role: 'member' };
const cases = [
  {
    name: 'list',
    method: 'get',
    path: '/api/org/teams',
    input: {},
    data: [team],
    expected: [team],
    invoke: fetchTeams,
  },
  {
    name: 'read',
    method: 'get',
    path: '/api/org/teams/{pid}',
    input: { path: { pid: 'team-pid' } },
    data: team,
    expected: team,
    invoke: (r?: Request) => fetchTeam('team-pid', r),
  },
  {
    name: 'create',
    method: 'post',
    path: '/api/org/teams',
    input: { body: create },
    data: team,
    expected: team,
    invoke: (r?: Request) => createTeam(create, r),
  },
  {
    name: 'update',
    method: 'put',
    path: '/api/org/teams/{pid}',
    input: { path: { pid: 'team-pid' }, body: update },
    data: team,
    expected: team,
    invoke: (r?: Request) => updateTeam('team-pid', update, r),
  },
  {
    name: 'delete',
    method: 'delete',
    path: '/api/org/teams/{pid}',
    input: { path: { pid: 'team-pid' } },
    data: true,
    expected: undefined,
    invoke: (r?: Request) => deleteTeam('team-pid', r),
  },
  {
    name: 'members',
    method: 'get',
    path: '/api/org/teams/{teamPid}/members',
    input: { path: { teamPid: 'team-pid' } },
    data: [member],
    expected: [memberView],
    invoke: (r?: Request) => fetchTeamMembers('team-pid', r),
  },
  {
    name: 'add member',
    method: 'post',
    path: '/api/org/teams/{teamPid}/members',
    input: { path: { teamPid: 'team-pid' }, body: add },
    data: member,
    expected: memberView,
    invoke: (r?: Request) => addTeamMember('team-pid', add, r),
  },
  {
    name: 'remove member',
    method: 'delete',
    path: '/api/org/teams/{teamPid}/members/{memberPid}',
    input: { path: { teamPid: 'team-pid', memberPid: 'team-membership-pid' } },
    data: true,
    expected: undefined,
    invoke: (r?: Request) => removeTeamMember('team-pid', 'team-membership-pid', r),
  },
  {
    name: 'current-user teams',
    method: 'get',
    path: '/api/org/teams/current-user',
    input: {},
    data: [team],
    expected: [{ id: 'team-pid', name: 'Sales' }],
    invoke: fetchCurrentUserTeams,
  },
];
describe('generated team operations', () => {
  beforeEach(() => call.mockReset());
  it.each(cases)('$name binds the operation and forwards the SSR request', async (row) => {
    const request = new Request('http://bff.local/organization/teams');
    call.mockResolvedValue({ code: '0', data: row.data });
    expect(await row.invoke(request)).toEqual(row.expected);
    expect(call).toHaveBeenCalledExactlyOnceWith(row.method, row.path, { ...row.input, request });
  });
  it.each(cases)('$name preserves a rejected envelope', async (row) => {
    call.mockResolvedValue({ code: '1', data: null, desc: 'Access denied' });
    await expect(row.invoke()).rejects.toThrow('Access denied');
  });
  it.each(cases.filter((row) => row.expected !== undefined))(
    '$name refuses absent successful data',
    async (row) => {
      call.mockResolvedValue({ code: '0', data: null });
      await expect(row.invoke()).rejects.toThrow(/Failed to/);
    },
  );
  it('preserves nullable related identity without changing the membership PID', async () => {
    call.mockResolvedValue({
      code: '0',
      data: { ...member, userPid: null, memberPid: null, userName: null, userEmail: null },
    });
    expect(await addTeamMember('team-pid', { userPid: 'user-pid' })).toEqual({
      ...memberView,
      userPid: null,
      memberPid: null,
      userName: null,
      userEmail: null,
    });
  });
  it('discards legacy int64 identity without rounding it into a UI key', async () => {
    call.mockResolvedValue({
      code: '0',
      data: [{ ...member, userId: Number.MAX_SAFE_INTEGER + 1 }],
    });
    expect(await fetchTeamMembers('team-pid')).toEqual([memberView]);
    expect((await fetchTeamMembers('team-pid'))[0]).not.toHaveProperty('userId');
  });
  it.each(['', null])('refuses an absent public membership PID (%s)', async (pid) => {
    call.mockResolvedValue({ code: '0', data: [{ ...member, pid }] });
    await expect(fetchTeamMembers('team-pid')).rejects.toThrow('Invalid team membership identity');
  });
  it('rejects legacy numeric add-user input at the service boundary', async () => {
    // @ts-expect-error UI callers must use public PIDs instead of an unsafe int64.
    await expect(addTeamMember('team-pid', { userId: 42 })).rejects.toThrow(
      'A public user or tenant-member PID is required',
    );
    expect(call).not.toHaveBeenCalled();
  });
});
