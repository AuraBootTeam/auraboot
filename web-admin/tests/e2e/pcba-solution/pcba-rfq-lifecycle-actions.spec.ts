/**
 * PCBA ERP — RFQ lifecycle actions
 *
 * Coverage:
 * - Sidebar entry: RFQ list is opened from PCBA Sales-to-Order IA
 * - RFQ status tabs: draft, submitted, clarification, finalized, quoted, cancelled
 * - Row actions: lifecycle commands appear only for matching RFQ states
 */

import { expect, test, type APIRequestContext, type Page } from '../../fixtures';
import type { Locator } from '@playwright/test';
import { ErrorCodes } from '~/shared/services/http-client/types';
import {
  ensureSidebarExpanded,
  executeCommandViaApi,
  uniqueId,
  waitForDynamicPageLoad,
  waitForTableHydration,
} from '../helpers/index';

type RfqStatus = 'draft' | 'submitted' | 'clarification' | 'finalized';

const NAV_TIMEOUT = 15_000;
const ENTERPRISE_PLUGIN_ROOT = '/Users/ghj/work/auraboot/auraboot-enterprise/plugins';
const REQUIRED_PLUGINS = ['pcba-solution', 'pcba-crm'];

const RFQ_ENTRY = {
  href: '/p/pe_rfq',
  label: /询价单|RFQ/i,
  parentLabel: /销售到订单|Sales To Order/i,
  route: /\/p\/pe_rfq(?:$|[?#])/,
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
        response.url().includes('/api/dynamic/pe_rfq') &&
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

async function createRfqInStatus(page: Page, status: RfqStatus): Promise<string> {
  const productModel = `E2E RFQ ${status} ${uniqueId()}`;
  const createResult = await executeCommandViaApi(
    page,
    'pe:create_rfq',
    {
      pe_rfq_product_model: productModel,
      pe_rfq_quantity: 500,
      pe_rfq_delivery_window: '21 days',
      pe_rfq_quality_class: 'class_2',
      pe_rfq_trace_level: 'l1_batch',
      pe_rfq_supply_mode: 'turnkey',
      pe_rfq_revision: 'A',
      pe_rfq_notes: `Lifecycle action E2E seed for ${status}`,
    },
    undefined,
    'create',
    { allowHttpError: true, timeoutMs: 30_000 },
  );
  expect(createResult.code, `create RFQ for ${status}`).toBe(ErrorCodes.SUCCESS);
  expect(createResult.recordId, `create RFQ for ${status} must return recordId`).toBeTruthy();

  if (status === 'submitted' || status === 'clarification' || status === 'finalized') {
    const submitResult = await executeCommandViaApi(
      page,
      'pe:submit_rfq',
      {},
      createResult.recordId,
      'update',
      { allowHttpError: true, timeoutMs: 30_000 },
    );
    expect(submitResult.code, `submit RFQ for ${status}`).toBe(ErrorCodes.SUCCESS);
  }

  if (status === 'clarification') {
    const clarifyResult = await executeCommandViaApi(
      page,
      'pe:clarify_rfq',
      {},
      createResult.recordId,
      'update',
      { allowHttpError: true, timeoutMs: 30_000 },
    );
    expect(clarifyResult.code, 'clarify RFQ').toBe(ErrorCodes.SUCCESS);
  }

  if (status === 'finalized') {
    const finalizeResult = await executeCommandViaApi(
      page,
      'pe:finalize_rfq',
      {},
      createResult.recordId,
      'update',
      { allowHttpError: true, timeoutMs: 30_000 },
    );
    expect(finalizeResult.code, 'finalize RFQ').toBe(ErrorCodes.SUCCESS);
  }

  return productModel;
}

async function selectStatusTab(page: Page, status: string): Promise<void> {
  const listResponse = page
    .waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/pe_rfq') &&
        response.url().includes('list') &&
        response.status() === 200,
      { timeout: NAV_TIMEOUT },
    )
    .catch(() => null);

  await page.getByTestId(`tab-${status}`).click();
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

async function submitDraftRfqFromRow(page: Page, productModel: string): Promise<void> {
  const row = page.locator('tbody tr', { hasText: productModel }).first();
  await expect(row, `draft row for ${productModel} must be visible before submit`).toBeVisible({
    timeout: NAV_TIMEOUT,
  });

  await row.getByTestId('row-action-more').click();
  const dropdown = page.getByTestId('row-action-dropdown');
  await expect(dropdown).toBeVisible({ timeout: NAV_TIMEOUT });
  await dropdown.getByTestId('row-action-submit').click();

  const dialog = page.getByTestId('confirm-dialog');
  await expect(dialog).toBeVisible({ timeout: NAV_TIMEOUT });
  await expect(dialog).toContainText(/确认提交|Confirm/i);

  const commandResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/meta/commands/execute/') &&
      response.url().includes('submit_rfq') &&
      response.status() === 200,
    { timeout: NAV_TIMEOUT },
  );
  const listResponse = page
    .waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/pe_rfq') &&
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

  test('PCBA-RFQ-01: sidebar RFQ list exposes status tabs and lifecycle row actions', async ({ page }) => {
    const seeded = {
      draft: await createRfqInStatus(page, 'draft'),
      submitted: await createRfqInStatus(page, 'submitted'),
      clarification: await createRfqInStatus(page, 'clarification'),
      finalized: await createRfqInStatus(page, 'finalized'),
    };

    await openRfqFromSidebar(page);

    for (const status of ['draft', 'submitted', 'clarification', 'finalized', 'quoted', 'cancelled']) {
      await expect(page.getByTestId(`tab-${status}`), `${status} tab must be visible`).toBeVisible({
        timeout: NAV_TIMEOUT,
      });
    }

    await selectStatusTab(page, 'draft');
    await expectRowActions(page, seeded.draft, ['edit', 'submit', 'cancel', 'delete'], [
      'clarify',
      'resubmit',
      'finalize',
      'convert',
    ]);
    await submitDraftRfqFromRow(page, seeded.draft);

    await selectStatusTab(page, 'submitted');
    await expect(page.locator('tbody tr', { hasText: seeded.draft }).first()).toBeVisible({
      timeout: NAV_TIMEOUT,
    });
    await expectRowActions(page, seeded.submitted, ['clarify', 'finalize', 'cancel'], [
      'edit',
      'submit',
      'resubmit',
      'convert',
      'delete',
    ]);

    await selectStatusTab(page, 'clarification');
    await expectRowActions(page, seeded.clarification, ['edit', 'resubmit', 'finalize', 'cancel'], [
      'submit',
      'clarify',
      'convert',
      'delete',
    ]);

    await selectStatusTab(page, 'finalized');
    await expectRowActions(page, seeded.finalized, ['convert'], [
      'edit',
      'submit',
      'clarify',
      'resubmit',
      'finalize',
      'cancel',
      'delete',
    ]);
  });
});
