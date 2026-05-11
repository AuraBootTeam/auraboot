/**
 * Marketplace Publisher Review UI Tests
 *
 * Covers the operator-facing dynamic pages that approve publisher applications
 * and review plugin submissions, then verifies published submissions appear in
 * the public Marketplace discovery UI.
 */

import { test, expect, type APIRequestContext, type Page } from '../../fixtures';
import {
  clickRowActionByLocator,
  ensureSidebarExpanded,
  executeCommandViaApi,
  findRowInPaginatedList,
  waitForDynamicPageLoad,
} from '../helpers';

type JsonRecord = Record<string, unknown>;

test.describe.configure({ timeout: 60000 });

const ENTERPRISE_PLUGIN_ROOT =
  process.env.AURABOOT_ENTERPRISE_PLUGIN_ROOT ||
  '/Users/ghj/work/auraboot/auraboot-enterprise/plugins';
const MARKETPLACE_PLUGIN_DIR = `${ENTERPRISE_PLUGIN_ROOT}/marketplace-server`;

function isRecord(value: unknown): value is JsonRecord {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function recordsFromResponse(body: unknown): JsonRecord[] {
  const data = isRecord(body) && 'data' in body ? body.data : body;
  const records = isRecord(data) && 'records' in data ? data.records : data;
  return Array.isArray(records) ? records.filter(isRecord) : [];
}

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

async function navigateToModelListViaMenu(page: Page, href: string) {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const marketplaceRoot = nav.getByText('Marketplace', { exact: true }).first();
  if (await marketplaceRoot.isVisible({ timeout: 3000 }).catch(() => false)) {
    await marketplaceRoot.click();
  }
  const link = nav.locator(`a[href="${href}"]`).first();
  await link.waitFor({ state: 'visible', timeout: 10000 });
  await link.evaluate((el) => (el as HTMLAnchorElement).click());
  const escapedHref = href.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  await expect(page).toHaveURL(new RegExp(escapedHref), { timeout: 10000 });
  await waitForDynamicPageLoad(page);
}

async function createPublisherApplication(page: Page, suffix: string) {
  const created = await executeCommandViaApi(
    page,
    'mkt:create_publisher_application',
    {
      mkt_pa_applicant_name: `Review Applicant ${suffix}`,
      mkt_pa_company_name: `Review Publisher Co ${suffix}`,
      mkt_pa_email: `review-publisher-${suffix}@example.test`,
      mkt_pa_website: `https://publisher-${suffix}.example.test`,
      mkt_pa_description: 'E2E publisher application review seed',
      mkt_pa_portfolio_url: `https://publisher-${suffix}.example.test/portfolio`,
      mkt_pa_github_url: `https://github.com/auraboot/review-${suffix}`,
    },
    undefined,
    'create',
    { timeoutMs: 30000 },
  );
  expect(created.code, 'create publisher application command should succeed').toBe('0');
  expect(created.recordId, 'created publisher application should return a record id').toBeTruthy();
  return created.recordId;
}

async function createInReviewSubmission(page: Page, suffix: string, pluginName: string, pluginCode: string) {
  const created = await executeCommandViaApi(
    page,
    'mkt:create_plugin_submission',
    {
      mkt_ps_publisher_id: `REVIEW-PUBLISHER-ID-${suffix}`,
      mkt_ps_publisher_pid: `REVIEW-PUBLISHER-PID-${suffix}`,
      mkt_ps_plugin_name: pluginName,
      mkt_ps_plugin_code: pluginCode,
      mkt_ps_description: `E2E publisher review submission ${suffix}`,
      mkt_ps_category: 'utility',
      mkt_ps_package_url: `https://review-${suffix}.example.test/plugin.zip`,
      mkt_ps_version_code: '1.0.0',
      mkt_ps_release_notes: 'First reviewed release',
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

  const inReview = await executeCommandViaApi(
    page,
    'mkt:start_review_plugin_submission',
    {},
    created.recordId,
    'state_transition',
    { timeoutMs: 30000 },
  );
  expect(inReview.code, 'start review plugin submission command should succeed').toBe('0');

  return created.recordId;
}

async function openDetailFromCurrentList(page: Page, rowText: string, detailUrlPattern: RegExp) {
  const row = await findRowInPaginatedList(page, rowText);
  await clickRowActionByLocator(page, row, 'view', 'Detail');
  await expect(page).toHaveURL(detailUrlPattern, { timeout: 10000 });
  await waitForDynamicPageLoad(page);
}

async function searchMarketplaceDiscovery(page: Page, keyword: string) {
  await page.goto('/plugins?tab=discovery', { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('marketplace-categories')).toBeVisible({ timeout: 10000 });
  const response = page.waitForResponse(
    (resp) => resp.url().includes('/api/marketplace/plugins') && resp.status() === 200,
    { timeout: 10000 },
  );
  const searchInput = page
    .locator('input[placeholder*="Search"], input[placeholder*="搜索"]')
    .first();
  await searchInput.fill(keyword);
  await searchInput.press('Enter');
  await response.catch(() => null);
}

function waitForCommandResponse(page: Page, commandKeyword: string) {
  return page.waitForResponse(
    (resp) =>
      resp.url().includes('/api/meta/commands/execute/') &&
      resp.url().includes(commandKeyword),
    { timeout: 30000 },
  );
}

async function expectCommandResponseOk(response: Awaited<ReturnType<Page['waitForResponse']>>, label: string) {
  const body = await response.json().catch(async () => ({
    raw: await response.text().catch(() => ''),
  }));
  expect(
    response.ok() && String(body?.code ?? '') === '0',
    `${label} should succeed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBeTruthy();
}

async function expectMarketplacePluginByCode(page: Page, pluginCode: string, expected: boolean) {
  await expect
    .poll(
      async () => {
        const resp = await page.request.get(
          `/api/marketplace/plugins?keyword=${encodeURIComponent(pluginCode)}&sort=newest`,
          { timeout: 10000 },
        );
        if (!resp.ok()) return false;
        const records = recordsFromResponse(await resp.json());
        return records.some((record) => record.pluginId === pluginCode);
      },
      { timeout: 10000, intervals: [250, 500, 1000] },
    )
    .toBe(expected);
}

test.describe('Marketplace Publisher Review', () => {
  test.beforeEach(async ({ page }) => {
    await importMarketplaceServer(page.request);
  });

  test('approving a publisher application creates a verified publisher record', async ({ page }) => {
    const suffix = `pub-${Date.now().toString(36)}`;
    await createPublisherApplication(page, suffix);

    await navigateToModelListViaMenu(page, '/p/mkt_publisher_application');
    await openDetailFromCurrentList(
      page,
      `Review Publisher Co ${suffix}`,
      /\/p\/mkt_publisher_application\/view\//,
    );

    await expect(page.getByTestId('toolbar-btn-approve')).toBeVisible({ timeout: 10000 });
    const approveResponse = waitForCommandResponse(page, 'approve_publisher_application');
    await page.getByTestId('toolbar-btn-approve').click();
    await expectCommandResponseOk(await approveResponse, 'approve publisher application command');

    await expect(page.getByTestId('form-field-mkt_pa_status')).toContainText(/approved|已批准|已通过|批准|通过/, {
      timeout: 10000,
    });

    await navigateToModelListViaMenu(page, '/p/mkt_publisher');
    const publisherRow = await findRowInPaginatedList(page, `Review Publisher Co ${suffix}`);
    await expect(publisherRow).toContainText(/active|启用|活跃/, { timeout: 10000 });
  });

  test('approved submission can be published and appears in marketplace discovery', async ({ page }) => {
    const suffix = `sub-${Date.now().toString(36)}`;
    const pluginName = `Review Publish Plugin ${suffix}`;
    const pluginCode = `review-publish-${suffix}`;
    await createInReviewSubmission(page, suffix, pluginName, pluginCode);

    await navigateToModelListViaMenu(page, '/p/mkt_plugin_submission');
    await openDetailFromCurrentList(page, pluginName, /\/p\/mkt_plugin_submission\/view\//);

    await expect(page.getByTestId('toolbar-btn-approve')).toBeVisible({ timeout: 10000 });
    await page.getByTestId('toolbar-btn-approve').click();
    await expect(page.getByTestId('form-dialog')).toBeVisible({ timeout: 10000 });
    await page.getByTestId('form-dialog-field-notes').fill(`Approved by E2E ${suffix}`);
    const approveResponse = waitForCommandResponse(page, 'approve_plugin_submission');
    await page.getByTestId('form-dialog-submit').click();
    await expectCommandResponseOk(await approveResponse, 'approve plugin submission command');

    await page.reload({ waitUntil: 'domcontentloaded' });
    await waitForDynamicPageLoad(page);
    await expect(page.getByTestId('form-field-mkt_ps_status')).toContainText(/approved|已通过|通过/, {
      timeout: 10000,
    });

    await expect(page.getByTestId('toolbar-btn-publish')).toBeVisible({ timeout: 10000 });
    const publishResponse = waitForCommandResponse(page, 'publish_plugin_submission');
    await page.getByTestId('toolbar-btn-publish').click();
    await expectCommandResponseOk(await publishResponse, 'publish plugin submission command');

    await expectMarketplacePluginByCode(page, pluginCode, true);
    await searchMarketplaceDiscovery(page, pluginName);
    await expect(page.getByText(pluginName).first()).toBeVisible({ timeout: 10000 });
  });

  test('rejected submission does not appear in marketplace discovery', async ({ page }) => {
    const suffix = `rej-${Date.now().toString(36)}`;
    const pluginName = `Review Reject Plugin ${suffix}`;
    const pluginCode = `review-reject-${suffix}`;
    await createInReviewSubmission(page, suffix, pluginName, pluginCode);

    await navigateToModelListViaMenu(page, '/p/mkt_plugin_submission');
    await openDetailFromCurrentList(page, pluginName, /\/p\/mkt_plugin_submission\/view\//);

    await expect(page.getByTestId('toolbar-btn-reject')).toBeVisible({ timeout: 10000 });
    await page.getByTestId('toolbar-btn-reject').click();
    await expect(page.getByTestId('form-dialog')).toBeVisible({ timeout: 10000 });
    await page.getByTestId('form-dialog-field-notes').fill(`Rejected by E2E ${suffix}`);
    const rejectResponse = waitForCommandResponse(page, 'reject_plugin_submission');
    await page.getByTestId('form-dialog-submit').click();
    await expectCommandResponseOk(await rejectResponse, 'reject plugin submission command');

    await expect(page.getByTestId('form-field-mkt_ps_status')).toContainText(/rejected|已拒绝|拒绝/, {
      timeout: 10000,
    });

    await expectMarketplacePluginByCode(page, pluginCode, false);
    await searchMarketplaceDiscovery(page, pluginName);
    await expect(page.locator('.grid').filter({ hasText: pluginName })).not.toBeVisible({
      timeout: 5000,
    });
  });
});
