import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';

const ROOT = resolve(dirname(new URL(import.meta.url).pathname), '../..');

function files(root, accept = () => true) {
  return readdirSync(root).flatMap((name) => {
    const path = resolve(root, name);
    return statSync(path).isDirectory() ? files(path, accept) : accept(path) ? [path] : [];
  });
}

function joined(root, accept) {
  return files(root, accept).map((path) => readFileSync(path, 'utf8')).join('\n');
}

test('core migrations own no BPM, CRM, SmartEngine or engine tables', () => {
  const sql = joined(resolve(ROOT, 'platform/src/main/resources/db/migration/core'),
    (path) => path.endsWith('.sql'));
  assert.doesNotMatch(
    sql,
    /\b(?:ab_bpm_[a-z0-9_]*|mt_crm_[a-z0-9_]*|se_[a-z0-9_]*|ab_inbound_(?:channel(?:_stats)?|form|message)|ab_lead_merge_queue|ab_calendar_(?:sync|event_map))\b/i,
  );
  assert.doesNotMatch(sql, /\b(?:smartengine|smart_engine)\b/i);
});

test('core tenant and platform-admin configuration owns no BPM or CRM resources', () => {
  const platformAdmin = joined(resolve(ROOT, 'plugins/platform-admin/config'),
    (path) => path.endsWith('.json'));
  assert.doesNotMatch(platformAdmin, /(?:^|[^a-z0-9])(?:bpm|crm|sla|smartengine)(?:[^a-z0-9]|$)/i);

  const bootstrap = JSON.parse(readFileSync(
    resolve(ROOT, 'platform/src/main/resources/tenant-templates/default-bootstrap.json'), 'utf8'));
  assert.deepEqual(
    bootstrap.permissions.filter((permission) => /^(?:bpm|crm)\./i.test(permission.code)
      || /^(?:bpm|crm)$/i.test(permission.module)),
    [],
  );

  const baseI18n = readFileSync(resolve(ROOT, 'platform/src/main/resources/seed/i18n-base.json'), 'utf8');
  assert.doesNotMatch(baseI18n, /(?:^|[^a-z0-9])(?:bpm|crm|smartengine)(?:[^a-z0-9]|$)/i);

  const runtimeI18n = ['i18n.zh-CN.yaml', 'i18n.en-US.yaml', 'i18n.ja-JP.yaml', 'i18n.ko-KR.yaml']
    .map((name) => readFileSync(resolve(ROOT, 'platform/src/main/resources', name), 'utf8'))
    .join('\n');
  assert.doesNotMatch(
    runtimeI18n,
    /^\s+(?:crm_opportunity_amount|crm_account_active|bpm_running|bpm_completed_week|newLead|newAccount|newOpportunity|newContract|startProcess):/m,
  );
  assert.doesNotMatch(runtimeI18n, /^\s+(?:crm|bpm):\s+(?:CRM|BPM|流程管理)/m);
  assert.doesNotMatch(runtimeI18n, /^bpm:/m);
  assert.doesNotMatch(
    runtimeI18n,
    /^\s+(?:pipeline|leads|activities|my_process|process_stats)(?:_desc)?:/m,
  );

  const showcaseNamedQueries = readFileSync(
    resolve(ROOT, 'plugins/showcase/config/named-queries.json'),
    'utf8',
  );
  assert.doesNotMatch(showcaseNamedQueries, /\bmt_crm_[a-z0-9_]+\b/i);
});

