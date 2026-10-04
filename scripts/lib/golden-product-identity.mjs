import { execFileSync } from 'node:child_process';
import { readFileSync, realpathSync, mkdirSync, writeFileSync, renameSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { pathToFileURL } from 'node:url';

const environmentKeys = ['AURA_RUNTIME_NAME', 'AURA_RUNTIME_OWNERSHIP_TOKEN',
  'SERVER_PORT', 'SPRING_DATASOURCE_URL', 'SPRING_DATA_REDIS_DATABASE',
  'VITE_PORT', 'BFF_PORT', 'SPRING_BOOT_URL', 'BFF_INTERNAL_URL', 'AGENT_LLM_STUB_MODE'];
const requireIdentity = (condition, message) => { if (!condition) throw new Error(message); };

// Compare independent live observations. Never include credentials or raw commands in evidence.
export function verifyGoldenProduct({ report, repo, environments, commands, token, database, health }) {
  requireIdentity(report.verdict === 'ok', 'Workspace identity verification failed');
  const source = report.sources.find(item => ['auraboot', 'core'].includes(item.key));
  requireIdentity(source?.status === 'ok' && source.expected.root === repo, 'OSS source identity mismatch');
  const artifact = report.artifacts.find(item => item.key === 'backend');
  requireIdentity(artifact?.status === 'ok' && artifact.expected.sourceCommit === source.expected.commit,
    'Backend artifact identity mismatch');
  const ports = report.ports;
  for (const key of ['backend', 'web', 'bff']) {
    const process = report.processes.find(item => item.key === key);
    const expectedCwd = join(repo, key === 'backend' ? 'platform' : 'web-admin');
    requireIdentity(process?.status === 'ok' && process.expected.cwd === expectedCwd &&
      ports[key]?.status === 'listening' && ports[key].pids.length === 1 &&
      ports[key].pids[0] === process.expected.pid, `${key} listener identity mismatch`);
    requireIdentity(environments[key]?.AURA_RUNTIME_NAME === report.runtime &&
      environments[key].AURA_RUNTIME_OWNERSHIP_TOKEN === token && Boolean(token),
    `${key} live runtime ownership mismatch`);
  }
  const backend = environments.backend;
  const jarIndex = commands.backend.indexOf('-jar');
  requireIdentity(jarIndex >= 0 && commands.backend[jarIndex + 1] === artifact.path,
    'Live JVM does not use the registered artifact');
  requireIdentity(backend.SERVER_PORT === String(ports.backend.port), 'Backend port mismatch');
  requireIdentity(backend.SPRING_DATASOURCE_URL ===
    `jdbc:postgresql://127.0.0.1:5432/${report.environment.POSTGRES_DB}?charSet=UTF8`, 'Backend database namespace mismatch');
  requireIdentity(database === report.environment.POSTGRES_DB, 'Database connection identity mismatch');
  requireIdentity(backend.SPRING_DATA_REDIS_DATABASE === report.environment.REDIS_DATABASE, 'Redis namespace mismatch');
  for (const key of ['web', 'bff']) {
    const env = environments[key];
    requireIdentity(env.VITE_PORT === String(ports.web.port) && env.BFF_PORT === String(ports.bff.port),
      `${key} frontend port mismatch`);
    requireIdentity(env.SPRING_BOOT_URL === `http://127.0.0.1:${ports.backend.port}` &&
      env.BFF_INTERNAL_URL === env.SPRING_BOOT_URL, `${key} backend upstream mismatch`);
  }
  requireIdentity(health.backend?.status === 'UP' && health.bff?.status === 'ok' &&
    health.bff.services?.springBoot?.status === 'healthy' &&
    health.bff.services.springBoot.backend?.status === 'UP', 'Product health verification failed');
  return { schemaVersion: 1, runtime: report.runtime, generatedAt: new Date().toISOString(),
    sourceCommit: source.expected.commit, artifactHash: artifact.expected.hash,
    sourceRoots: report.sources.map(item => ({ key: item.key, ...item.expected })),
    backendArtifact: { path: artifact.path, hash: artifact.expected.hash },
    processes: report.processes.map(item => ({ key: item.key, ...item.expected,
      tokenFile: undefined, tokenHash: undefined })),
    ports, database, redisDatabase: report.environment.REDIS_DATABASE,
    backendUrl: `http://127.0.0.1:${ports.backend.port}`,
    webUrl: `http://127.0.0.1:${ports.web.port}`, bffUrl: `http://127.0.0.1:${ports.bff.port}`,
    scope: 'OSS source, staged backend, live Web/BFF/backend identity and service namespaces; plugin import and browser acceptance are separate' };
}

function run(file, args, options = {}) {
  try { return execFileSync(file, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], ...options }); }
  catch { throw new Error(`Product identity probe failed: ${file}`); }
}
export function liveEnvironment(pid) {
  let entries;
  if (process.platform === 'linux') entries = readFileSync(`/proc/${pid}/environ`, 'utf8').split('\0');
  else if (process.platform === 'darwin') {
    const text = run('ps', ['eww', '-p', String(pid), '-o', 'command=']);
    entries = text.match(/(?:^|\s)[A-Z][A-Z0-9_]*=[^\s]*/g)?.map(value => value.trim()) ?? [];
  } else throw new Error('Live environment inspection is unsupported on this platform');
  return Object.fromEntries(entries.map(entry => {
    const index = entry.indexOf('='); return [entry.slice(0, index), entry.slice(index + 1)];
  }).filter(([key]) => environmentKeys.includes(key)));
}

