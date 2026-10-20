import { execFileSync, spawn, spawnSync } from 'node:child_process';
import { constants, fstatSync, lstatSync, mkdirSync, openSync, closeSync, readFileSync, writeFileSync, renameSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, resolve } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { pathToFileURL } from 'node:url';
import { goldenStackState } from './golden-stack-state.mjs';

const need = (condition, message) => { if (!condition) throw new Error(message); };
const hash = bytes => createHash('sha256').update(bytes).digest('hex');

function entryStat(file) {
  try { return lstatSync(file); }
  catch (cause) {
    // Only an absent directory entry permits initialization; dangling links remain entries.
    if (cause.code === 'ENOENT') return null;
    throw cause;
  }
}

export function privateRedisPort(value) {
  need(/^\d+$/.test(String(value)), 'Invalid private Redis port');
  const port = Number(value);
  need(Number.isInteger(port) && port >= 1024 && port <= 65535 && port !== 6379,
    'Private Redis requires a separate unprivileged port');
  return port;
}

export function privateRedisConfig(report, repo, value, { allowSourceDrift = false } = {}) {
  need(/^[a-zA-Z0-9][a-zA-Z0-9_-]*$/.test(report.runtime), 'Invalid Redis runtime owner');
  const source = report.sources.find(row => ['core', 'auraboot'].includes(row.key));
  need(source?.expected.root === repo && (allowSourceDrift || source.status === 'ok'),
    'Private Redis source owner mismatch');
  const port = privateRedisPort(value);
  need(!Object.values(report.ports).some(row => row.port === port), 'Private Redis port overlaps product ports');
  const database = report.environment.REDIS_DATABASE;
  need(typeof database === 'string' && /^\d+$/.test(database) && Number(database) <= 1023,
    'Invalid private Redis database');
  const dataRoot = join(resolve(report.stateDir), 'redis');
  need(!entryStat(dataRoot)?.isSymbolicLink(), 'Private Redis storage root may not be a symlink');
  return { schemaVersion: 1, runtime: report.runtime, repo, host: '127.0.0.1', port, database,
    dataDirectory: join(dataRoot, report.runtime) };
}

function descriptorPath(report) {
  return join(goldenStackState(report.stateDir, report.runtime), 'isolated-redis.json');
}

export function readPrivateRedisConfig(report, repo, options) {
  const file = descriptorPath(report);
  need(Number.isInteger(constants.O_NOFOLLOW) && Number.isInteger(constants.O_NONBLOCK),
    'Safe descriptor reads are unavailable');
  let fd;
  try { fd = openSync(file, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK); }
  catch (cause) {
    // An absent descriptor permits initialization; links and other open failures never do.
    if (cause.code === 'ENOENT') return null;
    throw cause;
  }
  try {
    need(fstatSync(fd).isFile(), 'Invalid private Redis descriptor');
    const bytes = readFileSync(fd), config = JSON.parse(bytes);
    need(isDeepStrictEqual(config, privateRedisConfig(report, repo, config.port, options)), 'Private Redis configuration changed');
    need(!entryStat(config.dataDirectory) || (lstatSync(config.dataDirectory).isDirectory() &&
      !lstatSync(config.dataDirectory).isSymbolicLink()), 'Invalid private Redis data directory');
    return { config, path: file, hash: hash(bytes) };
  } finally { closeSync(fd); }
}

