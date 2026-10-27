#!/usr/bin/env node
import assert from 'node:assert/strict';
import { createReadStream, existsSync, mkdirSync, openSync, closeSync, readFileSync, realpathSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:net';

export const MODEL = 'qwen3-embedding:4b';
export const BLOB = '2b0cf8f17b4c723c27303015383c27ec4bf2d8314bb677d05e920dd70bb0f16b';
export const MANIFEST = 'df5bd2e3c74cd8d069d21dc038f1b359fcdc9458fce1c99bd43c9eb1518ff907';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const execute = (exe, args, options = {}) => {
  const r = spawnSync(exe, args, { encoding: 'utf8', ...options });
  assert.equal(r.status, 0, `${exe} ${args.slice(0, 3).join(' ')} failed: ${r.stderr || r.error || r.stdout}`);
  return r.stdout.trim();
};
const alive = pid => {
  try { process.kill(pid, 0); } catch (e) { if (e.code === 'ESRCH') return false; throw e; }
  const status = spawnSync('ps', ['-p', String(pid), '-o', 'stat='], { encoding: 'utf8' });
  return status.status === 0 && !status.stdout.trim().startsWith('Z');
};
function snapshot(pid) {
  return {
    pid,
    start: execute('ps', ['-p', String(pid), '-o', 'lstart=']),
    command: execute('ps', ['-ww', '-p', String(pid), '-o', 'command=']),
    cwd: execute('lsof', ['-a', '-p', String(pid), '-d', 'cwd', '-Fn']).split('\n').find(s => s.startsWith('n'))?.slice(1),
  };
}
export function sameProcess(actual, expected) {
  assert.deepEqual(actual, expected, 'Process PID/start/cwd/command identity drift');
}
function listenerOwned(config, process) {
  const pids = execute('lsof', ['-nP', `-iTCP:${config.port}`, '-sTCP:LISTEN', '-Fp'])
    .split('\n').filter(line => line.startsWith('p')).map(line => Number(line.slice(1)));
  assert.deepEqual([...new Set(pids)], [process.pid], 'Embedding listener is not the recorded provider');
}
export function validateVector(body) {
  assert.equal(body.data?.length, 1, 'Expected exactly one actual embedding');
  const v = body.data[0].embedding;
  assert(Array.isArray(v) && v.length === 1536, 'Expected 1536 dimensions');
  assert(v.every(x => typeof x === 'number' && Number.isFinite(x)), 'Nonfinite embedding');
  const norm = Math.sqrt(v.reduce((n, x) => n + x * x, 0));
  assert(norm > 0, 'Zero embedding');
  return { dimensions: v.length, norm };
}
export async function verifyCache(cache) {
  const manifest = readFileSync(join(cache, 'manifests/registry.ollama.ai/library/qwen3-embedding/4b'));
  assert.equal(sha(manifest), MANIFEST, 'Cached model manifest changed');
  assert(JSON.parse(manifest).layers.some(l => l.digest === `sha256:${BLOB}`), 'Manifest must name frozen weights');
  const digest = createHash('sha256');
  for await (const bytes of createReadStream(join(cache, `blobs/sha256-${BLOB}`))) digest.update(bytes);
  assert.equal(digest.digest('hex'), BLOB, 'Actual model weights changed');
}
async function request(endpoint, path, body) {
  const response = await fetch(endpoint + path, { signal: AbortSignal.timeout(60000),
    ...(body ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : {}) });
  assert(response.ok, `Local provider returned HTTP ${response.status}`);
  return response.json();
}
async function probe(config) {
  const endpoint = `http://127.0.0.1:${config.port}`;
  const tags = await request(endpoint, '/api/tags');
  assert.equal(tags.models?.find(m => m.name === MODEL)?.digest, MANIFEST, 'Served model differs from frozen manifest');
  const vector = validateVector(await request(endpoint, '/v1/embeddings', { model: MODEL, input: 'AuraBoot embedding health probe', dimensions: 1536 }));
  return { ...vector, version: (await request(endpoint, '/api/version')).version, model: MODEL, endpoint, manifestSha256: MANIFEST, blobSha256: BLOB };
}
async function vacant(port) {
  assert(Number.isInteger(port) && port > 1024 && port <= 65535, 'Explicit valid unprivileged port required');
  await new Promise((ok, bad) => { const server = createServer(); server.once('error', bad); server.listen(port, '127.0.0.1', () => server.close(ok)); });
}
function atomic(file, value) {
  writeFileSync(`${file}.tmp`, JSON.stringify(value, null, 2) + '\n', { mode: 0o600 });
  renameSync(`${file}.tmp`, file);
}
function children(pid) {
  const lines = execute('ps', ['-axo', 'pid=,ppid=']).split('\n').map(l => l.trim().split(/\s+/).map(Number));
  const result = [];
  for (const [child, parent] of lines) if (parent === pid) result.push(...children(child), snapshot(child));
  return result;
}
async function stopOwned(state, aura) {
  if (!alive(state.process.pid)) return;
  sameProcess(snapshot(state.process.pid), state.process);
  aura(['runtime', 'process', 'check', state.runtime, 'embedding']);
  const descendants = children(state.process.pid);
  // Unregister while the exact process can still be verified by Workspace.
  aura(['runtime', 'process', 'unregister', state.runtime, 'embedding']);
  sameProcess(snapshot(state.process.pid), state.process);
  process.kill(state.process.pid, 'SIGTERM');
  // Ollama shuts down its own runners. Do not interrogate or signal a runner
  // halfway through that exit: its cwd can disappear before kill(0) does.
  // Observe the exact pre-stop descendant set; survivors fail closed below.
  for (let n = 0; n < 100 && [state.process, ...descendants].some(p => alive(p.pid)); n++) await sleep(100);
  assert([state.process, ...descendants].every(p => !alive(p.pid)), 'Owned provider did not stop; preserve record for inspection');
}

export async function main(args) {
  const [action, runtime, ...rest] = args;
  assert(['up', 'status', 'down', 'resume'].includes(action), 'Usage: local-embedding.mjs up|status|down|resume <runtime> --workspace <root> [--cache <existing-model-dir> --ollama <binary> --port <port>]');
  assert(/^[a-z][a-z0-9-]{0,63}$/.test(runtime || ''), 'Invalid runtime');
  const options = {};
  for (let i = 0; i < rest.length; i += 2) {
    assert(['--workspace', '--cache', '--ollama', '--port'].includes(rest[i]) && rest[i + 1] && !rest[i + 1].startsWith('--'), 'Unknown/missing option');
    assert(!(rest[i] in options), 'Duplicate option'); options[rest[i]] = rest[i + 1];
  }
  const workspace = realpathSync(options['--workspace']);
  const aura = args => execute(join(workspace, 'aura'), args);
  // Require an allocated environment. Never allocate, reset storage or change another runtime implicitly.
  assert(existsSync(join(workspace, '.workspace/env', `${runtime}.env`)), 'Allocate the target runtime first');
  const directory = join(workspace, '.workspace/runtimes', runtime, 'embedding');
  mkdirSync(directory, { recursive: true });
  const lock = join(directory, 'operation.lock');
  const fd = openSync(lock, 'wx', 0o600);
  try {
    const file = join(directory, 'service.json');
    let state = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : null;
    if (state) assert.equal(state.runtime, runtime);
    if (action === 'down') {
      assert(state, 'No owned provider state');
      await stopOwned(state, aura); await vacant(state.config.port);
      state.phase = 'stopped'; atomic(file, state); return { runtime, phase: state.phase, retainedCache: state.config.cache };
    }
    if (action === 'status') {
      assert(state?.phase === 'running' && alive(state.process.pid), 'Owned provider is not running');
      sameProcess(snapshot(state.process.pid), state.process);
      aura(['runtime', 'process', 'check', runtime, 'embedding']);
      listenerOwned(state.config, state.process);
      assert.equal(sha(readFileSync(state.config.binary)), state.config.binarySha256, 'Provider binary drift');
      return { runtime, pid: state.process.pid, ...await probe(state.config) };
    }
    if (state && alive(state.process.pid)) {
      assert.equal(rest.length, 2, 'An existing live provider accepts only --workspace; stop it explicitly before changing configuration');
      sameProcess(snapshot(state.process.pid), state.process); aura(['runtime', 'process', 'check', runtime, 'embedding']);
      listenerOwned(state.config, state.process);
      assert.equal(sha(readFileSync(state.config.binary)), state.config.binarySha256, 'Provider binary drift');
      return { runtime, pid: state.process.pid, ...await probe(state.config) };
    }
    assert(action !== 'resume' || state, 'No saved resume configuration');
    const config = action === 'resume' ? state.config : {
      cache: realpathSync(options['--cache'] || join(workspace, '.workspace/models/agent-enterprise-runtime/ollama')),
      binary: realpathSync(options['--ollama']), port: Number(options['--port']),
    };
    if (action === 'resume') assert.equal(sha(readFileSync(config.binary)), config.binarySha256, 'Provider binary drift');
    await verifyCache(config.cache); await vacant(config.port);
    config.binarySha256 = sha(readFileSync(config.binary));
    const token = aura(['runtime', 'process', 'token', runtime]);
    const log = openSync(join(directory, 'provider.log'), 'a', 0o600);
    const child = spawn(config.binary, ['serve'], { cwd: directory, detached: true, stdio: ['ignore', log, log],
      env: { ...process.env, AURA_RUNTIME_OWNERSHIP_TOKEN: token, OLLAMA_MODELS: config.cache,
        OLLAMA_HOST: `127.0.0.1:${config.port}`, OLLAMA_TMPDIR: directory, OLLAMA_NOPRUNE: 'true',
        OLLAMA_MAX_LOADED_MODELS: '1', OLLAMA_NUM_PARALLEL: '1' } });
    closeSync(log);
    await new Promise((ok, bad) => { child.once('spawn', ok); child.once('error', bad); }); child.unref();
    state = { schemaVersion: 1, runtime, config, phase: 'starting', process: snapshot(child.pid) }; atomic(file, state);
    try { aura(['runtime', 'process', 'register', runtime, '--key', 'embedding', '--pid', String(child.pid), '--token', token]); }
    catch (error) {
      if (alive(child.pid)) { sameProcess(snapshot(child.pid), state.process); process.kill(child.pid, 'SIGTERM'); }
      state.phase = 'registration-failed'; atomic(file, state); throw error;
    }
    let ready = false;
    for (let n = 0; n < 100; n++) {
      assert(alive(child.pid), 'Owned provider exited during startup');
      try { await request(`http://127.0.0.1:${config.port}`, '/api/version'); ready = true; break; }
      catch (error) { if (error.cause?.code !== 'ECONNREFUSED') throw error; await sleep(100); }
    }
    assert(ready, 'Owned provider startup timed out');
    listenerOwned(config, state.process);
    const health = await probe(config); state.phase = 'running'; state.health = health; atomic(file, state);
    return { runtime, pid: child.pid, phase: state.phase, ...health };
  } finally { closeSync(fd); rmSync(lock); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).then(result => console.log(JSON.stringify(result, null, 2))).catch(error => {
    console.error(`LOCAL_EMBEDDING_INVALID: ${error.message}`); process.exitCode = 1;
  });
}
