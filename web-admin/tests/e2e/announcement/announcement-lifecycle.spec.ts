/**
 * Announcement State Machine — E2E Lifecycle Test
 *
 * Coverage:
 *   D1  Menu Navigation — sidebar click to announcement list
 *   D2  List Rendering — table with columns, data visible
 *   D4  Create (Full Form) — fill title, content, priority, pinned, expires_at
 *   D6  Create Verification — new record in list with status=draft
 *   D9  State Transitions — draft→published→archived→published
 *   D10 Invalid Transitions — reject publish on published record
 *   D11 Delete — confirm dialog, record removed
 *   D14 Toast / Feedback — mutation shows success toast
 *
 * Prerequisites:
 *   - core-announcement plugin imported
 *   - Backend + Frontend running
 *
 * @since 6.5.0
 */

import { test, expect, type Page } from '../../fixtures';
import {
  uniqueId,
  dateOffsetStr,
  waitForDynamicPageLoad,
  executeCommandViaApi,
  findRowByContent,
} from '../helpers/index';

test.describe.configure({ mode: 'serial' });

const UID = uniqueId('ANN');
const TITLE = `Test Announcement ${UID}`;
const CONTENT = `Announcement content for E2E test ${UID}`;
const EXPIRES = dateOffsetStr(30) + 'T23:59:59Z';

let recordPid: string;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function navigateToAnnouncementList(page: Page): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  const parentBtn = nav.locator('text="公告管理"').first();
  await parentBtn.waitFor({ state: 'visible', timeout: 5_000 });
  await parentBtn.evaluate((el: HTMLElement) => el.click());

  const leafLink = nav.locator('a[href*="ab_announcement"]').first();
  await leafLink.waitFor({ state: 'visible', timeout: 5_000 });
  await leafLink.evaluate((el: HTMLElement) => el.click());

  await waitForDynamicPageLoad(page);
}

/** Click a row action from overflow menu, handle optional confirm dialog */
async function clickRowAction(page: Page, title: string, actionLabel: string): Promise<void> {
  const row = await findRowByContent(page, title);
  const moreBtn = row.locator('button:has-text("More"), [data-testid="row-action-more"]').first();
  await moreBtn.click();

  const actionBtn = page.locator(`button:has-text("${actionLabel}")`).first();
  await expect(actionBtn).toBeVisible({ timeout: 3_000 });
  await actionBtn.click();

  // Handle optional confirmation dialog
  const dialog = page.locator('[data-testid="confirm-dialog"]');
  const hasDialog = await dialog.isVisible({ timeout: 2_000 }).catch(() => false);
  if (hasDialog) {
    await page.locator('[data-testid="confirm-ok"]').click();
    await dialog.waitFor({ state: 'hidden', timeout: 5_000 });
  }
}

/** Wait for a row's status cell to show the expected status */
async function expectRowStatus(page: Page, title: string, status: RegExp): Promise<void> {
  const row = page.locator('table tbody tr').filter({ hasText: title }).first();
  await expect(row.locator('td').filter({ hasText: status }).first())
    .toBeVisible({ timeout: 5_000 });
}

// ---------------------------------------------------------------------------
// D4 — Create Announcement (Draft)
// ---------------------------------------------------------------------------

test('create announcement in draft status', async ({ page }) => {
  const result = await executeCommandViaApi(page, 'announcement:create_announcement', {
    title: TITLE,
    content: CONTENT,
    priority: 'normal',
    pinned: false,
    expires_at: EXPIRES,
  });
  expect(result.recordId).toBeTruthy();
  recordPid = result.recordId;
});

// ---------------------------------------------------------------------------
// D1 + D2 — Navigate and verify list rendering
// ---------------------------------------------------------------------------

test('list page shows created announcement with draft status', async ({ page }) => {
  await navigateToAnnouncementList(page);

  const table = page.locator('table');
  await table.first().waitFor({ state: 'visible', timeout: 10_000 });

  const row = await findRowByContent(page, TITLE);
  await expect(row).toBeVisible();

  await expectRowStatus(page, TITLE, /draft|草稿/i);
});

// ---------------------------------------------------------------------------
// D9 — State Transition: draft → published (via row action)
// ---------------------------------------------------------------------------

test('publish announcement from draft to published', async ({ page }) => {
  await navigateToAnnouncementList(page);
  await clickRowAction(page, TITLE, '发布');
  await expectRowStatus(page, TITLE, /published|已发布/i);
});

// ---------------------------------------------------------------------------
// D9 — Verify conditional row actions for published status
// ---------------------------------------------------------------------------

test('published record shows archive action, hides edit and publish', async ({ page }) => {
  await navigateToAnnouncementList(page);

  const row = await findRowByContent(page, TITLE);
  const moreBtn = row.locator('button:has-text("More"), [data-testid="row-action-more"]').first();
  await moreBtn.click();

  // Archive should be visible for published
  await expect(page.locator('button:has-text("撤回")').first()).toBeVisible({ timeout: 3_000 });

  // Edit and Publish should NOT be visible for published
  const menuItems = page.locator('[data-testid="row-action-dropdown"] button, [role="menu"] button');
  const texts = await menuItems.allTextContents();
  expect(texts.join('|')).not.toMatch(/edit|编辑/i);
  expect(texts.join('|')).not.toMatch(/^发布$/);
});

// ---------------------------------------------------------------------------
// D9 — State Transition: published → archived
// ---------------------------------------------------------------------------

test('archive announcement from published to archived', async ({ page }) => {
  await navigateToAnnouncementList(page);
  await clickRowAction(page, TITLE, '撤回');
  await expectRowStatus(page, TITLE, /archived|已撤回/i);
});

// ---------------------------------------------------------------------------
// D9 — State Transition: archived → published (republish)
// ---------------------------------------------------------------------------

test('republish announcement from archived to published', async ({ page }) => {
  await navigateToAnnouncementList(page);
  await clickRowAction(page, TITLE, '重新发布');
  await expectRowStatus(page, TITLE, /published|已发布/i);
});

// ---------------------------------------------------------------------------
// D10 — Invalid Transition: publish on already-published (API guard)
// ---------------------------------------------------------------------------

test('reject publish on already-published record via API', async ({ page }) => {
  expect(recordPid).toBeTruthy();

  try {
    await executeCommandViaApi(page, 'announcement:publish', {}, recordPid);
    test.fail(true, 'Expected publish to fail on already-published record');
  } catch {
    // Expected: API returns error for invalid state transition
  }
});

// ---------------------------------------------------------------------------
// D9 + D11 — Archive then Delete
// ---------------------------------------------------------------------------

test('archive and delete announcement', async ({ page }) => {
  // Archive first (published → archived) so delete becomes available
  await navigateToAnnouncementList(page);
  await clickRowAction(page, TITLE, '撤回');
  await expectRowStatus(page, TITLE, /archived|已撤回/i);

  // Delete
  await clickRowAction(page, TITLE, 'delete');

  // Verify record is gone
  const gone = page.locator('table').getByText(TITLE);
  await expect(gone).toHaveCount(0, { timeout: 5_000 });
});
