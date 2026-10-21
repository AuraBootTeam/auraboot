import {
  internalRequest,
  type InternalRequestInput,
  type InternalResponseData,
} from './http-client/internal-api';
import { ResultHelper } from '~/utils/type';

export type TenantMemberSearchRequest = InternalRequestInput<
  'post',
  '/api/tenant/members/search'
>['body'];
export type TenantMemberSearchPage = InternalResponseData<'post', '/api/tenant/members/search'>;
export type TenantMemberSearchRecord = NonNullable<TenantMemberSearchPage['records']>[number];

/** Keep membership and user PIDs distinct; no numeric user ID is returned by this endpoint. */
export async function searchTenantMembers(
  body: TenantMemberSearchRequest,
  request?: Request,
): Promise<TenantMemberSearchRecord[]> {
  const result = await internalRequest('post', '/api/tenant/members/search', { body, request });
  if (!ResultHelper.isSuccess(result))
    throw new Error(result.desc || 'Failed to search tenant members');
  if (!result.data || !Array.isArray(result.data.records))
    throw new Error('Invalid tenant member search pagination');
  for (const member of result.data.records) {
    if (typeof member.pid !== 'string' || !member.pid)
      throw new Error('Invalid tenant membership identity');
    if (member.user != null && (typeof member.user.pid !== 'string' || !member.user.pid))
      throw new Error('Invalid tenant member user identity');
  }
  return result.data.records;
}
