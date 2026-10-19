import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = fileURLToPath(new URL('../web-admin/', import.meta.url));
const specialized = [
  'project-delivery-automation-dashboard.spec.ts',
  'customer-bpm-roundtrip.spec.ts',
  'customer-delivery-dashboard.spec.ts',
  'customer-delivery-permissions-real.spec.ts',
  'oss-performance.spec.ts',
  'automation-designer-golden.spec.ts',
  'automation-deep.spec.ts',
];

function collect(config, overrides = {}, projects = [], files = []) {
  const root = mkdtempSync(join(tmpdir(), 'oss-profile-contract-'));
  try {
    const result = spawnSync('pnpm', ['exec', 'playwright', 'test', '-c', config,
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
  const actual = caseIdentities(report).filter(row => !row.includes('workflow-application.setup.ts'));
  assert.ok(actual.length > 0);
  assert.deepEqual(actual, caseIdentities(JSON.parse(originals.stdout)));
  assert.match(workflow.stdout, /requires the real BPM application/);
});

test('workflow profile rejects implicit runtime and authentication', () => {
  const result = collect('playwright.workflow.config.ts', { PW_ADMIN_STORAGE_STATE: '', PLAYWRIGHT_BASE_URL: '' });
  assert.notEqual(result.status, 0);
  assert.match(result.stdout + result.stderr, /Workflow tests require an explicit owned/);
});
