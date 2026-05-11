/**
 * Real-backend helpers for Learning Loop / Interrupt E2E (PR-64).
 *
 * These tests drive the live Spring Boot backend on :6443 instead of
 * stubbing API routes. They seed rows directly via node-postgres (bypassing
 * auth token minting) and verify results by hitting the read endpoints
 * AND cross-checking DB state.
 *
 * Tenant is fixed to the admin's primary tenant (303848950530707456)
 * because the admin JWT resolves to that tenant and every read is scoped
 * by the tenant interceptor.
 */

import { execFileSync, execSync } from 'node:child_process';
import { BACKEND_URL } from '../../helpers/playwright-env';

// Admin primary tenant — matches the JWT issued by
// `admin@auraboot.com / Test2026x`. Keep in sync with
// scripts/reset-and-init.sh.
/**
 * Admin's primary tenant. Resolved lazily at first import by querying
 * the JWT claim via `/api/auth/login` — the id rotates on DB resets, so
 * hard-coding is fragile.
 */
function resolveAdminTenantId(): string {
  try {
    // Honor BACKEND_URL / BE_PORT so isolated docker stacks (auraboot-ga-e2e
    // on :6444, auraboot-r2 on :6445, etc.) resolve the correct tenant.
    // Hardcoding :6443 made every docker-stack run silently fall back to
    // the host-DB tenant id, which then propagated into every menu /
    // seeded row insert and tripped FK violations on docker DB.
    const backendUrl =
      BACKEND_URL;
    const out = execSync(
      `curl -s -X POST ${backendUrl}/api/auth/login -H 'Content-Type: application/json' ` +
        `-d '{"email":"admin@auraboot.com","password":"Test2026x"}'`,
    ).toString();
    const parsed = JSON.parse(out);
    const token = parsed?.data?.jwt;
    if (!token) throw new Error('no jwt in login response');
    const payload = token.split('.')[1];
    const pad = '='.repeat((4 - (payload.length % 4)) % 4);
    // JWT tenantId is a 19-digit snowflake — JS Number precision drops the
    // last 2 digits. Parse the raw claim string with a regex to preserve
    // exact bytes.
    const rawClaim = Buffer.from(payload + pad, 'base64').toString('utf-8');
    const m = rawClaim.match(/"tenantId"\s*:\s*(\d+)/);
    if (!m) throw new Error('no tenantId in claim');
    return m[1];
  } catch (e) {
    // Fallback to the historical hard-coded value so tests don't all break
    // if the backend is down during helper-module load.
    return '303848950530707456';
  }
}

export const ADMIN_TENANT_ID = resolveAdminTenantId();

// Parent menu id of "AI 中心" — resolve from DB at load time (rotates on
// DB reset like tenant id). Look up via children's parent_id to avoid
// CJK literal quoting issues in the subprocess.
function resolveAiCenterMenuId(): string {
  try {
    const sql = `SELECT DISTINCT parent_id FROM ab_menu WHERE tenant_id = ${ADMIN_TENANT_ID} AND path LIKE '/aurabot/%' AND parent_id IS NOT NULL LIMIT 1;`;
    const id = psql(sql);
    return id || '303848987541245952';
  } catch (e) {
    return '303848987541245952';
  }
}

// ---------------------------------------------------------------------------
// SQL helpers — must be env-aware. Hardcoded `-h localhost -U ghj -d aura_boot`
// works for host-mode dev (postgres on :5432) but fails on isolated docker
// stacks (auraboot-ga-e2e on :5433 user auraboot, auraboot-r2 on :5434, etc.).
// Read PGHOST/PGPORT/PGUSER/PGDATABASE env (and AuraBoot PG_* aliases) so the
// same helpers work across stacks. Defaults remain the host-dev shape so
// historic local runs keep working without env setup.
//
// Use node-postgres through a tiny synchronous Node subprocess instead of the
// `psql` CLI. The Playwright frontend container intentionally has Node
// dependencies but no shell database client.
// Canonical pattern documented in
// `auraboot-enterprise/.../feedback_psql_helpers_must_be_env_aware.md`.
// ---------------------------------------------------------------------------

const PG_HOST = process.env.PGHOST || process.env.PG_HOST || 'localhost';
const PG_PORT = process.env.PGPORT || process.env.PG_PORT || '5432';
const PG_USER = process.env.PGUSER || process.env.PG_USER || 'ghj';
const PG_DB = process.env.PGDATABASE || process.env.PG_DB || 'aura_boot';
// PGPASSWORD is the standard libpq env — pass it through if set so docker
// stacks with non-trust auth can connect. Don't override if the operator
// has already set it.
const PG_ENV = {
  ...process.env,
  PGHOST: PG_HOST,
  PGPORT: PG_PORT,
  PGUSER: PG_USER,
  PGDATABASE: PG_DB,
  PGPASSWORD: process.env.PGPASSWORD || process.env.PG_PASSWORD || '',
};

