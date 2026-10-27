import test from 'node:test';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { run, validateManifest } from './memory-embedding-maintenance.mjs';

const manifest = { baseUrl: 'http://127.0.0.1:6464', tenantId: '373339825559965696', agentCode: 'test', expectedSpace: 'a'.repeat(64), pids: ['one', 'two'], reason: 'isolated test' };
function fixture() {
  const states = new Map(manifest.pids.map(pid => [pid, 'unknown_space'])), writes = [];
  return { states, writes, request: async (url, options) => {
    assert.equal(options.redirect, 'error');
    const pid = new URL(url).pathname.split('/')[5];
    if (options.method === 'POST') { states.set(pid, 'current'); writes.push(pid); }
    return { ok: true, status: 200, json: async () => ({ code: '0', data: {
      tenantId: manifest.tenantId, agentCode: manifest.agentCode, expectedSpace: manifest.expectedSpace, pid,
      status: states.get(pid), outcome: 'rebuilt', auditSequence: '123',
    } }) };
  } };
}
async function withFile(fn) { const dir = mkdtempSync(join(tmpdir(), 'memory-maintenance-')); try { await fn(join(dir, 'checkpoint.json')); } finally { rmSync(dir, { recursive: true }); } }

test('bounded checkpoint resume writes only unfinished targets and checks prior acknowledgements', async () => withFile(async path => {
  const f = fixture();
  assert.equal((await run(manifest, path, { ...f, token: 'test', maxItems: 1 })).status, 'partial');
  assert.deepEqual(f.writes, ['one']);
  assert.equal((await run(manifest, path, { ...f, token: 'test', resume: true })).status, 'complete');
  assert.deepEqual(f.writes, ['one', 'two']);
  assert.equal((await run(manifest, path, { ...f, token: 'test', resume: true })).status, 'complete');
  assert.deepEqual(f.writes, ['one', 'two']);
  assert.doesNotMatch(readFileSync(path, 'utf8'), /Bearer|Authorization/);
}));
test('checkpoint refuses changed targets, live identity drift and changed completed content before writes', async () => withFile(async path => {
  const f = fixture(); await run(manifest, path, { ...f, token: 'test', maxItems: 1 });
  await assert.rejects(run({ ...manifest, pids: ['two', 'one'] }, path, { ...f, token: 'test', resume: true }), /identity/);
  f.states.set('one', 'stale_content');
  await assert.rejects(run(manifest, path, { ...f, token: 'test', resume: true }), /completed item changed/);
  assert.deepEqual(f.writes, ['one']);
}));
test('a failed or ambiguous request is recorded without automatic retry', async () => withFile(async path => {
  const f = fixture(); let attempts = 0;
  const request = async (url, options) => { if (options.method === 'POST') { attempts++; throw Error('connection lost'); } return f.request(url, options); };
  await assert.rejects(run(manifest, path, { token: 'test', request }), /connection lost/);
  assert.equal(attempts, 1); assert.deepEqual(JSON.parse(readFileSync(path)).remaining, ['one', 'two']);
}));
test('unknown fields, duplicate targets, embedded credentials and malformed scopes fail closed', () => {
  for (const bad of [{ ...manifest, tenantId: 373339825559965696 }, { ...manifest, pids: ['one', 'one'] },
    { ...manifest, baseUrl: 'https://secret@example.com' }, { ...manifest, baseUrl: 'http://localhost/api' },
    { ...manifest, expectedSpace: '' }, { ...manifest, automaticRetry: true }]) assert.throws(() => validateManifest(bad));
});

test('cancellation preserves ambiguous work, releases its lock and never retries the POST', async () => withFile(async path => {
  const f = fixture(), controller = new AbortController();
  let attempts = 0;
  const request = async (url, options) => {
    if (options.method !== 'POST') return f.request(url, options);
    attempts++;
    return new Promise((_, reject) => {
      options.signal.addEventListener('abort', () => reject(options.signal.reason), { once: true });
      controller.abort(Error('operator cancelled'));
    });
  };
  await assert.rejects(run(manifest, path, { token: 'test', request, signal: controller.signal }), /operator cancelled/);
  assert.equal(attempts, 1);
  assert.equal(existsSync(`${path}.lock`), false);
  const saved = JSON.parse(readFileSync(path));
  assert.deepEqual(saved.remaining, ['one', 'two']);
  assert.equal(saved.failure, 'operator cancelled');
  // Simulate the server committing after the client lost the acknowledgement.
  f.states.set('one', 'current');
  const resumeRequest = async (url, options) => {
    const pid = new URL(url).pathname.split('/')[5];
    if (options.method === 'POST' && f.states.get(pid) === 'current') {
      return { ok: true, status: 200, json: async () => ({ code: '0', data: {
        agentCode: manifest.agentCode, expectedSpace: manifest.expectedSpace, pid, outcome: 'already_current' } }) };
    }
    return f.request(url, options);
  };
  assert.equal((await run(manifest, path, { token: 'test', request: resumeRequest, resume: true })).status, 'complete');
  assert.deepEqual(f.writes, ['two']);
}));

test('real SIGTERM exits cleanly with a resumable checkpoint and no retained lock', { timeout: 10_000 }, async () => withFile(async path => {
  let observed;
  const entered = new Promise(resolve => { observed = resolve; });
  const server = createServer((request, response) => { observed(); /* Keep the inspection in flight. */ });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const source = `${path}.manifest.json`;
  writeFileSync(source, JSON.stringify({ ...manifest, baseUrl: `http://127.0.0.1:${server.address().port}` }));
  const child = spawn(process.execPath, [fileURLToPath(new URL('./memory-embedding-maintenance.mjs', import.meta.url)),
    '--manifest', source, '--checkpoint', path, '--token-env', 'TEST_MAINTENANCE_TOKEN'],
  { env: { ...process.env, TEST_MAINTENANCE_TOKEN: 'isolated-fixture-token' }, stdio: ['ignore', 'pipe', 'pipe'] });
  let stderr = ''; child.stderr.on('data', data => { stderr += data; });
  const exited = once(child, 'exit');
  try {
    await entered;
    assert.equal(existsSync(`${path}.lock`), true);
    child.kill('SIGTERM');
    assert.deepEqual(await exited, [1, null]);
    assert.equal(existsSync(`${path}.lock`), false);
    const saved = JSON.parse(readFileSync(path));
    assert.deepEqual(saved.completed, []);
    assert.deepEqual(saved.remaining, manifest.pids);
    assert.match(saved.failure, /interrupted/);
    assert.doesNotMatch(stderr, /isolated-fixture-token/);
  } finally {
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGTERM');
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
}));
