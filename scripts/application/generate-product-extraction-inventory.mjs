#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname, extname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SOURCE_EXTENSIONS = new Set([
  '.gradle',
  '.java',
  '.js',
  '.json',
  '.kts',
  '.mjs',
  '.sql',
  '.ts',
  '.tsx',
  '.xml',
  '.yaml',
  '.yml',
]);

const PRODUCT_RULES = {
  bpm: {
    targetOwner: 'aura-bpm',
    dedicatedPaths: [
      /^platform\/src\/(main|test)\/java\/com\/auraboot\/framework\/bpm\//,
      /^plugins\/core-bpm\//,
      /^web-admin\/app\/plugins\/core-bpm\//,
    ],
    pathSignals: [/\/(bpm|bpmn)(\/|[-_.])/i, /(^|\/)core-bpm(\/|$)/],
    contentSignals: [
      /com\.auraboot\.framework\.bpm/,
      /~\/plugins\/core-bpm/,
      /['"]core-bpm['"]/,
      /org\.(smartboot|drools|kie)\b/,
      /\bSmartEngine\b/,
      /\b(?:ab|mt)_bpm_[a-z0-9_]+\b/i,
    ],
  },
  crm: {
    targetOwner: 'aura-crm',
    dedicatedPaths: [
      /^platform\/src\/(main|test)\/java\/com\/auraboot\/framework\/crm\//,
      /^plugins\/crm\//,
      /^web-admin\/app\/routes\/crm\//,
    ],
    pathSignals: [/\/(crm)(\/|[-_.])/i, /(^|\/)crm(\/|$)/],
    contentSignals: [
      /com\.auraboot\.framework\.crm/,
      /~\/routes\/crm/,
      /['"`]\/crm(?:\/|['"`])/,
      /com\.auraboot\.crm/,
      /\b(?:ab|mt)_crm_[a-z0-9_]+\b/i,
    ],
  },
};

function normalizePath(path) {
  return path.replaceAll('\\', '/').replace(/^\.\//, '');
}

function isTestPath(path) {
  return /(^|\/)(test|tests|__tests__|e2e)(\/|$)/.test(path) || /\.(test|spec)\.[^.]+$/.test(path);
}

function areaFor(path) {
  if (/\/db\/migration\//.test(path) || /(^|\/)migrations?\//.test(path) || extname(path) === '.sql') {
    return 'migration';
  }
  if (path.startsWith('platform/')) return 'backend';
  if (path.startsWith('web-admin/')) return 'frontend';
  if (path.startsWith('plugins/')) return 'plugin';
  if (path.startsWith('scripts/')) return 'tooling';
  return 'repository';
}

function signalNames(path, content, rule) {
  const signals = [];
  if (rule.dedicatedPaths.some((pattern) => pattern.test(path))) signals.push('dedicated-path');
  if (rule.pathSignals.some((pattern) => pattern.test(path))) signals.push('product-path');
  if (rule.contentSignals.some((pattern) => pattern.test(content))) signals.push('direct-reference');
  if (/\/db\/migration\//.test(path) || /(^|\/)migrations?\//.test(path)) signals.push('migration');
  if (/route\s*\(|route-manifest|routes?\//.test(content) || /(^|\/)routes?\//.test(path)) signals.push('route');
  if (isTestPath(path)) signals.push('test');
  return [...new Set(signals)];
}

function dependencyRefs(content, product) {
  const rule = PRODUCT_RULES[product];
  return content
    .split('\n')
    .map((line, index) => ({ line: index + 1, text: line.trim() }))
    .filter(({ text }) =>
      /^(import|export|implementation|api|compileOnly|runtimeOnly)\b/.test(text)
      && rule.contentSignals.some((pattern) => pattern.test(text)),
    )
    .map(({ line, text }) => ({ line, text: text.slice(0, 240) }));
}

function classifyRow(path, content, product) {
  const rule = PRODUCT_RULES[product];
  const dedicated = rule.dedicatedPaths.some((pattern) => pattern.test(path));
  const area = areaFor(path);
  let targetOwner = dedicated ? rule.targetOwner : 'auraboot';
  let disposition = dedicated ? 'move' : 'remove-direct-reference-or-generalize';

  if (area === 'migration' && !dedicated) {
    targetOwner = rule.targetOwner;
    disposition = 'split-migration-owner';
  }

  return {
    product,
    path,
    area,
    currentOwner: 'auraboot',
    targetOwner,
    disposition,
    isTest: isTestPath(path),
    signals: signalNames(path, content, rule),
    dependencyRefs: dependencyRefs(content, product),
  };
}

export function buildInventory({ root, files }) {
  const rows = [];
  const contents = new Map();
  const normalizedFiles = [...new Set(files.map(normalizePath))].sort();

  for (const path of normalizedFiles) {
    if (!SOURCE_EXTENSIONS.has(extname(path))) continue;
    let content;
    try {
      content = readFileSync(resolve(root, path), 'utf8');
    } catch {
      continue;
    }
    contents.set(path, content);

    for (const [product, rule] of Object.entries(PRODUCT_RULES)) {
      const dedicated = rule.dedicatedPaths.some((pattern) => pattern.test(path));
      const signaled = dedicated
        || rule.pathSignals.some((pattern) => pattern.test(path))
        || rule.contentSignals.some((pattern) => pattern.test(content));
      if (signaled) rows.push(classifyRow(path, content, product));
    }
  }

  for (const row of rows) {
    if (row.isTest) {
      row.testEvidence = { status: 'test-file', candidates: [row.path] };
      continue;
    }
    const symbol = basename(row.path, extname(row.path));
    const candidates = rows
      .filter((candidate) => candidate.product === row.product && candidate.isTest)
      .filter((candidate) => contents.get(candidate.path)?.includes(symbol))
      .map((candidate) => candidate.path)
      .slice(0, 20);
    row.testEvidence = {
      status: candidates.length > 0 ? 'candidate-mapped' : 'unmapped',
      candidates,
    };
  }

  const counts = {};
  for (const product of Object.keys(PRODUCT_RULES)) {
    const productRows = rows.filter((row) => row.product === product);
    counts[product] = {
      total: productRows.length,
      move: productRows.filter((row) => row.disposition === 'move').length,
      splitMigrationOwner: productRows.filter((row) => row.disposition === 'split-migration-owner').length,
      coreLeakage: productRows.filter((row) => row.targetOwner === 'auraboot').length,
      tests: productRows.filter((row) => row.isTest).length,
      directDependencies: productRows.reduce((total, row) => total + row.dependencyRefs.length, 0),
      unmappedImplementationRows: productRows.filter(
        (row) => !row.isTest && row.testEvidence.status === 'unmapped',
      ).length,
    };
  }

  const inventory = {
    schemaVersion: 1,
    source: {
      repository: 'auraboot',
      commit: gitValue(root, ['rev-parse', 'HEAD']),
      trackedFileCount: normalizedFiles.length,
    },
    counts,
    rows,
  };
  inventory.identity = createHash('sha256').update(JSON.stringify(inventory)).digest('hex');
  return inventory;
}

export function collectTrackedFiles(root) {
  return execFileSync('git', ['-C', root, 'ls-files', '-z'], { encoding: 'utf8' })
    .split('\0')
    .filter(Boolean);
}

function gitValue(root, args) {
  try {
    return execFileSync('git', ['-C', root, ...args], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    }).trim();
  } catch {
    return null;
  }
}

function parseArgs(argv) {
  const options = { root: process.cwd(), output: null, stdout: false };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--root') options.root = resolve(argv[++index]);
    else if (argument === '--output') options.output = resolve(argv[++index]);
    else if (argument === '--stdout') options.stdout = true;
    else throw new Error(`Unknown argument: ${argument}`);
  }
  if (!options.output) {
    options.output = resolve(options.root, '.workspace/evidence/product-extraction/inventory.json');
  }
  return options;
}

export function writeInventory({ root, output }) {
  const inventory = buildInventory({ root, files: collectTrackedFiles(root) });
  mkdirSync(dirname(output), { recursive: true });
  writeFileSync(output, `${JSON.stringify(inventory, null, 2)}\n`);
  return inventory;
}

function printSummary(inventory, output) {
  process.stdout.write(`${JSON.stringify({ output, identity: inventory.identity, counts: inventory.counts }, null, 2)}\n`);
}

const isMain = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  try {
    const options = parseArgs(process.argv.slice(2));
    const inventory = writeInventory(options);
    if (options.stdout) process.stdout.write(`${JSON.stringify(inventory, null, 2)}\n`);
    else printSummary(inventory, relative(options.root, options.output));
  } catch (error) {
    process.stderr.write(`product extraction inventory failed: ${error.message}\n`);
    process.exitCode = 1;
  }
}