export const AI_CENTER_MENU_ID = resolveAiCenterMenuId();

function psql(sql: string): string {
  return runPgSync(sql);
}

/**
 * Kept as a semantic alias for call sites that only need SELECT rows from a
 * multi-statement transactional batch.
 */
function psqlQuiet(sql: string): string {
  return runPgSync(sql);
}

function runPgSync(sql: string): string {
  const runner = `
const { Client } = require('pg');

let sql = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => { sql += chunk; });
process.stdin.on('end', async () => {
  const client = new Client({
    host: process.env.PGHOST || 'localhost',
    port: Number(process.env.PGPORT || '5432'),
    user: process.env.PGUSER || 'ghj',
    database: process.env.PGDATABASE || 'aura_boot',
    password: process.env.PGPASSWORD || undefined,
  });
  try {
    await client.connect();
    const result = await client.query(sql);
    const rows = Array.isArray(result)
      ? result.flatMap(item => item.rows || [])
      : (result.rows || []);
    const out = rows
      .map(row => Object.values(row).map(value => value == null ? '' : String(value)).join('|'))
      .join('\\n');
    process.stdout.write(out);
  } catch (error) {
    console.error(error && error.stack ? error.stack : String(error));
    process.exitCode = 1;
  } finally {
    await client.end().catch(() => {});
  }
});
`;
  return execFileSync(process.execPath, ['-e', runner], {
    input: sql,
    encoding: 'utf8',
    env: PG_ENV,
  }).trim();
}

function randomPid(prefix: string): string {
  const rand = Math.random().toString(36).slice(2, 10).toUpperCase();
  const ts = Date.now().toString(36).toUpperCase();
  const raw = `${prefix}${ts}${rand}`;
  // ab_agent_*.pid columns are VARCHAR(26); truncate / pad deterministically.
  return (raw + 'XXXXXXXXXXXXXXXXXXXXXXXXXX').slice(0, 26);
}

// ---------------------------------------------------------------------------
// Draft seeding
// ---------------------------------------------------------------------------

export interface SeededDraft {
  pid: string;
  draftSkillCode: string;
}

/**
 * Insert a SkillDraft row. Returns its pid.
 *
 * Status must match the chk_skill_draft_status constraint:
 * DRAFT_PENDING_REVIEW / REVIEWED_OK / REVIEWED_REJECTED /
 * SHADOW_RUNNING / PROMOTED_PENDING_HUMAN / ACTIVE / DISCARDED.
 */
export function seedDraft(
  status: string,
  overrides: { draftSkillCode?: string; contractYaml?: string } = {},
): SeededDraft {
  const pid = randomPid('E2ELD');
  const draftSkillCode = overrides.draftSkillCode ?? `auto.e2e.${Date.now()}`;
  const contractYaml =
    overrides.contractYaml ??
    `substrate: dsl\nskill_code: ${draftSkillCode}\naction_type: query\ntool_refs:\n  - nq_leads\n`;
  // PostgreSQL dollar-quote side-steps newline escape headaches.
  const sql = `
    INSERT INTO ab_agent_skill_draft
      (pid, tenant_id, draft_skill_code, source_pattern_hash, contract_yaml, status, created_at)
    VALUES
      ('${pid}', ${ADMIN_TENANT_ID}, '${draftSkillCode}', 'hash_${pid}', $$${contractYaml}$$, '${status}', NOW());
  `;
  psql(sql);
  return { pid, draftSkillCode };
}

/** Return the current `status` of a draft straight from the DB. */
export function dbDraftStatus(pid: string): string {
  return psql(`SELECT status FROM ab_agent_skill_draft WHERE pid = '${pid}';`);
}

/** Return (status, review_comment) for fuller assertions. */
export function dbDraftRow(
  pid: string,
): { status: string; reviewComment: string | null } {
  const raw = psql(
    `SELECT status || '|' || COALESCE(review_comment, '') FROM ab_agent_skill_draft WHERE pid = '${pid}';`,
  );
  const [status, reviewComment] = raw.split('|');
  return { status, reviewComment: reviewComment || null };
}

/** Delete seeded drafts by pid. Use in afterEach. */
export function cleanupDrafts(pids: string[]): void {
  if (pids.length === 0) return;
  const list = pids.map((p) => `'${p}'`).join(',');
  psql(`DELETE FROM ab_agent_skill_draft WHERE pid IN (${list});`);
}

