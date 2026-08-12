import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import {
  assertFullProductReady,
  assertManifestMatches,
  buildReleaseManifest,
  runMutationProof,
} from '../scripts/verify_release_coverage.mjs';

test('CRM release manifest derives the complete RG-1 through RG-4 denominator', async () => {
  const generated = buildReleaseManifest();
  const committed = JSON.parse(
    await readFile(new URL('../coverage-manifest.json', import.meta.url), 'utf8'),
  );
  assertManifestMatches(committed, generated);
  assert.equal(committed.run.sot, 'plugins/crm/README.md');
  assert.doesNotMatch(JSON.stringify(committed.run), /auraboot-enterprise/);

  assert.equal(committed.axes.semanticActions.length, 26);
  assert.equal(committed.axes.commands.length, 29);
  assert.equal(committed.axes.pages.length, 11);
  assert.equal(committed.axes.permissions.length, 17);
  assert.equal(committed.axes.queries.length, 31);
  assert.deepEqual(
    committed.untested.map((row) => row.id),
    ['RG3-INDEPENDENT-HUMAN-ADOPTER'],
  );

  assert.equal(committed.schemaVersion, 2);
  assert.deepEqual(
    committed.groups.map((group) => group.id),
    [
      'model-surfaces',
      'menus',
      'pages',
      'page-blocks',
      'page-fields',
      'ui-actions',
      'commands',
      'permissions',
      'queries',
      'dashboards',
    ],
  );
  assert.equal(committed.scope.productDenominator.pages, 96);
  assert.equal(committed.scope.productDenominator.commands, 193);
  assert.equal(committed.scope.productDenominator.permissions, 87);
  assert.equal(committed.scope.productDenominator['page-blocks'], 316);
  assert.equal(committed.scope.productDenominator['page-fields'], 1324);
  assert.equal(committed.scope.productDenominator['ui-actions'], 466);
  assert.equal(committed.scope.productDenominator.queries, 45);
  assert.equal(committed.scope.productVerdicts.pass, 329);
  assert.equal(committed.scope.productVerdicts.untested, 2317);
  assert.ok(committed.scope.productVerdicts.untested > 0);
  assert.equal(committed.scope.productVerdicts.gap ?? 0, 0);
  const coreWorkbenchContract = committed.runtimeEvidenceContracts.find(
    (contract) => contract.id === 'RG1-BROWSER',
  );
  assert.equal(coreWorkbenchContract.expectedActions, 26);
  assert.equal(coreWorkbenchContract.expectedScenarios, 5);
  assert.equal(coreWorkbenchContract.expectedCoverage.blocks.length, 37);
  assert.equal(coreWorkbenchContract.expectedCoverage.fields.length, 50);
  assert.deepEqual(Object.keys(coreWorkbenchContract.expectedCoverage).sort(), [
    'blocks',
    'commands',
    'fields',
    'pages',
    'queries',
    'uiActions',
  ]);
  const releaseBContract = committed.runtimeEvidenceContracts.find(
    (contract) => contract.id === 'RELEASE-B-OPPORTUNITY',
  );
  assert.equal(releaseBContract.expectedScenarios, 11);
  assert.equal(releaseBContract.minimumScreenshots, 24);
  assert.ok(releaseBContract.expectedCoverage.commands.includes('crm:win_opportunity'));
  assert.ok(
    releaseBContract.expectedCoverage.uiActions.includes(
      'crm_opportunity_common_list:platform:select_preset_view',
    ),
  );
  assert.deepEqual(Object.keys(releaseBContract.expectedCoverage).sort(), [
    'blocks',
    'commands',
    'dashboardTargets',
    'fields',
    'pages',
    'queries',
    'uiActions',
  ]);
  const dashboardContract = committed.runtimeEvidenceContracts.find(
    (contract) => contract.id === 'CRM-DASHBOARDS',
  );
  assert.equal(dashboardContract.expectedScenarios, 2);
  assert.equal(dashboardContract.minimumScreenshots, 6);
  assert.equal(dashboardContract.expectedCoverage.queries.length, 17);
  assert.equal(dashboardContract.expectedCoverage.dashboardTargets.length, 20);
  assert.deepEqual(Object.keys(dashboardContract.expectedCoverage).sort(), [
    'dashboardTargets',
    'pages',
    'queries',
    'uiActions',
  ]);
  assert.equal(dashboardContract.expectedTechnicalVerdict, 'pass');
  assert.equal(dashboardContract.expectedDataMigration, 'out-of-scope-development-stage');
  assert.equal(dashboardContract.requireNoFailedRuntimeRequests, true);
  assert.equal(dashboardContract.requireSeedLineage, true);
  const forecastVarianceContract = committed.runtimeEvidenceContracts.find(
    (contract) => contract.id === 'CRM-FORECAST-VARIANCE',
  );
  assert.equal(forecastVarianceContract.expectedActions, 8);
  assert.equal(forecastVarianceContract.minimumScreenshots, 2);
  assert.equal(forecastVarianceContract.expectedCoverage.queries.length, 2);
  assert.equal(forecastVarianceContract.expectedCoverage.blocks.length, 4);
  assert.equal(forecastVarianceContract.expectedCoverage.fields.length, 12);
  assert.equal(forecastVarianceContract.expectedCoverage.uiActions.length, 2);
  const modelGroup = committed.groups.find((group) => group.id === 'model-surfaces');
  assert.equal(
    modelGroup.rows.find((row) => row.id === 'model:crm_sla_breach')?.verdict,
    'pass',
    'system-managed SLA breach ledger must not require a manual form',
  );
  assert.throws(
    () => assertFullProductReady(committed),
    /CRM full-product release gate is NOT MET/,
  );
});

