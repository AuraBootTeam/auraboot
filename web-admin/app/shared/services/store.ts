import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { getTokenFromRequest } from '~/shared/services/session.js';

// Store相关的类型定义
export interface Store {
  pid: string;
  name: string;
  code: string;
  type: string;
  addressId?: string;
  status: string;
  openDate?: string;
  closeDate?: string;
  extension?: Record<string, any>;
  createdAt: string;
  updatedAt: string;
}

export interface StoreCreateRequest {
  name: string;
  code: string;
  type: string;
  addressId?: string;
  status: string;
  openDate?: string;
  closeDate?: string;
  extension?: Record<string, any>;
}

export interface StoreUpdateRequest {
  name?: string;
  code?: string;
  type?: string;
  addressId?: string;
  status?: string;
  openDate?: string;
  closeDate?: string;
  extension?: Record<string, any>;
}

export interface PaginationRequest {
  pageNum: number;
  pageSize: number;
  keyword?: string;
}

export interface PaginationResult<T> {
  records: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

// 获取Store列表（分页）
export async function getStoreList(request: Request, pagination: PaginationRequest) {
  const token = await getTokenFromRequest(request);

  const result = await fetchResult<PaginationResult<Store>>('/api/stores', {
    method: 'get',
    params: {
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      keyword: pagination.keyword,
    },
    token,
  });

  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.message || 'Failed to fetch store list');
  }

  return result.data;
}

// 根据PID获取Store详情
export async function getStoreByPid(request: Request, pid: string) {
  const token = await getTokenFromRequest(request);

  const result = await fetchResult<Store>(`/api/stores/${pid}`, {
    method: 'get',
    params: {},
    token,
  });

  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.message || '获取Store详情失败');
  }

  return result.data;
}

// 创建Store
export async function createStore(request: Request, storeData: StoreCreateRequest) {
  const token = await getTokenFromRequest(request);

  const result = await fetchResult<Store>('/api/stores', {
    method: 'post',
    params: storeData,
    token,
  });

  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.message || '创建Store失败');
  }

  return result.data;
}

// 更新Store
export async function updateStore(request: Request, pid: string, storeData: StoreUpdateRequest) {
  const token = await getTokenFromRequest(request);

  const result = await fetchResult<Store>(`/api/stores/${pid}`, {
    method: 'put',
    params: storeData,
    token,
  });

  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.message || '更新Store失败');
  }

  return result.data;
}

// 删除Store
export async function deleteStore(request: Request, pid: string) {
  const token = await getTokenFromRequest(request);

  const result = await fetchResult<void>(`/api/stores/${pid}`, {
    method: 'delete',
    params: {},
    token,
  });

  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.message || '删除Store失败');
  }

  return result.data;
}

// 批量删除Store
export async function batchDeleteStores(request: Request, pids: string[]) {
  const token = await getTokenFromRequest(request);

  const result = await fetchResult<void>('/api/stores/batch', {
    method: 'delete',
    params: { pids },
    token,
  });

  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.message || '批量删除Store失败');
  }

  return result.data;
}

// 根据租户ID获取Store列表
export async function getStoresByTenant(request: Request, tenantId: string) {
  const token = await getTokenFromRequest(request);

  const result = await fetchResult<Store[]>(`/api/stores/tenant/${tenantId}`, {
    method: 'get',
    params: {},
    token,
  });

  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.message || '获取租户Store列表失败');
  }

  return result.data;
}

// 检查Store代码是否唯一
export async function checkStoreCodeUnique(request: Request, code: string, excludePid?: string) {
  const token = await getTokenFromRequest(request);

  const result = await fetchResult<boolean>(`/api/stores/check-code/${code}`, {
    method: 'get',
    params: excludePid ? { excludePid } : {},
    token,
  });

  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.message || '检查Store代码失败');
  }

  return result.data;
}
