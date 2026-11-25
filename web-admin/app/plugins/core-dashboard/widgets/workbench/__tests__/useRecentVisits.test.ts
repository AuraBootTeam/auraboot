/**
 * useRecentVisits: the cache and the mapping between the backend's visit records and the rows the
 * home page shows.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchRecentVisits, getRecentVisits, dedupeVisits, type RecentVisit } from '../useRecentVisits';
import type { UserEngagement } from '~/shared/services/engagementService';

const listRecentVisitsMock = vi.hoisted(() => vi.fn());
vi.mock('~/shared/services/engagementService', () => ({
  listRecentVisits: (...args: unknown[]) => listRecentVisitsMock(...args),
  recordRecentVisit: vi.fn(async () => undefined),
}));

const record = (path: string, createdAt: string, updatedAt?: string): UserEngagement => ({
  id: path,
  targetType: 'page',
  targetId: path,
  targetLabel: 'AuraBoot',
  targetContext: { path },
  engagementType: 'recent_view',
  sortOrder: 0,
  createdAt,
  ...(updatedAt ? { updatedAt } : undefined),
});

describe('useRecentVisits', () => {
  beforeEach(() => {
    listRecentVisitsMock.mockReset();
    localStorage.clear();
  });
  afterEach(() => localStorage.clear());

  it('stamps a row with the latest visit, not with the first one', async () => {
    listRecentVisitsMock.mockResolvedValue([record('/p/x', '2026-11-20T01:00:00Z', '2026-11-26T09:00:00Z')]);

    const visits = await fetchRecentVisits(8);

    expect(visits[0].visitedAt).toBe('2026-11-26T09:00:00Z');
  });

  it('falls back to the first visit when a page was never revisited', async () => {
    listRecentVisitsMock.mockResolvedValue([record('/p/x', '2026-11-26T01:00:00Z')]);

    expect((await fetchRecentVisits(8))[0].visitedAt).toBe('2026-11-26T01:00:00Z');
  });

  it('shows one row per page when the backend holds two records for it', async () => {
    listRecentVisitsMock.mockResolvedValue([
      record('/p/x', '2026-11-20T01:00:00Z', '2026-11-26T09:00:00Z'),
      record('/p/x', '2026-11-21T01:00:00Z', '2026-11-26T08:00:00Z'),
      record('/p/y', '2026-11-26T07:00:00Z'),
    ]);

    const visits = await fetchRecentVisits(8);

    expect(visits.map((visit) => visit.path)).toEqual(['/p/x', '/p/y']);
    // and the cache that feeds the first paint holds the same rows
    expect(getRecentVisits(8).map((visit) => visit.path)).toEqual(['/p/x', '/p/y']);
  });

  it('dedupeVisits keeps the first occurrence and its time', () => {
    const visits: RecentVisit[] = [
      { title: 'A', path: '/a', visitedAt: '2026-11-26T09:00:00Z' },
      { title: 'B', path: '/b', visitedAt: '2026-11-26T08:00:00Z' },
      { title: 'A again', path: '/a', visitedAt: '2026-11-26T07:00:00Z' },
    ];

    expect(dedupeVisits(visits)).toEqual([visits[0], visits[1]]);
  });
});
