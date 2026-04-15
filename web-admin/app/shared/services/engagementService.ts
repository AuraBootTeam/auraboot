/**
 * User Engagement Service
 *
 * Frontend service for the /api/user-engagement API.
 * Supports favorites, recent views, and pinned items.
 */

import { get, post, put, del } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

export interface UserEngagement {
  id: number;
  targetType: string;
  targetId: string;
  targetLabel: string;
  targetContext: {
    icon?: string;
    path?: string;
    modelCode?: string;
    color?: string;
  };
  engagementType: string;
  sortOrder: number;
  createdAt?: string;
}

const ENGAGEMENT_TYPE_FAVORITE = 'favorite';
const ENGAGEMENT_TYPE_RECENT_VIEW = 'recent_view';
const TARGET_TYPE_PAGE = 'page';

// ---------------------------------------------------------------------------
// Promise dedup — concurrent identical requests share one in-flight promise
// ---------------------------------------------------------------------------
const inflight = new Map<string, Promise<any>>();

function dedup<T>(key: string, fn: () => Promise<T>): Promise<T> {
  const existing = inflight.get(key);
  if (existing) return existing as Promise<T>;
  const promise = fn().finally(() => inflight.delete(key));
  inflight.set(key, promise);
  return promise;
}

/**
 * List favorites for the current user.
 *
 * @param targetType optional filter: menu | record | page
 */
export async function listFavorites(targetType?: string): Promise<UserEngagement[]> {
  const dedupKey = `favorites:${targetType || '__all__'}`;
  return dedup(dedupKey, async () => {
    const params: Record<string, string> = { engagementType: ENGAGEMENT_TYPE_FAVORITE };
    if (targetType) {
      params.targetType = targetType;
    }
    const result = await get<UserEngagement[]>('/api/user-engagement', params);
    if (!ResultHelper.isSuccess(result) || !result.data) {
      return [];
    }
    return result.data;
  });
}

/**
 * Add a favorite engagement record (upsert by composite key).
 */
export async function addFavorite(
  engagement: Partial<UserEngagement>,
): Promise<UserEngagement | null> {
  const result = await post<UserEngagement>('/api/user-engagement', {
    ...engagement,
    engagementType: ENGAGEMENT_TYPE_FAVORITE,
  });
  if (!ResultHelper.isSuccess(result) || !result.data) {
    return null;
  }
  return result.data;
}

/**
 * Remove a favorite engagement record by ID.
 */
export async function removeFavorite(id: number): Promise<void> {
  const result = await del<void>(`/api/user-engagement/${id}`);
  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.desc || 'Failed to remove favorite');
  }
}

/**
 * Reorder favorites by providing an ordered list of IDs.
 */
export async function reorderFavorites(orderedIds: number[]): Promise<void> {
  const result = await put<void>('/api/user-engagement/reorder', { orderedIds });
  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.desc || 'Failed to reorder favorites');
  }
}

/**
 * List recent page visits for the current user.
 * Backend returns results sorted by createdAt desc.
 */
export async function listRecentVisits(limit = 10): Promise<UserEngagement[]> {
  return dedup('recent_visits', async () => {
    const params: Record<string, string> = {
      engagementType: ENGAGEMENT_TYPE_RECENT_VIEW,
      targetType: TARGET_TYPE_PAGE,
    };
    const result = await get<UserEngagement[]>('/api/user-engagement', params);
    if (!ResultHelper.isSuccess(result) || !result.data) {
      return [];
    }
    return result.data.slice(0, limit);
  });
}

/**
 * Record a recent page visit.
 * Backend handles dedup (upsert by composite key) and pruning (max 20).
 */
export async function recordRecentVisit(visit: {
  path: string;
  title: string;
  modelCode?: string;
  icon?: string;
}): Promise<void> {
  await post<UserEngagement>('/api/user-engagement', {
    targetType: TARGET_TYPE_PAGE,
    targetId: visit.path,
    targetLabel: visit.title,
    targetContext: {
      path: visit.path,
      modelCode: visit.modelCode,
      icon: visit.icon,
    },
    engagementType: ENGAGEMENT_TYPE_RECENT_VIEW,
  });
}
