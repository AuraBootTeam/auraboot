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
  assert.match(gate, /bash -n scripts\/sync-marketplace-catalog\.sh/);
  assert.match(gate, /bash -n scripts\/docker-ga-e2e-bootstrap\.sh/);
  assert.match(gate, /bash -n scripts\/env\/reset-and-init\.sh/);
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
  const sync = read('scripts/sync-marketplace-catalog.sh');

  assert.match(seed, /set -euo pipefail/);
  assert.match(seed, /deprecated name/);
  assert.match(seed, /exec "\$SCRIPT_DIR\/sync-marketplace-catalog\.sh" "\$@"/);

  assert.match(sync, /PLUGIN_DIRS="\$\{PLUGIN_DIRS:-\$PLUGINS_DIR\}"/);
  assert.match(sync, /SYSTEM_TENANT_ID="\$\{SYSTEM_TENANT_ID:-1\}"/);
  assert.match(sync, /PG_HOST:-localhost/);
  assert.match(sync, /PG_PORT:-5432/);
  assert.match(sync, /PG_DB:-aura_boot/);
  assert.match(sync, /PG_USER:-\$\{USER:-ghj\}/);
  assert.match(sync, /-v ON_ERROR_STOP=1/);
  assert.match(sync, /tenant_id = \$SYSTEM_TENANT_ID/);
  assert.match(sync, /WHERE tenant_id = \$SYSTEM_TENANT_ID/);
});

test('normalized reset entrypoint makes product runtime and profile explicit', () => {
  const script = read('scripts/env/reset-and-init.sh');

  assert.match(script, /--product=oss\|enterprise/);
  assert.match(script, /--runtime=host\|docker/);
  assert.match(script, /--profile=<name>/);
  assert.match(script, /oss:docker\) PROFILE="e2e"/);
  assert.match(script, /enterprise:docker\) PROFILE="enterprise-demo"/);
  assert.match(script, /oss:host/);
  assert.match(script, /oss:docker/);
  assert.match(script, /export_docker_proxy_defaults\(\)/);
  assert.match(script, /host\.docker\.internal/);
  assert.match(script, /docker-ga-e2e-down\.sh" --purge/);
  assert.match(script, /GA_E2E_FRONTEND_IMAGE="\$\{GA_E2E_FRONTEND_IMAGE:-node:22-bookworm-slim\}"/);
  assert.match(script, /enterprise:host/);
  assert.match(script, /enterprise:docker/);
  assert.match(script, /stop-isolated\.sh" --slug="\$SLUG" --purge/);
  assert.match(script, /scripts\/dev\/import-isolated-plugins\.sh/);
  assert.match(script, /import_profile="enterprise-demo"/);
  assert.match(script, /--edition=enterprise/);
  assert.match(script, /sync-marketplace-catalog\.sh/);
  assert.match(script, /PG_PORT="\$PG_PORT"/);
  assert.match(script, /PGPASSWORD="\$\{PGPASSWORD:-auraboot_dev\}"/);
});

test('isolated plugin import retries each plugin before importing dependents', () => {
  const script = read('scripts/dev/import-isolated-plugins.sh');

  assert.match(script, /IMPORT_ATTEMPTS="\$\{IMPORT_ATTEMPTS:-2\}"/);
  assert.match(script, /import_plugin_once\(\)/);
  assert.match(script, /while \[ "\$attempt" -le "\$IMPORT_ATTEMPTS" \]/);
  assert.match(script, /sleep "\$attempt"/);
  assert.match(script, /failures\+=\("\$plugin: \$result"\)/);
});

test('isolated plugin import validates latest import state instead of whole history', () => {
  const script = read('scripts/dev/import-isolated-plugins.sh');

  assert.match(script, /successful_plugin_ids=\(\)/);
  assert.match(script, /verify_latest_import_statuses\(\)/);
  assert.match(script, /distinct on \(plugin_id\)/);
  assert.match(script, /latest import status is not success/);
  assert.doesNotMatch(script, /where status <> 'success'/i);
});

test('showcase CRM opportunity seeds send date-only values to DATE fields', () => {
  for (const file of [
    'web-admin/tests/api/setup/seed-showcase-data.spec.ts',
    'web-admin/tests/api/setup/seed-showcase-extended.spec.ts',
  ]) {
    const source = read(file);
    assert.doesNotMatch(
      source,
      /crm_opp_expected_close_date:\s*dateTimeAt\(/,
      `${file} must not feed datetime values into crm_opp_expected_close_date`,
    );
    assert.doesNotMatch(
      source,
      /closeDate:\s*dateTimeAt\(/,
      `${file} must keep opportunity closeDate seed values date-only`,
    );
  }
});

test('docker GA bootstrap initializes a blank stack before admin login', () => {
  const script = read('scripts/docker-ga-e2e-bootstrap.sh');

  assert.match(script, /ensure_bootstrap_initialized\(\)/);
  assert.match(script, /api\/bootstrap\/status/);
  assert.match(script, /api\/bootstrap\/setup/);
  assert.match(script, /seedDemoData/);
  assert.match(script, /data = d\.get\('data'\) if isinstance\(d, dict\) else \{\}/);
  assert.match(script, /data\.get\('initialized'\) is True/);
  assert.match(script, /d\.get\('code'\) == '0'/);
  assert.match(script, /ensure_bootstrap_initialized\s*\n\s*# 1\. Login as admin -> JWT/);
});

test('docker GA bootstrap refreshes storage against the active isolated stack only', () => {
  const script = read('scripts/docker-ga-e2e-bootstrap.sh');

  assert.match(script, /API_BASE="http:\/\/localhost:6444"/);
  assert.match(script, /BACKEND_URL="\$API_BASE"[\s\S]*BE_PORT=6444[\s\S]*PGPORT=5433/);
  assert.match(script, /npx playwright test tests\/auth\.setup\.ts[\s\S]*--project=auth --no-deps --reporter=line/);
  assert.doesNotMatch(script, /npx playwright test tests\/auth\.setup\.ts\s*\\\n\s*--reporter=line/);
});
