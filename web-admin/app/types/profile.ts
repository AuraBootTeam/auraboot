import type {
  InternalRequestInput,
  InternalResponseData,
} from '~/shared/services/http-client/internal-api';

export type UserProfileWire = InternalResponseData<'get', '/api/user/profile'>;
/** Identity required by the profile view, checked when adapting the wire response. */
export type UserProfile = UserProfileWire & { pid: string; userName: string };
export type UpdateUserProfileRequest = InternalRequestInput<'put', '/api/user/profile'>['body'];
export type FileUploadResponseWire = InternalResponseData<'post', '/api/user/avatar/upload'>;
/** Upload identity and usable URL are checked before the page accepts the upload. */
export type FileUploadResponse = FileUploadResponseWire & { fileId: string; url: string };
