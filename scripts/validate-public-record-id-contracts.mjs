#!/usr/bin/env node

import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const repoRoot = path.resolve(new URL('..', import.meta.url).pathname);
const workspaceRoot = path.resolve(repoRoot, '..');
const scanRoots = [
  path.join(repoRoot, 'plugins'),
  path.join(workspaceRoot, 'auraboot-enterprise', 'plugins'),
];

const findings = [];

async function exists(target) {
  try {
    await fs.access(target);
    return true;
  } catch {
    return false;
  }
}

async function collectJsonFiles(dir) {
  const out = [];
  if (!(await exists(dir))) return out;
  const entries = await fs.readdir(dir, { withFileTypes: true });
  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...await collectJsonFiles(fullPath));
      continue;
    }
    if (entry.isFile() && entry.name.endsWith('.json') && fullPath.includes(`${path.sep}config${path.sep}`)) {
      out.push(fullPath);
    }
  }
  return out;
}

function visit(value, file, jsonPath = '$') {
  if (Array.isArray(value)) {
    value.forEach((item, index) => visit(item, file, `${jsonPath}[${index}]`));
    return;
  }
  if (!value || typeof value !== 'object') {
    return;
  }

  if (Object.prototype.hasOwnProperty.call(value, 'targetRecordId')
      && !Object.prototype.hasOwnProperty.call(value, 'targetRecordPid')) {
    findings.push({
      file,
      jsonPath,
      field: 'targetRecordId',
      message: 'Public command target uses targetRecordId without targetRecordPid.',
    });
  }

  for (const [key, child] of Object.entries(value)) {
    visit(child, file, `${jsonPath}.${key}`);
  }
}

for (const root of scanRoots) {
  const files = await collectJsonFiles(root);
  for (const file of files) {
    const raw = await fs.readFile(file, 'utf8');
    try {
      visit(JSON.parse(raw), file);
    } catch (error) {
      findings.push({
        file,
        jsonPath: '$',
        field: 'json',
        message: `Invalid JSON: ${error.message}`,
      });
    }
  }
}

if (findings.length > 0) {
  console.error('Public record id contract validation failed.');
  for (const finding of findings) {
    console.error(`- ${path.relative(workspaceRoot, finding.file)} ${finding.jsonPath}: ${finding.message}`);
  }
  process.exit(1);
}

console.log('Public record id contract validation passed.');
