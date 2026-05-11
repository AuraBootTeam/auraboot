/**
 * Marketplace Operations Dashboard Tests
 *
 * Uses real paid APIs and the imported marketplace-server dashboard config.
 * The dashboard must render persisted purchase/provider-event data and expose
 * a drill-down path into the provider event list.
 */

import { test, expect } from '../../fixtures';
import type { APIRequestContext, Page } from '../../fixtures';
import { executeCommandViaApi } from '../helpers';

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

async function createDashboardPurchase(page: Page, suffix: string) {
  const checkout = await postPaid(page.request, '/checkout', {
    pluginPid: `OPS-PLUGIN-${suffix}`,
    pricingPlanPid: `OPS-PLAN-${suffix}`,
    buyerTenantPid: `OPS-TENANT-${suffix}`,
    amount: 59.99,
    currency: 'usd',
    idempotencyKey: `ops-${suffix}`,
  });
  await postPaid(page.request, '/payment-events/local-test', {
    purchasePid: checkout.purchasePid,
    provider: 'local_test',
    providerPaymentId: `local_test:ops-payment-${suffix}`,
    eventId: `ops-event-${suffix}`,
    eventType: 'payment_confirmed',
    idempotencyKey: `ops-payment-${suffix}`,
  });
  await postPaid(page.request, '/install-tokens', {
    purchasePid: checkout.purchasePid,
    versionPid: `OPS-VERSION-${suffix}`,
    targetInstanceUrl: `https://ops-${suffix}.example.test`,
    ttlHours: 24,
  });
  return checkout;
}

async function createReviewQueueSubmission(page: Page, suffix: string) {
  const created = await executeCommandViaApi(
    page,
    'mkt:create_plugin_submission',
    {
      mkt_ps_publisher_id: `OPS-PUBLISHER-ID-${suffix}`,
      mkt_ps_publisher_pid: `OPS-PUBLISHER-PID-${suffix}`,
      mkt_ps_plugin_name: `Ops Review Plugin ${suffix}`,
      mkt_ps_plugin_code: `ops-review-${suffix}`,
      mkt_ps_description: 'E2E operations dashboard review queue seed',
      mkt_ps_category: 'utility',
      mkt_ps_package_url: `https://ops-${suffix}.example.test/plugin.zip`,
      mkt_ps_version_code: '1.0.0',
      mkt_ps_release_notes: 'E2E seed',
    },
    undefined,
    'create',
    { timeoutMs: 30000 },
  );
  expect(created.code, 'create plugin submission command should succeed').toBe('0');
  expect(created.recordId, 'created plugin submission should return a record id').toBeTruthy();

  const submitted = await executeCommandViaApi(
    page,
    'mkt:submit_plugin_submission',
    {},
    created.recordId,
    'state_transition',
    { timeoutMs: 30000 },
  );
  expect(submitted.code, 'submit plugin submission command should succeed').toBe('0');
}

async function createPublisherApplication(page: Page, suffix: string) {
  const created = await executeCommandViaApi(
    page,
    'mkt:create_publisher_application',
    {
      mkt_pa_applicant_name: `Ops Publisher ${suffix}`,
      mkt_pa_company_name: `Ops Publisher Co ${suffix}`,
      mkt_pa_email: `ops-publisher-${suffix}@example.test`,
      mkt_pa_website: `https://publisher-${suffix}.example.test`,
      mkt_pa_description: 'E2E operations dashboard publisher application seed',
      mkt_pa_portfolio_url: `https://publisher-${suffix}.example.test/portfolio`,
      mkt_pa_github_url: `https://github.com/auraboot/ops-${suffix}`,
    },
    undefined,
    'create',
    { timeoutMs: 30000 },
  );
  expect(created.code, 'create publisher application command should succeed').toBe('0');
  expect(created.recordId, 'created publisher application should return a record id').toBeTruthy();
}

function waitForNamedQueryResponse(page: Page, queryCode: string) {
  return page.waitForResponse(
    (resp) =>
      resp.url().includes('/api/meta/chart-data') &&
      resp.status() === 200 &&
      (resp.request().postData() || '').includes(`"queryCode":"${queryCode}"`),
    { timeout: 15000 },
  );
}

async function expectNamedQueryRows(
  responsePromise: Promise<Awaited<ReturnType<Page['waitForResponse']>>>,
  queryCode: string,
  numericField?: string,
): Promise<JsonRecord[]> {
  const response = await responsePromise;
  const body = await response.json();
  const rows = body?.data?.rows || body?.rows || [];
  expect(Array.isArray(rows), `${queryCode} chart-data rows should be an array`).toBe(true);
  expect(rows.length, `${queryCode} chart-data should return non-empty rows`).toBeGreaterThan(0);
  if (numericField) {
    const total = rows.reduce((sum: number, row: JsonRecord) => sum + Number(row[numericField] || 0), 0);
    expect(total, `${queryCode}.${numericField} should be greater than 0`).toBeGreaterThan(0);
  }
  return rows as JsonRecord[];
}

test.describe('Marketplace Operations Dashboard', () => {
  test.beforeEach(async ({ page }) => {
    await importMarketplaceServer(page.request);
  });

  test('renders paid operations data and drills into provider events', async ({ page }) => {
    const suffix = `dash-${Date.now().toString(36)}`;
    await createDashboardPurchase(page, suffix);
    await createReviewQueueSubmission(page, suffix);
    await createPublisherApplication(page, suffix);

    const tokenStatusResponse = waitForNamedQueryResponse(page, 'mkt_token_status_counts');
    const reviewQueueResponse = waitForNamedQueryResponse(page, 'mkt_publisher_review_queue');
    const applicationQueueResponse = waitForNamedQueryResponse(page, 'mkt_publisher_application_review_queue');
    const providerEventsResponse = waitForNamedQueryResponse(page, 'mkt_recent_provider_events');

    await page.goto('/dashboards/view/mkt_operations_dashboard', { waitUntil: 'domcontentloaded' });
    await expect(page.getByText('Marketplace Operations Dashboard')).toBeVisible({ timeout: 15000 });
    await expect(page.getByText(/购买总数|Total Purchases/).first()).toBeVisible({ timeout: 15000 });
    await expect(page.getByText(/最近支付事件|Recent Provider Events/).first()).toBeVisible({ timeout: 15000 });
    await expect(page.getByText(/发布者申请审核|Publisher Applications/).first()).toBeVisible({ timeout: 15000 });
    await expectNamedQueryRows(tokenStatusResponse, 'mkt_token_status_counts', 'token_count');
    await expectNamedQueryRows(reviewQueueResponse, 'mkt_publisher_review_queue');
    await expectNamedQueryRows(applicationQueueResponse, 'mkt_publisher_application_review_queue');
    const providerEventRows = await expectNamedQueryRows(providerEventsResponse, 'mkt_recent_provider_events');

    const purchasePid = String(providerEventRows.find((row) => row.mkt_evt_purchase_pid)?.mkt_evt_purchase_pid || '');
    expect(purchasePid, 'provider event response should include a purchase pid for table drill-down').toBeTruthy();
    const providerEventRow = page.locator('tr', { hasText: purchasePid }).first();
    await expect(providerEventRow).toBeVisible({ timeout: 15000 });
    await providerEventRow.click();
    await expect(page).toHaveURL(/\/p\/mkt_provider_event/, { timeout: 10000 });
  });
});
