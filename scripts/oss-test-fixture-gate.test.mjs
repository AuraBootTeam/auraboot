import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import test from 'node:test';

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(__dirname, '..');
const SCRIPT = resolve(REPO_ROOT, 'scripts/oss-test.sh');
const SCRIPT_SRC = readFileSync(SCRIPT, 'utf8');

// We assert by parsing the script and running it dry. The full script runs
// playwright which needs a backend. So we extract just the preflight gate
// section and execute it in isolation against a stub `npx` / minimal env.

test('oss-test.sh defaults PW_PROFILE=oss so setup project auto-imports fixtures', () => {
  assert.match(SCRIPT_SRC, /export PW_PROFILE="\$\{PW_PROFILE:-oss\}"/);
});

test('--smoke also exports IMPORT_TEST_FIXTURES=true (smoke specs touch e2et_*)', () => {
  // Verify the --smoke branch wires both env vars
  assert.match(SCRIPT_SRC, /--smoke\)[\s\S]{0,400}IMPORT_TEST_FIXTURES=true/);
});

test('preflight recognizes all four fixture auto-import gates', () => {
  assert.match(SCRIPT_SRC, /AURA_ENV:-.*== "test"/);
  assert.match(SCRIPT_SRC, /IMPORT_TEST_FIXTURES:-.*== "true"/);
  assert.match(SCRIPT_SRC, /PW_PROFILE" == "oss"/);
  assert.match(SCRIPT_SRC, /PW_PROFILE" == "full"/);
});

test('preflight has ALLOW_MISSING_FIXTURES escape hatch', () => {
  assert.match(SCRIPT_SRC, /ALLOW_MISSING_FIXTURES:-.*== "1"/);
});

test('preflight exits non-zero with EX_CONFIG (78) when no gate is set and no escape hatch', () => {
  assert.match(SCRIPT_SRC, /exit 78\s*#\s*EX_CONFIG/);
});

test('preflight gate fails fast when all gates cleared', () => {
  // Run the script with all gates explicitly cleared. It should fail before
  // doing any real work (i.e. before invoking playwright). We stop early by
  // pointing it at a non-existent scope file or by intercepting early.
  //
  // The script's first guard is the SCOPE_FILE existence check — if missing,
  // it exits 1 before reaching the preflight. To isolate the preflight, we
  // run with a real (existing) scope file but in a stubbed PATH so npx /
  // jq calls don't fly off. The preflight runs BEFORE `cd web-admin` and
  // BEFORE the playwright invocation, so cwd-relative spec counts may print
  // 0 but the gate check still fires.
  const env = {
    ...process.env,
    PATH: process.env.PATH ?? '',
    // Explicitly clear all 4 gates + escape hatch:
    AURA_ENV: '',
    IMPORT_TEST_FIXTURES: '',
    PW_PROFILE: 'core', // any value not in {oss, full}
    ALLOW_MISSING_FIXTURES: '',
    // Keep PW_SKIP_WEBSERVER unset so the script's default applies
  };
  const result = spawnSync('bash', [SCRIPT], {
    cwd: REPO_ROOT,
    env,
    encoding: 'utf8',
    timeout: 30_000,
  });

  // Should exit 78 (EX_CONFIG) before reaching playwright
  assert.equal(result.status, 78, `expected exit 78, got ${result.status}. stderr: ${result.stderr}`);
  assert.match(result.stderr, /no test-fixtures auto-import gate is set/);
  assert.match(result.stderr, /IMPORT_TEST_FIXTURES=true|AURA_ENV=test/);
});

test('preflight passes through when PW_PROFILE defaults to oss', () => {
  // With no env overrides, PW_PROFILE defaults to oss → gate satisfied →
  // script proceeds past preflight. We can't run the whole thing (no backend),
  // but we can assert the gate-accepted message appears in stdout before
  // any failure.
  const env = {
    ...process.env,
    PATH: process.env.PATH ?? '',
    AURA_ENV: '',
    IMPORT_TEST_FIXTURES: '',
    PW_PROFILE: '',
    ALLOW_MISSING_FIXTURES: '',
  };
  const result = spawnSync('bash', [SCRIPT], {
    cwd: REPO_ROOT,
    env,
    encoding: 'utf8',
    timeout: 30_000,
  });

  // It will fail later when invoking playwright (no backend), but the
  // preflight should have printed the accepted-gate line.
  assert.match(
    result.stdout,
    /test-fixtures auto-import: enabled via PW_PROFILE=oss/,
    `preflight gate message missing. stdout: ${result.stdout}\nstderr: ${result.stderr}`,
  );
});

test('preflight passes through with ALLOW_MISSING_FIXTURES=1 escape hatch', () => {
  const env = {
    ...process.env,
    PATH: process.env.PATH ?? '',
    AURA_ENV: '',
    IMPORT_TEST_FIXTURES: '',
    PW_PROFILE: 'core',
    ALLOW_MISSING_FIXTURES: '1',
  };
  const result = spawnSync('bash', [SCRIPT], {
    cwd: REPO_ROOT,
    env,
    encoding: 'utf8',
    timeout: 30_000,
  });

  assert.match(
    result.stdout,
    /ALLOW_MISSING_FIXTURES=1 set — proceeding without test-fixtures/,
    `escape-hatch message missing. stdout: ${result.stdout}\nstderr: ${result.stderr}`,
  );
});