// ---------------------------------------------------------------------------
// Interrupt-log seeding
// ---------------------------------------------------------------------------

export interface SeededInterrupt {
  pid: string;
  sessionId: string;
}

/**
 * Insert an Interrupt log row. sub_policy must be one of
 * replace_intent / append_context / insert_subtask.
 */
export function seedInterrupt(args: {
  sessionId: string;
  subPolicy: string;
  excerpt: string;
  confidence?: number;
  classifierTier?: string;
}): SeededInterrupt {
  const pid = randomPid('E2EIL');
  const confidence = args.confidence ?? 0.92;
  const classifierTier = args.classifierTier ?? 'keyword';
  const sql = `
    INSERT INTO ab_agent_interrupt_log
      (pid, tenant_id, session_id, active_run_id, new_message_excerpt,
       sub_policy, classifier_tier, confidence, reason, action_taken, created_at)
    VALUES
      ('${pid}', ${ADMIN_TENANT_ID}, '${args.sessionId}', 'RUN${Date.now().toString(36).toUpperCase()}X',
       $$${args.excerpt}$$, '${args.subPolicy}', '${classifierTier}', ${confidence},
       'e2e seeded', 'active_run_cancelled', NOW());
  `;
  psql(sql);
  return { pid, sessionId: args.sessionId };
}

export function cleanupInterrupts(sessionIdPrefix: string): void {
  psql(
    `DELETE FROM ab_agent_interrupt_log WHERE session_id LIKE '${sessionIdPrefix}%';`,
  );
}

// ---------------------------------------------------------------------------
// Menu seeding
// ---------------------------------------------------------------------------

/**
 * Learning Drafts and Interrupts pages live at /aurabot/learning-drafts
 * and /aurabot/interrupts but the bootstrap menu seed does not include
 * them yet (see plugins/core-aurabot/resources.ts — they're registered
 * client-side but not in `ab_menu`). Seed them under "AI 中心" so the
 * sidebar shows the leaves, and clean up afterwards so we don't leak
 * test rows into the shared DB.
 *
 * Returns the seeded menu pids so afterAll can delete them.
 */
export interface SeededMenus {
  learningDraftsMenuId: string;
  interruptsMenuId: string;
  /**
   * Only the menu ids this worker actually INSERTED — not ids discovered
   * to already exist. afterAll should delete only these so a sibling
   * worker that still needs the menu rows isn't left staring at a blank
   * sidebar mid-test.
   */
  ownedIds: string[];
}

interface UpsertResult {
  id: string;
  didInsert: boolean;
}

/**
 * Idempotent: if the menu rows already exist (prior run left them, or a
 * sibling worker inserted first) we simply look up the existing ids
 * instead of colliding on uq_ab_menu_tenant_code. This is NOT the
 * forbidden "ensureXxx() self-heal" pattern — menu rows are test
 * infrastructure, not product data, and we also match the cleanup
 * semantics: only delete rows we (or a previous test run) seeded.
 *
 * The helper always wraps INSERT in a CTE so the stdin-fed psql returns
 * just the id (no "INSERT 0 1" status tag on stdout).
 */
export function seedMissionControlMenus(): SeededMenus {
  const rand = () => Math.random().toString(36).slice(2, 8).toUpperCase();
  const ldPid = `E2EM_LD_${rand()}${rand()}`.slice(0, 26);
  const ilPid = `E2EM_IL_${rand()}${rand()}`.slice(0, 26);
  const ld = upsertMenu({
    pid: ldPid,
    code: 'aurabot.learning-drafts',
    name: '技能草稿',
    path: '/aurabot/learning-drafts',
    order: 35,
  });
  const il = upsertMenu({
    pid: ilPid,
    code: 'aurabot.interrupts',
    name: '中断审计',
    path: '/aurabot/interrupts',
    order: 36,
  });
  const ownedIds: string[] = [];
  if (ld.didInsert) ownedIds.push(ld.id);
  if (il.didInsert) ownedIds.push(il.id);
  return {
    learningDraftsMenuId: ld.id,
    interruptsMenuId: il.id,
    ownedIds,
  };
}

