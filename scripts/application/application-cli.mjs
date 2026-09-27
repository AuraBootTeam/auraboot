#!/usr/bin/env node

import { mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

import {
  buildApplicationGraph,
  readStructuredFile,
  resolveApplication,
  verifyArtifacts,
  validateLock,
  validateLockForManifest,
  validateManifest,
} from './application-contract.mjs';
import {
  buildArtifactApplicationGraph,
  buildSourceApplicationGraph,
  buildSourceRouteManifest,
  loadCatalogWebContributions,
  materializeSourceWebAssets,
} from './application-graph-adapters.mjs';
import { publishApplicationRelease } from './application-release-control.mjs';

function parseArgs(argv) {
  const [command, ...rest] = argv;
  const options = { command };
  for (let index = 0; index < rest.length; index += 1) {
    const argument = rest[index];
    if (argument === '--manifest') options.manifest = resolve(rest[++index]);
    else if (argument === '--catalog') options.catalog = resolve(rest[++index]);
    else if (argument === '--expected-identity') {
      options.expectedIdentity = rest[++index];
      if (!/^sha256:[0-9a-f]{64}$/.test(options.expectedIdentity ?? '')) throw new Error('--expected-identity requires a SHA256 identity');
    }
    else if (argument === '--lock') options.lock = resolve(rest[++index]);
    else if (argument === '--output') options.output = resolve(rest[++index]);
    else if (argument === '--artifact-root') options.artifactRoot = resolve(rest[++index]);
    else if (argument === '--skip-image') options.skipImage = true;
    else if (argument === '--source-map') options.sourceMap = resolve(rest[++index]);
    else if (argument === '--route-root') options.routeRoot = resolve(rest[++index]);
    else if (argument === '--source-graph') options.sourceGraph = resolve(rest[++index]);
    else if (argument === '--artifact-graph') options.artifactGraph = resolve(rest[++index]);
    else if (argument === '--target') options.target = rest[++index];
    else if (argument === '--mode') options.mode = rest[++index];
    else if (argument === '--base-url') options.baseUrl = rest[++index];
    else if (argument === '--application-code') options.applicationCode = rest[++index];
    else if (argument === '--application-name') options.applicationName = rest[++index];
    else if (argument === '--registration') options.registration = resolve(rest[++index]);
    else if (argument === '--receipt') options.receipt = resolve(rest[++index]);
    else if (argument === '--token-file') options.tokenFile = resolve(rest[++index]);
    else if (argument === '--expected-stable-version') {
      options.expectedStableVersion = Number(rest[++index]);
      if (!Number.isSafeInteger(options.expectedStableVersion) || options.expectedStableVersion <= 0) {
        throw new Error('--expected-stable-version requires a positive integer');
      }
    }
    else if (argument === '--timeout-ms') {
      options.timeoutMs = Number(rest[++index]);
      if (!Number.isSafeInteger(options.timeoutMs) || options.timeoutMs <= 0) {
        throw new Error('--timeout-ms requires a positive integer');
      }
    }
    else throw new Error(`Unknown argument: ${argument}`);
  }
  return options;
}

function required(options, key) {
  if (!options[key]) throw new Error(`--${key} is required for ${options.command}`);
  return options[key];
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.command === 'validate') {
    const manifest = validateManifest(readStructuredFile(required(options, 'manifest')));
    process.stdout.write(`valid application manifest: ${manifest.app.id}@${manifest.app.version}\n`);
    return;
  }
  if (options.command === 'resolve') {
    const manifest = readStructuredFile(required(options, 'manifest'));
    const catalog = readStructuredFile(required(options, 'catalog'));
    const output = required(options, 'output');
    const webContributions = loadCatalogWebContributions(manifest, catalog, options.artifactRoot);
    const lock = resolveApplication(manifest, catalog, {
      webContributions,
      skipImage: options.skipImage === true,
    });
    mkdirSync(dirname(output), { recursive: true });
    writeFileSync(output, `${JSON.stringify(lock, null, 2)}\n`);
    process.stdout.write(`resolved application lock: ${lock.identity}\n`);
    return;
  }
  if (options.command === 'verify-lock') {
    const input = readStructuredFile(required(options, 'lock'));
    const validation = { expectedIdentity: options.expectedIdentity, skipImage: options.skipImage === true };
    const lock = options.manifest
      ? validateLockForManifest(input, readStructuredFile(options.manifest), validation)
      : validateLock(input, validation);
    process.stdout.write(`valid application lock: ${lock.identity}\n`);
    return;
  }
  if (options.command === 'verify-artifacts') {
    const lock = readStructuredFile(required(options, 'lock'));
    verifyArtifacts(lock, { artifactRoot: required(options, 'artifactRoot') });
    process.stdout.write(`verified ${lock.artifacts.length} staged artifacts: ${lock.identity}\n`);
    return;
  }
  if (options.command === 'graph') {
    const mode = options.mode ?? 'artifact';
    if (!['source', 'artifact'].includes(mode)) throw new Error('--mode must be source or artifact');
    const target = options.target ?? 'client';
    if (!['client', 'ssr'].includes(target)) throw new Error('--target must be client or ssr');
    const manifest = readStructuredFile(required(options, 'manifest'));
    const graph = mode === 'source'
      ? buildSourceApplicationGraph(manifest, options.sourceMap)
      : buildArtifactApplicationGraph(
        manifest,
        readStructuredFile(required(options, 'lock')),
        required(options, 'artifactRoot'),
      );
    const output = options.output;
    const envelope = { schemaVersion: 1, mode, target, ...graph };
    if (output) {
      mkdirSync(dirname(output), { recursive: true });
      writeFileSync(output, `${JSON.stringify(envelope, null, 2)}\n`);
    }
    process.stdout.write(`${JSON.stringify(envelope, null, 2)}\n`);
    return;
  }
  if (options.command === 'compare-graphs') {
    const source = readStructuredFile(required(options, 'sourceGraph'));
    const artifact = readStructuredFile(required(options, 'artifactGraph'));
    if (source.graphDigest !== artifact.graphDigest || JSON.stringify(source.nodes) !== JSON.stringify(artifact.nodes)) {
      throw new Error(`source/artifact graph mismatch: ${source.graphDigest} != ${artifact.graphDigest}`);
    }
    process.stdout.write(`equivalent application graphs: ${source.graphDigest}\n`);
    return;
  }
  if (options.command === 'materialize-assets') {
    const manifest = readStructuredFile(required(options, 'manifest'));
    const materialized = materializeSourceWebAssets(
      manifest,
      required(options, 'sourceMap'),
      required(options, 'target'),
    );
    process.stdout.write(`materialized ${materialized.length} web asset mounts\n`);
    return;
  }
  if (options.command === 'emit-route-manifest') {
    const routeManifest = buildSourceRouteManifest(
      readStructuredFile(required(options, 'manifest')),
      required(options, 'sourceMap'),
      options.routeRoot,
    );
    const output = required(options, 'output');
    mkdirSync(dirname(output), { recursive: true });
    const moduleSource = Object.entries(routeManifest)
      .map(([name, entries]) => `export const ${name} = ${JSON.stringify(entries, null, 2)};`)
      .join('\n\n');
    writeFileSync(output, `${moduleSource}\n`);
    process.stdout.write(`emitted ${Object.values(routeManifest).flat().length} web routes\n`);
    return;
  }
  if (options.command === 'publish-release') {
    let token = process.env.AURA_RELEASE_TOKEN?.trim();
    if (options.tokenFile) {
      const mode = statSync(options.tokenFile).mode & 0o777;
      if ((mode & 0o077) !== 0) throw new Error('--token-file must not grant group or other permissions');
      token = readFileSync(options.tokenFile, 'utf8').trim();
    }
    const receipt = await publishApplicationRelease({
      applicationCode: required(options, 'applicationCode'),
      applicationName: options.applicationName,
      baseUrl: required(options, 'baseUrl'),
      expectedStableVersion: options.expectedStableVersion,
      receiptPath: required(options, 'receipt'),
      registrationPath: required(options, 'registration'),
      timeoutMs: options.timeoutMs,
      token,
    });
    process.stdout.write(`published ${receipt.applicationCode} release ${receipt.registration.releaseId} to stable version ${receipt.stable.version}\n`);
    process.stdout.write(`release receipt: ${options.receipt}\n`);
    return;
  }
  throw new Error('Usage: application-cli.mjs validate|resolve|verify-lock|verify-artifacts|graph|compare-graphs|materialize-assets|emit-route-manifest|publish-release [options]');
}

try {
  await main();
} catch (error) {
  process.stderr.write(`application contract failed: ${error.message}\n`);
  process.exitCode = 1;
}
