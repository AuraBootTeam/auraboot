import { expect, test } from '@playwright/test';

const routes = [
  { path: '/ai/colleagues', expectedMaxWidth: 1280 },
  { path: '/scheduler', expectedMaxWidth: 1280 },
  { path: '/audit-logs', expectedMaxWidth: 1024 },
  { path: '/documents', expectedMaxWidth: 1024 },
];

test.describe('Page width layout', () => {
  test('top-level routed pages use the available content width', async ({ page }) => {
    for (const route of routes) {
      await page.goto(route.path, { waitUntil: 'domcontentloaded' });
      expect(new URL(page.url()).pathname).toBe(route.path);
      await expect(page.locator('h1').first()).toBeVisible({ timeout: 10_000 });

      const layout = await page.evaluate(() => {
        const main = document.querySelector('main');
        const pageRoot = main?.firstElementChild?.firstElementChild;
        const mainBox = main?.getBoundingClientRect();
        const pageRootBox = pageRoot?.getBoundingClientRect();

        return {
          mainWidth: mainBox?.width ?? 0,
          pageRootWidth: pageRootBox?.width ?? 0,
          pageRootClassName: pageRoot?.getAttribute('class') ?? '',
        };
      });

      const expectedWidth = Math.min(layout.mainWidth, route.expectedMaxWidth);

      expect(
        layout.pageRootWidth,
        `${route.path} root (${layout.pageRootClassName}) should not shrink below its intended layout width`,
      ).toBeGreaterThanOrEqual(expectedWidth - 4);
    }
  });
});
