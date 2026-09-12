import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';

import Ajv from 'ajv';
import YAML from 'yaml';

const SCRIPT_ROOT = dirname(new URL(import.meta.url).pathname);
const REPO_ROOT = resolve(SCRIPT_ROOT, '../..');
const MANIFEST_SCHEMA_PATH = resolve(REPO_ROOT, 'distribution/application/application-manifest.schema.json');
const LOCK_SCHEMA_PATH = resolve(REPO_ROOT, 'distribution/application/application-lock.schema.json');

const ajv = new Ajv({ allErrors: true, strict: true });
const validateManifestSchema = ajv.compile(JSON.parse(readFileSync(MANIFEST_SCHEMA_PATH, 'utf8')));
const validateLockSchema = ajv.compile(JSON.parse(readFileSync(LOCK_SCHEMA_PATH, 'utf8')));

export function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
      .join(',')}}`;
  }
  return JSON.stringify(value);
}

export function sha256(value) {
  return `sha256:${createHash('sha256').update(value).digest('hex')}`;
}

export function sha256Path(path) {
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
      else throw new Error(`unsupported staged artifact type: ${child}`);
    }
  };
  walk(path);
  return sha256(`${entries.join('\n')}\n`);
}

function withoutIdentity(lock) {
  const { identity: _identity, ...content } = lock;
  return content;
}

export function lockIdentity(lock) {
  return sha256(canonicalJson(withoutIdentity(lock)));
}

export function readStructuredFile(path) {
  return YAML.parse(readFileSync(path, 'utf8'));
}

function assertSchema(validate, value, label) {
  if (validate(value)) return;
  const details = validate.errors.map((error) => `${error.instancePath || '/'} ${error.message}`).join('; ');
  throw new Error(`${label} is invalid: ${details}`);
}

function assertUnique(values, label) {
  const duplicates = values.filter((value, index) => values.indexOf(value) !== index);
  if (duplicates.length > 0) throw new Error(`${label} contains duplicate owner IDs: ${[...new Set(duplicates)].join(', ')}`);
}

export function validateManifest(manifest) {
  assertSchema(validateManifestSchema, manifest, 'application manifest');
  assertUnique(manifest.backend.plugins.map((plugin) => plugin.id), 'backend.plugins');
  assertUnique(manifest.backend.migrationSets, 'backend.migrationSets');
  assertUnique(manifest.frontend.contributions.map((item) => item.package), 'frontend.contributions');
  assertUnique(manifest.config.importOrder, 'config.importOrder');
  return manifest;
}

export function validateLock(lock) {
  assertSchema(validateLockSchema, lock, 'application lock');
  assertUnique(lock.artifacts.map((artifact) => `${artifact.type}:${artifact.id}`), 'artifacts');
  const expectedIdentity = lockIdentity(lock);
  if (lock.identity !== expectedIdentity) {
    throw new Error(`application lock identity mismatch: expected ${expectedIdentity}, received ${lock.identity}`);
  }
  return lock;
}

export function buildApplicationGraph(manifestInput) {
  const manifest = validateManifest(manifestInput);
  const nodes = [
    { kind: 'runtime', id: 'com.auraboot:runtime', version: manifest.platform.runtime },
    { kind: 'api', id: 'com.auraboot:platform-plugin-api', version: manifest.platform.pluginApi },
    { kind: 'web', id: '@auraboot/web-shell', version: manifest.platform.webShell },
    { kind: 'web', id: '@auraboot/plugin-sdk', version: manifest.platform.pluginSdk },
    ...manifest.backend.plugins.map((plugin) => ({ kind: 'plugin', id: plugin.id, version: plugin.version })),
    ...manifest.backend.migrationSets.map((id) => ({ kind: 'migration', id })),
    ...manifest.frontend.contributions.map((item) => ({
      kind: 'web',
      id: item.package,
      version: item.version,
    })),
    ...manifest.config.importOrder.map((id) => ({ kind: 'config', id })),
  ].map((node, order) => ({ ...node, order }));
  const duplicateNodes = nodes
    .map((node) => `${node.kind}:${node.id}`)
    .filter((id, index, all) => all.indexOf(id) !== index);
  if (duplicateNodes.length > 0) {
    throw new Error(`application graph contains duplicate nodes: ${[...new Set(duplicateNodes)].join(', ')}`);
  }
  return {
    graphDigest: sha256(canonicalJson(nodes)),
    nodes,
  };
}

export function verifyArtifacts(lockInput, { artifactRoot }) {
  const lock = validateLock(lockInput);
  const root = resolve(artifactRoot);
  for (const artifact of lock.artifacts) {
    if (!artifact.localPath) {
      throw new Error(`artifact ${artifact.type}:${artifact.id} has no staged localPath`);
    }
    const path = resolve(root, artifact.localPath);
    if (relative(root, path).startsWith('..')) {
      throw new Error(`artifact ${artifact.type}:${artifact.id} escapes the artifact root`);
    }
    let actualDigest;
    try {
      actualDigest = sha256Path(path);
    } catch (error) {
      throw new Error(`artifact ${artifact.type}:${artifact.id} cannot be read at ${artifact.localPath}: ${error.message}`);
    }
    if (actualDigest !== artifact.digest) {
      throw new Error(
        `artifact ${artifact.type}:${artifact.id} checksum mismatch: expected ${artifact.digest}, received ${actualDigest}`,
      );
    }
  }
  return lock;
}

function requirements(manifest) {
  return [
    { type: 'runtime', id: 'com.auraboot:runtime', version: manifest.platform.runtime },
    { type: 'maven', id: 'com.auraboot:platform-plugin-api', version: manifest.platform.pluginApi },
    { type: 'npm', id: '@auraboot/web-shell', version: manifest.platform.webShell },
    { type: 'npm', id: '@auraboot/plugin-sdk', version: manifest.platform.pluginSdk },
    ...manifest.backend.plugins.map((plugin) => ({ type: 'plugin', id: plugin.id, version: plugin.version })),
    ...manifest.backend.migrationSets.map((id) => ({ type: 'migration', id })),
    ...manifest.frontend.contributions.map((item) => ({
      type: 'npm',
      id: item.package,
      version: item.version,
    })),
    ...manifest.config.importOrder.map((id) => ({ type: 'config', id })),
  ];
}

function resolveRequirement(requirement, catalog) {
  const matches = catalog.artifacts.filter(
    (artifact) => artifact.type === requirement.type
      && artifact.id === requirement.id
      && (requirement.version === undefined || artifact.version === requirement.version),
  );
  if (matches.length !== 1) {
    throw new Error(
      `artifact requirement ${requirement.type}:${requirement.id}${requirement.version ? `@${requirement.version}` : ''} resolved to ${matches.length} entries`,
    );
  }
  return matches[0];
}

export function resolveApplication(manifestInput, catalog) {
  const manifest = validateManifest(manifestInput);
  if (!catalog || !Array.isArray(catalog.artifacts)) throw new Error('artifact catalog must contain artifacts[]');
  const artifacts = requirements(manifest)
    .map((requirement) => resolveRequirement(requirement, catalog))
    .sort((left, right) => `${left.type}:${left.id}`.localeCompare(`${right.type}:${right.id}`));

  for (const artifact of artifacts) {
    if (!/^sha256:[0-9a-f]{64}$/.test(artifact.digest)) {
      throw new Error(`artifact ${artifact.type}:${artifact.id} has an invalid digest`);
    }
    if (!/^[0-9a-f]{40}$/.test(artifact.source?.commit ?? '')) {
      throw new Error(`artifact ${artifact.type}:${artifact.id} must record a 40-character source commit`);
    }
    if (/snapshot|latest|workspace|branch/i.test(artifact.version)) {
      throw new Error(`artifact ${artifact.type}:${artifact.id} uses a mutable release version: ${artifact.version}`);
    }
  }

  const lockWithoutIdentity = {
    schemaVersion: 1,
    application: { id: manifest.app.id, version: manifest.app.version },
    manifestDigest: sha256(canonicalJson(manifest)),
    composition: buildApplicationGraph(manifest),
    artifacts,
  };
  const lock = {
    ...lockWithoutIdentity,
    identity: lockIdentity(lockWithoutIdentity),
  };
  return validateLock(lock);
}
