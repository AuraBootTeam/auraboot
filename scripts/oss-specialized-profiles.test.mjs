import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const web = fileURLToPath(new URL('../web-admin/', import.meta.url));
const requireWeb = createRequire(new URL('../web-admin/package.json', import.meta.url));
const specialized = [
  'agent-approval/permissions.spec.ts',
  'project-delivery-automation-dashboard.spec.ts',
  'customer-bpm-roundtrip.spec.ts',
  'customer-delivery-dashboard.spec.ts',
  'customer-delivery-permissions-real.spec.ts',
  'oss-performance.spec.ts',
  'automation-designer-golden.spec.ts',
  'automation-deep.spec.ts',
  'crud-default-case.spec.ts',
];

function collect(config, overrides = {}, projects = [], files = []) {
  const root = mkdtempSync(join(tmpdir(), 'oss-profile-contract-'));
  try {
    // Resolve the test SDK itself; a global "playwright" binary may be a different CLI.
    const cli = requireWeb.resolve('@playwright/test/cli');
    const result = spawnSync(process.execPath, [cli, 'test', '-c', config,
      '--list', '--reporter=json', ...projects.map(name => `--project=${name}`), ...files], {
      cwd: web, encoding: 'utf8', timeout: 60_000, maxBuffer: 32 * 1024 * 1024,
      env: { ...process.env, PW_PROFILE: 'full', PW_ROLE_PROJECTS: '1', PW_SKIP_WEBSERVER: '1',
        PW_ARTIFACT_DIR: join(root, 'artifacts'), PW_REPORT_DIR: join(root, 'report'),
        PW_RESULTS_JSON: '', PLAYWRIGHT_JSON_OUTPUT_FILE: '',
        PW_ADMIN_STORAGE_STATE: join(root, 'private-admin.json'), PW_STORAGE_DIR: '',
        PERF_EVIDENCE_DIR: join(root, 'performance'), ...overrides },
    });
    assert.ifError(result.error);
    return result;
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

test('general OSS collection keeps platform tests and excludes specialized fixture contracts', () => {
  const result = collect('playwright.oss.config.ts', {}, ['chromium', 'chromium-deep']);
  assert.equal(result.status, 0, result.stderr);
  const report = JSON.parse(result.stdout);
  assert.deepEqual(report.errors, []);
  assert.match(result.stdout, /ML-03: status and sourceType filters/);
  for (const file of specialized) assert.ok(!result.stdout.includes(file), `${file} leaked into OSS full`);
});

test('performance profile collects both original cases with private auth', () => {
  const result = collect('playwright.performance.config.ts');
  assert.equal(result.status, 0, result.stderr);
  const report = JSON.parse(result.stdout);
  assert.deepEqual(report.errors, []);
  const specs = report.suites.flatMap(suite => suite.specs);
  assert.equal(specs.length, 2);
  assert.ok(specs.every(spec => spec.file.endsWith('oss-performance.spec.ts')));
});

test('performance profile rejects missing private auth before executing a worker', () => {
  const result = collect('playwright.performance.config.ts', { PW_ADMIN_STORAGE_STATE: '' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr + result.stdout, /require private PW_ADMIN_STORAGE_STATE or PW_STORAGE_DIR/);
});

test('performance profile rejects missing evidence destination before executing a worker', () => {
  const result = collect('playwright.performance.config.ts', { PERF_EVIDENCE_DIR: '' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr + result.stdout, /require PERF_EVIDENCE_DIR/);
});

function caseIdentities(report) {
  const rows = [];
  function visit(node, parents = []) {
    for (const suite of node.suites || []) visit(suite, [...parents, suite.title]);
    for (const spec of node.specs || []) rows.push([spec.file, ...parents, spec.title].join(' / '));
  }
  visit(report);
  return rows.sort();
}

test('CRUD profile retains the original workbench journey and real administrator auth', () => {
  const profile = collect('playwright.crud.config.ts', {
    PLAYWRIGHT_BASE_URL: 'http://localhost:5173', PW_STORAGE_DIR: '/tmp/crud-profile-private',
  });
  assert.equal(profile.status, 0, profile.stderr);
  const report = JSON.parse(profile.stdout);
  assert.deepEqual(report.errors, []);
  const rows = caseIdentities(report);
  assert.equal(rows.filter(row => row.includes('authenticate as admin')).length, 1);
  assert.equal(rows.filter(row => row.includes('crud-default-case.spec.ts')).length, 6);
  const original = collect('playwright.config.ts', { PLAYWRIGHT_BASE_URL: 'http://localhost:5173' },
    ['chromium'], ['--no-deps', 'tests/e2e/showcase/model-page-workbench-e2e.spec.ts']);
  assert.equal(original.status, 0, original.stderr);
  assert.deepEqual(rows.filter(row => row.includes('model-page-workbench-e2e.spec.ts')),
    caseIdentities(JSON.parse(original.stdout)));
});

test('CRUD profile rejects implicit runtime or shared authentication', () => {
  const result = collect('playwright.crud.config.ts', {
    PLAYWRIGHT_BASE_URL: '', PW_STORAGE_DIR: '',
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stdout + result.stderr, /CRUD regression requires explicit owned/);
});

test('workflow profile retains every original automation case and adds an application preflight', () => {
  const env = { PLAYWRIGHT_BASE_URL: 'http://localhost:5173' };
  const workflow = collect('playwright.workflow.config.ts', env);
  assert.equal(workflow.status, 0, workflow.stderr);
  const report = JSON.parse(workflow.stdout);
  const originals = collect('playwright.config.ts', env, ['chromium', 'chromium-deep'], [
    '--no-deps', 'tests/e2e/automation/automation-designer-golden.spec.ts',
    'tests/e2e/automation/automation-deep.spec.ts',
  ]);
  assert.equal(originals.status, 0, originals.stderr);
  const setupFiles = ['workflow-application.setup.ts', 'workflow-fixture.setup.ts'];
  const actual = caseIdentities(report).filter(row => !setupFiles.some(file => row.includes(file)));
  for (const file of setupFiles) {
    assert.equal(caseIdentities(report).filter(row => row.includes(file)).length, 1, `${file} must collect once`);
  }
  assert.ok(actual.length > 0);
  assert.deepEqual(actual, caseIdentities(JSON.parse(originals.stdout)));
  assert.match(workflow.stdout, /requires the real BPM application/);
});

test('workflow profile rejects implicit runtime and authentication', () => {
  const result = collect('playwright.workflow.config.ts', { PW_ADMIN_STORAGE_STATE: '', PLAYWRIGHT_BASE_URL: '' });
  assert.notEqual(result.status, 0);
  assert.match(result.stdout + result.stderr, /Workflow tests require an explicit owned/);
});

test('agent permission profile rejects implicit model usage or private fixtures', () => {
  const result = collect('playwright.agent-approval.config.ts', {
    AGENT_PERMISSION_FIXTURE: '', AGENT_PERMISSION_EVIDENCE: '', AGENT_ACCEPTANCE_REAL_MODEL: '',
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr + result.stdout, /Agent permission UI requires explicit owned URLs/);
});

test('agent permission profile collects exactly the three real UI journeys without setup mutations', () => {
  const root = mkdtempSync(join(tmpdir(), 'agent-ui-profile-contract-'));
  try {
    const fixture = join(root, 'private-fixture.json');
    writeFileSync(fixture, JSON.stringify({
      baseURL: 'http://localhost:5173', backendURL: 'http://localhost:6443',
      admin: { email: 'admin@example.invalid', password: 'fixture-only' },
      reader: { email: 'reader@example.invalid', password: 'fixture-only' },
      modelCode: 'controlled_model', modelName: 'Controlled model', tenantId: '1',
      adminConversationId: 1, privateHistoryValue: 'Private fixture history',
      database: { database: 'controlled_database' }, productIdentity: join(root, 'identity.json'),
      bundleSha256: 'a'.repeat(64),
    }), { mode: 0o600 });
    const result = collect('playwright.agent-approval.config.ts', {
      PLAYWRIGHT_BASE_URL: 'http://localhost:5173', BACKEND_URL: 'http://localhost:6443',
      BE_PORT: '6443', VITE_PORT: '5173', BFF_PORT: '3500',
      AGENT_PERMISSION_FIXTURE: fixture, AGENT_PERMISSION_EVIDENCE: root,
      AGENT_ACCEPTANCE_REAL_MODEL: 'deepseek',
    });
    assert.equal(result.status, 0, result.stderr + result.stdout);
    const report = JSON.parse(result.stdout);
    assert.deepEqual(report.errors, []);
    const identities = caseIdentities(report);
    assert.equal(identities.length, 3);
    assert.ok(identities.every(value => value.includes('permissions.spec.ts')));
    assert.ok(!identities.some(value => value.includes('setup')));
    assert.match(result.stdout, /ordinary member reads model metadata/);
    assert.match(result.stdout, /ordinary member cannot create a model/);
    assert.match(result.stdout, /switching from admin to a nonmember/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