function command(file, args) {
  try { return execFileSync(file, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim(); }
  catch (cause) {
    // CLI boundary: arguments can contain ownership tokens; never expose subprocess diagnostics.
    throw new Error('Private Redis command failed', { cause });
  }
}

export function privateRedisRuntimeReport(cli, name) {
  const result = spawnSync(cli, ['runtime', 'explain', name, '--json'], { encoding: 'utf8' });
  // During startup other product listeners may be absent. Still require actual source
  // and owned-process probes below; a registration-only `show` cannot establish identity.
  need(!result.error && [0, 1].includes(result.status), 'Private Redis runtime probe failed');
  const report = JSON.parse(result.stdout);
  need(report.runtime === name && Array.isArray(report.sources) && Array.isArray(report.processes),
    'Private Redis runtime probe owner mismatch');
  return report;
}

function listenerPids(port) {
  try {
    return execFileSync('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-t'],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim().split(/\s+/).filter(Boolean).map(Number);
  } catch (cause) {
    // lsof uses exit 1 for an empty listener set; other errors do not prove the port is free.
    if (cause.status === 1) return [];
    throw new Error('Private Redis listener probe failed', { cause });
  }
}

function redis(config, ...args) {
  return command('redis-cli', ['-h', config.host, '-p', String(config.port), '-n', config.database, ...args]);
}

export function verifyPrivateRedis({ report, repo, descriptor, listeners, liveDirectory, pong }) {
  const config = descriptor.config;
  need(isDeepStrictEqual(config, privateRedisConfig(report, repo, config.port)), 'Private Redis owner mismatch');
  const process = report.processes.find(row => row.key === 'redis-owned');
  need(process?.status === 'ok' && process.expected.cwd === config.dataDirectory &&
    listeners.length === 1 && listeners[0] === process.expected.pid, 'Private Redis process identity mismatch');
  need(liveDirectory === config.dataDirectory && pong === 'PONG', 'Private Redis endpoint identity mismatch');
  return { isolated: true, host: config.host, port: config.port, database: config.database,
    pid: process.expected.pid, dataDirectory: config.dataDirectory };
}

export function collectPrivateRedis(report, repo) {
  const descriptor = readPrivateRedisConfig(report, repo);
  if (!descriptor) {
    need(!report.processes.some(row => row.key === 'redis-owned'), 'Private Redis descriptor is missing');
    return null;
  }
  const directory = JSON.parse(redis(descriptor.config, '--json', 'CONFIG', 'GET', 'dir'));
  return verifyPrivateRedis({ report, repo, descriptor, listeners: listenerPids(descriptor.config.port),
    liveDirectory: directory.dir, pong: redis(descriptor.config, 'PING') });
}

export function verifyBackendRedisConnection(pid, endpoint) {
  const links = command('lsof', ['-nP', '-a', '-p', String(pid), `-iTCP:${endpoint.port}`,
    '-sTCP:ESTABLISHED', '-Fn']);
  need(links.split('\n').some(line => line.startsWith('n') &&
    line.endsWith(`->${endpoint.host}:${endpoint.port}`)), 'Backend private Redis connection missing');
}

export async function startPrivateRedis(name, repo, cli, value, reset) {
  let report = privateRedisRuntimeReport(cli, name);
  need(report.runtime === name, 'Private Redis runtime mismatch');
  const config = privateRedisConfig(report, repo, value), file = descriptorPath(report);
  const retained = readPrivateRedisConfig(report, repo);
  need(!retained || isDeepStrictEqual(retained.config, config), 'Private Redis port or owner changed');
  const listeners = listenerPids(config.port);
  if (listeners.length) {
    need(retained, 'Private Redis port is occupied by an unregistered service');
    collectPrivateRedis(report, repo);
  } else {
    need(!report.processes.some(row => row.key === 'redis-owned' && row.status === 'ok'),
      'Registered private Redis is not listening');
    mkdirSync(config.dataDirectory, { recursive: true, mode: 0o700 });
    need(!lstatSync(config.dataDirectory).isSymbolicLink(), 'Private Redis storage may not be a symlink');
    mkdirSync(goldenStackState(report.stateDir, name), { recursive: true });
    const bytes = JSON.stringify(config, null, 2) + '\n';
    writeFileSync(`${file}.tmp`, bytes, { mode: 0o600 }); renameSync(`${file}.tmp`, file);
    mkdirSync(join(goldenStackState(report.stateDir, name), 'logs'), { recursive: true });
    const log = openSync(join(goldenStackState(report.stateDir, name), 'logs', 'redis.log'), 'a', 0o600);
    const token = command(cli, ['runtime', 'process', 'token', name]);
    const child = spawn('redis-server', ['--bind', config.host, '--port', String(config.port),
      '--dir', config.dataDirectory, '--dbfilename', 'dump.rdb', '--appendonly', 'yes', '--appendfsync', 'always',
      '--databases', String(Math.max(16, Number(config.database) + 1)), '--save', '',
      '--protected-mode', 'yes', '--daemonize', 'no', '--set-proc-title', 'no'], {
      cwd: join(repo, 'platform'), detached: true, stdio: ['ignore', log, log],
      env: { ...process.env, AURA_RUNTIME_NAME: name, AURA_RUNTIME_OWNERSHIP_TOKEN: token },
    });
    closeSync(log);
    await new Promise((done, reject) => { child.once('spawn', done); child.once('error', reject); });
    child.unref();
    // Startup readiness polling observes the owned listener; it never retries application work.
    for (let attempt = 0; attempt < 50 && !listenerPids(config.port).length; attempt++) {
      await new Promise(done => setTimeout(done, 100));
    }
    command(cli, ['runtime', 'process', 'register', name, '--key', 'redis-owned', '--pid', String(child.pid), '--token', token]);
    report = privateRedisRuntimeReport(cli, name);
    collectPrivateRedis(report, repo);
  }
  if (reset) {
    // Explicit fresh-db initialization, after PID/cwd/port/storage ownership verification.
    need(redis(config, 'FLUSHDB') === 'OK', 'Private Redis initialization failed');
  }
  return collectPrivateRedis(report, repo);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const [action, name, repo, cli, port, reset] = process.argv.slice(2);
    if (action === 'validate-port') {
      need(process.argv.length === 4, 'Invalid private Redis port arguments');
      console.log(privateRedisPort(name));
    } else {
      need(action === 'start' && [undefined, '0', '1'].includes(reset), 'Invalid private Redis action');
      console.log(JSON.stringify(await startPrivateRedis(name, resolve(repo), cli, port, reset === '1')));
    }
  } catch { // Top-level CLI boundary: keep ownership credentials out of public diagnostics.
    console.error('Private Redis initialization refused; owned process metadata and data retained');
    process.exitCode = 1;
  }
}
