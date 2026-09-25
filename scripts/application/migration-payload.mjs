#!/usr/bin/env node
/** Reviewed SQL payload packaging; runtime and release provenance are separate requirements. */
import { createHash } from 'node:crypto';
import { lstatSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { auditComposition } from './migration-composition-audit.mjs';

const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const encode = (value) => `${JSON.stringify(value, null, 2)}\n`;
function need(condition, message) { if (!condition) throw new Error(message); }
function regular(file) { const s = lstatSync(file); need(s.isFile() && !s.isSymbolicLink(), `regular file required: ${file}`); return readFileSync(file); }
function walk(root, prefix = '') {
  const files = [];
  for (const entry of readdirSync(root).sort()) {
    const path = join(root, entry); const relative = prefix + entry; const stat = lstatSync(path);
    need(!stat.isSymbolicLink(), `payload symlink rejected: ${relative}`);
    if (stat.isDirectory()) files.push(...walk(path, `${relative}/`));
    else { need(stat.isFile(), `non-regular payload entry: ${relative}`); files.push(relative); }
  }
  return files;
}
function loadComposition(file) {
  const manifest = JSON.parse(regular(file));
  need(manifest.schemaVersion === 1 && Array.isArray(manifest.packages), 'unsupported composition manifest');
  return manifest.packages.map((entry) => {
    need(typeof entry.root === 'string' && typeof entry.contract === 'string', 'explicit package paths required');
    return { root: resolve(dirname(file), entry.root), contract: JSON.parse(regular(resolve(dirname(file), entry.contract))) };
  });
}

export function stagePayload(compositionFile, output) {
  const packages = loadComposition(resolve(compositionFile));
  const audit = auditComposition(packages);
  output = resolve(output);
  for (const entry of packages) {
    const path = relative(resolve(entry.root), output);
    need(path === '..' || path.startsWith(`..${sep}`), 'payload output must be outside source migration directories');
  }
  // Exclusive destination, retained on failure for diagnosis; never overwrite a prior payload.
  mkdirSync(output);
  writeFileSync(join(output, '.incomplete'), 'payload assembly in progress\n', { flag: 'wx' });
  const files = {};
  function put(path, bytes) {
    mkdirSync(dirname(join(output, path)), { recursive: true });
    writeFileSync(join(output, path), bytes, { flag: 'wx', mode: 0o444 });
    files[path] = sha(bytes);
  }
  const packed = [];
  for (const entry of [...packages].sort((a, b) => a.contract.packageId.localeCompare(b.contract.packageId))) {
    const id = entry.contract.packageId;
    const root = `packages/${id}/sql`; const contract = `packages/${id}/ownership.json`;
    put(contract, encode(entry.contract));
    for (const migration of entry.contract.migrations) {
      need(!/[\\/\r\n]/.test(migration.file), 'unsupported migration filename');
      const bytes = regular(join(entry.root, migration.file));
      need(sha(bytes) === migration.sha256, `source changed while packaging: ${id}/${migration.file}`);
      put(`${root}/${migration.file}`, bytes);
    }
    packed.push({ id, root, contract });
  }
  // Detect changed/added SQL while copying; inspect the packaged bytes independently as well.
  auditComposition(packages);
  const manifest = { schemaVersion: 1, kind: 'reviewed-migration-payload',
    claim: 'SQL payload only; no Flyway runtime, frozen source provenance or release qualification',
    historyTable: audit.historyTable, packages: packed, files };
  const bytes = encode(manifest);
  writeFileSync(join(output, 'payload.json'), bytes, { flag: 'wx', mode: 0o444 });
  const digest = sha(bytes);
  // Internal pre-publication validation permits only this assembly marker.
  verifyPayloadInternal(output, digest, true);
  rmSync(join(output, '.incomplete'));
  return { root: output, manifestSha256: digest, migrationCount: audit.migrationCount, claim: manifest.claim };
}

export function verifyPayload(root, expectedDigest) {
  return verifyPayloadInternal(root, expectedDigest, false);
}

function verifyPayloadInternal(root, expectedDigest, assembling) {
  root = resolve(root);
  const stat = lstatSync(root);
  need(stat.isDirectory() && !stat.isSymbolicLink(), 'payload root must be a real directory');
  need(/^[a-f0-9]{64}$/.test(expectedDigest || ''), 'externally pinned payload manifest SHA256 required');
  const bytes = regular(join(root, 'payload.json'));
  need(sha(bytes) === expectedDigest, 'payload manifest digest mismatch');
  const manifest = JSON.parse(bytes);
  need(manifest.schemaVersion === 1 && manifest.kind === 'reviewed-migration-payload'
    && manifest.historyTable === 'ab_flyway_schema_history', 'unsupported migration payload');
  need(manifest.files && typeof manifest.files === 'object' && !Array.isArray(manifest.files), 'payload file inventory required');
  need(Array.isArray(manifest.packages) && manifest.packages.length > 0, 'payload package inventory required');
  const expectedFiles = ['payload.json', ...Object.keys(manifest.files), ...(assembling ? ['.incomplete'] : [])].sort();
  need(JSON.stringify(walk(root)) === JSON.stringify(expectedFiles), 'payload file denominator mismatch or incomplete assembly');
  for (const [path, digest] of Object.entries(manifest.files)) {
    need(/^packages\/[a-z][a-z0-9-]*\/(ownership\.json|sql\/[^/\\]+\.sql)$/.test(path), 'invalid payload path');
    need(sha(regular(join(root, path))) === digest, `payload file digest mismatch: ${path}`);
  }
  const used = new Set();
  const packages = manifest.packages.map((entry) => {
    need(/^[a-z][a-z0-9-]*$/.test(entry.id || '') && !used.has(entry.id), 'invalid or duplicate payload package');
    used.add(entry.id);
    need(entry.root === `packages/${entry.id}/sql` && entry.contract === `packages/${entry.id}/ownership.json`, 'payload package paths differ');
    const contract = JSON.parse(regular(join(root, entry.contract)));
    need(contract.packageId === entry.id, 'payload package identity mismatch');
    return { root: join(root, entry.root), contract };
  });
  need(Object.keys(manifest.files).every((path) => used.has(path.split('/')[1])), 'unselected package files in payload');
  return { ...auditComposition(packages), manifestSha256: expectedDigest };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const [action, input, argument, ...extra] = process.argv.slice(2);
    need(extra.length === 0 && input && argument && ['stage', 'verify'].includes(action),
      'usage: migration-payload.mjs stage <composition.json> <new-directory> | verify <directory> <manifest-sha256>');
    console.log(encode(action === 'stage' ? stagePayload(input, argument) : verifyPayload(input, argument)));
  } catch (error) { console.error(`migration-payload: ${error.message}`); process.exitCode = 2; }
}
