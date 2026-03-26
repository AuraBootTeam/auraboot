import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { getTokenFromRequest, sessionStorage } from '~/services/session.js';
import { JWT_TOKEN_KEY } from '~/constants/AuthConstant';

export interface FileRecord {
  fileId?: string; // 改为fileId而不是id
  fileName: string;
  originalName: string;
  fileSize: number;
  mimeType: string;
  localPath?: string;
  cloudPath?: string;
  storageType: 'local' | 'aliyun' | 'aws';
  uploadTime?: string; // 后端返回的是字符串格式
  createdBy?: string;
  status: 'success' | 'active' | 'deleted'; // 添加success状态
  url?: string; // 添加url字段
}

// 根据文件ID获取文件记录
export const getFileById = async (fileId: string, request: Request): Promise<FileRecord | null> => {
  try {
    // 从请求中获取 token
    const token = await getTokenFromRequest(request);

    const result = await fetchResult<FileRecord>('/api/file/{fileId}', {
      method: 'get',
      params: { fileId }, // 这会被替换到URL路径中
      token,
    });

    if (!ResultHelper.isSuccess(result)) {
      console.error('查询文件失败:', result.desc);
      return null;
    } else {
      return result.data || null;
    }
  } catch (error) {
    console.error('查询文件失败:', error);
    return null;
  }
};

// 保存文件记录到数据库
export const saveFileToDatabase = async (
  fileData: Omit<FileRecord, 'fileId' | 'status'>,
  request: Request,
): Promise<FileRecord> => {
  try {
    // 从请求中获取 token
    const token = await getTokenFromRequest(request);

    const result = await fetchResult<FileRecord>('/api/file/create', {
      method: 'post',
      params: fileData,
      token,
    });

    if (!ResultHelper.isSuccess(result)) {
      console.error('保存文件记录失败:', result.desc);
      throw new Error(result.desc || '保存文件记录失败');
    } else {
      return result.data!;
    }
  } catch (error) {
    console.error('保存文件记录失败:', error);
    throw error;
  }
};
