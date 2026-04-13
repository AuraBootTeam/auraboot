/**
 * 用户记忆管理服务
 * 管理用户偏好、投资风格等记忆信息
 */

import { get, post, put, del } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

const API_BASE_URL = '/chat';

/**
 * 记忆类型
 */
export type MemoryType = 'preference' | 'fact' | 'context';

/**
 * 用户记忆
 */
export interface UserMemory {
  id: number;
  memory_type: MemoryType;
  key: string;
  value: string;
  importance: number; // 0-1
  confidence: number; // 0-1
  source_session_id?: string;
  compliance_related: boolean;
  last_accessed?: string;
  created_at: string;
  updated_at: string;
}

/**
 * 创建记忆请求
 */
export interface CreateMemoryRequest {
  memory_type: MemoryType;
  key: string;
  value: string;
  importance?: number;
  confidence?: number;
  compliance_related?: boolean;
}

/**
 * 更新记忆请求
 */
export interface UpdateMemoryRequest {
  value?: string;
  importance?: number;
  confidence?: number;
}

/**
 * 获取用户记忆列表
 */
export async function getUserMemories(params?: {
  memory_type?: MemoryType;
  limit?: number;
  offset?: number;
}): Promise<{ memories: UserMemory[]; total: number }> {
  const result = await get<{ memories: UserMemory[]; total: number }>(
    `${API_BASE_URL}/user-memory`,
    { params },
  );
  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.desc || 'Failed to get user memories');
  }
  return result.data!;
}

/**
 * 创建用户记忆
 */
export async function createUserMemory(request: CreateMemoryRequest): Promise<UserMemory> {
  const result = await post<UserMemory>(`${API_BASE_URL}/user-memory`, request);
  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.desc || 'Failed to create user memory');
  }
  return result.data!;
}

/**
 * 更新用户记忆
 */
export async function updateUserMemory(
  id: number,
  request: UpdateMemoryRequest,
): Promise<UserMemory> {
  const result = await put<UserMemory>(`${API_BASE_URL}/user-memory/${id}`, request);
  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.desc || 'Failed to update user memory');
  }
  return result.data!;
}

/**
 * 删除用户记忆
 */
export async function deleteUserMemory(id: number): Promise<void> {
  const result = await del<void>(`${API_BASE_URL}/user-memory/${id}`);
  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.desc || 'Failed to delete user memory');
  }
}

/**
 * 获取记忆类型显示文本
 */
export function getMemoryTypeText(type: MemoryType): string {
  const typeMap: Record<MemoryType, string> = {
    preference: '用户偏好',
    fact: '已知事实',
    context: '上下文信息',
  };
  return typeMap[type] || type;
}

/**
 * 获取记忆类型颜色
 */
export function getMemoryTypeColor(type: MemoryType): string {
  const colorMap: Record<MemoryType, string> = {
    preference: 'bg-blue-100 text-blue-800',
    fact: 'bg-green-100 text-green-800',
    context: 'bg-purple-100 text-purple-800',
  };
  return colorMap[type] || 'bg-gray-100 text-gray-800';
}
