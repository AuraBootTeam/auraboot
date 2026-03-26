/**
 * E2E Test Customer — CRUD + UNIQUE_COMPOSITE Validation
 *
 * Tests UC-001 ~ UC-003: Customer model CRUD with UNIQUE_COMPOSITE validation
 * - Create customer via UI
 * - Duplicate code+region → error
 * - Different region same code → success
 *
 * Uses real database, NO MOCKING.
 *
 * @since 6.2.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId, navigateToDynamicPage, waitForDynamicPageLoad } from '../helpers';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_CUSTOMER_CONFIG } from '../../helpers/configs/e2et-customer.config';
import { ErrorCodes } from '~/services/http-client/types';

test.describe('E2E Test Customer — CRUD + UNIQUE_COMPOSITE', () => {
  const testCode = `CUST-${Date.now()}`;

  test.afterAll(async ({ browser }) => {
    // Clean up any test customers created
    const context = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await context.newPage();
    const helper = new ModelTestHelper(page, E2ET_CUSTOMER_CONFIG);

    // List customers and delete those matching our test code prefix
    try {
      const resp = await page.request.get(`/api/dynamic/e2et-customer/list`);
      if (resp.ok()) {
        const body = await resp.json();
        const records = body?.data?.records || body?.data?.list || [];
        for (const rec of records) {
          if (rec.e2et_cust_code?.startsWith('CUST-') && rec.pid) {
            await helper.deleteViaApi(rec.pid).catch(() => {});
          }
        }
      }
    } catch {
      // Cleanup is best-effort
    }
    await page.close();
    await context.close();
  });

  /**
   * UC-001: Create a customer via UI form
   */
  test('UC-001: should create customer via UI @smoke', async ({ page }) => {
    const custCode = `UC1-${uniqueId('C')}`;
    const custName = `Test Customer ${uniqueId('N')}`;

    // Navigate to customer list page
    await navigateToDynamicPage(page, 'e2et-customer');

    // Click create button
    const createBtn = page.locator('[data-testid="toolbar-btn-create"], button:has-text("新建"), button:has-text("Create")').first();
    await createBtn.waitFor({ state: 'visible', timeout: 5000 });
    await createBtn.click();

    // Wait for form to load
    await page.waitForURL((url) => url.pathname.includes('/new') || url.pathname.includes('/create'), { timeout: 10000 });
    await waitForDynamicPageLoad(page);

    // Wait for form fields to be ready (switch component as indicator)
    await page.locator('button[role="switch"], input, select').first()
      .waitFor({ state: 'attached', timeout: 10000 });

    // Fill form fields
    const codeInput = page.locator('[data-testid="form-field-e2et_cust_code"] input').first();
    if (await codeInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await codeInput.fill(custCode);
    } else {
      // Fallback: find input by placeholder or label
      await page.locator('input[placeholder*="客户编码"], input[placeholder*="customer code" i]').first().fill(custCode);
    }

    const nameInput = page.locator('[data-testid="form-field-e2et_cust_name"] input').first();
    if (await nameInput.isVisible({ timeout: 2000 }).catch(() => false)) {
      await nameInput.fill(custName);
    } else {
      await page.locator('input[placeholder*="客户名称"], input[placeholder*="customer name" i]').first().fill(custName);
    }

    // Select region — native <select> rendered by smart components
    const regionSelect = page.locator('[data-testid="form-field-e2et_cust_region"] select').first();
    if (await regionSelect.isVisible({ timeout: 2000 }).catch(() => false)) {
      await regionSelect.selectOption('east');
    } else {
      // Fallback: Ant Design select or custom component
      const customSelect = page.locator('select').first();
      await customSelect.selectOption('east');
    }

    // Click save button
    const saveBtn = page.locator('[data-testid^="form-btn-"], button:has-text("保存"), button:has-text("Save"), button[type="submit"]').first();
    await saveBtn.click();

    // Wait for command response
    const resp = await page.waitForResponse(
      (r) => r.url().includes('/commands/execute/') && r.status() === 200,
      { timeout: 10000 }
    );
    const body = await resp.json();
    expect(String(body.code)).toBe(ErrorCodes.SUCCESS);
  });

  /**
   * UC-002: Duplicate code+region should fail with UNIQUE_COMPOSITE error
   */
  test('UC-002: should reject duplicate code+region @smoke', async ({ page }) => {
    const helper = new ModelTestHelper(page, E2ET_CUSTOMER_CONFIG);
    const dupCode = `DUP-${uniqueId('D')}`;

    // Create first customer via API
    await helper.createViaApi({
      e2et_cust_code: dupCode,
      e2et_cust_name: 'First Customer',
      e2et_cust_region: 'south',
    });

    // Try to create second customer with same code+region via API
    const resp = await page.request.post('/api/meta/commands/execute/e2et:create_customer', {
      data: {
        operationType: 'create',
        payload: {
          e2et_cust_code: dupCode,
          e2et_cust_name: 'Duplicate Customer',
          e2et_cust_region: 'south',
          e2et_cust_active: true,
        },
      },
    });

    const body = await resp.json();
    // Should fail with validation error (non-200 code or error message)
    const isError = String(body.code) !== '200' || body.message?.includes('重复') || body.message?.includes('unique');
    expect(isError).toBe(true);
  });

  /**
   * UC-003: Same code but different region should succeed
   */
  test('UC-003: should allow same code in different region @critical', async ({ page }) => {
    const helper = new ModelTestHelper(page, E2ET_CUSTOMER_CONFIG);
    const sameCode = `SAME-${uniqueId('S')}`;

    // Create first customer in EAST
    await helper.createViaApi({
      e2et_cust_code: sameCode,
      e2et_cust_name: 'East Customer',
      e2et_cust_region: 'east',
    });

    // Create second customer with same code but in WEST — should succeed
    const pid2 = await helper.createViaApi({
      e2et_cust_code: sameCode,
      e2et_cust_name: 'West Customer',
      e2et_cust_region: 'west',
    });

    expect(pid2).toBeTruthy();

    // Verify second customer exists by fetching it directly
    const data2 = await helper.fetchViaApi(pid2);
    expect(data2.e2et_cust_code).toBe(sameCode);
    expect(data2.e2et_cust_region).toBe('west');
  });
});
