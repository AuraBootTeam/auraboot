import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';

const root = resolve(import.meta.dirname, '../..');
const stage = readFileSync(resolve(root, 'scripts/application/stage-core-only-artifacts.mjs'), 'utf8');
const deploy = readFileSync(resolve(root, 'scripts/application/auraboot-core-env.sh'), 'utf8');
const audit = readFileSync(resolve(root, 'scripts/application/audit-core-only-schema.sh'), 'utf8');

test('core release stages a compiled Web shell and a self-contained deployment driver', () => {
  assert.match(stage, /materializeAndBuildCoreWeb/);
  assert.match(stage, /pnpm', \['--dir', 'web-admin', 'build'\]/);
  assert.match(stage, /bin\/auraboot-core-env\.sh/);
  assert.match(stage, /bin\/application/);
  assert.match(stage, /web: \{ path: 'web', digest:/);
});

test('core deployment forces an empty PF4J directory and verifies exact runtime identity', () => {
  assert.match(deploy, /-Daura\.plugins\.dir="\$STATE_ROOT\/empty-plugins"/);
  assert.match(deploy, /api\/application\/identity/);
  assert.match(deploy, /actual_identity.*expected_identity/);
  assert.match(deploy, /audit-core-only-schema\.sh/);
  assert.match(deploy, /code ~\* '\(\^\|_\)\(bpm\|crm\)/);
});

test('core schema audit keeps provider-neutral automation storage in core', () => {
  assert.doesNotMatch(audit, /'ab_automation_node_execution'/);
  assert.match(audit, /tablename ~ '\(\^\|_\)\(bpm\|crm\)\(_\|\$\)'/);
  assert.match(audit, /tablename LIKE 'se_%'/);
  assert.match(audit, /tablename LIKE 'ab_sla_%'/);
});
