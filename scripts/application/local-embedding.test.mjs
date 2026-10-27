import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { sameProcess, validateVector, verifyCache, main } from './local-embedding.mjs';

test('process identity refuses PID reuse, changed command and changed cwd', () => {
  const expected = { pid: 42, start: 'first', cwd: '/owned', command: 'ollama serve' };
  sameProcess({ ...expected }, expected);
  for (const [key, value] of [['pid', 43], ['start', 'reused'], ['cwd', '/foreign'], ['command', 'other']]) {
    assert.throws(() => sameProcess({ ...expected, [key]: value }, expected), /identity drift/);
  }
});

test('health requires one finite nonzero 1536-dimensional actual vector', () => {
  const good = { data: [{ embedding: Array(1536).fill(.1) }] };
  assert.equal(validateVector(good).dimensions, 1536);
  for (const bad of [{}, { data: [] }, { data: [good.data[0], good.data[0]] },
    { data: [{ embedding: [1] }] }, { data: [{ embedding: Array(1536).fill(0) }] },
    { data: [{ embedding: [NaN, ...Array(1535).fill(1)] }] },
    { data: [{ embedding: ['1', ...Array(1535).fill(1)] }] }]) assert.throws(() => validateVector(bad));
});

test('changed model manifest is rejected before a service or download can start', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'local-embedding-cache-'));
  try {
    const path = join(dir, 'manifests/registry.ollama.ai/library/qwen3-embedding'); mkdirSync(path, { recursive: true });
    writeFileSync(join(path, '4b'), JSON.stringify({ layers: [] }));
    await assert.rejects(verifyCache(dir), /manifest changed/);
  } finally { rmSync(dir, { recursive: true }); }
});

test('invalid action, runtime and unknown or duplicate options fail before runtime mutation', async () => {
  for (const args of [['pull', 'test'], ['up', '../foreign'], ['up', 'test', '--download', 'yes'],
    ['up', 'test', '--workspace', '/tmp', '--workspace', '/tmp']]) await assert.rejects(main(args));
});
