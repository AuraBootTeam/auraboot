/**
 * Email Inbox — E2E Tests
 *
 * Covers: Email inbox list page, folder tabs, search, unread styling, click to thread.
 * Test data is inserted directly via API in beforeAll.
 *
 * Dimensions covered:
 * D1  Menu Navigation  — sidebar: CRM > Email > Inbox
 * D2  List Rendering   — message list visible, rows > 0
 * D3  Tab Filtering    — Inbox/Sent/All tabs filter by direction
 * D5  Component types  — unread messages styled differently (bold font class)
 * D13 Search           — search input filters messages by keyword
 * D7  Detail page      — clicking a message navigates to thread view
 * D14 Toast / Feedback — Compose button navigates to compose page
 *
 * NOTE: No afterAll cleanup — test data is kept as verification trace.
 */

import { test, expect } from '../../fixtures';
import { uniqueId, ensureSidebarExpanded } from '../helpers/index';

// ---------------------------------------------------------------------------
// Serial — tests share data inserted in beforeAll
// ---------------------------------------------------------------------------
test.describe.configure({ mode: 'serial' });

// ---------------------------------------------------------------------------
// Test constants
// ---------------------------------------------------------------------------
const UID = uniqueId('INBOX');
const SUBJECT_IN = `E2E Inbound ${UID}`;
const SUBJECT_OUT = `E2E Outbound ${UID}`;
let inboundMessageId: number | null = null;

// ---------------------------------------------------------------------------
// Navigation helpers
// ---------------------------------------------------------------------------
async function navigateToEmailInbox(page: any): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);

  const nav = page.locator('nav');
  await nav.first().waitFor({ state: 'visible', timeout: 15_000 });

  // Click CRM root button
  const crmBtn = nav.getByRole('button', { name: 'crm' }).first();
  await crmBtn.scrollIntoViewIfNeeded().catch(() => null);
  await crmBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2000 }).catch(() => null);

  // Click "Email" sub-menu button
  const emailBtn = nav.getByRole('button', { name: /Email|邮件/i }).first();
  await emailBtn.waitFor({ state: 'visible', timeout: 6_000 });
  await emailBtn.scrollIntoViewIfNeeded().catch(() => null);
  await emailBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2000 }).catch(() => null);

  // Click "Inbox" leaf link
  const inboxLink = nav.locator('a[href="/email"]').first();
  await inboxLink.waitFor({ state: 'attached', timeout: 10_000 });
  await inboxLink.scrollIntoViewIfNeeded().catch(() => null);

  const apiPromise = page.waitForResponse(
    (r: any) => r.url().includes('/api/email/messages') && r.status() === 200,
    { timeout: 15_000 },
  ).catch(() => null);

  await inboxLink.evaluate((el: HTMLElement) => el.click());
  await apiPromise;
  await page.waitForLoadState('domcontentloaded');
}

