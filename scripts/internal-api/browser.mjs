import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { canonicalJson, requireCondition, sha256 } from './contract.mjs';
import { loadBrowserCatalog } from './browser-catalog.mjs';
import { auditBrowserEvidence, readEvidenceFile } from './browser-evidence.mjs';

const sourceRoot = fileURLToPath(new URL('../../', import.meta.url));
const read = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const write = (file, value) => fs.writeFileSync(file, canonicalJson(value), { flag: 'wx', mode: 0o600 });
const git = (...args) => execFileSync('git', ['-C', sourceRoot, ...args], { encoding: 'utf8' }).trim();
function files(directory) {
  return fs.readdirSync(directory, { recursive: true }).map(file => path.join(directory, file))
    .filter(file => fs.lstatSync(file).isFile());
}

export function collectBrowserAttempt(directory, reviewPath) {
  const root = fs.realpathSync(directory), catalog = read(path.join(root, 'catalog.json'));
  const registry = read(path.join(root, 'registry.json')), before = read(path.join(root, 'before.json'));
  const after = read(path.join(root, 'after.json')), bytes = readEvidenceFile(root, path.join(root, 'results.json'));
  const originals = catalog.scenarios.flatMap(scene => files(path.join(root, 'artifacts'))
    .filter(file => path.basename(file) === scene.screenshot).map(file => ({ id: scene.id, path: file })));
  requireCondition(sha256(readEvidenceFile(root, path.join(root, 'runtime-manifest.json'))) === before.identity.runtimeManifestSha256,
    'Captured runtime manifest digest mismatch');
  const result = auditBrowserEvidence({ registry, catalog, before, after, reportBytes: bytes,
    report: JSON.parse(String(bytes)), root, originals, expected: before.identity,
    review: reviewPath ? read(reviewPath) : undefined });
  write(path.join(root, reviewPath ? 'reviewed-catalog-receipt.json' : 'functional-catalog-receipt.json'), result);
  return result;
}

