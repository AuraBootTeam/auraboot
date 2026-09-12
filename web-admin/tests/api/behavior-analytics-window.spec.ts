/** Real collect-to-rollup coverage with isolated event-time fixtures. */
import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('behavior rollups share half-open boundaries and deduplicate delivery', async ({
  request,
}) => {
  // A unique millisecond window prevents earlier retained fixture events from satisfying the assertions.
  const start = Date.now() + 365 * 86400000;
  const from = new Date(start).toISOString();
  const to = new Date(start + 1000).toISOString();
  const params = { from, to };
  const before = await request.get('/api/analytics/behavior/overview', { params });
  expect(before.status()).toBe(200);
  expect((await before.json()).data.records[0].totalEvents).toBe(0);
  const session = randomUUID();
  const events = [-1, 0, 500, 1000].map((offset) => ({
    eventId: randomUUID(),
    eventName: 'page_view',
    schemaVersion: '1',
    eventCategory: 'navigation',
    source: 'web',
    clientSessionId: session,
    occurredAt: new Date(start + offset).toISOString(),
  }));
  const collect = await request.post('/api/collect', { data: { events } });
  expect(collect.status()).toBe(200);
  expect((await collect.json()).accepted).toBe(4);
  const duplicate = await request.post('/api/collect', { data: { events } });
  expect(duplicate.status()).toBe(200);
  await expect
    .poll(async () => {
      const response = await request.get('/api/analytics/behavior/overview', { params });
      expect(response.status()).toBe(200);
      return (await response.json()).data.records[0];
    })
    .toEqual({ totalEvents: 2, pageViews: 2, uniqueVisitors: 1, sessions: 1 });
  const top = await request.get('/api/analytics/behavior/top-events', { params });
  expect(top.status()).toBe(200);
  expect((await top.json()).data.records).toEqual([
    { eventName: 'page_view', eventCategory: 'navigation', count: 2 },
  ]);
  const daily = await request.get('/api/analytics/behavior/daily', { params });
  expect(daily.status()).toBe(200);
  expect(await daily.json()).toEqual([
    { day: from.slice(0, 10), totalEvents: 2, pageViews: 2, uniqueVisitors: 1 },
  ]);
});

test('behavior queries reject partial and reversed windows', async ({ request }) => {
  for (const params of [
    { from: '2026-09-01T00:00:00Z' },
    { from: '2026-09-02T00:00:00Z', to: '2026-09-01T00:00:00Z' },
    { from: '2024-01-01T00:00:00Z', to: '2026-01-01T00:00:00Z' },
  ]) {
    const response = await request.get('/api/analytics/behavior/overview', { params });
    expect(response.status()).toBe(400);
  }
});
