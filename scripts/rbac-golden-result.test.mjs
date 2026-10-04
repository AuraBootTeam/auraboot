import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawnSync } from 'node:child_process';
const titles = [
  'tenant_admin resolves the full matrix surface and sees admin-tier menus',
  'tenant_member resolves only the L1 baseline, sees no admin-tier menu, and is admitted to the app',
];
for (const [name, mutate, expected] of [
  ['two successful roles', () => {}, 0],
  ['zero tests', r => { r.suites = []; }, 1],
  ['skipped role', r => { r.suites[0].specs[0].tests[0].results[0].status = 'skipped'; }, 1],
  ['retried role', r => { r.suites[0].specs[0].tests[0].results.push({ status: 'passed' }); }, 1],
  ['wrong project', r => { r.suites[0].specs[0].tests[0].projectName = 'setup'; }, 1],
  ['duplicate role', r => { r.suites[0].specs[1] = r.suites[0].specs[0]; }, 1],
  ['runner error', r => { r.errors = [{ message: 'worker died' }]; }, 1],
  ['missing timestamp', r => { delete r.stats; }, 1],
  ['stale prior day', r => { r.stats.startTime = new Date(Date.now() - 86400000).toISOString(); }, 1],
  ['future timestamp', r => { r.stats.startTime = new Date(Date.now() + 86400000).toISOString(); }, 1],
]) test(`nightly receipt rejects false evidence: ${name}`, t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rbac-receipt-contract-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const report = { stats: { startTime: new Date(Date.now() - 1000).toISOString(), duration: 500 }, suites: [{ specs: titles.map(title => ({ title, tests: [{ projectName: 'chromium', status: 'expected', results: [{ status: 'passed' }] }] })) }] };
  mutate(report);
  const file = path.join(dir, 'results.json');
  fs.writeFileSync(file, JSON.stringify(report));
  const result = spawnSync(process.execPath, [path.join(import.meta.dirname, 'rbac-golden-result.mjs'), file, '1', 'contract-fixture', path.join(import.meta.dirname, '..')], { encoding: 'utf8' });
  assert.equal(result.status, expected, result.stderr);
  assert.equal(fs.existsSync(path.join(dir, 'rbac-receipt.json')), expected === 0);
  if (expected === 0) {
    const second = spawnSync(process.execPath, [path.join(import.meta.dirname, 'rbac-golden-result.mjs'), file, '1', 'contract-fixture', path.join(import.meta.dirname, '..')]);
    assert.notEqual(second.status, 0, 'existing receipt must remain immutable');
  }
});
