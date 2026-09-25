#!/usr/bin/env node
/** Build-time ownership admission. Does not execute SQL or grant deployment approval. */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { auditPackage } from './schema-ownership-audit.mjs';

function requireThat(condition, message) { if (!condition) throw new Error(message); }
const objectKey = (name) => name.includes('.') ? name : `public.${name}`;

export function auditComposition(packages) {
  requireThat(Array.isArray(packages) && packages.length > 0, 'explicit migration packages required');
  const byId = new Map();
  const receipts = [];
  for (const entry of packages) {
    const receipt = auditPackage(entry.root, entry.contract);
    requireThat(!byId.has(receipt.packageId), `duplicate package: ${receipt.packageId}`);
    byId.set(receipt.packageId, entry);
    receipts.push(receipt);
  }
  const visiting = new Set(); const visited = new Set(); const dependencyOrder = [];
  function visit(id) {
    requireThat(byId.has(id), `missing dependency package: ${id}`);
    requireThat(!visiting.has(id), `migration package dependency cycle: ${id}`);
    if (visited.has(id)) return;
    visiting.add(id);
    for (const dependency of byId.get(id).contract.dependencies) visit(dependency);
    visiting.delete(id); visited.add(id); dependencyOrder.push(id);
  }
  for (const id of [...byId.keys()].sort()) visit(id);
  const versions = new Map(); const repeatables = new Map(); const owners = new Map();
  for (const [id, entry] of byId) {
    for (const migration of entry.contract.migrations) {
      // Version identity is numeric, independent of description and package location.
      const parsed = migration.file.match(/^V([0-9]+(?:[._][0-9]+)*)__([^/]+)\.sql$/);
      if (parsed) {
        const parts = parsed[1].split(/[._]/).map((part) => BigInt(part).toString());
        while (parts.length > 1 && parts.at(-1) === '0') parts.pop();
        const key = parts.join('.');
        requireThat(!versions.has(key), `global migration version collision: ${key} (${versions.get(key)}, ${id})`);
        versions.set(key, id);
      } else {
        const key = migration.file.slice(3, -4).replaceAll('_', ' ');
        requireThat(!repeatables.has(key), `repeatable migration description collision: ${key}`);
        repeatables.set(key, id);
      }
    }
    for (const [name, object] of Object.entries(entry.contract.objects)) {
      const key = objectKey(name);
      requireThat(!owners.has(key) || owners.get(key) === object.owner, `conflicting object owner: ${key}`);
      owners.set(key, object.owner);
      requireThat(byId.has(object.owner), `object owner package missing: ${key}:${object.owner}`);
    }
  }
  for (const [key, owner] of owners) {
    requireThat(Object.entries(byId.get(owner).contract.objects).some(([name, object]) =>
      objectKey(name) === key && object.owner === owner), `object not declared by its owner: ${key}:${owner}`);
  }
  return { schemaVersion: 1, verdict: 'PASS', claim: 'reviewed-migration-composition-only',
    historyTable: 'ab_flyway_schema_history', dependencyOrder,
    executionOrder: 'Flyway global version order; dependencyOrder is package admission only',
    packages: receipts, migrationCount: receipts.reduce((n, r) => n + r.migrationCount, 0), objectCount: owners.size };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    requireThat(process.argv.length === 4 && process.argv[2] === '--manifest', 'usage: migration-composition-audit.mjs --manifest <file>');
    const file = resolve(process.argv[3]); const manifest = JSON.parse(readFileSync(file, 'utf8'));
    requireThat(manifest.schemaVersion === 1 && Array.isArray(manifest.packages), 'unsupported composition manifest');
    const packages = manifest.packages.map((entry) => {
      requireThat(typeof entry.root === 'string' && typeof entry.contract === 'string', 'package root and contract paths required');
      return { root: resolve(dirname(file), entry.root), contract: JSON.parse(readFileSync(resolve(dirname(file), entry.contract), 'utf8')) };
    });
    console.log(JSON.stringify(auditComposition(packages), null, 2));
  } catch (error) { console.error(`migration-composition: ${error.message}`); process.exitCode = 2; }
}
