import { test, expect, type Page } from '@playwright/test';

async function openRoute(page: Page, route: string) {
  await page.goto(route, { waitUntil: 'domcontentloaded' });
  await expect(page.locator('body')).not.toContainText(/Access forbidden|Page not found/i, {
    timeout: 15000,
  });
}

test.describe('Quarry regression guards @smoke', () => {
  test('RG-001: key business routes are accessible for tenant admin', async ({ page }) => {
    const routes = [
      '/project-management/schedule-deviation',
      '/quarry-operation/daily-summary',
      '/contract-cost/profit-analysis',
      '/dual-prevention/compliance-report',
    ];

    for (const route of routes) {
      await openRoute(page, route);
    }
  });

  test('RG-002: contract-cost pages show localized labels', async ({ page }) => {
    await openRoute(page, '/contract-cost/payments');
    await expect(page.locator('body')).toContainText('收付款编号');

    await openRoute(page, '/contract-cost/budgets');
    await expect(page.locator('body')).toContainText('预算编号');

    await openRoute(page, '/contract-cost/budget-lines');
    await expect(page.locator('body')).toContainText('费用类别');
  });

  test('RG-003: construction-process submenus show localized labels', async ({ page }) => {
    const checks: Array<{ route: string; expected: string }> = [
      { route: '/construction-process/logs', expected: '日志编号' },
      { route: '/construction-process/reports', expected: '周报编号' },
      { route: '/construction-process/equipment-inspections', expected: '设备名称' },
      { route: '/construction-process/inspections', expected: '报验编号' },
      { route: '/construction-process/issues', expected: '问题编号' },
      { route: '/construction-process/follow-ups', expected: '关联问题' },
      { route: '/construction-process/summary', expected: '项目名称' },
    ];

    for (const item of checks) {
      await openRoute(page, item.route);
      await expect(page.locator('body')).toContainText(item.expected);
    }
  });

  test('RG-004: doc content uses rich text editor and category has parent selector', async ({
    page,
  }) => {
    const parentName = `RG Parent ${Date.now()}`;
    const createResp = await page.request.post('/api/dynamic/dk_doc_category', {
      data: {
        dk_cat_name: parentName,
        dk_cat_code: `RG-${Date.now()}`,
        dk_cat_sort_order: 999,
      },
    });
    expect(createResp.ok()).toBeTruthy();

    await page.goto('/p/dk_document/new?commandCode=dk%3Acreate_document', {
      waitUntil: 'domcontentloaded',
    });
    await expect(
      page.locator('[data-testid="form-field-dk_doc_content"] [title="Bold"]'),
    ).toBeVisible({ timeout: 15000 });
    await expect(
      page.locator('[data-testid="form-field-dk_doc_content"] [contenteditable="true"]'),
    ).toBeVisible();

    await page.goto('/p/dk_doc_category/new?commandCode=dk%3Acreate_category', {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.locator('[data-testid="form-field-dk_cat_parent_id"]')).toBeVisible({
      timeout: 15000,
    });
    const parentSelect = page.locator('[data-testid="select-trigger-dk_cat_parent_id"]');
    await parentSelect.click();
    const options = page.getByRole('option');
    await expect(options.first()).toBeVisible({ timeout: 15000 });
    await options.first().click();
  });
});
