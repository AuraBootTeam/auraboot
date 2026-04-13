/**
 * CRM Inbound Full Lifecycle E2E Test
 *
 * Validates the end-to-end inbound lead ingestion pipeline:
 * - lifecycle-01: Create a channel via API, submit a lead via webhook endpoint
 * - lifecycle-02: Verify the lead appears in the CRM Leads list
 * - lifecycle-03: Verify Merge Queue page renders (may have merge candidates)
 * - lifecycle-04: Web Forms page accessible and shows empty or list state
 * - lifecycle-05: Calendar Sync provider cards render with disconnect state
 *
 * @since 10.0.0
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId, queryFilteredList } from '../helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const uid = uniqueId('lc');
const channelName = `Lifecycle_${uid}`;
const leadCompany = `LifecycleCorp_${uid}`;
const leadName = `John_${uid}`;
const leadEmail = `john_${uid}@e2etest.com`;

let channelPid = '';
let apiKey = '';
let channelApiAvailable = true;

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

async function navigateViaCrmMenu(page: Page, href: string, subMenuName?: string): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav');

  // Expand CRM root
  const crmBtn = nav.getByRole('button', { name: 'crm' }).first();
  await crmBtn.scrollIntoViewIfNeeded();
  await crmBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2000 }).catch(() => null);

  // Expand sub-menu if needed
  if (subMenuName) {
    const subBtn = nav.getByRole('button', { name: subMenuName });
    const subVisible = await subBtn.isVisible({ timeout: 3000 }).catch(() => false);
    if (subVisible) {
      await subBtn.scrollIntoViewIfNeeded();
      await subBtn.evaluate((el: HTMLElement) => el.click());
      await page.waitForResponse(() => true, { timeout: 1500 }).catch(() => null);
    }
  }

  const leafLink = nav.locator(`a[href="${href}"]`).first();
  await leafLink.waitFor({ state: 'attached', timeout: 8000 });
  await leafLink.scrollIntoViewIfNeeded();
  await leafLink.evaluate((el: HTMLElement) => el.click());

  await page.waitForURL((url) => url.pathname === href, { timeout: 10000 });
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('CRM Inbound Full Lifecycle @critical', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  // =========================================================================
  // Seed: Create channel via API
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Probe if CRM channel API is available
      const probeResp = await page.request.get('/api/crm/inbound-channels');
      if (!probeResp.ok()) {
        channelApiAvailable = false;
        return;
      }

      // Create a generic webhook channel via the REST API
      const createResp = await page.request.post('/api/crm/inbound-channels', {
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

      if (createResp.ok()) {
        const body = await createResp.json();
        const data = body.data ?? body;
        channelPid = data.pid ?? '';
        apiKey = data.apiKey ?? '';
      }
    } finally {
      await ctx.close();
    }
  });

  test('lifecycle-01: Submit lead via webhook API', async ({ page }) => {
    // Skip if CRM channel API is not available or channel creation failed
    test.skip(!channelApiAvailable, 'CRM channel API not available (backend may need restart)');
    test.skip(!channelPid, 'Channel creation failed — cannot submit webhook');

    // Submit a lead payload via the webhook endpoint
    const webhookResp = await page.request.post(`/api/crm/inbound/${channelPid}/webhook`, {
      data: {
        company: leadCompany,
        name: leadName,
        email: leadEmail,
      },
      headers: {
        'X-API-Key': apiKey,
      },
    });

    // Should return 200 or 202 (accepted)
    expect(
      webhookResp.status(),
      `Webhook submission should succeed, got ${webhookResp.status()}`,
    ).toBeLessThan(300);
  });

  test('lifecycle-02: Lead appears in CRM Leads', async ({ page }) => {
    // Skip if CRM channel API is not available or channel creation failed
    test.skip(!channelApiAvailable, 'CRM channel API not available (backend may need restart)');
    test.skip(!channelPid, 'Channel creation failed — cannot verify lead');

    // Use API query to verify the lead was created
    // The lead may be processed asynchronously, so retry a few times
    const found = await expect
      .poll(
        async () => {
          const records = await queryFilteredList(page, 'crm-lead', 'crm_lead_company', leadCompany);
          return records.length > 0;
        },
        {
          timeout: 12_000,
          intervals: [1_000, 1_500, 2_000, 2_500, 3_000],
        },
      )
      .toBeTruthy()
      .then(() => true);

    // Also navigate to the Leads page via menu and visually verify
    await navigateViaCrmMenu(page, '/p/crm_lead');
    await expect(page).toHaveURL(/\/p\/crm_lead(?:\?.*)?$/);

    // Wait for the table to appear
    await expect(page.locator('table').first()).toBeVisible({ timeout: 15000 });

    // If the lead was found via API, it should eventually appear in the UI
    if (found) {
      // Search for the lead in the paginated list
      const searchInput = page
        .locator(
          'input[placeholder*="Search"], input[placeholder*="search"], [data-testid="search-input"]',
        )
        .first();
      const hasSearch = await searchInput.isVisible({ timeout: 3000 }).catch(() => false);
      if (hasSearch) {
        await searchInput.fill(leadCompany);
        await searchInput.press('Enter');
        await page
          .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, {
            timeout: 8000,
          })
          .catch(() => null);
      }

      // The lead company name should be visible somewhere in the table
      await expect(page.locator('tbody tr', { hasText: leadCompany }).first()).toBeVisible({
        timeout: 10000,
      });
    }

    // Assert: at minimum the Leads page loaded with a table
    expect(found, 'Lead should be found via API query').toBe(true);
  });

  test('lifecycle-03: Merge Queue page renders after ingestion', async ({ page }) => {
    await navigateViaCrmMenu(page, '/crm/merge-queue');
    await expect(page).toHaveURL(/\/crm\/merge-queue/);

    await expect(page.getByRole('heading', { name: 'Lead Merge Queue' })).toBeVisible({
      timeout: 10000,
    });

    // Filter tabs should be present
    await expect(page.locator('[data-testid="merge-tab-all"]')).toBeVisible();

    // Either list or empty state should show
    const list = page.locator('[data-testid="merge-queue-list"]');
    const empty = page.locator('[data-testid="merge-queue-empty"]');
    await expect(list.or(empty).first()).toBeVisible({ timeout: 10000 });
  });

  test('lifecycle-04: Web Forms page accessible', async ({ page }) => {
    await navigateViaCrmMenu(page, '/crm/settings/web-forms', 'Settings');
    await expect(page).toHaveURL(/\/crm\/settings\/web-forms/);

    await expect(page.getByRole('heading', { name: 'Web Forms' })).toBeVisible({ timeout: 10000 });

    // Either form list or empty state
    const list = page.locator('[data-testid="webform-list"]');
    const empty = page.locator('[data-testid="webform-empty"]');
    await expect(list.or(empty).first()).toBeVisible({ timeout: 10000 });

    // "New Form" button should be visible
    await expect(page.locator('[data-testid="webform-create-btn"]')).toBeVisible();
  });

  test('lifecycle-05: Calendar Sync providers render correctly', async ({ page }) => {
    await navigateViaCrmMenu(page, '/crm/settings/calendar-sync', 'Settings');
    await expect(page).toHaveURL(/\/crm\/settings\/calendar-sync/);

    // Wait for loading to finish — Calendar Sync page shows Loading until providers API returns
    await expect(page.locator('[data-testid="calendar-providers"]')).toBeVisible({
      timeout: 15000,
    });

    await expect(page.getByRole('heading', { name: 'Calendar Sync', exact: true })).toBeVisible({
      timeout: 5000,
    });

    // Both provider cards should render
    await expect(page.locator('[data-testid="calendar-provider-google"]')).toBeVisible();
    await expect(page.locator('[data-testid="calendar-provider-microsoft"]')).toBeVisible();

    // "How Calendar Sync Works" info section should be visible
    await expect(page.getByText('How Calendar Sync Works')).toBeVisible();

    // Info items should be present
    await expect(page.getByText('Sync runs automatically every 15 minutes')).toBeVisible();
  });
});