test('core production graph contains no product tables or product-owned HTTP routes', () => {
  const java = joined(resolve(ROOT, 'platform/src/main/java'), (path) => path.endsWith('.java'));
  const web = joined(resolve(ROOT, 'web-admin/app'), (path) => /\.(?:ts|tsx)$/.test(path)
    && !path.includes('/__tests__/'));
  assert.doesNotMatch(java, /\b(?:ab_bpm_[a-z0-9_]*|mt_crm_[a-z0-9_]*)\b/i);
  assert.doesNotMatch(java, /["']\/api\/bpm(?:\/|["'])/i);
  assert.doesNotMatch(java, /["']\/api\/crm(?:\/|["'])/i);
  assert.doesNotMatch(java, /modelCode\.startsWith\(["']crm_["']\)/);
  assert.doesNotMatch(web, /["']\/api\/bpm(?:\/|["'])/i);
  assert.doesNotMatch(web, /["']\/api\/crm(?:\/|["'])/i);
  assert.doesNotMatch(web, /["']\/bpm\//i);
  assert.doesNotMatch(`${java}\n${web}`, /on_bpm_event|trigger-bpm-event|bpm-inline-approval|process-select/i);
});

test('core build declares no SmartEngine, Drools or KIE artifact dependency', () => {
  const build = readFileSync(resolve(ROOT, 'platform/build.gradle'), 'utf8');
  assert.doesNotMatch(build, /com\.auraboot\.smart\.framework|org\.drools|org\.kie|kie-/i);
});

test('core exposes no legacy BPM or CRM product test and release fallback', () => {
  for (const relative of [
    'scripts/bpm-release-gate.sh',
    'scripts/bpm-release-gate.pins.json',
    'scripts/run-bpm-release-image-gate.sh',
    'scripts/run-wf-e2e.sh',
    'scripts/p1-verify-in-docker.sh',
    'scripts/dev/run-p0-e2e-docker.sh',
    'scripts/crm-acceptance',
    'web-admin/playwright.bpm-regression.config.ts',
    'web-admin/playwright.workflow-demo.config.ts',
    'web-admin/tests/e2e/bpm-designer',
    'web-admin/tests/e2e/bpm-smoke/wf-end-to-end-smoke.spec.ts',
    'web-admin/tests/e2e/crm',
    'web-admin/tests/api/bpm-conversion.spec.ts',
    'web-admin/tests/api/bpm-process-definition.spec.ts',
    'web-admin/tests/api/bpm-sla-suspend.spec.ts',
    'web-admin/tests/api/bpm-workflow.spec.ts',
    'web-admin/tests/api/setup/workflow-demo-import.spec.ts',
    'web-admin/tests/api/agent/crm-agent-quality-report.mjs',
    'web-admin/tests/api/agent/crm-agent-quality-report.spec.ts',
    'web-admin/tests/api/agent/crm-agent-validation.spec.ts',
    'web-admin/tests/api/agent/crm-ai-scenarios.spec.ts',
    'web-admin/tests/api/agent/run-crm-agent-validation.mjs',
    'web-admin/tests/e2e/email/email-crm-timeline.spec.ts',
    'web-admin/tests/e2e/aurabot/pcba-procurement-agent-entry.spec.ts',
    'web-admin/tests/e2e/aurabot/pcba-procurement-agent-write.spec.ts',
    'web-admin/tests/e2e/aurabot/pcba-quality-agent-entry.spec.ts',
    'web-admin/tests/e2e/aurabot/pcba-quality-agent-write.spec.ts',
    'web-admin/tests/e2e/pcba/oee-dashboard-host-golden.spec.ts',
    'web-admin/tests/e2e/automation/rule-binding-designer-host.spec.ts',
    'web-admin/tests/e2e/decisionops/condition-fragment-library-golden.spec.ts',
    'web-admin/tests/e2e/decisionops/strategy-studio-dmn-value-labels.spec.ts',
    'web-admin/tests/e2e/p1demo/wd-leave-request-ai-lifecycle.spec.ts',
    'web-admin/tests/e2e/permission/permission-abac-rule-center.spec.ts',
    'web-admin/tests/e2e/sales/crm-receivables-lifecycle.spec.ts',
    'web-admin/tests/e2e/sales/crm-win-sales-extension.spec.ts',
    'platform/src/test/java/com/auraboot/framework/meta/contribution/CrmOpportunityPageContributionContractTest.java',
    'platform/src/test/java/com/auraboot/framework/automation/bpm',
    'platform/src/test/java/com/auraboot/framework/automation/AutomationServiceIntegrationTest.java',
    'platform/src/test/java/com/auraboot/framework/automation/AutomationIntegrationTest.java',
    'platform/src/test/java/com/auraboot/framework/automation/AutomationSendNotificationIntegrationTest.java',
    'platform/src/test/java/com/auraboot/framework/automation/QualityAutoCapaChainGoldenIT.java',
    'platform/src/test/java/com/auraboot/framework/automation/iot/IotRuleSmartEngineIntegrationTest.java',
    'plugins/crm',
    'plugins/core-bpm',
    'plugins/workflow-demo',
    'web-admin/scripts/seed-workflow-demo.mjs',
    'web-admin/tests/api/setup/seed-showcase-data.spec.ts',
    'web-admin/tests/api/setup/seed-showcase-extended.spec.ts',
    'web-admin/tests/api/setup/seed-showcase-commercial.spec.ts',
    'web-admin/tests/api/setup/seed-showcase-supplement.spec.ts',
    'web-admin/tests/api/setup/seed-showcase-invariants.spec.ts',
    'web-admin/tests/api/setup/seed-showcase-dashboard-default.spec.ts',
    'web-admin/tests/api/setup/seed-showcase-ai.spec.ts',
    'web-admin/tests/api/setup/seed-showcase-ownership.spec.ts',
    'web-admin/tests/api/setup/seed-showcase-workflow.spec.ts',
    'web-admin/tests/api/setup/seed-showcase-arsenal.spec.ts',
    'web-admin/tests/api/setup/seed-showcase-v2-invariants.spec.ts',
    'web-admin/tests/e2e/showcase/showcase-smoke.spec.ts',
    'web-admin/tests/e2e/showcase/showcase-ux-regression.spec.ts',
    'web-admin/tests/e2e/showcase/seed-data-validation.spec.ts',
    'web-admin/tests/e2e/dashboard/designer-datasource-config-golden.spec.ts',
    'web-admin/tests/e2e/dashboard/aggregate-grain-orderby-golden.spec.ts',
    'web-admin/tests/e2e/dashboard/arsenal-live-datasource-golden.spec.ts',
    'web-admin/tests/golden/g1-fixes-golden.spec.ts',
    'web-admin/tests/golden/playwright.g1.config.ts',
    'web-admin/scripts/run-showcase-seed-sequence.mjs',
    'web-admin/scripts/run-showcase-seed-sequence.test.mjs',
    'web-admin/playwright.seed.config.ts',
    'scripts/docker-ga-showcase-e2e.sh',
    'scripts/host-oee-dashboard-golden.sh',
    'scripts/deploy/oss-remote/gen-admin-storage.mjs',
    'web-admin/tests/helpers/wd-fixtures.ts',
  ]) {
    assert.equal(existsSync(resolve(ROOT, relative)), false, `${relative} must remain product-owned outside core`);
  }
});

test('core repository publishing never reconstructs product source trees', () => {
  const script = readFileSync(resolve(ROOT, 'scripts/publish-repos.sh'), 'utf8');
  assert.doesNotMatch(script, /plugins\/(?:crm|core-bpm|workflow-demo)/);
});

test('core runtime gates never seed or execute BPM/CRM product journeys', () => {
  const scripts = [
    'scripts/docker-ga-e2e-bootstrap.sh',
    'scripts/oss-e2e-gate-run.sh',
    'scripts/ga-showcase-e2e.sh',
    'scripts/deploy/oss-remote/deploy.sh',
  ].map((relative) => readFileSync(resolve(ROOT, relative), 'utf8')).join('\n');

  assert.doesNotMatch(scripts, /seed-showcase|run-showcase-seed-sequence|test:agent:crm/i);
  assert.doesNotMatch(scripts, /(?:plugins\/|import[_ -]?plugins[^\n]*)(?:crm|core-bpm|workflow-demo)/i);
});

test('core CLI ships no executable CRM workflow defaults or shell shortcuts', () => {
  const shell = readFileSync(resolve(ROOT, 'plugins/cli/src/commands/shell.ts'), 'utf8');
  const templates = joined(resolve(ROOT, 'plugins/cli/src/pipe/templates'),
    (path) => /\.ya?ml$/.test(path));

  assert.doesNotMatch(shell, /['"]crm\s+(?:leads|opps|accounts|dashboard)['"]/i);
  assert.doesNotMatch(templates, /\bcrm_(?:lead|opportunity|account|activity)_common\b/i);
  assert.equal(existsSync(resolve(ROOT, 'plugins/cli/src/pipe/templates/new-leads-digest.yaml')), false);
  assert.equal(existsSync(resolve(ROOT, 'plugins/cli/src/pipe/templates/daily-sales-report.yaml')), false);
});

test('public source-facade npm exports resolve to typed source files', () => {
  const dslRuntime = JSON.parse(readFileSync(resolve(ROOT, 'packages/dsl-runtime/package.json'), 'utf8'));
  const designerSdk = JSON.parse(readFileSync(resolve(ROOT, 'packages/designer-sdk/package.json'), 'utf8'));

  assert.equal(dslRuntime.exports['./contexts/*'], './src/contexts/*.tsx');
  assert.equal(dslRuntime.exports['./shared/services/*'], './src/shared/services/*.ts');
  assert.equal(dslRuntime.exports['./shared/*'], './src/shared/*.ts');
  assert.equal(designerSdk.exports['./flow-designer-sdk'], './src/flow-designer-sdk/index.ts');
  assert.equal(designerSdk.exports['./flow-designer-sdk/store/*'], './src/flow-designer-sdk/store/*.ts');
});
