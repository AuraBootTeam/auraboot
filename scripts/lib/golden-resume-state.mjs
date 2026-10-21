import { goldenStackState } from './golden-stack-state.mjs';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, realpathSync, readdirSync, statSync, mkdirSync, writeFileSync, renameSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { pathToFileURL } from 'node:url';
import { liveEnvironment } from './golden-product-identity.mjs';
import { readPrivateRedisConfig, privateRedisPort } from './golden-private-redis.mjs';

const TELEMETRY_KEYS = ['SESSION_SECRET_FILE', 'AURA_WEB_MODE', 'OTEL_EXPORTER_OTLP_TRACES_ENDPOINT', 'MANAGEMENT_TRACING_ENABLED',
  'MANAGEMENT_TRACING_SAMPLING_PROBABILITY', 'OTEL_TRACES_SAMPLER', 'OTEL_TRACES_SAMPLER_ARG',
  'VITE_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT', 'VITE_OTEL_TRACES_SAMPLE_RATE'];
const need = (condition, message) => { if (!condition) throw new Error(message); };
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
function run(cli, args) {
  try { return execFileSync(cli, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }); }
  catch { throw new Error('Resume identity probe failed'); }
}

// Follow dependency links and hash bytes, not timestamps or a node_modules pathname.
export function fingerprintDirectory(root) {
  const digest = createHash('sha256'), visited = new Set();
  function visit(file, relative) {
    const real = realpathSync(file), info = statSync(real);
    digest.update(JSON.stringify([relative, real, info.mode & 0o777]) + '\n');
    if (info.isDirectory()) {
      if (visited.has(real)) return;
      visited.add(real);
      for (const name of readdirSync(real).sort()) visit(join(real, name), `${relative}/${name}`);
    } else {
      need(info.isFile(), 'Unsupported retained dependency entry');
      digest.update(hash(readFileSync(real)) + '\n');
    }
  }
  visit(root, '.');
  return { root: realpathSync(root), hash: digest.digest('hex') };
}

