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
  const invalidWindows: Record<string, string>[] = [
    { from: '2026-09-01T00:00:00Z' },
    { from: '2026-09-02T00:00:00Z', to: '2026-09-01T00:00:00Z' },
    { from: '2024-01-01T00:00:00Z', to: '2026-01-01T00:00:00Z' },
  ];
  for (const params of invalidWindows) {
    const response = await request.get('/api/analytics/behavior/overview', { params });
    expect(response.status()).toBe(400);
  }
});

test('client collect cannot forge analytics query success', async ({ request }) => {
  const response = await request.post('/api/collect', {
    data: {
      events: [
        {
          eventId: randomUUID(),
          eventName: 'analytics_query_succeeded',
          source: 'server',
          occurredAt: new Date().toISOString(),
        },
      ],
    },
  });
  expect(response.status()).toBe(400);
});

test('client collect rejects forged server provenance without accepting part of a batch', async ({
  request,
}) => {
  const start = Date.now() + 364 * 86400000;
  const params = { from: new Date(start).toISOString(), to: new Date(start + 1).toISOString() };
  const client = {
    eventName: 'page_view',
    eventCategory: 'navigation',
    source: 'web',
    schemaVersion: '1',
    occurredAt: params.from,
  };
  for (const forged of [
    { source: 'server' },
    { eventCategory: 'business_outcome' },
    { producerName: 'server-outcome-outbox' },
    { producerName: 'aurabot-analytics' },
  ]) {
    const response = await request.post('/api/collect', {
      data: {
        events: [
          { ...client, eventId: randomUUID() },
          { ...client, ...forged, eventId: randomUUID() },
        ],
      },
    });
    expect(response.status(), JSON.stringify(forged)).toBe(400);
  }
  const accepted = await request.post('/api/collect', {
    data: { events: [{ ...client, eventId: randomUUID() }] },
  });
  expect(accepted.status()).toBe(200);
  expect((await accepted.json()).accepted).toBe(1);
  await expect
    .poll(async () => {
      const response = await request.get('/api/analytics/behavior/overview', { params });
      expect(response.status()).toBe(200);
      return (await response.json()).data.records[0].totalEvents;
    })
    .toBe(1);
});

test('anonymous site-key collect rejects server provenance and records a genuine anonymous visitor', async ({
  request,
  playwright,
}) => {
  const name = `Provenance ${randomUUID()}`;
  const created = await request.post('/api/meta/commands/execute/behavior_site_key:create', {
    data: { payload: { name } },
  });
  expect(created.status()).toBe(200);
  expect((await created.json()).code).toBe('0');
  const listed = await request.get('/api/dynamic/behavior_site_key/list', {
    params: {
      pageNum: '1',
      pageSize: '10',
      filters: JSON.stringify([{ fieldName: 'name', operator: 'EQ', value: name }]),
    },
  });
  expect(listed.status()).toBe(200);
  const rows = (await listed.json()).data.records;
  expect(rows).toHaveLength(1);
  const siteKey = rows[0].site_key;
  expect(siteKey).toMatch(/^abk_/);
  const anonymous = await playwright.request.newContext({
    baseURL: process.env.BACKEND_URL,
    storageState: { cookies: [], origins: [] },
  });
  try {
    const start = Date.now() + 363 * 86400000;
    const params = { from: new Date(start).toISOString(), to: new Date(start + 1).toISOString() };
    const client = {
      eventName: 'page_view',
      eventCategory: 'navigation',
      source: 'web',
      schemaVersion: '1',
      anonId: randomUUID(),
      clientSessionId: randomUUID(),
      occurredAt: params.from,
    };
    const headers = { 'X-Site-Key': siteKey, Origin: 'https://customer-app.example.com' };
    for (const forged of [
      { source: 'server' },
      { eventCategory: 'business_outcome' },
      { producerName: 'server-outcome-outbox' },
      { producerName: 'aurabot-analytics' },
    ]) {
      const response = await anonymous.post('/api/collect/keyed', {
        headers,
        data: {
          events: [
            { ...client, eventId: randomUUID() },
            { ...client, ...forged, eventId: randomUUID() },
          ],
        },
      });
      expect(response.status(), JSON.stringify(forged)).toBe(400);
    }
    const accepted = await anonymous.post('/api/collect/keyed', {
      headers,
      data: { events: [{ ...client, eventId: randomUUID() }] },
    });
    expect(accepted.status()).toBe(200);
    expect((await accepted.json()).accepted).toBe(1);
    await expect
      .poll(async () => {
        const response = await request.get('/api/analytics/behavior/overview', { params });
        expect(response.status()).toBe(200);
        return (await response.json()).data.records[0];
      })
      .toEqual({ totalEvents: 1, pageViews: 1, uniqueVisitors: 1, sessions: 1 });
    const unknown = await anonymous.post('/api/collect/keyed', {
      headers: { ...headers, 'X-Site-Key': `abk_${randomUUID().replaceAll('-', '')}` },
      data: { events: [{ ...client, eventId: randomUUID() }] },
    });
    expect(unknown.status()).toBe(403);
  } finally {
    await anonymous.dispose();
  }
});
