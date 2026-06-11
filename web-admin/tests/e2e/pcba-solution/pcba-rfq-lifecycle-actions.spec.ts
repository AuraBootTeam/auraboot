/**
 * PCBA ERP — RFQ lifecycle actions (A2-S2: customer-request + PCBA sidecar)
 *
 * Coverage:
 * - Sidebar entry: PCBA RFQ sidecar list (crm_customer_request_pcba_rfq) is opened
 *   from the PCBA Sales-to-Order IA
 * - Sidecar list tabs: all / dfm_pending / bom_confirmed
 * - Row actions: the DFM gate commands appear only for matching crm_crq_dfm_status
 *   (pending → request_dfm; in_review → pass_dfm / conditional_dfm / fail_dfm)
 * - One real UI transition: request_dfm on a pending row moves it out of the
 *   dfm_pending tab (pending → in_review)
 *
 * The legacy RFQ model was decommissioned (A2). The request lifecycle (draft →
 * submitted → routed → …) lives on crm_customer_request; this spec seeds it via
 * crm:create_customer_request → crm:submit_customer_request →
 * pe:route_customer_request_to_rfq (the handler auto-creates the sidecar) and then
 * drives the sidecar's DFM gate.
 */

import { expect, test, type APIRequestContext, type Page } from '../../fixtures';
import type { Locator } from '@playwright/test';
import path from 'node:path';
import { ErrorCodes } from '~/shared/services/http-client/types';
import {
  ensureSidebarExpanded,
  executeCommandViaApi,
  uniqueId,
  waitForDynamicPageLoad,
  waitForTableHydration,
} from '../helpers/index';

type DfmStatus = 'pending' | 'in_review' | 'passed';

const NAV_TIMEOUT = 15_000;
const ENTERPRISE_PLUGIN_ROOT =
  process.env.ENTERPRISE_PLUGIN_ROOT ?? path.resolve(process.cwd(), '../../../auraboot-enterprise/plugins');
const REQUIRED_PLUGINS = ['pcba-solution', 'crm', 'pcba-crm', 'pcba-sales'];

const RFQ_MODEL = 'crm_customer_request_pcba_rfq';

