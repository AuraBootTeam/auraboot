/**
 * Real-backend helpers for Learning Loop / Interrupt E2E (PR-64).
 *
 * These tests drive the live Spring Boot backend on :6443 instead of
 * stubbing API routes. They seed rows directly via `psql` (bypassing
 * auth token minting) and verify results by hitting the read endpoints
 * AND cross-checking DB state.
 *
 * Tenant is fixed to the admin's primary tenant (303848950530707456)
 * because the admin JWT resolves to that tenant and every read is scoped
 * by the tenant interceptor.
 */

import { execSync } from 'node:child_process';

// Admin primary tenant — matches the JWT issued by
// `admin@example.com / Test2026x`. Keep in sync with
// scripts/reset-and-init.sh.
export const ADMIN_TENANT_ID = '303848950530707456';

// Parent menu id of "AI 中心" in ab_menu — seeded by
// default-bootstrap. Used so seeded leaf menus show up under the
// correct submenu node.
export const AI_CENTER_MENU_ID = '303848987541245952';

// ---------------------------------------------------------------------------
// psql helpers
// ---------------------------------------------------------------------------

function psql(sql: string): string {
  // Pipe the SQL via stdin so we can ship multi-line templates (contract_yaml
  // needs real \n characters) without worrying about shell quoting. -tA keeps
  // the output aligned-free and tuples-only so the caller can parse it.
  return execSync(
    `psql -h localhost -U ghj -d aura_boot -P pager=off -v ON_ERROR_STOP=1 -tA`,
    { input: sql, stdio: ['pipe', 'pipe', 'pipe'] },
  )
    .toString()
    .trim();
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
  // Racing parallel workers: do a "SELECT ... FOR UPDATE" style existence
  // check via CTE so the second worker sees the first worker's insert
  // and short-circuits. We guard on path because there's no natural
  // unique index on path (intentional — code is nullable).
  const raw = psql(
    `WITH existing AS (
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
     SELECT id, 'ins' AS origin FROM inserted
     UNION ALL
     SELECT id, 'exi' AS origin FROM existing
     LIMIT 1;`,
  );
  // psql -tA emits columns separated by '|'.
  const [id, origin] = raw.split('|');
  return { id, didInsert: origin === 'ins' };
}

export function cleanupMissionControlMenus(ids: SeededMenus): void {
  // Only delete rows this worker OWNS (inserted itself). If a sibling
  // worker's seedMissionControlMenus() found our row first, its
  // ownedIds list won't include those ids and it'll leave them alone.
  if (ids.ownedIds.length === 0) return;
  const list = ids.ownedIds.join(',');
  psql(
    `DELETE FROM ab_menu
      WHERE tenant_id = ${ADMIN_TENANT_ID}
        AND path IN ('/aurabot/learning-drafts', '/aurabot/interrupts')
        AND id IN (${list});`,
  );
}
