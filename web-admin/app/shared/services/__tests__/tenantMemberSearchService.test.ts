import { beforeEach, describe, expect, it, vi } from 'vitest';
const { call } = vi.hoisted(() => ({ call: vi.fn() }));
vi.mock('../http-client/internal-api', () => ({ internalRequest: call }));
import { searchTenantMembers, type TenantMemberSearchRecord } from '../tenantMemberSearchService';
const row = {
  pid: 'membership-pid',
  status: 'active',
  user: {
    pid: 'user-pid',
    username: null,
    realName: null,
    email: 'only@example.test',
    avatar: null,
  },
} satisfies TenantMemberSearchRecord;
describe('tenant member search generated contract', () => {
  beforeEach(() => call.mockReset());
  it('binds the canonical operation and forwards the exact SSR request and body', async () => {
    const request = new Request('https://admin.example.test/organization/teams');
    const body = { pageNum: 1, pageSize: 20, status: 'active', keyword: 'only' };
    call.mockResolvedValue({ code: '0', data: { records: [row] } });
    expect(await searchTenantMembers(body, request)).toEqual([row]);
    expect(call).toHaveBeenCalledExactlyOnceWith('post', '/api/tenant/members/search', {
      body,
      request,
    });
    expect(row.user.username).toBeNull();
    expect(row.pid).not.toBe(row.user.pid);
  });
  it('preserves a successful empty page and nullable user without inventing an identity', async () => {
    call.mockResolvedValueOnce({ code: '0', data: { records: [] } });
    expect(await searchTenantMembers({})).toEqual([]);
    call.mockResolvedValueOnce({
      code: '0',
      data: { records: [{ pid: 'membership', user: null }] },
    });
    expect(await searchTenantMembers({})).toEqual([{ pid: 'membership', user: null }]);
  });
  it.each([null, [], { content: [row] }, {}, { records: null }])(
    'rejects noncanonical pagination %j',
    async (data) => {
      call.mockResolvedValue({ code: '0', data });
      await expect(searchTenantMembers({})).rejects.toThrow('pagination');
    },
  );
  it.each([
    { ...row, pid: '' },
    { ...row, user: { pid: '' } },
  ])('rejects missing public identity %j', async (record) => {
    call.mockResolvedValue({ code: '0', data: { records: [record] } });
    await expect(searchTenantMembers({})).rejects.toThrow('identity');
  });
  it('preserves refusal and transport failure instead of returning an empty success', async () => {
    call.mockResolvedValueOnce({ code: '403', desc: 'Denied' });
    await expect(searchTenantMembers({})).rejects.toThrow('Denied');
    call.mockRejectedValueOnce(new Error('offline'));
    await expect(searchTenantMembers({})).rejects.toThrow('offline');
  });
});
