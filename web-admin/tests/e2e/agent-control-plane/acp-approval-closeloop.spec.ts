import { test, expect, type Locator, type Page } from '@playwright/test';
import { gotoAcpUiPage } from './route-helpers';
import { findRowByContent } from '../helpers';

/**
 * A5 — L3 agent approval close-loop browser golden (retro item A5).
 *
 * The AI-governance whitepaper §5 promise: a risky (L3/L4) agent action is
 * gated, surfaces as a pending approval, and only executes once a human
 * approves. The backend close-loop already exists end-to-end:
 *
 *   DSL page `agent_approval_list` (/p/agent_approval, backed by the real
 *   `ab_agent_approval` table)
 *     -> rowAction "同意/拒绝"  (action.command = acp:approve_request / acp:reject_request)
 *       -> AgentApprovalCommandHandler  (platform command handler)
 *         -> AgentApprovalGateService.approve/reject  (+ executeApprovedPendingTool)
 *
 * Existing ACP specs cover CRUD/lifecycle/dashboard but NONE drives the
 * approval card -> approve -> status-change loop. This golden closes that gap:
 * it seeds real pending approvals, drives the rowAction through the real
 * command pipeline, and asserts the persisted status flips — i.e. the button
 * is not a no-op (the §2.2 gate-gap check), and the governance gate works.
 *
 * Scope note: a directly-seeded approval has no linked suspended run, so the
 * `executeApprovedPendingTool` leg returns handled=false — that resume-and-run
 * leg is covered by backend integration tests, not this browser golden. What
 * this proves is the UI close-loop: pending row -> approve action -> command ->
 * handler -> gate service -> persisted approval_status transition.
 *
 * Host-first run (zero docker), against a running OSS stack:
 *   cd web-admin && NO_PROXY=localhost \
 *     PLAYWRIGHT_BASE_URL=http://127.0.0.1:<vite> BACKEND_URL=http://127.0.0.1:<be> \
 *     BE_PORT=<be> BFF_PORT=<bff> PW_SKIP_WEBSERVER=1 \
 *     npx playwright test -c playwright.oss.config.ts \
 *       tests/e2e/agent-control-plane/acp-approval-closeloop.spec.ts
 */

const APPROVAL_LIST = '/dynamic/agent-approval';
const uid = `a5${Date.now().toString(36)}`;
const APPROVE_TITLE = `ApproveCase_${uid}`;
const REJECT_TITLE = `RejectCase_${uid}`;

let acpPluginInstalled = true;
const seededPids: Record<string, string> = {};

// --- row-action helpers (mirrors acp-lifecycle-deep.spec.ts conventions) -----

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

/** Confirm a Popconfirm/modal ("确定"/"确认"/OK or data-testid=confirm-ok). */
async function confirmDialog(page: Page): Promise<void> {
  const ok = page
    .locator('[data-testid="confirm-ok"], button:has-text("确定"), button:has-text("确认"), button:has-text("OK")')
    .first();
  await ok.waitFor({ state: 'visible', timeout: 5_000 });
  await ok.click();
}

/** Fetch the persisted approval_status straight from the dynamic entity API. */
async function fetchApprovalStatus(page: Page, pid: string): Promise<string | null> {
  const resp = await page.request.get(`/api/dynamic/agent-approval/${pid}`);
  if (!resp.ok()) return null;
  const body = await resp.json().catch(() => null);
  const data = body?.data ?? body;
  return (data?.approval_status as string) ?? null;
}

// --- seed ---------------------------------------------------------------------

async function seedPendingApproval(page: Page, title: string): Promise<string | null> {
  const resp = await page.request.post('/api/dynamic/agent-approval/create', {
    data: {
      approval_type: 'action',
      approval_title: title,
      description: `A5 close-loop golden ${title}`,
      approval_status: 'pending',
      expires_at: new Date(Date.now() + 86_400_000).toISOString(),
    },
  });
  if (!resp.ok()) return null;
  const body = await resp.json().catch(() => null);
  const data = body?.data ?? body;
  return (data?.pid as string) ?? (data?.recordId as string) ?? null;
}

test.describe('A5: Agent approval close-loop (golden)', () => {
  test.describe.configure({ mode: 'serial', timeout: 120_000 });
  test.setTimeout(90_000);

  test.beforeAll(async ({ browser }) => {
    test.setTimeout(120_000);
    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();
    try {
      // Plugin-availability probe: if the agent-approval model is not imported,
      // the dynamic create endpoint 404s — that is an environment gap, skip.
      const approvePid = await seedPendingApproval(page, APPROVE_TITLE);
      if (!approvePid) {
        acpPluginInstalled = false;
        return;
      }
      seededPids.approve = approvePid;
      const rejectPid = await seedPendingApproval(page, REJECT_TITLE);
      expect(rejectPid, 'second pending approval must seed once the model is reachable').toBeTruthy();
      seededPids.reject = rejectPid as string;
    } finally {
      await ctx.close();
    }
  });

  test('ACP-APPROVAL-01: pending approval is visible with an Approve action', async ({ page }) => {
    test.skip(!acpPluginInstalled, 'agent-control-plane plugin not installed — environment gap');
    await gotoAcpUiPage(page, APPROVAL_LIST);

    const row = await findRowByContent(page, APPROVE_TITLE);
    await expect(row).toBeVisible({ timeout: 15_000 });
    // status tag still pending, and the gated action is offered (visibleWhen pending)
    await expect(row).toContainText(/pending|待审批|待处理/i);
    await expect(row.locator('[data-testid="row-action-approve"]')).toBeVisible({ timeout: 10_000 });
  });

  test('ACP-APPROVAL-02: Approve drives the real command pipeline and flips status', async ({ page }) => {
    test.skip(!acpPluginInstalled, 'agent-control-plane plugin not installed — environment gap');
    await gotoAcpUiPage(page, APPROVAL_LIST);
    const row = await findRowByContent(page, APPROVE_TITLE);
    await expect(row).toBeVisible({ timeout: 15_000 });

    // Click 同意 -> acp:approve_request must hit the command pipeline (200).
    const execResp = page.waitForResponse(
      (r) => r.url().includes('/commands/execute/') && r.status() === 200,
      { timeout: 20_000 },
    );
    await clickRowActionBtn(page, row, 'approve');
    await confirmDialog(page);
    const resolved = await execResp;
    expect(resolved.url()).toContain('acp:approve_request');

    // Persisted truth: status flipped pending -> approved through the gate service.
    await expect
      .poll(() => fetchApprovalStatus(page, seededPids.approve), { timeout: 15_000 })
      .toBe('approved');

    // UI gate-gap proof: the Approve action is no longer offered (visibleWhen pending).
    await gotoAcpUiPage(page, APPROVAL_LIST);
    const after = await findRowByContent(page, APPROVE_TITLE);
    await expect(after).toBeVisible({ timeout: 15_000 });
    await expect(after.locator('[data-testid="row-action-approve"]')).toHaveCount(0);
  });

  test('ACP-APPROVAL-03: Reject with a reason flips status to rejected', async ({ page }) => {
    test.skip(!acpPluginInstalled, 'agent-control-plane plugin not installed — environment gap');
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

    await expect
      .poll(() => fetchApprovalStatus(page, seededPids.reject), { timeout: 15_000 })
      .toBe('rejected');
  });
});
