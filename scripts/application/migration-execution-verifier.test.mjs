import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { mkdtempSync, copyFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { verifyMigrationExecution } from './migration-execution-verifier.mjs';

const bytes = value => Buffer.from(JSON.stringify(value));
const digest = value => `sha256:${createHash('sha256').update(value).digest('hex')}`;
function fixture() {
  const context = { deploymentId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', generation: 3,
    action: 'migrate', payloadSha256: 'a'.repeat(64), database: { host: 'db.internal', port: 5432, name: 'release_db' } };
  const started = { schemaVersion: 1, kind: 'migration-execution', ...context,
    state: 'started', startedAt: '2026-09-26T01:00:00.000Z' };
  const result = { schemaVersion: 1, kind: 'migration-execution-result', startedDigest: digest(bytes(started)),
    state: 'succeeded', exitCode: 0, failureKind: null, completedAt: '2026-09-26T01:00:01.000Z' };
  const expected = { ...structuredClone(context), startedDigest: digest(bytes(started)), resultDigest: digest(bytes(result)) };
  return { started, result, expected };
}
const verify = f => verifyMigrationExecution(bytes(f.started), bytes(f.result), f.expected);
const pinResult = f => { f.expected.resultDigest = digest(bytes(f.result)); };

test('standalone CLI needs no installed packages and distinguishes success, failure and invalid context', () => {
  const root = mkdtempSync(join(tmpdir(), 'migration-consumer-cli-'));
  copyFileSync(new URL('./migration-execution-verifier.mjs', import.meta.url), join(root, 'verify.mjs'));
  const f = fixture();
  const run = () => {
    for (const name of ['started', 'result', 'expected']) writeFileSync(join(root, `${name}.json`), bytes(f[name]));
    return spawnSync(process.execPath, [join(root, 'verify.mjs'), ...['started', 'result', 'expected'].map(name => join(root, `${name}.json`))],
      { encoding: 'utf8', timeout: 10000, env: {} });
  };
  let result = run(); assert.equal(result.status, 0, result.stderr);
  assert.equal(JSON.parse(result.stdout).executionSucceeded, true);
  Object.assign(f.result, { state: 'failed', exitCode: 1, failureKind: 'engine-exit-nonzero' }); pinResult(f);
  result = run(); assert.equal(result.status, 1, result.stderr);
  assert.equal(JSON.parse(result.stdout).executionSucceeded, false);
  f.expected.generation++;
  result = run(); assert.notEqual(result.status, 0); assert.match(result.stderr, /target context/);
  assert.equal(result.stdout, '');
});

test('valid success and failure remain distinct; returned context is detached', () => {
  const f = fixture(); const success = verify(f);
  assert.equal(success.executionSucceeded, true);
  success.database.name = 'mutated'; assert.equal(f.expected.database.name, 'release_db');
  for (const failureKind of ['engine-launch-failed', 'engine-signaled', 'engine-exit-nonzero']) {
    Object.assign(f.result, { state: 'failed', exitCode: 1, failureKind }); pinResult(f);
    assert.equal(verify(f).executionSucceeded, false);
    assert.equal(verify(f).failureKind, failureKind);
  }
});

test('stale generation, other target, action or payload cannot reuse a receipt', () => {
  for (const change of [{ generation: 4 }, { deploymentId: '01ARZ3NDEKTSV4RRFFQ69G5FAW' },
    { action: 'validate' }, { payloadSha256: 'b'.repeat(64) }]) {
    const f = fixture(); Object.assign(f.expected, change); assert.throws(() => verify(f), /target context/);
  }
  for (const [key, value] of [['host', 'other.internal'], ['port', 5433], ['name', 'other_db']]) {
    const f = fixture(); f.expected.database[key] = value; assert.throws(() => verify(f), /target context/);
  }
});

test('both byte identities and the result-to-start link must match', () => {
  const f = fixture();
  assert.throws(() => verifyMigrationExecution(Buffer.concat([bytes(f.started), Buffer.from('\n')]), bytes(f.result), f.expected), /pinned digests/);
  f.result.completedAt = '2026-09-26T01:00:02.000Z'; assert.throws(() => verify(f), /pinned digests/);
  f.result.startedDigest = 'sha256:' + 'b'.repeat(64); pinResult(f);
  assert.throws(() => verify(f), /another execution/);
});

test('malformed or contradictory outcomes cannot become successful evidence', () => {
  for (const change of [{ state: 'succeeded', exitCode: 1 }, { failureKind: 'engine-signaled' },
    { state: 'failed' }, { completedAt: '2026-02-30T01:00:00.000Z' }, { exitCode: false },
    { admitted: true }, { schemaVersion: 2 }]) {
    const f = fixture(); Object.assign(f.result, change); pinResult(f); assert.throws(() => verify(f));
  }
  for (const change of [{ generation: true }, { generation: 9007199254740992 }, { startedDigest: null }]) {
    const f = fixture(); Object.assign(f.expected, change); assert.throws(() => verify(f), /pinned migration context/);
  }
  const f = fixture(); assert.throws(() => verifyMigrationExecution(bytes(f.started), Buffer.alloc(0), f.expected));
  assert.throws(() => verifyMigrationExecution(bytes(f.started), Buffer.alloc(16385), f.expected));
});
