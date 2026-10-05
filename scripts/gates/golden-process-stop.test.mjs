import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import net from 'node:net';
import { planGoldenStop, executeGoldenStop, stopGoldenProcesses, stableGoldenLaunch, registerGoldenSupervisor, verifyGoldenStopOwnership, readGoldenProcessSnapshot } from '../lib/golden-process-stop.mjs';

// Workspace API is mocked; OS fixtures belong only to this test, with no product runtime.
const supervisor = 1000000010, backend = 1000000020, child = 1000000011;
function fixture() {
  const make = (pid, parent, cwd = '/owned/oss/web-admin') => ({ pid, parent, cwd,
    runtime: 'owned', token: 'fixture-token', commandHash: `hash-${pid}`, startedAt: 'fixture-start' });
  return { runtime: 'owned', repo: '/owned/oss', token: 'fixture-token', roots: [supervisor, backend],
    snapshots: [make(child, supervisor), make(backend, 1, '/owned/oss/platform'), make(supervisor, 1)],
    listeners: [child, backend] };
}
test('plans supervisor-first shutdown and omits secrets from the plan', () => {
  const plan = planGoldenStop(fixture());
  assert.deepEqual(plan.map(item => item.pid), [supervisor, backend, child]);
  assert.doesNotMatch(JSON.stringify(plan), /fixture-token|"token"/);
});
test('rechecks each process generation before signaling only owned targets', () => {
  const f = fixture(); const calls = [];
  const plan = planGoldenStop(f);
  executeGoldenStop(plan, pid => ({ ...f.snapshots.find(item => item.pid === pid), ownershipVerified: true }),
    (...args) => calls.push(args));
  assert.deepEqual(calls, [[supervisor, 'SIGKILL'], [backend, 'SIGKILL'], [child, 'SIGKILL']]);
});
const mutations = {
  'missing token': f => { f.token = ''; },
  'foreign runtime': f => { f.snapshots[0].runtime = 'foreign'; },
  'foreign token': f => { f.snapshots[0].token = 'foreign'; },
  'foreign cwd': f => { f.snapshots[0].cwd = '/foreign'; },
  'unowned ancestry': f => { f.snapshots[0].parent = 999; },
  'cyclic ancestry': f => { f.snapshots[0].parent = child; },
  'unknown listener': f => { f.listeners.push(999); },
  'invalid target PID': f => { f.snapshots[0].pid = 0; },
  'invalid root PID': f => { f.roots[0] = 0; },
  'caller PID': f => { f.snapshots[0].pid = process.pid; },
  'missing start time': f => { f.snapshots[0].startedAt = ''; },
  'missing command identity': f => { f.snapshots[0].commandHash = ''; },
  'duplicate snapshot': f => { f.snapshots.push({ ...f.snapshots[0] }); },
};
for (const [label, mutate] of Object.entries(mutations)) test(`refuses ${label} before any stop plan exists`, () => {
  const f = fixture(); mutate(f); assert.throws(() => planGoldenStop(f));
});
for (const key of ['pid', 'cwd', 'commandHash', 'startedAt', 'runtime', 'ownershipVerified']) {
  test(`refuses a changed ${key} immediately before the signal`, () => {
    const f = fixture(); const calls = []; const plan = planGoldenStop(f);
    assert.throws(() => executeGoldenStop(plan, pid => {
      const snapshot = { ...f.snapshots.find(item => item.pid === pid), ownershipVerified: true };
      snapshot[key] = key === 'pid' ? 999 : key === 'ownershipVerified' ? false : 'foreign';
      return snapshot;
    }, (...args) => calls.push(args)));
    assert.deepEqual(calls, []);
  });
}
test('ignores a metadata probe failure only after the target has actually exited', () => {
  let live = true;
  const result = readGoldenProcessSnapshot(child, () => {
    live = false;
    throw new Error('ps exited while the owned child was disappearing');
  }, () => live);
  assert.equal(result, null);
});
test('retains a live process when its metadata probe fails', () => {
  const calls = [];
  const failure = new Error('live ps permission failure');
  assert.throws(() => executeGoldenStop(planGoldenStop(fixture()),
    pid => readGoldenProcessSnapshot(pid, () => { throw failure; }, () => true),
    (...args) => calls.push(args)), error => error === failure);
  assert.deepEqual(calls, []);
});
test('never probes or signals an already exited target', () => {
  let probes = 0;
  assert.equal(readGoldenProcessSnapshot(child, () => { probes++; }, () => false), null);
  assert.equal(probes, 0);
});

