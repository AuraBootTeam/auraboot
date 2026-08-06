import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

async function readJson(relativePath) {
  return JSON.parse(await readFile(new URL(relativePath, import.meta.url), 'utf8'));
}

test('public CRM named queries only read public CRM model tables', async () => {
  const [models, namedQueries] = await Promise.all([
    readJson('../config/models.json'),
    readJson('../config/named-queries.json'),
  ]);
  const ownedTables = new Set(models.map((model) => `mt_${model.code}`));
  const references = new Map();

  for (const query of namedQueries) {
    for (const table of query.fromSql?.match(/\bmt_[a-z0-9_]+\b/g) ?? []) {
      if (!references.has(table)) references.set(table, []);
      references.get(table).push(query.code);
    }
  }

  const foreignTables = [...references]
    .filter(([table]) => !ownedTables.has(table))
    .map(([table, queries]) => `${table} (${queries.join(', ')})`);

  assert.deepEqual(
    foreignTables,
    [],
    `public CRM SQL must not depend on private or optional model tables: ${foreignTables.join('; ')}`,
  );
});

test('public forecast dashboard does not expose the private incentive quota widget', async () => {
  const [dashboard, namedQueries] = await Promise.all([
    readJson('../config/dashboards/crm_sales_forecast.json'),
    readJson('../config/named-queries.json'),
  ]);

  assert.equal(
    dashboard.widgets.some((widget) => widget.id === 'table_attainment_vs_quota'),
    false,
  );
  assert.equal(
    namedQueries.some((query) => query.code === 'crm_sales_attainment_vs_quota'),
    false,
  );
});

test('public CRM dashboards localize chart metrics and coded dimensions', async () => {
  const dashboards = await Promise.all([
    readJson('../config/dashboards/crm_dashboard.json'),
    readJson('../config/dashboards/crm_sales_forecast.json'),
  ]);

  for (const dashboard of dashboards) {
    for (const widget of dashboard.widgets) {
      const dataSource = widget.config?.dataSource;
      const metrics = dataSource?.metrics ?? [];
      if (metrics.length === 0) continue;

      const metricLabels = widget.config?.visualization?.metricLabels ?? {};
      for (const metric of metrics) {
        const key = metric.alias ?? metric.field;
        assert.equal(
          typeof metricLabels[key]?.['zh-CN'],
          'string',
          `${dashboard.code}/${widget.id} must provide a zh-CN label for ${key}`,
        );
        assert.equal(
          typeof metricLabels[key]?.en,
          'string',
          `${dashboard.code}/${widget.id} must provide an English label for ${key}`,
        );
      }
    }
  }

  const forecast = dashboards.find((dashboard) => dashboard.code === 'crm_sales_forecast');
  const stage = forecast.widgets.find((widget) => widget.id === 'chart_forecast_by_stage');
  const category = forecast.widgets.find(
    (widget) => widget.id === 'chart_forecast_by_category',
  );
  assert.equal(stage.config.dataSource.dimensionDicts.stage, 'crm_opp_stage');
  assert.equal(
    category.config.dataSource.dimensionDicts.forecast_category,
    'crm_forecast_category',
  );
});

test('forecast win rate uses the 0-to-1 ratio expected by percent cards', async () => {
  const namedQueries = await readJson('../config/named-queries.json');
  const forecastKpi = namedQueries.find((query) => query.code === 'crm_sales_forecast_kpi');

  assert.ok(forecastKpi, 'forecast KPI query should exist');
  assert.match(forecastKpi.fromSql, /\* 1\.0 \/ COUNT/);
  assert.doesNotMatch(forecastKpi.fromSql, /\* 100\.0 \/ COUNT/);
});

test('public win confirmation does not promise an optional Sales order side effect', async () => {
  const [messages, namingBaseline] = await Promise.all([
    readJson('../config/i18n.json'),
    readJson('../scripts/model-code-baseline.json'),
  ]);
  const confirmation = messages.find(
    (message) => message.key === 'message.crm.opportunity.win.confirm.content',
  );

  assert.ok(confirmation, 'win confirmation should exist');
  assert.doesNotMatch(confirmation['zh-CN'], /销售订单/);
  assert.doesNotMatch(confirmation['en-US'], /sales order/i);
  assert.equal(
    namingBaseline.some((modelCode) => modelCode.startsWith('crm_inc_')),
    false,
    'private incentive models must not be grandfathered by the public naming baseline',
  );
});
