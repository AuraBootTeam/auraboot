/** Real semantic calculation across conversation, saved dashboard and report APIs. */
import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('governed revenue stays consistent across AuraBot, dashboard and report artifacts', async ({
  request,
}) => {
  test.setTimeout(180000);
  const key = `consistency_${randomUUID().replaceAll('-', '').slice(0, 10)}`;
  for (const [region, amount] of [
    ['East', 70],
    ['East', 50],
    ['West', 80],
  ] as const) {
    const response = await request.post('/api/dynamic/e2et_order/create', {
      data: {
        e2et_order_title: key,
        e2et_order_customer: region,
        e2et_order_amount: amount,
        e2et_order_type: 'normal',
        e2et_order_urgent: false,
        e2et_order_status: 'draft',
      },
    });
    expect(response.status()).toBe(200);
    expect(String((await response.json()).code)).toBe('0');
  }
  const yaml = `version: "0.1"
semantic_model:
  code: ${key}
  label: { en-US: Consistency revenue, zh-CN: 一致性收入 }
  model_ref: e2et_order
  primary_entity: order_id
entities:
  - name: order_id
    type: primary
    field_ref: id
dimensions:
  - code: region
    label: { en-US: Region, zh-CN: 区域 }
    field_ref: e2et_order_customer
    type: categorical
  - code: fixture
    label: { en-US: Fixture, zh-CN: 夹具 }
    field_ref: e2et_order_title
    type: categorical
measures:
  - code: amount
    agg: SUM
    expr: e2et_order_amount
metrics:
  - code: revenue
    label: { en-US: Revenue, zh-CN: 收入 }
    type: simple
    type_params:
      measure: amount
`;
  const published = await request.post('/api/semantic/publish', {
    data: { yaml, pluginCode: 'test-fixtures' },
  });
  expect(published.status(), await published.text()).toBe(200);
  const query = {
    type: 'aggregate',
    semanticModelCode: key,
    dimensions: ['region'],
    metrics: [{ field: 'revenue', aggregation: 'sum', alias: 'revenue' }],
    filters: [{ field: 'fixture', operator: 'eq', value: key }],
    limit: 10,
  };
  const normalized = (rows: Record<string, unknown>[]) =>
    rows
      .map((row) => ({
        region: row.region,
        revenue: Number(row.revenue),
      }))
      .sort((a, b) => String(a.region).localeCompare(String(b.region)));
  const expected = [
    { region: 'East', revenue: 120 },
    { region: 'West', revenue: 80 },
  ];
  const chart = await request.post('/api/meta/chart-data', { data: query });
  expect(chart.status(), await chart.text()).toBe(200);
  expect(normalized((await chart.json()).data.rows)).toEqual(expected);
  const { type: queryType, ...toolQuery } = query;
  expect(queryType).toBe('aggregate');
  const chat = await request.post('/api/ai/aurabot/chat/stream', {
    headers: { Accept: 'text/event-stream' },
    data: {
      sessionId: key,
      clientMsgId: randomUUID(),
      message:
        '@@AURABOOT_STUB_TOOL_USE@@ ' +
        JSON.stringify({
          name: 'aurabot_chat-bi',
          input: { ...toolQuery, chartType: 'table' },
        }),
    },
  });
  expect(chat.status()).toBe(200);
  const contracts = (await chat.text())
    .split(/\r?\n\r?\n/)
    .filter(
      (block) =>
        block.startsWith('event:result_contract') || block.startsWith('event: result_contract'),
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
  expect(analysis, JSON.stringify(contracts)).toBeDefined();
  expect(normalized(analysis.records)).toEqual(expected);
  expect(analysis.dataSource.semanticModelCode).toBe(key);
  const dashboard = await request.post('/api/dashboards', {
    data: {
      title: key,
      scope: 'personal',
      sourceAnalysisId: analysis.analysisId,
      widgets: [
        {
          id: 'revenue',
          type: 'smart-table-chart',
          x: 0,
          y: 0,
          w: 6,
          h: 4,
          config: { title: key, dataSource: analysis.dataSource },
        },
      ],
    },
  });
  expect(dashboard.status()).toBe(200);
  const dashboardPid = (await dashboard.json()).data.pid;
  const dashboardData = await request.post(`/api/dashboards/${dashboardPid}/widgets/revenue/data`, {
    data: { usageId: randomUUID() },
  });
  expect(dashboardData.status(), await dashboardData.text()).toBe(200);
  expect(normalized((await dashboardData.json()).data.rows)).toEqual(expected);
  const preview = await request.post('/api/reports/query/aggregate', { data: analysis.dataSource });
  expect(preview.status(), await preview.text()).toBe(200);
  expect(normalized((await preview.json()).data.rows)).toEqual(expected);
  const dsl = {
    $schema: 'auraboot://schemas/report/v1',
    version: '1.0.0',
    title: key,
    page: {
      size: 'A4',
      orientation: 'portrait',
      margin: { top: 20, right: 20, bottom: 20, left: 20 },
    },
    dataSources: { revenue: { type: 'aggregate', aggregateQuery: analysis.dataSource } },
    body: [
      {
        id: 'revenue',
        blockType: 'table',
        dataSource: 'revenue',
        columns: [
          { field: 'region', label: 'Region' },
          { field: 'revenue', label: 'Revenue' },
        ],
        showHeader: true,
      },
    ],
  };
  const report = await request.post('/api/report-definitions', {
    data: { code: key, title: key, profile: 'paged-media', dsl },
  });
  expect(report.status(), await report.text()).toBe(200);
  const reportPid = (await report.json()).data.pid;
  const exported = await request.post('/api/reports/export/json', { data: { reportPid } });
  expect(exported.status(), await exported.text()).toBe(200);
  const artifact = await exported.json();
  expect(normalized(artifact.dataSets.revenue)).toEqual(expected);
  expect(artifact.reportDsl.dataSources.revenue.aggregateQuery).toEqual(analysis.dataSource);
  await test.info().attach('semantic-artifact-consistency', {
    body: JSON.stringify({
      key,
      dashboardPid,
      reportPid,
      expected,
      query: analysis.dataSource,
      artifact,
    }),
    contentType: 'application/json',
  });
});