function upsertMenu(args: {
  pid: string;
  code: string;
  name: string;
  path: string;
  order: number;
}): UpsertResult {
  // `code` is intentionally left NULL on insert: the frontend derives
  // `nameKey = menu.${code}` when `code` is present, and that i18n key
  // is never registered → sidebar renders the literal key string, not
  // `name`. By leaving code NULL we fall back to `item.name` (the
  // Chinese label).
  //
  // Racing parallel workers: the CTE-level `WHERE NOT EXISTS` check is
  // NOT atomic across concurrent transactions (TOCTOU) — two workers
  // both pass the existence check before either commits and both
  // insert, leaving duplicate menu rows that cause strict-mode
  // `getByRole('link')` to resolve to 2 elements (observed flake).
  //
  // Fix: serialize the upsert per `path` via a PostgreSQL transactional
  // advisory lock. hashtext(path) produces a stable int used as the
  // lock key; pg_advisory_xact_lock blocks any second worker trying to
  // upsert the same path until the first commits, after which the
  // second worker sees the row in `existing` and short-circuits.
  // PostgreSQL defaults to statement-level auto-commit, so wrap the batch in
  // explicit BEGIN/COMMIT so the lock, check, and insert share one tx.
  const raw = psqlQuiet(
    `BEGIN;
     SELECT pg_advisory_xact_lock(hashtext('ab_menu_upsert:${args.path}')::bigint);
     WITH existing AS (
       SELECT id FROM ab_menu
        WHERE tenant_id = ${ADMIN_TENANT_ID} AND path = '${args.path}' AND deleted_flag = false
        LIMIT 1
     ),
     inserted AS (
       INSERT INTO ab_menu (id, pid, tenant_id, parent_id, code, name, path, type, permission_code, visible, order_no, status)
       SELECT (EXTRACT(EPOCH FROM NOW())*1000 + floor(random()*1000000))::bigint,
              '${args.pid}', ${ADMIN_TENANT_ID}, ${AI_CENTER_MENU_ID},
              NULL, '${args.name}', '${args.path}', 1,
              NULL, true, ${args.order}, 'active'
        WHERE NOT EXISTS (SELECT 1 FROM existing)
       RETURNING id
     )
     SELECT id || '|' || origin FROM (
       SELECT id, 'ins' AS origin FROM inserted
       UNION ALL
       SELECT id, 'exi' AS origin FROM existing
       LIMIT 1
     ) s;
     COMMIT;`,
  );
  // runPgSync emits columns separated by '|'.
  const [id, origin] = raw.split('|');
  return { id, didInsert: origin === 'ins' };
}

export function cleanupMissionControlMenus(_ids: SeededMenus): void {
  // Intentional no-op. Previously deleted "owned" rows per spec file in
  // afterAll, but with Playwright's file-level parallelism (workers=4,
  // fullyParallel=false) two spec files that share a menu path (e.g.
  // ai-learning-drafts-real + ai-interrupts-real) race to seed the same
  // row; whichever file finishes first deletes the row while the other
  // is still running, blanking its sidebar mid-test and causing flakes
  // (observed on USP-11 and sibling tests). Menu rows are test infra —
  // safe to leave behind; centralized cleanup runs once in
  // global-teardown.ts against all E2EM_* pids in the admin tenant.
  // Keeping the function signature avoids churning every call site.
}

// ---------------------------------------------------------------------------
// Shadow Run seeding + menu (D.5 Phase 1)
// ---------------------------------------------------------------------------

export interface SeededShadowRunFixture {
  draftPid: string;
  draftSkillCode: string;
  shadowRunPids: string[];
}

/**
 * Seed one Skill Draft + N shadow runs for D.5 Phase 1 E2E.
 *
 * Returns the draft pid + the shadow run pids so afterEach can clean up.
 * Variants follow the controller's KPI math: 1 fidelity match + 1 fidelity
 * miss + 2 output matches by default → fidelity_rate=0.5 / output_rate=1.0.
 */
