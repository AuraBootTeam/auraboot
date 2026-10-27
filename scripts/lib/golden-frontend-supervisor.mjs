import { spawn, execFileSync } from 'node:child_process';
import { realpathSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { registerGoldenSupervisor, readGoldenProcessSnapshot, planGoldenStop, executeGoldenStop,
  verifyGoldenStopOwnership } from './golden-process-stop.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const [runtime, source, cli, separator, ...command] = process.argv.slice(2);
if (!runtime || !source || !cli || separator !== '--' || !command.length || process.env.AURA_RUNTIME_NAME !== runtime) {
  console.error('Frontend supervisor requires an explicit runtime, source and command'); process.exit(1);
}
const repo = realpathSync(source), cwd = join(repo, 'web-admin');
if (realpathSync(process.cwd()) !== cwd) { console.error('Frontend supervisor source mismatch'); process.exit(1); }
// A stable registered parent outlives pnpm/concurrently execs and all automatic child restarts.
await registerGoldenSupervisor(runtime, repo, cli, process.pid);
const preload = pathToFileURL(join(here, 'golden-frontend-registration.mjs')).href;
// A vite that dies on EADDRINUSE while an impostor process serves the port is the worst
// failure mode a golden stack can have: the supervisor stays up, the port answers 200, and
// every browser run silently exercises a foreign stack. Detect a foreign listener on the
// web port before spawning and fail loudly instead.
// The web port reaches the dev command through the VITE_PORT env assignment in the
// launch prefix (there is no --port argv to read).
const webPort = process.env.VITE_PORT;
if (webPort && /^\d+$/.test(webPort)) {
  let listeners = '';
  try {
    listeners = execFileSync('/usr/sbin/lsof', ['-nP', `-iTCP:${webPort}`, '-sTCP:LISTEN'],
      { encoding: 'utf8' }).trim();
  } catch { /* nothing listens */ }
  const lines = listeners.split('\n').slice(1).filter(Boolean);
  if (lines.length > 0) {
    console.error(`[golden-frontend] port ${webPort} is already served by another process;` +
      ' refusing to start (a foreign frontend would silently serve a foreign stack):');
    for (const line of lines) console.error('  ' + line.trim());
    process.exit(2);
  }
}

const child = spawn(command[0], command.slice(1), { cwd, stdio: 'inherit', env: { ...process.env,
  AURA_GOLDEN_FRONTEND_SUPERVISOR_PID: String(process.pid), AURA_GOLDEN_FRONTEND_REPO: repo,
  AURA_GOLDEN_FRONTEND_CLI: cli,
  NODE_OPTIONS: `${process.env.NODE_OPTIONS || ''} --import ${JSON.stringify(preload)}`.trim() } });
let stopping = false;
function stop(code) {
  if (stopping) return;
  stopping = true; // Disable exit handling before signaling the existing command tree.
  try {
    const report = JSON.parse(execFileSync(cli, ['runtime', 'show', runtime, '--json'], { encoding: 'utf8' }));
    const roots = [...new Set([child.pid, ...report.processes.filter(item => ['web', 'bff'].includes(item.key))
      .map(item => item.expected.pid)].filter(pid => pid && readGoldenProcessSnapshot(pid)))];
    const all = execFileSync('ps', ['-e', '-o', 'pid=', '-o', 'ppid='], { encoding: 'utf8' }).trim()
      .split('\n').map(line => line.trim().split(/\s+/).map(Number));
    const snapshots = [], pending = [...roots], seen = new Set();
    while (pending.length) {
      const pid = pending.shift(); if (seen.has(pid)) continue; seen.add(pid);
      if (seen.size > 1024) throw new Error('Frontend process tree too large');
      const item = readGoldenProcessSnapshot(pid); if (!item) continue;
      snapshots.push(item); pending.push(...all.filter(([, parent]) => parent === pid).map(([pid]) => pid));
    }
    const token = process.env.AURA_RUNTIME_OWNERSHIP_TOKEN;
    const plan = planGoldenStop({ runtime, repo, token, roots, snapshots, listeners: [] });
    executeGoldenStop(plan, pid => {
      const current = readGoldenProcessSnapshot(pid), expected = plan.find(item => item.pid === pid);
      return current && { ...current, ownershipVerified: verifyGoldenStopOwnership(current, expected, token) };
    }, (pid, signal) => { try { process.kill(pid, signal); } catch (error) { if (error.code !== 'ESRCH') throw error; } });
    process.exit(code);
  } catch { console.error('Frontend supervisor cleanup refused: inspect owned process identity'); process.exit(1); }
}
process.on('SIGTERM', () => stop(0));
process.on('SIGINT', () => stop(0));
child.once('error', () => stop(1));
child.once('exit', code => stop(code ?? 1));
