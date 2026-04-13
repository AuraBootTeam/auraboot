import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { UserProfile, UpdateUserProfileRequest } from '~/types/profile';

/**
 * 获取当前用户的个人资料
 */
export async function getUserProfile(request: Request): Promise<UserProfile | null> {
  const result = await fetchResult<UserProfile>(
    '/api/user/profile',
    {
      method: 'get',
    },
    request,
  );

  if (!ResultHelper.isSuccess(result) || !result.data) {
    console.error('获取用户资料失败:', result.desc || result.message);
    throw new Error(result.desc || result.message || '获取用户资料失败');
  }

  return result.data;
}

/**
 * 更新用户个人资料
 */
export async function updateUserProfile(
  request: Request,
  profileData: UpdateUserProfileRequest,
): Promise<UserProfile | null> {
  const result = await fetchResult<UserProfile>(
    '/api/user/profile',
    {
      method: 'put',
      params: profileData,
    },
    request,
  );

  if (!ResultHelper.isSuccess(result)) {
    console.error('更新用户资料失败:', result.desc);
    throw new Error(result.desc || '更新失败');
  }

  return result.data;
}

/**
 * 上传用户头像
 */
export async function uploadAvatar(file: File, request?: Request): Promise<string | null> {
  let token: string | null | undefined;
  if (request) {
    // Dynamic import to avoid pulling server-only session module into client bundle
    const { getTokenFromRequest } = await import('~/shared/services/session');
    token = await getTokenFromRequest(request);
  }
  const formData = new FormData();
  formData.append('file', file);

  const headers: HeadersInit = {};
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  try {
    const response = await fetch('/api/user/avatar', {
      method: 'post',
      headers,
      body: formData,
    });

    if (!response.ok) {
      throw new Error(`HTTP错误: ${response.status}`);
    }

    const result = await response.json();

    if (!ResultHelper.isSuccess(result)) {
      console.error('上传头像失败:', result.desc);
      throw new Error(result.desc || '上传失败');
    }

    return result.data;
  } catch (error) {
    console.error('上传头像失败:', error);
    throw error;
  }
}
