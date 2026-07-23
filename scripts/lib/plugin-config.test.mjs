import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { loadConfigList, loadConfigText } from './plugin-config.mjs';

// Two on-disk layouts must read identically, or a gate silently returns nothing
// for one of them — the class that gave check-command-reachability 19 false
// positives on its first run.

function plugin(files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pcfg-'));
  const cfg = path.join(root, 'config');
  for (const [rel, doc] of Object.entries(files)) {
    const abs = path.join(cfg, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, typeof doc === 'string' ? doc : JSON.stringify(doc));
  }
  return root;
}

test('single-file array layout (OSS)', () => {
  const p = plugin({ 'commands.json': [{ code: 'a:x' }, { code: 'a:y' }] });
  assert.deepEqual(loadConfigList(p, 'commands').map((c) => c.code), ['a:x', 'a:y']);
});

test('single-file wrapped layout {commands:[...]}', () => {
  const p = plugin({ 'commands.json': { commands: [{ code: 'a:x' }] } });
  assert.deepEqual(loadConfigList(p, 'commands').map((c) => c.code), ['a:x']);
});

test('sharded directory layout (aura-quote), one object per file', () => {
  const p = plugin({
    'commands/x.json': { code: 'a:x' },
    'commands/y.json': { code: 'a:y' },
  });
  assert.deepEqual(loadConfigList(p, 'commands').map((c) => c.code).sort(), ['a:x', 'a:y']);
});

test('a shard that is itself an array is flattened (bindings shards)', () => {
  const p = plugin({ 'bindings/b.json': [{ fieldCode: 'f1' }, { fieldCode: 'f2' }] });
  assert.deepEqual(loadConfigList(p, 'bindings').map((b) => b.fieldCode), ['f1', 'f2']);
});

test('both layouts present are unioned, not one shadowing the other', () => {
  const p = plugin({ 'commands.json': [{ code: 'a:x' }], 'commands/y.json': { code: 'a:y' } });
  assert.deepEqual(loadConfigList(p, 'commands').map((c) => c.code).sort(), ['a:x', 'a:y']);
});

test('a malformed shard is skipped, not fatal — one bad file must not zero the plugin', () => {
  const p = plugin({ 'commands/good.json': { code: 'a:x' }, 'commands/bad.json': '{ not json' });
  assert.deepEqual(loadConfigList(p, 'commands').map((c) => c.code), ['a:x']);
});

test('loadConfigText concatenates raw text across both layouts', () => {
  const p = plugin({ 'pages.json': '"a:x"', 'pages/extra.json': '"a:y"' });
  const t = loadConfigText(p, 'pages');
  assert.ok(t.includes('a:x') && t.includes('a:y'));
});

test('an absent base yields an empty list, not a throw', () => {
  assert.deepEqual(loadConfigList(plugin({}), 'commands'), []);
});
