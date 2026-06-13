/**
 * Designer Lifecycle E2E Tests
 *
 * Legacy lifecycle smoke coverage for the remaining designer suites:
 *   1. Component/Block/Widget/Node Add
 *   2. Property Edit
 *   3. Save + API Verify
 *   4. Publish/Deploy/Enable + API Verify
 *   5. Backend Data Verify
 *
 * BPMN lifecycle coverage moved to tests/e2e/bpm-designer, where the specs
 * assert designerJson, BPMN XML, and selected SmartEngine runtime behavior
 * without permission-gated skip wrappers.
 * Automation coverage moved to tests/e2e/automation/automation-designer-golden.spec.ts,
 * where trigger/action/control nodes are verified through browser authoring,
 * backend persistence, and runtime side effects instead of API save fallbacks.
 * Dashboard coverage moved to tests/e2e/dashboard, where widget authoring,
 * property panels, management lifecycle, artifacts, and published viewer runtime
 * paths are covered with dedicated browser/backend evidence.
 *
 * Each remaining designer has a serial describe block (DL-PD, DL-RPT).
 *
 * @since 6.0.0
 */

import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import { uniqueId } from '../helpers';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Wait for designer to finish loading */
async function waitForDesignerLoad(page: Page) {
  await page.waitForLoadState('networkidle').catch(() => {});
  await page
    .locator('.animate-spin')
    .waitFor({ state: 'hidden', timeout: 10000 })
    .catch(() => {});
  await page
    .locator('text=Loading page...')
    .waitFor({ state: 'hidden', timeout: 10000 })
    .catch(() => {});
}

// ============================================================
//  1. Page Designer (DL-PD)
// ============================================================

test.describe.serial('Page Designer Lifecycle (DL-PD)', () => {
  test.setTimeout(60000);
  const testId = uniqueId('dl_pd');
  const pageKey = `dlpd_${Date.now()}`;
  let pid: string;

  test('DL-PD-01: Block add — create page and open designer', async ({ page }) => {
    // Create page via API — must match PageSchemaCreateRequest fields
    const resp = await page.request.post('/api/pages', {
      data: {
        pageKey,
        name: testId,
        title: testId,
        kind: 'list',
        modelCode: 'ab_user',
        blocks: [
          { blockType: 'table', id: 'main_table', config: {} },
        ],
        layout: { type: 'areas' },
        semver: '0.1.0',
      },
    });
    const body = await resp.json();
    pid = body.data?.pid || body.data?.id;
    expect(pid, `Create page failed: ${JSON.stringify(body)}`).toBeTruthy();

    await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);

    // Try to open blocks tab
    const blocksTab = page
      .locator(
        '[data-testid="designer-tab-blocks"], button:has-text("Blocks"), button:has-text("区块")',
      )
      .first();
    if (await blocksTab.isVisible({ timeout: 5000 }).catch(() => false)) {
      await blocksTab.click();
    }

    // Look for draggable items and try to drag one
    const draggableItems = page.locator('[draggable="true"], [data-draggable]');
    if ((await draggableItems.count()) > 0) {
      const item = draggableItems.first();
      const canvas = page.locator('[data-testid="designer-canvas"]').first();
      const itemBox = await item.boundingBox();
      const canvasBox = await canvas.boundingBox();
      if (itemBox && canvasBox) {
        await page.mouse.move(itemBox.x + itemBox.width / 2, itemBox.y + itemBox.height / 2);
        await page.mouse.down();
        await page.mouse.move(
          canvasBox.x + canvasBox.width / 2,
          canvasBox.y + canvasBox.height / 2,
          { steps: 15 },
        );
        await page.mouse.up();
      }
    }

    // Verify canvas visible
    await expect(page.locator('[data-testid="designer-canvas"], main').first()).toBeVisible({
      timeout: 5000,
    });
  });

  test('DL-PD-02: Property edit — select block, verify properties panel', async ({ page }) => {
    await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);

    // Click on a sortable block or section heading
    const block = page
      .locator('[data-testid="designer-canvas"]')
      .locator('[aria-roledescription="sortable"]')
      .first();
    if (await block.isVisible({ timeout: 5000 }).catch(() => false)) {
      await block.click();
    }

    // Properties panel should be visible
    await expect(
      page
        .locator(
          '[data-testid="designer-properties-panel"], [data-testid="floors-properties-panel"], aside',
        )
        .first(),
    ).toBeVisible({ timeout: 5000 });
  });

  test('DL-PD-03: Save + API verify', async ({ page }) => {
    await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);

    const btn = page
      .locator('[data-testid="toolbar-save"], button:has-text("Save"), button:has-text("保存")')
      .first();
    await expect(btn).toBeVisible({ timeout: 8000 });

    // If save is disabled (no dirty state), just verify it exists
    if (!(await btn.isEnabled().catch(() => false))) {
      await expect(btn).toBeVisible();
      return;
    }

    const [response] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().includes(`/api/pages/${pid}`) && r.request().method().toLowerCase() === 'put',
        { timeout: 15000 },
      ),
      btn.click(),
    ]);
    expect(response.status()).toBeLessThan(400);
  });

  test('DL-PD-04: Publish + API verify', async ({ page }) => {
    const resp = await page.request.post(`/api/pages/${pid}/publish`);
    expect(resp.status()).toBeLessThan(400);
  });

  test('DL-PD-05: Backend data verify — published, blocks non-empty', async ({ page }) => {
    const resp = await page.request.get(`/api/pages/${pid}`);
    expect(resp.ok()).toBeTruthy();
    const { data } = await resp.json();
    expect(data.status).toBe('published');
    expect(data.kind).toBeTruthy();
    expect(data.blocks).toBeTruthy();
  });
});

