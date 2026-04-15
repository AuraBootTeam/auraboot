/**
 * Recent visits tracking — synced to backend engagement API with localStorage as instant cache.
 *
 * - recordVisit(): writes localStorage immediately (optimistic), then fires API call in background
 * - getRecentVisits(): returns localStorage data synchronously (for initial render)
 * - fetchRecentVisits(): async version that fetches from API and updates localStorage cache
 */

import {
  listRecentVisits,
  recordRecentVisit,
  type UserEngagement,
} from '~/shared/services/engagementService';

const STORAGE_KEY = 'auraboot:recent-visits';
const MAX_ITEMS = 20;

export interface RecentVisit {
  title: string;
  path: string;
  modelCode?: string;
  icon?: string;
  visitedAt: string;
}

/**
 * Read recent visits from localStorage (synchronous, for instant display).
 */
export function getRecentVisits(limit = 10): RecentVisit[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const items: RecentVisit[] = JSON.parse(raw);
    return items.slice(0, limit);
  } catch {
    return [];
  }
}

/**
 * Fetch recent visits from the backend API and update localStorage cache.
 * Returns the API data mapped to RecentVisit[].
 */
export async function fetchRecentVisits(limit = 10): Promise<RecentVisit[]> {
  const engagements = await listRecentVisits(limit);
  const visits = engagements.map(mapEngagementToVisit);

  // Update localStorage cache with API data
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(visits));
  } catch {
    // localStorage unavailable — ignore
  }

  return visits;
}

/**
 * Record a page visit. Writes to localStorage immediately (optimistic),
 * then fires a background API call for cross-device sync.
 */
export function recordVisit(visit: Omit<RecentVisit, 'visitedAt'>) {
  // Optimistic localStorage update
  try {
    const existing = getRecentVisits(MAX_ITEMS);
    const filtered = existing.filter((v) => v.path !== visit.path);
    const updated: RecentVisit[] = [
      { ...visit, visitedAt: new Date().toISOString() },
      ...filtered,
    ].slice(0, MAX_ITEMS);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(updated));
  } catch {
    // localStorage unavailable — ignore
  }

  // Fire-and-forget API call
  recordRecentVisit({
    path: visit.path,
    title: visit.title,
    modelCode: visit.modelCode,
    icon: visit.icon,
  }).catch(() => {
    // Non-blocking — API failure does not affect UX
  });
}

function mapEngagementToVisit(e: UserEngagement): RecentVisit {
  return {
    title: e.targetLabel,
    path: e.targetContext?.path || e.targetId,
    modelCode: e.targetContext?.modelCode,
    icon: e.targetContext?.icon,
    visitedAt: e.createdAt || new Date().toISOString(),
  };
}
