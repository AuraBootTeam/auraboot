#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { basename, relative, resolve } from 'node:path';

function parseArgs(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key.startsWith('--')) throw new Error(`unexpected argument: ${key}`);
    options[key.slice(2)] = argv[++index];
  }
  for (const key of ['root', 'output', 'required', 'product', 'core-commit', 'product-commit', 'image-digest']) {
    if (!options[key]) throw new Error(`--${key} is required`);
  }
  return options;
}

function walkPngs(root, directory = root) {
  const files = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = resolve(directory, entry.name);
    if (entry.isSymbolicLink()) throw new Error(`screenshot evidence must not contain symlinks: ${path}`);
    if (entry.isDirectory()) files.push(...walkPngs(root, path));
    else if (entry.isFile() && entry.name.endsWith('.png')) files.push(path);
  }
  return files;
}

function inspectPng(root, path) {
  const bytes = readFileSync(path);
  const signature = '89504e470d0a1a0a';
  if (bytes.length < 24 || bytes.subarray(0, 8).toString('hex') !== signature || bytes.subarray(12, 16).toString() !== 'IHDR') {
    throw new Error(`invalid PNG screenshot: ${path}`);
  }
  const width = bytes.readUInt32BE(16);
  const height = bytes.readUInt32BE(20);
  if (width === 0 || height === 0) throw new Error(`zero-sized PNG screenshot: ${path}`);
  return {
    id: basename(path),
    path: relative(root, path).replaceAll('\\', '/'),
    sha256: `sha256:${createHash('sha256').update(bytes).digest('hex')}`,
    bytes: bytes.length,
    width,
    height,
  };
}

try {
  const options = parseArgs(process.argv.slice(2));
  const root = resolve(options.root);
  const output = resolve(options.output);
  if (!lstatSync(root).isDirectory()) throw new Error(`screenshot root is not a directory: ${root}`);
  const required = options.required.split(',').map((value) => value.trim()).filter(Boolean);
  if (new Set(required).size !== required.length) throw new Error('required screenshot IDs must be unique');
  const screenshots = walkPngs(root).sort().map((path) => inspectPng(root, path));
  const byId = new Map();
  for (const screenshot of screenshots) byId.set(screenshot.id, [...(byId.get(screenshot.id) ?? []), screenshot]);
  for (const id of required) {
    const matches = byId.get(id) ?? [];
    if (matches.length !== 1) throw new Error(`required screenshot ${id} must exist exactly once; found ${matches.length}`);
  }
  const manifest = {
    schemaVersion: 1,
    product: options.product,
    coreCommit: options['core-commit'],
    productCommit: options['product-commit'],
    imageDigest: options['image-digest'],
    required,
    screenshots,
    allRequiredPresent: true,
    visualReview: 'PENDING',
  };
  writeFileSync(output, `${JSON.stringify(manifest, null, 2)}\n`);
  process.stdout.write(`verified ${required.length} required release screenshots (${screenshots.length} PNG files)\n`);
} catch (error) {
  process.stderr.write(`release screenshot manifest failed: ${error.message}\n`);
  process.exitCode = 1;
}
