/**
 * User Engagement Service
 *
 * Frontend service for the /api/user-engagement API.
 * Supports favorites, recent views, and pinned items.
 */

import { get, post, put, del } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

export interface UserEngagement {
  id: string;
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
  /** When the record was created — for a page visit, the first visit. */
  createdAt?: string;
  /** When the record was last touched — for a page visit, the latest visit. */
  updatedAt?: string;
}

const ENGAGEMENT_TYPE_FAVORITE = 'favorite';
const ENGAGEMENT_TYPE_RECENT_VIEW = 'recent_view';
const TARGET_TYPE_PAGE = 'page';
const MAX_TARGET_ID_LENGTH = 64;

function hashString(value: string): string {
  let hash = 2166136261;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0).toString(36);
}

function recentVisitTargetId(path: string): string {
  if (path.length <= MAX_TARGET_ID_LENGTH) {
    return path;
  }
  return `page:${hashString(path)}`;
}

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
export async function removeFavorite(id: string): Promise<void> {
  const result = await del<void>(`/api/user-engagement/${id}`);
  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.desc || 'Failed to remove favorite');
  }
}

/**
 * Reorder favorites by providing an ordered list of IDs.
 */
export async function reorderFavorites(orderedIds: string[]): Promise<void> {
  const result = await put<void>('/api/user-engagement/reorder', { orderedIds });
  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.desc || 'Failed to reorder favorites');
  }
}

/**
 * When a page was last visited, as a timestamp (0 when neither date parses). A visit record keeps
 * the first visit as `createdAt` and stamps every later visit on `updatedAt`.
 */
export function lastVisitedAt(engagement: UserEngagement): number {
  const latest = Date.parse(engagement.updatedAt ?? '');
  if (!Number.isNaN(latest)) return latest;
  const first = Date.parse(engagement.createdAt ?? '');
  return Number.isNaN(first) ? 0 : first;
}

/** Most recently visited first; records with the same time keep the order they came in. */
export function byLastVisitDesc(a: UserEngagement, b: UserEngagement): number {
  return lastVisitedAt(b) - lastVisitedAt(a);
}

/**
 * List recent page visits for the current user, most recently visited first.
 *
 * The backend returns its rows in its own order (first-visit order), so the order the list stands
 * for is made here, before the limit is applied — otherwise "the latest 8" are the 8 visited first.
 * The request itself is shared, the limit is the caller's.
 */
export async function listRecentVisits(limit = 10): Promise<UserEngagement[]> {
  const sorted = dedup('recent_visits', async () => {
    const params: Record<string, string> = {
      engagementType: ENGAGEMENT_TYPE_RECENT_VIEW,
      targetType: TARGET_TYPE_PAGE,
    };
    const result = await get<UserEngagement[]>('/api/user-engagement', params);
    if (!ResultHelper.isSuccess(result) || !result.data) {
      return [] as UserEngagement[];
    }
    return [...result.data].sort(byLastVisitDesc);
  });
  return (await sorted).slice(0, limit);
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
  const payload = {
    targetType: TARGET_TYPE_PAGE,
    targetId: recentVisitTargetId(visit.path),
    targetLabel: visit.title,
    targetContext: {
      path: visit.path,
      modelCode: visit.modelCode,
      icon: visit.icon,
    },
    engagementType: ENGAGEMENT_TYPE_RECENT_VIEW,
  };

  if (typeof window !== 'undefined' && typeof fetch === 'function') {
    if (typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
      const blob = new Blob([JSON.stringify(payload)], { type: 'application/json' });
      if (navigator.sendBeacon('/api/user-engagement', blob)) {
        return;
      }
    }

    await fetch('/api/user-engagement', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      credentials: 'same-origin',
      keepalive: true,
    });
    return;
  }

  await post<UserEngagement>('/api/user-engagement', payload);
}
