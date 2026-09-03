import fs from 'node:fs';
import path from 'node:path';
import { expect, test, type Page } from '../../fixtures';

/**
 * PAR-25 dashboard routes + edit-pos slice. Closes the Cordys
 * dashboard edit-pos (drag ordering), DASHBOARD_LINK (deep link),
 * DASHBOARD_MODULE (module management view) and FULL_PAGE_DASHBOARD
 * (big-screen presentation) surfaces on a fresh real stack.
 */

const RUN_ID = `par25rt-${Date.now()}`;
const VIEWER_PATH = '/dashboards';
const MGMT_PATH = '/p/dashboard_management';
const EVIDENCE_ROOT = process.env.AURA_EVIDENCE_ROOT
  ? path.join(process.env.AURA_EVIDENCE_ROOT, 'par25-dashboard-routes-pos')
  : path.resolve(
      process.cwd(),
      '..',
      '.workspace',
      'evidence',
      'par25-dashboard-routes-pos-s79',
      'par25-dashboard-routes-pos',
    );

async function shot(page: Page, name: string): Promise<void> {
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  await page.screenshot({ path: path.join(EVIDENCE_ROOT, `${name}.png`), fullPage: false });
}

interface CreatedDashboard {
  pid: string;
  code: string;
  title: string;
}

async function getToken(page: Page): Promise<string> {
  const cookies = await page.context().cookies();
  const session = cookies.find((cookie) => cookie.name === '__session');
  expect(session, 'session cookie with embedded JWT').toBeTruthy();
  const decoded = JSON.parse(
    Buffer.from(decodeURIComponent(session!.value).split('.')[0], 'base64').toString(),
  );
  return String(decoded.jwtToken);
}

async function createPublishedDashboard(page: Page, title: string): Promise<CreatedDashboard> {
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
          type: 'smart-rich-text',
          title: 'PAR-25 routes',
          x: 0,
          y: 0,
          w: 4,
          h: 2,
          config: { content: 'PAR-25 大屏演示' },
        },
      ],
    },
  });
  expect(response.ok(), `create dashboard: ${response.status()}`).toBe(true);
  const body = await response.json();
  const pid = String(body.data.pid);
  const code = String(body.data.code);
  const publish = await page.request.post(`/api/dashboards/${pid}/publish`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(publish.ok(), 'publish dashboard').toBe(true);
  return { pid, code, title };
}

async function openViewer(page: Page): Promise<void> {
  await page.goto(VIEWER_PATH, { waitUntil: 'domcontentloaded' });
  await page
    .getByRole('navigation', { name: 'Dashboard tabs' })
    .locator('button')
    .first()
    .waitFor({ state: 'visible', timeout: 20_000 });
}

function runTabs(page: Page) {
  const tabs = page.getByRole('navigation', { name: 'Dashboard tabs' });
  return tabs.locator('button', { hasText: RUN_ID });
}

async function tabOrder(page: Page): Promise<string[]> {
  const tabs = page.getByRole('navigation', { name: 'Dashboard tabs' });
  const titles = await tabs.locator('button').allTextContents();
  const a = titles.findIndex((title) => title.includes('排序A'));
  const b = titles.findIndex((title) => title.includes('排序B'));
  expect(a, 'tab A present').toBeGreaterThanOrEqual(0);
  expect(b, 'tab B present').toBeGreaterThanOrEqual(0);
  return a < b ? ['A', 'B'] : ['B', 'A'];
}

async function dragTab(page: Page, fromTitle: string, toTitle: string): Promise<void> {
  const fromLocator = page.getByRole('button', { name: fromTitle, exact: true });
  await fromLocator.scrollIntoViewIfNeeded();
  const from = await fromLocator.boundingBox();
  const to = await page.getByRole('button', { name: toTitle, exact: true }).boundingBox();
  expect(from && to, 'both tabs on screen').toBeTruthy();
  // Long titles push the tab under the toolbar actions at the nav's right
  // edge; start the drag from the tab's visible left portion.
  const startX = from!.x + Math.min(24, from!.width / 2);
  await page.mouse.move(startX, from!.y + from!.height / 2);
  await page.mouse.down();
  await page.mouse.move(to!.x + to!.width / 2, to!.y + to!.height / 2, { steps: 12 });
  await page.mouse.up();
}

