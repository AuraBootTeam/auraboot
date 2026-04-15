/**
 * Hook to fetch workbench stats from GET /api/workbench/stats.
 * Concurrent requests with the same keys share a single in-flight promise.
 */

import { useState, useEffect, useCallback } from 'react';
import { get } from '~/shared/services/http-client';
import type { WorkbenchStats } from './workbench-types';

interface UseWorkbenchStatsOptions {
  keys?: string[];
}

interface UseWorkbenchStatsResult {
  stats: WorkbenchStats;
  loading: boolean;
  refresh: () => void;
}

// Module-level promise dedup — same keys = share one request
const inflight = new Map<string, Promise<WorkbenchStats>>();

async function fetchStatsDeduped(keys?: string[]): Promise<WorkbenchStats> {
  const cacheKey = keys?.length ? keys.slice().sort().join(',') : '__all__';
  const existing = inflight.get(cacheKey);
  if (existing) return existing;

  const promise = (async () => {
    const params: Record<string, string> = {};
    if (keys?.length) {
      params.keys = keys.join(',');
    }
    const result = await get<WorkbenchStats>('/api/workbench/stats', params);
    if (result.code === '0' && result.data) {
      // API may wrap stats in { stats: {...} } or return flat — handle both
      const raw = result.data as Record<string, unknown>;
      if (
        raw.stats &&
        typeof raw.stats === 'object' &&
        !('value' in raw.stats)
      ) {
        return raw.stats as WorkbenchStats;
      }
      return result.data;
    }
    return {};
  })().finally(() => {
    inflight.delete(cacheKey);
  });

  inflight.set(cacheKey, promise);
  return promise;
}

export function useWorkbenchStats(options?: UseWorkbenchStatsOptions): UseWorkbenchStatsResult {
  const [stats, setStats] = useState<WorkbenchStats>({});
  const [loading, setLoading] = useState(true);

  const fetchStats = useCallback(async () => {
    setLoading(true);
    try {
      const nextStats = await fetchStatsDeduped(options?.keys);
      setStats(nextStats);
    } catch {
      setStats({});
    } finally {
      setLoading(false);
    }
  }, [options?.keys?.join(',')]);

  useEffect(() => {
    fetchStats();
  }, [fetchStats]);

  return { stats, loading, refresh: fetchStats };
}
