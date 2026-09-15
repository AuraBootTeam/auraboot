/** Metric denominator semantics: identity/session counting, UTC day bucketing, fallback, and self-describing definitions. */
import { test, expect, request as requestFactory, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

const BEHAVIOR = '/api/analytics/behavior';

const collect = (ctx: APIRequestContext, events: object[]) =>
  ctx.post('/api/collect', { data: { events } });

const overview = (ctx: APIRequestContext, from: string, to: string) =>
  ctx.get(`${BEHAVIOR}/overview`, { params: { from, to } });

test('overview counts identities, sessions and page views with exact denominators', async ({
  request,
}) => {
  const peers: APIRequestContext[] = [];
  try {
    for (let index = 0; index < 2; index++) {
      const email = `denominator-${randomUUID()}@e2e.local`;
      const password = `Aa7!${randomUUID()}`;
      const created = await request.post('/api/admin/users', {
        data: {
          email,
          displayName: `Denominator peer ${index}`,
          initialPassword: password,
          roleCodes: ['tenant_admin'],
          sendInviteEmail: false,
        },
      });
      expect(created.status(), await created.text()).toBe(200);
      const login = await request.post('/api/auth/login', { data: { email, password } });
      expect(login.status(), await login.text()).toBe(200);
      peers.push(
        await requestFactory.newContext({
          baseURL: process.env.BACKEND_URL,
          extraHTTPHeaders: { Authorization: `Bearer ${(await login.json()).data.jwt}` },
        }),
      );
    }
    // A unique future second keeps late-arriving login/server events out of the window.
    const stamp = Date.now() + 120_000;
    const from = new Date(stamp - 1_000).toISOString();
    const to = new Date(stamp + 1_000).toISOString();
    const before = await overview(request, from, to);
    expect(before.status()).toBe(200);
    expect((await before.json()).data.records[0]).toEqual({
      totalEvents: 0, pageViews: 0, uniqueVisitors: 0, sessions: 0,
    });

    const now = new Date(stamp).toISOString();
    const base = { schemaVersion: '1', source: 'web', occurredAt: now };
    // Admin: two sessions (SA with an ignored anonId — authenticated user identity wins — and SB).
    const adminEvents = [
      { ...base, eventId: randomUUID(), eventName: 'page_view', eventCategory: 'navigation', clientSessionId: 'denom-SA', anonId: 'denom-anon-ignored' },
      { ...base, eventId: randomUUID(), eventName: 'element_click', eventCategory: 'ui_interaction', clientSessionId: 'denom-SA' },
      { ...base, eventId: randomUUID(), eventName: 'page_view', eventCategory: 'navigation', clientSessionId: 'denom-SB' },
    ];
    // Peers share one client session id: sessions are identity×session pairs, so it counts twice.
    const shared = { ...base, eventName: 'page_view', eventCategory: 'navigation', clientSessionId: 'denom-SHARED' };
    for (const ctx of [request, ...peers]) {
      const events = ctx === request ? adminEvents : [{ ...shared, eventId: randomUUID() }];
      const accepted = await collect(ctx, events);
      expect(accepted.status(), await accepted.text()).toBe(200);
      expect((await accepted.json()).accepted).toBe(events.length);
    }

    await expect
      .poll(async () => {
        const response = await overview(request, from, to);
        expect(response.status()).toBe(200);
        return (await response.json()).data.records[0];
      })
      .toEqual({
        totalEvents: 5,
        pageViews: 4,
        uniqueVisitors: 3,
        sessions: 4,
      });
  } finally {
    for (const peer of peers) await peer.dispose();
  }
});

test('daily buckets split on UTC day boundaries without zero-filling gaps', async ({ request }) => {
  // Random day base keeps reruns on untouched UTC days (existing suites use +300/+363..365).
  const dayBase = 210 + (parseInt(randomUUID().slice(0, 4), 16) % 80);
  const day = (offset: number) => new Date(Date.now() + (dayBase + offset) * 86_400_000).toISOString().slice(0, 10);
  const at = (offset: number, time: string) => `${day(offset)}T${time}Z`;
  const events = [
    { eventId: randomUUID(), eventName: 'page_view', eventCategory: 'navigation', schemaVersion: '1', source: 'web', clientSessionId: 'denom-daily', occurredAt: at(200, '23:59:59.999') },
    { eventId: randomUUID(), eventName: 'page_view', eventCategory: 'navigation', schemaVersion: '1', source: 'web', clientSessionId: 'denom-daily', occurredAt: at(201, '00:00:00.000') },
    { eventId: randomUUID(), eventName: 'element_click', eventCategory: 'ui_interaction', schemaVersion: '1', source: 'web', clientSessionId: 'denom-daily', occurredAt: at(201, '12:00:00.000') },
    { eventId: randomUUID(), eventName: 'page_view', eventCategory: 'navigation', schemaVersion: '1', source: 'web', clientSessionId: 'denom-daily', occurredAt: at(202, '12:00:00.000') },
  ];
  const accepted = await collect(request, events);
  expect(accepted.status(), await accepted.text()).toBe(200);
  const from = `${day(200)}T00:00:00.000Z`;
  const to = `${day(203)}T00:00:00.000Z`;
  await expect
    .poll(async () => {
      const response = await request.get(`${BEHAVIOR}/daily`, { params: { from, to } });
      expect(response.status()).toBe(200);
      return await response.json();
    })
    .toEqual([
      { day: day(200), totalEvents: 1, pageViews: 1, uniqueVisitors: 1 },
      { day: day(201), totalEvents: 2, pageViews: 1, uniqueVisitors: 1 },
      { day: day(202), totalEvents: 1, pageViews: 1, uniqueVisitors: 1 },
    ]);
});

test('events without occurredAt fall back to ingestion time', async ({ request }) => {
  const before = new Date(Date.now() - 5_000).toISOString();
  const after = new Date(Date.now() + 60_000).toISOString();
  const baseline = await overview(request, before, after);
  expect(baseline.status()).toBe(200);
  const base = (await baseline.json()).data.records[0];
  const accepted = await collect(request, [
    { eventId: randomUUID(), eventName: 'page_view', eventCategory: 'navigation', schemaVersion: '1', source: 'web', clientSessionId: 'denom-fallback' },
  ]);
  expect(accepted.status(), await accepted.text()).toBe(200);
  await expect
    .poll(async () => {
      const response = await overview(request, before, after);
      expect(response.status()).toBe(200);
      return (await response.json()).data.records[0];
    })
    .toEqual({
      totalEvents: base.totalEvents + 1,
      pageViews: base.pageViews + 1,
      uniqueVisitors: base.uniqueVisitors + 1,
      sessions: base.sessions + 1,
    });
});

test('metric responses self-describe their definitions and cutoffs', async ({ request }) => {
  const from = new Date(Date.now() - 1_000).toISOString();
  const to = new Date(Date.now()).toISOString();
  const started = Date.now();

  const funnel = await request.get(`${BEHAVIOR}/analysis-funnel`, { params: { from, to } });
  expect(funnel.status()).toBe(200);
  const funnelBody = await funnel.json();
  expect(funnelBody.data.definitionVersion).toBe('analysis-task-funnel-v2');
  expect(funnelBody.data.unit).toBe('analysis_task');
  expect(funnelBody.data.cohortRule).toBe('initiator_first_entry_in_window');
  expect(funnelBody.data.timezone).toBe('UTC');
  expect(funnelBody.data.quality.completeness).toBe('observed_events_not_delivery_guaranteed');
  expect(Object.keys(funnelBody.data.quality).sort()).toEqual(
    ['completeness', 'missingCorrelationEvents', 'sampledEvents', 'unmatchedStageEvents', 'withoutWindowEntryTasks'],
  );
  expect(new Date(funnelBody.data.dataCutoff).getTime()).toBeGreaterThanOrEqual(started - 2_000);

  const retention = await request.get(`${BEHAVIOR}/retention`, { params: { from, to, unit: 'user' } });
  expect(retention.status()).toBe(200);
  const retentionBody = await retention.json();
  expect(retentionBody.data.definitionVersion).toBe('analytics-first-use-retention-v1');
  expect(retentionBody.data.unit).toBe('user');
  expect(retentionBody.data.cohortRule).toBe('first_successful_dashboard_use');
  expect(retentionBody.data.timezone).toBe('UTC');
  expect(new Date(retentionBody.data.dataCutoff).getTime()).toBeGreaterThanOrEqual(started - 2_000);

  const executions = await request.get(`${BEHAVIOR}/executions`, { params: { from, to } });
  expect(executions.status()).toBe(200);
  const executionBody = await executions.json();
  expect(executionBody.data.definitionVersion).toBe('agent-execution-cohort-v1');
  expect(executionBody.data.cohortRule).toBe('first_start_in_window');
  expect(executionBody.data.timezone).toBe('UTC');

  const invalidUnit = await request.get(`${BEHAVIOR}/retention`, {
    params: { from, to, unit: 'session' },
  });
  expect(invalidUnit.status()).toBe(400);
});
