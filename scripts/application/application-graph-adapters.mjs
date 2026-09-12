import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
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
  return validateWebContribution(parseJson(bytes, `${label} contribution manifest`));
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
    const contributionPath = resolve(mapRoot, packageRoot, CONTRIBUTION_FILE);
    return validateWebContribution(parseJson(readFileSync(contributionPath, 'utf8'), contributionPath));
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
