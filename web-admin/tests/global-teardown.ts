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
 * - ab_agent_run/action/bif/interrupt_log + ab_ai_trace/span: replay rows with E2EL* prefixes
 *
 * Does NOT delete fixture data (e2et_record etc. managed by setup).
 *
 * @since 4.0.0
 */

import type { FullConfig } from '@playwright/test';
import { BASE_URL, PG_CONN } from './helpers/environments';

const ADMIN_STORAGE = process.env.PW_ADMIN_STORAGE_STATE || './tests/storage/admin.json';

async function globalTeardown(config: FullConfig): Promise<void> {
  // Resolve baseURL from Playwright config (native fetch requires absolute URLs)
  const baseURL = config.projects[0]?.use?.baseURL ?? BASE_URL;
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
    await cleanupTempModels();

    // Clean up E2E-seeded AI-center menu rows (E2EM_* pid prefix). These
    // used to be cleaned up per spec file in afterAll but the per-file
    // cleanup raced across workers (two spec files share the same menu
    // path; whichever file finished first deleted rows the other still
    // needed, causing sidebar-link timeouts — the USP-11 flake root
    // cause). See _real-backend-helpers.ts cleanup* no-ops.
    await cleanupE2eMenus();
    await cleanupE2eSoulProfiles();
    await cleanupE2eAgentReplayRows();

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

async function cleanupTempModels(): Promise<void> {
  try {
    const rows = await executeCleanupQuery<{ count: number | string }>(
      `SELECT COUNT(*)::int AS count
         FROM ab_meta_model
        WHERE code LIKE 'intg_e2e\\_%' ESCAPE '\\';`,
    );
    const count = Number(rows[0]?.count ?? 0);

    if (count === 0) return;

    console.log(`  Cleaning ${count} temp integration test models`);

    await executeCleanupSql(`
CREATE TEMP TABLE tmp_e2e_temp_models AS
SELECT id, code
  FROM ab_meta_model
 WHERE code LIKE 'intg_e2e\\_%' ESCAPE '\\';

CREATE TEMP TABLE tmp_e2e_temp_fields AS
SELECT DISTINCT b.field_id
  FROM ab_meta_model_field_binding b
  JOIN tmp_e2e_temp_models m ON m.id = b.model_id
 WHERE b.deleted_flag = false
   AND (b.is_system_binding IS NULL OR b.is_system_binding = false);

DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT code FROM tmp_e2e_temp_models LOOP
    EXECUTE format('DROP TABLE IF EXISTS %I', 'mt_' || r.code);
  END LOOP;
END $$;

DELETE FROM ab_meta_model_field_binding
 WHERE model_id IN (SELECT id FROM tmp_e2e_temp_models);

DELETE FROM ab_meta_field f
 WHERE f.id IN (SELECT field_id FROM tmp_e2e_temp_fields)
   AND NOT EXISTS (
     SELECT 1 FROM ab_meta_model_field_binding b WHERE b.field_id = f.id
   );

DELETE FROM ab_meta_model
 WHERE id IN (SELECT id FROM tmp_e2e_temp_models);

DROP TABLE tmp_e2e_temp_fields;
DROP TABLE tmp_e2e_temp_models;
`);
  } catch (e) {
    console.log(`  ⚠️ Temp model cleanup error (non-fatal): ${e}`);
  }
}

/**
 * Delete menu rows seeded by aurabot E2E helpers (pid prefix `E2EM_`).
 * Runs once after all workers finish so it cannot race with in-flight tests.
 */
async function cleanupE2eMenus(): Promise<void> {
  try {
    await executeCleanupSql(`DELETE FROM ab_menu WHERE pid LIKE 'E2EM\\_%' ESCAPE '\\';`);
  } catch (e) {
    console.log(`  ⚠️ E2E menu cleanup error (non-fatal): ${e}`);
  }
}

/**
 * Delete any lingering user-soul-profile rows seeded by aurabot E2E
 * helpers. Covers:
 *   - Rows with pid prefix `E2EUSP` (direct seed rows).
 *   - Tombstone rows inserted by the backend's forgetProfile /
 *     admin-forget code paths — these use a ULID pid that does NOT
 *     carry the test prefix and therefore slip past the per-spec
 *     afterEach cleanup (historical bug: tombstones accumulated across
 *     runs and eventually collided with the `uq_user_soul_profile_active`
 *     partial unique index).
 *   - Any rows under e2e_victim_* / e2e_admin_probe_* user ids used by
 *     the admin-dashboard specs.
 *
 * Runs once after all workers finish so it cannot race with in-flight
 * tests.
 */
async function cleanupE2eSoulProfiles(): Promise<void> {
  try {
    await executeCleanupSql(`DELETE FROM ab_agent_user_soul_profile
                 WHERE pid LIKE 'E2EUSP%'
                    OR user_id LIKE 'e2e_%'
                    OR edited_fields ? '_forgotten';`);
  } catch (e) {
    console.log(`  ⚠️ E2E soul-profile cleanup error (non-fatal): ${e}`);
  }
}

/**
 * Delete rows seeded by tests/e2e/aurabot/admin-agent-runs.spec.ts. The spec
 * intentionally does not clean up in afterAll so failed runs leave inspectable
 * traces until the suite-level teardown runs once after all workers finish.
 */
async function cleanupE2eAgentReplayRows(): Promise<void> {
  try {
    await executeCleanupSql(`
DELETE FROM ab_ai_trace_span
 WHERE trace_id IN (
       SELECT trace_id FROM ab_ai_trace WHERE session_id LIKE 'E2ELR%'
     );

DELETE FROM ab_ai_trace
 WHERE session_id LIKE 'E2ELR%';

DELETE FROM ab_agent_interrupt_log
 WHERE pid LIKE 'E2ELI%'
    OR active_run_id LIKE 'E2ELR%'
    OR subtask_run_id LIKE 'E2ELR%';

DELETE FROM ab_agent_bif
 WHERE pid LIKE 'E2ELB%'
    OR run_id LIKE 'E2ELR%';

DELETE FROM ab_agent_action
 WHERE pid LIKE 'E2ELA%'
    OR run_id LIKE 'E2ELR%';

DELETE FROM ab_agent_run
 WHERE pid LIKE 'E2ELR%'
    OR parent_run_id LIKE 'E2ELR%';

DELETE FROM ab_im_message
 WHERE client_msg_id LIKE 'in-E2ELU%'
    OR client_msg_id LIKE 'out-E2ELU%'
    OR conversation_id IN (
       SELECT id FROM ab_im_conversation WHERE name LIKE 'E2ELC\\_%' ESCAPE '\\'
     );

DELETE FROM ab_im_conversation
 WHERE name LIKE 'E2ELC\\_%' ESCAPE '\\';

DELETE FROM ab_agent_task
 WHERE pid LIKE 'E2ELT%';
`);
  } catch (e) {
    console.log(`  ⚠️ E2E agent replay cleanup error (non-fatal): ${e}`);
  }
}

async function executeCleanupSql(sql: string): Promise<void> {
  const { Client } = await import('pg');
  const client = new Client(PG_CONN);

  await client.connect();
  try {
    await client.query(sql);
  } finally {
    await client.end();
  }
}

async function executeCleanupQuery<T extends Record<string, unknown>>(sql: string): Promise<T[]> {
  const { Client } = await import('pg');
  const client = new Client(PG_CONN);

  await client.connect();
  try {
    const result = await client.query<T>(sql);
    return result.rows;
  } finally {
    await client.end();
  }
}

export default globalTeardown;
