#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';

const testRoot = path.resolve('tests/e2e');

const enterpriseDirs = new Set([
  'annual-plan',
  'asset-management',
  'construction-process',
  'contract-cost',
  'doc-knowledge',
  'dual-prevention',
  'enterprise',
  'finance',
  'finance-accounting',
  'inventory',
  'license',
  'logistics',
  'maintenance',
  'marketplace',
  'pcba',
  'pcba-solution',
  'payment',
  'procurement',
  'product-catalog',
  'project-management',
  'quality',
  'quarry',
  'sales',
  'sales-templates',
  'tax',
  'templates',
]);

const contractDirs = new Set([
  'action-system',
  'activity',
  'agent-control-plane',
  'approval',
  'bpm',
  'command',
  'dashboard',
  'data-tools',
  'e2et-order',
  'integration',
  'model',
  'named-query',
  'plugin',
  'query-builder',
  'scheduler',
  'search',
  'smart-components',
]);
const enterpriseProfileExtraDirs = new Set(['plugin']);
const enterpriseFilePatterns = [
  /^aurabot\/pcba-.*\.spec\.ts$/,
  /^plugin\/asset-.*\.spec\.ts$/,
  /^plugin\/pcba-.*\.spec\.ts$/,
  /^plugin\/pm-.*\.spec\.ts$/,
  /^plugin\/plugin-all-packages-smoke\.spec\.ts$/,
];

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const filePath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(filePath, out);
    } else if (entry.isFile() && entry.name.endsWith('.spec.ts')) {
      out.push(filePath);
    }
  }
  return out;
}

function firstScopeDir(filePath) {
  const rel = path.relative(testRoot, filePath);
  return rel.split(path.sep)[0] || '';
}

function classify(filePath) {
  const rel = path.relative(testRoot, filePath).split(path.sep).join('/');
  if (enterpriseFilePatterns.some((pattern) => pattern.test(rel))) return 'enterprise';
  const dir = firstScopeDir(filePath);
  if (enterpriseDirs.has(dir)) return 'enterprise';
  if (contractDirs.has(dir)) return 'contract';
  return 'oss';
}

const files = walk(testRoot).sort();
const counts = { oss: 0, contract: 0, enterprise: 0 };
const dirs = new Map();
for (const file of files) {
  const dir = firstScopeDir(file);
  const scope = classify(file);
  counts[scope] += 1;
  const stat = dirs.get(dir) || { oss: 0, contract: 0, enterprise: 0 };
  stat[scope] += 1;
  dirs.set(dir, stat);
}

const ambiguous = [];
for (const [dir, stat] of [...dirs.entries()].sort(([a], [b]) => a.localeCompare(b))) {
  const nonZero = Object.entries(stat).filter(([, count]) => count > 0);
  if (nonZero.length > 1) ambiguous.push({ dir, ...stat });
}

const defaultOssDirs = [...dirs.keys()]
  .filter((dir) => !enterpriseDirs.has(dir) && !contractDirs.has(dir))
  .sort();

console.log(
  JSON.stringify(
    {
      testRoot,
      specs: files.length,
      counts,
      defaultOssDirs,
      ambiguous,
      enterpriseProfileExtraDirs: [...enterpriseProfileExtraDirs],
      enterpriseFilePatterns: enterpriseFilePatterns.map((pattern) => pattern.source),
      profiles: {
        oss: 'setup + auth + e2e specs outside enterprise dirs/file patterns, plus oss-deep',
        contract: 'setup + auth + shared platform contract dirs',
        enterpriseSmoke:
          'setup + auth + @smoke specs inside enterprise dirs plus enterprise profile extra dirs',
        enterpriseFull:
          'setup + auth + all specs inside enterprise dirs plus enterprise profile extra dirs',
      },
    },
    null,
    2,
  ),
);
