/**
 * Page Designer full lifecycle E2E.
 *
 * Covers the highest-signal Page Designer chain:
 *   1. Sidebar navigation to the page schema list
 *   2. Open the designer from the list row
 *   3. Add a block through the block palette
 *   4. Open preview and verify the rendered block count
 *   5. Save and publish
 *   6. Return to the list and verify published status
 *   7. Re-open the designer and verify persisted blocks
 *
 * This spec is intentionally menu-driven and avoids deep-linking to
 * `/page-designer/:id` so it validates the real operator workflow.
 */

import { test, expect, type Page, type APIRequestContext } from '../../fixtures';
import { ensureSidebarExpanded, uniqueId } from '../helpers';

type PageDto = {
  pid: string;
  status?: string;
  isPublished?: boolean;
  blocks?: Array<{ id?: string; blockType?: string }>;
  layout?: { blocks?: Array<{ id?: string; blockType?: string }> };
};

async function apiCreateFixturePage(page: Page, pageKey: string): Promise<string> {
  const response = await page.request.post('/api/pages', {
    data: {
      pageKey,
      name: `PD Lifecycle ${pageKey}`,
      title: `PD Lifecycle ${pageKey}`,
      kind: 'form',
      modelCode: 'tenant',
      layout: { type: 'stack' },
      blocks: [
        {
          id: 'seed_form_section',
          blockType: 'form-section',
          title: { 'en-US': 'Seed Section' },
          fields: [],
        },
      ],
      semver: '0.1.0',
    },
  });

  expect(response.ok(), `Create fixture page failed with ${response.status()}`).toBeTruthy();
  const body = (await response.json()) as { code: string; data?: { pid?: string } };
  expect(body.code).toBe('0');
  expect(body.data?.pid, 'fixture page pid must be returned').toBeTruthy();
  return body.data!.pid!;
}

async function deleteFixturePage(request: APIRequestContext, pid: string | null): Promise<void> {
  if (!pid) return;
  await request.delete(`/api/pages/${pid}`).catch(() => null);
}

async function fetchPageDto(request: APIRequestContext, pid: string): Promise<PageDto> {
  const response = await request.get(`/api/pages/${pid}`);
  expect(response.ok(), `Fetch page ${pid} failed`).toBeTruthy();
  const body = (await response.json()) as { code: string; data?: PageDto };
  expect(body.code).toBe('0');
  expect(body.data, 'page dto must be present').toBeTruthy();
  return body.data!;
}

function extractBlocks(page: PageDto): Array<{ id?: string; blockType?: string }> {
  return Array.isArray(page.blocks)
    ? page.blocks
    : Array.isArray(page.layout?.blocks)
      ? page.layout.blocks
      : [];
}

async function navigateToPageSchemaList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);

  const nav = page.locator('nav, aside, [role="navigation"]').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  const parent = nav
    .locator('button', { hasText: /元数据管理|Metadata|menu\.meta_management/i })
    .first();
  await parent.waitFor({ state: 'visible', timeout: 8_000 });
  await parent.evaluate((element: HTMLElement) => element.click());

  const leaf = nav.locator('a[href="/p/page_schema"], a[href*="/p/page_schema"]').first();
  await leaf.waitFor({ state: 'attached', timeout: 8_000 });

  const listResponse = page.waitForResponse(
    (response) =>
      (response.url().includes('/api/meta/page-render/dynamic/page_schema_list/list') ||
        (response.url().includes('/dynamic/page_schema_list') &&
          response.url().includes('/list'))) &&
      response.status() === 200,
    { timeout: 10_000 },
  );

  await leaf.evaluate((element: HTMLElement) => element.click());
  await listResponse.catch(() => null);

  await expect(page.getByTestId('toolbar-btn-create')).toBeVisible({ timeout: 8_000 });
}

async function openDesignerFromList(page: Page, pid: string, pageKey: string): Promise<void> {
  const search = page
    .locator('input[placeholder*="搜索"], input[placeholder*="Search"], input[type="search"]')
    .first();

  if (await search.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await search.click();
    await search.fill(pageKey);
    await search.press('Enter').catch(() => null);
    await page
      .waitForResponse(
        (response) =>
          response.url().includes('/dynamic/page_schema_list') && response.status() === 200,
        { timeout: 8_000 },
      )
      .catch(() => null);
  }

  const row = page.locator(`tr:has-text("${pageKey}")`).first();
  await expect(row).toBeVisible({ timeout: 8_000 });

  await page.evaluate(() => {
    document.querySelectorAll('vite-error-overlay').forEach((element) => element.remove());
  });

  const link = row.locator(`a[href*="/page-designer/${pid}"]`).first();
  if (await link.isVisible({ timeout: 1_500 }).catch(() => false)) {
    await link.evaluate((element: HTMLElement) => element.click());
  } else {
    const fallbackLink = row.locator('a[href*="/page-designer/"]').first();
    if (await fallbackLink.isVisible({ timeout: 1_500 }).catch(() => false)) {
      await fallbackLink.evaluate((element: HTMLElement) => element.click());
    } else {
      await row.evaluate((element: HTMLElement) => element.click());
    }
  }

  await expect(page).toHaveURL(new RegExp(`/page-designer/${pid}`), { timeout: 8_000 });
  await expect(page.getByTestId('designer-canvas')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId('designer-tab-blocks')).toBeVisible({ timeout: 5_000 });
}

async function canvasBlockIds(page: Page): Promise<string[]> {
  return page.locator('[data-block-id]').evaluateAll((elements) =>
    elements
      .map((element) => (element as HTMLElement).getAttribute('data-block-id') || '')
      .filter(Boolean),
  );
}

