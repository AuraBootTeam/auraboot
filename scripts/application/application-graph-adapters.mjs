import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, realpathSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';

import {
  buildApplicationGraph,
  canonicalJson,
  sha256,
  sha256Path,
  validateLock,
  validateManifest,
  validateWebContribution,
  verifyArtifacts,
} from './application-contract.mjs';

const CONTRIBUTION_FILE = 'auraboot.contribution.json';

function inside(root, candidate, label) {
  const normalizedRoot = resolve(root);
  const normalized = resolve(candidate);
  if (relative(normalizedRoot, normalized).startsWith('..')) throw new Error(`${label} escapes its root`);
  return normalized;
}

function parseJson(bytes, label) {
  try {
    return JSON.parse(bytes);
  } catch (error) {
    throw new Error(`${label} is not valid JSON: ${error.message}`);
  }
}

function assertNoSymbolicLinks(path, label) {
  const stat = lstatSync(path);
  if (stat.isSymbolicLink()) throw new Error(`${label} must not contain symbolic links: ${path}`);
  if (!stat.isDirectory()) return;
  for (const name of readdirSync(path)) assertNoSymbolicLinks(resolve(path, name), label);
}

function requirementArtifacts(manifest, artifacts) {
  return manifest.frontend.contributions.map((requirement) => {
    const matches = artifacts.filter(
      (artifact) => artifact.type === 'npm'
        && artifact.id === requirement.package
        && artifact.version === requirement.version,
    );
    if (matches.length !== 1) {
      throw new Error(`artifact web contribution ${requirement.package}@${requirement.version} resolved to ${matches.length} entries`);
    }
    return matches[0];
  });
}

