import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  REQUIRED_SUITES,
  summarizeCapabilityEvalResults,
} from './capability-eval-junit.mjs';

function fixture(overrides = new Map()) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'capability-eval-junit-'));
  for (const [task, className] of REQUIRED_SUITES) {
    if (overrides.get(className) === 'missing') continue;
    const stats = overrides.get(className) ?? { tests: 1, skipped: 0, failures: 0, errors: 0 };
    const dir = path.join(root, task);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(
      path.join(dir, `TEST-${className}.xml`),
      `<testsuite name="${className}" tests="${stats.tests}" skipped="${stats.skipped}" ` +
      `failures="${stats.failures}" errors="${stats.errors}"></testsuite>`,
    );
  }
  return root;
}

function summarize(root) {
  return summarizeCapabilityEvalResults(root, () => {});
}

function javaFiles(root) {
  const files = [];
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    const target = path.join(root, entry.name);
    if (entry.isDirectory()) files.push(...javaFiles(target));
    else if (entry.name.endsWith('.java')) files.push(target);
  }
  return files;
}

test('explicit inventory contains every agent-eval-live source suite', () => {
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
  const testRoot = path.join(repoRoot, 'platform/src/test/java');
  const discovered = javaFiles(testRoot)
    .filter((file) => fs.readFileSync(file, 'utf8').split('\n')
      .some((line) => /^\s*@Tag\("agent-eval-live"\)\s*$/.test(line)))
    .map((file) => path.relative(testRoot, file).replace(/\.java$/, '').split(path.sep).join('.'))
    .sort();
  const inventoried = REQUIRED_SUITES
    .map(([, className]) => className)
    .filter((className) => discovered.includes(className))
    .sort();
  assert.deepEqual(inventoried, discovered);
});

test('accepts only when every required suite ran and passed', () => {
  const root = fixture();
  try {
    const report = summarize(root);
    assert.equal(report.ok, true);
    assert.equal(report.totals.suites, REQUIRED_SUITES.length);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('rejects an explicit test failure', () => {
  const target = REQUIRED_SUITES[3][1];
  const root = fixture(new Map([[target, { tests: 1, skipped: 0, failures: 1, errors: 0 }]]));
  try {
    const report = summarize(root);
    assert.equal(report.ok, false);
    assert.match(report.violations.join('\n'), /FAILED/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('rejects an all-skipped run even though Gradle would exit zero', () => {
  const allSkipped = new Map(
    REQUIRED_SUITES.map(([, className]) => [
      className,
      { tests: 1, skipped: 1, failures: 0, errors: 0 },
    ]),
  );
  const root = fixture(allSkipped);
  try {
    const report = summarize(root);
    assert.equal(report.ok, false);
    assert.equal(report.totals.skipped, REQUIRED_SUITES.length);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('rejects a missing required suite instead of trusting a broad XML glob', () => {
  const target = REQUIRED_SUITES.at(-1)[1];
  const root = fixture(new Map([[target, 'missing']]));
  try {
    const report = summarize(root);
    assert.equal(report.ok, false);
    assert.match(report.violations.join('\n'), /MISSING/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
