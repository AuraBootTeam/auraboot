import assert from 'node:assert/strict';
import { existsSync, readFileSync, statSync } from 'node:fs';
import test from 'node:test';

function read(path) {
  return readFileSync(path, 'utf8');
}

test('OSS reset init contract gate covers reset, DB, marketplace, and seed runner checks', () => {
  assert.ok(existsSync('scripts/check-reset-init-contracts.sh'));

  const gate = read('scripts/check-reset-init-contracts.sh');
  assert.match(gate, /set -euo pipefail/);
  assert.match(gate, /bash -n scripts\/oss-reset-and-init\.sh/);
  assert.match(gate, /bash -n scripts\/reset-db\.sh/);
  assert.match(gate, /bash -n scripts\/seed-marketplace\.sh/);
  assert.match(gate, /node --test scripts\/reset-init-contracts\.test\.mjs/);
  assert.match(gate, /node web-admin\/scripts\/run-showcase-seed-sequence\.test\.mjs/);
});

test('OSS reset init contract gate is executable for direct local use', () => {
  const mode = statSync('scripts/check-reset-init-contracts.sh').mode;

  assert.notEqual(
    mode & 0o111,
    0,
    'scripts/check-reset-init-contracts.sh must be executable because docs reference it directly',
  );
});

test('OSS CI runs reset init contract gate when reset or seed files change', () => {
  assert.ok(existsSync('.github/workflows/reset-init-contracts.yml'));

  const workflow = read('.github/workflows/reset-init-contracts.yml');
  assert.match(workflow, /name: Reset Init Contracts/);
  assert.match(workflow, /node-version: '20'/);
  assert.match(workflow, /scripts\/oss-reset-and-init\.sh/);
  assert.match(workflow, /scripts\/reset-db\.sh/);
  assert.match(workflow, /scripts\/seed-marketplace\.sh/);
  assert.match(workflow, /web-admin\/package\.json/);
  assert.match(workflow, /web-admin\/scripts\/run-showcase-seed-sequence\.mjs/);
  assert.match(workflow, /web-admin\/scripts\/run-showcase-seed-sequence\.test\.mjs/);
  assert.match(workflow, /bash scripts\/check-reset-init-contracts\.sh/);
});

test('OSS reset script fails fast and delegates showcase seeds through the ordered runner', () => {
  const reset = read('scripts/oss-reset-and-init.sh');

  assert.match(reset, /set -o pipefail/);
  assert.match(reset, /"\$SCRIPT_DIR\/seed-marketplace\.sh" 2>&1 \| tail -1/);
  assert.match(reset, /node scripts\/run-showcase-seed-sequence\.mjs[\s\S]*"\$\{seed_phases\[@\]\}"/);
  assert.match(reset, /node scripts\/run-showcase-seed-sequence\.mjs[\s\S]*dashboard-default invariants/);
  assert.doesNotMatch(reset, /npx playwright test tests\/api\/setup\/seed-showcase-/);
});

test('OSS marketplace seed is env-aware and writes the catalog to the system tenant', () => {
  const seed = read('scripts/seed-marketplace.sh');

  assert.match(seed, /set -euo pipefail/);
  assert.match(seed, /PLUGIN_DIRS="\$\{PLUGIN_DIRS:-\$PLUGINS_DIR\}"/);
  assert.match(seed, /SYSTEM_TENANT_ID="\$\{SYSTEM_TENANT_ID:-1\}"/);
  assert.match(seed, /PG_HOST:-localhost/);
  assert.match(seed, /PG_PORT:-5432/);
  assert.match(seed, /PG_DB:-aura_boot/);
  assert.match(seed, /PG_USER:-\$\{USER:-ghj\}/);
  assert.match(seed, /-v ON_ERROR_STOP=1/);
  assert.match(seed, /tenant_id = \$SYSTEM_TENANT_ID/);
  assert.match(seed, /WHERE tenant_id = \$SYSTEM_TENANT_ID/);
});
