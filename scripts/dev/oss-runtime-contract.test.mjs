import assert from 'node:assert/strict';
import { test } from 'node:test';
import { assertLiveContract, assertOwnedStop, descendants, runCommand } from './oss-runtime-contract.mjs';
import { auditResults } from './oss-gate-results.mjs';

test('failed subprocess diagnostics preserve exit status without argv or stderr credentials', () => {
  assert.throws(() => runCommand(process.execPath, ['-e', 'console.error("private-fixture-token");process.exit(7)', '--', '--token', 'private-fixture-token']), error => {
    assert.equal(error.status, 7);
    assert.match(error.message, /failed \(exit 7\)/);
    assert.doesNotMatch(error.message, /private-fixture-token|--token/);
    return true;
  });
});

test('owned stop covers supervisor descendants and reparented registered listeners', () => {
  const table = [{ pid: 11, ppid: 10 }, { pid: 12, ppid: 11 }, { pid: 21, ppid: 1 }];
  assert.deepEqual(descendants(10, table), [10, 11, 12]);
  assert.deepEqual(assertOwnedStop({ registered: [10, 11, 12, 21], roots: [10], table,
    listeners: [12, 21], isAlive: () => true }), [10, 11, 12, 21]);
});

test('unknown listener or descendant rejects the complete stop plan', () => {
  for (const fixture of [
    { table: [], listeners: [99] },
    { table: [{ pid: 99, ppid: 10 }], listeners: [] },
  ]) {
    assert.throws(() => assertOwnedStop({ registered: [10], roots: [10], isAlive: () => true, ...fixture }), /Unknown process 99/);
  }
});

function liveFixture() {
  const input = { repo: '/sources/auraboot', frontend: true, backendArtifact: '/state/backend.jar', backendHash: 'abc', pluginDirectory: '/state/pf4j-plugins' };
  const report = { verdict: 'ok', artifacts: [{ key: 'backend', status: 'ok', path: input.backendArtifact, expected: { hash: 'abc' } }],
    environment: { SERVER_PORT: '6578', WEB_PORT: '5278', BFF_PORT: '6278', REDIS_DATABASE: '198', POSTGRES_DB: 'aura_boot_178' } };
  const backend = { command: `java -jar ${input.backendArtifact}`, cwd: '/sources/auraboot/platform', env: {
    AURA_PLUGINS_DIR: '/state/pf4j-plugins', AURA_BUILTIN_PLUGINS_DIR: '/sources/auraboot/plugins', SERVER_PORT: '6578', SPRING_DATA_REDIS_DATABASE: '198', SPRING_DATASOURCE_URL: 'jdbc:postgresql://127.0.0.1:5432/aura_boot_178?charSet=UTF8' } };
  const web = { cwd: '/sources/auraboot/web-admin', env: { SPRING_BOOT_URL: 'http://127.0.0.1:6578', BFF_INTERNAL_URL: 'http://127.0.0.1:6578', VITE_PORT: '5278', BFF_PORT: '6278' } };
  return { input, report, backend, web, bff: structuredClone(web) };
}

test('live contract accepts matching staged artifact and isolated service targets', () => {
  assert.doesNotThrow(() => assertLiveContract(liveFixture()));
});

for (const [name, mutate] of [
  ['source identity drift', f => { f.report.verdict = 'invalid'; }],
  ['artifact bytes changed', f => { f.report.artifacts[0].expected.hash = 'other'; }],
  ['unstaged executable', f => { f.backend.command = 'java -jar /old/backend.jar'; }],
  ['foreign plugin directory', f => { f.backend.env.AURA_PLUGINS_DIR = '/other/plugins'; }],
  ['foreign cwd', f => { f.backend.cwd = '/other/platform'; }],
  ['foreign database', f => { f.backend.env.SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5432/aura_boot'; }],
  ['foreign Redis namespace', f => { f.backend.env.SPRING_DATA_REDIS_DATABASE = '0'; }],
  ['foreign BFF upstream', f => { f.bff.env.BFF_INTERNAL_URL = 'http://127.0.0.1:6400'; }],
  ['foreign Vite port', f => { f.web.env.VITE_PORT = '5100'; }],
]) test(`live contract refuses ${name}`, () => {
  const fixture = liveFixture(); mutate(fixture);
  assert.throws(() => assertLiveContract(fixture));
});

const reportWith = (tests) => ({ suites: [{ suites: [{ specs: [{ tests }] }] }], errors: [] });
const passed = { expectedStatus: 'passed', results: [{ status: 'passed' }] };
test('structured gate audit counts real results, never collection as execution', () => {
  assert.deepEqual(auditResults(reportWith([passed])).counts, { collected: 1, executed: 1, passed: 1, failed: 0, skipped: 0, didNotRun: 0, retried: 0 });
  assert.equal(auditResults(reportWith([passed])).valid, true);
  for (const report of [reportWith([]), reportWith([{ results: [] }]), reportWith([{ results: [{ status: 'skipped' }] }]),
    reportWith([{ ...passed, results: [{ status: 'failed' }, { status: 'passed' }] }]),
    reportWith([{ expectedStatus: 'failed', results: [{ status: 'failed' }] }]),
    { ...reportWith([passed]), errors: [{ message: 'worker crashed' }] }]) {
    assert.equal(auditResults(report).valid, false);
  }
});
