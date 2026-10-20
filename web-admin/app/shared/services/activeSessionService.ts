import { internalRequest, type InternalResponseData } from './http-client/internal-api';
import type { Result } from './http-client/types';
import { ResultHelper } from '~/utils/type';

export type ActiveSession = InternalResponseData<'get', '/api/user/sessions'>[number];
function success(result: Result<unknown>, message: string): void {
  if (!ResultHelper.isSuccess(result)) throw new Error(result.desc || message);
}
export async function fetchActiveSessions(request?: Request): Promise<ActiveSession[]> {
  const result = await internalRequest('get', '/api/user/sessions', { request });
  success(result, 'Failed to fetch sessions');
  if (!Array.isArray(result.data)) throw new Error('Invalid active session list');
  return result.data;
}
export async function revokeActiveSession(sessionPid: string, request?: Request): Promise<void> {
  success(
    await internalRequest('delete', '/api/user/sessions/{sessionPid}', {
      path: { sessionPid },
      request,
    }),
    'Failed to revoke session',
  );
}
export async function revokeCurrentSession(request?: Request): Promise<void> {
  success(
    await internalRequest('delete', '/api/user/sessions/current', { request }),
    'Failed to revoke current session',
  );
}
export async function revokeAllActiveSessions(request?: Request): Promise<void> {
  success(
    await internalRequest('delete', '/api/user/sessions', { request }),
    'Failed to revoke all sessions',
  );
}