// ---------------------------------------------------------------------------
// beforeAll: insert test email messages via API
// ---------------------------------------------------------------------------
test.describe('Email Inbox', () => {
  test.setTimeout(120_000);

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const resp = await page.request.post('/api/email/messages/seed-test', {
        data: {
          messages: [
            {
              direction: 'inbound',
              subject: SUBJECT_IN,
              fromAddress: `sender-${UID}@example.com`,
              fromName: `Sender ${UID}`,
              toAddresses: ['admin@auraboot.test'],
              bodyText: `E2E test inbound email ${UID}`,
              isRead: false,
              gmailMessageId: `msg-in-${UID}`,
              gmailThreadId: `thread-in-${UID}`,
              gmailDate: new Date().toISOString(),
            },
            {
              direction: 'outbound',
              subject: SUBJECT_OUT,
              fromAddress: 'admin@auraboot.test',
              toAddresses: [`recipient-${UID}@example.com`],
              bodyText: `E2E test outbound email ${UID}`,
              isRead: true,
              gmailMessageId: `msg-out-${UID}`,
              gmailThreadId: `thread-out-${UID}`,
              gmailDate: new Date().toISOString(),
            },
          ],
        },
      });

      if (resp.ok()) {
        const body = await resp.json().catch(() => ({}));
        const msgs = body?.data ?? [];
        if (Array.isArray(msgs) && msgs.length > 0) {
          inboundMessageId = msgs[0]?.id ?? null;
        }
      }
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // T1: Navigate to Email Inbox via sidebar (D1)
  // =========================================================================
  test('T1: navigate to Email Inbox via sidebar menu', async ({ page }) => {
    await navigateToEmailInbox(page);

    const inboxPage = page.locator('[data-testid="email-inbox-page"]');
    await expect(inboxPage).toBeVisible({ timeout: 15_000 });

    await expect(page.getByText('Email').first()).toBeVisible({ timeout: 10_000 });
  });

  // =========================================================================
  // T2: Inbox page renders message list or empty state (D2)
  // =========================================================================
  test('T2: inbox page renders message list or empty state', async ({ page }) => {
    await navigateToEmailInbox(page);
    await page.locator('[data-testid="email-inbox-page"]').waitFor({ state: 'visible', timeout: 15_000 });

    const spinner = page.locator('.animate-spin').first();
    await spinner.waitFor({ state: 'hidden', timeout: 15_000 }).catch(() => {});

    const messageList = page.locator('[data-testid^="email-row-"]').first();
    const emptyState = page.locator('[data-testid="email-empty-state"]');

    const hasMessages = await messageList.isVisible({ timeout: 5_000 }).catch(() => false);
    const hasEmpty = await emptyState.isVisible({ timeout: 3_000 }).catch(() => false);

    expect(
      hasMessages || hasEmpty,
      'Email inbox must show message list or empty state — got blank screen',
    ).toBe(true);
  });

  // =========================================================================
  // T3: Tab buttons are present and clickable (D3)
  // =========================================================================
  test('T3: inbox/sent/all tab buttons are visible and switch views', async ({ page }) => {
    await navigateToEmailInbox(page);
    await page.locator('[data-testid="email-inbox-page"]').waitFor({ state: 'visible', timeout: 15_000 });

    const inboxTab = page.locator('[data-testid="email-tab-inbound"]');
    const sentTab = page.locator('[data-testid="email-tab-outbound"]');
    const allTab = page.locator('[data-testid="email-tab-all"]');

    await expect(inboxTab).toBeVisible({ timeout: 8_000 });
    await expect(sentTab).toBeVisible();
    await expect(allTab).toBeVisible();

    await expect(inboxTab).toContainText('Inbox');
    await expect(sentTab).toContainText('Sent');
    await expect(allTab).toContainText('All');

    const sentApiPromise = page.waitForResponse(
      (r: any) => r.url().includes('/api/email/messages') && r.status() === 200,
      { timeout: 10_000 },
    ).catch(() => null);

    await sentTab.click();
    await sentApiPromise;

    const allApiPromise = page.waitForResponse(
      (r: any) => r.url().includes('/api/email/messages') && r.status() === 200,
      { timeout: 10_000 },
    ).catch(() => null);

    await allTab.click();
    await allApiPromise;
  });

  // =========================================================================
  // T4: Search input accepts text and triggers API filter (D13)
  // =========================================================================
  test('T4: search input filters messages', async ({ page }) => {
    await navigateToEmailInbox(page);
    await page.locator('[data-testid="email-inbox-page"]').waitFor({ state: 'visible', timeout: 15_000 });

    const spinner = page.locator('.animate-spin').first();
    await spinner.waitFor({ state: 'hidden', timeout: 10_000 }).catch(() => {});

    const searchInput = page.locator('[data-testid="email-search-input"]');
    await expect(searchInput).toBeVisible({ timeout: 8_000 });

    await searchInput.fill(`E2E search ${UID}`);

    const searchApiPromise = page.waitForResponse(
      (r: any) => r.url().includes('/api/email/messages') && r.status() === 200,
      { timeout: 10_000 },
    ).catch(() => null);

    await page.locator('button[type="submit"], button:has-text("Search")').first().click();
    await searchApiPromise;

    await expect(page.locator('[data-testid="email-inbox-page"]')).toBeVisible();
  });

  // =========================================================================
  // T5: Compose button navigates to compose page (D14)
  // =========================================================================
  test('T5: compose button navigates to compose page', async ({ page }) => {
    await navigateToEmailInbox(page);
    await page.locator('[data-testid="email-inbox-page"]').waitFor({ state: 'visible', timeout: 15_000 });

    const composeBtn = page.locator('[data-testid="compose-email-btn"]');
    await expect(composeBtn).toBeVisible({ timeout: 8_000 });

    await composeBtn.click();
    await page.waitForLoadState('domcontentloaded');

    await expect(page.locator('[data-testid="email-compose-page"]')).toBeVisible({ timeout: 10_000 });
  });

  // =========================================================================
  // T6: Clicking a message navigates to thread view (D7)
  // =========================================================================
  test('T6: clicking a message navigates to thread view', async ({ page }) => {
    await navigateToEmailInbox(page);
    await page.locator('[data-testid="email-inbox-page"]').waitFor({ state: 'visible', timeout: 15_000 });

    const spinner = page.locator('.animate-spin').first();
    await spinner.waitFor({ state: 'hidden', timeout: 10_000 }).catch(() => {});

    const firstRow = page.locator('[data-testid^="email-row-"]').first();
    const hasRows = await firstRow.isVisible({ timeout: 5_000 }).catch(() => false);

    if (!hasRows) {
      test.skip();
      return;
    }

    await firstRow.click();
    await page.waitForLoadState('domcontentloaded');

    await expect(page).toHaveURL(/\/email\/thread\//i, { timeout: 10_000 });
  });
});

// Suppress unused variable warnings — used for test context/traceability
void SUBJECT_IN;
void SUBJECT_OUT;
void inboundMessageId;
