/** Real seed/collect/read APIs. No direct SQL writes or forged tokens. */
import { test, expect, request as requestFactory } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('behavior rollups isolate populated tenants even with colliding event and session IDs', async ({
  request,
}, testInfo) => {
  test.setTimeout(120000);
  const seed = await request.post('/api/test/seed', {
    timeout: 90000,
    params: { testRunId: `analytics-tenant-${randomUUID()}` },
  });
  expect(seed.status()).toBe(200);
  const identity = await seed.json();
  expect(identity.jwt).toBeTruthy();
  const other = await requestFactory.newContext({
    baseURL: process.env.BACKEND_URL,
    extraHTTPHeaders: { Authorization: `Bearer ${identity.jwt}` },
  });
  try {
    const currentMe = await request.get('/api/auth/me');
    const otherMe = await other.get('/api/auth/me');
    expect(currentMe.status()).toBe(200);
    expect(otherMe.status()).toBe(200);
    // The seed tenant has its own authenticated administrator; compare token-scoped tenants without logging tokens.
    const currentSpaces = await request.get('/api/tenant-selection/my-spaces');
    expect(currentSpaces.status()).toBe(200);
    const spaces = (await currentSpaces.json()).data;
    expect(spaces.some((space: any) => String(space.tenantId) === String(identity.tenantId))).toBe(
      false,
    );
    const start = Date.now() + 300 * 86400000;
    const params = {
      from: new Date(start).toISOString(),
      to: new Date(start + 1000).toISOString(),
    };
    const session = randomUUID();
    const shared = {
      eventId: randomUUID(),
      eventName: 'page_view',
      schemaVersion: '1',
      eventCategory: 'navigation',
      source: 'web',
      clientSessionId: session,
      occurredAt: new Date(start + 1).toISOString(),
    };
    const inputs = [
      [shared],
      [shared, { ...shared, eventId: randomUUID(), occurredAt: new Date(start + 2).toISOString() }],
    ];
    const clients = [request, other];
    for (const [index, client] of clients.entries()) {
      const before = await client.get('/api/analytics/behavior/overview', { params });
      expect(before.status()).toBe(200);
      expect((await before.json()).data.records[0].totalEvents).toBe(0);
      const collected = await client.post('/api/collect', { data: { events: inputs[index] } });
      expect(collected.status()).toBe(200);
      expect((await collected.json()).accepted).toBe(index + 1);
    }
    const evidence = [];
    for (const [index, client] of clients.entries()) {
      await expect
        .poll(async () => {
          const response = await client.get('/api/analytics/behavior/overview', { params });
          expect(response.status()).toBe(200);
          return (await response.json()).data.records[0];
        })
        .toEqual({ totalEvents: index + 1, pageViews: index + 1, uniqueVisitors: 1, sessions: 1 });
      const top = await client.get('/api/analytics/behavior/top-events', { params });
      expect(top.status()).toBe(200);
      const topRows = (await top.json()).data.records;
      expect(topRows).toEqual([
        { eventName: 'page_view', eventCategory: 'navigation', count: index + 1 },
      ]);
      const daily = await client.get('/api/analytics/behavior/daily', { params });
      expect(daily.status()).toBe(200);
      expect(await daily.json()).toEqual([
        {
          day: params.from.slice(0, 10),
          totalEvents: index + 1,
          pageViews: index + 1,
          uniqueVisitors: 1,
        },
      ]);
      evidence.push({ tenant: index === 0 ? 'A' : 'B', events: index + 1, topRows });
    }
    // A client-supplied tenantId must not replace the authenticated tenant.
    const forged = await request.get('/api/analytics/behavior/overview', {
      params: { ...params, tenantId: String(identity.tenantId) },
    });
    expect(forged.status()).toBe(200);
    expect((await forged.json()).data.records[0].totalEvents).toBe(1);
    const analysisFrom = new Date().toISOString();
    const title = `Tenant analysis ${randomUUID()}`;
    const order = await request.post('/api/dynamic/e2et_order/create', {
      data: {
        e2et_order_title: title,
        e2et_order_type: 'normal',
        e2et_order_urgent: false,
        e2et_order_status: 'draft',
      },
    });
    expect(order.status()).toBe(200);
    const stream = await request.post('/api/ai/aurabot/chat/stream', {
      timeout: 30000,
      headers: { Accept: 'text/event-stream' },
      data: {
        sessionId: randomUUID(),
        clientMsgId: randomUUID(),
        message:
          '@@AURABOOT_STUB_TOOL_USE@@ ' +
          JSON.stringify({
            name: 'aurabot_chat-bi',
            input: {
              modelCode: 'e2et_order',
              chartType: 'table',
              dimensions: ['e2et_order_title'],
              metrics: [{ field: 'pid', aggregation: 'count', alias: 'cnt' }],
              filters: [{ field: 'e2et_order_title', operator: 'eq', value: title }],
              limit: 5,
            },
          }),
      },
    });
    expect(stream.status()).toBe(200);
    const contracts = (await stream.text())
      .split(/\r?\n\r?\n/)
      .filter((block) =>
        block
          .split(/\r?\n/)
          .some((line) => line.startsWith('event:') && line.slice(6).trim() === 'result_contract'),
      )
      .map((block) => {
        const raw = block
          .split(/\r?\n/)
          .filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).trim())
          .join('\n');
        const decoded = JSON.parse(raw);
        return typeof decoded === 'string' ? JSON.parse(decoded) : decoded;
      });
    const analysis = contracts.find((contract) => contract?.data?.data?.analysisId)?.data.data;
    expect(analysis?.analysisId).toBeTruthy();
    expect(analysis.records).toEqual([{ cnt: 1, e2et_order_title: title }]);
    const readFunnel = async (client: typeof request) => {
      const response = await client.get('/api/analytics/behavior/analysis-funnel', {
        params: { from: analysisFrom, to: new Date().toISOString() },
      });
      expect(response.status()).toBe(200);
      return (await response.json()).data.records.map((r: any) => r.tasks);
    };
    await expect.poll(() => readFunnel(request)).toEqual([1, 1, 0, 0, 0]);
    expect(await readFunnel(other)).toEqual([0, 0, 0, 0, 0]);
    const foreignView = await other.post(`/api/analytics/results/${analysis.analysisId}/view`, {
      data: {},
    });
    expect(foreignView.status()).toBe(400);
    const viewed = await request.post(`/api/analytics/results/${analysis.analysisId}/view`, {
      data: {},
    });
    expect(viewed.status()).toBe(200);
    await expect.poll(() => readFunnel(request)).toEqual([1, 1, 1, 0, 0]);
    const saveBody = {
      title,
      scope: 'personal',
      code: `tenant_${randomUUID().replaceAll('-', '')}`,
      sourceAnalysisId: analysis.analysisId,
      layoutConfig: { cols: 12, rowHeight: 80 },
      widgets: [
        {
          id: 'tenant_widget',
          type: 'smart-table-chart',
          x: 0,
          y: 0,
          w: 6,
          h: 4,
          config: { title: 'Tenant analysis', dataSource: analysis.dataSource },
        },
      ],
    };
    const foreignSave = await other.post('/api/dashboards', { data: saveBody });
    expect(foreignSave.status()).toBe(400);
    expect(await foreignSave.text()).toContain('Analysis is unavailable');
    const saved = await request.post('/api/dashboards', { data: saveBody });
    expect(saved.status(), await saved.text()).toBe(200);
    const dashboard = (await saved.json()).data;
    await expect.poll(() => readFunnel(request)).toEqual([1, 1, 1, 1, 0]);
    const foreignUse = await other.post(
      `/api/dashboards/${dashboard.pid}/widgets/tenant_widget/data`,
      { data: { usageId: randomUUID() } },
    );
    expect(foreignUse.status()).toBe(404);
    const used = await request.post(`/api/dashboards/${dashboard.pid}/widgets/tenant_widget/data`, {
      data: { usageId: randomUUID() },
    });
    expect(used.status()).toBe(200);
    expect((await used.json()).data.rows).toEqual([{ cnt: 1, e2et_order_title: title }]);
    await expect.poll(() => readFunnel(request)).toEqual([1, 1, 1, 1, 1]);
    expect(await readFunnel(other)).toEqual([0, 0, 0, 0, 0]);
    const retentionWindow = { from: analysisFrom, to: new Date().toISOString(), unit: 'artifact' };
    const ownRetention = await request.get('/api/analytics/behavior/retention', {
      params: retentionWindow,
    });
    const otherRetention = await other.get('/api/analytics/behavior/retention', {
      params: retentionWindow,
    });
    expect(ownRetention.status()).toBe(200);
    expect(otherRetention.status()).toBe(200);
    const ownPoints = (await ownRetention.json()).data.records;
    expect(ownPoints).toHaveLength(3);
    expect(ownPoints.map((p: any) => p.cohortSize)).toEqual([1, 1, 1]);
    expect((await otherRetention.json()).data.records).toEqual([]);
    await testInfo.attach('tenant-analysis-journey', {
      body: JSON.stringify({
        ownFunnel: [1, 1, 1, 1, 1],
        foreignFunnel: await readFunnel(other),
        foreignView: foreignView.status(),
        foreignSave: foreignSave.status(),
        foreignUse: foreignUse.status(),
        ownPoints,
        foreignPoints: [],
      }),
      contentType: 'application/json',
    });
    await testInfo.attach('populated-tenant-rollups', {
      body: JSON.stringify({ window: params, evidence }),
      contentType: 'application/json',
    });
  } finally {
    await other.dispose();
  }
});
