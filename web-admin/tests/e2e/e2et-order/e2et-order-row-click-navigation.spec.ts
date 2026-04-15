/**
 * E2E Test Order — Row Click Navigation
 *
 * Validates DSL `options.detailNavigation: "page"` wiring end-to-end:
 *   - DB `ab_page_schema.extension.options` must hoist into `schema.options`
 *     (see useSchemaLoader.ts)
 *   - ListPageContent reads `schema.options.detailNavigation` and navigates
 *     to `/p/{key}/view/{id}` on row click instead of opening the preview drawer.
 *
 * Coverage:
 *   RCN-01: Row click navigates to detail page (not drawer)
 *   RCN-02: Detail URL matches /p/e2et_order/view/{id}
 *   RCN-03: Preview drawer is not rendered after row click
 *
 * @since 7.4.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId } from '../quarry-management.setup';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';

test.describe('E2E Test Order — Row Click Navigation @smoke', () => {
  test.describe.configure({ mode: 'serial' });

  const orderTitle = `RCN Order ${uniqueId()}`;
  let orderPid: string;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
      orderPid = await order.createViaApi({
        e2et_order_title: orderTitle,
        e2et_order_type: 'normal',
        e2et_order_urgent: false,
      });
      expect(orderPid).toBeTruthy();
    } finally {
      await ctx.close();
    }
  });

  test('RCN-01/02/03: row click navigates to detail page, no drawer', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.gotoList();

    const row = page.locator('tbody tr', { hasText: orderTitle }).first();
    await row.waitFor({ state: 'visible', timeout: 10000 });

    const drawer = page.locator('[data-testid="record-preview-drawer"]');
    // Click the cell containing the order title (skips checkbox / action columns)
    await row.locator('td', { hasText: orderTitle }).first().click();

    // RCN-02: URL matches the detail route convention
    await expect(page).toHaveURL(
      new RegExp(`/p/e2et_order/view/${orderPid}(?:[/?#]|$)`),
      { timeout: 5000 },
    );

    // RCN-03: preview drawer must not be rendered
    await expect(drawer).not.toBeVisible({ timeout: 2000 });

    // RCN-01: detail page shows the record's title
    await expect(page.locator('body')).toContainText(orderTitle, { timeout: 10000 });
  });
});
