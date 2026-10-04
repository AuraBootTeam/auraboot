import { goldenStackState } from './golden-stack-state.mjs';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, realpathSync, unlinkSync } from 'node:fs';
import { join, basename } from 'node:path';
import { pathToFileURL } from 'node:url';
import { liveEnvironment } from './golden-product-identity.mjs';

const need = (condition, message) => {
  if (!condition) {
    const error = new Error(message);
    // Only our fixed predicate messages become diagnostics; never print process/env payloads.
    error.goldenStopReason = message.toUpperCase().replace(/[^A-Z0-9]+/g, '_');
    throw error;
  }
};
const hash = value => createHash('sha256').update(value).digest('hex');
function run(file, args) {
  try { return execFileSync(file, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }); }
  catch {
    const error = new Error('Owned process identity probe failed');
    // Classify the public probe without logging its arguments (which may carry a token).
    error.goldenStopReason = args[0] === 'runtime'
      ? ({ show: 'WORKSPACE_RUNTIME_SHOW_FAILED', process: args[2] === 'check'
          ? 'WORKSPACE_PROCESS_CHECK_FAILED' : 'WORKSPACE_PROCESS_REGISTER_FAILED' }[args[1]]
          ?? 'WORKSPACE_RUNTIME_PROBE_FAILED')
      : ({ ps: 'PROCESS_METADATA_PROBE_FAILED', lsof: 'PROCESS_CWD_PROBE_FAILED',
          node: 'LISTENER_LIVENESS_PROBE_FAILED' }[basename(file)] ?? 'OWNED_IDENTITY_PROBE_FAILED');
    throw error;
  }
}
function alive(pid) {
  try {
    process.kill(pid, 0);
    if (process.platform === 'linux') {
      try {
        const stat = readFileSync(`/proc/${pid}/stat`, 'utf8');
        if (['Z', 'X'].includes(stat.slice(stat.lastIndexOf(')') + 2, stat.lastIndexOf(')') + 3))) return false;
      } catch (error) { if (error.code === 'ENOENT') return false; throw error; }
    }
    return true;
  } catch (error) {
    if (error.code === 'ESRCH') return false;
    throw new Error('Cannot establish owned process liveness');
  }
}
/** A process may exit between liveness and metadata probes during owned shutdown. */
export function readGoldenProcessSnapshot(pid, readMetadata = processMetadata, isAlive = alive) {
  if (!isAlive(pid)) return null;
  try { return readMetadata(pid); }
  catch (error) {
    // Never suppress metadata errors for a process that is still alive.
    if (!isAlive(pid)) return null;
    throw error;
  }
}
const snapshot = readGoldenProcessSnapshot;
function processMetadata(pid) {
  const cwd = process.platform === 'linux' ? realpathSync(`/proc/${pid}/cwd`) :
    realpathSync(run('lsof', ['-a', '-p', String(pid), '-d', 'cwd', '-Fn']).split('\n').find(line => line.startsWith('n'))?.slice(1));
  const env = liveEnvironment(pid);
  const command = run('ps', ['-ww', '-p', String(pid), '-o', 'command=']).trimEnd();
  return { pid, cwd, commandHash: hash(command), executable: basename(command.trim().split(/\s+/)[0]),
    startedAt: run('ps', ['-p', String(pid), '-o', 'lstart=']).trim(),
    parent: Number(run('ps', ['-p', String(pid), '-o', 'ppid=']).trim()),
    runtime: env.AURA_RUNTIME_NAME, token: env.AURA_RUNTIME_OWNERSHIP_TOKEN };
}

