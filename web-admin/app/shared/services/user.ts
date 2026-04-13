import type { User } from '~/utils/type';
import { get, post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

export async function getUserByIdFromRemote(id: User['id']) {
  const result = await get<User>('/getUserById', { id });

  if (!ResultHelper.isSuccess(result)) {
    return null;
  } else {
    return result.data as User;
  }
}

export async function signUp(email: string, password: string) {
  return await post<User>('/api/auth/register', { email, password });
}

export interface AuthenticationResponse {
  jwt: string;
  userPid: string;
  username: string;
  tenantId?: number;
  tenantStatus: 'member' | 'pending' | 'none';
}
