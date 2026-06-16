import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  GERBER_RUNTIME_BOTTOM_FILE_ID,
  GERBER_RUNTIME_TOP_FILE_ID,
  seedGerberRuntimeQuote,
} from './quote-e2e-helpers';

const VIEWER_TOKEN = 'e2e-gerber-viewer-token';

const boardSvg = (label: string) => `
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 420 180">
  <rect width="420" height="180" fill="#0f3d1f"/>
  <rect x="12" y="12" width="396" height="156" rx="8" fill="#1f6f3a" stroke="#d9f99d" stroke-width="4"/>
  <circle cx="60" cy="60" r="18" fill="#93c5fd"/>
  <circle cx="300" cy="120" r="18" fill="#facc15"/>
  <text x="210" y="96" text-anchor="middle" fill="#ffffff" font-size="32">${label}</text>
</svg>`;

test.describe('PCBA quote Gerber runtime viewer', () => {
  test.describe.configure({ timeout: 120_000 });

  test('renders persisted dynamic-line SVG previews through authenticated file downloads @smoke', async ({
    page,
  }) => {
    const created = await seedGerberRuntimeQuote(page);
    const fileRequests: Array<{ fileId: string; authorization: string; cookie: string }> = [];

    await page.addInitScript(
      ({ key, token }) => {
        window.localStorage.setItem(key, token);
        window.sessionStorage.setItem(key, token);
      },
      { key: 'jwtToken', token: VIEWER_TOKEN },
    );

    await page.route(
      new RegExp(
        `/api/file/download/(${GERBER_RUNTIME_TOP_FILE_ID}|${GERBER_RUNTIME_BOTTOM_FILE_ID})$`,
      ),
      async (route) => {
        const url = route.request().url();
        const fileId = url.endsWith(GERBER_RUNTIME_TOP_FILE_ID)
          ? GERBER_RUNTIME_TOP_FILE_ID
          : GERBER_RUNTIME_BOTTOM_FILE_ID;
        const headers = route.request().headers();
        fileRequests.push({
          fileId,
          authorization: headers.authorization || '',
          cookie: headers.cookie || '',
        });

        await route.fulfill({
          status: 200,
          contentType: 'image/svg+xml',
          body: boardSvg(fileId === GERBER_RUNTIME_TOP_FILE_ID ? 'TOP' : 'BOTTOM'),
        });
      },
    );

    try {
      const lineListResponse = page.waitForResponse((response) => {
        const url = decodeURIComponent(response.url());
        return (
          response.status() === 200 &&
          url.includes('/api/dynamic/qo_quote_line_common/list') &&
          url.includes(created.quoteId)
        );
      });

      await page.goto(`/p/qo_quote_common/view/${created.quoteId}#spec_process`, {
        waitUntil: 'domcontentloaded',
      });

      const lineListBody = await (await lineListResponse).json();
      const serializedLineList = JSON.stringify(lineListBody);
      expect(serializedLineList).toContain('E2E-GERBER-RUNTIME');
      expect(serializedLineList).toContain(GERBER_RUNTIME_TOP_FILE_ID);
      expect(serializedLineList).toContain(GERBER_RUNTIME_BOTTOM_FILE_ID);

      const viewer = page.getByTestId('gerber-viewer');
      await expect(viewer).toContainText('E2E-GERBER-RUNTIME', { timeout: 30_000 });
      await expect(viewer).toContainText('Dynamic line persisted Gerber inspection');
      await expect(page.getByTestId('gerber-svg-unavailable')).toHaveCount(0);

      await expect(page.getByRole('img', { name: 'Top Gerber board render' })).toBeVisible({
        timeout: 30_000,
      });
      await expect
        .poll(() => fileRequests.some((request) => request.fileId === GERBER_RUNTIME_TOP_FILE_ID))
        .toBe(true);
      const topRequest = fileRequests.find(
        (request) => request.fileId === GERBER_RUNTIME_TOP_FILE_ID,
      );
      expect(topRequest?.authorization).toBe(`Bearer ${VIEWER_TOKEN}`);
      expect(topRequest?.cookie).toContain('__session=');

      await page.getByRole('button', { name: 'Bottom' }).click();
      await expect(page.getByRole('img', { name: 'Bottom Gerber board render' })).toBeVisible({
        timeout: 30_000,
      });
      await expect
        .poll(() =>
          fileRequests.some((request) => request.fileId === GERBER_RUNTIME_BOTTOM_FILE_ID),
        )
        .toBe(true);
      const bottomRequest = fileRequests.find(
        (request) => request.fileId === GERBER_RUNTIME_BOTTOM_FILE_ID,
      );
      expect(bottomRequest?.authorization).toBe(`Bearer ${VIEWER_TOKEN}`);
      expect(bottomRequest?.cookie).toContain('__session=');
    } finally {
      await cleanupRows(page, created);
    }
  });
});
