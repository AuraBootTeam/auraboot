/**
 * E2E Test: SavedView TREE capability gate
 *
 * e2et_order has a REFERENCE field and therefore can enter the Tree creation
 * config step, but reorder support is not available yet. The user path must
 * surface a degraded diagnostic and still save a fully configured Tree view.
 *
 * @since 7.0.0
 */

import { test, expect, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_CUSTOMER_CONFIG } from '../../helpers/configs/e2et-customer.config';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';
import { navigateToDynamicPage, openSavedViewManagePanel, uniqueId } from '../helpers';
import { cleanupGeneratedSavedViews, createOrReuseSavedView } from './helpers';

const ROUTE_PAGE_KEY = 'e2et_order';
const CUSTOMER_PAGE_KEY = 'e2et_customer';
const SCREENSHOT_DIR = 'test-results/saved-view-vnext';
const ORDER_LIST_PAGE_KEY = 'e2et_order_list';
const CLEANUP_PREFIXES = ['树视图视图', '树视图表格视图', 'SV Tree Table View', 'SV_Tree_'];

async function cleanupTreeViews(page: Page): Promise<void> {
  const params = new URLSearchParams({
    modelCode: ROUTE_PAGE_KEY,
    pageKey: ORDER_LIST_PAGE_KEY,
  });
  const accessible = await page.request.get(`/api/views/accessible?${params.toString()}`);
  if (!accessible.ok()) return;
  const body = await accessible.json().catch(() => ({}));
  const views = Array.isArray(body.data) ? body.data : [];
  for (const view of views) {
    if (
      view?.pid &&
      view.scope === 'personal' &&
      CLEANUP_PREFIXES.some((prefix) => String(view.name ?? '').startsWith(prefix))
    ) {
      await page.request.delete(`/api/views/${view.pid}`).catch(() => {});
    }
  }
}

async function ensureOrderTableView(page: Page): Promise<string> {
  const { pid } = await createOrReuseSavedView(page, {
    name: '树视图表格视图',
    modelCode: ROUTE_PAGE_KEY,
    pageKey: ORDER_LIST_PAGE_KEY,
    scope: 'personal',
    viewType: 'table',
    viewConfig: {},
    expectSuccess: true,
  });
  return String(pid);
}