test.describe.serial('PAR-25 dashboard routes + edit-pos parity', () => {
  const titleA = `${RUN_ID} 排序A`;
  const titleB = `${RUN_ID} 排序B`;
  let dashA: CreatedDashboard | null = null;
  let dashB: CreatedDashboard | null = null;

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    dashA = await createPublishedDashboard(page, titleA);
    dashB = await createPublishedDashboard(page, titleB);
    await page.close();
    await context.close();
  });

  test.afterAll(async ({ browser }) => {
    if (!dashA && !dashB) return;
    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    const token = await getToken(page);
    for (const dashboard of [dashA, dashB].filter(Boolean) as CreatedDashboard[]) {
      await page.request
        .delete(`/api/dashboards/${dashboard.pid}`, {
          headers: { Authorization: `Bearer ${token}` },
        })
        .catch(() => undefined);
    }
    await page.close();
    await context.close();
  });

  test('edit-pos persists drag ordering with revert on failure', async ({ page }) => {
    test.setTimeout(300_000);

    await openViewer(page);
    await runTabs(page).first().waitFor({ state: 'visible', timeout: 15_000 });
    const initial = await tabOrder(page);
    await shot(page, '01-viewer-tabs-initial');

    // Allow: drag the first RUN_ID tab onto the second; the preference PUT
    // must succeed and the order must survive a reload.
    const [firstTitle, secondTitle] = initial[0] === 'A' ? [titleA, titleB] : [titleB, titleA];
    const orderSave = page.waitForResponse(
      (r) =>
        r.url().includes('/api/user-preferences/dashboard_tab_order') &&
        r.request().method() === 'PUT' &&
        r.status() === 200,
      { timeout: 10_000 },
    );
    await dragTab(page, firstTitle, secondTitle);
    await orderSave;
    await expect(page.getByText(/排序已保存|Order saved/)).toBeVisible({ timeout: 10_000 });
    await shot(page, '02-drag-saved');

    await page.reload({ waitUntil: 'domcontentloaded' });
    await openViewerReloading(page);
    const persisted = await tabOrder(page);
    expect(persisted[0]).toBe(initial[1]);
    expect(persisted[1]).toBe(initial[0]);
    await shot(page, '03-order-persisted');

    // Failure: abort the preference write; the move must revert and surface
    // a visible failure instead of silently dropping the change.
    await page.route('**/api/user-preferences/dashboard_tab_order', (route) => route.abort());
    const failedSave = page.waitForEvent('requestfailed', {
      predicate: (r) =>
        r.url().includes('/api/user-preferences/dashboard_tab_order') && r.method() === 'PUT',
      timeout: 10_000,
    });
    await dragTab(page, secondTitle, firstTitle);
    await failedSave;
    await expect(page.getByText(/排序保存失败|Failed to save order/)).toBeVisible({
      timeout: 10_000,
    });
    const reverted = await tabOrder(page);
    expect(reverted).toEqual(persisted);
    await page.unroute('**/api/user-preferences/dashboard_tab_order');
    await shot(page, '04-drag-failed-reverted');
  });

  test('deep link opens the linked dashboard tab', async ({ page }) => {
    test.setTimeout(150_000);
    test.expect(dashB).toBeTruthy();

    // DASHBOARD_LINK: the viewer deep link opens the linked dashboard.
    await page.goto(`${VIEWER_PATH}?code=${dashB!.code}`, {
      waitUntil: 'domcontentloaded',
    });
    const tabs = page.getByRole('navigation', { name: 'Dashboard tabs' });
    await tabs.locator('button').first().waitFor({ state: 'visible', timeout: 20_000 });
    await expect(page.getByRole('button', { name: titleB, exact: true })).toHaveClass(
      /border-blue-500/,
      { timeout: 15_000 },
    );
    await shot(page, '05-deep-link-active-tab');
  });

  test('big-screen presentation renders and exits fullscreen', async ({ page }) => {
    test.setTimeout(150_000);
    test.expect(dashB).toBeTruthy();

    await page.goto(`/dashboard-designer/${dashB!.pid}`, {
      waitUntil: 'domcontentloaded',
    });
    await page.getByTestId('toolbar-btn-presentation').waitFor({
      state: 'visible',
      timeout: 20_000,
    });
    await page.getByTestId('toolbar-btn-presentation').click();
    const bigScreen = page.getByTestId('big-screen-mode');
    await bigScreen.waitFor({ state: 'visible', timeout: 15_000 });
    await shot(page, '06-big-screen-fullpage');

    await page.getByTestId('big-screen-exit').click();
    await expect(bigScreen).toBeHidden({ timeout: 10_000 });
    await shot(page, '07-big-screen-exited');
  });

  test('module management view is reachable from the management page', async ({ page }) => {
    test.setTimeout(150_000);

    // DASHBOARD_MODULE: the management page carries the folder-tree module
    // view (management surface for dashboard modules).
    await page.goto(MGMT_PATH, { waitUntil: 'domcontentloaded' });
    await page.getByTestId('dashboard-module-tree').waitFor({
      state: 'visible',
      timeout: 20_000,
    });
    await expect(page.getByTestId('module-create-save')).toBeVisible();
    await shot(page, '08-module-management-view');
  });

  test('unauthenticated preference write is denied', async ({ browser }) => {
    test.setTimeout(120_000);

    const anonymous = await browser.newContext();
    const page = await anonymous.newPage();
    const denied = await page.request.put(
      'http://127.0.0.1:6479/api/user-preferences/dashboard_tab_order',
      {
        headers: { 'Content-Type': 'application/json' },
        data: { value: [titleA, titleB] },
      },
    );
    expect([401, 403]).toContain(denied.status());
    await page.close();
    await anonymous.close();
  });
});

/** openViewer for an already-loading document (reload tolerant). */
async function openViewerReloading(page: Page): Promise<void> {
  await page
    .getByRole('navigation', { name: 'Dashboard tabs' })
    .locator('button')
    .first()
    .waitFor({ state: 'visible', timeout: 20_000 });
}
