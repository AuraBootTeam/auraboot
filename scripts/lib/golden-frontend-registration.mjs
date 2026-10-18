import { execFileSync } from 'node:child_process';
import { realpathSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { readGoldenProcessSnapshot } from './golden-process-stop.mjs';

const need = (value, message) => { if (!value) throw new Error(message); };
function cliCall(cli, args) {
  try { return execFileSync(cli, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }); }
  catch { throw new Error('Frontend process registration refused'); }
}

export function frontendProcessKey(entry, cwd, repo) {
  if (!entry || cwd !== join(repo, 'web-admin')) return null;
  const file = resolve(cwd, entry);
  if (file === join(cwd, 'app/server/bff.server.ts')) return 'bff';
  if (file === join(cwd, 'scripts/production-entry.mjs') || /\/@react-router\/dev\/bin\.js$/.test(file)) return 'web';
  return null;
}

/** Authenticate the whole lineage; a matching cwd or port alone never establishes ownership. */
export function assertFrontendLineage({ pid, supervisor, repo, runtime, token, snapshots }) {
  need(Number.isSafeInteger(pid) && pid > 1 && Number.isSafeInteger(supervisor) && supervisor > 1 && supervisor !== pid && token && runtime,
    'Invalid frontend supervisor identity');
  const byPid = new Map(snapshots.map(item => [item.pid, item]));
  need(byPid.size === snapshots.length, 'Duplicate frontend process identity');
  let current = pid;
  const seen = new Set();
  for (;;) {
    need(!seen.has(current) && seen.size < 64, 'Invalid frontend process ancestry');
    seen.add(current);
    const item = byPid.get(current);
    need(item && item.cwd === join(repo, 'web-admin') && item.startedAt && item.commandHash,
      'Frontend process source or generation mismatch');
    const owned = item.runtime === runtime && item.token === token;
    const bridge = item.executable === 'sh' && item.runtime === undefined && item.token === undefined
      && current !== pid && current !== supervisor;
    need(owned || bridge, 'Frontend process ownership mismatch');
    if (current === supervisor) break;
    current = item.parent;
  }
  need(seen.size === snapshots.length, 'Unrelated frontend process identity');
}

export function registerFrontendProcess({ runtime, repo, cli, supervisor, pid = process.pid,
  key, snapshot = readGoldenProcessSnapshot, call = cliCall }) {
  need(['web', 'bff'].includes(key), 'Unsupported frontend process key');
  repo = realpathSync(repo);
  const report = JSON.parse(call(cli, ['runtime', 'show', runtime, '--json']));
  need(report.runtime === runtime && report.sources?.find(item => ['core', 'auraboot'].includes(item.key))?.expected.root === repo,
    'Frontend runtime source mismatch');
  need(report.processes?.find(item => item.key === 'frontend-launch')?.expected.pid === supervisor,
    'Frontend supervisor is not registered');
  call(cli, ['runtime', 'process', 'check', runtime, 'frontend-launch']);
  const previous = report.processes.find(item => item.key === key)?.expected.pid;
  need(!previous || previous === pid || !snapshot(previous), 'Previous frontend generation is still alive');
  const token = process.env.AURA_RUNTIME_OWNERSHIP_TOKEN;
  const snapshots = [];
  let current = pid;
  while (snapshots.length < 64) {
    const item = snapshot(current);
    need(item, 'Frontend process exited before registration');
    snapshots.push(item);
    if (current === supervisor) break;
    need(!snapshots.some(entry => entry.pid === item.parent), 'Cyclic frontend process ancestry');
    current = item.parent;
  }
  assertFrontendLineage({ pid, supervisor, repo, runtime, token, snapshots });
  call(cli, ['runtime', 'process', 'register', runtime, '--key', key, '--pid', String(pid), '--token', token]);
}

// NODE_OPTIONS also reaches pnpm/tsx helpers. Only the actual listener entrypoints register.
if (process.env.AURA_GOLDEN_FRONTEND_SUPERVISOR_PID) {
  try {
    const repo = realpathSync(process.env.AURA_GOLDEN_FRONTEND_REPO);
    const key = frontendProcessKey(process.argv[1], realpathSync(process.cwd()), repo);
    if (key) registerFrontendProcess({ runtime: process.env.AURA_RUNTIME_NAME, repo,
      cli: process.env.AURA_GOLDEN_FRONTEND_CLI,
      supervisor: Number(process.env.AURA_GOLDEN_FRONTEND_SUPERVISOR_PID), key });
  } catch {
    console.error('Frontend startup refused: runtime process identity could not be registered');
    process.exit(1);
  }
}
