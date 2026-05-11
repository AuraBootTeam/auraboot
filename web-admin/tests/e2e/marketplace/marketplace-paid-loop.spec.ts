/**
 * Marketplace Paid Loop UI Tests
 *
 * Drives the Marketplace paid loop from the Discovery/detail UI. The test uses
 * the real local-test paid backend and intentionally avoids route mocks and
 * direct DB writes.
 */

import { test, expect } from '../../fixtures';
import type { APIRequestContext, Page } from '../../fixtures';
import { executeCommandViaApi } from '../helpers';

const ENTERPRISE_PLUGIN_ROOT =
  process.env.AURABOOT_ENTERPRISE_PLUGIN_ROOT ||
  '/Users/ghj/work/auraboot/auraboot-enterprise/plugins';
const MARKETPLACE_PLUGIN_DIR = `${ENTERPRISE_PLUGIN_ROOT}/marketplace-server`;

async function importMarketplaceServer(request: APIRequestContext) {
  const response = await request.post('/api/plugins/import/import-directory-sync', {
    data: {
      path: MARKETPLACE_PLUGIN_DIR,
      conflictStrategy: 'OVERWRITE',
      autoPublishModels: true,
      autoPublishFields: true,
      autoPublishCommands: true,
      autoPublishPages: true,
    },
    headers: { 'Content-Type': 'application/json' },
    timeout: 600_000,
  });
  const body = await response.json().catch(() => ({}));
  const data = body && typeof body.data === 'object' ? body.data : body;
  const success = response.ok() && (data?.success === true || body.success === true);
  expect(
    success,
    `marketplace-server import should succeed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBeTruthy();
}

async function navigateToMarketplace(page: Page) {
  await page.goto('/dashboards', { waitUntil: 'load' });
  const nav = page.locator('nav');
  const sysBtn = nav.getByRole('button', { name: /系统管理|System/ });
  await sysBtn.first().waitFor({ state: 'visible', timeout: 10000 });
  await sysBtn.first().evaluate((el: HTMLElement) => el.click());

  const menuLink = page.locator('a[href^="/plugins"]');
  await menuLink.first().waitFor({ state: 'visible', timeout: 10000 });
  await menuLink.first().evaluate((el) => (el as HTMLAnchorElement).click());

  await expect(page).toHaveURL(/\/plugins/, { timeout: 10000 });
  const discoveryTab = page.getByRole('tab', { name: /Discovery|发现/ });
  await discoveryTab.first().waitFor({ state: 'visible', timeout: 10000 });
  await discoveryTab.first().click();
  await expect(page.locator('[data-testid="marketplace-categories"]')).toBeVisible({
    timeout: 10000,
  });
}

async function publishPaidMarketplacePlugin(page: Page, suffix: string) {
  const pluginName = `Paid Loop Plugin ${suffix}`;
  const pluginCode = `paid-loop-${suffix}`;
  const created = await executeCommandViaApi(
    page,
    'mkt:create_plugin_submission',
    {
      mkt_ps_publisher_id: `PAID-LOOP-PUBLISHER-ID-${suffix}`,
      mkt_ps_publisher_pid: `PAID-LOOP-PUBLISHER-PID-${suffix}`,
      mkt_ps_plugin_name: pluginName,
      mkt_ps_plugin_code: pluginCode,
      mkt_ps_description: `E2E paid loop plugin ${suffix}`,
      mkt_ps_category: 'utility',
      mkt_ps_package_url: `https://paid-loop-${suffix}.example.test/plugin.zip`,
      mkt_ps_version_code: '1.0.0',
      mkt_ps_release_notes: 'First paid loop release',
    },
    undefined,
    'create',
    { timeoutMs: 30000 },
  );
  expect(created.code, 'create plugin submission command should succeed').toBe('0');
  expect(created.recordId, 'created plugin submission should return a record id').toBeTruthy();

  for (const [commandCode, operationType] of [
    ['mkt:submit_plugin_submission', 'state_transition'],
    ['mkt:start_review_plugin_submission', 'state_transition'],
    ['mkt:approve_plugin_submission', undefined],
    ['mkt:publish_plugin_submission', undefined],
  ] as const) {
    const result = await executeCommandViaApi(
      page,
      commandCode,
      commandCode.endsWith('approve_plugin_submission')
        ? { notes: `Approved by paid-loop E2E ${suffix}` }
        : {},
      created.recordId,
      operationType,
      { timeoutMs: 30000 },
    );
    expect(result.code, `${commandCode} command should succeed`).toBe('0');
  }

  await expect
    .poll(
      async () => {
        const resp = await page.request.get(
          `/api/marketplace/plugins?keyword=${encodeURIComponent(pluginCode)}&sort=newest`,
          { timeout: 10000 },
        );
        if (!resp.ok()) return false;
        const body = await resp.json().catch(() => ({}));
        const records = Array.isArray(body?.data) ? body.data : [];
        return records.some(
          (record: Record<string, unknown>) =>
            record.pluginId === pluginCode && record.licenseMode === 'vendor',
        );
      },
      { timeout: 10000, intervals: [250, 500, 1000] },
    )
    .toBe(true);

  return { pluginName, pluginCode };
}

async function searchMarketplace(page: Page, keyword: string) {
  const response = page.waitForResponse(
    (resp) => resp.url().includes('/api/marketplace/plugins') && resp.status() === 200,
    { timeout: 10000 },
  );
  const searchInput = page.locator('input[placeholder*="Search"], input[placeholder*="搜索"]').first();
  await searchInput.fill(keyword);
  await searchInput.press('Enter');
  await response.catch(() => null);
}

test.describe('Marketplace Paid Loop UI', () => {
  test.beforeEach(async ({ page }) => {
    await importMarketplaceServer(page.request);
  });

  test('detail checkout completes local payment, token redeem and revoke from UI', async ({
    page,
  }) => {
    const suffix = Date.now().toString(36);
    const paidPlugin = await publishPaidMarketplacePlugin(page, suffix);

    await navigateToMarketplace(page);
    await searchMarketplace(page, paidPlugin.pluginName);

    const paidCta = page.getByTestId('marketplace-card-paid-cta').first();
    await expect(paidCta).toBeVisible({ timeout: 10000 });
    await paidCta.click();
    await page.waitForLoadState('domcontentloaded');
    await expect(page).toHaveURL(new RegExp(`/plugins/${paidPlugin.pluginCode}`), {
      timeout: 10000,
    });
    await expect(page.getByTestId('marketplace-direct-install-open')).toHaveCount(0);

    await page.getByTestId('marketplace-paid-checkout-open').click();

    const checkoutCall = page.waitForResponse(
      (resp) => resp.url().includes('/api/marketplace/paid/checkout') && resp.status() === 200,
    );
    await page.getByTestId('marketplace-paid-buy').click();
    await checkoutCall;

    const result = page.getByTestId('marketplace-paid-result');
    await expect(result).toContainText(/Token redeemed|令牌已兑换/, { timeout: 15000 });
    await expect(result).toContainText(/purchasePid:/);
    await expect(result).toContainText(/tokenPid:/);

    const revokeCall = page.waitForResponse(
      (resp) =>
        resp.url().includes('/api/marketplace/paid/purchases/revoke') && resp.status() === 200,
    );
    await page.getByTestId('marketplace-paid-revoke').click();
    await revokeCall;

    await expect(result).toContainText(/Purchase revoked|购买已撤销/, { timeout: 10000 });
  });
});
