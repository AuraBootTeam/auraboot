import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

// Builds a throwaway repository with the core migration layout and runs the
// pull-request guard against a base branch.
function repository(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'migration-order-'));
  t.after(() => fs.rmSync(root, {recursive: true, force: true}));
  const dir = path.join(root, 'platform/src/main/resources/db/migration/core');
  fs.mkdirSync(dir, {recursive: true});
  const script = path.join(root, 'check.sh');
  fs.copyFileSync(new URL('./check-migration-order-vs-base.sh', import.meta.url), script);
  const git = (...args) => {
    const result = spawnSync('git', ['-C', root, ...args], {encoding: 'utf8'});
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  };
  git('init', '-q', '-b', 'main');
  git('config', 'user.email', 't@example.com');
  git('config', 'user.name', 'Test');
  const migrations = path.join(root, 'migrations'); // a second directory must not count
  fs.mkdirSync(migrations, {recursive: true});
  const add = name => fs.writeFileSync(path.join(dir, name), 'SELECT 1;\n');
  const commit = message => { git('add', '-A'); git('commit', '-q', '-m', message); };
  const run = base => spawnSync('bash', [script, base], {env: {...process.env, AURA_CORE_ROOT: root}, encoding: 'utf8'});
  add('V20261117010000__older.sql');
  add('V20261123090000__highest.sql');
  commit('base');
  return {root, migrations, add, commit, run, git};
}

test('a migration newer than the base branch highest version is accepted', t => {
  const r = repository(t);
  r.git('switch', '-q', '-c', 'feature');
  r.add('V20261124010000__newer.sql');
  r.commit('newer');
  const out = r.run('main');
  assert.equal(out.status, 0, out.stderr);
});

test('a migration added below the base branch highest version is rejected although a fresh database applies it in order', t => {
  const r = repository(t);
  r.git('switch', '-q', '-c', 'feature');
  r.add('V20261119003000__backdated.sql');
  r.commit('backdated');
  const out = r.run('main');
  assert.equal(out.status, 1);
  assert.match(out.stderr, /V20261119003000 is not above V20261123090000/);
  assert.match(out.stderr, /re-version it above scripts\/db\/migration-version-floor\.txt/);
});

test('one backdated migration among newer ones is still reported', t => {
  const r = repository(t);
  r.git('switch', '-q', '-c', 'feature');
  r.add('V20261124010000__newer.sql');
  r.add('V20261119003000__backdated.sql');
  r.commit('mixed');
  const out = r.run('main');
  assert.equal(out.status, 1);
  assert.match(out.stderr, /V20261119003000 is not above/);
  assert.doesNotMatch(out.stderr, /V20261124010000 is not above/);
});

test('a change that touches no core migration is accepted', t => {
  const r = repository(t);
  r.git('switch', '-q', '-c', 'feature');
  fs.writeFileSync(path.join(r.root, 'README.md'), 'no migration\n');
  fs.writeFileSync(path.join(r.migrations, 'V19990101000000__not_core.sql'), 'SELECT 1;\n');
  r.commit('no core migration');
  const out = r.run('main');
  assert.equal(out.status, 0, out.stderr);
});

test('an unknown base ref is a usage failure, not a pass', t => {
  const r = repository(t);
  const out = r.run('no-such-ref');
  assert.equal(out.status, 2);
  assert.match(out.stderr, /base ref not found/);
});