/** No port-based kill targets: every target needs an owned root or an owned ancestry. */
export function planGoldenStop({ runtime, repo, token, roots, snapshots, listeners }) {
  need(typeof token === 'string' && token.length > 0, 'Missing runtime ownership token');
  need(roots.every(pid => Number.isSafeInteger(pid) && pid > 1), 'Invalid owned root PID');
  const byPid = new Map(snapshots.map(item => [item.pid, item]));
  need(byPid.size === snapshots.length, 'Duplicate process snapshot');
  for (const item of snapshots) {
    need(Number.isSafeInteger(item.pid) && item.pid > 1 && item.pid !== process.pid,
      'Invalid stop target PID');
    need(item.runtime === runtime && item.token === token, 'Foreign process runtime ownership');
    need([join(repo, 'platform'), join(repo, 'web-admin')].includes(item.cwd), 'Foreign process cwd');
    need(Boolean(item.startedAt) && Boolean(item.commandHash), 'Missing process generation identity');
    let current = item.pid;
    const seen = new Set();
    while (!roots.includes(current)) {
      need(!seen.has(current), 'Cyclic process ancestry'); seen.add(current);
      const entry = byPid.get(current);
      need(entry && byPid.has(entry.parent), 'Process has no owned ancestry'); current = entry.parent;
    }
  }
  for (const pid of listeners) need(byPid.has(pid), 'Unknown listener: refusing stop');
  // Supervisors first prevents concurrently from respawning children during shutdown.
  const order = [...new Set([...roots.filter(pid => byPid.has(pid)), ...snapshots.map(item => item.pid)])];
  return order.map(pid => {
    const { token: _token, ...identity } = byPid.get(pid);
    return identity;
  });
}

/** Recheck the full process generation immediately before each signal. */
export function executeGoldenStop(plan, readSnapshot, signal) {
  for (const expected of plan) {
    const actual = readSnapshot(expected.pid);
    if (!actual) continue;
    for (const key of ['pid', 'cwd', 'commandHash', 'startedAt', 'runtime']) {
      need(actual[key] === expected[key], 'Process identity changed before stop');
    }
    need(actual.ownershipVerified === true, 'Process ownership changed before stop');
    signal(expected.pid, 'SIGKILL');
  }
}

export function stableGoldenLaunch(current, previous, { runtime, token, cwd, executable }) {
  return Boolean(current && previous && current.cwd === cwd && current.runtime === runtime && current.token === token &&
    current.executable === executable && current.commandHash === previous.commandHash &&
    current.startedAt === previous.startedAt);
}
export async function registerGoldenLaunch(name, repo, cli, pid, key) {
  need(['frontend-launch', 'backend-launch'].includes(key), 'Unsupported launch process');
  const cwd = key === 'frontend-launch' ? 'web-admin' : 'platform';
  const executable = key === 'frontend-launch' ? 'node' : 'java';
  const report = JSON.parse(run(cli, ['runtime', 'show', name, '--json']));
  need(report.runtime === name && report.sources?.find(item => item.key === 'auraboot')?.expected.root === realpathSync(repo),
    'Launch source owner mismatch');
  const token = readFileSync(join(report.stateDir, 'runtimes', name, 'processes', 'ownership.token'), 'utf8').trim();
  let current, previous, ready = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    current = snapshot(pid);
    if (!current) break;
    if (stableGoldenLaunch(current, previous, { runtime: name, token, cwd: join(realpathSync(repo), cwd), executable })) {
      ready = true; break;
    }
    // spawn_detached returns after fork; the child still has to exec its environment.
    previous = current;
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  need(ready, 'Launch process ownership mismatch');
  run(cli, ['runtime', 'process', 'register', name, '--key', key, '--pid', String(pid), '--token', token]);
}
export const registerGoldenSupervisor = (name, repo, cli, pid) => registerGoldenLaunch(name, repo, cli, pid, 'frontend-launch');

