import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, symlinkSync, rmSync, chmodSync, realpathSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fingerprintDirectory, verifyResumeState, loadResumeState } from '../lib/golden-resume-state.mjs';

// Pure identity controls and test-owned dependency fixtures; no product runtime is started.
function fixture() {
  const backend = { path: '/state/artifacts/backend.jar', hash: 'artifact-hash' };
  const dependencies = { root: '/dependencies', hash: 'dependency-hash' };
  const plugins = { root: '/plugins', hash: 'plugin-hash' };
  const plan = { schemaVersion: 1, runtime: 'owned', repo: '/oss', sourceCommit: 'commit', backend,
    ports: { backend: 6600, web: 5300, bff: 6300 }, llmStubMode: 'true',
    database: 'auraboot_200', redisDatabase: '5', dependencies, plugins };
  return { repo: '/oss', plan, planHash: 'plan-hash', dependencies: { ...dependencies }, plugins: { ...plugins },
    manifest: { runtime: 'owned', sourceCommit: 'commit', backendArtifact: { ...backend }, resumePlan: { hash: 'plan-hash' } },
    report: { verdict: 'ok', runtime: 'owned',
      sources: [{ key: 'auraboot', status: 'ok', expected: { root: '/oss', commit: 'commit' } }],
      artifacts: [{ key: 'backend', status: 'ok', path: backend.path, expected: { hash: backend.hash, sourceCommit: 'commit' } }],
      ports: Object.fromEntries(Object.entries(plan.ports).map(([key, port]) => [key, { port }])),
      environment: { POSTGRES_DB: plan.database, REDIS_DATABASE: plan.redisDatabase } } };
}
test('returns only the bound backend and retained LLM mode for unchanged launch inputs', () => {
  assert.deepEqual(verifyResumeState(fixture()), { backend: '/state/artifacts/backend.jar', llmStubMode: 'true' });
});
const mutations = {
  'Workspace drift': f => { f.report.verdict = 'invalid'; },
  'foreign runtime': f => { f.plan.runtime = 'foreign'; },
  'foreign root': f => { f.plan.repo = '/foreign'; },
  'unbound plan': f => { f.planHash = 'changed'; },
  'foreign manifest': f => { f.manifest.runtime = 'foreign'; },
  'manifest source drift': f => { f.manifest.sourceCommit = 'changed'; },
  'manifest backend drift': f => { f.manifest.backendArtifact.hash = 'changed'; },
  'source commit drift': f => { f.report.sources[0].expected.commit = 'changed'; },
  'source root drift': f => { f.report.sources[0].expected.root = '/foreign'; },
  'source missing': f => { f.report.sources = []; },
  'artifact bytes changed': f => { f.report.artifacts[0].expected.hash = 'changed'; },
  'artifact path changed': f => { f.report.artifacts[0].path = '/foreign.jar'; },
  'artifact source changed': f => { f.report.artifacts[0].expected.sourceCommit = 'changed'; },
  'artifact missing': f => { f.report.artifacts = []; },
  'LLM mode changed': f => { f.plan.llmStubMode = 'invalid'; },
  'port changed': f => { f.report.ports.web.port++; },
  'port absent on both sides': f => { delete f.report.ports.web; delete f.plan.ports.web; },
  'database changed': f => { f.report.environment.POSTGRES_DB = 'foreign'; },
  'database absent on both sides': f => { delete f.report.environment.POSTGRES_DB; delete f.plan.database; },
  'Redis namespace changed': f => { f.report.environment.REDIS_DATABASE = 'foreign'; },
  'dependency bytes changed': f => { f.dependencies.hash = 'changed'; },
  'dependency root changed': f => { f.dependencies.root = '/foreign'; },
  'plugin bytes changed': f => { f.plugins.hash = 'changed'; },
  'plugin root changed': f => { f.plugins.root = '/foreign'; },
};
for (const [label, mutate] of Object.entries(mutations)) test(`refuses ${label} before constructing launch arguments`, () => {
  const f = fixture(); mutate(f); assert.throws(() => verifyResumeState(f));
});
test('dependency fingerprints detect byte, mode and linked target changes with deterministic traversal', t => {
  const root = mkdtempSync(join(tmpdir(), 'golden-resume-dependencies-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const deps = join(root, 'deps'), target = join(root, 'target'); mkdirSync(deps); mkdirSync(target);
  writeFileSync(join(target, 'entry.js'), 'original'); symlinkSync(target, join(deps, 'package'));
  symlinkSync(deps, join(target, 'cycle'));
  const first = fingerprintDirectory(deps);
  assert.deepEqual(fingerprintDirectory(deps), first);
  writeFileSync(join(target, 'entry.js'), 'changed');
  assert.notEqual(fingerprintDirectory(deps).hash, first.hash);
  writeFileSync(join(target, 'entry.js'), 'original');
  assert.deepEqual(fingerprintDirectory(deps), first);
  chmodSync(join(target, 'entry.js'), 0o700);
  assert.notEqual(fingerprintDirectory(deps).hash, first.hash);
  chmodSync(join(target, 'entry.js'), 0o644);
  const other = join(root, 'other'); mkdirSync(other); writeFileSync(join(other, 'entry.js'), 'original');
  rmSync(join(deps, 'package')); symlinkSync(other, join(deps, 'package'));
  assert.notEqual(fingerprintDirectory(deps).hash, first.hash);
});

function privateFixture(t, lifecycle = 'resuming') {
  const temporary = mkdtempSync(join(tmpdir(), 'golden-resume-private-')), root = realpathSync(temporary);
  t.after(() => rmSync(temporary, { recursive: true, force: true }));
  const f = fixture(); f.repo = join(root, 'oss'); f.plan.repo = f.repo;
  f.report.sources[0].expected.root = f.repo; f.report.metadata = { lifecycle };
  f.report.stateDir = join(root, 'state');
  const deps = join(f.repo, 'web-admin/node_modules'), plugins = join(f.report.stateDir, 'golden/owned/pf4j-plugins');
  mkdirSync(deps, { recursive: true }); mkdirSync(plugins, { recursive: true });
  writeFileSync(join(deps, 'entry.js'), 'dependency'); writeFileSync(join(plugins, 'plugin.jar'), 'plugin');
  f.plan.dependencies = fingerprintDirectory(deps); f.plan.plugins = fingerprintDirectory(plugins);
  const bytes = JSON.stringify(f.plan, null, 2) + '\n';
  f.manifest.resumePlan.hash = createHash('sha256').update(bytes).digest('hex');
  const privateRoot = join(f.report.stateDir, 'runtimes/owned/oss-golden-stack');
  mkdirSync(privateRoot, { recursive: true });
  writeFileSync(join(privateRoot, 'resume.json'), bytes);
  writeFileSync(join(privateRoot, 'manifest.json'), JSON.stringify(f.manifest));
  const cli = join(root, 'aura');
  writeFileSync(cli, '#!/usr/bin/env node\n' + `console.log(${JSON.stringify(JSON.stringify(f.report))});\n`, { mode: 0o755 });
  return { f, cli, deps, plugins, privateRoot };
}
test('public loader accepts unchanged private fixtures only inside the lifecycle transaction', t => {
  const { f, cli } = privateFixture(t);
  assert.deepEqual(loadResumeState('owned', f.repo, cli), { backend: f.plan.backend.path, llmStubMode: 'true' });
});
test('public loader refuses an open runtime before any launch can begin', t => {
  const { f, cli } = privateFixture(t, 'open');
  assert.throws(() => loadResumeState('owned', f.repo, cli), /lifecycle transaction/);
});
for (const key of ['deps', 'plugins']) test(`public loader refuses modified retained ${key} bytes`, t => {
  const fixture = privateFixture(t);
  writeFileSync(join(fixture[key], key === 'deps' ? 'entry.js' : 'plugin.jar'), 'changed');
  assert.throws(() => loadResumeState('owned', fixture.f.repo, fixture.cli), /Retained launch input changed/);
});
test('public loader refuses an altered private launch recipe', t => {
  const { f, cli, privateRoot } = privateFixture(t);
  f.plan.llmStubMode = 'false'; writeFileSync(join(privateRoot, 'resume.json'), JSON.stringify(f.plan));
  assert.throws(() => loadResumeState('owned', f.repo, cli), /not bound/);
});

for (const action of ['suspend', 'resume']) test(`product ${action} entry delegates to Workspace and preserves its failure exit`, t => {
  const root = mkdtempSync(join(tmpdir(), 'golden-lifecycle-entry-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const calls = join(root, 'calls');
  writeFileSync(join(root, 'dev.sh'), '# fixture workspace marker\n');
  writeFileSync(join(root, 'aura'), '#!/usr/bin/env node\n' +
    `require('node:fs').appendFileSync(${JSON.stringify(calls)},JSON.stringify(process.argv.slice(2))+'\\n');process.exit(7);\n`,
  { mode: 0o755 });
  const result = spawnSync('bash', [fileURLToPath(new URL('../oss-golden-stack.sh', import.meta.url)), action, 'owned'],
    { env: { ...process.env, AURA_WORKSPACE_ROOT: root, AURA_WORKSPACE_STATE_DIR: join(root, 'state') }, encoding: 'utf8' });
  assert.equal(result.status, 7, result.stderr);
  assert.deepEqual(readFileSync(calls, 'utf8').trim().split('\n').map(line => JSON.parse(line)), [['runtime', action, 'owned']]);
});
