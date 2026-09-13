#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, readdirSync } from 'node:fs';
import { basename, relative, resolve } from 'node:path';

const sha256 = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`;

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function sha256Path(path) {
  const stat = lstatSync(path);
  if (stat.isSymbolicLink()) throw new Error(`symbolic links are not valid staged artifacts: ${path}`);
  if (stat.isFile()) return sha256(readFileSync(path));
  if (!stat.isDirectory()) throw new Error(`unsupported staged artifact type: ${path}`);
  const entries = [];
  const walk = (directory, prefix = '') => {
    for (const name of readdirSync(directory).sort()) {
      const child = resolve(directory, name);
      const childRelative = prefix ? `${prefix}/${name}` : name;
      const childStat = lstatSync(child);
      if (childStat.isSymbolicLink()) throw new Error(`symbolic links are not valid staged artifacts: ${child}`);
      if (childStat.isDirectory()) walk(child, childRelative);
      else if (childStat.isFile()) entries.push(`${childRelative}\0${sha256(readFileSync(child))}`);
      else throw new Error(`unsupported artifact type: ${child}`);
    }
  };
  walk(path);
  return sha256(`${entries.join('\n')}\n`);
}

function assertLockShape(lock) {
  if (!lock || typeof lock !== 'object' || Array.isArray(lock)) throw new Error('application lock must be an object');
  if (lock.schemaVersion !== 1) throw new Error('application lock schemaVersion must be 1');
  if (!lock.application || typeof lock.application.id !== 'string' || typeof lock.application.version !== 'string') {
    throw new Error('application lock must declare application.id and application.version');
  }
  if (!/^sha256:[0-9a-f]{64}$/.test(lock.identity ?? '')) throw new Error('application lock identity is invalid');
  if (!Array.isArray(lock.artifacts) || lock.artifacts.length === 0) throw new Error('application lock artifacts must be non-empty');
  const identities = new Set();
  for (const artifact of lock.artifacts) {
    if (!artifact || typeof artifact !== 'object' || Array.isArray(artifact)) throw new Error('lock artifact must be an object');
    for (const field of ['type', 'id', 'version', 'digest', 'localPath']) {
      if (typeof artifact[field] !== 'string' || artifact[field].length === 0) {
        throw new Error(`lock artifact ${artifact.id ?? '<unknown>'} has invalid ${field}`);
      }
    }
    if (!/^sha256:[0-9a-f]{64}$/.test(artifact.digest)) throw new Error(`artifact ${artifact.type}:${artifact.id} digest is invalid`);
    const identity = `${artifact.type}:${artifact.id}`;
    if (identities.has(identity)) throw new Error(`application lock contains duplicate artifact ${identity}`);
    identities.add(identity);
  }
  const { identity: _identity, ...content } = lock;
  const expected = sha256(canonicalJson(content));
  if (lock.identity !== expected) throw new Error(`application lock identity mismatch: expected ${expected}, received ${lock.identity}`);
}

function assertNoMigrationCollisions(artifacts, root) {
  const versions = new Map();
  const visit = (directory, artifact) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = resolve(directory, entry.name);
      if (entry.isDirectory()) visit(path, artifact);
      else if (entry.isFile()) {
        const match = /^V([^_]+(?:_[^_]+)*)__.+\.sql$/i.exec(basename(path));
        if (!match) continue;
        const version = match[1].replaceAll('_', '.');
        const owner = `${artifact.id}:${relative(root, path)}`;
        const existing = versions.get(version);
        if (existing) throw new Error(`migration version collision ${version}: ${existing} and ${owner}`);
        versions.set(version, owner);
      }
    }
  };
  for (const artifact of artifacts.filter((item) => item.type === 'migration')) {
    const path = resolve(root, artifact.localPath);
    if (lstatSync(path).isDirectory()) visit(path, artifact);
  }
}

function parseArgs(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index += 1) {
    if (argv[index] === '--lock') options.lock = resolve(argv[++index]);
    else if (argv[index] === '--artifact-root') options.artifactRoot = resolve(argv[++index]);
    else throw new Error(`unknown argument: ${argv[index]}`);
  }
  if (!options.lock || !options.artifactRoot) throw new Error('--lock and --artifact-root are required');
  return options;
}

try {
  const options = parseArgs(process.argv.slice(2));
  const lock = JSON.parse(readFileSync(options.lock, 'utf8'));
  assertLockShape(lock);
  for (const artifact of lock.artifacts) {
    const path = resolve(options.artifactRoot, artifact.localPath);
    if (relative(options.artifactRoot, path).startsWith('..')) throw new Error(`artifact ${artifact.type}:${artifact.id} escapes artifact root`);
    const actual = sha256Path(path);
    if (actual !== artifact.digest) {
      throw new Error(`artifact ${artifact.type}:${artifact.id} checksum mismatch: expected ${artifact.digest}, received ${actual}`);
    }
  }
  assertNoMigrationCollisions(lock.artifacts, options.artifactRoot);
  process.stdout.write(`verified ${lock.artifacts.length} staged artifacts: ${lock.identity}\n`);
} catch (error) {
  process.stderr.write(`application artifact verification failed: ${error.message}\n`);
  process.exitCode = 1;
}
