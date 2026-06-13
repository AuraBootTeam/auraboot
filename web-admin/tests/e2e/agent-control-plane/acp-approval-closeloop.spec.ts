import { test, expect, type Locator, type Page } from '@playwright/test';
import { Client } from 'pg';
import { BACKEND_URL, PG_CONN } from '../../helpers/environments';
import { gotoAcpUiPage } from './route-helpers';
import { findRowByContent } from '../helpers';

/**
 * A5 — L3 agent approval close-loop browser golden (retro item A5).
 *
 * The AI-governance whitepaper §5 promise: a risky (L3/L4) agent action is
 * gated, surfaces as a pending approval, and only executes once a human
 * approves. The backend close-loop already exists end to end:
 *
 *   DSL page `agent_approval_list` (/p/agent_approval, backed by the real
 *   `ab_agent_approval` table)
 *     -> rowAction 同意/拒绝  (action.command = acp:approve_request / acp:reject_request)
 *       -> AgentApprovalCommandHandler  (platform command handler)
 *         -> AgentApprovalGateService.approve/reject  (fail-secure, policy-driven)
 *
 * Existing ACP specs cover CRUD/lifecycle/dashboard but NONE drives the
 * approval card -> approve/reject -> persisted-status-change loop. This golden
 * closes that gap and is the §2.2 gate-gap proof that the rowAction buttons are
 * not no-ops.
 *
 * SEED — discovered constraints (verified 2026-06-11 against a live stack):
 *   1. `ab_agent_approval` is created by the agent runtime, NOT by users — the
 *      user-facing `POST /api/dynamic/agent-approval/create` is 403 (admin lacks
 *      `model.agent_approval.create`, by design). So we seed via SQL, mirroring
 *      what the runtime writes.
 *   2. `AgentApprovalGateService.isAuthorizedApprover` is fail-secure: the
 *      approval must reference an `ab_approval_policy` whose `approver_rules`
 *      authorize the caller. We seed a policy with a `{type:user}` rule for the
 *      admin so the approve/reject actually pass authorization.
 *
 * Note: a directly-seeded approval has no linked suspended run, so the
 * `executeApprovedPendingTool` resume leg returns handled=false (covered by
 * backend integration tests, not this browser golden). What this proves is the
 * UI close-loop: pending row -> rowAction -> command pipeline -> handler ->
 * gate service -> persisted approval_status transition.
 *
 * The same close-loop was verified at the command-pipeline level on 2026-06-11
 * (POST /api/meta/commands/execute/acp:approve_request|reject_request flips
 * ab_agent_approval.approval_status pending -> approved/rejected). This spec
 * adds the browser-DOM-click leg; run it host-first against a running OSS stack:
 *
 *   cd web-admin && NO_PROXY=localhost \
 *     PLAYWRIGHT_BASE_URL=http://127.0.0.1:<vite> BACKEND_URL=http://127.0.0.1:<be> \
 *     BE_PORT=<be> BFF_PORT=<bff> PW_SKIP_WEBSERVER=1 \
 *     PG_HOST=127.0.0.1 PG_PORT=5432 PG_USER=<u> PG_DB=<db> PGPASSWORD=<p> \
 *     npx playwright test -c playwright.oss.config.ts \
 *       tests/e2e/agent-control-plane/acp-approval-closeloop.spec.ts
 */

const APPROVAL_LIST = '/dynamic/agent-approval';
const uid = `a5${Date.now().toString(36)}`;
const APPROVE_TITLE = `ApproveCase_${uid}`;
const REJECT_TITLE = `RejectCase_${uid}`;
const POLICY_PID = `A5POL${uid}`.slice(0, 26).padEnd(26, '0');
const APPROVE_PID = `A5APV${uid}`.slice(0, 26).padEnd(26, '0');
const REJECT_PID = `A5REJ${uid}`.slice(0, 26).padEnd(26, '0');

let acpReady = true;

// --- row-action helpers (mirror acp-lifecycle-deep.spec.ts conventions) -------

