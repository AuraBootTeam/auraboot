import type { Result, User } from '~/utils/type';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { getTokenFromRequest, sessionStorage } from '~/services/session.js';
import { JWT_TOKEN_KEY } from '~/constants/AuthConstant';

export async function getFormSchema(id: string, request: Request) {
  // 如果提供了请求对象，则从中获取 token
  const token = request ? await getTokenFromRequest(request) : undefined;

  const result = await fetchResult<any>(`/api/view/new/${id}`, {
    method: 'get',
    params: {},
    token,
  });

  if (!ResultHelper.isSuccess(result)) {
    return null;
  } else {
    return result.data;
  }
}

export async function getFormData(id: string, request: Request) {
  // 如果提供了请求对象，则从中获取 token
  const token = await getTokenFromRequest(request);

  const result = await fetchResult<any>(`/api/view/${id}`, {
    method: 'get',
    params: {},
    token,
  });

  if (!ResultHelper.isSuccess(result)) {
    return null;
  } else {
    return result.data;
  }
}

export async function submitFormData(data: any, request: Request) {
  // 如果提供了请求对象，则从中获取 token
  const token = await getTokenFromRequest(request);
  // 使用 HttpUtil 中的 fetchResult 函数替代原始的 fetch 调用
  const result = await fetchResult<any>('/api/view/create', {
    method: 'post',
    params: data,
    token,
  });

  return result;
}

// 添加保存表单设计的方法
export async function saveFormDesign(designData: any, request: Request) {
  // 从请求中获取 token
  const token = await getTokenFromRequest(request);

  // 调用后端API保存表单设计
  const result = await fetchResult<any>('/api/page/schema/create', {
    method: 'post',
    params: designData,
    token,
  });

  return result;
}

export async function getItemList(request: Request, id: string) {
  const token = await getTokenFromRequest(request);

  const result = await fetchResult<any>(`/api/page/list/{id}`, {
    method: 'get',
    params: { id: id },
    token,
  });

  if (!ResultHelper.isSuccess(result)) {
    return null;
  } else {
    return result.data;
  }
}

// 添加提交搜索查询的方法，使用 GET 请求
export async function submitSearchQuery(formData: FormData, request: Request, id?: string) {
  // 从请求中获取 token
  const token = await getTokenFromRequest(request);

  // 将 FormData 转换为普通对象
  const searchParams = Object.fromEntries(formData.entries());

  // 调用后端API提交搜索查询，使用 GET 方法
  const result = await fetchResult<any>(`/api/view/list/{id}`, {
    method: 'get',
    params: {
      id: id,
      ...searchParams,
    },
    token,
  });

  if (!ResultHelper.isSuccess(result)) {
    return null;
  } else {
    return result.data;
  }
}

// 添加获取 i18n 数据的方法
export async function getI18nData(locale: string, request: Request) {
  const token = await getTokenFromRequest(request);

  try {
    // SSR/BFF inherits system proxy env. Ensure localhost requests bypass it.
    const noProxy = process.env.NO_PROXY || process.env.no_proxy || '';
    if (!noProxy.includes('localhost')) {
      const merged = noProxy ? `${noProxy},localhost,127.0.0.1` : 'localhost,127.0.0.1';
      process.env.NO_PROXY = merged;
      process.env.no_proxy = merged;
    }

    const finalLocale = locale || 'zh-CN';
    const url = new URL(`/api/i18n/${finalLocale}`, request.url).toString();

    const response = await fetch(url, {
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        'Content-Type': 'application/json',
      },
    });

    if (!response.ok) {
      console.error('Failed to fetch i18n data:', response.statusText);
      return {};
    }

    const result = await response.json();

    if (!ResultHelper.isSuccess(result) || !result.data) {
      console.error('Invalid response from /api/i18n:', result);
      return {};
    }

    return result.data;
  } catch (error) {
    console.error('Error fetching i18n data:', error);
    return {};
  }
}
