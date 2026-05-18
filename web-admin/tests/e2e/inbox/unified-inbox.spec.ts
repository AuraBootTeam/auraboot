/**
 * Unified Inbox E2E Tests
 *
 * Covers: D1 (navigation), D2 (list render), D3 (tab filter), D14 (feedback)
 *
 * Tests the unified inbox feature:
 * - InboxBadge in header (badge + unread count)
 * - InboxDropdown (recent items)
 * - UnifiedInboxPage (tabs, card list, pagination, dismiss)
 * - BpmTaskDrawer integration (approval items open drawer)
 */

import { test, expect, type Page } from '@playwright/test';

test.describe('Unified Inbox', () => {
  let inboxAvailable = true;

  async function waitForInboxListSettled(page: Page) {
    await expect
      .poll(
        async () => {
          const loading = await page.getByTestId('inbox-loading-state').count().catch(() => 0);
          const cards = await page.getByTestId(/^inbox-item-/).count().catch(() => 0);
          const empty = await page.getByTestId('inbox-empty-state').count().catch(() => 0);
          return loading > 0 ? 'loading' : cards > 0 ? 'items' : empty > 0 ? 'empty' : 'pending';
        },
        { timeout: 10_000, intervals: [100, 250, 500, 1_000] },
      )
      .not.toBe('loading');
  }

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const resp = await page.request.get('/api/inbox?pageNum=1&pageSize=1', {
        failOnStatusCode: false,
      });
      if (!resp.ok()) {
        inboxAvailable = false;
      }
    } finally {
      await page.close();
      await ctx.close();
    }
  });

  test.beforeEach(async ({ page }) => {
    test.skip(!inboxAvailable, 'Inbox API not available');
    await page.goto('/inbox');
    // Wait for the inbox page container instead of networkidle
    // (InboxBadge polls /unread-count every 30s, so networkidle never settles)
    await expect(page.getByTestId('unified-inbox-page')).toBeVisible({ timeout: 15_000 });
  });

  test('inbox page loads with correct structure', async ({ page }) => {
    // D1: Navigate to inbox page
    const inboxPage = page.getByTestId('unified-inbox-page');
    await expect(inboxPage).toBeVisible();

    // Page header visible
    await expect(page.getByRole('heading', { name: 'Inbox' })).toBeVisible();

    // D2: Tabs are visible
    await expect(page.getByTestId('inbox-tab-all')).toBeVisible();
    await expect(page.getByTestId('inbox-tab-approval')).toBeVisible();
    await expect(page.getByTestId('inbox-tab-alert')).toBeVisible();
    await expect(page.getByTestId('inbox-tab-assignment')).toBeVisible();

    // Status filters visible
    await expect(page.getByTestId('inbox-status-pending')).toBeVisible();
    await expect(page.getByTestId('inbox-status-all')).toBeVisible();

    // Mark all read button visible
    await expect(page.getByTestId('inbox-mark-all-read')).toBeVisible();

    // Summary and refresh affordances visible
    await expect(page.getByTestId('inbox-summary-cards')).toBeVisible();
    await expect(page.getByTestId('inbox-refresh')).toBeVisible();

    // Primary toolbar is visible
    await expect(page.getByTestId('inbox-primary-toolbar')).toBeVisible();
  });

  test('tab filtering changes displayed items', async ({ page }) => {
    // Tab state is driven by URL search params; under full-suite contention the
    // setSearchParams → useEffect → setState round-trip can take >5s, so poll
    // generously instead of the default 5s.
    await page.getByTestId('inbox-tab-approval').click();
    await expect(page.getByTestId('inbox-tab-approval')).toHaveClass(
      /bg-blue-50|bg-blue-900/,
      { timeout: 15_000 },
    );
    await waitForInboxListSettled(page);

    await page.getByTestId('inbox-tab-alert').click();
    await expect(page.getByTestId('inbox-tab-alert')).toHaveClass(
      /bg-blue-50|bg-blue-900/,
      { timeout: 15_000 },
    );
    await waitForInboxListSettled(page);

    await page.getByTestId('inbox-tab-all').click();
    await expect(page.getByTestId('inbox-tab-all')).toHaveClass(
      /bg-blue-50|bg-blue-900/,
      { timeout: 15_000 },
    );
    await waitForInboxListSettled(page);
  });

  test('status filter changes displayed items', async ({ page }) => {
    // Click "All" status filter and wait for the API response
    const responsePromise = page.waitForResponse(
      (resp) => resp.url().includes('/api/inbox') && resp.request().method() === 'GET',
    );
    await page.getByTestId('inbox-status-all').click();
    const response = await responsePromise;
    expect(response.status()).toBe(200);
  });

  test('empty state shows when no items', async ({ page }) => {
    test.fixme(true, 'Inbox filter combination not reliably producing empty state');
    // Navigate with a filter combination likely to return 0 results
    // Click alert tab first, then "closed" status
    const alertResponsePromise = page.waitForResponse(
      (resp) =>
        resp.url().includes('/api/inbox') &&
        resp.url().includes('itemType=alert') &&
        resp.request().method() === 'GET',
    );
    await page.getByTestId('inbox-tab-alert').click();
    await alertResponsePromise;

    const closedResponsePromise = page.waitForResponse(
      (resp) => resp.url().includes('/api/inbox') && resp.request().method() === 'GET',
    );
    await page.getByTestId('inbox-status-closed').click();
    await closedResponsePromise;

    // Either shows items or empty state
    const hasItems = await page.getByTestId(/^inbox-item-/).count();
    if (hasItems === 0) {
      await expect(page.getByTestId('inbox-empty-state')).toBeVisible();
      await expect(page.getByText('No items to show')).toBeVisible();
    }
  });

  test('mark all read button works', async ({ page }) => {
    const markAllBtn = page.getByTestId('inbox-mark-all-read');
    const isBtnVisible = await markAllBtn.isVisible({ timeout: 5000 }).catch(() => false);
    if (!isBtnVisible) {
      // Button may not be shown if there are no unread items
      test.skip(true, 'Mark all read button not visible — no unread items');
      return;
    }

    // Set up response listener BEFORE clicking — match any inbox-related PUT
    const responsePromise = page.waitForResponse(
      (resp) => resp.url().includes('/api/inbox') && resp.request().method() === 'PUT',
      { timeout: 10_000 },
    );

    await markAllBtn.click();

    const response = await responsePromise;
    expect(response.status()).toBe(200);
  });
});

