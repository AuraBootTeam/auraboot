#!/usr/bin/env node
/** Review-bound migration ownership. This inventory never rewrites released SQL. */
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { splitSqlStatements, stripSqlComments } from './migration-ownership.mjs';

const digest = value => createHash('sha256').update(value).digest('hex');
const ID = /^[a-z][a-z0-9-]*$/;
const validId = value => typeof value === 'string' && ID.test(value);
const OBJECT = /^(?:[a-z_][a-z0-9_]*\.)?[a-z_][a-z0-9_]*$/;
const LIFECYCLES = new Set(['static-schema', 'model-publish', 'tenant-migration', 'platform-catalog-data']);
const OPERATIONS = new Set(['create', 'alter', 'drop', 'index', 'seed', 'backfill', 'reference']);

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

function assertDirectory(root) {
  const stat = lstatSync(root);
  requireCondition(stat.isDirectory() && !stat.isSymbolicLink(), 'package root must be a real directory');
}

/** Returns a draft only; comments and procedural SQL remain attached to their exact bytes. */
export function inventoryPackage(root, packageId) {
  requireCondition(validId(packageId), 'invalid package ID');
  root = resolve(root);
  assertDirectory(root);
  const names = readdirSync(root).sort();
  const migrations = [];
  const versions = new Set();
  for (const file of names) {
    const path = resolve(root, file);
    const stat = lstatSync(path);
    requireCondition(!stat.isSymbolicLink(), `symlink in migration package: ${file}`);
    requireCondition(!stat.isDirectory(), `nested migration package requires explicit review: ${file}`);
    if (!file.endsWith('.sql')) continue;
    requireCondition(stat.isFile(), `migration must be a regular file: ${file}`);
    const versioned = file.match(/^V([0-9]+(?:[._][0-9]+)*)__[^/]+\.sql$/);
    const repeatable = /^R__[^/]+\.sql$/.test(file);
    requireCondition(versioned || repeatable, `invalid migration filename: ${file}`);
    if (versioned) {
      const version = versioned[1].split(/[._]/).map(part => BigInt(part).toString()).join('.');
      requireCondition(!versions.has(version), `duplicate migration version: ${version}`);
      versions.add(version);
    }
    const bytes = readFileSync(path);
    const statements = splitSqlStatements(bytes.toString('utf8'))
      .filter(statement => stripSqlComments(statement).trim())
      .map((sql, index) => ({ index: index + 1, sha256: digest(sql), review: 'draft',
        objects: [], rationale: '', sql }));
    requireCondition(statements.length > 0, `migration has no executable statements: ${file}`);
    migrations.push({ file, sha256: digest(bytes), statements });
  }
  requireCondition(migrations.length > 0, 'migration package is empty');
  return { schemaVersion: 1, packageId, migrations };
}

