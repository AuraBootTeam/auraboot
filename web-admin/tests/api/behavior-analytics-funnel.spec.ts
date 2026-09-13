/** Real conversation/query/save producers; no direct event-store fixture writes. */
import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('ordered analysis funnel counts 10 to 8 to 6 to 4 to 2 tasks', async ({ request }) => {
  test.setTimeout(180000);
  const from = new Date().toISOString();
  const title = `Funnel ${randomUUID()}`;
  const fixture = await request.post('/api/dynamic/e2et_order/create', {
    data: {
      e2et_order_title: title,
      e2et_order_type: 'normal',
      e2et_order_urgent: false,
      e2et_order_status: 'draft',
    },
  });
  expect(fixture.status()).toBe(200);
  const analyses: { analysisId: string; dataSource: Record<string, unknown> }[] = [];
  for (let index = 0; index < 10; index++) {
    const response = await request.post('/api/ai/aurabot/chat/stream', {
      headers: { Accept: 'text/event-stream' },
      data: {
        sessionId: `funnel-${randomUUID()}`,
        clientMsgId: randomUUID(),
        message:
          '@@AURABOOT_STUB_TOOL_USE@@ ' +
          JSON.stringify({
            name: 'aurabot_chat-bi',
            input: {
              modelCode: 'e2et_order',
              chartType: 'table',
              dimensions: ['e2et_order_title'],
              metrics: [
                {
                  field: index < 8 ? 'pid' : 'missing_funnel_field',
                  aggregation: 'count',
                  alias: 'cnt',
                },
              ],
              filters: [{ field: 'e2et_order_title', operator: 'eq', value: title }],
              limit: 5,
            },
          }),
      },
    });
    expect(response.status()).toBe(200);
    const events = (await response.text())
      .split(/\r?\n\r?\n/)
      .filter(Boolean)
      .map((block) => {
        const lines = block.split(/\r?\n/);
        const event = lines
          .find((line) => line.startsWith('event:'))
          ?.slice(6)
          .trim();
        const raw = lines
          .filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).trim())
          .join('\n');
        if (!raw) return { event, data: null };
        const decoded = JSON.parse(raw);
        return { event, data: typeof decoded === 'string' ? JSON.parse(decoded) : decoded };
      });
    const contract = events.find(
      (event) => event.event === 'result_contract' && event.data?.data?.data?.analysisId,
    );
    if (index < 8) {
      expect(
        contract,
        JSON.stringify(events.filter((event) => event.event === 'result_contract')),
      ).toBeDefined();
      expect(contract!.data.data.data.records).toEqual([{ cnt: 1, e2et_order_title: title }]);
      analyses.push(contract!.data.data.data);
    } else {
      expect(contract).toBeUndefined();
    }
  }
  const afterQueries = new Date().toISOString();
  const read = async (start = from, end = new Date().toISOString()) => {
    const response = await request.get('/api/analytics/behavior/analysis-funnel', {
      params: { from: start, to: end },
    });
    expect(response.status()).toBe(200);
    return (await response.json()).data;
  };
  await expect
    .poll(async () => (await read()).records.map((stage: { tasks: number }) => stage.tasks))
    .toEqual([10, 8, 0, 0, 0]);
  for (let index = 0; index < 6; index++) {
    const analysis = analyses[index];
    for (let delivery = 0; delivery < 2; delivery++) {
      const viewed = await request.post(`/api/analytics/results/${analysis.analysisId}/view`, {
        data: {},
      });
      expect(viewed.status()).toBe(200);
    }
    if (index >= 4) continue;
    const savedResponse = await request.post('/api/dashboards', {
      data: {
        title: `Funnel ${index}`,
        scope: 'personal',
        sourceAnalysisId: analysis.analysisId,
        widgets: [
          {
            id: 'funnel_widget',
            type: 'smart-table-chart',
            x: 0,
            y: 0,
            w: 6,
            h: 4,
            config: { title: 'Funnel', dataSource: analysis.dataSource },
          },
        ],
      },
    });
    expect(savedResponse.status()).toBe(200);
    const saved = (await savedResponse.json()).data;
    if (index >= 2) continue;
    const usageId = randomUUID();
    for (let delivery = 0; delivery < 2; delivery++) {
      const used = await request.post(`/api/dashboards/${saved.pid}/widgets/funnel_widget/data`, {
        data: { usageId },
      });
      expect(used.status()).toBe(200);
      expect((await used.json()).data.rows).toEqual([{ cnt: 1, e2et_order_title: title }]);
    }
  }
  const to = new Date().toISOString();
  await expect
    .poll(
      async () => (await read(from, to)).records.map((stage: { tasks: number }) => stage.tasks),
      { timeout: 15000 },
    )
    .toEqual([10, 8, 6, 4, 2]);
  const result = await read(from, to);
  await test.info().attach('ordered-funnel-response', {
    body: JSON.stringify(result, null, 2),
    contentType: 'application/json',
  });
  expect(result.records.map((stage: { overallRate: number }) => stage.overallRate)).toEqual([
    1, 0.8, 0.6, 0.4, 0.2,
  ]);
  expect(
    result.records.map(
      (stage: { previousStageDenominator: number }) => stage.previousStageDenominator,
    ),
  ).toEqual([10, 10, 8, 6, 4]);
  expect(result.quality).toMatchObject({
    missingCorrelationEvents: 0,
    withoutWindowEntryTasks: 0,
    unmatchedStageEvents: 0,
    sampledEvents: 0,
  });
  const midstream = await read(afterQueries, to);
  expect(midstream.records.map((stage: { tasks: number }) => stage.tasks)).toEqual([0, 0, 0, 0, 0]);
  expect(
    midstream.records.every(
      (stage: { overallRate: unknown; previousStageRate: unknown; status: string }) =>
        stage.overallRate == null &&
        stage.previousStageRate == null &&
        stage.status === 'no_sample',
    ),
  ).toBe(true);
  expect(midstream.quality.withoutWindowEntryTasks).toBe(6);
  // A save before presentation and use of a different saved artifact cannot complete the chain.
  const analysis = analyses[6];
  const save = async () => {
    const response = await request.post('/api/dashboards', {
      data: {
        title: 'Ordered funnel edge',
        scope: 'personal',
        sourceAnalysisId: analysis.analysisId,
        widgets: [
          {
            id: 'edge',
            type: 'smart-table-chart',
            x: 0,
            y: 0,
            w: 6,
            h: 4,
            config: { title: 'Ordered edge', dataSource: analysis.dataSource },
          },
        ],
      },
    });
    expect(response.status()).toBe(200);
    return (await response.json()).data;
  };
  const early = await save();
  const presented = await request.post(`/api/analytics/results/${analysis.analysisId}/view`, {
    data: {},
  });
  expect(presented.status()).toBe(200);
  const usedEarly = await request.post(`/api/dashboards/${early.pid}/widgets/edge/data`, {
    data: { usageId: randomUUID() },
  });
  expect(usedEarly.status()).toBe(200);
  await expect
    .poll(async () => (await read()).records.map((stage: { tasks: number }) => stage.tasks), {
      timeout: 15000,
    })
    .toEqual([10, 8, 7, 4, 2]);
  const ordered = await save();
  const wrongArtifact = await request.post(`/api/dashboards/${early.pid}/widgets/edge/data`, {
    data: { usageId: randomUUID() },
  });
  expect(wrongArtifact.status()).toBe(200);
  await expect
    .poll(async () => (await read()).records.map((stage: { tasks: number }) => stage.tasks), {
      timeout: 15000,
    })
    .toEqual([10, 8, 7, 5, 2]);
  const correctArtifact = await request.post(`/api/dashboards/${ordered.pid}/widgets/edge/data`, {
    data: { usageId: randomUUID() },
  });
  expect(correctArtifact.status()).toBe(200);
  await expect
    .poll(async () => (await read()).records.map((stage: { tasks: number }) => stage.tasks), {
      timeout: 15000,
    })
    .toEqual([10, 8, 7, 5, 3]);
  await expect.poll(async () => (await read()).quality.unmatchedStageEvents).toBe(3);
  expect((await read(from, to)).records.map((stage: { tasks: number }) => stage.tasks)).toEqual([
    10, 8, 6, 4, 2,
  ]);
  const retention = await request.get('/api/analytics/behavior/retention', {
    params: { unit: 'artifact', from, to },
  });
  expect(retention.status()).toBe(200);
  const retained = (await retention.json()).data;
  expect(retained.records).toHaveLength(3);
  expect(retained.records.map((point: { dayOffset: number }) => point.dayOffset)).toEqual([
    1, 7, 30,
  ]);
  for (const point of retained.records) {
    expect(point.cohortSize).toBe(2);
    expect(point.status).toBe('immature');
    expect(point.retentionRate ?? null).toBeNull();
  }
  const invalidUnit = await request.get('/api/analytics/behavior/retention', {
    params: { unit: 'tenant_id', from, to },
  });
  expect(invalidUnit.status()).toBe(400);
});