export function seedShadowRunFixture(): SeededShadowRunFixture {
  const draftPid = randomPid('E2ESHD');
  const draftSkillCode = `auto.shadow.${Date.now()}`;
  // Insert the draft first so the FK to ab_agent_skill_draft satisfies.
  psql(`
    INSERT INTO ab_agent_skill_draft
      (pid, tenant_id, draft_skill_code, source_pattern_hash, contract_yaml, status, created_at)
    VALUES
      ('${draftPid}', ${ADMIN_TENANT_ID}, '${draftSkillCode}', 'h_${draftPid}',
       $$skill_code: ${draftSkillCode}$$, 'SHADOW_RUNNING', NOW());
  `);
  const shadowRunPids: string[] = [];
  // Run 1 — fidelity match + output match. Cost +0.0010 vs prod.
  const r1 = randomPid('E2ESHR');
  shadowRunPids.push(r1);
  psql(`
    INSERT INTO ab_agent_shadow_run
      (pid, tenant_id, draft_id, original_run_id,
       shadow_status, shadow_duration_ms, shadow_cost_usd, shadow_tokens, shadow_output_hash,
       original_status, original_duration_ms, original_cost_usd, original_output_hash,
       output_match, fidelity_match, output_diff, created_at)
    VALUES
      ('${r1}', ${ADMIN_TENANT_ID}, '${draftPid}', '${r1.slice(0, 22)}_OR',
       'success', 1200, 0.0050, 42, 'sh_${r1}',
       'success', 1500, 0.0040, 'or_${r1}',
       TRUE, TRUE, NULL, NOW() - INTERVAL '15 minutes');
  `);
  // Run 2 — fidelity MISS + output match. Output diff present.
  const r2 = randomPid('E2ESHR');
  shadowRunPids.push(r2);
  psql(`
    INSERT INTO ab_agent_shadow_run
      (pid, tenant_id, draft_id, original_run_id,
       shadow_status, shadow_duration_ms, shadow_cost_usd, shadow_tokens, shadow_output_hash,
       original_status, original_duration_ms, original_cost_usd, original_output_hash,
       output_match, fidelity_match, output_diff, created_at)
    VALUES
      ('${r2}', ${ADMIN_TENANT_ID}, '${draftPid}', '${r2.slice(0, 22)}_OR',
       'success', 1100, 0.0030, 35, 'sh_${r2}',
       'success', 1300, 0.0040, 'or_${r2}',
       TRUE, FALSE,
       $$[{"path":"/items/0/score","shadow":0.81,"production":0.83}]$$::jsonb,
       NOW() - INTERVAL '5 minutes');
  `);
  return { draftPid, draftSkillCode, shadowRunPids };
}

export function cleanupShadowRunFixture(fixture: SeededShadowRunFixture): void {
  if (!fixture) return;
  // CASCADE delete on draft would clean shadow runs, but be explicit.
  psql(
    `DELETE FROM ab_agent_shadow_run WHERE pid IN (${fixture.shadowRunPids
      .map((p) => `'${p}'`)
      .join(',')});`,
  );
  psql(
    `DELETE FROM ab_agent_skill_draft WHERE pid = '${fixture.draftPid}';`,
  );
}

export interface SeededShadowRunsMenu {
  menuId: string;
  ownedIds: string[];
}

/**
 * Seed the "Shadow Runs" leaf under AI 中心 so the sidebar shows it. The
 * page itself sits at /admin/agent-runs/shadow-runs (not /aurabot/*) so we
 * pick a unique path for the menu row that matches the React route.
 */
export function seedShadowRunsMenu(): SeededShadowRunsMenu {
  const rand = () => Math.random().toString(36).slice(2, 8).toUpperCase();
  const pid = `E2EM_SH_${rand()}${rand()}`.slice(0, 26);
  const r = upsertMenu({
    pid,
    code: 'aurabot.admin.shadow-runs',
    name: '影子运行比对',
    path: '/admin/agent-runs/shadow-runs',
    order: 110,
  });
  return { menuId: r.id, ownedIds: r.didInsert ? [r.id] : [] };
}

export function cleanupShadowRunsMenu(_menu: SeededShadowRunsMenu): void {
  // No-op: centralized teardown sweeps E2EM_* menu pids.
}

// ---------------------------------------------------------------------------
// Memory Promotion seeding (PR-69 Phase 5)
// ---------------------------------------------------------------------------

export interface SeededPromotion {
  pid: string;
  /** Optional source memory pid — created when the seed populates one. */
  sourceMemoryPid?: string;
  /** Optional promoted memory pid — set when status = PROMOTED_SHADOW. */
  promotedMemoryPid?: string;
}

const PROMO_PREFIX = 'E2EMP';
const MEM_PREFIX = 'E2EMM';

/**
 * Insert a DRAFT_PENDING_REVIEW promotion row with sensible defaults.
 *
 * Ensures the row satisfies chk_memory_promotion_scope (source=user,
 * target=tenant for reason_code ∈ cross_user_agreement / implicit_co_sign /
 * importance_spike; source=session, target=user for session_upgrade).
 */
export function seedMemoryPromotion(
  status: string,
  confidence: number = 0.85,
  reasonCode: string = 'cross_user_agreement',
): string {
  const pid = randomPid(PROMO_PREFIX);
  const { source, target } =
    reasonCode === 'session_upgrade'
      ? { source: 'session', target: 'user' }
      : { source: 'user', target: 'tenant' };
  const sql = `
    INSERT INTO ab_agent_memory_promotion
      (pid, tenant_id, source_scope, target_scope, category,
       proposed_title, proposed_content, proposed_importance,
       reason_code, reason_detail, confidence_score, similarity_score,
       status, created_at)
    VALUES
      ('${pid}', ${ADMIN_TENANT_ID}, '${source}', '${target}', 'operations',
       'E2E test promotion ${pid}',
       $$This is a test memory proposal for E2E validation ${pid}.$$, 7,
       '${reasonCode}',
       '{"agreement_count":3,"user_ids":[1,2,3]}'::jsonb,
       ${confidence}, 0.87,
       '${status}', NOW());
  `;
  psql(sql);
  return pid;
}

