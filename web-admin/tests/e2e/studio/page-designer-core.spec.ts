/**
 * Page Designer — Core Interaction Tests
 *
 * 每个测试验证"操作→结果"，不检查元素存在性。
 * 删除任何核心功能代码，对应测试必定失败。
 *
 * @since 4.2.0
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers/index';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function createBlankPage(page: Page): Promise<string> {
  const name = uniqueId('core');
  const pageKey = `e2e_core_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;
  const resp = await page.request.post('/api/pages', {
    data: { name, pageKey, title: name, kind: 'list', blocks: [], metaInfo: { componentCount: 0 }, semver: '0.1.0' },
  });
  expect(resp.ok(), `createBlankPage failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  return body.data.pid;
}

async function createPageWithBlock(page: Page, blockType: string): Promise<string> {
  const name = uniqueId('core');
  const pageKey = `e2e_core_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;
  const resp = await page.request.post('/api/pages', {
    data: {
      name, pageKey, title: name, kind: 'list',
      blocks: [{ id: 'blk_pre', blockType, config: {}, layout: { col: 0, colSpan: 12, rowSpan: 1, order: 0 } }],
      metaInfo: { componentCount: 1 }, semver: '0.1.0',
    },
  });
  expect(resp.ok(), `createPageWithBlock(${blockType}) failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  return body.data.pid;
}

async function open(page: Page, pid: string) {
  await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
  await page.getByTestId('designer-canvas').waitFor({ state: 'visible', timeout: 15000 });
}

async function blockCount(page: Page): Promise<number> {
  return page.locator(BLOCK_SELECTOR).count();
}

async function addBlock(page: Page, type: string) {
  const countBefore = await blockCount(page);
  // Switch to blocks tab then click palette item (click-to-add via onAddBlock)
  const paletteItem = page.getByTestId(`block-palette-item-${type}`);

  const ensureClicked = async () => {
    await page.getByTestId('designer-tab-blocks').click();
    await paletteItem.click();
    return page
      .waitForFunction(
        ({ selector, expected }) =>
          document.querySelectorAll(selector).length >= expected,
        { selector: BLOCK_SELECTOR, expected: countBefore + 1 },
        { timeout: 2000 },
      )
      .then(() => true)
      .catch(() => false);
  };

  const ok = (await ensureClicked()) || (await ensureClicked());
  if (!ok) {
    throw new Error(
      `addBlock(${type}): block count did not advance from ${countBefore} after 2 click attempts`,
    );
  }
}

// BlocksDesigner uses data-testid="sortable-block" on each block wrapper
const BLOCK_SELECTOR = '[data-testid="sortable-block"]';

async function selectFirstBlock(page: Page) {
  await page.locator(BLOCK_SELECTOR).first().click();
  await page.waitForTimeout(200);
}

// ---------------------------------------------------------------------------
// 1. 添加 Block — 点击组件 → canvas 数量 +1
// ---------------------------------------------------------------------------

test.describe('添加 Block', () => {
  test('点击 Table → canvas 从 0 变 1', async ({ page }) => {
    const pid = await createBlankPage(page);
    await open(page, pid);

    expect(await blockCount(page)).toBe(0); // blank page = 0 blocks
    await addBlock(page, 'table');
    expect(await blockCount(page)).toBe(1); // 0 + 1 added
  });

  test('连续添加 3 个 stat-card → 数量变 3', async ({ page }) => {
    const pid = await createBlankPage(page);
    await open(page, pid);

    await addBlock(page, 'stat-card');
    await addBlock(page, 'stat-card');
    await addBlock(page, 'stat-card');
    expect(await blockCount(page)).toBe(3); // 0 + 3 added
  });
});

// ---------------------------------------------------------------------------
// 2. 一行多列 — stat-card 并排
// ---------------------------------------------------------------------------

test.describe('一行多列', () => {
  test('3 个 stat-card 在同一行（y 坐标相同）', async ({ page }) => {
    const pid = await createBlankPage(page);
    await open(page, pid);

    await addBlock(page, 'stat-card');
    await addBlock(page, 'stat-card');
    await addBlock(page, 'stat-card');

    // 等待 RGL 渲染完成
    await page.waitForTimeout(500);

    const items = page.locator('.react-grid-item');
    const count = await items.count();
    expect(count).toBe(3); // 3 stat-cards

    // 3 个 stat-card 的 y 坐标应该相同（同一行）
    const boxes = await Promise.all(
      Array.from({ length: 3 }, (_, i) => items.nth(i).boundingBox()),
    );

    for (const box of boxes) {
      expect(box).not.toBeNull();
    }

    // 同一行：y 坐标相同（允许 2px 误差）
    expect(Math.abs(boxes[0]!.y - boxes[1]!.y)).toBeLessThan(2);
    expect(Math.abs(boxes[1]!.y - boxes[2]!.y)).toBeLessThan(2);

    // 从左到右排列
    expect(boxes[1]!.x).toBeGreaterThan(boxes[0]!.x);
    expect(boxes[2]!.x).toBeGreaterThan(boxes[1]!.x);
  });

  test('table(12col) + chart(6col) → chart 在新行', async ({ page }) => {
    const pid = await createBlankPage(page);
    await open(page, pid);

    await addBlock(page, 'table');
    await addBlock(page, 'chart');
    await page.waitForTimeout(500);

    const items = page.locator('.react-grid-item');
    // table at 0, chart at 1
    const box1 = await items.nth(0).boundingBox();
    const box2 = await items.nth(1).boundingBox();

    // chart 在 table 下方（y 坐标更大）
    expect(box2!.y).toBeGreaterThan(box1!.y);
  });
});

// ---------------------------------------------------------------------------
// 3. Widget 添加到 form-section
// ---------------------------------------------------------------------------

test.describe('Widget 添加', () => {
  test('选中 form-section → 点击 widget → 字段加入已有 block（不创建新 block）', async ({ page }) => {
    const pid = await createBlankPage(page);
    await open(page, pid);

    // 先添加一个 form-section
    await addBlock(page, 'form-section');
    expect(await blockCount(page)).toBe(1); // blank page + 1 added

    // 选中这个 form-section
    await selectFirstBlock(page);

    // 切到 Widgets tab → 点击 Text Input
    await page.getByTestId('designer-tab-fields').click();
    await page.getByTestId('widget-palette-item-text').click();
    await page.waitForTimeout(300);

    // 关键：block 数量不变（widget 加入已有 form-section，不创建新 block）
    expect(await blockCount(page)).toBe(1); // still 1 form-section

    // form-section 内应该have widget field (renders as "Text Input" via WidgetRegistry)
    const blockContent = page.locator('[data-testid="sortable-block"]').first();
    await expect(blockContent.locator('text=/Text Input|widget_/')).toBeVisible({ timeout: 3000 });
  });

  test('无选中 → 点击 widget → 创建新 form-section', async ({ page }) => {
    const pid = await createPageWithBlock(page, 'table');
    await open(page, pid);

    expect(await blockCount(page)).toBe(1);

    // 不选中任何 block → 点击空白
    await page.getByTestId('canvas-body').click();
    await page.waitForTimeout(200);

    // 切到 Widgets → 点击 Number
    await page.getByTestId('designer-tab-fields').click();
    await page.getByTestId('widget-palette-item-number').click();
    await page.waitForTimeout(300);

    // 新建了一个 form-section
    expect(await blockCount(page)).toBe(2);
  });
});

// ---------------------------------------------------------------------------
// 4. Block 删除
// ---------------------------------------------------------------------------

test.describe('Block 删除', () => {
  test('删除 block → 数量 -1', async ({ page }) => {
    const pid = await createBlankPage(page);
    await open(page, pid);

    await addBlock(page, 'table');
    await addBlock(page, 'chart');
    expect(await blockCount(page)).toBe(2); // blank page + 2 added

    // 删除第一个
    await page.locator('[data-testid="block-delete"]').first().click();
    expect(await blockCount(page)).toBe(1);
  });
});

// ---------------------------------------------------------------------------
// 5. Block 选中 → 右面板切换
// ---------------------------------------------------------------------------

test.describe('选中与面板', () => {
  test('选 table → 面板显示 table config；选 chart → 切换到 chart config', async ({ page }) => {
    const pid = await createBlankPage(page);
    await open(page, pid);

    await addBlock(page, 'table');
    await addBlock(page, 'chart');

    // 点击第一个 block (default table)
    await page.locator(BLOCK_SELECTOR).first().click();
    await expect(page.getByTestId('table-schema-config')).toBeVisible({ timeout: 3000 });

    // 点击最后一个 block (chart — index 1, after added table)
    await page.locator(BLOCK_SELECTOR).nth(1).click();
    await expect(page.getByTestId('chart-schema-config')).toBeVisible({ timeout: 3000 });

    // 点击空白 → 面板显示 empty state
    await page.getByTestId('canvas-body').click();
    await expect(page.getByTestId('block-config-empty')).toBeVisible({ timeout: 3000 });
  });
});

// ---------------------------------------------------------------------------
// 6. Layout section — 修改 colSpan → canvas 宽度变化
// ---------------------------------------------------------------------------

test.describe('Layout 属性', () => {
  test('改 colSpan 6→12 → block 变宽', async ({ page }) => {
    const pid = await createBlankPage(page);
    await open(page, pid);

    await addBlock(page, 'chart'); // 默认 6col
    // Select the chart block (last one, after the default block)
    await page.locator(BLOCK_SELECTOR).last().click();
    await page.waitForTimeout(200);

    // 记录初始宽度 (chart is the last .react-grid-item)
    const itemBefore = await page.locator('.react-grid-item').last().boundingBox();
    expect(itemBefore).not.toBeNull();

    // 修改 colSpan 为 12
    const colSpanInput = page.getByTestId('layout-colSpan');
    await colSpanInput.fill('12');
    await colSpanInput.press('Tab'); // 触发 onChange
    await page.waitForTimeout(500);

    // 验证宽度变大
    const itemAfter = await page.locator('.react-grid-item').last().boundingBox();
    expect(itemAfter).not.toBeNull();
    expect(itemAfter!.width).toBeGreaterThan(itemBefore!.width);

    // badge 显示 12col
    const badge = page.locator(BLOCK_SELECTOR).last().locator('text=/12col/');
    await expect(badge).toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// 7. 属性修改持久化 — 改值 → 切走 → 切回 → 值还在
// ---------------------------------------------------------------------------

test.describe('属性持久化', () => {
  test('改 chartType 为 Pie → 切走再切回 → 仍然是 Pie', async ({ page }) => {
    const pid = await createBlankPage(page);
    await open(page, pid);

    await addBlock(page, 'table');
    await addBlock(page, 'chart');

    // 选 chart (last block — index 1, after added table)
    await page.locator(BLOCK_SELECTOR).nth(1).click();
    await expect(page.getByTestId('chart-schema-config')).toBeVisible({ timeout: 3000 });

    // 改 chartType 为 Pie
    const chartTypeSelect = page.locator('#chartType');
    await chartTypeSelect.click();
    await page.getByRole('option', { name: 'Pie', exact: true }).click();

    // 切到 table (first block — the default)
    await page.locator(BLOCK_SELECTOR).first().click();
    await expect(page.getByTestId('table-schema-config')).toBeVisible({ timeout: 3000 });

    // 切回 chart
    await page.locator(BLOCK_SELECTOR).nth(1).click();
    await expect(page.getByTestId('chart-schema-config')).toBeVisible({ timeout: 3000 });

    // chartType 应该还是 Pie
    const currentValue = await chartTypeSelect.textContent();
    expect(currentValue).toContain('Pie');
  });
});

// ---------------------------------------------------------------------------
// 8. Outline 同步
// ---------------------------------------------------------------------------

test.describe('Outline 同步', () => {
  test('添加 3 个 block → outline 显示 3 项 → 删 1 个 → 变 2 项', async ({ page }) => {
    const pid = await createBlankPage(page);
    await open(page, pid);

    await addBlock(page, 'table');
    await addBlock(page, 'chart');
    await addBlock(page, 'form-section');

    // 切到 Outline tab
    await page.getByTestId('designer-tab-outline').click();

    // 应有 3 项 (blank page + 3 added)
    const outlineItems = page.getByTestId('outline-panel').locator('[data-testid^="outline-item-"]');
    await expect(outlineItems).toHaveCount(3, { timeout: 5000 });

    // 删除第一个 canvas block
    await page.locator('[data-testid="block-delete"]').first().click();
    await page.waitForTimeout(300);

    // outline 应变 2 项
    await expect(outlineItems).toHaveCount(2, { timeout: 5000 });
  });
});

// ---------------------------------------------------------------------------
// 9. Grid 网格线可见
// ---------------------------------------------------------------------------

test.describe('Grid 网格线', () => {
  test('canvas 有 block 时 grid overlay 可见且 pointer-events:none', async ({ page }) => {
    const pid = await createPageWithBlock(page, 'table');
    await open(page, pid);

    const overlay = page.getByTestId('canvas-grid-overlay');
    await expect(overlay).toBeVisible();

    // pointer-events:none 确保不拦截点击
    const pe = await overlay.evaluate((el) => window.getComputedStyle(el).pointerEvents);
    expect(pe).toBe('none');

    // 点击 block 仍然能选中（不被 overlay 阻挡）
    await page.locator(BLOCK_SELECTOR).first().click();
    await expect(page.locator('.react-grid-item.selected')).toBeVisible({ timeout: 2000 });
  });
});

// ---------------------------------------------------------------------------
// 10. Auto-save — 操作后 DSL 保存到后端
// ---------------------------------------------------------------------------

test.describe('Auto-save', () => {
  test('添加 block 后等 auto-save → 重新打开 → block 还在', async ({ page }) => {
    const pid = await createBlankPage(page);
    await open(page, pid);

    await addBlock(page, 'chart');
    expect(await blockCount(page)).toBe(1); // blank page + 1 added

    // Wait for auto-save: intercept the save API call to confirm it completed
    const savePromise = page.waitForResponse(
      (resp) => resp.url().includes('/api/pages/') && resp.request().method() === 'PUT' && resp.status() === 200,
      { timeout: 10000 },
    ).catch(() => null);

    // Also wait a bit for the debounce to trigger
    await page.waitForTimeout(3000);
    await savePromise;

    // Extra wait to ensure save completes
    await page.waitForTimeout(500);

    // 重新打开同一页面
    await open(page, pid);

    // block 应该还在
    expect(await blockCount(page)).toBe(1); // blank page + 1 added
  });
});

// ---------------------------------------------------------------------------
// 11. 工具栏对话框
// ---------------------------------------------------------------------------

test.describe('工具栏', () => {
  test.fixme('AI 按钮 → 对话框开关', async ({ page }) => {
    // The toolbar AI button calls onToggleAiPanel (side panel), not showAiGenerate (dialog).
    // AiPageGenerateDialog only opens as fallback when onToggleAiPanel is NOT provided.
    const pid = await createBlankPage(page);
    await open(page, pid);
    await page.getByTestId('toolbar-ai-generate').click();
    await expect(page.getByTestId('ai-page-generate-dialog')).toBeVisible();
  });
});
