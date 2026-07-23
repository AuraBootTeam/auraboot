import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { auditReachability, referencedCommands } from './check-command-reachability.mjs';

// Every case builds a repo that SHOULD be red and asserts it is, then asserts
// silence on the clean one. A gate whose failure nobody has seen is not a gate.

function makeRepo({ commands = [], pagesJson = null, pagesDir = null, menusJson = null } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cmdreach-'));
  const cfg = path.join(root, 'plugins', 'demo', 'config');
  fs.mkdirSync(cfg, { recursive: true });
  fs.writeFileSync(path.join(cfg, 'commands.json'),
    JSON.stringify({ commands: commands.map((code) => ({ code, type: 'custom' })) }, null, 2));
  if (pagesJson) fs.writeFileSync(path.join(cfg, 'pages.json'), JSON.stringify(pagesJson, null, 2));
  if (menusJson) fs.writeFileSync(path.join(cfg, 'menus.json'), JSON.stringify(menusJson, null, 2));
  if (pagesDir) {
    fs.mkdirSync(path.join(cfg, 'pages'), { recursive: true });
    for (const [name, doc] of Object.entries(pagesDir)) {
      fs.writeFileSync(path.join(cfg, 'pages', `${name}.json`), JSON.stringify(doc, null, 2));
    }
  }
  return { root: path.join(root, 'plugins'), pluginDir: path.join(root, 'plugins', 'demo') };
}

const errs = (r) => r.findings.filter((f) => f.level === 'error');
const kinds = (r) => errs(r).map((f) => f.kind);

test('a command referenced by a page is reachable', () => {
  const { root } = makeRepo({
    commands: ['demo:do_thing'],
    pagesJson: [{ pageKey: 'p', blocks: [{ action: { type: 'command', command: 'demo:do_thing' } }] }],
  });
  assert.deepEqual(auditReachability({ roots: [root], config: {} }).findings, []);
});

test('a command no page references is an error', () => {
  const { root } = makeRepo({
    commands: ['demo:do_thing', 'demo:orphan_cmd'],
    pagesJson: [{ pageKey: 'p', blocks: [{ action: { command: 'demo:do_thing' } }] }],
  });
  const r = auditReachability({ roots: [root], config: {} });
  assert.deepEqual(kinds(r), ['unreachable']);
  assert.equal(errs(r)[0].code, 'demo:orphan_cmd');
});

test('pages kept one-per-file in a directory count as references', () => {
  // The first version of this gate only opened config/pages.json and called
  // nineteen reachable commands unreachable. A false positive rate like that
  // is how a gate gets switched off, so it is pinned here.
  const { root } = makeRepo({
    commands: ['demo:do_thing'],
    pagesDir: { form: { pageKey: 'f', blocks: [{ action: { command: 'demo:do_thing' } }] } },
  });
  assert.deepEqual(auditReachability({ roots: [root], config: {} }).findings, []);
});

test('a menu entry counts as an entry point', () => {
  const { root } = makeRepo({
    commands: ['demo:do_thing'],
    menusJson: [{ code: 'm', command: 'demo:do_thing' }],
  });
  assert.deepEqual(auditReachability({ roots: [root], config: {} }).findings, []);
});

test('a command whose name is a prefix of another is not credited by it', () => {
  // `bom:create_material` vs `bom:create_material_rule_dict`: a substring check
  // reports the first as reachable because the second is on a page. Whole
  // quoted tokens are extracted precisely to avoid that.
  const { root, pluginDir } = makeRepo({
    commands: ['demo:create_thing', 'demo:create_thing_rule'],
    pagesJson: [{ pageKey: 'p', blocks: [{ action: { command: 'demo:create_thing_rule' } }] }],
  });
  assert.deepEqual([...referencedCommands(pluginDir)], ['demo:create_thing_rule']);
  const r = auditReachability({ roots: [root], config: {} });
  assert.deepEqual(errs(r).map((f) => f.code), ['demo:create_thing']);
});

test('baselined debt warns instead of blocking, but new debt still blocks', () => {
  const { root } = makeRepo({
    commands: ['demo:old_debt', 'demo:new_debt'],
    pagesJson: [{ pageKey: 'p' }],
  });
  const config = { baseline: { demo: ['demo:old_debt'] } };
  const r = auditReachability({ roots: [root], config });
  assert.deepEqual(errs(r).map((f) => f.code), ['demo:new_debt']);
  assert.ok(r.findings.some((f) => f.kind === 'unreachable-baselined' && f.code === 'demo:old_debt'));
});

test('a baseline entry for a command that no longer exists is reported', () => {
  const { root } = makeRepo({ commands: ['demo:a'], pagesJson: [{ pageKey: 'p', c: 'demo:a' }] });
  const r = auditReachability({ roots: [root], config: { baseline: { demo: ['demo:gone'] } } });
  assert.deepEqual(r.findings.map((f) => f.kind), ['stale-baseline']);
});

test('an allowlist entry without a reason is itself an error', () => {
  const { root } = makeRepo({ commands: ['demo:x'], pagesJson: [{ pageKey: 'p' }] });
  assert.deepEqual(kinds(auditReachability({ roots: [root], config: { allow: { demo: { 'demo:x': '' } } } })),
    ['allow-without-reason']);
  assert.deepEqual(auditReachability({ roots: [root], config: { allow: { demo: { 'demo:x': 'backend-only migration helper' } } } }).findings, []);
});

test('a missing plugin root fails loudly rather than reporting nothing wrong', () => {
  const r = auditReachability({ roots: ['/no/such/plugins'], config: {} });
  assert.deepEqual(kinds(r), ['missing-root']);
});