async function ensureRowDropdownOpen(page: Page, row: Locator): Promise<boolean> {
  const existing = page.locator('[data-testid="row-action-dropdown"]');
  if (await existing.first().isVisible({ timeout: 250 }).catch(() => false)) return true;
  const moreBtn = row.locator('[data-testid="row-action-more"]').first();
  if (!(await moreBtn.isVisible({ timeout: 1_000 }).catch(() => false))) return false;
  await row.hover().catch(() => {});
  await moreBtn.click();
  await existing.first().waitFor({ state: 'visible', timeout: 5_000 }).catch(() => {});
  return true;
}

async function clickRowActionBtn(page: Page, row: Locator, btnCode: string): Promise<void> {
  const btn = row.locator(`[data-testid="row-action-${btnCode}"]`);
  if (await btn.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await btn.click();
    return;
  }
  const opened = await ensureRowDropdownOpen(page, row);
  if (opened) {
    const dropdownBtn = page
      .locator('[data-testid="row-action-dropdown"]')
      .locator(`[data-testid="row-action-${btnCode}"]`)
      .first();
    await dropdownBtn.waitFor({ state: 'visible', timeout: 5_000 });
    await dropdownBtn.click();
    return;
  }
  await btn.waitFor({ state: 'visible', timeout: 5_000 });
  await btn.click();
}

async function confirmDialog(page: Page): Promise<void> {
  const ok = page
    .locator('[data-testid="confirm-ok"], button:has-text("确定"), button:has-text("确认"), button:has-text("OK")')
    .first();
  await ok.waitFor({ state: 'visible', timeout: 5_000 });
  await ok.click();
}

async function statusOf(pid: string): Promise<string | null> {
  const client = new Client(PG_CONN);
  await client.connect();
  try {
    const r = await client.query('SELECT approval_status FROM ab_agent_approval WHERE pid = $1', [pid]);
    return r.rows[0]?.approval_status ?? null;
  } finally {
    await client.end();
  }
}