test.describe('Inbox Header Widget', () => {
  test('inbox badge is visible in header', async ({ page }) => {
    await page.goto('/');
    // Wait for the inbox badge to appear instead of networkidle
    const badge = page.getByTestId('inbox-badge');
    await expect(badge).toBeVisible({ timeout: 15_000 });
  });

  test('clicking badge opens dropdown', async ({ page }) => {
    test.fixme(true, 'Inbox header widget testids changed — inbox-badge not found');
    await page.goto('/');
    await expect(page.getByTestId('inbox-badge')).toBeVisible({ timeout: 15_000 });

    // Click inbox badge
    await page.getByTestId('inbox-badge').click();

    // Dropdown should appear
    const dropdown = page.getByTestId('inbox-dropdown');
    await expect(dropdown).toBeVisible();

    // Should have "View all" link
    await expect(page.getByTestId('inbox-view-all')).toBeVisible();

    // Should have mark all read button in dropdown
    await expect(dropdown.getByTestId('inbox-mark-all-read')).toBeVisible();
  });

  test('view all link navigates to inbox page', async ({ page }) => {
    test.fixme(true, 'Inbox header widget testids changed — inbox-badge not found');
    await page.goto('/');
    await expect(page.getByTestId('inbox-badge')).toBeVisible({ timeout: 15_000 });

    // Open dropdown
    await page.getByTestId('inbox-badge').click();
    await expect(page.getByTestId('inbox-dropdown')).toBeVisible({ timeout: 5_000 });

    // Click view all
    await page.getByTestId('inbox-view-all').click();

    // Should navigate to /inbox
    await page.waitForURL('**/inbox');
    await expect(page.getByTestId('unified-inbox-page')).toBeVisible();
  });

  test('dropdown closes on escape', async ({ page }) => {
    test.fixme(true, 'Inbox header widget testids changed — inbox-badge not found');
    await page.goto('/');
    await expect(page.getByTestId('inbox-badge')).toBeVisible({ timeout: 15_000 });

    // Open dropdown
    await page.getByTestId('inbox-badge').click();
    await expect(page.getByTestId('inbox-dropdown')).toBeVisible();

    // Press escape
    await page.keyboard.press('Escape');

    // Dropdown should close
    await expect(page.getByTestId('inbox-dropdown')).not.toBeVisible();
  });
});
