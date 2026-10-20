import { beforeEach, describe, expect, it, vi } from 'vitest';
const { call } = vi.hoisted(() => ({ call: vi.fn() }));
vi.mock('../http-client/internal-api', () => ({ internalRequest: call }));
import {
  fetchActiveSessions,
  revokeActiveSession,
  revokeCurrentSession,
  revokeAllActiveSessions,
  type ActiveSession,
} from '../activeSessionService';
const session = {
  pid: 'session-pid',
  deviceInfo: 'Browser',
  ipAddress: '127.0.0.1',
  createdAt: '2026-10-18T01:02:03Z',
  lastActiveAt: '2026-10-18T04:05:06Z',
} satisfies ActiveSession;
const cases = [
  {
    name: 'list',
    method: 'get',
    path: '/api/user/sessions',
    input: {},
    data: [session],
    expected: [session],
    invoke: fetchActiveSessions,
  },
  {
    name: 'specific',
    method: 'delete',
    path: '/api/user/sessions/{sessionPid}',
    input: { path: { sessionPid: 'session-pid' } },
    data: null,
    expected: undefined,
    invoke: (request?: Request) => revokeActiveSession('session-pid', request),
  },
  {
    name: 'current',
    method: 'delete',
    path: '/api/user/sessions/current',
    input: {},
    data: null,
    expected: undefined,
    invoke: revokeCurrentSession,
  },
  {
    name: 'all',
    method: 'delete',
    path: '/api/user/sessions',
    input: {},
    data: null,
    expected: undefined,
    invoke: revokeAllActiveSessions,
  },
];
describe('generated active session operations', () => {
  beforeEach(() => call.mockReset());
  for (const row of cases) {
    it(`${row.name} preserves exact operation and SSR context`, async () => {
      const request = new Request('https://admin.example.test/personal/security');
      call.mockResolvedValue({ code: '0', data: row.data });
      expect(await row.invoke(request)).toEqual(row.expected);
      expect(call).toHaveBeenCalledExactlyOnceWith(row.method, row.path, { ...row.input, request });
    });
    it(`${row.name} propagates refusal instead of declaring success`, async () => {
      call.mockResolvedValue({ code: '403', desc: 'Denied' });
      await expect(row.invoke()).rejects.toThrow('Denied');
    });
  }
  it.each([null, {}, { records: [] }])(
    'rejects malformed or absent session lists %j',
    async (data) => {
      call.mockResolvedValue({ code: '0', data });
      await expect(fetchActiveSessions()).rejects.toThrow('Invalid active session list');
    },
  );
  it('preserves a legitimate empty session list', async () => {
    call.mockResolvedValue({ code: '0', data: [] });
    expect(await fetchActiveSessions()).toEqual([]);
  });
});
