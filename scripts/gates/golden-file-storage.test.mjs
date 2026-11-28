import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chmodSync, linkSync, mkdtempSync, mkdirSync, readFileSync, readdirSync, readlinkSync, renameSync, rmSync,
  symlinkSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';
import * as storage from '../lib/golden-file-storage.mjs';
const { retainFileStorage } = storage;

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


const digest = value => createHash('sha256').update(value).digest('hex');
function primaryMigration(t) {
  const f = fixture(t); f.prepare();
  const originalPath = join(f.alias, 'original.bin'); writeFileSync(originalPath, 'original-application-bytes');
  const marker = join(dirname(f.persistent), 'owner.json');
  const before = { ...f.meta, AURA_RUNTIME_MODE: 'development', AURA_RUNTIME_LIFECYCLE: 'suspended',
    AURA_RUNTIME_SOURCE_BRANCH: 'fix/old-source', AURA_RUNTIME_SOURCE_COMMIT: 'a'.repeat(40) };
  const repo = '/owned/new-core';
  const after = { ...before, AURA_RUNTIME_SOURCE_ROOT: repo, AURA_RUNTIME_SOURCE_BRANCH: 'codex/new-source', AURA_RUNTIME_SOURCE_COMMIT: 'b'.repeat(40) };
  const content = values => Object.entries(values).map(([key, value]) => `${key}=${value}`).join('\n') + '\n';
  const beforeSources = `core\t${f.repo}\t${before.AURA_RUNTIME_SOURCE_BRANCH}\t${before.AURA_RUNTIME_SOURCE_COMMIT}\tclean\n`;
  const afterSources = `core\t${repo}\t${after.AURA_RUNTIME_SOURCE_BRANCH}\t${after.AURA_RUNTIME_SOURCE_COMMIT}\tclean\n`;
  const generation = '11111111-2222-4333-8444-555555555555';
  const entry = { version: 4, runtime: f.name, id: generation, runtimeRetention: 'metadata-only', completedAt: '2026-11-28T04:30:00.000Z',
    before: beforeSources, after: afterSources, beforeHash: digest(beforeSources), afterHash: digest(afterSources),
    primaryTransition: { specification: `core=${repo}`,
      previous: { key: 'core', root: f.repo, branch: before.AURA_RUNTIME_SOURCE_BRANCH, commit: before.AURA_RUNTIME_SOURCE_COMMIT, fingerprint: 'clean' },
      target: { root: repo, branch: after.AURA_RUNTIME_SOURCE_BRANCH, commit: after.AURA_RUNTIME_SOURCE_COMMIT },
      proof: { task: 'owned-task', owner: 'owned-session', repo: 'auraboot', registryDigest: 'c'.repeat(64) },
      allocation: f.allocation, beforeMeta: content(before), afterMeta: content(after) } };
  f.manager.evidenceDir = name => join(f.state, 'evidence', name);
  f.manager.sourceFile = name => join(f.state, 'runtime-sources', name + '.tsv');
  f.manager.metadataContent = content;
  f.manager.verifySources = () => true;
  f.manager.assertSourceRefreshStopped = () => {};
  Object.assign(f.meta, after);
  mkdirSync(f.manager.evidenceDir(f.name), { recursive: true });
  mkdirSync(dirname(f.manager.sourceFile(f.name)), { recursive: true });
  const history = join(f.manager.evidenceDir(f.name), 'source-history.json');
  const writeHistory = () => writeFileSync(history, JSON.stringify({ schemaVersion: 2, runtime: f.name, generations: [entry] }), { mode: 0o600 });
  writeHistory(); writeFileSync(f.manager.sourceFile(f.name), afterSources, { mode: 0o600 });
  const apply = extra => storage.rebindFileStorageSource(f.manager, f.name, repo,
    { generation, expected: digest(readFileSync(marker)), ...extra });
  return { ...f, newRepo: repo, marker, originalPath, entry, generation, writeHistory, apply };
}

test('explicit completed primary migration rebinds only the marker and preserves original file bytes and paths', t => {
  const f = primaryMigration(t), old = readFileSync(f.marker);
  assert.throws(() => retainFileStorage(f.manager, f.name, f.newRepo, { prepare: true }), /another lease/);
  const plan = f.apply({ dryRun: true });
  assert.equal(plan.changed, true); assert.deepEqual(readFileSync(f.marker), old);
  // Mirror source-refresh removing disposable state while persistent bytes survive.
  rmSync(f.runtime, { recursive: true });
  const result = f.apply(); assert.equal(result.changed, true);
  assert.equal(JSON.parse(readFileSync(f.marker)).repo, f.newRepo);
  mkdirSync(f.stack, { recursive: true });
  retainFileStorage(f.manager, f.name, f.newRepo, { prepare: true });
  assert.equal(readFileSync(f.originalPath, 'utf8'), 'original-application-bytes');
  assert.equal(f.apply().changed, false);
});

for (const [label, mutate] of Object.entries({
  'wrong generation': f => { f.generation = '22222222-2222-4333-8444-555555555555'; },
  'unfinished transition': f => { delete f.entry.completedAt; f.writeHistory(); },
  'source digest': f => { f.entry.afterHash = '0'.repeat(64); f.writeHistory(); },
  'different original source': f => { f.entry.primaryTransition.previous.root = '/unrelated/core'; f.writeHistory(); },
  'different allocation': f => { f.entry.primaryTransition.allocation = { ...f.allocation, slot: 177 }; f.writeHistory(); },
  'changed lease': f => { f.meta.AURA_RUNTIME_CREATED_AT = 'new-lease'; },
  'live process': f => { f.manager.assertSourceRefreshStopped = () => { throw new Error('live-owned-process'); }; },
  'source drift': f => { f.manager.verifySources = () => false; },
})) test(`primary file rebind rejects ${label} without changing metadata or payload`, t => {
  const f = primaryMigration(t), marker = readFileSync(f.marker); mutate(f);
  assert.throws(() => f.apply({ generation: f.generation }));
  assert.deepEqual(readFileSync(f.marker), marker);
  assert.equal(readFileSync(f.originalPath, 'utf8'), 'original-application-bytes');
});

test('primary file rebind rejects a stale marker token before changing its owner', t => {
  const f = primaryMigration(t), before = readFileSync(f.marker);
  assert.throws(() => f.apply({ expected: '0'.repeat(64) }), /compare-and-swap/);
  assert.deepEqual(readFileSync(f.marker), before);
});
