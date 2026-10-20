import { realpathSync } from 'node:fs';
import fs from 'node:fs/promises';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { assertSameContract, canonicalJson, normalizeDocument, operations, requireCondition, sha256 } from './contract.mjs';

export async function fetchDocument(url, request = fetch) {
  const parsed = new URL(url);
  requireCondition(['http:', 'https:'].includes(parsed.protocol) && !parsed.username && !parsed.password,
    'OpenAPI origin must be HTTP(S) without embedded credentials');
  requireCondition(parsed.pathname === '/v3/api-docs/internal-web-admin' && !parsed.search && !parsed.hash,
    'Only the internal-web-admin OpenAPI endpoint can be exported');
  const response = await request(url, { headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(90_000), redirect: 'error' });
  requireCondition(response.ok, `Internal OpenAPI export failed: HTTP ${response.status}`);
  return normalizeDocument(await response.json());
}

export async function exportContract({ url, output, check, receipt, source, request }) {
  requireCondition(Boolean(output) !== Boolean(check), 'Choose exactly one of --output or --check');
  if (receipt) {
    try { await fs.access(receipt); throw new Error('Receipt already exists; use a new evidence attempt'); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  const git = (...args) => execFileSync('git', ['-C', source, ...args], { encoding: 'utf8' }).trim();
  const commit = source ? git('rev-parse', 'HEAD') : null;
  if (source) requireCondition(git('status', '--porcelain') === '', 'Contract export requires a clean source checkout');
  const document = await fetchDocument(url, request);
  const normalized = canonicalJson(document);
  if (check) assertSameContract(JSON.parse(await fs.readFile(check, 'utf8')), document);
  if (source) requireCondition(git('rev-parse', 'HEAD') === commit && git('status', '--porcelain') === '', 'Source changed during export');
  if (output) { await fs.mkdir(path.dirname(output), { recursive: true }); await fs.writeFile(output, normalized); }
  const result = { schemaVersion: 1, claim: 'contract-export-only', mode: check ? 'live-drift-check' : 'export',
    verdict: 'pass', sourceCommit: commit, contractSha256: sha256(normalized),
    operations: operations(document).length, generatedAt: new Date().toISOString() };
  if (receipt) {
    await fs.mkdir(path.dirname(receipt), { recursive: true });
    await fs.writeFile(receipt, canonicalJson(result), { flag: 'wx' });
  }
  return result;
}

export async function main(args) {
  const allowed = new Set(['url', 'output', 'check', 'receipt', 'source']);
  const options = {};
  while (args.length) {
    const flag = args.shift(), key = flag?.slice(2);
    requireCondition(flag?.startsWith('--') && allowed.has(key) && !Object.hasOwn(options, key), `Invalid or repeated option: ${flag}`);
    const value = args.shift(); requireCondition(value && !value.startsWith('--'), `Missing value for ${flag}`);
    options[key] = value;
  }
  requireCondition(options.url, '--url is required');
  console.log(canonicalJson(await exportContract(options)).trim());
}

if (process.argv[1] && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href) {
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