function readTarballContribution(tarball, label) {
  let bytes;
  try {
    bytes = execFileSync('tar', ['-xOf', tarball, `package/${CONTRIBUTION_FILE}`], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch (error) {
    throw new Error(`${label} does not contain package/${CONTRIBUTION_FILE}: ${error.stderr?.trim() || error.message}`);
  }
  const contribution = validateWebContribution(parseJson(bytes, `${label} contribution manifest`));
  const entries = new Set(execFileSync('tar', ['-tzf', tarball], { encoding: 'utf8' })
    .split('\n').filter(Boolean).map((entry) => entry.replace(/\/$/, '')));
  const requireEntry = (relativePath, kind, allowChildren = false) => {
    const entry = `package/${relativePath.replace(/^\.\//, '').replace(/\/$/, '')}`;
    if (!entries.has(entry) && !(allowChildren && [...entries].some((candidate) => candidate.startsWith(`${entry}/`)))) {
      throw new Error(`${label} is missing declared ${kind}: ${relativePath}`);
    }
  };
  for (const route of contribution.routes) requireEntry(route.export, `route ${route.id}`);
  for (const registration of contribution.contributions) {
    requireEntry(registration.export, `${registration.kind} ${registration.id}`);
  }
  for (const asset of contribution.assets) requireEntry(asset.source, `asset ${asset.id}`, true);
  return contribution;
}

export function loadSourceWebContributions(manifestInput, sourceMapPath) {
  const manifest = validateManifest(manifestInput);
  if (manifest.frontend.contributions.length === 0) return [];
  if (!sourceMapPath) throw new Error('--source-map is required when source mode has web contributions');
  const sourceMap = parseJson(readFileSync(sourceMapPath, 'utf8'), 'source map');
  if (!sourceMap || typeof sourceMap.packages !== 'object' || Array.isArray(sourceMap.packages)) {
    throw new Error('source map must contain a packages object');
  }
  const mapRoot = dirname(sourceMapPath);
  return manifest.frontend.contributions.map((requirement) => {
    const packageRoot = sourceMap.packages[requirement.package];
    if (typeof packageRoot !== 'string') throw new Error(`source map has no root for ${requirement.package}`);
    const resolvedPackageRoot = realpathSync(resolve(mapRoot, packageRoot));
    const contributionPath = inside(resolvedPackageRoot, resolve(resolvedPackageRoot, CONTRIBUTION_FILE), `source contribution ${requirement.package}`);
    const contribution = validateWebContribution(parseJson(readFileSync(contributionPath, 'utf8'), contributionPath));
    const requirePath = (relativePath, kind) => {
      const path = inside(resolvedPackageRoot, resolve(resolvedPackageRoot, relativePath), `${kind} ${requirement.package}`);
      if (!existsSync(path)) throw new Error(`${requirement.package} is missing declared ${kind}: ${relativePath}`);
    };
    for (const route of contribution.routes) requirePath(route.export, `route ${route.id}`);
    for (const registration of contribution.contributions) requirePath(registration.export, `${registration.kind} ${registration.id}`);
    for (const asset of contribution.assets) requirePath(asset.source, `asset ${asset.id}`);
    return contribution;
  });
}

export function loadCatalogWebContributions(manifestInput, catalog, artifactRoot) {
  const manifest = validateManifest(manifestInput);
  if (manifest.frontend.contributions.length === 0) return [];
  if (!artifactRoot) throw new Error('--artifact-root is required when resolving web contribution artifacts');
  return requirementArtifacts(manifest, catalog.artifacts ?? []).map((artifact) => {
    if (!artifact.localPath) throw new Error(`artifact ${artifact.id} has no staged localPath`);
    const tarball = inside(artifactRoot, resolve(artifactRoot, artifact.localPath), `artifact ${artifact.id}`);
    const actualDigest = sha256Path(tarball);
    if (actualDigest !== artifact.digest) {
      throw new Error(`artifact ${artifact.id} checksum mismatch before contribution resolution`);
    }
    return readTarballContribution(tarball, `artifact ${artifact.id}`);
  });
}

export function loadArtifactWebContributions(manifestInput, lockInput, artifactRoot) {
  const manifest = validateManifest(manifestInput);
  const lock = validateLock(lockInput);
  if (lock.manifestDigest !== sha256(canonicalJson(manifest))) {
    throw new Error('application lock does not belong to the supplied manifest');
  }
  verifyArtifacts(lock, { artifactRoot });
  return requirementArtifacts(manifest, lock.artifacts).map((artifact) => {
    const tarball = inside(artifactRoot, resolve(artifactRoot, artifact.localPath), `artifact ${artifact.id}`);
    return readTarballContribution(tarball, `artifact ${artifact.id}`);
  });
}

export function buildSourceApplicationGraph(manifest, sourceMapPath) {
  const contributions = loadSourceWebContributions(manifest, sourceMapPath);
  return buildApplicationGraph(manifest, contributions, { requireContributions: true });
}

export function buildArtifactApplicationGraph(manifest, lock, artifactRoot) {
  const contributions = loadArtifactWebContributions(manifest, lock, artifactRoot);
  return buildApplicationGraph(manifest, contributions, { requireContributions: true });
}

export function materializeSourceWebAssets(manifestInput, sourceMapPath, targetRoot) {
  const manifest = validateManifest(manifestInput);
  const contributions = loadSourceWebContributions(manifest, sourceMapPath);
  const sourceMap = parseJson(readFileSync(sourceMapPath, 'utf8'), 'source map');
  const mapRoot = dirname(sourceMapPath);
  const target = resolve(targetRoot);
  const materialized = [];
  for (const contribution of contributions) {
    const packageRoot = inside(mapRoot, resolve(mapRoot, sourceMap.packages[contribution.package.name]), `source package ${contribution.package.name}`);
    for (const asset of contribution.assets) {
      const source = inside(packageRoot, resolve(packageRoot, asset.source), `asset ${asset.id}`);
      if (!existsSync(source)) throw new Error(`asset ${contribution.package.name}:${asset.id} source does not exist: ${asset.source}`);
      const stat = lstatSync(source);
      assertNoSymbolicLinks(source, `asset ${contribution.package.name}:${asset.id} source`);
      const destination = inside(target, resolve(target, `.${asset.mount}`), `asset ${asset.id} mount`);
      if (existsSync(destination)) throw new Error(`asset mount already exists: ${asset.mount}`);
      mkdirSync(dirname(destination), { recursive: true });
      cpSync(source, destination, { recursive: stat.isDirectory(), errorOnExist: true, force: false });
      materialized.push({ owner: contribution.package.name, id: asset.id, source: asset.source, mount: asset.mount });
    }
  }
  return materialized;
}

export function buildSourceRouteManifest(manifestInput, sourceMapPath, routeRootPath) {
  const manifest = validateManifest(manifestInput);
  const contributions = loadSourceWebContributions(manifest, sourceMapPath);
  const sourceMap = parseJson(readFileSync(sourceMapPath, 'utf8'), 'source map');
  const mapRoot = dirname(sourceMapPath);
  const routes = contributions.flatMap((contribution) => {
    const packageRoot = resolve(mapRoot, sourceMap.packages[contribution.package.name]);
    return contribution.routes.map((route) => ({
      path: route.path,
      file: inside(packageRoot, resolve(packageRoot, route.export), `route ${route.id}`),
      kind: route.kind,
      shell: route.shell,
    }));
  });
  const routeRoot = routeRootPath ? resolve(routeRootPath) : null;
  const routeFile = (file) => {
    if (!routeRoot) return file;
    inside(routeRoot, file, 'route module');
    const path = relative(routeRoot, file).replaceAll('\\', '/');
    return path.startsWith('.') ? path : `./${path}`;
  };
  const entries = (predicate) => routes
    .filter(predicate)
    .map(({ path, file }) => ({ path, file: routeFile(file) }));
  return {
    APPLICATION_ROUTES: entries((route) => route.kind === 'page' && route.shell === 'application'),
    STANDALONE_ROUTES: entries((route) => route.kind === 'page' && route.shell === 'standalone'),
    RESOURCE_ROUTES: entries((route) => route.kind === 'resource'),
    PLATFORM_ROUTES: [],
  };
}