test('CRM release coverage gate rejects a controlled missing-action mutation', async () => {
  const committed = JSON.parse(
    await readFile(new URL('../coverage-manifest.json', import.meta.url), 'utf8'),
  );
  const proof = runMutationProof(committed);
  assert.equal(proof.verdict, 'pass');
  assert.deepEqual(
    proof.phases.map((phase) => phase.phase),
    ['green-before', 'red-controlled-mutation', 'green-restored'],
  );
  assert.ok(proof.phases.every((phase) => phase.result === 'pass'));
});

test('CRM dashboard evidence defaults to a worktree-owned directory', async () => {
  const source = await readFile(
    new URL('../e2e/crm-dashboards.golden.spec.ts', import.meta.url),
    'utf8',
  );
  assert.doesNotMatch(source, /['"]\/tmp\//);
  assert.match(
    source,
    /path\.resolve\(process\.cwd\(\), '\.workspace', 'evidence', 'crm-dashboard'\)/,
  );
});

test('CRM operating dashboards fill each authored row without overlap or half-page dead space', async () => {
  const cases = [
    ['crm_dashboard.json', [0, 1, 3, 5, 7, 9]],
    ['crm_sales_forecast.json', [0, 1, 3, 5, 7]],
  ];
  for (const [file, rowStarts] of cases) {
    const dashboard = JSON.parse(
      await readFile(new URL(`../config/dashboards/${file}`, import.meta.url), 'utf8'),
    );
    assert.match(dashboard.title, /^\$i18n:/);
    assert.match(dashboard.description, /^\$i18n:/);
    for (const row of rowStarts) {
      const starters = dashboard.widgets.filter((widget) => widget.y === row);
      assert.equal(
        starters.reduce((total, widget) => total + widget.w, 0),
        12,
        `${file} row ${row} must use the full 12-column canvas`,
      );
    }
    for (const [index, widget] of dashboard.widgets.entries()) {
      assert.ok(widget.x >= 0 && widget.x + widget.w <= 12, `${file} widget ${widget.id} bounds`);
      if (widget.type !== 'smart-number-card') {
        assert.ok(
          widget.h >= 2,
          `${file} widget ${widget.id} must have readable chart/table height`,
        );
      }
      for (const other of dashboard.widgets.slice(index + 1)) {
        const overlaps =
          widget.x < other.x + other.w &&
          widget.x + widget.w > other.x &&
          widget.y < other.y + other.h &&
          widget.y + widget.h > other.y;
        assert.equal(overlaps, false, `${file} widgets ${widget.id}/${other.id} overlap`);
      }
    }
  }
});

test('CRM operating dashboards preserve business semantics and localized table values', async () => {
  const mainDashboard = JSON.parse(
    await readFile(new URL('../config/dashboards/crm_dashboard.json', import.meta.url), 'utf8'),
  );
  const forecastDashboard = JSON.parse(
    await readFile(
      new URL('../config/dashboards/crm_sales_forecast.json', import.meta.url),
      'utf8',
    ),
  );
  const namedQueries = JSON.parse(
    await readFile(new URL('../config/named-queries.json', import.meta.url), 'utf8'),
  );

  const pendingComplaintMetric = mainDashboard.widgets
    .flatMap((widget) => widget.config?.cards ?? [])
    .find((metric) => metric.field === 'pending_complaints');
  assert.deepEqual(pendingComplaintMetric?.drillDown?.filters?.[0]?.value, [
    'open',
    'investigating',
  ]);
  const dashboardKpi = namedQueries.find((query) => query.code === 'crm_dashboard_kpi');
  assert.match(dashboardKpi.fromSql, /crm_cmp_status IN \('open', 'investigating'\)/);
  assert.doesNotMatch(dashboardKpi.fromSql, /crm_cmp_status IN \('new', 'investigating'\)/);

  const recentActivities = namedQueries.find((query) => query.code === 'crm_recent_activities');
  assert.match(recentActivities.fromSql, /a\.crm_act_related_model/);
  assert.match(recentActivities.fromSql, /WHEN 'crm_account_common' THEN 'account'/);

  const widgets = Object.fromEntries(
    forecastDashboard.widgets.map((widget) => [widget.id, widget]),
  );
  assert.equal(widgets.chart_forecast_by_stage.config.orientation, 'vertical');
  assert.equal(widgets.chart_forecast_by_category.config.orientation, 'vertical');
  assert.equal(widgets.chart_forecast_by_owner.config.showLabel, false);
  assert.equal(widgets.chart_forecast_by_owner.config.chartOptions.grid.right, '10%');
  assert.equal(widgets.chart_forecast_by_owner.config.chartOptions.xAxis.splitNumber, 3);
  assert.deepEqual(widgets.table_stage_detail.config.dataSource.dimensionDicts, {
    stage: 'crm_opp_stage',
  });
  assert.deepEqual(widgets.table_forecast_by_category_detail.config.dataSource.dimensionDicts, {
    forecast_category: 'crm_forecast_category',
  });
  assert.deepEqual(widgets.table_open_opportunities.config.dataSource.dimensionDicts, {
    crm_opp_stage: 'crm_opp_stage',
  });
  assert.equal(
    widgets.table_open_opportunities.config.table.columns.find(
      (column) => column.field === 'crm_opp_expected_close_date',
    )?.format,
    'date',
  );
});
