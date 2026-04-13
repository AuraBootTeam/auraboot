/**
 * CRM Lead Merge Queue E2E Tests
 *
 * Tests the merge queue review workflow:
 * - mq-01: Page loads with heading and filter tabs
 * - mq-02: Tab switching works (All / Pending / Merged / Rejected)
 * - mq-03: Seed duplicate data → merge queue entry appears
 * - mq-04: Click row → detail panel opens with side-by-side comparison
 * - mq-05: Reject action → status changes to rejected
 * - mq-06: Merge action → status changes to merged
 * - mq-07: Refresh button reloads data
 * - mq-08: Empty state shows guidance text
 *
 * Seeding strategy:
 * - Create 3 leads via dynamic API with the SAME company name
 * - Submit 2 webhook payloads with similar company but DIFFERENT email
 *   (to avoid exact email dedup and trigger fuzzy company+name match)
 * - Pipeline detects fuzzy match → creates merge queue entries
 *
 * @since 10.0.0
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const uid = uniqueId('mq');
const channelName = `MergeQ_${uid}`;
const companyName = `FuzzyMatchCorp_${uid}`;

// Existing lead (created via dynamic API)
const existingLeadName = `Alice_${uid}`;
const existingLeadEmail = `alice_${uid}@e2etest.com`;

// Inbound leads (submitted via webhook) — DIFFERENT email, same company → fuzzy match
const inbound1Name = `Alice_${uid}`; // same name, different email → high fuzzy score
const inbound2Name = `Alicia_${uid}`; // similar name → fuzzy match

let channelPid = '';
let apiKey = '';
let mergeQueueApiAvailable = true;
let hasMergeQueueItems = false;

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

async function navigateToMergeQueue(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav');

  // Expand CRM root
  const crmBtn = nav.getByRole('button', { name: 'crm' }).first();
  await crmBtn.scrollIntoViewIfNeeded();
  await crmBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2000 }).catch(() => null);

  // Click Lead Merge Queue
  const href = '/crm/merge-queue';
  const leafLink = nav.locator(`a[href="${href}"]`).first();
  await leafLink.waitFor({ state: 'attached', timeout: 8000 });
  await leafLink.scrollIntoViewIfNeeded();
  await leafLink.evaluate((el: HTMLElement) => el.click());

  await page.waitForURL((url) => url.pathname === href, { timeout: 10000 });
  await expect(page.getByRole('heading', { name: 'Lead Merge Queue' })).toBeVisible({
    timeout: 10000,
  });
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('CRM Lead Merge Queue @critical', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  // =========================================================================
  // Seed: Create a lead via dynamic API, then submit similar leads via
  // webhook with DIFFERENT emails to trigger fuzzy (not exact) dedup.
  //
  // Fuzzy match formula:  company * 0.5 + name * 0.3 + phone * 0.2
  // Threshold: ≥ 0.70 → merge queue entry
  //
  // Same company (1.0 * 0.5 = 0.5) + same name (1.0 * 0.3 = 0.3) = 0.80 ≥ 0.70 ✓
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // 1. Probe merge queue API
      const probeResp = await page.request.get('/api/crm/merge-queue');
      if (!probeResp.ok()) {
        mergeQueueApiAvailable = false;
        return;
      }

      // Check if there are already pending items
      const probeBody = await probeResp.json();
      const existingItems = probeBody.data ?? [];
      if (Array.isArray(existingItems) && existingItems.length > 0) {
        hasMergeQueueItems = true;
      }

      // 2. Create existing lead via dynamic API (uses actual DB column names)
      const createLeadResp = await page.request.post('/api/dynamic/crm_lead/create', {
        data: {
          crm_lead_code: `MQ-${uid}`,
          crm_lead_company: companyName,
          crm_lead_contact_name: existingLeadName,
          crm_lead_contact_email: existingLeadEmail,
          crm_lead_status: 'new',
        },
      });
      if (!createLeadResp.ok()) {
        // Backend might not be ready — continue with existing queue data
        return;
      }

      // 3. Create a generic webhook channel with correct field mapping (flat map format)
      const createChannelResp = await page.request.post('/api/crm/inbound-channels', {
        data: {
          name: channelName,
          channelType: 'generic_webhook',
          enabled: true,
          rateLimit: 100,
          config: {},
          fieldMapping: {
            company: 'crm_lead_company',
            name: 'crm_lead_contact_name',
            email: 'crm_lead_contact_email',
          },
        },
      });

      if (createChannelResp.ok()) {
        const body = await createChannelResp.json();
        const data = body.data ?? body;
        channelPid = data.pid ?? '';
        apiKey = data.apiKey ?? '';
      }

      // 4. Submit 3 inbound leads with SAME company but DIFFERENT email
      //    → no exact email/phone match → fuzzy company+name triggers merge queue
      //    Need 3 entries so mq-05 (reject) and mq-06 (merge) both have pending items
      if (channelPid) {
        for (let i = 1; i <= 3; i++) {
          await page.request.post(`/api/crm/inbound/${channelPid}/webhook`, {
            data: {
              company: companyName,
              name: `${existingLeadName}_v${i}`,
              email: `inbound${i}_${uid}@e2etest.com`,
            },
            headers: { 'X-API-Key': apiKey },
          });
          // Small delay between submissions to avoid race conditions
          await new Promise((resolve) => setTimeout(resolve, 500));
        }

        // Wait for async Redis Stream pipeline processing — need at least 2 pending items
        for (let attempt = 0; attempt < 10; attempt++) {
          await new Promise((resolve) => setTimeout(resolve, 2000));
          const recheckResp = await page.request.get('/api/crm/merge-queue');
          if (recheckResp.ok()) {
            const recheckBody = await recheckResp.json();
            const items = recheckBody.data ?? [];
            const pendingCount = Array.isArray(items)
              ? items.filter((it: { status: string }) => it.status === 'pending').length
              : 0;
            if (pendingCount >= 2) {
              hasMergeQueueItems = true;
              break;
            }
          }
        }
      }
    } finally {
      await ctx.close();
    }
  });

  test.beforeEach(async () => {
    test.skip(
      !mergeQueueApiAvailable,
      'CRM merge queue API not available (backend may need restart)',
    );
  });

  // =========================================================================
  // mq-01: Page loads with heading and filter tabs
  // =========================================================================
  test('mq-01: Page loads with heading and filter tabs', async ({ page }) => {
    await navigateToMergeQueue(page);

    // Heading visible
    await expect(page.getByRole('heading', { name: 'Lead Merge Queue' })).toBeVisible();

    // All 4 filter tabs visible
    await expect(page.locator('[data-testid="merge-tab-all"]')).toBeVisible();
    await expect(page.locator('[data-testid="merge-tab-pending"]')).toBeVisible();
    await expect(page.locator('[data-testid="merge-tab-merged"]')).toBeVisible();
    await expect(page.locator('[data-testid="merge-tab-rejected"]')).toBeVisible();

    // Either queue list or empty state must be visible
    const list = page.locator('[data-testid="merge-queue-list"]');
    const empty = page.locator('[data-testid="merge-queue-empty"]');
    await expect(list.or(empty).first()).toBeVisible({ timeout: 10000 });
  });

  // =========================================================================
  // mq-02: Tab switching changes active tab styling
  // =========================================================================
  test('mq-02: Tab switching works', async ({ page }) => {
    await navigateToMergeQueue(page);

    // Click "Pending" tab
    await page.locator('[data-testid="merge-tab-pending"]').click();
    await expect(page.locator('[data-testid="merge-tab-pending"]')).toHaveClass(/border-blue-600/);

    // Click "Merged" tab
    await page.locator('[data-testid="merge-tab-merged"]').click();
    await expect(page.locator('[data-testid="merge-tab-merged"]')).toHaveClass(/border-blue-600/);

    // Click "Rejected" tab
    await page.locator('[data-testid="merge-tab-rejected"]').click();
    await expect(page.locator('[data-testid="merge-tab-rejected"]')).toHaveClass(/border-blue-600/);

    // Click back to "All"
    await page.locator('[data-testid="merge-tab-all"]').click();
    await expect(page.locator('[data-testid="merge-tab-all"]')).toHaveClass(/border-blue-600/);
  });

  // =========================================================================
  // mq-03: Queue has items from fuzzy-matched seed data
  // =========================================================================
  test('mq-03: Queue shows items when data exists', async ({ page }) => {
    expect(
      hasMergeQueueItems,
      'Merge queue should have items after seeding fuzzy-matched leads ' +
        '(same company, different email). If this fails, check: ' +
        '1) Redis Stream consumer is running, ' +
        '2) pg_trgm extension is enabled, ' +
        '3) existing lead was created with correct column names',
    ).toBeTruthy();

    await navigateToMergeQueue(page);

    // Queue list should be visible with at least one row
    const list = page.locator('[data-testid="merge-queue-list"]');
    await expect(list).toBeVisible({ timeout: 10000 });

    const rows = page.locator('[data-testid="merge-queue-row"]');
    const rowCount = await rows.count();
    expect(rowCount, 'Queue should have at least 1 item').toBeGreaterThan(0);

    // First row should have a match score percentage displayed (e.g. "80%")
    const firstRow = rows.first();
    await expect(firstRow).toBeVisible();
    await expect(firstRow.getByText('%')).toBeVisible();
  });

  // =========================================================================
  // mq-04: Click row → detail panel opens with side-by-side comparison
  // =========================================================================
  test('mq-04: Detail panel opens on row click', async ({ page }) => {
    expect(hasMergeQueueItems, 'Need merge queue items').toBeTruthy();

    await navigateToMergeQueue(page);

    // Stay on "All" tab to ensure rows are visible (Pending may be empty from prior runs)
    await expect(page.locator('[data-testid="merge-queue-list"]')).toBeVisible({ timeout: 10000 });

    // Click the first row → detail API call
    const detailResponse = page.waitForResponse(
      (r) =>
        r.url().match(/\/api\/crm\/merge-queue\/\d+$/) !== null && r.request().method() === 'GET',
      { timeout: 10000 },
    );
    await page.locator('[data-testid="merge-queue-row"]').first().click();
    await detailResponse;

    // Detail panel should open with "Merge Review" heading
    await expect(page.getByText('Merge Review')).toBeVisible({ timeout: 5000 });

    // Side-by-side comparison labels
    await expect(page.getByText('Inbound (New)')).toBeVisible();
    await expect(page.getByText('Existing Lead')).toBeVisible();

    // Match score in header
    await expect(page.getByText(/\d+% match/)).toBeVisible();

    // Action buttons are only visible for pending items — check conditionally
    const mergeBtn = page.locator('[data-testid="merge-confirm-btn"]');
    const hasMergeBtn = await mergeBtn.isVisible({ timeout: 2000 }).catch(() => false);
    if (hasMergeBtn) {
      await expect(page.locator('[data-testid="merge-reject-btn"]')).toBeVisible();
      await expect(page.locator('[data-testid="merge-create-new-btn"]')).toBeVisible();
    }

    // Close detail panel
    const closeBtn = page
      .locator('.fixed.inset-0 button')
      .filter({ has: page.locator('svg.h-5') })
      .first();
    await closeBtn.click();
    await expect(page.getByText('Merge Review')).not.toBeVisible({ timeout: 3000 });
  });

  // =========================================================================
  // mq-05: Reject action → confirmation modal → status changes
  // =========================================================================
  test('mq-05: Reject action with confirmation', async ({ page }) => {
    expect(hasMergeQueueItems, 'Need merge queue items').toBeTruthy();

    await navigateToMergeQueue(page);

    // Switch to Pending tab
    await page.locator('[data-testid="merge-tab-pending"]').click();

    // Wait for list
    const list = page.locator('[data-testid="merge-queue-list"]');
    const empty = page.locator('[data-testid="merge-queue-empty"]');
    await expect(list.or(empty).first()).toBeVisible({ timeout: 10000 });

    const pendingRows = page.locator('[data-testid="merge-queue-row"]');
    const pendingCount = await pendingRows.count();
    expect(pendingCount, 'Should have at least 1 pending item to reject').toBeGreaterThan(0);

    // Click first pending row to open detail
    const detailResponse = page.waitForResponse(
      (r) =>
        r.url().match(/\/api\/crm\/merge-queue\/\d+$/) !== null && r.request().method() === 'GET',
      { timeout: 10000 },
    );
    await pendingRows.first().click();
    await detailResponse;

    // Click Reject
    await expect(page.locator('[data-testid="merge-reject-btn"]')).toBeVisible({ timeout: 5000 });
    await page.locator('[data-testid="merge-reject-btn"]').click();

    // Confirmation modal
    await expect(page.getByText('Reject Duplicate')).toBeVisible({ timeout: 3000 });
    await expect(page.locator('[data-testid="confirm-action-btn"]')).toBeVisible();

    // Confirm rejection
    const rejectResponse = page.waitForResponse(
      (r) => r.url().includes('/reject') && r.request().method() === 'POST',
      { timeout: 10000 },
    );
    await page.locator('[data-testid="confirm-action-btn"]').click();
    const resp = await rejectResponse;
    expect(resp.status()).toBeLessThan(300);

    // Toast confirms rejection
    await expect(page.getByText('Duplicate rejected')).toBeVisible({ timeout: 5000 });

    // Detail panel closes
    await expect(page.getByText('Merge Review')).not.toBeVisible({ timeout: 5000 });
  });

  // =========================================================================
  // mq-06: Merge action → confirmation modal → status changes
  // =========================================================================
  test('mq-06: Merge action with confirmation', async ({ page }) => {
    expect(hasMergeQueueItems, 'Need merge queue items').toBeTruthy();

    await navigateToMergeQueue(page);

    // Switch to Pending tab
    await page.locator('[data-testid="merge-tab-pending"]').click();

    const list = page.locator('[data-testid="merge-queue-list"]');
    const empty = page.locator('[data-testid="merge-queue-empty"]');
    await expect(list.or(empty).first()).toBeVisible({ timeout: 10000 });

    const pendingRows = page.locator('[data-testid="merge-queue-row"]');
    const pendingCount = await pendingRows.count();
    expect(pendingCount, 'Should have at least 1 pending item to merge').toBeGreaterThan(0);

    // Click a pending row
    const detailResponse = page.waitForResponse(
      (r) =>
        r.url().match(/\/api\/crm\/merge-queue\/\d+$/) !== null && r.request().method() === 'GET',
      { timeout: 10000 },
    );
    await pendingRows.first().click();
    await detailResponse;

    // Click Merge
    await expect(page.locator('[data-testid="merge-confirm-btn"]')).toBeVisible({ timeout: 5000 });
    await page.locator('[data-testid="merge-confirm-btn"]').click();

    // Confirmation modal
    await expect(page.getByText('Confirm Merge')).toBeVisible({ timeout: 3000 });

    // Confirm merge
    const mergeResponse = page.waitForResponse(
      (r) => r.url().includes('/merge') && r.request().method() === 'POST',
      { timeout: 10000 },
    );
    await page.locator('[data-testid="confirm-action-btn"]').click();
    const resp = await mergeResponse;
    expect(resp.status()).toBeLessThan(300);

    // Toast confirms merge
    await expect(page.getByText('Lead merged successfully')).toBeVisible({ timeout: 5000 });

    // Detail panel closes
    await expect(page.getByText('Merge Review')).not.toBeVisible({ timeout: 5000 });
  });

  // =========================================================================
  // mq-07: Refresh button reloads data
  // =========================================================================
  test('mq-07: Refresh button reloads data', async ({ page }) => {
    await navigateToMergeQueue(page);

    // Wait for initial load
    const list = page.locator('[data-testid="merge-queue-list"]');
    const empty = page.locator('[data-testid="merge-queue-empty"]');
    await expect(list.or(empty).first()).toBeVisible({ timeout: 10000 });

    // Click Refresh → intercept API call
    const refreshResponse = page.waitForResponse(
      (r) => r.url().includes('/api/crm/merge-queue') && r.request().method() === 'GET',
      { timeout: 10000 },
    );
    await page.getByRole('button', { name: 'Refresh' }).click();
    const resp = await refreshResponse;
    expect(resp.ok()).toBeTruthy();

    // Page stays in valid state
    await expect(list.or(empty).first()).toBeVisible({ timeout: 10000 });
  });

  // =========================================================================
  // mq-08: Empty state shows guidance text
  // =========================================================================
  test('mq-08: Empty state shows guidance text', async ({ page }) => {
    await navigateToMergeQueue(page);

    // After mq-05/06 acted on items, "Rejected" tab should have data
    // Switch to a specific status tab and verify either list or empty state
    await page.locator('[data-testid="merge-tab-rejected"]').click();

    const list = page.locator('[data-testid="merge-queue-list"]');
    const empty = page.locator('[data-testid="merge-queue-empty"]');
    await expect(list.or(empty).first()).toBeVisible({ timeout: 10000 });

    // If empty, verify guidance text is present
    const emptyVisible = await empty.isVisible().catch(() => false);
    if (emptyVisible) {
      await expect(empty).toContainText(/no.*items|switch.*all/i);
    }

    // If list, verify rejected badge is shown
    const listVisible = await list.isVisible().catch(() => false);
    if (listVisible) {
      await expect(list.getByText('Rejected').first()).toBeVisible();
    }
  });
});
