import { afterEach, describe, expect, it, vi } from 'vitest';
import { recordRecentVisit } from '../engagementService';

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
});