/**
 * Seed a PROMOTED_SHADOW row with a real back-linked ab_agent_memory
 * tenant-scope row so the retract path has something to soft-delete.
 */
export function seedMemoryPromotionWithPromotedMemory(): SeededPromotion {
  const promotionPid = randomPid(PROMO_PREFIX);
  const memoryPid = randomPid(MEM_PREFIX);
  const now = 'NOW()';
  const shadowEnds = `NOW() + INTERVAL '7 days'`;

  // 1) Insert the ab_agent_memory row first (FK target)
  psql(`
    INSERT INTO ab_agent_memory
      (pid, tenant_id, memory_agent_id, memory_type, category,
       memory_title, memory_content, importance, shareable,
       scope, scope_key, shadow_mode, promoted_from_pid,
       created_at, updated_at)
    VALUES
      ('${memoryPid}', ${ADMIN_TENANT_ID}, 'default', 'tenant_shared', 'operations',
       'Shadow memory ${memoryPid}',
       $$Shadow-mode memory content for retract test ${memoryPid}$$,
       7, TRUE,
       'tenant', NULL, TRUE, '${promotionPid}',
       ${now}, ${now});
  `);

  // 2) Insert the promotion row linking back to it
  psql(`
    INSERT INTO ab_agent_memory_promotion
      (pid, tenant_id, source_scope, target_scope, category,
       proposed_title, proposed_content, proposed_importance,
       reason_code, reason_detail, confidence_score, similarity_score,
       status, promoted_memory_pid, shadow_started_at, shadow_ends_at,
       reviewed_at, created_at)
    VALUES
      ('${promotionPid}', ${ADMIN_TENANT_ID}, 'user', 'tenant', 'operations',
       'Shadow memory ${memoryPid}',
       $$Shadow-mode memory content for retract test ${memoryPid}$$, 7,
       'cross_user_agreement',
       '{"agreement_count":3,"user_ids":[1,2,3]}'::jsonb,
       0.88, 0.90,
       'PROMOTED_SHADOW', '${memoryPid}',
       ${now}, ${shadowEnds},
       ${now}, ${now});
  `);

  return { pid: promotionPid, promotedMemoryPid: memoryPid };
}

/** Return the current promotion.status straight from the DB. */
export function dbPromotionStatus(pid: string): string {
  return psql(
    `SELECT status FROM ab_agent_memory_promotion WHERE pid = '${pid}';`,
  );
}

export interface PromotionRow {
  status: string;
  rejectReason: string | null;
  reviewComment: string | null;
  promotedMemoryPid: string | null;
}

export function dbPromotionRow(pid: string): PromotionRow {
  const raw = psql(
    `SELECT status || '|' || COALESCE(reject_reason, '') || '|' ||
            COALESCE(review_comment, '') || '|' ||
            COALESCE(promoted_memory_pid, '')
     FROM ab_agent_memory_promotion WHERE pid = '${pid}';`,
  );
  const [status, rejectReason, reviewComment, promotedMemoryPid] = raw.split('|');
  return {
    status,
    rejectReason: rejectReason || null,
    reviewComment: reviewComment || null,
    promotedMemoryPid: promotedMemoryPid || null,
  };
}

/** Whether ab_agent_memory row is soft-deleted (deleted_flag = TRUE). */
export function dbMemoryIsDeleted(memoryPid: string): boolean {
  const raw = psql(
    `SELECT deleted_flag FROM ab_agent_memory WHERE pid = '${memoryPid}';`,
  );
  return raw === 't' || raw === 'true';
}

/** Delete seeded promotions + their back-linked memories by pid prefix. */
export function cleanupPromotions(pidPrefix: string): void {
  // Memories FIRST (promotion.promoted_memory_pid has no FK, but
  // ab_agent_memory.promoted_from_pid → promotion.pid is informational only).
  psql(
    `DELETE FROM ab_agent_memory
       WHERE tenant_id = ${ADMIN_TENANT_ID} AND pid LIKE '${pidPrefix}%';`,
  );
  psql(
    `DELETE FROM ab_agent_memory_promotion
       WHERE tenant_id = ${ADMIN_TENANT_ID} AND pid LIKE '${pidPrefix}%';`,
  );
  // Also clean any memory rows linked to our seeded promotions (via
  // promoted_memory_pid) that used a different prefix (MEM_PREFIX).
  psql(
    `DELETE FROM ab_agent_memory
       WHERE tenant_id = ${ADMIN_TENANT_ID} AND pid LIKE '${MEM_PREFIX}%';`,
  );
}

