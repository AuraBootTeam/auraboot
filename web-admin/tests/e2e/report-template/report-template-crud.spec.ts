/**
 * Report Template Management E2E Tests
 *
 * Validates the Report Template management UI:
 * - Menu navigation: accessible via sidebar
 * - CRUD: create, read, update, delete templates
 * - Publish/Archive workflow
 * - API integration
 *
 * @since 8.0.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers/index';

test.describe('Report Template Management @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('rpt');
  const templateCode = uid.toLowerCase();
  const templateName = `TestReport_${uid}`;
  let templatePid: string;

  // =========================================================================
  // TESTS
  // =========================================================================

  test('RPT-01: Report Templates page is accessible via sidebar menu', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

    // Navigate via sidebar: Meta Management → Report Templates
    const metaMenu = page.locator('button', { hasText: /元数据管理|Meta/ }).first();
    await metaMenu.waitFor({ state: 'visible', timeout: 10000 });
    await metaMenu.evaluate((el: HTMLElement) => el.click());

    const reportLink = page.locator('a[href="/report-templates"]');
    await reportLink.first().waitFor({ state: 'attached', timeout: 5000 });
    await reportLink.first().evaluate((el: HTMLElement) => el.click());

    await expect(page).toHaveURL(/\/report-templates/, { timeout: 10000 });
    await expect(page.locator('text=Report Templates')).toBeVisible({ timeout: 10000 });
  });

  test('RPT-02: Can create a new report template via API', async ({ page }) => {
    const resp = await page.request.post('/api/report-templates', {
      data: {
        code: templateCode,
        name: templateName,
        description: `E2E test template ${uid}`,
        category: 'e2e-test',
        outputFormat: 'pdf',
        pageSize: 'a4',
        orientation: 'portrait',
        dataSourceType: 'model',
        dataSourceConfig: { modelCode: 'e2et_record' },
        parameters: [
          { name: 'recordId', type: 'string', required: false, description: 'Record PID' },
        ],
      },
    });
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.code).toBe('0');
    expect(body?.data?.pid).toBeTruthy();
    templatePid = body.data.pid;
  });

  test('RPT-03: Created template appears in list API', async ({ page }) => {
    const resp = await page.request.get(`/api/report-templates?keyword=${templateCode}&page=1&size=20`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const items = body?.data?.records || [];
    expect(items.length, 'Should find the created template').toBeGreaterThanOrEqual(1);
    const found = items.find((t: { code: string }) => t.code === templateCode);
    expect(found, 'Should find our template in results').toBeTruthy();
  });

  test('RPT-04: Can read template detail via API', async ({ page }) => {
    const resp = await page.request.get(`/api/report-templates/${templatePid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.data?.code).toBe(templateCode);
    expect(body?.data?.name).toBe(templateName);
    expect(body?.data?.status).toBe('draft');
    expect(body?.data?.outputFormat).toBe('pdf');
    expect(body?.data?.parameters).toHaveLength(1);
  });

  test('RPT-05: Can update template via API', async ({ page }) => {
    const resp = await page.request.put(`/api/report-templates/${templatePid}`, {
      data: {
        code: templateCode,
        name: `${templateName}_Updated`,
        description: 'Updated description',
        category: 'e2e-test',
        outputFormat: 'xlsx',
        pageSize: 'a4',
        orientation: 'landscape',
      },
    });
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.data?.name).toBe(`${templateName}_Updated`);
    expect(body?.data?.outputFormat).toBe('xlsx');
    expect(body?.data?.orientation).toBe('landscape');
  });

  test('RPT-06: Publish requires template content', async ({ page }) => {
    // Templates without JRXML content cannot be published
    const resp = await page.request.post(`/api/report-templates/${templatePid}/publish`);
    const body = await resp.json();
    // Should fail with non-success code (template has no content)
    expect(body?.code).not.toBe('0');
  });

  test('RPT-07: Published templates API is accessible', async ({ page }) => {
    const resp = await page.request.get('/api/report-templates/published');
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.code).toBe('0');
  });

  test('RPT-08: Code uniqueness check API works', async ({ page }) => {
    // Existing code should not be unique
    const resp = await page.request.get(`/api/report-templates/check-code?code=${templateCode}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.data).toBe(false); // code exists, not unique
  });

  test('RPT-09: Categories API returns our category', async ({ page }) => {
    const resp = await page.request.get('/api/report-templates/categories');
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const categories: string[] = body?.data || [];
    expect(categories).toContain('e2e-test');
  });

  test('RPT-10: Editor page loads for existing template', async ({ page }) => {
    await page.goto(`/report-templates/${templatePid}`, { waitUntil: 'domcontentloaded' });
    await page.waitForResponse(
      (resp) => resp.url().includes(`/api/report-templates/${templatePid}`) && resp.status() === 200,
      { timeout: 10000 },
    );

    // Verify form fields loaded
    const codeInput = page.locator('input[value="' + templateCode + '"]');
    await expect(codeInput).toBeVisible({ timeout: 5000 });
  });
});
