import { fetchResult } from '~/shared/services/http-client';
import { getTokenFromRequest } from './session';
import type {
  FeedbackItem,
  FeedbackComment,
  CreateFeedbackRequest,
  CreateCommentRequest,
} from '~/types/feedback';

interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

/**
 * List feedback items with pagination, filters, and sort.
 */
export async function listFeedback(
  request: Request,
  params: {
    pageNum?: number;
    pageSize?: number;
    type?: string;
    status?: string;
    sortBy?: string;
    sortOrder?: string;
  } = {},
): Promise<PageResult<FeedbackItem>> {
  const token = await getTokenFromRequest(request);
  const result = await fetchResult<PageResult<FeedbackItem>>('/api/feedback', {
    method: 'get',
    params: {
      pageNum: params.pageNum ?? 1,
      pageSize: params.pageSize ?? 20,
      ...(params.type ? { type: params.type } : {}),
      ...(params.status ? { status: params.status } : {}),
      sortBy: params.sortBy ?? 'voteCount',
      sortOrder: params.sortOrder ?? 'desc',
    },
    token,
  });

  if (result.code !== '0' || !result.data) {
    return { records: [], total: 0, size: 20, current: 1, pages: 0 };
  }
  return result.data;
}

/**
 * Create a new feedback item.
 */
export async function createFeedback(
  request: Request,
  data: CreateFeedbackRequest,
): Promise<FeedbackItem | null> {
  const token = await getTokenFromRequest(request);
  const result = await fetchResult<FeedbackItem>('/api/feedback', {
    method: 'post',
    params: data,
    token,
  });

  if (result.code !== '0') {
    throw new Error(String(result.data) || 'Failed to create feedback');
  }
  return result.data;
}

/**
 * Toggle vote on a feedback item.
 */
export async function toggleVote(request: Request, feedbackId: number): Promise<boolean> {
  const token = await getTokenFromRequest(request);
  const result = await fetchResult<{ voted: boolean }>(`/api/feedback/${feedbackId}/vote`, {
    method: 'post',
    token,
  });

  if (result.code !== '0' || !result.data) {
    throw new Error('Failed to toggle vote');
  }
  return result.data.voted;
}

/**
 * Get comments for a feedback item.
 */
export async function getComments(
  request: Request,
  feedbackId: number,
): Promise<FeedbackComment[]> {
  const token = await getTokenFromRequest(request);
  const result = await fetchResult<FeedbackComment[]>(`/api/feedback/${feedbackId}/comments`, {
    method: 'get',
    token,
  });

  if (result.code !== '0' || !result.data) {
    return [];
  }
  return result.data;
}

/**
 * Add a comment to a feedback item.
 */
export async function addComment(
  request: Request,
  feedbackId: number,
  data: CreateCommentRequest,
): Promise<FeedbackComment | null> {
  const token = await getTokenFromRequest(request);
  const result = await fetchResult<FeedbackComment>(`/api/feedback/${feedbackId}/comments`, {
    method: 'post',
    params: data,
    token,
  });

  if (result.code !== '0') {
    throw new Error(String(result.data) || 'Failed to add comment');
  }
  return result.data;
}

/**
 * Update feedback status (admin).
 */
export async function updateFeedbackStatus(
  request: Request,
  feedbackId: number,
  status: string,
): Promise<FeedbackItem | null> {
  const token = await getTokenFromRequest(request);
  const result = await fetchResult<FeedbackItem>(`/api/feedback/${feedbackId}/status`, {
    method: 'put',
    params: { status },
    token,
  });

  if (result.code !== '0') {
    throw new Error(String(result.data) || 'Failed to update status');
  }
  return result.data;
}

/**
 * Delete a feedback item.
 */
export async function deleteFeedback(request: Request, feedbackId: number): Promise<void> {
  const token = await getTokenFromRequest(request);
  const result = await fetchResult<boolean>(`/api/feedback/${feedbackId}`, {
    method: 'delete',
    token,
  });

  if (result.code !== '0') {
    throw new Error(String(result.data) || 'Failed to delete feedback');
  }
}
