/**
 * BPM Frontend Management Pages E2E Tests
 *
 * Tests for domain config and SLA config management pages.
 * DOMAIN-E01 ~ DOMAIN-E02, SLA-MGMT-E01 ~ SLA-MGMT-E02.
 *
 * Prerequisites: Backend running.
 *
 * Uses real database, NO MOCKING.
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import { navigateToDynamicPage, waitForDynamicPageLoad, extractRecordId } from '../helpers/index';

test.describe('BPM Frontend Management Pages', () => {
  // ==================== Domain Config (DSL page) ====================

  /**
   * DOMAIN-E01: Domain config list page is accessible (now DSL page)
   */
  test('DOMAIN-E01: Domain config list page loads', async ({ page }) => {
    await navigateToDynamicPage(page, 'bpm-domain-config');
    await waitForDynamicPageLoad(page);

    // DSL page should render a table
    await expect(page.locator('table')).toBeVisible({ timeout: 8000 });
  });

  /**
   * DOMAIN-E02: Create domain config via command API, verify it appears in list
   */
  test('DOMAIN-E02: Domain config created via API appears in list', async ({ page }) => {
    const domainCode = `e2e_domain_${Date.now()}`;

    // Create via command API
    const createResponse = await page.request.post(
      `/api/meta/commands/execute/admin:create_bpm_domain_config`,
      {
        data: {
          operationType: 'create',
          payload: {
            domain_code: domainCode,
            domain_name: `E2E Domain ${domainCode}`,
            model_code: 'test-model',
            enabled: true,
          },
        },
      },
    );

    if (!createResponse.ok()) {
      const body = await createResponse.text().catch(() => '');
      console.warn(`Domain config API failed: ${createResponse.status()} ${body.slice(0, 200)}`);
      throw new Error(String(`Domain config API returned ${createResponse.status()}`));
      return;
    }

    const result = await createResponse.json();
    const pid = extractRecordId(result);

    try {
      // Navigate to DSL list page
      await navigateToDynamicPage(page, 'bpm-domain-config');
      await waitForDynamicPageLoad(page);

      // Should show the created config
      await expect(page.locator('tbody tr', { hasText: domainCode }).first()).toBeVisible({
        timeout: 5000,
      });
    } finally {
      // Cleanup
      if (pid) {
        await page.request
          .post(`/api/meta/commands/execute/admin:delete_bpm_domain_config`, {
            data: { targetRecordId: pid, operationType: 'delete', payload: {} },
          })
          .catch(() => {});
      }
    }
  });

  // ==================== SLA Config (DSL page) ====================

  /**
   * SLA-MGMT-E01: SLA config list page is accessible (now DSL page)
   */
  test('SLA-MGMT-E01: SLA config list page loads', async ({ page }) => {
    await navigateToDynamicPage(page, 'sla-config');
    await waitForDynamicPageLoad(page);

    // DSL page should render a table
    await expect(page.locator('table')).toBeVisible({ timeout: 8000 });
  });

  /**
   * SLA-MGMT-E02: Create SLA config via command API, verify it appears in list
   */
  test('SLA-MGMT-E02: SLA config created via API appears in list', async ({ page }) => {
    const slaName = `E2E SLA ${Date.now()}`;

    // Create via command API
    const createResponse = await page.request.post(
      `/api/meta/commands/execute/admin:create_sla_config`,
      {
        data: {
          operationType: 'create',
          payload: {
            name: slaName,
            target_type: 'process',
            target_key: 'e2e-sla-test-process',
            deadline_mode: 'fixed',
            deadline_value: 'pt24h',
            enabled: true,
          },
        },
      },
    );

    if (!createResponse.ok()) {
      const body = await createResponse.text().catch(() => '');
      console.warn(`SLA config API failed: ${createResponse.status()} ${body.slice(0, 200)}`);
      throw new Error(String(`SLA config API returned ${createResponse.status()}`));
      return;
    }

    const result = await createResponse.json();
    const pid = extractRecordId(result);

    try {
      // Navigate to DSL list page
      await navigateToDynamicPage(page, 'sla-config');
      await waitForDynamicPageLoad(page);

      // Should show the created config
      await expect(page.getByText(slaName)).toBeVisible({ timeout: 5000 });
    } finally {
      // Cleanup
      if (pid) {
        await page.request
          .post(`/api/meta/commands/execute/admin:delete_sla_config`, {
            data: { targetRecordId: pid, operationType: 'delete', payload: {} },
          })
          .catch(() => {});
      }
    }
  });

  // ==================== SLA Monitor ====================

  /**
   * SLA-MON-E01: SLA monitor page is accessible and shows dashboard stats
   */
  test('SLA-MON-E01: SLA monitor page loads with stats', async ({ page }) => {
    await page.goto(`/bpm/sla-monitor`);
    await page.waitForLoadState('domcontentloaded');

    // Should show the page title h1 (Chinese: SLA 监控)
    await expect(page.locator('h1').filter({ hasText: /sla|监控/i })).toBeVisible({
      timeout: 5000,
    });

    const emptyStateVisible = await expect
      .poll(
        async () => {
          const text = await page.locator('main').textContent().catch(() => '');
          return /暂无监控数据|No monitoring data available/i.test(text || '');
        },
        { timeout: 5000 },
      )
      .toBe(true)
      .then(() => true)
      .catch(() => false);

    if (emptyStateVisible) {
      await expect(page.getByTestId('sla-refresh')).toBeVisible();
      return;
    }

    // With data available, stat sections should render.
    await expect(page.getByTestId('sla-dashboard-process-definitions')).toBeVisible();
    await expect(page.getByTestId('sla-dashboard-active-records')).toBeVisible();
    await expect(page.getByTestId('sla-dashboard-configs')).toBeVisible();
  });
});
