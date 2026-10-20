import { internalRequest, type InternalRequestInput, type InternalResponseData } from './http-client/internal-api';
import { ResultHelper, type Result } from '~/utils/type';

type MemberWire = InternalResponseData<'get', '/api/roles/{rolePid}/members/candidates'>[number];
type PageWire = InternalResponseData<'get', '/api/roles/{rolePid}/members'>;
/** UI identity is the public member PID; the legacy numeric ID is deliberately discarded. */
export type RoleMember = Omit<MemberWire, 'memberId'>;
export type RoleMemberPage = Omit<PageWire, 'records'> &
  Required<Pick<PageWire, 'total' | 'page' | 'pageSize' | 'totalPages'>> & { records: RoleMember[] };
type Assignment = NonNullable<InternalRequestInput<'post', '/api/roles/{rolePid}/members/assign'>['body']>;
export type RoleMembershipWindow = Pick<Assignment, 'effectiveDate' | 'expiryDate'>;

function data<T>(result: Result<T>, message: string): T {
  if (!ResultHelper.isSuccess(result) || result.data == null) throw new Error(result.desc || message);
  return result.data;
}
function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function assertMembers(value: unknown): asserts value is MemberWire[] {
  if (!Array.isArray(value)) throw new Error('Invalid role member list');
  const seen = new Set<string>();
  for (const member of value) {
    if (!record(member) || typeof member.memberPid !== 'string' || !member.memberPid
        || seen.has(member.memberPid) || typeof member.memberId !== 'number' || !Number.isInteger(member.memberId))
      throw new Error('Invalid role member identity');
    seen.add(member.memberPid);
    for (const field of ['userName', 'email', 'departmentName', 'positionName', 'assignedAt', 'effectiveDate', 'expiryDate'])
      if (!(member[field] === null || typeof member[field] === 'string')) throw new Error(`Invalid role member ${field}`);
  }
}
function project({ memberId: _legacyId, ...member }: MemberWire): RoleMember { return member; }
function assertPids(memberPids: string[]) {
  if (!memberPids.length || memberPids.some(pid => typeof pid !== 'string' || !pid)
      || new Set(memberPids).size !== memberPids.length) throw new Error('Unique public member PIDs are required');
}
function success(result: Result<unknown>, message: string) {
  if (!ResultHelper.isSuccess(result)) throw new Error(result.desc || message);
}

export const roleMemberService = {
  async list(rolePid: string, query: { pageNum: number; pageSize: number }, request?: Request): Promise<RoleMemberPage> {
    const result = await internalRequest('get', '/api/roles/{rolePid}/members', { path: { rolePid }, query, request });
    const page = data(result, 'Failed to fetch role members');
    if (!record(page)) throw new Error('Invalid role member page');
    assertMembers(page.records);
    const { total, page: pageNumber, pageSize, totalPages } = page;
    if (typeof total !== 'number' || !Number.isSafeInteger(total) || total < 0
        || typeof pageNumber !== 'number' || !Number.isSafeInteger(pageNumber) || pageNumber < 1
        || typeof pageSize !== 'number' || !Number.isSafeInteger(pageSize) || pageSize < 1
        || typeof totalPages !== 'number' || !Number.isSafeInteger(totalPages) || totalPages < 0)
      throw new Error('Invalid role member pagination');
    return { ...page, total, page: pageNumber, pageSize, totalPages, records: page.records.map(project) };
  },
  async candidates(rolePid: string, keyword?: string, request?: Request): Promise<RoleMember[]> {
    const result = await internalRequest('get', '/api/roles/{rolePid}/members/candidates', {
      path: { rolePid }, query: keyword ? { keyword } : {}, request,
    });
    const members = data(result, 'Failed to fetch role member candidates');
    assertMembers(members);
    return members.map(project);
  },
  async assign(rolePid: string, memberPids: string[], window?: RoleMembershipWindow, request?: Request): Promise<void> {
    assertPids(memberPids);
    success(await internalRequest('post', '/api/roles/{rolePid}/members/assign', {
      path: { rolePid }, body: { ...window, memberPids }, request,
    }), 'Failed to add role members');
  },
  async remove(rolePid: string, memberPids: string[], request?: Request): Promise<void> {
    assertPids(memberPids);
    success(await internalRequest('post', '/api/roles/{rolePid}/members/remove', {
      path: { rolePid }, body: memberPids, request,
    }), 'Failed to remove role members');
  },
};
