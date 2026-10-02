import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, realpathSync, renameSync, unlinkSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
const readJson = (file) => JSON.parse(readFileSync(file, 'utf8'));
const run = (command, args) => execFileSync(command, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
const alive = (pid) => { try { process.kill(pid, 0); return true; } catch { return false; } };
function atomicJson(file, value) {
  mkdirSync(dirname(file), { recursive: true });
  const temp = `${file}.${randomUUID()}.tmp`;
  writeFileSync(temp, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  renameSync(temp, file);
}

export function descendants(rootPid, table) {
  const result = [rootPid];
  for (let index = 0; index < result.length; index++) {
    for (const row of table) if (row.ppid === result[index] && !result.includes(row.pid)) result.push(row.pid);
  }
  return result;
}

export function assertOwnedStop({ registered, roots, table, listeners, isAlive }) {
  const known = new Set(registered);
  const tree = [...new Set(roots.filter(isAlive).flatMap((pid) => descendants(pid, table)))];
  for (const pid of [...tree, ...listeners]) {
    if (!known.has(pid)) throw new Error(`Unknown process ${pid}; refusing to stop any process`);
  }
  // Registered listeners that reparented are still checked before they are stopped.
  return [...new Set([...tree, ...listeners, ...registered.filter(isAlive)])];
}

export function assertLiveContract({ input, report, backend, web, bff }) {
  if (report.verdict !== 'ok') throw new Error('Workspace runtime identity is invalid');
  const artifact = report.artifacts.find((item) => item.key === 'backend');
  if (!artifact || artifact.status !== 'ok' || artifact.path !== input.backendArtifact) throw new Error('Backend artifact identity mismatch');
  if (artifact.expected.hash !== input.backendHash) throw new Error('Backend artifact baseline changed');
  if (!backend.command.endsWith(`java -jar ${input.backendArtifact}`)) throw new Error('Backend is not executing the staged artifact');
  if (backend.cwd !== join(input.repo, 'platform')) throw new Error('Backend cwd mismatch');
  const env = report.environment;
  if (backend.env.SERVER_PORT !== env.SERVER_PORT || backend.env.SPRING_DATA_REDIS_DATABASE !== env.REDIS_DATABASE ||
      backend.env.SPRING_DATASOURCE_URL !== `jdbc:postgresql://127.0.0.1:5432/${env.POSTGRES_DB}?charSet=UTF8`) {
    throw new Error('Backend port/database namespace mismatch');
  }
  if (backend.env.AURA_BUILTIN_PLUGINS_DIR !== join(input.repo, 'plugins') ||
      backend.env.AURA_PLUGINS_DIR !== input.pluginDirectory) throw new Error('Backend plugin target mismatch');
  if (input.frontend) {
    const upstream = `http://127.0.0.1:${env.SERVER_PORT}`;
    for (const item of [web, bff]) {
      if (!item || item.cwd !== join(input.repo, 'web-admin') || item.env.SPRING_BOOT_URL !== upstream ||
          item.env.BFF_INTERNAL_URL !== upstream || item.env.BFF_PORT !== env.BFF_PORT || item.env.VITE_PORT !== env.WEB_PORT) {
        throw new Error('Frontend cwd, ports or BFF upstream mismatch');
      }
    }
  }
}

function processTable() {
  return run('ps', ['-axo', 'pid=,ppid=']).split('\n').map((line) => {
    const [pid, ppid] = line.trim().split(/\s+/).map(Number); return { pid, ppid };
  });
}
function listeners(port) {
  try { return run('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-t']).split('\n').map(Number).filter(Boolean); }
  catch (error) { if (error.status === 1) return []; throw error; }
}
function snapshot(pid) {
  const command = run('ps', ['-ww', '-p', String(pid), '-o', 'command=']);
  const cwd = run('lsof', ['-a', '-p', String(pid), '-d', 'cwd', '-Fn']).split('\n').find((line) => line.startsWith('n'))?.slice(1);
  // Keep process credentials in memory; expose only the runtime target allowlist.
  const raw = process.platform === 'linux'
    ? readFileSync(`/proc/${pid}/environ`, 'utf8').replaceAll('\0', ' ')
    : run('ps', ['eww', '-p', String(pid), '-o', 'command=']);
  const env = {};
  for (const key of ['SERVER_PORT', 'SPRING_DATASOURCE_URL', 'SPRING_DATA_REDIS_DATABASE', 'SPRING_BOOT_URL', 'BFF_INTERNAL_URL', 'VITE_PORT', 'BFF_PORT', 'AURA_BUILTIN_PLUGINS_DIR', 'AURA_PLUGINS_DIR']) {
    env[key] = raw.match(new RegExp(`(?:^| )${key}=([^ ]*)`))?.[1];
  }
  return { pid, cwd, command, env };
}

export async function execute(action, name, { repo, workspace, state, frontend = true, importArgs = [] }) {
  if (!/^[a-zA-Z0-9][a-zA-Z0-9_-]*$/.test(name)) throw new Error('Invalid runtime name');
  repo = realpathSync(repo); workspace = realpathSync(workspace); state = realpathSync(state);
  const cli = (...args) => run(join(workspace, 'aura'), args);
  const show = () => JSON.parse(cli('runtime', 'show', name, '--json'));
  const sd = join(state, 'golden', name);
  const productDir = join(state, 'runtimes', name, 'auraboot');
  const inputFile = join(productDir, 'inputs.json');
  const pidFile = (key) => join(sd, `${key}.pid`);
  const pid = (key) => Number(readFileSync(pidFile(key), 'utf8').trim());
  const baseline = () => {
    const input = readJson(inputFile);
    if (input.runtime !== name || input.repo !== repo || input.workspace !== workspace) throw new Error('Product input identity mismatch');
    return input;
  };
  if (action === 'capture') {
    if (existsSync(inputFile)) throw new Error('Product inputs already exist; use a fresh verification allocation');
    const report = show();
    const artifact = report.artifacts.find((item) => item.key === 'backend');
    if (!artifact) throw new Error('Staged backend artifact missing');
    atomicJson(inputFile, { runtime: name, repo, workspace, frontend, pluginDirectory: join(sd, 'pf4j-plugins'), backendArtifact: artifact.path, backendHash: artifact.expected.hash });
    return;
  }
  if (action === 'record-import') {
    const input = baseline();
    const log = join(sd, 'import.log');
    if (!existsSync(log)) throw new Error('Successful import log missing');
    atomicJson(join(productDir, 'imports', `${randomUUID()}.json`), {
      runtime: name, importedAt: new Date().toISOString(), sourceCommit: show().sources.find((item) => item.key === 'auraboot')?.expected.commit,
      args: importArgs, logHash: sha256(readFileSync(log)),
    });
    // Keep each receipt's original bytes; later imports may reuse import.log.
    const archive = join(productDir, 'import-logs', `${sha256(readFileSync(log))}.log`);
    mkdirSync(dirname(archive), { recursive: true });
    if (!existsSync(archive)) writeFileSync(archive, readFileSync(log), { mode: 0o600 });
    return input;
  }
  if (action === 'register' || action === 'register-backend') {
    const input = baseline(); const table = processTable();
    const records = [{ key: 'backend', pid: pid('backend'), cwd: join(repo, 'platform') }];
    if (input.frontend && action === 'register') {
      for (const processPid of descendants(pid('frontend'), table)) records.push({ key: `frontend-${processPid}`, pid: processPid, cwd: join(repo, 'web-admin') });
    }
    const report = show();
    for (const item of records) if (snapshot(item.pid).cwd !== item.cwd) throw new Error('Spawned process cwd mismatch');
    const backendListeners = listeners(report.environment.SERVER_PORT);
    if (backendListeners.length !== 1 || backendListeners[0] !== pid('backend')) throw new Error('Foreign or missing backend listener');
    for (const port of input.frontend && action === 'register' ? [report.environment.WEB_PORT, report.environment.BFF_PORT] : []) {
      const bound = listeners(port);
      if (bound.length !== 1 || !records.some((item) => item.pid === bound[0])) throw new Error('Foreign or missing frontend listener');
    }
    if (report.processes.length) cli('runtime', 'process', 'check', name);
    const token = cli('runtime', 'process', 'token', name);
    for (const item of records) cli('runtime', 'process', 'register', name, '--key', item.key, '--pid', String(item.pid), '--token', token);
    return;
  }
  if (action === 'stop') {
    const report = show(); const registered = report.processes.map((item) => item.expected.pid);
    const roots = ['frontend', 'backend'].filter((key) => existsSync(pidFile(key))).map(pid);
    const bound = [report.environment.SERVER_PORT, report.environment.WEB_PORT, report.environment.BFF_PORT].flatMap(listeners);
    const targets = assertOwnedStop({ registered, roots, table: processTable(), listeners: bound, isAlive: alive });
    // Complete the preflight for every live identity before the first signal.
    for (const item of report.processes.filter((entry) => alive(entry.expected.pid))) cli('runtime', 'process', 'check', name, item.key);
    for (const processPid of targets) {
      if (!alive(processPid)) continue;
      const item = report.processes.find((entry) => entry.expected.pid === processPid);
      cli('runtime', 'process', 'check', name, item.key);
      process.kill(processPid, 'SIGKILL');
    }
    for (let attempt = 0; attempt < 50; attempt++) {
      if (![report.environment.SERVER_PORT, report.environment.WEB_PORT, report.environment.BFF_PORT].flatMap(listeners).length) {
        for (const key of ['frontend', 'backend']) if (existsSync(pidFile(key))) unlinkSync(pidFile(key));
        return;
      }
      await new Promise((done) => setTimeout(done, 100));
    }
    throw new Error('Runtime listeners remain; preserved without port-based killing');
  }
  if (action !== 'verify') throw new Error(`Unknown runtime contract action: ${action}`);
  const input = baseline();
  const report = JSON.parse(cli('runtime', 'verify', name, '--json'));
  const backend = snapshot(pid('backend'));
  const web = input.frontend ? snapshot(listeners(report.environment.WEB_PORT)[0]) : null;
  const bff = input.frontend ? snapshot(listeners(report.environment.BFF_PORT)[0]) : null;
  assertLiveContract({ input, report, backend, web, bff });
  const health = await fetch(`http://127.0.0.1:${report.environment.SERVER_PORT}/actuator/health`, { signal: AbortSignal.timeout(10000) });
  if (!health.ok || (await health.json()).status !== 'UP') throw new Error('Backend health is not UP');
  const imports = existsSync(join(productDir, 'imports')) ? readdirSync(join(productDir, 'imports')).map((file) => readJson(join(productDir, 'imports', file))) : [];
  for (const receipt of imports) {
    const log = join(productDir, 'import-logs', `${receipt.logHash}.log`);
    if (receipt.runtime !== name || receipt.sourceCommit !== report.sources.find((item) => item.key === 'auraboot').expected.commit ||
        !existsSync(log) || sha256(readFileSync(log)) !== receipt.logHash) throw new Error('Config import receipt drift');
  }
  const staging = join(sd, 'pf4j-staging.tsv');
  const plugins = [];
  for (const row of readFileSync(staging, 'utf8').trim().split('\n').slice(1)) {
    const [code, sourceRoot, sourceJar, stagedJar, hash] = row.split('\t');
    if (sha256(readFileSync(stagedJar)) !== hash || sha256(readFileSync(sourceJar)) !== hash) throw new Error('PF4J artifact drift');
    plugins.push({ code, sourceRoot, jarPath: stagedJar, jarHash: hash });
  }
  const manifest = join(productDir, 'manifest.json');
  atomicJson(manifest, {
    schemaVersion: 1, runtime: name, generatedAt: new Date().toISOString(), sourceCommit: report.sources.find((item) => item.key === 'auraboot').expected.commit,
    artifactHash: input.backendHash, compositionKey: `sha256:${sha256(JSON.stringify(report.sources.map((item) => item.expected)))}`,
    sourceRoots: Object.fromEntries(report.sources.map((item) => [item.key, item.expected.root])),
    git: Object.fromEntries(report.sources.map((item) => [item.key, { commit: item.expected.commit, dirtyFingerprint: item.expected.dirtyFingerprint }])),
    backend: { pid: backend.pid, cwd: backend.cwd, serverPort: Number(report.environment.SERVER_PORT), bootJarPath: input.backendArtifact },
    frontend: input.frontend ? { vitePid: web.pid, viteCwd: web.cwd, bffPid: bff.pid, bffCwd: bff.cwd, webPort: Number(report.environment.WEB_PORT), bffPort: Number(report.environment.BFF_PORT), springBootUrl: bff.env.SPRING_BOOT_URL } : null,
    plugins, imports, verificationScope: 'source/artifact/process/namespace/import receipts; business semantics require E2E',
  });
  cli('runtime', 'manifest', 'publish', name, '--product', 'auraboot', '--from', manifest, '--verified-by', 'oss-golden-stack:verify-artifacts');
  cli('runtime', 'verify', name);
  console.log(`Verified OSS runtime ${name}; manifest published`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [action, name] = process.argv.slice(2);
  try {
    await execute(action, name, { repo: process.env.AURA_OSS_CONTRACT_REPO, workspace: process.env.AURA_WORKSPACE_ROOT,
      state: process.env.AURA_WORKSPACE_STATE_DIR, frontend: process.env.AURA_OSS_FRONTEND !== '0', importArgs: JSON.parse(process.env.AURA_OSS_IMPORT_ARGS || '[]') });
  } catch (error) { console.error(`OSS runtime contract failed: ${error.message}`); process.exitCode = 1; }
}