test.describe('A5: Agent approval close-loop (golden)', () => {
  test.describe.configure({ mode: 'serial', timeout: 120_000 });
  test.setTimeout(90_000);

  test.beforeAll(async () => {
    const client = new Client(PG_CONN);
    await client.connect();
    try {
      const admin = await client.query(`SELECT id FROM ab_user WHERE email = 'admin@auraboot.com' LIMIT 1`);
      if (admin.rows.length === 0) {
        acpReady = false;
        return;
      }
      const adminId: string = admin.rows[0].id;
      // Seed into the admin's *login* tenant (the tenant the UI/command run in),
      // resolved from the login JWT — NOT the lowest ab_tenant_member row, which
      // is the system tenant and is not what the approval list/page shows.
      const loginResp = await fetch(`${BACKEND_URL}/api/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: 'admin@auraboot.com', password: 'Test2026x' }),
      });
      const loginBody = await loginResp.json().catch(() => null);
      const jwt: string | undefined = loginBody?.data?.jwt;
      const model = await client.query(`SELECT 1 FROM ab_meta_model WHERE code = 'agent_approval' LIMIT 1`);
      if (!jwt || model.rows.length === 0) {
        acpReady = false; // not logged in / agent-control-plane not imported — environment gap
        return;
      }
      // Extract tenantId from the JWT payload as a STRING via regex — tenant ids
      // exceed 2^53, so JSON.parse would silently lose precision (…104 -> …100)
      // and the seed would land in the wrong tenant (row never shows in the UI).
      const payloadJson = Buffer.from(jwt.split('.')[1], 'base64url').toString();
      const tenantMatch = payloadJson.match(/"tenantId"\s*:\s*"?(\d+)"?/);
      if (!tenantMatch) {
        acpReady = false;
        return;
      }
      const tenantId: string = tenantMatch[1];

      // Policy authorizing the admin as approver (fail-secure requirement).
      // adminId is an ab_user.id snowflake (> 2^53). Number(adminId) would silently
      // lose precision (…880 -> …900), so the seeded approver_rules.userId would NOT
      // equal the real Long userId in AgentApprovalGateService.evaluateApproverRules
      // (userId.equals(toLong(ruleUserId))) -> "not authorized" and the approve
      // command never returns 200. Embed adminId (a digit string from PG) directly as
      // a full-precision JSON number — same precision discipline already applied to
      // tenantId above (extracted as string), which the original missed for userId.
      await client.query(
        `INSERT INTO ab_approval_policy (pid, tenant_id, policy_name, approver_rules, deleted_flag, created_at)
         VALUES ($1, $2, 'A5 Golden Policy', $3::jsonb, FALSE, now())
         ON CONFLICT (pid) DO UPDATE SET approver_rules = EXCLUDED.approver_rules`,
        [POLICY_PID, tenantId, `[{"type":"user","userId":${adminId}}]`],
      );
      // Two pending approvals (as the runtime would create), linked to the policy.
      for (const [pid, title] of [[APPROVE_PID, APPROVE_TITLE], [REJECT_PID, REJECT_TITLE]] as const) {
        await client.query(
          `INSERT INTO ab_agent_approval (pid, tenant_id, approval_type, approval_title, approval_status, policy_id, created_at)
           VALUES ($1, $2, 'action', $3, 'pending', $4, now())
           ON CONFLICT (pid) DO UPDATE SET approval_status = 'pending', approver_id = NULL, policy_id = EXCLUDED.policy_id`,
          [pid, tenantId, title, POLICY_PID],
        );
      }
    } finally {
      await client.end();
    }
  });

  test('ACP-APPROVAL-01: pending approval visible with an Approve action', async ({ page }) => {
    test.skip(!acpReady, 'agent-control-plane not imported / no admin tenant — environment gap');
    await gotoAcpUiPage(page, APPROVAL_LIST);
    const row = await findRowByContent(page, APPROVE_TITLE);
    await expect(row).toBeVisible({ timeout: 15_000 });
    await expect(row).toContainText(/pending|待审批|待处理/i);
    await expect(row.locator('[data-testid="row-action-approve"]')).toBeVisible({ timeout: 10_000 });
  });

  test('ACP-APPROVAL-02: Approve drives the real command pipeline and flips status', async ({ page }) => {
    test.skip(!acpReady, 'environment gap');
    await gotoAcpUiPage(page, APPROVAL_LIST);
    const row = await findRowByContent(page, APPROVE_TITLE);
    await expect(row).toBeVisible({ timeout: 15_000 });

    const execResp = page.waitForResponse(
      (r) => r.url().includes('/commands/execute/') && r.status() === 200,
      { timeout: 20_000 },
    );
    await clickRowActionBtn(page, row, 'approve');
    await confirmDialog(page);
    const resolved = await execResp;
    expect(resolved.url()).toContain('acp:approve_request');

    await expect.poll(() => statusOf(APPROVE_PID), { timeout: 15_000 }).toBe('approved');

    // gate-gap proof: the Approve action is no longer offered once approved.
    await gotoAcpUiPage(page, APPROVAL_LIST);
    const after = await findRowByContent(page, APPROVE_TITLE);
    await expect(after).toBeVisible({ timeout: 15_000 });
    await expect(after.locator('[data-testid="row-action-approve"]')).toHaveCount(0);
  });

  test('ACP-APPROVAL-03: Reject with a reason flips status to rejected', async ({ page }) => {
    test.skip(!acpReady, 'environment gap');
    await gotoAcpUiPage(page, APPROVAL_LIST);
    const row = await findRowByContent(page, REJECT_TITLE);
    await expect(row).toBeVisible({ timeout: 15_000 });

    const execResp = page.waitForResponse(
      (r) => r.url().includes('/commands/execute/') && r.status() === 200,
      { timeout: 20_000 },
    );
    await clickRowActionBtn(page, row, 'reject');
    // acp:reject_request declares inputFields:[rejection_reason] -> a form dialog.
    const reasonField = page.locator(
      '[data-testid="form-field-rejection_reason"] textarea, [data-testid="form-field-rejection_reason"] input, textarea[id*="rejection_reason"]',
    ).first();
    if (await reasonField.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await reasonField.fill('Not authorized by reviewer (A5 golden)');
      await page.locator('[data-testid="form-btn-submit"], button:has-text("确定"), button:has-text("提交")').first().click();
    } else {
      await confirmDialog(page);
    }
    const resolved = await execResp;
    expect(resolved.url()).toContain('acp:reject_request');

    await expect.poll(() => statusOf(REJECT_PID), { timeout: 15_000 }).toBe('rejected');
  });
});
