/**
 * E2E Test: SavedView TREE capability gate
 *
 * e2et_order has a REFERENCE field and therefore can enter the Tree creation
 * config step, but reorder support is not available yet. The user path must
 * surface a degraded diagnostic and still save a fully configured Tree view.
 *
 * @since 7.0.0
 */

import { test, expect } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_CUSTOMER_CONFIG } from '../../helpers/configs/e2et-customer.config';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';
import { navigateToDynamicPage, openSavedViewManagePanel, uniqueId } from '../helpers';

const ROUTE_PAGE_KEY = 'e2et_order';
const CUSTOMER_PAGE_KEY = 'e2et_customer';
const SCREENSHOT_DIR = 'test-results/saved-view-vnext';

test.describe('SavedView — TREE View', () => {
  test('SV-033: TREE — blocks creation when no hierarchy field exists @smoke', async ({
    page,
  }) => {
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
    await panel.getByRole('button', { name: /New View/i }).click();
    await expect(panel.getByText('Choose type')).toBeVisible();
    await panel.locator('.grid button').filter({ hasText: 'Tree' }).click();

    const blocked = panel.getByTestId('view-capability-blocked-tree');
    await expect(blocked).toBeVisible({ timeout: 5000 });
    await expect(blocked).toContainText(/parent|path|level/i);
    await expect(panel.locator('[role="alert"]').first()).toContainText(/Tree requires/i);
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

    await navigateToDynamicPage(page, ROUTE_PAGE_KEY);
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
    await panel.getByRole('button', { name: /New View/i }).click();
    await expect(panel.getByText('Choose type')).toBeVisible();
    await panel.locator('.grid button').filter({ hasText: 'Tree' }).click();

    const degraded = panel.getByTestId('view-capability-degraded-tree');
    await expect(degraded).toBeVisible({ timeout: 5000 });
    await expect(degraded).toContainText(/Reorder is disabled/i);
    await expect(panel.getByText(/Configure Tree View/i)).toBeVisible();
    expect(createPayloads).toHaveLength(0);

    await mkdir(SCREENSHOT_DIR, { recursive: true });
    await page.screenshot({
      path: `${SCREENSHOT_DIR}/tree-capability-degraded.png`,
      fullPage: true,
    });

    const selects = panel.locator('select');
    await expect(selects.first()).toHaveValue('e2et_order_customer');
    const titleField = await selects.nth(1).inputValue();
    expect(titleField).toMatch(/^e2et_order_(no|title|desc|remark)$/);

    const done = panel.getByRole('button', { name: /^Done$/i });
    await expect(done).toBeEnabled();

    const createResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/views',
      { timeout: 10000 },
    );

    await done.click();
    const createResponse = await createResponsePromise;
    expect(createResponse.ok(), `create Tree view failed: ${createResponse.status()}`).toBe(true);

    await expect(panel).toBeHidden({ timeout: 10000 });
    expect(createPayloads).toHaveLength(1);
    expect(createPayloads[0]).toMatchObject({
      viewType: 'tree',
      viewConfig: {
        treeParentField: 'e2et_order_customer',
        treeTitleField: titleField,
      },
    });

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
    await expect(treeContainer).toBeInViewport();
    await page.screenshot({
      path: `${SCREENSHOT_DIR}/tree-view-rendered.png`,
      fullPage: true,
    });
  });
});
