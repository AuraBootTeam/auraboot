/** Raw analytics facts cannot become semantic query sources. */
import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('semantic execution and SQL preview reject private analytics models', async ({ request }) => {
  for (const model of ['core_dashboard_suggestion', 'core_dashboard_adoption']) {
    const code = `private_${randomUUID().replaceAll('-', '').slice(0, 10)}`;
    const yaml = `version: "0.1"
semantic_model:
  code: ${code}
  label: { en-US: Private source boundary }
  model_ref: ${model}
  primary_entity: record
entities:
  - name: record
    type: primary
    field_ref: pid
dimensions:
  - code: title
    field_ref: core_dashboard_title
    type: categorical
measures:
  - code: records
    agg: COUNT
    expr: pid
metrics:
  - code: total
    label: { en-US: Total }
    type: simple
    type_params:
      measure: records
`;
    const published = await request.post('/api/semantic/publish', {
      data: { yaml, pluginCode: 'test-fixtures' },
    });
    expect(published.status(), await published.text()).toBe(200);
    for (const endpoint of ['query', 'sql']) {
      const denied = await request.post(`/api/semantic/${endpoint}`, {
        data: { metrics: [`${code}.total`], limit: 5 },
      });
      expect(denied.status(), await denied.text()).toBe(403);
      expect(await denied.text()).toContain(
        'Raw analytics records require the authorized analytics service',
      );
    }
    const chart = await request.post('/api/meta/chart-data', {
      data: {
        type: 'aggregate',
        semanticModelCode: code,
        metrics: [{ field: 'total', aggregation: 'count', alias: 'total' }],
        limit: 5,
      },
    });
    expect(chart.status(), await chart.text()).toBe(403);
  }
});