test('does not signal a disappeared process', () => {
  const calls = [];
  executeGoldenStop(planGoldenStop(fixture()), () => null, (...args) => calls.push(args));
  assert.deepEqual(calls, []);
});
test('admits an unmarked intermediate shell only through complete owned ancestry', () => {
  const f = fixture();
  f.snapshots[0] = { ...f.snapshots[0], runtime: undefined, token: undefined, executable: 'sh' };
  f.listeners = [backend];
  const plan = planGoldenStop(f);
  assert.equal(plan.find(item => item.pid === child).inheritedOwnership, true);
  for (const change of [
    { runtime: 'foreign' }, { token: 'foreign' }, { executable: 'node' },
    { parent: 999 }, { cwd: '/foreign' },
  ]) {
    const altered = { ...f, snapshots: f.snapshots.map(item => item.pid === child ? { ...item, ...change } : item) };
    assert.throws(() => planGoldenStop(altered));
  }
  assert.throws(() => planGoldenStop({ ...f, roots: [child, backend] }));
  assert.throws(() => planGoldenStop({ ...f, listeners: [child, backend] }));
  const expected = plan.find(item => item.pid === child);
  assert.equal(verifyGoldenStopOwnership(f.snapshots[0], expected, f.token), true);
  for (const change of [{ runtime: 'foreign' }, { token: 'foreign' }, { token: f.token }, { executable: 'node' }]) {
    assert.equal(verifyGoldenStopOwnership({ ...f.snapshots[0], ...change }, expected, f.token), false);
  }
});
test('never signals a recycled child PID after already stopping its owned supervisor', () => {
  const f = fixture(); const calls = []; const plan = planGoldenStop(f);
  assert.throws(() => executeGoldenStop(plan, pid => ({ ...f.snapshots.find(item => item.pid === pid),
    startedAt: pid === child ? 'new-generation' : 'fixture-start', ownershipVerified: true }),
  (...args) => calls.push(args)));
  assert.deepEqual(calls, [[supervisor, 'SIGKILL'], [backend, 'SIGKILL']]);
});

async function processFixture(t, runtime, workdir = 'platform') {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'golden-stop-boundary-'));
  const root = fs.realpathSync(temporary), repo = path.join(root, 'oss'), state = path.join(root, 'state');
  fs.mkdirSync(path.join(repo, 'platform'), { recursive: true });
  fs.mkdirSync(path.join(repo, 'web-admin'), { recursive: true });
  fs.mkdirSync(path.join(state, 'runtimes/owned/processes'), { recursive: true });
  fs.writeFileSync(path.join(state, 'runtimes/owned/processes/ownership.token'), 'fixture-token\n');
  const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
    cwd: path.join(repo, workdir), stdio: 'ignore',
    env: { ...process.env, AURA_RUNTIME_NAME: runtime, AURA_RUNTIME_OWNERSHIP_TOKEN: 'fixture-token' },
  });
  await new Promise((resolve, reject) => { child.once('spawn', resolve); child.once('error', reject); });
  t.after(async () => {
    if (child.exitCode === null && child.signalCode === null) {
      const exited = new Promise(resolve => child.once('exit', resolve)); child.kill('SIGKILL'); await exited;
    }
    fs.rmSync(temporary, { recursive: true, force: true });
  });
  const report = { runtime: 'owned', stateDir: state,
    sources: [{ key: 'auraboot', expected: { root: repo } }],
    processes: [{ key: 'backend', expected: { pid: child.pid } }], ports: {} };
  const cli = path.join(root, 'aura'), calls = path.join(root, 'calls');
  fs.writeFileSync(cli, '#!/usr/bin/env node\n' +
    `const fs=require('node:fs'); const args=process.argv.slice(2);\n` +
    `fs.appendFileSync(${JSON.stringify(calls)},JSON.stringify(args)+'\\n');\n` +
    `if(args[1]==='show')console.log(${JSON.stringify(JSON.stringify(report))});\n`, { mode: 0o755 });
  return { child, repo, cli, calls, report, root };
}
test('public-entry boundary stops a test-owned live process after checking the Workspace API', async t => {
  const f = await processFixture(t, 'owned');
  const result = stopGoldenProcesses('owned', f.repo, f.cli);
  await new Promise(resolve => f.child.once('exit', resolve));
  assert.equal(f.child.signalCode, 'SIGKILL');
  assert.equal(result.length, 1); assert.equal(result[0].pid, f.child.pid);
  assert.match(fs.readFileSync(f.calls, 'utf8'), /"process","check","owned","backend"/);
});

