import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, readdirSync } from 'node:fs';
import { basename, dirname, relative, resolve } from 'node:path';

import Ajv from 'ajv';
import YAML from 'yaml';

const SCRIPT_ROOT = dirname(new URL(import.meta.url).pathname);
const REPO_ROOT = resolve(SCRIPT_ROOT, '../..');
const MANIFEST_SCHEMA_PATH = resolve(REPO_ROOT, 'distribution/application/application-manifest.schema.json');
const LOCK_SCHEMA_PATH = resolve(REPO_ROOT, 'distribution/application/application-lock.schema.json');
const WEB_CONTRIBUTION_SCHEMA_PATH = resolve(REPO_ROOT, 'distribution/application/web-contribution.schema.json');

const ajv = new Ajv({ allErrors: true, strict: true });
const validateManifestSchema = ajv.compile(JSON.parse(readFileSync(MANIFEST_SCHEMA_PATH, 'utf8')));
const validateLockSchema = ajv.compile(JSON.parse(readFileSync(LOCK_SCHEMA_PATH, 'utf8')));
const validateWebContributionSchema = ajv.compile(JSON.parse(readFileSync(WEB_CONTRIBUTION_SCHEMA_PATH, 'utf8')));

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
  assertUnique(manifest.platform.webPackages.map((item) => item.package), 'platform.webPackages');
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

export function validateWebContribution(manifest) {
  assertSchema(validateWebContributionSchema, manifest, 'web contribution manifest');
  const routeIds = manifest.routes.map((route) => route.id);
  const routePaths = manifest.routes.map((route) => route.path);
  const registrations = manifest.contributions.map((item) => `${item.kind}:${item.id}`);
  assertUnique(routeIds, 'web contribution routes');
  assertUnique(routePaths, 'web contribution route paths');
  assertUnique(registrations, 'web registry contributions');
  return manifest;
}

function orderedWebContributions(manifest, webContributionInputs, required) {
  const declared = manifest.frontend.contributions;
  if (!required && webContributionInputs.length === 0) return [];
  const contributions = webContributionInputs.map(validateWebContribution);
  const packages = contributions.map((item) => item.package.name);
  assertUnique(packages, 'web contribution packages');
  if (contributions.length !== declared.length) {
    throw new Error(`expected ${declared.length} web contribution manifests, received ${contributions.length}`);
  }
  return declared.map((requirement) => {
    const matches = contributions.filter((item) => item.package.name === requirement.package);
    if (matches.length !== 1) {
      throw new Error(`web contribution ${requirement.package}@${requirement.version} resolved to ${matches.length} manifests`);
    }
    const contribution = matches[0];
    if (contribution.package.version !== requirement.version) {
      throw new Error(
        `web contribution ${requirement.package} version mismatch: expected ${requirement.version}, received ${contribution.package.version}`,
      );
    }
    return contribution;
  });
}

function assertWebGraphIntegrity(contributions) {
  assertUnique(contributions.map((item) => item.plugin.code), 'web plugin codes');
  assertUnique(contributions.flatMap((item) => item.routes.map((route) => route.id)), 'application route IDs');
  assertUnique(contributions.flatMap((item) => item.routes.map((route) => route.path)), 'application route paths');
  assertUnique(
    contributions.flatMap((item) => item.contributions.map((entry) => `${entry.kind}:${entry.id}`)),
    'application registry owners',
  );
  for (const peer of ['react', 'reactDom', 'router', 'pluginSdk']) {
    const ranges = [...new Set(contributions.map((item) => item.peerDependencies[peer]))];
    if (ranges.length > 1) throw new Error(`web contribution peer ${peer} has incompatible ranges: ${ranges.join(', ')}`);
  }
  const byCode = new Map(contributions.map((item) => [item.plugin.code, item]));
  const visiting = new Set();
  const visited = new Set();
  const visit = (code) => {
    if (visited.has(code)) return;
    if (visiting.has(code)) throw new Error(`web contribution activation graph contains a cycle at ${code}`);
    visiting.add(code);
    for (const dependency of byCode.get(code)?.plugin.dependsOn ?? []) {
      if (byCode.has(dependency)) visit(dependency);
    }
    visiting.delete(code);
    visited.add(code);
  };
  for (const code of byCode.keys()) visit(code);
}

