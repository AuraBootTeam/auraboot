import fs from 'node:fs';
import path from 'node:path';
import { expect, test, type Page } from '../../fixtures';

/**
 * PAR-25 dashboard favorites slice. A favorite is tenant-scoped user
 * preference data, not a dashboard mutation. The journey proves collect,
 * persisted collect-page filtering, and un-collect on the real viewer.
 */

const RUN_ID = `par25-fav-${Date.now()}`;
const DASHBOARDS_PATH = '/dashboards';
const EVIDENCE_ROOT = process.env.AURA_EVIDENCE_ROOT
  ? path.join(process.env.AURA_EVIDENCE_ROOT, 'par25-dashboard-favorites')
  : path.resolve(
      process.cwd(),
      '..',
      '.workspace',
      'evidence',
      'par25-dashboard-favorites-s77',
      'par25-dashboard-favorites',
    );

function shot(page: Page, name: string): void {
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  void page.screenshot({ path: path.join(EVIDENCE_ROOT, `${name}.png`), fullPage: false });
}

interface CreatedDashboard {
  pid: string;
  title: string;
}

async function getToken(page: Page): Promise<string> {
  const cookies = await page.context().cookies();
  const session = cookies.find((cookie) => cookie.name === '__session');
  expect(session, 'admin session cookie').toBeTruthy();
  const decoded = JSON.parse(
    Buffer.from(decodeURIComponent(session!.value).split('.')[0], 'base64').toString(),
  );
  return String(decoded.jwtToken);
}

async function createDashboard(page: Page, title: string): Promise<CreatedDashboard> {
  const token = await getToken(page);
  const response = await page.request.post('/api/dashboards', {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: {
      title,
      scope: 'global',
      status: 'draft',
      widgets: [
        {
          id: `${title}-widget`,
          type: 'stat-card',
          title: 'PAR-25 favorites',
          x: 0,
          y: 0,
          w: 4,
          h: 2,
          config: { label: 'Count', value: '0', color: 'blue' },
        },
      ],
    },
  });
  expect(response.ok(), `create dashboard: ${response.status()} ${await response.text()}`).toBe(
    true,
  );
  const body = await response.json();
  return { pid: String(body.data.pid), title };
}

async function publishDashboard(page: Page, pid: string): Promise<void> {
  const token = await getToken(page);
  const response = await page.request.post(`/api/dashboards/${pid}/publish`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(response.ok(), `publish dashboard: ${response.status()}`).toBe(true);
}

async function deleteDashboard(page: Page, pid: string): Promise<void> {
  const token = await getToken(page);
  await page.request
    .delete(`/api/dashboards/${pid}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    .catch(() => undefined);
}

async function openDashboardViewer(page: Page): Promise<void> {
  await page.goto(DASHBOARDS_PATH, { waitUntil: 'domcontentloaded' });
  await page.getByRole('navigation', { name: 'Dashboard tabs' }).locator('button').first().waitFor({
    state: 'visible',
    timeout: 15_000,
  });
}

test.describe.serial('PAR-25 dashboard favorites', () => {
  const created: CreatedDashboard[] = [];
  const favoriteTitle = `${RUN_ID} favorite A`;
  const otherTitle = `${RUN_ID} control B`;

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    const favorite = await createDashboard(page, favoriteTitle);
    await publishDashboard(page, favorite.pid);
    created.push(favorite);

    const other = await createDashboard(page, otherTitle);
    await publishDashboard(page, other.pid);
    created.push(other);

    await page.close();
    await context.close();
  });

  test.afterAll(async ({ browser }) => {
    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    for (const dashboard of created) {
      await deleteDashboard(page, dashboard.pid);
    }
    await page.close();
    await context.close();
  });

  test('collect, filter, and un-collect persist across real reloads', async ({ page }) => {
    test.setTimeout(120_000);

    await openDashboardViewer(page);
    await page.getByRole('button', { name: favoriteTitle, exact: true }).click();
    await expect(page.getByTestId('dashboard-favorite-toggle')).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    await shot(page, '01-before-collect');

    const favoriteWrite = page.waitForResponse(
      (response) =>
        response.url().includes('/api/user-preferences/dashboard_favorites') &&
        response.request().method().toLowerCase() === 'put' &&
        response.status() === 200,
      { timeout: 10_000 },
    );
    await page.getByTestId('dashboard-favorite-toggle').click();
    await favoriteWrite;
    await expect(page.getByTestId('dashboard-favorite-toggle')).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await shot(page, '02-collected');

    await page.reload({ waitUntil: 'domcontentloaded' });
    await page
      .getByRole('navigation', { name: 'Dashboard tabs' })
      .locator('button')
      .first()
      .waitFor({ state: 'visible', timeout: 15_000 });
    await expect(page.getByTestId('dashboard-favorite-toggle')).toHaveAttribute(
      'aria-pressed',
      'true',
      { timeout: 15_000 },
    );
    await shot(page, '03-favorite-persisted-after-reload');

    await page.getByTestId('dashboard-favorites-filter').click();
    await expect(page.getByRole('button', { name: favoriteTitle, exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: otherTitle, exact: true })).toHaveCount(0);
    await shot(page, '04-favorites-only-filter');

    await page.getByTestId('dashboard-favorites-filter').click();
    await expect(page.getByRole('button', { name: otherTitle, exact: true })).toBeVisible();

    const unfavoriteWrite = page.waitForResponse(
      (response) =>
        response.url().includes('/api/user-preferences/dashboard_favorites') &&
        response.request().method().toLowerCase() === 'put' &&
        response.status() === 200,
      { timeout: 10_000 },
    );
    await page.getByTestId('dashboard-favorite-toggle').click();
    await unfavoriteWrite;
    await expect(page.getByTestId('dashboard-favorite-toggle')).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    await shot(page, '05-un-collected');

    await page.reload({ waitUntil: 'domcontentloaded' });
    await page
      .getByRole('navigation', { name: 'Dashboard tabs' })
      .locator('button')
      .first()
      .waitFor({ state: 'visible', timeout: 15_000 });
    await expect(page.getByTestId('dashboard-favorite-toggle')).toHaveAttribute(
      'aria-pressed',
      'false',
      { timeout: 15_000 },
    );
    await shot(page, '06-unfavorite-persisted-after-reload');
  });
});
