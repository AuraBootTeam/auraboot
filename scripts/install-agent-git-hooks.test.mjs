#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { installAgentGitHooks } from './install-agent-git-hooks.mjs';

function git(repo, args) {
  const result = spawnSync('git', ['-C', repo, ...args], {
    encoding: 'utf8',
  });
  assert.equal(result.status, 0, result.stderr);
  return result.stdout.trim();
}

function makeRepo() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-hook-install-'));
  const init = spawnSync('git', ['init', '-b', 'main', root], { encoding: 'utf8' });
  assert.equal(init.status, 0, init.stderr);
  git(root, ['config', 'user.email', 'agent-hook-install@example.test']);
  git(root, ['config', 'user.name', 'Agent Hook Install Test']);
  fs.writeFileSync(path.join(root, 'README.md'), '# fixture\n');
  git(root, ['add', 'README.md']);
  git(root, ['commit', '-m', 'Initial commit']);
  return root;
}

test('installs executable post-checkout hook that invokes the repo-local git guard', () => {
  const root = makeRepo();
  const result = installAgentGitHooks(root, { timestamp: () => '20260624100000' });

  assert.equal(result.installed, true);
  assert.equal(path.basename(result.hookPath), 'post-checkout');
  const hook = fs.readFileSync(result.hookPath, 'utf8');
  assert.equal(fs.statSync(result.hookPath).mode & 0o111, 0o111);
  assert.match(hook, /aura-agent-git-guard-managed/);
  assert.match(hook, /scripts\/agent-git-guard\.mjs/);
  assert.match(hook, /--post-checkout/);
});

test('backs up an existing unmanaged post-checkout hook before replacing it', () => {
  const root = makeRepo();
  const hookDir = path.join(root, '.git', 'hooks');
  const hookPath = path.join(hookDir, 'post-checkout');
  fs.writeFileSync(hookPath, '#!/usr/bin/env bash\necho existing-hook\n');
  fs.chmodSync(hookPath, 0o755);

  const result = installAgentGitHooks(root, { timestamp: () => '20260624100102' });

  assert.equal(result.installed, true);
  assert.equal(result.backupPath, `${result.hookPath}.backup-20260624100102`);
  assert.match(fs.readFileSync(result.backupPath, 'utf8'), /existing-hook/);
  assert.match(fs.readFileSync(hookPath, 'utf8'), /aura-agent-git-guard-managed/);
});