/** Prefix used by seedMemoryPromotion* — exported for cleanupPromotions callers. */
export const MEMORY_PROMOTION_PID_PREFIX = PROMO_PREFIX;

// ---------------------------------------------------------------------------
// Memory Promotion menu seeding
// ---------------------------------------------------------------------------

export interface SeededPromotionMenu {
  menuId: string;
  ownedIds: string[];
}

/**
 * Idempotent: insert "记忆提案" leaf under "AI 中心" at /aurabot/memory-promotions.
 * Mirrors seedMissionControlMenus for the Phase 4 / Phase 5 page.
 */
export function seedMemoryPromotionsMenu(): SeededPromotionMenu {
  const rand = () => Math.random().toString(36).slice(2, 8).toUpperCase();
  const pid = `E2EM_MP_${rand()}${rand()}`.slice(0, 26);
  const r = upsertMenu({
    pid,
    code: 'aurabot.memory-promotions',
    // Match the canonical zh title registered in
    // web-admin/app/plugins/core-aurabot/resources.ts (`记忆晋升`). Specs
    // assert against `/记忆晋升|Memory Promotions?/` so the seeded label
    // must agree.
    name: '记忆晋升',
    path: '/aurabot/memory-promotions',
    order: 37,
  });
  return {
    menuId: r.id,
    ownedIds: r.didInsert ? [r.id] : [],
  };
}

export function cleanupMemoryPromotionsMenu(_menu: SeededPromotionMenu): void {
  // No-op — see rationale on cleanupMissionControlMenus. Centralized
  // teardown in global-teardown.ts deletes all E2EM_* menu rows.
}

// ---------------------------------------------------------------------------
// User Soul Profile seeding (PR-80 Phase 6)
// ---------------------------------------------------------------------------

export const SOUL_PROFILE_PID_PREFIX = 'E2EUSP';

/** Compact profile JSONB that matches §4 shape well enough for UI assertions. */
function defaultProfileJson(userId: string): Record<string, unknown> {
  return {
    schema_version: '1.0',
    persona: {
      text: `E2E persona for user ${userId} — pragmatic engineer.`,
      source_memory_pids: ['M01', 'M02'],
      confidence: 0.82,
    },
    preferences: {
      communication_style: {
        text: 'concise bullet points',
        source_memory_pids: ['M03'],
        confidence: 0.91,
      },
      domain_vocabulary: {
        text: ['SKU', '月结'],
        source_memory_pids: ['M04'],
        confidence: 0.85,
      },
    },
    boundaries: {
      text: 'never send external email without explicit confirm',
      source_memory_pids: ['M05'],
      confidence: 0.95,
    },
    language: 'zh-CN',
  };
}

export interface SeededSoulProfile {
  pid: string;
  userId: string;
}

export function seedUserSoulProfile(args: {
  userId: string;
  status: 'DRAFT' | 'ACTIVE' | 'SUPERSEDED' | 'ARCHIVED';
  version?: number;
  profileJson?: Record<string, unknown>;
  confidence?: number;
  stale?: boolean;
  hidden?: boolean;
  editedFields?: Record<string, unknown>;
}): SeededSoulProfile {
  const pid = randomPid(SOUL_PROFILE_PID_PREFIX);
  const profile = args.profileJson ?? defaultProfileJson(args.userId);
  const profileJsonStr = JSON.stringify(profile).replace(/\$/g, '\\$');
  const editedFieldsJson = args.editedFields
    ? `'${JSON.stringify(args.editedFields).replace(/'/g, "''")}'::jsonb`
    : 'NULL';
  const activatedAt = args.status === 'ACTIVE' ? 'NOW()' : 'NULL';
  psql(`
    INSERT INTO ab_agent_user_soul_profile
      (pid, tenant_id, user_id, version, status,
       profile, profile_hash, language_preference,
       derivation_confidence, edited_fields,
       stale_flagged_at, hidden_at, activated_at, created_at)
    VALUES
      ('${pid}', ${ADMIN_TENANT_ID}, '${args.userId}',
       ${args.version ?? 1}, '${args.status.toLowerCase()}',
       $JSON$${profileJsonStr}$JSON$::jsonb,
       'hash_${pid}', 'zh-CN',
       ${args.confidence ?? 0.82}, ${editedFieldsJson},
       ${args.stale ? 'NOW()' : 'NULL'},
       ${args.hidden ? 'NOW()' : 'NULL'},
       ${activatedAt}, NOW());
  `);
  return { pid, userId: args.userId };
}

