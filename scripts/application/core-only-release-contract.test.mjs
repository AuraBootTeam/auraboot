import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';

const root = resolve(import.meta.dirname, '../..');
const stage = readFileSync(resolve(root, 'scripts/application/stage-core-only-artifacts.mjs'), 'utf8');
const deploy = readFileSync(resolve(root, 'scripts/application/auraboot-core-env.sh'), 'utf8');
const audit = readFileSync(resolve(root, 'scripts/application/audit-core-only-schema.sh'), 'utf8');
const oci = readFileSync(resolve(root, 'scripts/application/oci-layout.mjs'), 'utf8');
const productImageGate = readFileSync(resolve(root, 'scripts/application/run-product-release-image-gate.sh'), 'utf8');

test('core release stages a compiled Web shell and a self-contained deployment driver', () => {
  assert.match(stage, /platform\/gradlew/);
  assert.match(stage, /'clean', 'bootJar', '--no-daemon', '-x', 'test'/);
  assert.match(stage, /materializeAndBuildCoreWeb/);
  assert.match(stage, /pnpm', \['--dir', 'web-admin', 'build'\]/);
  assert.match(stage, /bin\/auraboot-core-env\.sh/);
  assert.match(stage, /bin\/application/);
  assert.match(stage, /web: \{ path: 'web', digest:/);
});

test('product release-image gate is CI-only, Docker-only and evidence-backed', () => {
  assert.match(productImageGate, /AURA_CI_JOB_ID/);
  assert.match(productImageGate, /uname -s.*Linux/);
  assert.match(productImageGate, /uname -m.*x86_64/);
  assert.match(productImageGate, /AURA_OCI_BUILDER must be docker/);
  assert.match(productImageGate, /docker load --input/);
  assert.match(productImageGate, /fresh database migration failed/);
  assert.match(productImageGate, /playwright test --config playwright\.release\.config\.ts/);
  assert.match(productImageGate, /release-image-receipt\.json/);
  assert.doesNotMatch(productImageGate, /\bcontainer\s+(?:build|run|image)/);
});

test('core deployment forces an empty PF4J directory and verifies exact runtime identity', () => {
  assert.match(deploy, /AURA_APPLICATION_MODE=core-only/);
  assert.match(deploy, /-Daura\.plugins\.dir="\$STATE_ROOT\/empty-plugins"/);
  assert.match(deploy, /api\/application\/identity/);
  assert.match(deploy, /actual_identity.*expected_identity/);
  assert.match(deploy, /audit-core-only-schema\.sh/);
  assert.match(deploy, /code ~\* '\(\^\|_\)\(bpm\|crm\)/);
});

test('core bootstrap source contains no BPM or CRM product seed content', () => {
  const categorySeeder = readFileSync(resolve(root, 'platform/src/main/java/com/auraboot/framework/application/bootstrap/seeder/MarketplaceCategorySeeder.java'), 'utf8');
  const solutionSeeder = readFileSync(resolve(root, 'platform/src/main/java/com/auraboot/framework/application/bootstrap/seeder/SolutionSeeder.java'), 'utf8');
  const agentSeeder = readFileSync(resolve(root, 'platform/src/main/java/com/auraboot/framework/application/bootstrap/seeder/AgentTemplateSeeder.java'), 'utf8');
  const bootstrap = readFileSync(resolve(root, 'platform/src/main/resources/tenant-templates/default-bootstrap.json'), 'utf8');
  for (const source of [categorySeeder, solutionSeeder, agentSeeder]) {
    assert.doesNotMatch(source, /(^|[^a-z0-9])(crm|bpm)([^a-z0-9]|$)/i);
  }
  assert.doesNotMatch(bootstrap, /BPM/);
});

test('core schema audit keeps provider-neutral automation storage in core', () => {
  assert.doesNotMatch(audit, /'ab_automation_node_execution'/);
  assert.match(audit, /tablename ~ '\(\^\|_\)\(bpm\|crm\)\(_\|\$\)'/);
  assert.match(audit, /tablename LIKE 'se_%'/);
  assert.match(audit, /tablename LIKE 'ab_sla_%'/);
});

test('release OCI is built on a pinned Linux JRE and requires a real image builder', () => {
  assert.match(oci, /eclipse-temurin:21-jre@sha256:[0-9a-f]{64}/);
  assert.doesNotMatch(oci, /execFileSync\('container'/);
  assert.match(oci, /self-hosted Linux CI Docker builder \(linux\/amd64\)/);
  assert.match(oci, /AURA_OCI_BUILDER must be docker/);
  assert.match(oci, /'buildx', 'build'/);
  assert.match(oci, /'--platform', 'linux\/amd64'/);
  assert.match(oci, /mkdtempSync\(resolve\(dirname\(output\), '\.auraboot-image-'\)\)/);
  assert.doesNotMatch(oci, /application payload layer/);
});