test.describe('SavedView — TREE View', () => {
  test.beforeEach(async ({ page }) => {
    await cleanupGeneratedSavedViews(page, {
      modelCode: ROUTE_PAGE_KEY,
      pageKey: ORDER_LIST_PAGE_KEY,
    });
    await cleanupTreeViews(page);
  });

  test.afterEach(async ({ page }) => {
    await cleanupGeneratedSavedViews(page, {
      modelCode: ROUTE_PAGE_KEY,
      pageKey: ORDER_LIST_PAGE_KEY,
    });
    await cleanupTreeViews(page);
  });

  test('SV-033: TREE — blocks creation when no hierarchy field exists @smoke', async ({ page }) => {
    const customer = new ModelTestHelper(page, E2ET_CUSTOMER_CONFIG);
    const customerName = `SV Tree Blocked ${uniqueId('TREE_BLOCKED')}`;
    await customer.createViaApi({
      e2et_cust_name: customerName,
    });

    await navigateToDynamicPage(page, CUSTOMER_PAGE_KEY);
    await expect(page.getByText(customerName)).toBeVisible({ timeout: 15000 });

    const createRequests: string[] = [];
    page.on('request', (request) => {
      const url = new URL(request.url());
      if (request.method() === 'POST' && url.pathname === '/api/views') {
        createRequests.push(request.postData() ?? '');
      }
    });

    const panel = await openSavedViewManagePanel(page);
    await panel.getByTestId('saved-view-create-personal').click();
    await expect(panel.getByTestId('saved-view-quota-status')).toContainText('个人视图：');
    await panel.getByTestId('saved-view-type-tree').click();

    const blocked = panel.getByTestId('view-capability-blocked-tree');
    await expect(blocked).toBeVisible({ timeout: 5000 });
    await expect(blocked).toContainText(/缺少|父级|路径|层级/);
    expect(createRequests).toHaveLength(0);

    await mkdir(SCREENSHOT_DIR, { recursive: true });
    await page.screenshot({
      path: `${SCREENSHOT_DIR}/tree-capability-blocked.png`,
      fullPage: true,
    });
  });

  test('SV-031: TREE — shows degraded diagnostic and saves required parent mapping @smoke', async ({
    page,
  }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const orderTitle = `SV_Tree_${uniqueId('TREE')}`;
    await order.createViaApi({
      e2et_order_title: orderTitle,
      e2et_order_customer: '',
    });

    const tableViewPid = await ensureOrderTableView(page);
    await navigateToDynamicPage(page, ROUTE_PAGE_KEY, { viewPid: tableViewPid });
    const searchResponsePromise = page
      .waitForResponse(
        (response) =>
          response.url().includes('/api/dynamic/e2et_order/list') && response.status() === 200,
        {
          timeout: 10000,
        },
      )
      .catch(() => null);
    await page.getByTestId('list-search-input').fill(orderTitle);
    await page.getByTestId('list-search-input').press('Enter');
    await searchResponsePromise;
    await expect(page.getByText(orderTitle)).toBeVisible({ timeout: 15000 });

    const createPayloads: Array<Record<string, unknown>> = [];
    page.on('request', (request) => {
      const url = new URL(request.url());
      if (request.method() === 'POST' && url.pathname === '/api/views') {
        const body = request.postData();
        createPayloads.push(body ? JSON.parse(body) : {});
      }
    });

    const panel = await openSavedViewManagePanel(page);
    await panel.getByTestId('saved-view-create-personal').click();
    await expect(panel.getByTestId('saved-view-quota-status')).toContainText('个人视图：');
    await panel.getByTestId('saved-view-type-tree').click();

    const degraded = panel.getByTestId('view-capability-degraded-tree');
    await expect(degraded).toBeVisible({ timeout: 5000 });
    await expect(degraded).toContainText(/排序命令|只读展示/);
    await expect(panel.getByText(/配置树视图/)).toBeVisible();
    expect(createPayloads).toHaveLength(0);

    await mkdir(SCREENSHOT_DIR, { recursive: true });
    await page.screenshot({
      path: `${SCREENSHOT_DIR}/tree-capability-degraded.png`,
      fullPage: true,
    });

    await expect(panel.getByTestId('saved-view-config-field-treeParentField')).toHaveValue(
      'e2et_order_customer',
    );
    const titleField = await panel
      .getByTestId('saved-view-config-field-treeTitleField')
      .inputValue();
    expect(titleField).toMatch(/^e2et_order_(no|title|desc|remark)$/);

    const save = panel.getByTestId('saved-view-config-save');
    await expect(save).toBeEnabled();

    const createResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/views',
      { timeout: 10000 },
    );

    await save.click();
    const createResponse = await createResponsePromise;
    expect(createResponse.ok(), `create Tree view failed: ${createResponse.status()}`).toBe(true);
    const createBody = await createResponse.json();
    const treePid = createBody.data?.pid ?? createBody.data?.view?.pid ?? createBody.pid;
    expect(treePid, `create Tree view returned no pid: ${JSON.stringify(createBody)}`).toBeTruthy();

    await expect(panel).toBeHidden({ timeout: 10000 });
    expect(createPayloads).toHaveLength(1);
    expect(createPayloads[0]).toMatchObject({
      viewType: 'tree',
      viewConfig: {
        treeParentField: 'e2et_order_customer',
        treeTitleField: titleField,
      },
    });
    await expect(page).toHaveURL(new RegExp(`view=${treePid}`), { timeout: 10000 });

    await expect(page.getByTestId('view-selector-trigger')).toHaveAttribute(
      'data-current-view-type',
      'tree',
      { timeout: 10000 },
    );
    const treeContainer = page.getByTestId('tree-view-container');
    await expect(treeContainer).toBeVisible({ timeout: 15000 });
    await expect(treeContainer.getByTestId('tree-toolbar')).toBeVisible();
    await expect(page.getByTestId('ab:list:e2et_order:table')).toBeHidden();
    await expect(page.getByTestId('tree-node-count')).toContainText(/\d+ nodes/);
    await page.screenshot({
      path: `${SCREENSHOT_DIR}/tree-view-rendered.png`,
      fullPage: true,
    });
  });
});