const RFQ_ENTRY = {
  href: `/p/${RFQ_MODEL}`,
  label: /客户需求-PCBA RFQ|Customer Requests \(PCBA RFQ\)|RFQ/i,
  parentLabel: /销售到订单|Sales To Order/i,
  route: /\/p\/crm_customer_request_pcba_rfq(?:$|[?#])/,
};

async function importPluginDirectory(request: APIRequestContext, pluginName: string): Promise<void> {
  const response = await request.post('/api/plugins/import/import-directory-sync', {
    data: {
      path: `${ENTERPRISE_PLUGIN_ROOT}/${pluginName}`,
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
  const data = body?.data ?? body;
  const success = response.ok() && (data?.success === true || body?.success === true);
  expect(
    success,
    `${pluginName} import should succeed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
}

function rfqLink(nav: Locator): Locator {
  const byHref = nav.locator(`a[href="${RFQ_ENTRY.href}"], a[href$="${RFQ_ENTRY.href}"]`);
  const byLabel = byHref.filter({ hasText: RFQ_ENTRY.label });
  return byLabel.or(byHref).first();
}

async function clickIfVisible(locator: Locator): Promise<boolean> {
  const visible = await locator.isVisible({ timeout: 1_000 }).catch(() => false);
  if (!visible) return false;
  await locator.scrollIntoViewIfNeeded();
  await locator.evaluate((el: HTMLElement) => el.click());
  return true;
}

async function openRfqFromSidebar(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);

  const nav = page.locator('nav, aside, [role="navigation"]').first();
  await nav.waitFor({ state: 'visible', timeout: NAV_TIMEOUT });

  let leaf = rfqLink(nav);
  if (!(await leaf.isVisible({ timeout: 1_000 }).catch(() => false))) {
    await clickIfVisible(
      nav
        .getByRole('button', { name: /PCBA ERP|PCBA|电子制造/i })
        .or(nav.getByRole('menuitem', { name: /PCBA ERP|PCBA|电子制造/i }))
        .or(nav.getByRole('link', { name: /PCBA ERP|PCBA|电子制造/i }))
        .or(nav.locator('text=/PCBA ERP|PCBA|电子制造/i'))
        .first(),
    );
  }

  leaf = rfqLink(nav);
  if (!(await leaf.isVisible({ timeout: 1_000 }).catch(() => false))) {
    await clickIfVisible(
      nav
        .getByRole('button', { name: RFQ_ENTRY.parentLabel })
        .or(nav.getByRole('menuitem', { name: RFQ_ENTRY.parentLabel }))
        .or(nav.getByRole('link', { name: RFQ_ENTRY.parentLabel }))
        .or(nav.locator('button, [role="menuitem"], a').filter({ hasText: RFQ_ENTRY.parentLabel }))
        .first(),
    );
  }

  leaf = rfqLink(nav);
  await leaf.waitFor({ state: 'visible', timeout: NAV_TIMEOUT });

  const listResponse = page
    .waitForResponse(
      (response) =>
        response.url().includes(`/api/dynamic/${RFQ_MODEL}`) &&
        response.url().includes('list') &&
        response.status() === 200,
      { timeout: NAV_TIMEOUT },
    )
    .catch(() => null);

  await leaf.scrollIntoViewIfNeeded();
  await leaf.evaluate((el: HTMLElement) => el.click());
  await expect(page).toHaveURL(RFQ_ENTRY.route, { timeout: NAV_TIMEOUT });
  await listResponse;
  await waitForDynamicPageLoad(page, NAV_TIMEOUT);
  await waitForTableHydration(page, { timeout: NAV_TIMEOUT });
}

/**
 * Seed one customer request routed into a PCBA RFQ sidecar with the requested DFM
 * gate state. Returns the product model (= request title, copied onto the sidecar
 * by the route handler) used to find the row in the sidecar list.
 */
async function createSidecarInDfmStatus(page: Page, status: DfmStatus): Promise<string> {
  const productModel = `E2E RFQ ${status} ${uniqueId()}`;

  // The route handler refuses requests without an account, so seed one first.
  const accountResult = await executeCommandViaApi(
    page,
    'crm:create_account',
    { crm_acc_name: `E2E RFQ Account ${status} ${uniqueId()}` },
    undefined,
    'create',
    { allowHttpError: true, timeoutMs: 30_000 },
  );
  expect(accountResult.code, `create account for ${status}`).toBe(ErrorCodes.SUCCESS);

  const createResult = await executeCommandViaApi(
    page,
    'crm:create_customer_request',
    {
      crm_cr_title: productModel,
      crm_cr_account_id: accountResult.recordId,
      crm_cr_type: 'rfq',
      crm_cr_summary: `Lifecycle action E2E seed for DFM ${status}`,
    },
    undefined,
    'create',
    { allowHttpError: true, timeoutMs: 30_000 },
  );
  expect(createResult.code, `create customer request for ${status}`).toBe(ErrorCodes.SUCCESS);
  expect(createResult.recordId, `create customer request for ${status} must return recordId`).toBeTruthy();

  const submitResult = await executeCommandViaApi(
    page,
    'crm:submit_customer_request',
    {},
    createResult.recordId,
    'update',
    { allowHttpError: true, timeoutMs: 30_000 },
  );
  expect(submitResult.code, `submit customer request for ${status}`).toBe(ErrorCodes.SUCCESS);

  const routeResult = await executeCommandViaApi(
    page,
    'pe:route_customer_request_to_rfq',
    {},
    createResult.recordId,
    'update',
    { allowHttpError: true, timeoutMs: 30_000 },
  );
  expect(routeResult.code, `route customer request for ${status}`).toBe(ErrorCodes.SUCCESS);

  // Resolve the sidecar pid written back to the request by the route handler.
  const requestResp = await page.request.get(
    `/api/dynamic/crm_customer_request/${createResult.recordId}`,
  );
  expect(requestResp.ok(), 'routed customer request should be readable').toBe(true);
  const requestBody = await requestResp.json();
  const requestRecord = (requestBody.data ?? requestBody) as Record<string, unknown>;
  const sidecarId = String(requestRecord.crm_cr_routed_object_id ?? '');
  expect(sidecarId, `route for ${status} must create the PCBA RFQ sidecar`).toBeTruthy();

  if (status === 'in_review' || status === 'passed') {
    const requestDfm = await executeCommandViaApi(
      page,
      'pe:request_dfm_pcba_rfq',
      {},
      sidecarId,
      'update',
      { allowHttpError: true, timeoutMs: 30_000 },
    );
    expect(requestDfm.code, `request DFM for ${status}`).toBe(ErrorCodes.SUCCESS);
  }

  if (status === 'passed') {
    const passDfm = await executeCommandViaApi(
      page,
      'pe:pass_dfm_pcba_rfq',
      {},
      sidecarId,
      'update',
      { allowHttpError: true, timeoutMs: 30_000 },
    );
    expect(passDfm.code, 'pass DFM').toBe(ErrorCodes.SUCCESS);
  }

  return productModel;
}

async function selectStatusTab(page: Page, tabKey: string): Promise<void> {
  const listResponse = page
    .waitForResponse(
      (response) =>
        response.url().includes(`/api/dynamic/${RFQ_MODEL}`) &&
        response.url().includes('list') &&
        response.status() === 200,
      { timeout: NAV_TIMEOUT },
    )
    .catch(() => null);

  await page.getByTestId(`tab-${tabKey}`).click();
  await listResponse;
  await waitForTableHydration(page, { timeout: NAV_TIMEOUT });
}

async function expectRowActions(
  page: Page,
  productModel: string,
  expectedMoreActions: string[],
  absentActions: string[],
): Promise<void> {
  const row = page.locator('tbody tr', { hasText: productModel }).first();
  await expect(row, `row for ${productModel} must be visible`).toBeVisible({ timeout: NAV_TIMEOUT });

  await expect(row.getByTestId('row-action-view')).toBeVisible({ timeout: NAV_TIMEOUT });
  await row.getByTestId('row-action-more').click();

  const dropdown = page.getByTestId('row-action-dropdown');
  await expect(dropdown).toBeVisible({ timeout: NAV_TIMEOUT });
  for (const action of expectedMoreActions) {
    await expect(dropdown.getByTestId(`row-action-${action}`), `${action} must be visible`).toBeVisible();
  }
  for (const action of absentActions) {
    await expect(dropdown.getByTestId(`row-action-${action}`), `${action} must not be visible`).toHaveCount(0);
  }

  await page.mouse.click(4, 4);
  await expect(dropdown).not.toBeVisible({ timeout: 2_000 });
}

/** Drive request_dfm on a pending row; it must leave the dfm_pending tab. */
async function requestDfmFromRow(page: Page, productModel: string): Promise<void> {
  const row = page.locator('tbody tr', { hasText: productModel }).first();
  await expect(row, `pending row for ${productModel} must be visible before request_dfm`).toBeVisible({
    timeout: NAV_TIMEOUT,
  });

  await row.getByTestId('row-action-more').click();
  const dropdown = page.getByTestId('row-action-dropdown');
  await expect(dropdown).toBeVisible({ timeout: NAV_TIMEOUT });
  await dropdown.getByTestId('row-action-request_dfm').click();

  const dialog = page.getByTestId('confirm-dialog');
  await expect(dialog).toBeVisible({ timeout: NAV_TIMEOUT });
  await expect(dialog).toContainText(/DFM|确认/i);

  const commandResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/meta/commands/execute/') &&
      response.url().includes('request_dfm_pcba_rfq') &&
      response.status() === 200,
    { timeout: NAV_TIMEOUT },
  );
  const listResponse = page
    .waitForResponse(
      (response) =>
        response.url().includes(`/api/dynamic/${RFQ_MODEL}`) &&
        response.url().includes('list') &&
        response.status() === 200,
      { timeout: NAV_TIMEOUT },
    )
    .catch(() => null);

  await page.getByTestId('confirm-ok').click();
  await commandResponse;
  await listResponse;
  await waitForTableHydration(page, { timeout: NAV_TIMEOUT });

  await expect(page.locator('tbody tr', { hasText: productModel })).toHaveCount(0, {
    timeout: NAV_TIMEOUT,
  });
}

test.describe('PCBA ERP — RFQ lifecycle actions @critical', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(120_000);

  test.beforeAll(async ({ request }, testInfo) => {
    testInfo.setTimeout(180_000);
    for (const pluginName of REQUIRED_PLUGINS) {
      await importPluginDirectory(request, pluginName);
    }
  });

  test('PCBA-RFQ-01: sidebar RFQ list exposes DFM tabs and gate row actions', async ({ page }) => {
    const seeded = {
      pending: await createSidecarInDfmStatus(page, 'pending'),
      inReview: await createSidecarInDfmStatus(page, 'in_review'),
      passed: await createSidecarInDfmStatus(page, 'passed'),
    };

    await openRfqFromSidebar(page);

    for (const tabKey of ['all', 'dfm_pending', 'bom_confirmed']) {
      await expect(page.getByTestId(`tab-${tabKey}`), `${tabKey} tab must be visible`).toBeVisible({
        timeout: NAV_TIMEOUT,
      });
    }

    // pending row: only request_dfm of the gate commands (edit is always available)
    await selectStatusTab(page, 'dfm_pending');
    await expectRowActions(page, seeded.pending, ['request_dfm', 'edit'], [
      'pass_dfm',
      'conditional_dfm',
      'fail_dfm',
    ]);

    // in_review row: the three conclusions, request_dfm gone
    await selectStatusTab(page, 'all');
    await expectRowActions(page, seeded.inReview, ['pass_dfm', 'conditional_dfm', 'fail_dfm', 'edit'], [
      'request_dfm',
    ]);

    // passed row: no gate commands at all
    await expectRowActions(page, seeded.passed, ['edit'], [
      'request_dfm',
      'pass_dfm',
      'conditional_dfm',
      'fail_dfm',
    ]);

    // drive pending → in_review through the real row action; the row must leave
    // the dfm_pending tab once its DFM status moves on
    await selectStatusTab(page, 'dfm_pending');
    await requestDfmFromRow(page, seeded.pending);
  });
});