/** A declared object is an explicit review assertion, not a claim of SQL parser completeness. */
export function auditPackage(root, contract) {
  requireCondition(contract?.schemaVersion === 1 && validId(contract.packageId), 'unsupported ownership contract');
  requireCondition(Array.isArray(contract.dependencies)
    && contract.dependencies.every(id => validId(id) && id !== contract.packageId)
    && new Set(contract.dependencies).size === contract.dependencies.length, 'invalid package dependencies');
  requireCondition(contract.objects && typeof contract.objects === 'object'
    && !Array.isArray(contract.objects), 'object catalog required');
  const owners = new Set([contract.packageId, ...contract.dependencies]);
  for (const [name, object] of Object.entries(contract.objects)) {
    requireCondition(OBJECT.test(name), `invalid object name: ${name}`);
    requireCondition(owners.has(object?.owner) && LIFECYCLES.has(object.lifecycle), `invalid object owner/lifecycle: ${name}`);
    requireCondition(typeof object.evidence === 'string' && object.evidence.trim(), `object evidence required: ${name}`);
  }
  const inventory = inventoryPackage(root, contract.packageId);
  requireCondition(Array.isArray(contract.migrations), 'migration review list required');
  requireCondition(contract.migrations.length === inventory.migrations.length, 'migration file denominator differs');
  const byFile = new Map(contract.migrations.map(migration => [migration.file, migration]));
  requireCondition(byFile.size === contract.migrations.length, 'duplicate migration reviews');
  const used = new Set();
  let statementCount = 0;
  for (const migration of inventory.migrations) {
    const review = byFile.get(migration.file);
    requireCondition(review?.sha256 === migration.sha256, `migration bytes drift: ${migration.file}`);
    requireCondition(Array.isArray(review.statements) && review.statements.length === migration.statements.length,
      `statement denominator differs: ${migration.file}`);
    const statements = new Map(review.statements.map(statement => [statement.index, statement]));
    requireCondition(statements.size === review.statements.length, `duplicate statement reviews: ${migration.file}`);
    for (const current of migration.statements) {
      const statement = statements.get(current.index);
      const label = `${migration.file}#${current.index}`;
      requireCondition(statement?.sha256 === current.sha256, `statement bytes drift: ${label}`);
      requireCondition(statement.review === 'approved' && typeof statement.rationale === 'string'
        && statement.rationale.trim(), `unreviewed statement: ${label}`);
      requireCondition(Array.isArray(statement.objects) && statement.objects.length > 0, `statement objects missing: ${label}`);
      const seen = new Set();
      for (const action of statement.objects) {
        requireCondition(action && typeof action === 'object', `invalid object action: ${label}`);
        const object = contract.objects[action.name];
        requireCondition(object && OPERATIONS.has(action.operation), `undeclared object/action: ${label}`);
        const key = `${action.name}:${action.operation}`;
        requireCondition(!seen.has(key), `duplicate object action: ${label}`);
        seen.add(key); used.add(action.name);
        if (object.owner !== contract.packageId) {
          requireCondition(['reference', 'seed', 'backfill'].includes(action.operation),
            `foreign schema write: ${label}:${action.name}`);
        }
        if (object.lifecycle === 'model-publish' && !['reference', 'index'].includes(action.operation)) {
          requireCondition(typeof action.transition === 'string' && action.transition.trim(),
            `dynamic schema/data transition needs explicit review: ${label}:${action.name}`);
        }
      }
      statementCount += 1;
    }
  }
  requireCondition(used.size === Object.keys(contract.objects).length, 'unused catalog objects obscure review scope');
  return { schemaVersion: 1, verdict: 'PASS', claim: 'reviewed-statement-byte-coverage',
    packageId: contract.packageId, migrationCount: inventory.migrations.length, statementCount,
    objectCount: used.size, contractSha256: digest(JSON.stringify(contract)),
    migrations: inventory.migrations.map(({ file, sha256 }) => ({ file, sha256 })) };
}

function main(argv) {
  const [action, ...rest] = argv;
  requireCondition(['inventory', 'audit'].includes(action), 'action must be inventory or audit');
  const options = {};
  for (let index = 0; index < rest.length; index += 2) {
    requireCondition(['--root', '--package', '--contract', '--out'].includes(rest[index]) && rest[index + 1], 'invalid arguments');
    requireCondition(!Object.hasOwn(options, rest[index]), 'duplicate argument');
    options[rest[index]] = rest[index + 1];
  }
  requireCondition(options['--root'], '--root required');
  let result;
  if (action === 'inventory') result = inventoryPackage(options['--root'], options['--package']);
  else {
    requireCondition(options['--contract'], '--contract required');
    result = auditPackage(options['--root'], JSON.parse(readFileSync(options['--contract'], 'utf8')));
  }
  const json = JSON.stringify(result, null, 2) + '\n';
  if (options['--out']) writeFileSync(options['--out'], json, { flag: 'wx', mode: 0o600 });
  else process.stdout.write(json);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(process.argv.slice(2)); }
  catch (error) { console.error(`schema-ownership: ${error.message}`); process.exitCode = 2; }
}
