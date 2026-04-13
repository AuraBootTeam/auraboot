// OSS slot stub — recent-visits tracking lives in ent-dashboard-workbench plugin.

export interface RecentVisit {
  path: string;
  title: string;
  visitedAt?: number;
}

export function useRecentVisits(): { visits: RecentVisit[] } {
  return { visits: [] };
}

export function recordVisit(_visit: RecentVisit): void {
  // no-op in OSS; enterprise overlay persists to localStorage / API.
}