test('launch registration rejects the transient env executable and unstable generations', () => {
  const previous = { cwd: '/oss/web-admin', runtime: 'owned', token: 'fixture-token',
    executable: 'node', commandHash: 'hash', startedAt: 'start' };
  const expected = { cwd: '/oss/web-admin', runtime: 'owned', token: 'fixture-token', executable: 'node' };
  assert.equal(stableGoldenLaunch({ ...previous }, previous, expected), true);
  assert.equal(stableGoldenLaunch({ ...previous }, null, expected), false);
  for (const [key, value] of Object.entries({ executable: 'env', commandHash: 'changed', startedAt: 'changed',
    cwd: '/foreign', runtime: 'foreign', token: 'foreign' })) {
    assert.equal(stableGoldenLaunch({ ...previous, [key]: value }, previous, expected), false, key);
  }
});
test('registers a stable test-owned Node supervisor through the public Workspace entry', async t => {
  const f = await processFixture(t, 'owned', 'web-admin');
  await registerGoldenSupervisor('owned', f.repo, f.cli, f.child.pid);
  const calls = fs.readFileSync(f.calls, 'utf8').trim().split('\n').map(line => JSON.parse(line));
  assert.deepEqual(calls.at(-1).slice(0, 5), ['runtime', 'process', 'register', 'owned', '--key']);
  assert.equal(calls.at(-1)[5], 'frontend-launch');
  assert.equal(calls.at(-1)[7], String(f.child.pid));
  assert.equal(f.child.signalCode, null);
});
test('public-entry boundary refuses a foreign marker and leaves the test-owned process alive', async t => {
  const f = await processFixture(t, 'foreign');
  assert.throws(() => stopGoldenProcesses('owned', f.repo, f.cli), /Foreign process runtime ownership/);
  assert.equal(f.child.signalCode, null); assert.equal(f.child.exitCode, null);
  assert.doesNotThrow(() => process.kill(f.child.pid, 0));
});

test('refuses a reachable listener whose PID is invisible before signaling any fixture', async t => {
  const f = await processFixture(t, 'owned');
  const server = net.createServer(socket => socket.end());
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  f.report.ports.backend = { port: server.address().port };
  const cliSource = fs.readFileSync(f.cli, 'utf8');
  fs.writeFileSync(f.cli, cliSource.replace(JSON.stringify(JSON.stringify({ ...f.report, ports: {} })),
    JSON.stringify(JSON.stringify(f.report))));
  const realLsof = execFileSync('which', ['lsof'], { encoding: 'utf8' }).trim();
  fs.writeFileSync(path.join(f.root, 'lsof'), '#!/usr/bin/env node\n' +
    `if(process.argv.includes('-sTCP:LISTEN'))process.exit(1);\n` +
    `const r=require('node:child_process').spawnSync(${JSON.stringify(realLsof)},process.argv.slice(2),{stdio:'inherit'});process.exit(r.status??2);\n`,
  { mode: 0o755 });
  const previousPath = process.env.PATH;
  try {
    process.env.PATH = `${f.root}${path.delimiter}${previousPath}`;
    assert.throws(() => stopGoldenProcesses('owned', f.repo, f.cli), /Unidentified listener/);
  } finally { process.env.PATH = previousPath; }
  assert.equal(f.child.signalCode, null); assert.equal(f.child.exitCode, null);
  assert.doesNotThrow(() => process.kill(f.child.pid, 0));
});
