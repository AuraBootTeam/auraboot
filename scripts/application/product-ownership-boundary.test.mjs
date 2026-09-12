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
});

test('core production graph contains no product tables or product-owned HTTP routes', () => {
  const java = joined(resolve(ROOT, 'platform/src/main/java'), (path) => path.endsWith('.java'));
  const web = joined(resolve(ROOT, 'web-admin/app'), (path) => /\.(?:ts|tsx)$/.test(path)
    && !path.includes('/__tests__/'));
  assert.doesNotMatch(java, /\b(?:ab_bpm_[a-z0-9_]*|mt_crm_[a-z0-9_]*)\b/i);
  assert.doesNotMatch(java, /["']\/api\/bpm(?:\/|["'])/i);
  assert.doesNotMatch(web, /["']\/api\/bpm(?:\/|["'])/i);
  assert.doesNotMatch(web, /["']\/bpm\//i);
  assert.doesNotMatch(`${java}\n${web}`, /on_bpm_event|trigger-bpm-event|bpm-inline-approval|process-select/i);
});

test('core build declares no SmartEngine, Drools or KIE artifact dependency', () => {
  const build = readFileSync(resolve(ROOT, 'platform/build.gradle'), 'utf8');
  assert.doesNotMatch(build, /com\.auraboot\.smart\.framework|org\.drools|org\.kie|kie-/i);
});

test('core exposes no legacy BPM product or release runner fallback', () => {
  for (const relative of [
    'scripts/bpm-release-gate.sh',
    'scripts/bpm-release-gate.pins.json',
    'scripts/run-bpm-release-image-gate.sh',
  ]) {
    assert.equal(existsSync(resolve(ROOT, relative)), false, `${relative} must remain product-owned outside core`);
  }
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
