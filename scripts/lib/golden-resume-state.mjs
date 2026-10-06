import { goldenStackState } from './golden-stack-state.mjs';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, realpathSync, readdirSync, statSync, mkdirSync, writeFileSync, renameSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { pathToFileURL } from 'node:url';
import { liveEnvironment } from './golden-product-identity.mjs';

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

export function verifyResumeState({ report, repo, plan, planHash, manifest, dependencies, plugins, logging = null }) {
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
  return { backend: plan.backend.path, llmStubMode: plan.llmStubMode,
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
  const llmStubMode = liveEnvironment(backend.expected.pid).AGENT_LLM_STUB_MODE;
  need(['true', 'false'].includes(llmStubMode), 'Live LLM mode is not reproducible');
  const plan = { schemaVersion: 1, runtime: name, repo, sourceCommit: manifest.sourceCommit,
    backend: manifest.backendArtifact, llmStubMode,
    ports: Object.fromEntries(Object.entries(report.ports).map(([key, item]) => [key, item.port])),
    database: manifest.database, redisDatabase: manifest.redisDatabase,
    logging: loggingInput(report, name),
    dependencies: fingerprintDirectory(join(repo, 'web-admin/node_modules')),
    plugins: fingerprintDirectory(join(goldenStackState(report.stateDir, name), 'pf4j-plugins')) };
  const planFile = join(root, 'resume.json');
  const planHash = hash(JSON.stringify(plan, null, 2) + '\n');
  verifyResumeState({ report, repo, plan, planHash, manifest: { ...manifest, resumePlan: { hash: planHash } },
    dependencies: plan.dependencies, plugins: plan.plugins, logging: plan.logging });
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
    logging: loggingInput(report, name) });
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const [action, name, repo, cli] = process.argv.slice(2);
    if (action === 'record') recordResumeState(name, repo, cli);
    else if (action === 'load') {
      const state = loadResumeState(name, repo, cli);
      console.log(state.backend); console.log(state.llmStubMode); console.log(state.loggingConfig || '');
    } else throw new Error('Unknown resume action');
  } catch { console.error('Retained golden launch identity refused; no database initialization performed'); process.exitCode = 1; }
}
