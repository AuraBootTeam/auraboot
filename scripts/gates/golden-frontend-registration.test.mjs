import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import { mkdtempSync, rmSync, realpathSync } from 'node:fs';
import { join } from 'node:path';
import { assertFrontendLineage, frontendProcessKey, registerFrontendProcess } from '../lib/golden-frontend-registration.mjs';

function fixture(repo = '/owned/oss') {
  return { pid: 110, supervisor: 100, repo, runtime: 'owned', token: 'unit-only-token', snapshots: [
    { pid: 110, parent: 105, cwd: join(repo, 'web-admin'), executable: 'node', startedAt: 'new', commandHash: 'child', runtime: 'owned', token: 'unit-only-token' },
    { pid: 105, parent: 100, cwd: join(repo, 'web-admin'), executable: 'sh', startedAt: 'bridge', commandHash: 'shell' },
    { pid: 100, parent: 1, cwd: join(repo, 'web-admin'), executable: 'node', startedAt: 'parent', commandHash: 'supervisor', runtime: 'owned', token: 'unit-only-token' },
  ] };
}
test('only actual development and production listener entrypoints register, not pnpm or tsx helpers', () => {
  const cwd = '/owned/oss/web-admin', repo = '/owned/oss';
  assert.equal(frontendProcessKey('app/server/bff.server.ts', cwd, repo), 'bff');
  assert.equal(frontendProcessKey('/capsule/@react-router/dev/bin.js', cwd, repo), 'web');
  assert.equal(frontendProcessKey('scripts/production-entry.mjs', cwd, repo), 'web');
  for (const entry of ['node_modules/tsx/dist/cli.mjs', '/opt/bin/pnpm', '../other/app/server/bff.server.ts', undefined]) {
    assert.equal(frontendProcessKey(entry, cwd, repo), null);
  }
  assert.equal(frontendProcessKey('app/server/bff.server.ts', '/foreign/web-admin', repo), null);
});
test('authenticates a replacement listener through its stable supervisor, allowing only an internal shell bridge', () => {
  assert.doesNotThrow(() => assertFrontendLineage(fixture()));
});
for (const [label, mutate] of Object.entries({
  'wrong token': f => { f.snapshots[0].token = 'foreign'; },
  'wrong runtime': f => { f.snapshots[0].runtime = 'foreign'; },
  'wrong cwd': f => { f.snapshots[0].cwd = '/foreign'; },
  'foreign parent': f => { f.snapshots[0].parent = 999; },
  'cyclic ancestry': f => { f.snapshots[0].parent = 110; },
  'duplicate PID': f => { f.snapshots.push(f.snapshots[0]); },
  'missing generation': f => { f.snapshots[0].startedAt = ''; },
  'missing command': f => { f.snapshots[0].commandHash = ''; },
  'unmarked node': f => { f.snapshots[1].executable = 'node'; },
  'partially marked shell': f => { f.snapshots[1].runtime = 'owned'; },
  'unmarked listener': f => { delete f.snapshots[0].runtime; delete f.snapshots[0].token; f.snapshots[0].executable = 'sh'; },
  'unmarked supervisor': f => { delete f.snapshots[2].runtime; delete f.snapshots[2].token; },
  'unrelated snapshot': f => { f.snapshots.push({ ...f.snapshots[0], pid: 500 }); },
  'invalid supervisor': f => { f.supervisor = 0; },
  'self supervisor': f => { f.supervisor = f.pid; },
})) test(`replacement registration rejects ${label}`, () => {
  const f = fixture(); mutate(f); assert.throws(() => assertFrontendLineage(f));
});

// Protocol/unit tests: real OS and Workspace CLI lifecycle proof runs separately on the owned runtime.
for (const failure of [null, 'live-old', 'wrong-root', 'unregistered-supervisor', 'recycled-supervisor', 'foreign-token']) {
  test(`registration ${failure ? 'refuses '+failure : 'replaces a dead generation before returning to listener startup'}`, () => {
    const repo = realpathSync(mkdtempSync(join(os.tmpdir(), 'golden-registration-')));
    const f = fixture(repo), calls = [];
    const priorToken = process.env.AURA_RUNTIME_OWNERSHIP_TOKEN;
    process.env.AURA_RUNTIME_OWNERSHIP_TOKEN = f.token;
    try {
      const report = { runtime: f.runtime, sources: [{ key: 'core', expected: { root: failure === 'wrong-root' ? '/foreign' : repo } }],
        processes: [{ key: 'frontend-launch', expected: { pid: failure === 'unregistered-supervisor' ? 999 : 100 } },
          { key: 'web', expected: { pid: 90 } }] };
      if (failure === 'foreign-token') f.snapshots[0].token = 'foreign';
      const action = () => registerFrontendProcess({ runtime: f.runtime, repo, cli: '/unit-cli', supervisor: 100, pid: 110, key: 'web',
        snapshot: pid => f.snapshots.find(item => item.pid === pid) ?? (failure === 'live-old' && pid === 90 ? {} : null),
        call: (_, args) => {
          calls.push(args);
          if (args[1] === 'show') return JSON.stringify(report);
          if (args[2] === 'check' && failure === 'recycled-supervisor') throw new Error('generation changed');
          return '';
        } });
      if (failure) {
        assert.throws(action);
        assert.equal(calls.some(args => args[2] === 'register'), false);
      } else {
        action();
        assert.deepEqual(calls.at(-1), ['runtime', 'process', 'register', 'owned', '--key', 'web', '--pid', '110', '--token', f.token]);
        assert.ok(calls.some(args => args[2] === 'check' && args.at(-1) === 'frontend-launch'));
      }
    } finally {
      if (priorToken === undefined) delete process.env.AURA_RUNTIME_OWNERSHIP_TOKEN;
      else process.env.AURA_RUNTIME_OWNERSHIP_TOKEN = priorToken;
      rmSync(repo, { recursive: true, force: true });
    }
  });
}
