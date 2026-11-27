import { afterEach, describe, expect, it, vi } from 'vitest';
import { byLastVisitDesc, listRecentVisits, recordRecentVisit, type UserEngagement } from '../engagementService';

const visit = (targetId: string, createdAt: string, updatedAt?: string): UserEngagement => ({
  id: targetId,
  targetType: 'page',
  targetId,
  targetLabel: 'AuraBoot',
  targetContext: { path: targetId },
  engagementType: 'recent_view',
  sortOrder: 0,
  createdAt,
  ...(updatedAt ? { updatedAt } : {}),
});

/** The backend's answer for GET /api/user-engagement, in the order the backend sends it. */
function stubEngagementApi(rows: UserEngagement[]): ReturnType<typeof vi.fn> {
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: '0', data: rows })));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('engagementService', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('records recent visits with sendBeacon so navigation does not abort the request', async () => {
    const beaconMock = vi.fn().mockReturnValue(true);
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: '0' })));
    Object.defineProperty(navigator, 'sendBeacon', {
      configurable: true,
      value: beaconMock,
    });
    vi.stubGlobal('fetch', fetchMock);

    await recordRecentVisit({
      path: '/p/decisionops_tables',
      title: 'Decision Tables',
      modelCode: 'decisionops_tables',
      icon: 'table',
    });

    expect(fetchMock).not.toHaveBeenCalled();
    expect(beaconMock).toHaveBeenCalledWith('/api/user-engagement', expect.any(Blob));
    const blob = beaconMock.mock.calls[0][1] as Blob;
    expect(JSON.parse(await blob.text())).toMatchObject({
      targetType: 'page',
      targetId: '/p/decisionops_tables',
      targetLabel: 'Decision Tables',
      engagementType: 'recent_view',
      targetContext: {
        path: '/p/decisionops_tables',
        modelCode: 'decisionops_tables',
        icon: 'table',
      },
    });
  });

  it('falls back to keepalive fetch when sendBeacon is unavailable', async () => {
    Object.defineProperty(navigator, 'sendBeacon', {
      configurable: true,
      value: undefined,
    });
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: '0' })));
    vi.stubGlobal('fetch', fetchMock);

    await recordRecentVisit({
      path: '/p/decisionops_tables',
      title: 'Decision Tables',
      modelCode: 'decisionops_tables',
      icon: 'table',
    });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/user-engagement',
      expect.objectContaining({
        method: 'POST',
        credentials: 'same-origin',
        keepalive: true,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toMatchObject({
      targetType: 'page',
      targetId: '/p/decisionops_tables',
      targetLabel: 'Decision Tables',
      engagementType: 'recent_view',
      targetContext: {
        path: '/p/decisionops_tables',
        modelCode: 'decisionops_tables',
        icon: 'table',
      },
    });
  });

  it('uses a compact target id for long page paths while preserving the full path in context', async () => {
    const beaconMock = vi.fn().mockReturnValue(true);
    Object.defineProperty(navigator, 'sendBeacon', {
      configurable: true,
      value: beaconMock,
    });

    const longPath = `/p/decisionops_event_policies/view/${'x'.repeat(96)}`;
    await recordRecentVisit({
      path: longPath,
      title: longPath,
      modelCode: 'decisionops_event_policies',
    });

    const blob = beaconMock.mock.calls[0][1] as Blob;
    const body = JSON.parse(await blob.text());
    expect(body.targetId).toMatch(/^page:/);
    expect(body.targetId.length).toBeLessThanOrEqual(64);
    expect(body.targetContext.path).toBe(longPath);
  });

  it('lists page visits by the latest visit, not by the first one', async () => {
    // the backend keeps one record per page in first-visit order and stamps a revisit on updatedAt:
    // /c was opened once just now, /a was opened first but revisited, /b was last opened before that
    const rows = [
      visit('/a', '2026-11-20T01:00:00Z', '2026-11-26T09:00:00Z'),
      visit('/b', '2026-11-20T02:00:00Z', '2026-11-26T08:00:00Z'),
      visit('/c', '2026-11-26T10:00:00Z'),
    ];
    const fetchMock = stubEngagementApi(rows);

    const result = await listRecentVisits(10);

    expect(result.map((row) => row.targetId)).toEqual(['/c', '/a', '/b']);
    // and asks the backend for page visits only
    const url = new URL(String(fetchMock.mock.calls[0][0]), 'http://localhost');
    expect(url.pathname).toBe('/api/user-engagement');
    expect(url.searchParams.get('engagementType')).toBe('recent_view');
    expect(url.searchParams.get('targetType')).toBe('page');
  });

  it('applies the limit after ordering, so the pages shown are the latest ones', async () => {
    stubEngagementApi([
      visit('/a', '2026-11-20T01:00:00Z', '2026-11-26T09:00:00Z'),
      visit('/b', '2026-11-20T02:00:00Z', '2026-11-26T08:00:00Z'),
      visit('/c', '2026-11-26T10:00:00Z'),
    ]);

    expect((await listRecentVisits(2)).map((row) => row.targetId)).toEqual(['/c', '/a']);
  });

  it('two callers with different limits share one request and each get its own limit', async () => {
    const fetchMock = stubEngagementApi([
      visit('/a', '2026-11-26T03:00:00Z'),
      visit('/b', '2026-11-26T02:00:00Z'),
      visit('/c', '2026-11-26T01:00:00Z'),
    ]);

    const [short, long] = await Promise.all([listRecentVisits(1), listRecentVisits(3)]);

    expect(short).toHaveLength(1);
    expect(long).toHaveLength(3);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('a record without dates sorts last and keeps a list sortable', () => {
    const undated = { ...visit('/x', undefined as unknown as string), createdAt: undefined };
    const ordered = [undated, visit('/y', '2026-01-01T00:00:00Z')].sort(byLastVisitDesc);
    expect(ordered.map((row) => row.targetId)).toEqual(['/y', '/x']);
  });
});
