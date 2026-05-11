/**
 * Marketplace Operator Purchase Detail Tests
 *
 * Covers the DSL-configured purchase detail operator workflow. Test data is
 * created through the real paid API; refund is triggered from the UI detail
 * toolbar with a required reason dialog.
 */

import { test, expect } from '../../fixtures';
import type { APIRequestContext, Page } from '../../fixtures';
import {
  clickRowActionByLocator,
  ensureSidebarExpanded,
  findRowByContent,
  waitForDynamicPageLoad,
} from '../helpers';

type JsonRecord = Record<string, unknown>;

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

async function postPaid(request: APIRequestContext, path: string, payload: JsonRecord) {
  const response = await request.post(`/api/marketplace/paid${path}`, {
    data: payload,
    headers: { 'Content-Type': 'application/json' },
  });
  const body = await response.json().catch(async () => ({ raw: await response.text().catch(() => '') }));
  expect(
    response.ok(),
    `paid API ${path} should succeed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
  return (body.data || body) as JsonRecord;
}

async function createActivePurchase(page: Page, suffix: string) {
  const checkout = await postPaid(page.request, '/checkout', {
    pluginPid: `E2E-PLUGIN-${suffix}`,
    pricingPlanPid: `E2E-PLAN-${suffix}`,
    buyerTenantPid: `E2E-TENANT-${suffix}`,
    amount: 49.99,
    currency: 'usd',
    idempotencyKey: `ui-operator-${suffix}`,
  });
  await postPaid(page.request, '/payment-events/local-test', {
    purchasePid: checkout.purchasePid,
    provider: 'local_test',
    providerPaymentId: `local_test:operator-payment-${suffix}`,
    eventId: `operator-event-${suffix}`,
    eventType: 'payment_confirmed',
    idempotencyKey: `operator-payment-${suffix}`,
  });
  return checkout;
}

async function navigateToPurchasesViaMenu(page: Page) {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const marketplaceRoot = nav.getByText('Marketplace', { exact: true }).first();
  if (await marketplaceRoot.isVisible({ timeout: 3000 }).catch(() => false)) {
    await marketplaceRoot.click();
  }
  const purchaseLink = nav.locator('a[href="/p/mkt_purchase"]').first();
  await purchaseLink.waitFor({ state: 'visible', timeout: 10000 });
  await purchaseLink.click();
  await expect(page).toHaveURL(/\/p\/mkt_purchase/, { timeout: 10000 });
  await waitForDynamicPageLoad(page);
}

test.describe('Marketplace Operator Purchase Detail', () => {
  test.beforeEach(async ({ page }) => {
    await importMarketplaceServer(page.request);
  });

  test('refund from purchase detail requires reason and records audit fields', async ({ page }) => {
    const suffix = `op-${Date.now().toString(36)}`;
    await createActivePurchase(page, suffix);

    await navigateToPurchasesViaMenu(page);
    const row = await findRowByContent(page, `E2E-PLUGIN-${suffix}`);
    await clickRowActionByLocator(page, row, 'view', 'Detail');
    await expect(page).toHaveURL(/\/p\/mkt_purchase\/view\//, { timeout: 10000 });
    await waitForDynamicPageLoad(page);

    await expect(page.getByTestId('toolbar-btn-refund')).toBeVisible({ timeout: 10000 });
    await expect(page.getByTestId('toolbar-btn-revoke')).toBeVisible({ timeout: 10000 });

    const reason = `Operator refund reason ${suffix}`;
    const refundResponse = page.waitForResponse(
      (resp) => resp.url().includes('/api/marketplace/paid/purchases/refund') && resp.status() === 200,
    );
    await page.getByTestId('toolbar-btn-refund').click();
    await expect(page.getByTestId('form-dialog')).toBeVisible({ timeout: 10000 });
    await page.getByTestId('form-dialog-field-reason').fill(reason);
    await page.getByTestId('form-dialog-field-providerRefundReference').fill(`re-ui-${suffix}`);
    await page.getByTestId('form-dialog-submit').click();
    await refundResponse;

    await expect(page.getByTestId('form-field-mkt_pur_status')).toContainText(/refunded|已退款/, {
      timeout: 10000,
    });
    await expect(page.getByTestId('form-field-mkt_pur_refund_reason')).toContainText(reason);
    await expect(page.getByTestId('form-field-mkt_pur_provider_refund_reference')).toContainText(`re-ui-${suffix}`);
    await expect(page.getByTestId('form-field-mkt_pur_last_operator_action')).toContainText('refunded');
  });

  test('revoke from purchase detail requires reason and records audit fields', async ({ page }) => {
    const suffix = `op-revoke-${Date.now().toString(36)}`;
    await createActivePurchase(page, suffix);

    await navigateToPurchasesViaMenu(page);
    const row = await findRowByContent(page, `E2E-PLUGIN-${suffix}`);
    await clickRowActionByLocator(page, row, 'view', 'Detail');
    await expect(page).toHaveURL(/\/p\/mkt_purchase\/view\//, { timeout: 10000 });
    await waitForDynamicPageLoad(page);

    await expect(page.getByTestId('toolbar-btn-revoke')).toBeVisible({ timeout: 10000 });

    const reason = `Operator revoke reason ${suffix}`;
    const revokeResponse = page.waitForResponse(
      (resp) => resp.url().includes('/api/marketplace/paid/purchases/revoke') && resp.status() === 200,
    );
    await page.getByTestId('toolbar-btn-revoke').click();
    await expect(page.getByTestId('form-dialog')).toBeVisible({ timeout: 10000 });
    await page.getByTestId('form-dialog-field-reason').fill(reason);
    await page.getByTestId('form-dialog-submit').click();
    await revokeResponse;

    await expect(page.getByTestId('form-field-mkt_pur_status')).toContainText(/revoked|已撤销/, {
      timeout: 10000,
    });
    await expect(page.getByTestId('form-field-mkt_pur_revoke_reason')).toContainText(reason);
    await expect(page.getByTestId('form-field-mkt_pur_last_operator_action')).toContainText('revoked');
    await expect(page.getByTestId('form-field-mkt_pur_last_operator_reason')).toContainText(reason);
  });
});
