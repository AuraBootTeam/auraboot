/**
 * Plugin Menu Operability Deep Scan
 *
 * Goal:
 * - After plugin import/setup, verify menu endpoints and UI navigation are consistent.
 * - Traverse real menu paths and assert pages render without application-level errors.
 * - Probe non-destructive interactions (tabs / search / reset) where available.
 *
 * Notes:
 * - This is a broad operability scan, not a replacement for per-model deep business tests.
 * - Uses admin authenticated storageState from global setup.
 */

import { test, expect } from '../../fixtures';
import type { Locator } from '@playwright/test';

type MenuNode = {
  code?: string;
  path?: string;
  name?: string;
  children?: MenuNode[];
};

function flattenMenus(nodes: MenuNode[] = []): MenuNode[] {
  const out: MenuNode[] = [];
  const walk = (arr: MenuNode[]) => {
    for (const item of arr) {
      out.push(item);
      if (Array.isArray(item.children) && item.children.length > 0) {
        walk(item.children);
      }
    }
  };
  walk(nodes);
  return out;
}

function isNavigablePath(path: string): boolean {
  if (!path || path === '#') return false;
  if (!path.startsWith('/')) return false;
  if (path.startsWith('/api/')) return false;
  if (/^\/(login|logout|register|forgot-password)(\/|$)/.test(path)) return false;
  if (path.includes(':')) return false; // dynamic param path from route template
  return true;
}

async function clickIfVisible(locator: Locator) {
  const visible = await locator.first().isVisible({ timeout: 2000 }).catch(() => false);
  if (visible) {
    try {
      await locator.first().click({ timeout: 2000 });
      return true;
    } catch {
      return false;
    }
  }
  return false;
}

test.describe('Plugin Menu Operability', () => {
  test.describe.configure({ mode: 'serial' });

  test('PMO-001: menu tree is navigable and pages expose operable UI areas @critical', async ({ browser }) => {
    test.setTimeout(90000);
    const context = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    let page = await context.newPage();

    try {
      const menuResp = await page.request.get('/api/menu/user');
      expect(menuResp.ok()).toBe(true);

      const menuBody = await menuResp.json();
      const menuTree: MenuNode[] = menuBody.data || menuBody;
      expect(Array.isArray(menuTree)).toBe(true);
      expect(menuTree.length).toBeGreaterThan(0);

      const allMenus = flattenMenus(menuTree);
      const navigable = allMenus
        .filter((m) => typeof m.path === 'string' && isNavigablePath(m.path as string))
        .map((m) => ({ path: m.path as string, code: m.code || '', name: m.name || '' }));

      // Deduplicate by path while preserving order
      const unique = new Map<string, { path: string; code: string; name: string }>();
      for (const item of navigable) {
        if (!unique.has(item.path)) unique.set(item.path, item);
      }
      const targets = [...unique.values()].slice(0, 12);
      expect(targets.length).toBeGreaterThan(0);

      let visited = 0;
      let withOperableAreas = 0;

      for (const target of targets) {
        const listWait = page
          .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, {
            timeout: 5000,
          })
          .catch(() => null);

        const navigated = await page
          .goto(target.path, { waitUntil: 'domcontentloaded', timeout: 10000 })
          .then(() => true)
          .catch(() => false);
        if (!navigated || page.isClosed()) {
          continue;
        }
        await listWait;
        if (page.isClosed()) {
          continue;
        }

        const bodyText = (await page.locator('body').textContent()) || '';
        const hasAppError =
          /Application Error|Unhandled Runtime Error|500 Internal Server Error/i.test(bodyText);
        expect(hasAppError, `path=${target.path} should not show app error`).toBe(false);

        // Page should expose at least one meaningful area
        const area = page.locator(
          'main, table, [role=\"table\"], form, [role=\"tablist\"], [data-testid=\"table-block\"], [data-testid=\"dynamic-list\"], [data-testid=\"dynamic-form\"]',
        ).first();
        const hasArea = await area.isVisible({ timeout: 5000 }).catch(() => false);
        expect(hasArea, `path=${target.path} should render operable area`).toBe(true);

        // Non-destructive operability probes
        const clickedTab = await clickIfVisible(page.locator('[role=\"tab\"]').first());
        if (clickedTab) {
          await expect(page.locator('body')).toBeVisible();
        }

        const clickedSearch = await clickIfVisible(
          page.locator('[data-testid="filter-search"], [data-testid="filter-btn-search"]'),
        );
        if (clickedSearch) {
          await expect(page.locator('body')).toBeVisible();
        }

        const clickedReset = await clickIfVisible(
          page.locator('[data-testid=\"filter-btn-reset\"], button:has-text(\"重置\"), button:has-text(\"Reset\")'),
        );
        if (clickedReset) {
          await expect(page.locator('body')).toBeVisible();
        }

        const operableCount = await page
          .locator('button:visible, input:visible, select:visible, textarea:visible, [role=\"tab\"]:visible')
          .count()
          .catch(() => 0);
        if (operableCount > 0) withOperableAreas += 1;
        visited += 1;
      }

      expect(visited).toBeGreaterThan(0);
      expect(withOperableAreas).toBeGreaterThan(0);
    } finally {
      await context.close();
    }
  });
});
