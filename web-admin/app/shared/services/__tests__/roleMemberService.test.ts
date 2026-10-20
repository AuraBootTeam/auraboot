import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { InternalResponseData } from '../http-client/internal-api';
const { transport } = vi.hoisted(() => ({ transport: vi.fn() }));
vi.mock('../http-client/HttpClient', () => ({ fetchResult: transport }));
import { permissionService } from '../permissionService';
const member = { memberId: 9007199254740992, memberPid: 'member-pid', userName: null, email: null,
  departmentName: null, positionName: null, assignedAt: null, effectiveDate: null, expiryDate: null,
} satisfies InternalResponseData<'get', '/api/roles/{rolePid}/members/candidates'>[number];
const { memberId: _unsafeId, ...view } = member;
const page = { records: [member], total: 1, page: 1, pageSize: 20, totalPages: 1, nextCursor: null };
const ok = (data: unknown) => ({ code: '0', success: true, data });

describe('generated role membership facade', () => {
  beforeEach(() => transport.mockReset());
  it('binds actual page query and SSR request while discarding unsafe numeric identity', async () => {
    const request = new Request('https://web.example/roles');
    transport.mockResolvedValue(ok(page));
    const result = await permissionService.getRoleMembers('role /?', { pageNum: 1, pageSize: 20 }, request);
    expect(transport).toHaveBeenCalledExactlyOnceWith('/api/roles/role%20%2F%3F/members?pageNum=1&pageSize=20',
      expect.objectContaining({ method: 'get', body: undefined }), request);
    expect(result).toEqual({ ...page, records: [view] });
    expect(result.records[0]).not.toHaveProperty('memberId');
    expect(result).not.toHaveProperty('pageNum');
  });
  it('encodes candidate keywords and preserves missing personal and organizational information as null', async () => {
    transport.mockResolvedValue(ok([member]));
    expect(await permissionService.getRoleMemberCandidates('role-pid','a +&/字')).toEqual([view]);
    expect(transport.mock.calls[0][0]).toBe('/api/roles/role-pid/members/candidates?keyword=a+%2B%26%2F%E5%AD%97');
    transport.mockResolvedValue(ok([]));
    expect(await permissionService.getRoleMemberCandidates('role-pid')).toEqual([]);
    expect(transport.mock.calls[1][0]).toBe('/api/roles/role-pid/members/candidates');
  });
  it('submits permanent and nullable time-bounded assignments using only public member PIDs', async () => {
    transport.mockResolvedValue(ok(null));
    await permissionService.addRoleMembers('role-pid',['member-pid']);
    expect(transport).toHaveBeenLastCalledWith('/api/roles/role-pid/members/assign',
      expect.objectContaining({method:'post',body:{memberPids:['member-pid']}}),undefined);
    await permissionService.addRoleMembers('role-pid',['member-pid'],{effectiveDate:'2026-01-01',expiryDate:null});
    expect(transport).toHaveBeenLastCalledWith('/api/roles/role-pid/members/assign',
      expect.objectContaining({method:'post',body:{memberPids:['member-pid'],effectiveDate:'2026-01-01',expiryDate:null}}),undefined);
  });
  it('submits removals as an actual JSON array rather than a record assertion', async () => {
    transport.mockResolvedValue(ok(null));
    await permissionService.removeRoleMembers('role-pid',['member-pid']);
    expect(transport).toHaveBeenCalledExactlyOnceWith('/api/roles/role-pid/members/remove',
      expect.objectContaining({method:'post',body:['member-pid']}),undefined);
  });
  it.each([null, {}, [null], [{...member,memberPid:''}], [member,member], [{...member,email:undefined}]].map(data=>({data})))(
    'rejects malformed candidate data instead of inventing an empty list $data', async ({data}) => {
      transport.mockResolvedValue(ok(data));
      await expect(permissionService.getRoleMemberCandidates('role-pid')).rejects.toThrow();
    });
  it.each([null, {}, {...page,records:null}, {...page,page:undefined,pageNum:1}, {...page,total:-1}, {...page,pageSize:0}].map(data=>({data})))(
    'rejects invalid pagination rather than accepting the old handwritten shape $data', async ({data}) => {
      transport.mockResolvedValue(ok(data));
      await expect(permissionService.getRoleMembers('role-pid',{pageNum:1,pageSize:20})).rejects.toThrow();
    });
  it.each([
    {name:'list',action:()=>permissionService.getRoleMembers('role-pid',{pageNum:1,pageSize:20})},
    {name:'candidates',action:()=>permissionService.getRoleMemberCandidates('role-pid')},
    {name:'assign',action:()=>permissionService.addRoleMembers('role-pid',['member-pid'])},
    {name:'remove',action:()=>permissionService.removeRoleMembers('role-pid',['member-pid'])},
  ])('preserves backend refusal and transport errors for $name', async ({action}) => {
      transport.mockResolvedValueOnce({code:'403',success:false,desc:'Forbidden',data:null});
      await expect(action()).rejects.toThrow('Forbidden');
      transport.mockRejectedValueOnce(new Error('Network failed'));
      await expect(action()).rejects.toThrow('Network failed');
  });
  it.each([[],[''],['member-pid','member-pid']].map(pids=>({pids})))('refuses empty or duplicate write identities before transport $pids',async ({pids})=>{
    await expect(permissionService.addRoleMembers('role-pid',pids)).rejects.toThrow('public member PIDs');
    await expect(permissionService.removeRoleMembers('role-pid',pids)).rejects.toThrow('public member PIDs');
    expect(transport).not.toHaveBeenCalled();
  });
});
