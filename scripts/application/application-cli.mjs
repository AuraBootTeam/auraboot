#!/usr/bin/env node

import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

import {
  buildApplicationGraph,
  readStructuredFile,
  resolveApplication,
  verifyArtifacts,
  validateLock,
  validateManifest,
} from './application-contract.mjs';
import {
  buildArtifactApplicationGraph,
  buildSourceApplicationGraph,
  loadCatalogWebContributions,
} from './application-graph-adapters.mjs';

function parseArgs(argv) {
  const [command, ...rest] = argv;
  const options = { command };
  for (let index = 0; index < rest.length; index += 1) {
    const argument = rest[index];
    if (argument === '--manifest') options.manifest = resolve(rest[++index]);
    else if (argument === '--catalog') options.catalog = resolve(rest[++index]);
    else if (argument === '--lock') options.lock = resolve(rest[++index]);
    else if (argument === '--output') options.output = resolve(rest[++index]);
    else if (argument === '--artifact-root') options.artifactRoot = resolve(rest[++index]);
    else if (argument === '--source-map') options.sourceMap = resolve(rest[++index]);
    else if (argument === '--source-graph') options.sourceGraph = resolve(rest[++index]);
    else if (argument === '--artifact-graph') options.artifactGraph = resolve(rest[++index]);
    else if (argument === '--target') options.target = rest[++index];
    else if (argument === '--mode') options.mode = rest[++index];
    else throw new Error(`Unknown argument: ${argument}`);
  }
  return options;
}

function required(options, key) {
  if (!options[key]) throw new Error(`--${key} is required for ${options.command}`);
  return options[key];
}

function main() {
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
    const lock = resolveApplication(manifest, catalog, { webContributions });
    mkdirSync(dirname(output), { recursive: true });
    writeFileSync(output, `${JSON.stringify(lock, null, 2)}\n`);
    process.stdout.write(`resolved application lock: ${lock.identity}\n`);
    return;
  }
  if (options.command === 'verify-lock') {
    const lock = validateLock(readStructuredFile(required(options, 'lock')));
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
  throw new Error('Usage: application-cli.mjs validate|resolve|verify-lock|verify-artifacts|graph|compare-graphs [options]');
}

try {
  main();
} catch (error) {
  process.stderr.write(`application contract failed: ${error.message}\n`);
  process.exitCode = 1;
}