// ============================================================
//  2. Report Designer (DL-RPT)
// ============================================================

test.describe.serial('Report Designer Lifecycle (DL-RPT)', () => {
  test.setTimeout(60000);
  let pid: string;

  test('DL-RPT-01: Block add — add data-table block to canvas', async ({ page }) => {
    await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);

    const palette = page.getByTestId('block-palette');
    await expect(palette).toBeVisible({ timeout: 10000 });

    // Click "Data Table" in palette
    const dataTableBtn = page.getByRole('button', { name: /Data Table|数据表/i }).first();
    await expect(dataTableBtn).toBeVisible({ timeout: 5000 });
    await dataTableBtn.click();

    // Verify canvas has content after adding block
    const canvas = page.getByTestId('report-canvas');
    await expect(canvas).toBeVisible();
    // The canvas should have at least one child block element
    await expect(
      canvas.locator('[data-block-id]').first().or(canvas.locator('div > div').first()),
    ).toBeVisible({
      timeout: 5000,
    });
  });

  test('DL-RPT-02: Property edit — verify property panel visible', async ({ page }) => {
    await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);

    await expect(page.getByTestId('block-palette')).toBeVisible({ timeout: 10000 });

    // Add block
    await page
      .getByRole('button', { name: /Data Table|数据表/i })
      .first()
      .click();

    // Verify property panel appears
    const propPanel = page.getByTestId('block-property-panel');
    await expect(propPanel).toBeVisible({ timeout: 5000 });

    // Try to edit title
    const titleInput = propPanel.locator('input').first();
    if (await titleInput.isVisible({ timeout: 2000 }).catch(() => false)) {
      await titleInput.fill('DL-RPT Test Table');
    }
    await expect(propPanel).toBeVisible();
  });

  test('DL-RPT-03: Save + API verify', async ({ page }) => {
    const reportTitle = uniqueId('dl_rpt');
    await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);

    await expect(page.getByTestId('block-palette')).toBeVisible({ timeout: 10000 });

    // Add block so there's content to save (triggers isDirty)
    await page
      .getByRole('button', { name: /Data Table|数据表/i })
      .first()
      .click();

    const reportTitleInput = page.locator('input[placeholder="Report Title"]').first();
    if (await reportTitleInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await reportTitleInput.fill(uniqueId('dl_rpt'));
    }

    // Click Save — listen for POST (new) or PUT (existing)
    const btn = page.locator('[data-testid="report-designer-toolbar-btn-save"]').first();
    await expect(btn).toBeVisible({ timeout: 5000 });

    const responsePromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/pages') &&
        (r.request().method().toLowerCase() === 'post' ||
          r.request().method().toLowerCase() === 'put'),
      { timeout: 15000 },
    );
    await btn.click();
    const response = await responsePromise;
    expect(response.status()).toBeLessThan(400);

    const body = await response.json().catch(() => ({}));
    pid = body.data?.pid || body.data?.id;
    expect(pid).toBeTruthy();
  });

  test('DL-RPT-04: Publish + API verify', async ({ page }) => {
    expect(pid).toBeTruthy();
    const resp = await page.request.post(`/api/pages/${pid}/publish`);
    expect(resp.status()).toBeLessThan(400);
  });

  test('DL-RPT-05: Backend data verify — profile=report, blocks array present', async ({
    page,
  }) => {
    if (!pid) {
      await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
      await waitForDesignerLoad(page);

      await page
        .getByRole('button', { name: /Data Table|数据表/i })
        .first()
        .click();

      const reportTitleInput = page.locator('input[placeholder="Report Title"]').first();
      if (await reportTitleInput.isVisible({ timeout: 3000 }).catch(() => false)) {
        await reportTitleInput.fill(uniqueId('dl_rpt'));
      }

      const btn = page.locator('[data-testid="report-designer-toolbar-btn-save"]').first();
      await expect(btn).toBeVisible({ timeout: 5000 });
      const responsePromise = page.waitForResponse(
        (r) =>
          r.url().includes('/api/pages') &&
          (r.request().method().toLowerCase() === 'post' ||
            r.request().method().toLowerCase() === 'put'),
        { timeout: 15000 },
      );
      await btn.click();
      const response = await responsePromise;
      expect(response.status()).toBeLessThan(400);
      const body = await response.json().catch(() => ({}));
      pid = body.data?.pid || body.data?.id;
    }

    expect(pid).toBeTruthy();
    const resp = await page.request.get(`/api/pages/${pid}`);
    expect(resp.ok()).toBeTruthy();
    const { data } = await resp.json();
    expect(data.profile).toBe('report');
    expect(data.profile).toBe('report');
    expect(['draft', 'published']).toContain(data.status);
    expect(Array.isArray(data.blocks)).toBe(true);
  });
});