export function stopGoldenProcesses(name, repo, cli) {
  repo = realpathSync(repo);
  const report = JSON.parse(run(cli, ['runtime', 'show', name, '--json']));
  need(report.runtime === name && report.sources?.find(item => item.key === 'auraboot')?.expected.root === repo,
    'Registered source root mismatch');
  const token = readFileSync(join(report.stateDir, 'runtimes', name, 'processes', 'ownership.token'), 'utf8').trim();
  const sd = goldenStackState(report.stateDir, name);
  const frontendFile = join(sd, 'frontend.pid');
  const frontendPid = existsSync(frontendFile) ? Number(readFileSync(frontendFile, 'utf8').trim()) : null;
  const supervisor = report.processes.find(item => item.key === 'frontend-launch');
  if (report.processes.some(item => item.key === 'web' || item.key === 'bff')) {
    need(supervisor, 'Frontend listeners have no registered supervisor');
  }
  if (existsSync(frontendFile)) need(Number.isSafeInteger(frontendPid) && frontendPid > 1, 'Invalid frontend PID file');
  if (frontendPid && alive(frontendPid)) need(supervisor?.expected.pid === frontendPid, 'Unregistered frontend supervisor');
  const roots = report.processes.slice().sort((a, b) => Number(b.key === 'frontend-launch') - Number(a.key === 'frontend-launch'))
    .map(item => item.expected.pid).filter(pid => alive(pid));
  for (const item of report.processes) if (alive(item.expected.pid)) run(cli, ['runtime', 'process', 'check', name, item.key]);
  const all = run('ps', ['-e', '-o', 'pid=', '-o', 'ppid=']).trim().split('\n')
    .map(line => line.trim().split(/\s+/).map(Number));
  const snapshots = [], pending = [...roots], seen = new Set();
  while (pending.length) {
    const pid = pending.shift(); if (seen.has(pid)) continue; seen.add(pid);
    need(seen.size <= 1024, 'Owned process tree is unexpectedly large');
    const current = snapshot(pid); if (!current) continue;
    snapshots.push(current);
    pending.push(...all.filter(([, parent]) => parent === pid).map(([child]) => child));
  }
  const listenerPids = () => Object.values(report.ports).flatMap(({ port }) => {
    // lsof exit 1 means no listener; other errors are not evidence of an empty port.
    let pids;
    try { pids = execFileSync('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-t'],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim().split(/\s+/).filter(Boolean).map(Number); }
    catch (error) { if (error.status === 1) pids = []; else throw new Error('Listener ownership probe failed'); }
    if (!pids.length) {
      need(Number.isInteger(port) && port > 0 && port <= 65535, 'Invalid registered port');
      // An invisible listener (for example another user's PID) is not an empty port.
      const listening = run(process.execPath, ['--input-type=module', '-e',
        "import net from 'node:net'; const s=net.connect(Number(process.argv[1]),'127.0.0.1'); " +
        "s.once('connect',()=>{console.log('listening');s.destroy();}); " +
        "s.once('error',e=>{if(e.code==='ECONNREFUSED')console.log('stopped');else process.exitCode=2;}); " +
        "s.setTimeout(1000,()=>{process.exitCode=2;s.destroy();});", String(port)]).trim();
      need(listening === 'stopped', 'Unidentified listener: refusing stop');
    }
    return pids;
  });
  const plan = planGoldenStop({ runtime: name, repo, token, roots, snapshots, listeners: listenerPids() });
  executeGoldenStop(plan, pid => {
    const current = snapshot(pid);
    return current && { ...current, ownershipVerified: current.token === token };
  }, (pid, signal) => {
    try { process.kill(pid, signal); } catch (error) { if (error.code !== 'ESRCH') throw new Error('Owned stop signal failed'); }
  });
  need(listenerPids().length === 0, 'Listener remains after owned stop');
  for (const file of ['frontend.pid', 'backend.pid']) if (existsSync(join(sd, file))) unlinkSync(join(sd, file));
  return plan.map(({ pid, cwd, startedAt, commandHash }) => ({ pid, cwd, startedAt, commandHash }));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const [action, name, repo, cli, pid] = process.argv.slice(2);
    if (action === 'register-supervisor') await registerGoldenSupervisor(name, repo, cli, Number(pid));
    else if (action === 'register-backend') await registerGoldenLaunch(name, repo, cli, Number(pid), 'backend-launch');
    else if (action === 'stop') console.log(JSON.stringify({ runtime: name, stopped: stopGoldenProcesses(name, repo, cli), retained: ['allocation', 'database', 'evidence'] }));
    else throw new Error('Unknown owned process action');
  } catch (error) {
    const reason = error.goldenStopReason ??
      (['ENOENT', 'EACCES', 'ESRCH', 'EPERM'].includes(error.code) ? error.code : 'UNCLASSIFIED_PROBE_FAILURE');
    console.error(`Owned golden process operation refused; inspect runtime process identity; reason=${reason}`);
    process.exitCode = 1;
  }
}
