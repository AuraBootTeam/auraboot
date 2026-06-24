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
    repos: [],
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--json') {
      args.json = true;
      continue;
    }
    if (arg === '--repo') {
      args.repos.push(requireNext(argv, index, arg));
      index += 1;
      continue;
    }
    if (arg === '--help' || arg === '-h') {
      args.help = true;
      continue;
    }
    throw new Error(`Unknown argument: ${arg}`);
  }

  if (args.repos.length === 0) {
    args.repos.push(process.cwd());
  }
  return args;
}

function runGit(repo, args) {
  return spawnSync('git', ['-C', repo, ...args], {
    encoding: 'utf8',
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

export function inspectRepo(repoPath, env = process.env) {
  const inputPath = path.resolve(repoPath);
  const repoRoot = normalizeExistingPath(requireGit(inputPath, ['rev-parse', '--show-toplevel']));
  const branch = requireGit(repoRoot, ['branch', '--show-current']);
  const gitDir = normalizeExistingPath(requireGit(repoRoot, ['rev-parse', '--absolute-git-dir']));
  const commonDir = resolveGitPath(repoRoot, requireGit(repoRoot, ['rev-parse', '--git-common-dir']));
  const superproject = runGit(repoRoot, ['rev-parse', '--show-superproject-working-tree']);
  const isSubmodule = superproject.status === 0 && superproject.stdout.trim().length > 0;
  const isolated = gitDir !== commonDir && !isSubmodule;
  const isMain = branch === 'main';
  const mainWriteOverride = env.AURA_ALLOW_MAIN_WRITE === '1';
  const blocked = isMain && !isolated && !mainWriteOverride;

  return {
    blocked,
    branch: branch || '(detached)',
    commonDir,
    gitDir,
    inputPath,
    isolated,
    isMain,
    isSubmodule,
    mainWriteOverride,
    reason: blocked ? 'canonical-main-write-blocked' : mainWriteOverride && isMain ? 'main-write-override' : 'ok',
    repoRoot,
  };
}

export function renderResult(result) {
  return [
    `repo=${result.repoRoot}`,
    `branch=${result.branch}`,
    `isolated=${result.isolated ? 'yes' : 'no'}`,
    `status=${result.blocked ? 'blocked' : 'ok'}`,
    `reason=${result.reason}`,
  ].join(' ');
}

function usage() {
  return `Usage: node scripts/agent-write-guard.mjs [--repo PATH ...] [--json]

Fails closed when any checked repository is a normal checkout on branch main.
Run it before file edits. To override for an explicit, user-authorized main
write, set AURA_ALLOW_MAIN_WRITE=1 for that command and document the reason.
`;
}

function main() {
  try {
    const args = parseArgs(process.argv.slice(2));
    if (args.help) {
      process.stdout.write(usage());
      return;
    }

    const results = args.repos.map((repo) => inspectRepo(repo));
    if (args.json) {
      process.stdout.write(`${JSON.stringify(results, null, 2)}\n`);
    } else {
      process.stdout.write(`${results.map(renderResult).join('\n')}\n`);
    }

    if (results.some((result) => result.blocked)) {
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
