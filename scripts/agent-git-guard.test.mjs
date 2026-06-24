#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { enforceCheckoutPolicy, inspectCheckout, renderCheckoutResult } from './agent-git-guard.mjs';

function git(repo, args, options = {}) {
  const result = spawnSync('git', ['-C', repo, ...args], {
    encoding: 'utf8',
    env: { ...process.env, ...(options.env || {}) },
  });
  assert.equal(result.status, 0, result.stderr);
  return result.stdout.trim();
}

function makeRepo() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-git-guard-'));
  const init = spawnSync('git', ['init', '-b', 'main', root], { encoding: 'utf8' });
  assert.equal(init.status, 0, init.stderr);
  git(root, ['config', 'user.email', 'agent-git-guard@example.test']);
  git(root, ['config', 'user.name', 'Agent Git Guard Test']);
  fs.writeFileSync(path.join(root, 'README.md'), '# fixture\n');
  git(root, ['add', 'README.md']);
  git(root, ['commit', '-m', 'Initial commit']);
  return root;
}

test('allows canonical checkout on main', () => {
  const root = makeRepo();
  const result = inspectCheckout(root, {});

  assert.equal(result.branch, 'main');
  assert.equal(result.isolated, false);
  assert.equal(result.blocked, false);
  assert.equal(result.reason, 'ok');
  assert.match(renderCheckoutResult(result), /status=ok/);
});

test('blocks canonical checkout on a non-main branch', () => {
  const root = makeRepo();
  git(root, ['switch', '-c', 'codex/should-use-worktree']);

  const result = inspectCheckout(root, {});
  assert.equal(result.branch, 'codex/should-use-worktree');
  assert.equal(result.isolated, false);
  assert.equal(result.blocked, true);
  assert.equal(result.reason, 'canonical-checkout-non-main-blocked');
});

test('allows linked worktree on a non-main branch', () => {
  const root = makeRepo();
  const worktreePath = path.join(path.dirname(root), `${path.basename(root)}-wt`);
  git(root, ['worktree', 'add', '-b', 'codex/worktree-ok', worktreePath, 'main']);

  const result = inspectCheckout(worktreePath, {});
  assert.equal(result.branch, 'codex/worktree-ok');
  assert.equal(result.isolated, true);
  assert.equal(result.blocked, false);
});

test('allows explicit override for one checkout operation', () => {
  const root = makeRepo();
  git(root, ['switch', '-c', 'codex/manual-override']);

  const result = inspectCheckout(root, { AURA_ALLOW_CANONICAL_BRANCH_SWITCH: '1' });
  assert.equal(result.branch, 'codex/manual-override');
  assert.equal(result.isolated, false);
  assert.equal(result.blocked, false);
  assert.equal(result.reason, 'branch-switch-override');
});

test('post-checkout enforcement restores canonical checkout to main', () => {
  const root = makeRepo();
  git(root, ['switch', '-c', 'codex/restore-me']);

  const result = enforceCheckoutPolicy(root, { restore: true, env: {} });

  assert.equal(result.blocked, true);
  assert.equal(result.restored, true);
  assert.equal(git(root, ['branch', '--show-current']), 'main');
});
