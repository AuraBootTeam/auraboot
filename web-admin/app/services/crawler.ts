import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { getTokenFromRequest } from '~/services/session';

export interface CrawlerTask {
  id: string;
  name: string;
  description?: string;
  site: string;
  config: Record<string, unknown>;
  enabled: boolean;
  createdAt: string;
}

export interface Article {
  id: number;
  source: string;
  stock?: string;
  url: string;
  title: string;
  author?: string;
  contentText: string;
  publishTime?: string;
  createdAt: string;
}

export interface CreateTaskRequest {
  name: string;
  description?: string;
  site: string;
  config: Record<string, unknown>;
}

/**
 * 获取任务列表
 */
export async function getTasks(
  request: Request,
  page: number = 1,
  size: number = 100,
): Promise<CrawlerTask[]> {
  const token = (await getTokenFromRequest(request)) || undefined;
  interface PageResponse {
    records: CrawlerTask[];
    total: number;
  }
  const result = await fetchResult<PageResponse>(
    `/api/crawler/tasks/templates?page=${page}&size=${size}`,
    {
      method: 'get',
      token,
    },
  );

  if (!ResultHelper.isSuccess(result)) {
    console.error('获取任务列表失败:', result.desc);
    return [];
  }

  return result.data?.records ?? [];
}

/**
 * 创建任务
 */
export async function createTask(
  request: Request,
  taskData: CreateTaskRequest,
): Promise<CrawlerTask | null> {
  const token = (await getTokenFromRequest(request)) || undefined;
  const result = await fetchResult<CrawlerTask>('/api/crawler/tasks/templates', {
    method: 'post',
    params: taskData,
    token,
  });

  if (!ResultHelper.isSuccess(result)) {
    console.error('创建任务失败:', result.desc);
    throw new Error(result.desc || '创建失败');
  }

  return result.data;
}

/**
 * 执行任务
 */
export async function executeTask(request: Request, templateId: string): Promise<void> {
  const token = (await getTokenFromRequest(request)) || undefined;
  const result = await fetchResult<{ id?: string }>(
    `/api/crawler/tasks/templates/${templateId}/execute`,
    {
      method: 'post',
      token,
    },
  );

  if (!ResultHelper.isSuccess(result)) {
    console.error('执行任务失败:', result.desc);
    throw new Error(result.desc || '执行失败');
  }
}

/**
 * 获取文章列表
 */
export async function getArticles(
  request: Request,
  source?: string,
  stock?: string,
): Promise<Article[]> {
  const token = (await getTokenFromRequest(request)) || undefined;
  const params: Record<string, string> = {};
  if (source) params.source = source;
  if (stock) params.stock = stock;

  const queryString = new URLSearchParams(params).toString();
  const apiUrl = `/api/crawler/articles${queryString ? '?' + queryString : ''}`;

  interface PageResponse {
    records: Article[];
    total: number;
  }
  const result = await fetchResult<PageResponse>(apiUrl, {
    method: 'get',
    token,
  });

  if (!ResultHelper.isSuccess(result)) {
    console.error('获取文章列表失败:', result.desc);
    return [];
  }

  return result.data?.records ?? [];
}