export function verifyResumeState({ report, repo, plan, planHash, manifest, dependencies, plugins, logging = null, frontendBuild = null, sessionSecretFile = null, isolatedRedis = null }) {
  need(report.verdict === 'ok', 'Workspace identity verification failed');
  need(plan.schemaVersion === 1 && plan.runtime === report.runtime && plan.repo === repo,
    'Resume plan owner mismatch');
  need(manifest.runtime === report.runtime && manifest.resumePlan?.hash === planHash,
    'Resume plan is not bound to the published product manifest');
  need(manifest.sourceCommit === plan.sourceCommit && manifest.backendArtifact.path === plan.backend.path &&
    manifest.backendArtifact.hash === plan.backend.hash, 'Product manifest launch identity mismatch');
  const source = report.sources.find(item => ['auraboot', 'core'].includes(item.key));
  const artifact = report.artifacts.find(item => item.key === 'backend');
  need(source?.status === 'ok' && source.expected.root === repo && source.expected.commit === plan.sourceCommit,
    'Resume source identity mismatch');
  need(artifact?.status === 'ok' && artifact.path === plan.backend.path &&
    artifact.expected.hash === plan.backend.hash && artifact.expected.sourceCommit === plan.sourceCommit,
    'Resume backend identity mismatch');
  need(['true', 'false'].includes(plan.llmStubMode), 'Invalid retained LLM mode');
  for (const key of ['backend', 'web', 'bff']) need(Number.isInteger(plan.ports[key]) &&
    plan.ports[key] > 0 && plan.ports[key] <= 65535 && report.ports[key]?.port === plan.ports[key], 'Resume port mismatch');
  need(typeof plan.database === 'string' && Boolean(plan.database) && typeof plan.redisDatabase === 'string' &&
    /^\d+$/.test(plan.redisDatabase) && report.environment.POSTGRES_DB === plan.database &&
    report.environment.REDIS_DATABASE === plan.redisDatabase,
    'Resume service namespace mismatch');
  for (const [expected, actual] of [[plan.dependencies, dependencies], [plan.plugins, plugins]]) {
    need(expected.root === actual.root && expected.hash === actual.hash, 'Retained launch input changed');
  }
  need(!/[\r\n]/.test(plan.backend.path), 'Unsupported backend path');
  if (plan.logging || logging) need(plan.logging && logging && plan.logging.path === logging.path &&
    plan.logging.hash === logging.hash, 'Retained logging input changed');
  if (plan.frontendBuild) {
    need(plan.telemetryEnvironment?.AURA_WEB_MODE === 'production' && frontendBuild &&
      plan.frontendBuild.root === frontendBuild.root && plan.frontendBuild.hash === frontendBuild.hash,
      'Retained production frontend changed');
    need(plan.sessionSecretFile && sessionSecretFile && plan.sessionSecretFile.root === sessionSecretFile.root &&
      plan.sessionSecretFile.hash === sessionSecretFile.hash, 'Retained production session configuration changed');
  }
  need(typeof (plan.redisHost ?? '127.0.0.1') === 'string' && !/[\r\n]/.test(plan.redisHost || ''), 'Invalid Redis host');
  const redisPort = plan.redisPort ?? '6379';
  need(typeof redisPort === 'string' && /^\d+$/.test(redisPort) && Number(redisPort) > 0 &&
    Number(redisPort) <= 65535, 'Invalid retained Redis port');
  if (plan.redisPort !== undefined) {
    need(manifest.redisEndpoint && String(manifest.redisEndpoint.port) === redisPort &&
      manifest.redisEndpoint.host === plan.redisHost && manifest.redisEndpoint.database === plan.redisDatabase,
      'Retained Redis endpoint changed');
  }
  if (plan.isolatedRedis || isolatedRedis || manifest.redisEndpoint?.isolated) {
    need(plan.isolatedRedis && isolatedRedis && manifest.redisEndpoint?.isolated &&
      plan.isolatedRedis.path === isolatedRedis.path && plan.isolatedRedis.hash === isolatedRedis.hash,
      'Retained private Redis configuration changed');
    need(isolatedRedis.config.port === privateRedisPort(redisPort) &&
      isolatedRedis.config.database === plan.redisDatabase && isolatedRedis.config.host === plan.redisHost &&
      isolatedRedis.config.host === '127.0.0.1' && isolatedRedis.config.runtime === report.runtime &&
      isolatedRedis.config.repo === repo && typeof isolatedRedis.config.dataDirectory === 'string' &&
      isolatedRedis.config.dataDirectory === plan.isolatedRedis.config.dataDirectory,
      'Retained private Redis owner changed');
  }
  for (const [key, value] of Object.entries(plan.telemetryEnvironment || {})) {
    need(TELEMETRY_KEYS.includes(key) && typeof value === 'string' && !/[\r\n]/.test(value), 'Invalid telemetry environment');
  }
  return { backend: plan.backend.path, llmStubMode: plan.llmStubMode,
    redisHost: plan.redisHost ?? '127.0.0.1', redisPort,
    ...(plan.isolatedRedis ? { isolatedRedisPort: redisPort } : {}),
    telemetryEnvironment: plan.telemetryEnvironment || {},
    ...(plan.logging ? { loggingConfig: plan.logging.path } : {}) };
}

function loggingInput(report, name) {
  const file = join(goldenStackState(report.stateDir, name), 'backend-logback.xml');
  return existsSync(file) ? { path: realpathSync(file), hash: hash(readFileSync(file)) } : null;
}

