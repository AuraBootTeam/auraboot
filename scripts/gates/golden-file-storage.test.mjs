import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chmodSync, linkSync, mkdtempSync, mkdirSync, readFileSync, readdirSync, readlinkSync, renameSync, rmSync,
  symlinkSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { retainFileStorage } from '../lib/golden-file-storage.mjs';

// Filesystem-only controls; native download survival is verified separately.
function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'golden-files-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const name = 'owned', repo = '/owned/core', state = join(root, '.workspace');
  const runtime = join(state, 'runtimes', name), stack = join(runtime, 'oss-stack');
  mkdirSync(stack, { recursive: true });
  const meta = { AURA_RUNTIME_NAME: name, AURA_RUNTIME_REPO: 'auraboot', AURA_RUNTIME_SLOT: '176',
    AURA_RUNTIME_WORKSPACE: root, AURA_RUNTIME_SOURCE_ROOT: repo, AURA_RUNTIME_CREATED_AT: 'lease-start' };
  const allocation = { name, repo: 'auraboot', slot: 176 };
  const manager = { root, state, runtimeDir: n => join(state, 'runtimes', n),
    requireAllocation: () => allocation, requireMeta: () => meta,
    assertPortsStopped() {}, assertNoGradleClient() {} };
  const alias = join(stack, 'file-storage'), persistent = join(state, 'runtime-data', name, 'files');
  return { root, state, name, repo, runtime, stack, meta, allocation, manager, alias, persistent,
    prepare: () => retainFileStorage(manager, name, repo, { prepare: true }),
    verify: () => retainFileStorage(manager, name, repo) };
}

test('moves old bytes without changing the absolute path used by stored metadata', t => {
  const f = fixture(t), bytes = Buffer.from([0, 10, 255, 32]);
  mkdirSync(join(f.alias, 'nested'), { recursive: true });
  const oldPath = join(f.alias, 'nested', 'original.bin'); writeFileSync(oldPath, bytes);
  const before = readFileSync(oldPath); f.prepare();
  assert.equal(readlinkSync(f.alias), f.persistent);
  assert.deepEqual(readFileSync(oldPath), before);
  assert.deepEqual(readFileSync(join(f.persistent, 'nested', 'original.bin')), bytes);
  assert.equal(f.verify().directory, f.persistent);
  f.prepare(); assert.deepEqual(readFileSync(oldPath), bytes);
});

test('payload survives metadata-only runtime removal and alias reconstruction', t => {
  const f = fixture(t); f.prepare();
  const oldPath = join(f.alias, 'original.bin'); writeFileSync(oldPath, 'retained-bytes');
  // This is the same rename/remove shape used by the original source-refresh.
  const archived = join(f.root, 'metadata-only'); renameSync(f.runtime, archived);
  rmSync(archived, { recursive: true });
  assert.equal(readFileSync(join(f.persistent, 'original.bin'), 'utf8'), 'retained-bytes');
  mkdirSync(f.stack, { recursive: true }); f.prepare();
  assert.equal(readFileSync(oldPath, 'utf8'), 'retained-bytes');
});

test('resumes interrupted preparation after directory move but before alias creation', t => {
  const f = fixture(t); f.prepare(); writeFileSync(join(f.alias, 'item'), 'original');
  rmSync(f.alias); f.prepare();
  assert.equal(readFileSync(join(f.alias, 'item'), 'utf8'), 'original');
});

test('read-only verification does not initialize a missing store', t => {
  const f = fixture(t); assert.throws(f.verify);
  assert.deepEqual(readdirSync(f.state), ['runtimes']);
});

for (const [label, mutation] of Object.entries({
  'runtime metadata identity': f => { f.meta.AURA_RUNTIME_NAME = 'other'; },
  'allocation slot': f => { f.allocation.slot = 177; },
  'source owner': f => { f.meta.AURA_RUNTIME_SOURCE_ROOT = '/other'; },
  'lease lifetime': f => { f.meta.AURA_RUNTIME_CREATED_AT = 'different-start'; },
})) test(`rejects changed ${label} without modifying original bytes`, t => {
  const f = fixture(t); f.prepare(); writeFileSync(join(f.alias, 'item'), 'original');
  mutation(f); assert.throws(f.prepare);
  assert.equal(readFileSync(join(f.alias, 'item'), 'utf8'), 'original');
});