export interface UserSoulProfileRow {
  status: string;
  editedFields: string | null;
  hiddenAt: string | null;
  staleFlaggedAt: string | null;
}

export function dbUserSoulProfileRow(pid: string): UserSoulProfileRow {
  const raw = psql(
    `SELECT status || '|' ||
            COALESCE(edited_fields::text, '') || '|' ||
            COALESCE(hidden_at::text, '') || '|' ||
            COALESCE(stale_flagged_at::text, '')
       FROM ab_agent_user_soul_profile WHERE pid = '${pid}';`,
  );
  const [status, editedFields, hiddenAt, staleFlaggedAt] = raw.split('|');
  return {
    status,
    editedFields: editedFields || null,
    hiddenAt: hiddenAt || null,
    staleFlaggedAt: staleFlaggedAt || null,
  };
}

/**
 * Delete seeded profiles within the admin tenant.
 *
 * Matches BOTH:
 *   1. `pid LIKE '<prefix>%'`       — rows inserted by `seedUserSoulProfile`.
 *   2. `user_id LIKE 'e2e_%'`       — rows seeded under e2e_victim_* /
 *      e2e_admin_probe_* users (USP-E2E-08 / USP-E2E-10).
 *   3. Rows in the admin tenant whose `user_id` matches any user_id we
 *      still have a seeded row for, AND whose pid does NOT look like a
 *      seeded pid — covers tombstone rows inserted by the backend's
 *      `UserSoulProfileEditor.forgetProfile` / admin-forget paths. Those
 *      rows use `UniqueIdGenerator.generate()` (ULID), not the
 *      `E2EUSP` prefix, and were leaking across runs.
 *
 * The existing per-pid-prefix contract is preserved so callers that rely
 * on `SOUL_PROFILE_PID_PREFIX` still do the right thing — we just match
 * MORE rows, never fewer.
 */
export function cleanupUserSoulProfiles(pidPrefix: string): void {
  // Collect user_ids that were touched by seeded rows (either still in
  // the table with the seeded pid prefix, or already rewritten to
  // tombstones). For each such user_id in the admin tenant, purge every
  // row — including backend-generated tombstones with non-prefixed pids.
  psql(
    `DELETE FROM ab_agent_user_soul_profile
       WHERE tenant_id = ${ADMIN_TENANT_ID}
         AND (
              pid LIKE '${pidPrefix}%'
           OR user_id LIKE 'e2e_%'
           OR user_id IN (
                SELECT DISTINCT user_id
                  FROM ab_agent_user_soul_profile
                 WHERE tenant_id = ${ADMIN_TENANT_ID}
                   AND pid LIKE '${pidPrefix}%'
              )
         );`,
  );
}

// ---------------------------------------------------------------------------
// Soul Profile menu seeding (my-profile + admin dashboard)
// ---------------------------------------------------------------------------

export interface SeededSoulProfileMenus {
  myProfileMenuId: string;
  adminMenuId: string;
  ownedIds: string[];
}

export function seedSoulProfileMenus(): SeededSoulProfileMenus {
  const rand = () => Math.random().toString(36).slice(2, 8).toUpperCase();
  const mpPid = `E2EM_USP_${rand()}`.slice(0, 26);
  const admPid = `E2EM_UAD_${rand()}`.slice(0, 26);
  const mp = upsertMenu({
    pid: mpPid,
    code: 'aurabot.my-profile',
    name: '我的画像',
    path: '/aurabot/my-profile',
    order: 38,
  });
  const adm = upsertMenu({
    pid: admPid,
    code: 'aurabot.soul-profiles-admin',
    name: 'Soul Profiles (管理)',
    path: '/aurabot/soul-profiles',
    order: 39,
  });
  const ownedIds: string[] = [];
  if (mp.didInsert) ownedIds.push(mp.id);
  if (adm.didInsert) ownedIds.push(adm.id);
  return {
    myProfileMenuId: mp.id,
    adminMenuId: adm.id,
    ownedIds,
  };
}

export function cleanupSoulProfileMenus(_menus: SeededSoulProfileMenus): void {
  // No-op — see rationale on cleanupMissionControlMenus. Two spec files
  // (ai-user-soul-profile.spec.ts mocked + ai-user-soul-profile-real.spec.ts)
  // both seed these same menu rows in beforeAll and ran in parallel
  // workers; the file that finished first deleted the rows while USP-11
  // in the other file was still opening the sidebar → menu leaf missing
  // → 5s waitFor timeout. Centralized teardown in global-teardown.ts
  // deletes all E2EM_* menu rows once all workers complete.
}