export function collectGoldenProduct(name, repo, cli) {
  const report = JSON.parse(run(cli, ['runtime', 'explain', name, '--json']));
  const environments = {}, commands = {};
  for (const key of ['backend', 'web', 'bff']) {
    const descriptor = report.processes.find(item => item.key === key);
    requireIdentity(descriptor?.status === 'ok', `Missing verified ${key} process`);
    const pid = descriptor.expected.pid;
    environments[key] = liveEnvironment(pid);
    commands[key] = process.platform === 'linux' ? readFileSync(`/proc/${pid}/cmdline`, 'utf8').split('\0').filter(Boolean) :
      run('ps', ['-ww', '-p', String(pid), '-o', 'command=']).trim().split(/\s+/);
  }
  const token = readFileSync(join(report.stateDir, 'runtimes', name, 'processes', 'ownership.token'), 'utf8').trim();
  const database = run('psql', ['-X', '-At', '-v', 'ON_ERROR_STOP=1', '-c', 'SELECT current_database()'], {
    env: { ...process.env, PGHOST: '127.0.0.1', PGPORT: '5432', PGUSER: 'auraboot',
      PGDATABASE: report.environment.POSTGRES_DB, PGPASSWORD: 'auraboot' },
  }).trim();
  const health = Object.fromEntries(['backend', 'bff'].map(key => [key,
    JSON.parse(run('curl', ['--noproxy', '*', '--fail', '--silent', '--max-time', '10',
      `http://127.0.0.1:${report.ports[key].port}/${key === 'backend' ? 'actuator/health' : 'health'}`]))]));
  const manifest = verifyGoldenProduct({ report, repo: realpathSync(repo), environments, commands, token, database, health });
  const file = join(report.stateDir, 'runtimes', name, 'oss-golden-stack', 'manifest.json');
  mkdirSync(dirname(file), { recursive: true });
  const temporary = `${file}.${process.pid}.tmp`;
  writeFileSync(temporary, JSON.stringify(manifest, null, 2) + '\n', { mode: 0o600 });
  renameSync(temporary, file);
  run(cli, ['runtime', 'manifest', 'publish', name, '--product', 'oss-golden-stack', '--from', file,
    '--verified-by', 'oss-golden-stack:verify-artifacts']);
  run(cli, ['runtime', 'verify', name]);
  return file;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    requireIdentity(process.argv.length === 5, 'Expected runtime name, OSS root and Workspace CLI');
    console.log(collectGoldenProduct(...process.argv.slice(2)));
  } catch (error) { console.error(error.message.startsWith('Product identity probe failed:') ? error.message :
    'Product runtime identity verification failed'); process.exitCode = 1; }
}
