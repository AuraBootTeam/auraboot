import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { compareManifests, isGitTracked, isGitIgnored } from './check-coverage-manifest-freshness.mjs';

// Each case is a drift that SHOULD be caught. A freshness gate that has never
// seen a stale file is indistinguishable from one that does not look.

const m = (rows, untested) => ({
  groups: [{ id: 'p', title: 'p', rows: rows.map((id) => ({ id, action: id })) }],
  stats: { commands: rows.length, untested },
});

test('an unchanged manifest is silent', () => {
  assert.deepEqual(compareManifests(m(['a', 'b'], 1), m(['a', 'b'], 1)).findings, []);
});

test('a command with no row is an error — the denominator shrank silently', () => {
  const r = compareManifests(m(['a'], 0), m(['a', 'b'], 1));
  assert.ok(r.findings.some((f) => f.level === 'error' && f.kind === 'missing-row'));
});

test('coverage going backwards is an error', () => {
  const r = compareManifests(m(['a', 'b'], 0), m(['a', 'b'], 2));
  assert.ok(r.findings.some((f) => f.kind === 'coverage-regressed'));
});

test('coverage improving is not an error — being ahead of the file is allowed', () => {
  assert.deepEqual(compareManifests(m(['a', 'b'], 2), m(['a', 'b'], 0)).findings, []);
});

test('a row for a command that no longer exists warns rather than blocks', () => {
  const r = compareManifests(m(['a', 'gone'], 0), m(['a'], 0));
  assert.deepEqual(r.findings.map((f) => f.level), ['warn']);
});

// The persistence assertion, against a real temp git repo (no mocks): a matrix
// nobody committed is transient. git does NOT apply .gitignore to tracked files,
// so an untracked-but-ignored file is caught by the tracked check, and isGitIgnored
// is only a diagnostic on that path — never a standalone (always-false) gate.
test('isGitTracked / isGitIgnored — committed matrix passes, untracked one fails', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'freshness-git-'));
  const g = (...a) => execFileSync('git', ['-C', dir, ...a], { stdio: 'pipe' });
  try {
    g('init', '-q'); g('config', 'user.email', 't@t'); g('config', 'user.name', 't');
    mkdirSync(path.join(dir, 'docs/coverage'), { recursive: true });
    writeFileSync(path.join(dir, 'docs/coverage/m.json'), '{}');
    assert.equal(isGitTracked(dir, 'docs/coverage/m.json'), false, 'uncommitted → not tracked (gate must fail)');
    g('add', 'docs/coverage/m.json'); g('commit', '-qm', 'add');
    assert.equal(isGitTracked(dir, 'docs/coverage/m.json'), true, 'committed → tracked (gate passes)');
    assert.equal(isGitIgnored(dir, 'docs/coverage/m.json'), false, 'a tracked file is never ignored');
    writeFileSync(path.join(dir, '.gitignore'), 'docs/coverage/ignored.json\n');
    writeFileSync(path.join(dir, 'docs/coverage/ignored.json'), '{}');
    assert.equal(isGitTracked(dir, 'docs/coverage/ignored.json'), false, 'gitignored+untracked is caught as untracked');
    assert.equal(isGitIgnored(dir, 'docs/coverage/ignored.json'), true, 'diagnostic: reason is a .gitignore rule');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