test('source commit changes do not change the allocation lifetime binding', t => {
  const f = fixture(t); f.meta.AURA_RUNTIME_SOURCE_COMMIT = 'before'; f.prepare();
  f.meta.AURA_RUNTIME_SOURCE_COMMIT = 'after'; assert.doesNotThrow(f.prepare);
});

test('rejects a foreign alias and never touches its target', t => {
  const f = fixture(t), other = join(f.root, 'foreign'); mkdirSync(other);
  writeFileSync(join(other, 'item'), 'foreign'); symlinkSync(other, f.alias);
  assert.throws(f.prepare, /another directory/);
  assert.equal(readFileSync(join(other, 'item'), 'utf8'), 'foreign');
});

test('rejects unowned nonempty persistent data instead of adopting it', t => {
  const f = fixture(t); mkdirSync(f.persistent, { recursive: true });
  writeFileSync(join(f.persistent, 'item'), 'unowned');
  assert.throws(f.prepare, /Unowned/);
  assert.equal(readFileSync(join(f.persistent, 'item'), 'utf8'), 'unowned');
});

test('rejects simultaneous real legacy and durable directories without merging or deleting', t => {
  const f = fixture(t); f.prepare(); writeFileSync(join(f.alias, 'durable'), 'kept');
  rmSync(f.alias); mkdirSync(f.alias); writeFileSync(join(f.alias, 'old'), 'also-kept');
  assert.throws(f.prepare, /Ambiguous/);
  assert.equal(readFileSync(join(f.alias, 'old'), 'utf8'), 'also-kept');
  assert.equal(readFileSync(join(f.persistent, 'durable'), 'utf8'), 'kept');
});

test('rejects nested payload links before moving the source directory', t => {
  const f = fixture(t); mkdirSync(f.alias); symlinkSync(f.root, join(f.alias, 'escape'));
  assert.throws(f.prepare, /symbolic links/);
  assert.equal(readlinkSync(join(f.alias, 'escape')), f.root);
});

test('rejects an ownership marker link', t => {
  const f = fixture(t); f.prepare();
  const marker = join(dirname(f.persistent), 'owner.json'), bytes = readFileSync(marker);
  const foreign = join(f.root, 'marker'); writeFileSync(foreign, bytes);
  rmSync(marker); symlinkSync(foreign, marker);
  assert.throws(f.prepare); assert.deepEqual(readFileSync(foreign), bytes);
});

for (const probe of ['assertPortsStopped', 'assertNoGradleClient']) test(`preparation requires ${probe}`, t => {
  const f = fixture(t); f.manager[probe] = () => { throw new Error('active-owned-work'); };
  assert.throws(f.prepare, /active-owned-work/);
  assert.deepEqual(readdirSync(f.state), ['runtimes']);
});

test('rejects a nonprivate existing marker without repairing permissions', t => {
  const f = fixture(t); f.prepare();
  chmodSync(join(dirname(f.persistent), 'owner.json'), 0o644);
  assert.throws(f.verify, /nonprivate/);
});

test('rejects an existing world-writable persistent directory', t => {
  const f = fixture(t); f.prepare(); chmodSync(f.persistent, 0o777);
  assert.throws(f.verify, /permissions/);
});

test('rejects a shared hardlinked payload instead of claiming exclusive ownership', t => {
  const f = fixture(t); f.prepare(); writeFileSync(join(f.persistent, 'item'), 'bytes');
  linkSync(join(f.persistent, 'item'), join(f.root, 'external-link'));
  assert.throws(f.verify, /shared/);
  assert.equal(readFileSync(join(f.root, 'external-link'), 'utf8'), 'bytes');
});
