#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptPath = fileURLToPath(import.meta.url);
const hookMarker = 'aura-agent-git-guard-managed';

function requireNext(argv, index, flag) {
  const value = argv[index + 1];
  if (!value || value.startsWith('--')) {
    throw new Error(`${flag} requires a value`);
  }
  return value;
}

function parseArgs(argv) {
  const args = {
    repos: [],
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
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

function resolveFromRepo(repoRoot, value) {
  if (path.isAbsolute(value)) {
    return normalizeExistingPath(value);
  }
  return normalizeExistingPath(path.resolve(repoRoot, value));
}

function defaultTimestamp() {
  return new Date().toISOString().replace(/\D/g, '').slice(0, 14);
}

export function buildPostCheckoutHook() {
  return `#!/usr/bin/env bash
set -u

# ${hookMarker}

if [ "\${AURA_GIT_GUARD_REENTER:-}" = "1" ]; then
  exit 0
fi

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [ -z "$REPO_ROOT" ]; then
  exit 0
fi

GUARD="$REPO_ROOT/scripts/agent-git-guard.mjs"
if [ ! -f "$GUARD" ]; then
  echo "agent-git-guard missing: $GUARD" >&2
  exit 0
fi

exec node "$GUARD" --repo "$REPO_ROOT" --post-checkout
`;
}

export function resolveHookPath(repoPath) {
  const inputPath = path.resolve(repoPath);
  const repoRoot = normalizeExistingPath(requireGit(inputPath, ['rev-parse', '--show-toplevel']));
  const configuredHooksPath = runGit(repoRoot, ['config', '--get', 'core.hooksPath']);
  const hookDir = configuredHooksPath.status === 0 && configuredHooksPath.stdout.trim()
    ? resolveFromRepo(repoRoot, configuredHooksPath.stdout.trim())
    : path.join(resolveFromRepo(repoRoot, requireGit(repoRoot, ['rev-parse', '--git-common-dir'])), 'hooks');
  return {
    hookDir,
    hookPath: path.join(hookDir, 'post-checkout'),
    repoRoot,
  };
}

function readHookIfPresent(hookPath) {
  try {
    return fs.readFileSync(hookPath, 'utf8');
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
}

function writeHookAtomically(hookPath, content) {
  const tempPath = `${hookPath}.tmp-${process.pid}-${defaultTimestamp()}`;
  try {
    fs.writeFileSync(tempPath, content, { mode: 0o755, flag: 'wx' });
    fs.renameSync(tempPath, hookPath);
  } catch (error) {
    try {
      fs.rmSync(tempPath, { force: true });
    } catch {
      // Best-effort cleanup only; preserve the original write error.
    }
    throw error;
  }
}

export function installAgentGitHooks(repoPath, options = {}) {
  const { hookDir, hookPath, repoRoot } = resolveHookPath(repoPath);
  fs.mkdirSync(hookDir, { recursive: true });

  let backupPath;
  const existing = readHookIfPresent(hookPath);
  if (existing !== null && !existing.includes(hookMarker)) {
    backupPath = `${hookPath}.backup-${(options.timestamp || defaultTimestamp)()}`;
    fs.renameSync(hookPath, backupPath);
  }

  writeHookAtomically(hookPath, buildPostCheckoutHook());
  fs.chmodSync(hookPath, 0o755);

  return {
    backupPath,
    hookPath,
    installed: true,
    repoRoot,
  };
}

function usage() {
  return `Usage: node scripts/install-agent-git-hooks.mjs [--repo PATH ...]

Installs the local post-checkout hook that keeps canonical normal checkouts on
main. Existing unmanaged post-checkout hooks are backed up before replacement.
`;
}

function main() {
  try {
    const args = parseArgs(process.argv.slice(2));
    if (args.help) {
      process.stdout.write(usage());
      return;
    }
    const results = args.repos.map((repo) => installAgentGitHooks(repo));
    for (const result of results) {
      process.stdout.write(`repo=${result.repoRoot} hook=${result.hookPath} installed=yes`);
      if (result.backupPath) {
        process.stdout.write(` backup=${result.backupPath}`);
      }
      process.stdout.write('\n');
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
