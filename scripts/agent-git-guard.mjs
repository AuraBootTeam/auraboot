#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptPath = fileURLToPath(import.meta.url);

function requireNext(argv, index, flag) {
  const value = argv[index + 1];
  if (!value || value.startsWith('--')) {
    throw new Error(`${flag} requires a value`);
  }
  return value;
}

function parseArgs(argv) {
  const args = {
    json: false,
    postCheckout: false,
    repo: process.cwd(),
    restore: false,
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--json') {
      args.json = true;
      continue;
    }
    if (arg === '--post-checkout') {
      args.postCheckout = true;
      args.restore = true;
      continue;
    }
    if (arg === '--restore') {
      args.restore = true;
      continue;
    }
    if (arg === '--repo') {
      args.repo = requireNext(argv, index, arg);
      index += 1;
      continue;
    }
    if (arg === '--help' || arg === '-h') {
      args.help = true;
      continue;
    }
    throw new Error(`Unknown argument: ${arg}`);
  }
  return args;
}

function runGit(repo, args, env = process.env) {
  return spawnSync('git', ['-C', repo, ...args], {
    encoding: 'utf8',
    env: { ...process.env, ...env },
  });
}

function requireGit(repo, args) {
  const result = runGit(repo, args);
  if (result.status !== 0) {
    throw new Error(`git ${args.join(' ')} failed in ${repo}: ${result.stderr.trim()}`);
  }
  return result.stdout.trim();
}

function normalizeExistingPath(value) {
  const absolute = path.resolve(value);
  try {
    return fs.realpathSync.native(absolute);
  } catch {
    return absolute;
  }
}

function resolveGitPath(repoRoot, value) {
  if (path.isAbsolute(value)) {
    return normalizeExistingPath(value);
  }
  return normalizeExistingPath(path.resolve(repoRoot, value));
}

export function inspectCheckout(repoPath, env = process.env) {
  const inputPath = path.resolve(repoPath);
  const repoRoot = normalizeExistingPath(requireGit(inputPath, ['rev-parse', '--show-toplevel']));
  const branchRaw = requireGit(repoRoot, ['branch', '--show-current']);
  const branch = branchRaw || '(detached)';
  const gitDir = normalizeExistingPath(requireGit(repoRoot, ['rev-parse', '--absolute-git-dir']));
  const commonDir = resolveGitPath(repoRoot, requireGit(repoRoot, ['rev-parse', '--git-common-dir']));
  const superproject = runGit(repoRoot, ['rev-parse', '--show-superproject-working-tree']);
  const isSubmodule = superproject.status === 0 && superproject.stdout.trim().length > 0;
  const isolated = gitDir !== commonDir && !isSubmodule;
  const override = env.AURA_ALLOW_CANONICAL_BRANCH_SWITCH === '1';
  const blocked = !isolated && branch !== 'main' && !override;

  return {
    blocked,
    branch,
    commonDir,
    gitDir,
    inputPath,
    isolated,
    isSubmodule,
    override,
    reason: blocked ? 'canonical-checkout-non-main-blocked' : override && !isolated && branch !== 'main' ? 'branch-switch-override' : 'ok',
    repoRoot,
  };
}

export function renderCheckoutResult(result) {
  return [
    `repo=${result.repoRoot}`,
    `branch=${result.branch}`,
    `isolated=${result.isolated ? 'yes' : 'no'}`,
    `status=${result.blocked ? 'blocked' : 'ok'}`,
    `reason=${result.reason}`,
    result.restored === undefined ? '' : `restored=${result.restored ? 'yes' : 'no'}`,
  ].filter(Boolean).join(' ');
}

export function enforceCheckoutPolicy(repoPath, options = {}) {
  const env = options.env || process.env;
  const result = inspectCheckout(repoPath, env);
  if (!result.blocked || !options.restore) {
    return { ...result, restored: false };
  }

  const restore = runGit(result.repoRoot, ['switch', 'main'], {
    ...env,
    AURA_GIT_GUARD_REENTER: '1',
  });
  if (restore.status !== 0) {
    return {
      ...result,
      restoreError: restore.stderr.trim() || restore.stdout.trim(),
      restored: false,
    };
  }
  return {
    ...result,
    restored: true,
  };
}

function usage() {
  return `Usage: node scripts/agent-git-guard.mjs [--repo PATH] [--post-checkout|--restore] [--json]

Blocks canonical normal checkouts from staying on any branch other than main.
Use linked git worktrees for feature branches. Set AURA_ALLOW_CANONICAL_BRANCH_SWITCH=1
only for a deliberate one-command override.
`;
}

function main() {
  try {
    const args = parseArgs(process.argv.slice(2));
    if (args.help) {
      process.stdout.write(usage());
      return;
    }
    if (process.env.AURA_GIT_GUARD_REENTER === '1') {
      return;
    }

    const result = enforceCheckoutPolicy(args.repo, {
      env: process.env,
      restore: args.restore,
    });
    const text = renderCheckoutResult(result);
    if (args.json) {
      process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    } else if (result.blocked) {
      process.stderr.write(`${text}\n`);
      if (result.restored) {
        process.stderr.write('canonical checkout branch switch was reverted to main; create a linked worktree for feature work\n');
      } else if (result.restoreError) {
        process.stderr.write(`${result.restoreError}\n`);
      }
    } else {
      process.stdout.write(`${text}\n`);
    }

    if (result.blocked && !args.postCheckout) {
      process.exitCode = 1;
    }
    if (result.blocked && args.postCheckout && !result.restored) {
      process.exitCode = 1;
    }
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.stderr.write(usage());
    process.exitCode = 2;
  }
}

if (process.argv[1] === scriptPath) {
  main();
}
