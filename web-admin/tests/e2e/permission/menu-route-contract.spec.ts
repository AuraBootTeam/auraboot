/**
 * Menu Route Contract Deep E2E
 *
 * Contract:
 * 1) Every sampled leaf menu path from /api/menu/user should be resolvable by /api/menu/by-path.
 * 2) Navigating to those paths should not crash (Application Error / Runtime Error).
 * 3) Page should expose either business content area or explicit access-state UI.
 */

import { test, expect } from '../../fixtures';

type MenuNode = {
  path?: string;
  children?: MenuNode[];
};

function collectLeafPaths(nodes: MenuNode[] = [], acc: string[] = []): string[] {
  for (const node of nodes) {
    const children = Array.isArray(node.children) ? node.children : [];
    if (typeof node.path === 'string' && node.path.startsWith('/') && node.path !== '#' && children.length === 0) {
      if (!node.path.includes(':')) acc.push(node.path);
    }
    if (children.length > 0) collectLeafPaths(children, acc);
  }
  return acc;
}

test.describe('Menu Route Contract', () => {
  test.describe.configure({ mode: 'serial' });

  test('MRC-001: menu leaf paths are resolvable and navigable @critical', async ({ page }) => {
    test.setTimeout(90000);

    const menuResp = await page.request.get('/api/menu/user');
    expect(menuResp.ok()).toBe(true);

    const menuBody = await menuResp.json();
    const tree: MenuNode[] = menuBody.data || menuBody;
    expect(Array.isArray(tree)).toBe(true);

    const leafPaths = [...new Set(collectLeafPaths(tree))];
    expect(leafPaths.length).toBeGreaterThan(0);

    // Cap sample size for deterministic runtime in critical profile
    const sample = leafPaths.slice(0, 20);

    for (const path of sample) {
      // API contract: menu/by-path should resolve known path
      const byPath = await page.request.get(`/api/menu/by-path?path=${encodeURIComponent(path)}`, { timeout: 8000 });
      expect(byPath.ok(), `menu/by-path should resolve: ${path}`).toBe(true);

      await page.goto(path, { waitUntil: 'domcontentloaded' });

      const bodyText = (await page.locator('body').textContent()) || '';
      const hasCrash = /Application Error|Unhandled Runtime Error|TypeError:|ReferenceError:/i.test(bodyText);
      expect(hasCrash, `path ${path} should not crash`).toBe(false);

      // Valid states:
      // - business content rendered
      // - explicit access-state/404 rendered
      const hasBusinessContent = await page.locator(
        'main, table, [role="table"], form, [role="tablist"], [data-testid="dynamic-list"], [data-testid="dynamic-form"]',
      ).first().isVisible({ timeout: 5000 }).catch(() => false);

      const hasAccessState = /403|Forbidden|无权限|权限不足|404|Not Found|页面不存在|加载失败|Page not found/i.test(bodyText);
      expect(hasBusinessContent || hasAccessState, `path ${path} should present business content or explicit state`).toBe(true);
    }
  });
});