function atomicJson(file, value) {
  mkdirSync(dirname(file), { recursive: true });
  const temporary = `${file}.${process.pid}.tmp`;
  writeFileSync(temporary, JSON.stringify(value, null, 2) + '\n', { mode: 0o600 });
  renameSync(temporary, file);
}
export function recordResumeState(name, repo, cli) {
  repo = realpathSync(repo);
  const report = JSON.parse(run(cli, ['runtime', 'explain', name, '--json']));
  need(report.verdict === 'ok', 'Workspace identity verification failed');
  const root = join(report.stateDir, 'runtimes', name, 'oss-golden-stack');
  const file = join(root, 'manifest.json'), manifest = JSON.parse(readFileSync(file, 'utf8'));
  const backend = report.processes.find(item => item.key === 'backend');
  need(backend?.status === 'ok', 'Verified backend is missing');
  const backendEnv = liveEnvironment(backend.expected.pid);
  const web = report.processes.find(item => item.key === 'web');
  const liveEnv = { ...backendEnv, ...(web ? liveEnvironment(web.expected.pid) : {}) };
  const llmStubMode = backendEnv.AGENT_LLM_STUB_MODE;
  need(['true', 'false'].includes(llmStubMode), 'Live LLM mode is not reproducible');
  const plan = { schemaVersion: 1, runtime: name, repo, sourceCommit: manifest.sourceCommit,
    backend: manifest.backendArtifact, llmStubMode,
    ports: Object.fromEntries(Object.entries(report.ports).map(([key, item]) => [key, item.port])),
    database: manifest.database, redisDatabase: manifest.redisDatabase,
    redisHost: backendEnv.SPRING_DATA_REDIS_HOST ?? '',
    redisPort: backendEnv.SPRING_DATA_REDIS_PORT,
    telemetryEnvironment: Object.fromEntries(TELEMETRY_KEYS.filter(key => liveEnv[key] !== undefined).map(key => [key, liveEnv[key]])),
    logging: loggingInput(report, name),
    dependencies: fingerprintDirectory(join(repo, 'web-admin/node_modules')),
    plugins: fingerprintDirectory(join(goldenStackState(report.stateDir, name), 'pf4j-plugins')) };
  const isolatedRedis = readPrivateRedisConfig(report, repo);
  if (isolatedRedis) plan.isolatedRedis = isolatedRedis;
  if (liveEnv.AURA_WEB_MODE === 'production') {
    plan.frontendBuild = fingerprintDirectory(join(repo, 'web-admin/build'));
    need(Boolean(liveEnv.SESSION_SECRET_FILE), 'Production session input missing');
    plan.sessionSecretFile = fingerprintDirectory(liveEnv.SESSION_SECRET_FILE);
    manifest.frontendBuild = plan.frontendBuild;
  }
  const planFile = join(root, 'resume.json');
  const planHash = hash(JSON.stringify(plan, null, 2) + '\n');
  verifyResumeState({ report, repo, plan, planHash, manifest: { ...manifest, resumePlan: { hash: planHash } },
    dependencies: plan.dependencies, plugins: plan.plugins, logging: plan.logging, frontendBuild: plan.frontendBuild,
    sessionSecretFile: plan.sessionSecretFile, isolatedRedis });
  atomicJson(planFile, plan);
  manifest.resumePlan = { hash: hash(readFileSync(planFile)) };
  atomicJson(file, manifest);
  run(cli, ['runtime', 'manifest', 'publish', name, '--product', 'oss-golden-stack', '--from', file,
    '--verified-by', 'oss-golden-stack:verify-artifacts']);
  run(cli, ['runtime', 'verify', name]);
}
export function loadResumeState(name, repo, cli) {
  repo = realpathSync(repo);
  const report = JSON.parse(run(cli, ['runtime', 'explain', name, '--json']));
  need(report.metadata?.lifecycle === 'resuming', 'Resume requires the Workspace lifecycle transaction');
  const root = join(report.stateDir, 'runtimes', name, 'oss-golden-stack');
  const bytes = readFileSync(join(root, 'resume.json')), plan = JSON.parse(bytes);
  const manifest = JSON.parse(readFileSync(join(root, 'manifest.json'), 'utf8'));
  return verifyResumeState({ report, repo, plan, planHash: hash(bytes), manifest,
    dependencies: fingerprintDirectory(join(repo, 'web-admin/node_modules')),
    plugins: fingerprintDirectory(join(goldenStackState(report.stateDir, name), 'pf4j-plugins')),
    logging: loggingInput(report, name), frontendBuild: plan.frontendBuild ? fingerprintDirectory(join(repo, 'web-admin/build')) : null,
    sessionSecretFile: plan.sessionSecretFile ? fingerprintDirectory(plan.sessionSecretFile.root) : null,
    isolatedRedis: readPrivateRedisConfig(report, repo) });
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const [action, name, repo, cli] = process.argv.slice(2);
    if (action === 'record') recordResumeState(name, repo, cli);
    else if (action === 'load-env') {
      const state = loadResumeState(name, repo, cli);
      for (const [key,value] of Object.entries(state.telemetryEnvironment)) console.log(`${key}=${value}`);
    } else if (action === 'load') {
      const state = loadResumeState(name, repo, cli);
      console.log(state.backend); console.log(state.llmStubMode); console.log(state.loggingConfig || ''); console.log(state.redisHost);
      console.log(state.redisPort); console.log(state.isolatedRedisPort ?? '');
    } else throw new Error('Unknown resume action');
  } catch { console.error('Retained golden launch identity refused; no database initialization performed'); process.exitCode = 1; }
}
