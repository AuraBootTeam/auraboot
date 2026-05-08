/**
 * Cross-Tenant Grants admin page E2E (MERGED-P1-#12).
 *
 * Covers the operator surface backed by CrossTenantGrantController
 * (gated by AdminRoleInterceptor + per-handler platform_admin guard):
 *   GET    /api/admin/cross-tenant-grants
 *   POST   /api/admin/cross-tenant-grants
 *   DELETE /api/admin/cross-tenant-grants/{id}
 *   GET    /api/admin/cross-tenant-grants/{id}/audit
 *
 * Source page: app/plugins/core-platform/pages/CrossTenantGrantsPage.tsx
 * Route:       /admin/cross-tenant-grants  (resources.ts: platform.cross-tenant-grants)
 *
 * Test cases:
 *   CTG-001: Smoke — page renders heading + create CTA (loading -> data|empty)
 *   CTG-002: Empty state OR data-table — at least one stable state is shown
 *   CTG-003: Create grant via UI form — happy path; new row appears
 *   CTG-004: Form validation — empty submit blocked by HTML5 required
 *   CTG-005: Revoke grant via row action — revoke confirm flow; row badge flips
 *   CTG-006: Audit drawer opens for an existing grant
 *
 * Selector strategy:
 *   - Prefer data-testid (page exposes a stable testid surface)
 *   - getByRole('heading') for the static page title (not localised by testid)
 *   - Avoid text-based selectors for dynamic copy to dodge i18n drift
 *
 * No PUT-API fallback, no retries:N, no sleeps. Verification uses expect().toBeVisible
 * with explicit timeouts. Each test owns its own seed via the public POST API
 * (admin storageState carries the platform_admin session) and cleans up via DELETE.
 *
 * @since MERGED-P1-#12
 */

import { test, expect } from '../../fixtures';
import type { APIRequestContext, Page } from '@playwright/test';

const ROUTE = '/admin/cross-tenant-grants';
const API_BASE = '/api/admin/cross-tenant-grants';

// Tenant IDs are arbitrary positive integers — the controller does not enforce
// tenant existence (it stores the FK loosely) but uses (parent, child) as a
// uniqueness hint via grant_type. We pick high numbers to dodge any seeded
// tenant fixture that might already hold a grant for tenants 1/2.
function genTenantPair(): { parent: number; child: number } {
  // Stay within INT range; collisions across parallel runs are unlikely
  // because workers get their own offset via process pid.
  const base = 900_000 + (process.pid % 1000) * 1000 + Math.floor(Math.random() * 1000);
  return { parent: base, child: base + 1 };
}

async function apiCreateGrant(
  request: APIRequestContext,
  body: { parentTenantId: number; childTenantId: number; note?: string },
): Promise<number | null> {
  const resp = await request.post(API_BASE, { data: body });
  if (!resp.ok()) return null;
  const json = await resp.json().catch(() => null);
  // Controller returns ApiResponse<Map<String, Object>> — id lives under data.id
  const id = json?.data?.id;
  return typeof id === 'number' ? id : null;
}

async function apiRevoke(request: APIRequestContext, id: number): Promise<void> {
  await request.delete(`${API_BASE}/${id}`).catch(() => undefined);
}