export function buildApplicationGraph(manifestInput, webContributionInputs = [], { requireContributions = false } = {}) {
  const manifest = validateManifest(manifestInput);
  const webContributions = orderedWebContributions(manifest, webContributionInputs, requireContributions);
  assertWebGraphIntegrity(webContributions);
  const nodes = [
    { kind: 'runtime', id: 'com.auraboot:runtime', version: manifest.platform.runtime },
    { kind: 'api', id: 'com.auraboot:platform-plugin-api', version: manifest.platform.pluginApi },
    { kind: 'web', id: '@auraboot/web-shell', version: manifest.platform.webShell },
    { kind: 'web', id: '@auraboot/plugin-sdk', version: manifest.platform.pluginSdk },
    ...manifest.platform.webPackages.map((item) => ({ kind: 'web', id: item.package, version: item.version })),
    ...manifest.backend.plugins.map((plugin) => ({ kind: 'plugin', id: plugin.id, version: plugin.version })),
    ...manifest.backend.migrationSets.map((id) => ({ kind: 'migration', id })),
    ...manifest.frontend.contributions.map((item) => ({
      kind: 'web',
      id: item.package,
      version: item.version,
    })),
    ...manifest.config.importOrder.map((id) => ({ kind: 'config', id })),
    ...webContributions.flatMap((contribution) => [
      ...contribution.routes.map((route) => ({
        kind: 'route',
        id: route.id,
        owner: contribution.package.name,
        path: route.path,
        export: route.export,
        rendering: route.rendering,
        ...(route.permission ? { permission: route.permission } : {}),
        ...(route.featureKey ? { featureKey: route.featureKey } : {}),
      })),
      ...contribution.contributions.map((entry) => ({
        kind: entry.kind,
        id: entry.id,
        owner: contribution.package.name,
        export: entry.export,
        ...(entry.permission ? { permission: entry.permission } : {}),
        ...(entry.featureKey ? { featureKey: entry.featureKey } : {}),
      })),
    ]),
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
  assertNoMigrationCollisions(lock.artifacts, { artifactRoot: root });
  return lock;
}

export function assertNoMigrationCollisions(artifacts, { artifactRoot }) {
  const versions = new Map();
  const visit = (directory, artifact) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = resolve(directory, entry.name);
      if (entry.isDirectory()) visit(path, artifact);
      else if (entry.isFile()) {
        const match = /^V([^_]+(?:_[^_]+)*)__.+\.sql$/i.exec(basename(path));
        if (!match) continue;
        const version = match[1].replaceAll('_', '.');
        const owner = `${artifact.id}:${relative(resolve(artifactRoot), path)}`;
        const existing = versions.get(version);
        if (existing) throw new Error(`migration version collision ${version}: ${existing} and ${owner}`);
        versions.set(version, owner);
      }
    }
  };
  for (const artifact of artifacts.filter((item) => item.type === 'migration' && item.localPath)) {
    const path = resolve(artifactRoot, artifact.localPath);
    const stat = lstatSync(path);
    if (stat.isDirectory()) visit(path, artifact);
  }
}

function requirements(manifest) {
  return [
    { type: 'runtime', id: 'com.auraboot:runtime', version: manifest.platform.runtime },
    { type: 'maven', id: 'com.auraboot:platform-plugin-api', version: manifest.platform.pluginApi },
    { type: 'npm', id: '@auraboot/web-shell', version: manifest.platform.webShell },
    { type: 'npm', id: '@auraboot/plugin-sdk', version: manifest.platform.pluginSdk },
    ...manifest.platform.webPackages.map((item) => ({ type: 'npm', id: item.package, version: item.version })),
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

export function resolveApplication(manifestInput, catalog, { webContributions = [] } = {}) {
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
    composition: buildApplicationGraph(manifest, webContributions, { requireContributions: true }),
    artifacts,
  };
  const lock = {
    ...lockWithoutIdentity,
    identity: lockIdentity(lockWithoutIdentity),
  };
  return validateLock(lock);
}