export function runBrowserAttempt({ evidence, manifest, runtimeCommand, runtime }) {
  const root = path.resolve(evidence);
  requireCondition(!root.startsWith(path.resolve(sourceRoot) + path.sep) && root !== path.resolve(sourceRoot), 'Evidence must be outside source checkout');
  requireCondition(typeof manifest === 'string' && typeof runtimeCommand === 'string' && path.isAbsolute(manifest) && path.isAbsolute(runtimeCommand) && /^[a-z0-9][a-z0-9-]+$/.test(runtime), 'Explicit managed runtime manifest and verifier are required');
  requireCondition(git('status', '--porcelain') === '', 'Browser gate requires a clean source checkout');
  fs.mkdirSync(root, { mode: 0o700 });
  let before;
  const verify = () => execFileSync(runtimeCommand, ['runtime', 'verify', runtime], {
    cwd: path.dirname(runtimeCommand), encoding: 'utf8', timeout: 60_000, stdio: ['ignore', 'pipe', 'pipe'],
  });
  try {
    const verified = verify(), producer = read(manifest), sourceCommit = git('rev-parse', 'HEAD');
    requireCondition(producer.runtime === runtime && producer.sourceCommit === sourceCommit
      && producer.sourceRoots?.length === 1 && producer.sourceRoots[0].commit === sourceCommit
      && producer.sourceRoots[0].dirtyFingerprint === 'clean'
      && fs.realpathSync(producer.sourceRoots[0].root) === fs.realpathSync(sourceRoot), 'Runtime manifest source identity mismatch');
    for (const [env, value] of [['PLAYWRIGHT_BASE_URL', producer.webUrl], ['BACKEND_URL', producer.backendUrl], ['BFF_URL', producer.bffUrl]])
      requireCondition(process.env[env] === value, `Managed runtime environment mismatch: ${env}`);
    requireCondition((process.env.PG_DB ?? process.env.PGDATABASE) === producer.database && process.env.PW_SKIP_WEBSERVER === '1', 'Managed database or existing server binding missing');
    const catalog = read(path.join(sourceRoot, 'scripts/internal-api/catalog.json')), registry = loadBrowserCatalog(sourceRoot, catalog);
    const identity = { sourceCommit, sourceTree: git('rev-parse', 'HEAD^{tree}'), catalogSha256: sha256(canonicalJson(catalog)),
      registrySha256: sha256(canonicalJson(registry)), specSha256: sha256(fs.readFileSync(path.join(sourceRoot, 'contracts/internal-web-admin/openapi.json'))),
      typesSha256: sha256(fs.readFileSync(path.join(sourceRoot, 'web-admin/app/generated/internal-api/schema.d.ts'))),
      toolchainLockSha256: sha256(fs.readFileSync(path.join(sourceRoot, 'scripts/internal-api/toolchain/package-lock.json'))),
      runtimeManifestSha256: sha256(fs.readFileSync(manifest)) };
    const specs = [...new Set(registry.tests.map(row => row.file.replace(/^web-admin\//, '')))];
    const args = ['exec', 'playwright', 'test', ...specs, '--project=contract', '--project=internal-api-wire', '--no-deps', '--workers=1', '--retries=0', '--trace=retain-on-failure'];
    write(path.join(root, 'catalog.json'), catalog); write(path.join(root, 'registry.json'), registry);
    fs.copyFileSync(manifest, path.join(root, 'runtime-manifest.json'));
    fs.writeFileSync(path.join(root, 'runtime-before.log'), verified, { flag: 'wx', mode: 0o600 });
    before = { schemaVersion: 1, identity, nonce: randomUUID(), startedAt: new Date().toISOString(), runtimeVerified: true, args,
      runtimeVerificationSha256: sha256(verified), sourceClaim: 'clean-git-and-verified-running-product' };
    write(path.join(root, 'before.json'), before);
    const execution = spawnSync('pnpm', args, { cwd: path.join(sourceRoot, 'web-admin'), stdio: 'inherit', timeout: 30 * 60_000,
      env: { ...process.env, PW_PROFILE: 'contract', PW_INTERNAL_API_OWNED_FIXTURES_ONLY: '1', PW_ARTIFACT_DIR: path.join(root, 'artifacts'),
        PW_REPORT_DIR: path.join(root, 'report'), PW_RESULTS_JSON: path.join(root, 'results.json') } });
    let runtimeVerified = false, verificationError;
    try {
      const output = verify(); fs.writeFileSync(path.join(root, 'runtime-after.log'), output, { flag: 'wx', mode: 0o600 });
      runtimeVerified = sha256(fs.readFileSync(manifest)) === identity.runtimeManifestSha256;
    } catch (error) { verificationError = error.message; }
    const after = { schemaVersion: 1, nonce: before.nonce, identity: { ...identity,
      sourceCommit: git('rev-parse', 'HEAD'), sourceTree: git('rev-parse', 'HEAD^{tree}'), runtimeManifestSha256: sha256(fs.readFileSync(manifest)) },
      endedAt: new Date().toISOString(), exitCode: execution.status, executionError: execution.error?.message,
      sourceUnchanged: git('rev-parse', 'HEAD') === sourceCommit && git('status', '--porcelain') === '', runtimeVerified, verificationError,
      reportSha256: fs.existsSync(path.join(root, 'results.json')) ? sha256(fs.readFileSync(path.join(root, 'results.json'))) : null };
    write(path.join(root, 'after.json'), after);
    for (const file of files(root)) fs.chmodSync(file, 0o600);
    return collectBrowserAttempt(root);
  } catch (error) {
    write(path.join(root, 'failed-attempt.json'), { sourceCommit: before?.identity.sourceCommit ?? git('rev-parse', 'HEAD'),
      claim: 'failed-browser-gate-attempt', error: error.message, endedAt: new Date().toISOString() });
    throw error;
  }
}

export function main(args) {
  const mode = args.shift(), values = {};
  requireCondition(['run', 'collect'].includes(mode), 'Usage: browser.mjs run --evidence <fresh-dir> --manifest <runtime-manifest> --runtime-command <workspace-aura> --runtime <name> | collect --evidence <attempt> --review <review.json>');
  while (args.length) {
    const flag = args.shift(), value = args.shift();
    requireCondition(['--evidence','--manifest','--runtime-command','--runtime','--review'].includes(flag)
      && value && !value.startsWith('--') && !Object.hasOwn(values, flag), 'Invalid browser gate arguments');
    values[flag] = value;
  }
  requireCondition(values['--evidence'], 'Browser evidence directory is required');
  const allowed = mode === 'run' ? ['--evidence','--manifest','--runtime-command','--runtime'] : ['--evidence','--review'];
  requireCondition(Object.keys(values).every(key => allowed.includes(key)) && (mode !== 'collect' || values['--review']), 'Collect requires an explicit original review; run requires runtime arguments');
  const result = mode === 'run' ? runBrowserAttempt({ evidence: values['--evidence'], manifest: values['--manifest'], runtimeCommand: values['--runtime-command'], runtime: values['--runtime'] })
    : collectBrowserAttempt(values['--evidence'], values['--review']);
  console.log(JSON.stringify({ claim: result.claim, sourceCommit: result.identity.sourceCommit, counts: result.counts,
    responses: result.responses, functionalVerdict: result.functionalVerdict, visualVerdict: result.visualVerdict }));
}
if (process.argv[1] && import.meta.url === pathToFileURL(fs.realpathSync(process.argv[1])).href) {
  try { main(process.argv.slice(2)); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
