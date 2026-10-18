import { internalFormRequest, internalRequest } from '~/shared/services/http-client/internal-api';
import { ResultHelper } from '~/utils/type';
import type {
  UserProfile,
  UserProfileWire,
  UpdateUserProfileRequest,
  FileUploadResponse,
  FileUploadResponseWire,
} from '~/types/profile';

function profileForView(data: UserProfileWire | null | undefined): UserProfile {
  if (
    !data ||
    Array.isArray(data) ||
    typeof data.pid !== 'string' ||
    !data.pid ||
    typeof data.userName !== 'string' ||
    !data.userName
  ) {
    throw new Error('Profile response is missing its identity');
  }
  return { ...data, pid: data.pid, userName: data.userName };
}

function uploadForView(data: FileUploadResponseWire | null | undefined): FileUploadResponse {
  if (
    !data ||
    Array.isArray(data) ||
    typeof data.fileId !== 'string' ||
    !data.fileId ||
    typeof data.url !== 'string' ||
    !data.url
  ) {
    throw new Error('Avatar upload response is missing its file identity or URL');
  }
  return { ...data, fileId: data.fileId, url: data.url };
}

export async function getUserProfile(request: Request): Promise<UserProfile> {
  const result = await internalRequest('get', '/api/user/profile', { request });
  if (!ResultHelper.isSuccess(result))
    throw new Error(result.desc || result.message || 'Failed to load profile');
  return profileForView(result.data);
}

export async function updateUserProfile(
  request: Request,
  profileData: UpdateUserProfileRequest,
): Promise<UserProfile> {
  const result = await internalRequest('put', '/api/user/profile', { body: profileData, request });
  if (!ResultHelper.isSuccess(result))
    throw new Error(result.desc || result.message || 'Failed to update profile');
  return profileForView(result.data);
}

export async function uploadAvatar(file: File, request?: Request): Promise<FileUploadResponse> {
  const result = await internalFormRequest('post', '/api/user/avatar/upload', {
    body: { file },
    request,
  });
  if (!ResultHelper.isSuccess(result))
    throw new Error(result.desc || result.message || 'Failed to upload avatar');
  return uploadForView(result.data);
}

/** Only mutable profile fields enter the JSON operation; credentials use verified flows. */
export function profileUpdateFromFormData(form: FormData): UpdateUserProfileRequest {
  const text = (name: string) => {
    const value = form.get(name);
    if (value !== null && typeof value !== 'string')
      throw new Error(`Invalid profile field: ${name}`);
    return value || undefined;
  };
  return {
    nickName: text('nickName'),
    mobile: text('mobile'),
    area: text('area'),
    signature: text('signature'),
    imgId: text('imgId'),
  };
}
