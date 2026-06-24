#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { inspectRepo, renderResult } from './agent-write-guard.mjs';

function git(repo, args) {
  const result = spawnSync('git', ['-C', repo, ...args], {
    encoding: 'utf8',
  });
  assert.equal(result.status, 0, result.stderr);
  return result.stdout.trim();
}

function makeRepo() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-write-guard-'));
  const init = spawnSync('git', ['init', '-b', 'main', root], { encoding: 'utf8' });
  assert.equal(init.status, 0, init.stderr);
  git(root, ['config', 'user.email', 'agent-write-guard@example.test']);
  git(root, ['config', 'user.name', 'Agent Write Guard Test']);
  fs.writeFileSync(path.join(root, 'README.md'), '# fixture\n');
  git(root, ['add', 'README.md']);
  git(root, ['commit', '-m', 'Initial commit']);
  return root;
}

test('blocks a normal checkout on main', () => {
  const root = makeRepo();
  const result = inspectRepo(root, {});

  assert.equal(result.branch, 'main');
  assert.equal(result.isolated, false);
  assert.equal(result.blocked, true);
  assert.equal(result.reason, 'canonical-main-write-blocked');
  assert.match(renderResult(result), /status=blocked/);
});

test('allows a normal checkout on a feature branch', () => {
  const root = makeRepo();
  git(root, ['switch', '-c', 'codex/guard-test']);

  const result = inspectRepo(root, {});
  assert.equal(result.branch, 'codex/guard-test');
  assert.equal(result.isolated, false);
  assert.equal(result.blocked, false);
});

test('allows a linked worktree on a feature branch', () => {
  const root = makeRepo();
  const worktreePath = path.join(path.dirname(root), `${path.basename(root)}-wt`);
  git(root, ['worktree', 'add', '-b', 'codex/guard-worktree', worktreePath, 'main']);

  const result = inspectRepo(worktreePath, {});
  assert.equal(result.branch, 'codex/guard-worktree');
  assert.equal(result.isolated, true);
  assert.equal(result.blocked, false);
});

test('allows explicit one-command main override', () => {
  const root = makeRepo();
  const result = inspectRepo(root, { AURA_ALLOW_MAIN_WRITE: '1' });

  assert.equal(result.branch, 'main');
  assert.equal(result.isolated, false);
  assert.equal(result.blocked, false);
  assert.equal(result.reason, 'main-write-override');
});
