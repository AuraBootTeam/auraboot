import fs from 'node:fs';
import path from 'node:path';
import { run } from 'node:test';
import { execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { canonicalJson, normalizeDocument, operations, requireCondition, sha256 } from './contract.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/** A green process exit cannot substitute for the registered test denominator. */
export function auditTests(expected, actual) {
  requireCondition(expected.length > 0 && new Set(expected).size === expected.length, 'Registered unit tests are empty or duplicated');
  requireCondition(actual.length === expected.length, `Unit test count mismatch: expected ${expected.length}, actual ${actual.length}`);
  const seen = new Set();
  for (const row of actual) {
    requireCondition(expected.includes(row.name) && !seen.has(row.name), `Unexpected or repeated unit test: ${row.name}`);
    requireCondition(row.status === 'passed' && !row.skip && !row.todo, `Unit test did not pass without skips: ${row.name}`);
    seen.add(row.name);
  }
  return { registered: expected.length, executed: actual.length, passed: seen.size, skipped: 0 };
}

export async function main(args) {
  requireCondition([2, 4].includes(args.length) && args[0] === '--evidence' && (args.length === 2 || args[2] === '--archive-source' && /^[0-9a-f]{40}$/.test(args[3])), 'Usage: node foundation.mjs --evidence <fresh-directory> [--archive-source <40-character-SHA>]');
  const archiveSource = args[3];
  requireCondition(!archiveSource || !fs.existsSync(path.join(root, '.git')), 'Archive source mode requires a git-free frozen archive');
  const evidence = path.resolve(args[1]);
  requireCondition(!evidence.startsWith(root + path.sep), 'Gate evidence must be outside the source checkout');
  fs.mkdirSync(evidence); // An attempt never reuses an earlier receipt or report.
  const git = (...values) => execFileSync('git', ['-C', root, ...values], { encoding: 'utf8' }).trim();
  const commit = archiveSource ?? git('rev-parse', 'HEAD');
  const result = { schemaVersion: 1, claim: 'offline-contract-consistency-only', sourceCommit: commit, sourceClaim: archiveSource ? 'caller-frozen-archive' : 'clean-git-checkout', verdict: 'fail', checks: [], tests: [] };
  try {
    if (!archiveSource) requireCondition(git('status', '--porcelain') === '', 'Foundation gate requires a clean source checkout');
    const scopedFiles = fs.readdirSync(path.join(root, 'scripts/internal-api'), {recursive:true})
      .filter(file => !file.includes('node_modules') && fs.statSync(path.join(root, 'scripts/internal-api', file)).isFile())
      .map(file => `scripts/internal-api/${file}`)
      .concat(['contracts/internal-web-admin/openapi.json', 'web-admin/app/generated/internal-api/schema.d.ts']);
    const inputDigest = () => sha256(scopedFiles.sort().map(file => `${file}\n${sha256(fs.readFileSync(path.join(root,file)))}`).join('\n'));
    result.inputSha256 = inputDigest();
    const catalog = JSON.parse(fs.readFileSync(path.join(root, 'scripts/internal-api/catalog.json'), 'utf8'));
    const profile = catalog.profiles.foundation;
    const file = 'contracts/internal-web-admin/openapi.json';
    const raw = fs.readFileSync(path.join(root, file), 'utf8');
    const document = normalizeDocument(JSON.parse(raw));
    requireCondition(raw === canonicalJson(document), 'Committed internal API snapshot is not canonical');
    result.contractSha256 = sha256(raw);
    result.operations = operations(document).length;
    result.toolchainLockSha256 = sha256(fs.readFileSync(path.join(root, 'scripts/internal-api/toolchain/package-lock.json')));
    const command = (name, script, values) => {
      execFileSync(process.execPath, [script, ...values], { cwd: root, stdio: 'pipe', timeout: 120_000 });
      result.checks.push({ name, verdict: 'pass' });
    };
    command('generated-types', 'scripts/internal-api/toolchain/generate.mjs', [file, 'web-admin/app/generated/internal-api/schema.d.ts', '--check']);
    command('standalone-schema-typecheck', 'scripts/internal-api/toolchain/node_modules/typescript/bin/tsc', ['-p', profile.generatedTypeProject]);
    const files = profile.unitFiles.map(file => path.join(root, 'scripts/internal-api', file));
    requireCondition(files.length > 0 && new Set(files).size === files.length, 'Unit file registration is empty or duplicated');
    for await (const event of run({ files, concurrency: 1 })) {
      if (!['test:pass', 'test:fail'].includes(event.type) || event.data.details?.type === 'suite') continue;
      const row = { name: event.data.name, status: event.type === 'test:pass' ? 'passed' : 'failed', skip: Boolean(event.data.skip), todo: Boolean(event.data.todo) };
      result.tests.push(row);
      if (row.status === 'failed') console.error(event.data.details?.error);
    }
    result.counts = auditTests(profile.unitTests, result.tests);
    requireCondition(inputDigest() === result.inputSha256, 'Contract inputs changed during the foundation gate');
    if (!archiveSource) requireCondition(git('rev-parse', 'HEAD') === commit && git('status', '--porcelain') === '', 'Source changed during the foundation gate');
    result.generatedTypesSha256 = sha256(fs.readFileSync(path.join(root, 'web-admin/app/generated/internal-api/schema.d.ts')));
    result.verdict = 'pass';
  } catch (error) {
    result.error = error.message;
    throw error;
  } finally {
    fs.writeFileSync(path.join(evidence, 'attempt.json'), canonicalJson(result), { flag: 'wx' });
    if (result.verdict === 'pass') fs.writeFileSync(path.join(evidence, 'foundation-receipt.json'), canonicalJson(result), { flag: 'wx' });
  }
  console.log(`Offline internal API consistency verified: ${result.counts.passed} registered tests; not migration or release acceptance`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
