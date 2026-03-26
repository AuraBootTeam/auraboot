// ~/types/profile.ts
export interface UserProfile {
  userName: string;
  nickName?: string;
  email: string;
  mobile?: string;
  area?: string;
  signature?: string;
  avatarUrl?: string;
  imgId?: string; // 如果后端返回头像文件ID
  createdAt: string | number | Date;
  lastSignInAt?: string | number | Date;
}

export type UpdateUserProfileRequest = {
  nickName?: string;
  email?: string;
  mobile?: string;
  area?: string;
  signature?: string;
  imgId?: string; // 允许更新头像ID
};

export interface FileUploadResponse {
  fileId: string;
  fileName: string;
  originalName: string;
  fileSize: number;
  mimeType: string;
  localPath?: string;
  cloudPath?: string;
  storageType: string;
  uploadTime: string;
  createdBy: string;
  status: string;
  url?: string;
}
