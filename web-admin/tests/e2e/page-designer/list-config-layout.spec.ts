import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

let pagePid: string;

test.beforeAll(async ({ browser }) => {
  const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
  const page = await ctx.newPage();
  const name = uniqueId('list-layout');
  const pageKey = `e2e_list_layout_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;

  const resp = await page.request.post('/api/pages', {
    data: {
      name,
      pageKey,
      title: name,
      kind: 'list',
      modelCode: 'tenant',
      blocks: [
        {
          blockType: 'filters',
          fields: ['name'],
          actions: ['search', 'reset'],
        },
        {
          blockType: 'toolbar',
          buttons: [{ preset: 'create' }],
        },
        {
          blockType: 'table',
          dataSource: 'tableData',
          columns: [{ field: 'name', width: 180 }, 'createdAt'],
          props: {
            pageSize: 20,
            multiSelect: false,
            rowClickAction: 'detail',
          },
        },
      ],
      metaInfo: { componentCount: 3 },
      semver: '0.1.0',
    },
  });

  expect(resp.ok(), `Create fixture page failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'API code must be 0').toBe('0');
  pagePid = body.data.pid as string;
  await ctx.close();
});

test.describe('List Designer Layout', () => {
  test('shows the new shell with workflow navigation and preview rail', async ({ page }) => {
    await page.goto(`/page-designer/${pagePid}`, { waitUntil: 'domcontentloaded' });

    await expect(page.locator('[data-testid="list-config-panel"]')).toBeVisible({ timeout: 15000 });
    await expect(page.locator('[data-testid="list-designer-summary"]')).toBeVisible();
    await expect(page.locator('[data-testid="list-designer-workspace"]')).toBeVisible();
    await expect(page.locator('[data-testid="list-preview-pane"]')).toBeVisible();
    await expect(page.getByText('先定义信息层级，再补充筛选与动作')).toBeVisible();
    await expect(page.getByText('列表结构预览')).toBeVisible();

    await expect(page.locator('[data-testid="list-tab-columns"]')).toContainText('列结构');
    await expect(page.locator('[data-testid="list-tab-filters"]')).toContainText('筛选器');
    await expect(page.locator('[data-testid="list-tab-toolbar"]')).toContainText('工具栏');
    await expect(page.locator('[data-testid="list-tab-behavior"]')).toContainText('交互行为');

    await page.locator('[data-testid="list-tab-filters"]').click();
    await expect(page.locator('[data-testid="list-designer-summary"]')).toContainText('筛选器');
    await expect(page.locator('[data-testid="filters-tab"]')).toBeVisible();
    await expect(page.locator('[data-testid="list-preview-pane"]')).toBeVisible();

    await page.locator('[data-testid="list-tab-toolbar"]').click();
    await expect(page.locator('[data-testid="toolbar-tab"]')).toBeVisible();
    await expect(page.locator('[data-testid="list-preview-pane"]')).toContainText('加载样例数据');

    await page.locator('[data-testid="list-tab-behavior"]').click();
    await expect(page.locator('[data-testid="behavior-tab"]')).toBeVisible();
  });

  test('keeps the editor usable on a narrow viewport', async ({ page }) => {
    await page.setViewportSize({ width: 1180, height: 900 });
    await page.goto(`/page-designer/${pagePid}`, { waitUntil: 'domcontentloaded' });

    await expect(page.locator('[data-testid="list-config-panel"]')).toBeVisible({ timeout: 15000 });
    await expect(page.locator('[data-testid="list-designer-summary"]')).toBeVisible();
    await expect(page.locator('[data-testid="list-designer-workspace"]')).toBeVisible();
    await expect(page.locator('[data-testid="list-preview-pane"]')).toBeHidden();

    await page.locator('[data-testid="list-tab-toolbar"]').click();
    await expect(page.locator('[data-testid="toolbar-tab"]')).toBeVisible();
    await expect(page.locator('[data-testid="toolbar-custom-editor"]')).toBeVisible();
  });
});
