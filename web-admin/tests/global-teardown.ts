/**
 * Global Teardown for Playwright E2E Tests
 *
 * Runs once after all tests complete. Cleans up residual test data
 * that individual spec afterAll hooks may have missed.
 *
 * Cleanup targets (by naming convention):
 * - mt_e2et_order: titles starting with "E2E "
 * - mt_e2et_order_item: orphaned items from deleted orders
 * - ab_meta_model: codes starting with "intg_e2e_" (temp models)
 *
 * Does NOT delete fixture data (e2et_record etc. managed by setup).
 *
 * @since 4.0.0
 */

import type { FullConfig } from '@playwright/test';

const ADMIN_STORAGE = './tests/storage/admin.json';

async function globalTeardown(config: FullConfig): Promise<void> {
  // Resolve baseURL from Playwright config (native fetch requires absolute URLs)
  const baseURL = config.projects[0]?.use?.baseURL ?? 'http://localhost:5173';
  console.log('🧹 Running global teardown...');

  try {
    // Use fetch to call cleanup APIs with stored auth
    const fs = await import('fs');
    const path = await import('path');

    const storagePath = path.resolve(ADMIN_STORAGE);
    if (!fs.existsSync(storagePath)) {
      console.log('  ⚠️ No admin storage found, skipping cleanup');
      return;
    }

    const storageState = JSON.parse(fs.readFileSync(storagePath, 'utf-8'));
    const cookies = storageState.cookies || [];
    const cookieHeader = cookies
      .map((c: { name: string; value: string }) => `${c.name}=${c.value}`)
      .join('; ');

    if (!cookieHeader) {
      console.log('  ⚠️ No auth cookies found, skipping cleanup');
      return;
    }

    // Clean up E2E test orders (title starts with "E2E ")
    await cleanupByPrefix(baseURL, cookieHeader, 'e2et_order', 'e2et_order_title', 'E2E ');

    // Clean up temp integration test models
    await cleanupTempModels(baseURL, cookieHeader);

    // Clean up E2E-seeded AI-center menu rows (E2EM_* pid prefix). These
    // used to be cleaned up per spec file in afterAll but the per-file
    // cleanup raced across workers (two spec files share the same menu
    // path; whichever file finished first deleted rows the other still
    // needed, causing sidebar-link timeouts — the USP-11 flake root
    // cause). See _real-backend-helpers.ts cleanup* no-ops.
    await cleanupE2eMenus();

    console.log('✅ Global teardown complete');
  } catch (error) {
    // Teardown should not fail the test run
    console.log(`  ⚠️ Teardown error (non-fatal): ${error}`);
  }
}

async function cleanupByPrefix(
  baseURL: string,
  cookieHeader: string,
  modelCode: string,
  fieldName: string,
  prefix: string,
): Promise<void> {
  try {
    const listUrl = `${baseURL}/api/dynamic/${modelCode}/list?current=1&size=100`;
    const resp = await fetch(listUrl, {
      headers: { Cookie: cookieHeader },
    });

    if (!resp.ok) return;

    const body = await resp.json();
    const records = body.data?.records || body.data?.list || [];
    const toDelete = records.filter((r: Record<string, unknown>) =>
      String(r[fieldName] || '').startsWith(prefix),
    );

    if (toDelete.length === 0) return;

    console.log(`  Cleaning ${toDelete.length} ${modelCode} records with prefix "${prefix}"`);

    for (const record of toDelete) {
      const pid = record.pid;
      if (!pid) continue;

      // Try delete command first
      await fetch(`${baseURL}/api/meta/commands/execute/e2et:delete_order`, {
        method: 'post',
        headers: {
          Cookie: cookieHeader,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          targetRecordId: pid,
          operationType: 'delete',
          payload: {},
        }),
      }).catch(() => {});
    }
  } catch {
    // Silently ignore cleanup errors
  }
}

async function cleanupTempModels(baseURL: string, cookieHeader: string): Promise<void> {
  try {
    const resp = await fetch(`${baseURL}/api/meta/models?current=1&size=200`, {
      headers: { Cookie: cookieHeader },
    });

    if (!resp.ok) return;

    const body = await resp.json();
    const models = body.data?.records || body.data?.list || body.data || [];

    if (!Array.isArray(models)) return;

    const tempModels = models.filter((m: Record<string, unknown>) =>
      String(m.code || '').startsWith('intg_e2e_'),
    );

    if (tempModels.length === 0) return;

    console.log(`  Cleaning ${tempModels.length} temp integration test models`);

    for (const model of tempModels) {
      const pid = model.pid;
      if (!pid) continue;

      await fetch(`${baseURL}/api/meta/models/${pid}`, {
        method: 'delete',
        headers: { Cookie: cookieHeader },
      }).catch(() => {});
    }
  } catch {
    // Silently ignore
  }
}

/**
 * Delete menu rows seeded by aurabot E2E helpers (pid prefix `E2EM_`).
 * Runs once after all workers finish so it cannot race with in-flight
 * tests. psql is synchronous via child_process — acceptable in teardown.
 */
async function cleanupE2eMenus(): Promise<void> {
  try {
    const { execSync } = await import('node:child_process');
    execSync(
      `psql -h localhost -U ghj -d aura_boot -P pager=off -v ON_ERROR_STOP=1 -tA`,
      {
        input: `DELETE FROM ab_menu WHERE pid LIKE 'E2EM\\_%' ESCAPE '\\';`,
        stdio: ['pipe', 'pipe', 'pipe'],
      },
    );
  } catch (e) {
    console.log(`  ⚠️ E2E menu cleanup error (non-fatal): ${e}`);
  }
}

export default globalTeardown;