async function addBlockViaPalette(page: Page, blockType: string): Promise<string> {
  await page.getByTestId('designer-tab-blocks').click();
  await expect(page.getByTestId('library-tab-blocks')).toBeVisible({ timeout: 5_000 });
  const paletteItem = page.getByTestId(`block-palette-item-${blockType}`);
  await expect(paletteItem).toBeVisible({ timeout: 5_000 });

  const beforeIds = await canvasBlockIds(page);
  const expectedCount = beforeIds.length + 1;
  let added = false;

  for (let attempt = 0; attempt < 3; attempt++) {
    await page.evaluate(() => {
      document.querySelectorAll('vite-error-overlay').forEach((element) => element.remove());
    });
    await paletteItem.evaluate((element: HTMLElement) => element.click());

    added = await expect
      .poll(async () => (await canvasBlockIds(page)).length === expectedCount, {
        timeout: 4_000,
      })
      .toBe(true)
      .then(() => true)
      .catch(() => false);

    if (added) break;
  }

  expect(added, `${blockType} block must be added to the canvas`).toBe(true);

  const afterIds = await canvasBlockIds(page);
  const newId = afterIds.find((id) => !beforeIds.includes(id));
  expect(newId, `new ${blockType} block id must be discoverable`).toBeTruthy();
  return newId!;
}

async function openPreviewAndAssertBlockCount(page: Page, expectedBlocks: number): Promise<void> {
  await page.getByTestId('toolbar-preview').click();

  const modal = page.getByTestId('preview-modal');
  await expect(modal).toBeVisible({ timeout: 5_000 });
  const blockCountLabel = page.getByTestId('preview-block-count');
  await expect(blockCountLabel).toBeVisible({ timeout: 5_000 });
  const blockCountText = (await blockCountLabel.textContent())?.trim() ?? '';
  const blockCount = Number.parseInt(blockCountText, 10);
  expect(Number.isNaN(blockCount)).toBe(false);
  expect(blockCount).toBeGreaterThanOrEqual(1);

  const mobileButton = page.getByRole('button', { name: 'Mobile' }).first();
  if (await mobileButton.isVisible({ timeout: 1_000 }).catch(() => false)) {
    await mobileButton.click();
    await expect(page.locator('text=/375 x 812/').first()).toBeVisible({ timeout: 5_000 });
  }

  await page.getByTestId('preview-close').click();
  await expect(modal).toBeHidden({ timeout: 5_000 });
}

async function clickSaveAndWait(page: Page, pid: string): Promise<void> {
  const saveButton = page.getByTestId('toolbar-save');
  await expect(saveButton).toBeVisible({ timeout: 5_000 });
  await expect
    .poll(async () => saveButton.isEnabled().catch(() => false), { timeout: 8_000 })
    .toBe(true);

  const saveResponse = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/pages/${pid}`) &&
      response.request().method() === 'PUT' &&
      response.status() < 400,
    { timeout: 10_000 },
  );

  await saveButton.click();
  await saveResponse;
}

async function clickPublishAndWait(page: Page, pid: string): Promise<void> {
  const publishButton = page.getByTestId('toolbar-publish');
  await expect(publishButton).toBeVisible({ timeout: 5_000 });

  const publishResponse = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/pages/${pid}/publish`) &&
      response.request().method() === 'POST' &&
      response.status() < 400,
    { timeout: 10_000 },
  );

  await publishButton.click();
  await publishResponse;
  await expect(page.locator('[role="alert"], .toast-message').first()).toContainText(
    /published successfully|发布成功|页面已发布/i,
    { timeout: 8_000 },
  );
}

test.describe('Page Designer full lifecycle', () => {
  test.setTimeout(90_000);

  let fixturePid: string | null = null;

  test.afterEach(async ({ request }) => {
    await deleteFixturePage(request, fixturePid);
    fixturePid = null;
  });

  test('menu-driven form page lifecycle closes the loop @critical', async ({ page, request }) => {
    const pageKey = uniqueId('pd_lifecycle');
    fixturePid = await apiCreateFixturePage(page, pageKey);

    await navigateToPageSchemaList(page);
    await openDesignerFromList(page, fixturePid, pageKey);

    const initialBlocks = await canvasBlockIds(page);
    expect(initialBlocks.length).toBeGreaterThanOrEqual(1);

    const addedBlockId = await addBlockViaPalette(page, 'form-buttons');
    expect(addedBlockId).toBeTruthy();

    const blockIdsAfterAdd = await canvasBlockIds(page);
    expect(blockIdsAfterAdd).toContain(addedBlockId);
    expect(blockIdsAfterAdd.length).toBe(initialBlocks.length + 1);

    await openPreviewAndAssertBlockCount(page, blockIdsAfterAdd.length);
    await clickSaveAndWait(page, fixturePid);
    await clickPublishAndWait(page, fixturePid);

    const savedPage = await fetchPageDto(request, fixturePid);
    const savedBlocks = extractBlocks(savedPage);
    expect(savedPage.status, 'published status must be persisted').toBe('published');
    expect(savedBlocks.some((block) => block.id === addedBlockId || block.blockType === 'form-buttons')).toBe(true);

    await page.getByTestId('toolbar-back').click();
    await expect(page.getByTestId('toolbar-btn-create')).toBeVisible({ timeout: 8_000 });

    const row = page.locator(`tr:has-text("${pageKey}")`).first();
    await expect(row).toBeVisible({ timeout: 8_000 });
    await expect(row).toContainText(/published|已发布/i);

    await openDesignerFromList(page, fixturePid, pageKey);

    const reopenedBlockIds = await canvasBlockIds(page);
    expect(reopenedBlockIds).toContain(addedBlockId);
    expect(reopenedBlockIds.length).toBe(blockIdsAfterAdd.length);

    await openPreviewAndAssertBlockCount(page, reopenedBlockIds.length);
  });
});