async function gotoPage(page: Page): Promise<void> {
  await page.goto(ROUTE, { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('cross-tenant-grants-page')).toBeVisible({ timeout: 15_000 });
  // Loading -> table | empty | error must resolve
  const settled = page.locator(
    '[data-testid="grants-table"], [data-testid="grants-empty"], [data-testid="grants-error-banner"]',
  );
  await expect(settled.first()).toBeVisible({ timeout: 15_000 });
}

test.describe.serial('Cross-Tenant Grants admin page', () => {
  // Track ids created via the UI/API so we can clean up even if a test mid-fails.
  const createdIds: number[] = [];

  test.afterAll(async ({ browser }) => {
    if (createdIds.length === 0) return;
    const ctx = await browser.newContext({
      storageState: './tests/storage/admin.json',
    });
    for (const id of createdIds) {
      await ctx.request.delete(`${API_BASE}/${id}`).catch(() => undefined);
    }
    await ctx.close();
  });

  test('CTG-001: smoke — heading + create CTA render', async ({ page }) => {
    await gotoPage(page);
    await expect(page.getByTestId('grant-create-button')).toBeVisible();
    // Heading text is bilingual; assert by role + accessible name pattern.
    await expect(
      page.getByRole('heading', { name: /Cross-Tenant Sub-Agent Grants|跨租户子代理授权/ }),
    ).toBeVisible();
  });

  test('CTG-002: stable terminal state — table or empty visible', async ({ page }) => {
    await gotoPage(page);
    const table = page.getByTestId('grants-table');
    const empty = page.getByTestId('grants-empty');

    const tableCount = await table.count();
    const emptyCount = await empty.count();
    expect(
      tableCount + emptyCount,
      'either grants-table or grants-empty must be present after load',
    ).toBeGreaterThan(0);
  });

  test('CTG-003: create grant via UI form — new row appears', async ({ page }) => {
    test.setTimeout(45_000);
    const { parent, child } = genTenantPair();

    await gotoPage(page);
    await page.getByTestId('grant-create-button').click();

    const modal = page.getByTestId('grant-form-modal');
    await expect(modal).toBeVisible({ timeout: 5_000 });

    // The data-testid is on the <input> itself in the source page (testid lives
    // on the number input, not a wrapper), so we can fill it directly.
    await modal.getByTestId('grant-form-parent-tenant').fill(String(parent));
    await modal.getByTestId('grant-form-child-tenant').fill(String(child));
    await modal.getByTestId('grant-form-note').fill(`e2e CTG-003 ${parent}->${child}`);

    // Capture POST response so we can extract the new id deterministically.
    const createResp = page.waitForResponse(
      (r) => r.url().includes(API_BASE) && r.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await modal.getByTestId('grant-form-submit').click();
    const resp = await createResp;
    expect(resp.ok(), 'POST /api/admin/cross-tenant-grants should succeed').toBeTruthy();
    const body = await resp.json().catch(() => null);
    const newId = body?.data?.id;
    expect(typeof newId === 'number', 'created grant must carry numeric id').toBeTruthy();
    createdIds.push(newId);

    // Modal closes; refreshed list contains the new row.
    await expect(modal).not.toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId(`grant-row-${newId}`)).toBeVisible({ timeout: 10_000 });

    // Row content sanity: parent/child tenant ids rendered.
    const row = page.getByTestId(`grant-row-${newId}`);
    await expect(row).toContainText(String(parent));
    await expect(row).toContainText(String(child));
  });

  test('CTG-004: form validation — empty submit is blocked', async ({ page }) => {
    await gotoPage(page);
    await page.getByTestId('grant-create-button').click();
    const modal = page.getByTestId('grant-form-modal');
    await expect(modal).toBeVisible({ timeout: 5_000 });

    // Submit with no inputs — HTML5 required on number fields blocks submission.
    // We assert no POST is fired and the modal stays open.
    let postFired = false;
    const onReq = (req: import('@playwright/test').Request) => {
      if (req.url().includes(API_BASE) && req.method() === 'POST') postFired = true;
    };
    page.on('request', onReq);
    await modal.getByTestId('grant-form-submit').click();

    // Modal must still be visible (no submission happened).
    await expect(modal).toBeVisible({ timeout: 2_000 });
    page.off('request', onReq);
    expect(postFired, 'empty form must not POST').toBeFalsy();

    // Cancel out cleanly.
    await modal.getByRole('button', { name: /Cancel|取消/ }).click();
    await expect(modal).not.toBeVisible({ timeout: 5_000 });
  });

  test('CTG-005: revoke grant via row action — confirms + status flips', async ({ page, request }) => {
    test.setTimeout(45_000);
    const { parent, child } = genTenantPair();
    const seedId = await apiCreateGrant(request, {
      parentTenantId: parent,
      childTenantId: child,
      note: 'e2e CTG-005 seed',
    });
    expect(seedId, 'API seed for CTG-005 must succeed (admin storageState carries platform_admin)').not.toBeNull();
    createdIds.push(seedId as number);

    await gotoPage(page);

    const row = page.getByTestId(`grant-row-${seedId}`);
    await expect(row).toBeVisible({ timeout: 10_000 });
    await row.getByTestId(`grant-revoke-button-${seedId}`).click();

    const confirmModal = page.getByTestId('grant-revoke-modal');
    await expect(confirmModal).toBeVisible({ timeout: 5_000 });

    const deleteResp = page.waitForResponse(
      (r) => r.url().includes(`${API_BASE}/${seedId}`) && r.request().method() === 'DELETE',
      { timeout: 15_000 },
    );
    await confirmModal.getByTestId('grant-revoke-confirm').click();
    const resp = await deleteResp;
    expect(resp.ok(), 'DELETE should return 2xx').toBeTruthy();

    await expect(confirmModal).not.toBeVisible({ timeout: 5_000 });

    // The revoke button is gone (page hides it for revoked rows); the row itself
    // remains in the active list when activeOnly=false. The page calls
    // listGrants(..., activeOnly=false), so the row stays but the revoke action
    // disappears.
    await expect(row).toBeVisible({ timeout: 10_000 });
    await expect(row.getByTestId(`grant-revoke-button-${seedId}`)).toHaveCount(0);
    await expect(row).toContainText(/revoked/i);
  });

  test('CTG-006: audit drawer opens with grant id header', async ({ page, request }) => {
    test.setTimeout(45_000);
    const { parent, child } = genTenantPair();
    const seedId = await apiCreateGrant(request, {
      parentTenantId: parent,
      childTenantId: child,
      note: 'e2e CTG-006 seed',
    });
    expect(seedId, 'API seed for CTG-006 must succeed').not.toBeNull();
    createdIds.push(seedId as number);

    await gotoPage(page);
    const row = page.getByTestId(`grant-row-${seedId}`);
    await expect(row).toBeVisible({ timeout: 10_000 });

    const auditResp = page.waitForResponse(
      (r) =>
        r.url().includes(`${API_BASE}/${seedId}/audit`) &&
        r.request().method() === 'GET',
      { timeout: 15_000 },
    );
    await row.getByTestId(`grant-audit-button-${seedId}`).click();

    const panel = page.getByTestId('grant-audit-panel');
    await expect(panel).toBeVisible({ timeout: 5_000 });
    await expect(panel).toContainText(`#${seedId}`);

    const resp = await auditResp;
    expect(resp.ok(), 'GET audit should return 2xx').toBeTruthy();

    // Panel shows either the audit table or the empty placeholder.
    // No spawn events have happened for this seed, so the empty copy is expected.
    await expect(panel).toContainText(/No audit rows yet|暂无审计记录|Time|时间/);
  });
});
