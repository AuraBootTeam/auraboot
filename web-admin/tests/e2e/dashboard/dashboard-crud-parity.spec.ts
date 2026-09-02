import fs from 'node:fs';
import path from 'node:path';
import { expect, test, type Page } from '../../fixtures';
import { clickRowActionByLocator, ensureFilterFormOpen } from '../helpers';

/**
 * PAR-25 dashboard CRUD slice. Closes the Cordys dashboard add / detail /
 * update / rename / delete / list surfaces plus the DASHBOARD_INDEX viewer
 * route on a fresh real stack. Allow journeys run from the real management
 * page, designer, and viewer; deny journeys assert 403 with no side effects
 * for a role without dashboard permissions.
 */

const RUN_ID = `par25crud-${Date.now()}`;
const MGMT_PATH = '/p/dashboard_management';
const VIEWER_PATH = '/dashboards';
const EVIDENCE_ROOT = process.env.AURA_EVIDENCE_ROOT
  ? path.join(process.env.AURA_EVIDENCE_ROOT, 'par25-dashboard-crud')
  : path.resolve(
      process.cwd(),
      '..',
      '.workspace',
      'evidence',
      'par25-dashboard-crud-s78',
      'par25-dashboard-crud',
    );

function shot(page: Page, name: string): void {
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  void page.screenshot({ path: path.join(EVIDENCE_ROOT, `${name}.png`), fullPage: false });
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

async function fetchDashboard(
  page: Page,
  pid: string,
): Promise<{ code: string; data: { title?: string; description?: string } | null }> {
  const token = await getToken(page);
  const response = await page.request.get(`/api/dashboards/${pid}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const body = await response.json();
  return { code: String(body.code ?? ''), data: body.data ?? null };
}

async function deleteDashboardViaApi(page: Page, pid: string): Promise<void> {
  const token = await getToken(page);
  await page.request
    .delete(`/api/dashboards/${pid}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    .catch(() => undefined);
}

async function gotoManagement(page: Page): Promise<void> {
  const listResponse = page
    .waitForResponse((r) => r.url().includes('/api/dashboards') && r.status() === 200, {
      timeout: 15_000,
    })
    .catch(() => null);
  await page.goto(MGMT_PATH, { waitUntil: 'domcontentloaded' });
  await page.locator('main, table, [data-testid="dynamic-list"]').first().waitFor({
    state: 'visible',
    timeout: 15_000,
  });
  await listResponse;
}

/** Navigate to the management page and filter by an exact title substring. */
async function gotoManagementFiltered(page: Page, titleFilter: string): Promise<void> {
  await gotoManagement(page);

  const allTab = page.locator('button, [role="tab"]', { hasText: /全部|All/ }).first();
  if (await allTab.isVisible({ timeout: 2000 }).catch(() => false)) {
    const tabResponse = page
      .waitForResponse((r) => r.url().includes('/api/dashboards') && r.status() === 200, {
        timeout: 5000,
      })
      .catch(() => null);
    await allTab.click().catch(() => null);
    await tabResponse;
  }

  await ensureFilterFormOpen(page);
  const searchInput = page
    .locator(
      '[data-testid="list-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]',
    )
    .first();
  await searchInput.fill(titleFilter);
  const filterResponse = page
    .waitForResponse((r) => r.url().includes('/api/dashboards') && r.status() === 200, {
      timeout: 10_000,
    })
    .catch(() => null);
  const searchButton = page.getByTestId('filter-search');
  if (await searchButton.isVisible({ timeout: 3000 }).catch(() => false)) {
    await searchButton.click();
  } else {
    await searchInput.press('Enter');
  }
  await filterResponse;
  await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 10_000 });
}

/**
 * Open the designer settings dialog, replace title/description, and confirm.
 * Returns once the store is dirty (settings dialog closed).
 */
async function editDesignerMeta(
  page: Page,
  meta: { title: string; description?: string },
): Promise<void> {
  await page.getByTestId('toolbar-btn-settings').click();
  const dialog = page.getByRole('dialog', { name: 'Dashboard Settings' });
  await dialog.waitFor({ state: 'visible', timeout: 5000 });

  const titleInput = dialog.locator('input[type="text"]').first();
  await titleInput.fill(meta.title);
  if (meta.description !== undefined) {
    await dialog.locator('textarea').first().fill(meta.description);
  }
  await dialog.getByRole('button', { name: /^(保存|Save)$/ }).click();
  await dialog.waitFor({ state: 'hidden', timeout: 5000 });
}

async function openDesignerDetail(page: Page, pid: string): Promise<void> {
  await page.goto(`/dashboard-designer/${pid}`, { waitUntil: 'domcontentloaded' });
  await page.getByTestId('toolbar-btn-settings').waitFor({ state: 'visible', timeout: 20_000 });
}

async function readSettingsTitle(page: Page): Promise<string> {
  await page.getByTestId('toolbar-btn-settings').click();
  const dialog = page.getByRole('dialog', { name: 'Dashboard Settings' });
  await dialog.waitFor({ state: 'visible', timeout: 5000 });
  const title = await dialog.locator('input[type="text"]').first().inputValue();
  await dialog.getByRole('button', { name: /^(取消|Cancel)$/ }).click();
  await dialog.waitFor({ state: 'hidden', timeout: 5000 });
  return title;
}

test.describe.serial('PAR-25 dashboard CRUD parity', () => {
  const createTitle = `${RUN_ID} 销售看板`;
  const renamedTitle = `${RUN_ID} 销售看板-R2`;
  const updateDescription = `${RUN_ID} parity description`;
  let pid: string | null = null;

  test.afterAll(async ({ browser }) => {
    if (!pid) return;
    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    await deleteDashboardViaApi(page, pid);
    await page.close();
    await context.close();
  });

  test('create from management page and persist detail', async ({ page }) => {
    test.setTimeout(120_000);

    await gotoManagement(page);
    await expect(page.getByTestId('toolbar-btn-create')).toBeVisible();
    await shot(page, '01-management-entry');

    await page.getByTestId('toolbar-btn-create').click();
    await page.getByTestId('toolbar-btn-settings').waitFor({ state: 'visible', timeout: 20_000 });
    await shot(page, '02-designer-new');

    const createResponse = page.waitForResponse(
      (r) =>
        r.url().match(/\/api\/dashboards$/) !== null &&
        r.request().method() === 'POST' &&
        r.status() === 200,
      { timeout: 15_000 },
    );
    await editDesignerMeta(page, { title: createTitle });
    await page.getByTestId('designer-toolbar-btn-save').click();
    const created = await createResponse;
    const body = await created.json();
    pid = String(body.data.pid);
    expect(pid).toBeTruthy();
    await expect(page.getByText(/保存成功|Saved/)).toBeVisible({ timeout: 10_000 });
    await shot(page, '03-created-saved');

    // Persistence readback through the API before the UI detail journey.
    const createdDetail = await fetchDashboard(page, pid);
    expect(createdDetail.data?.title).toBe(createTitle);

    // Detail: the designer reloads the persisted dashboard from GET by pid.
    await openDesignerDetail(page, pid);
    expect(await readSettingsTitle(page)).toBe(createTitle);
    await shot(page, '04-detail-persisted');
  });

  test('rename persists through detail API and management list', async ({ page }) => {
    test.setTimeout(120_000);
    test.expect(pid).toBeTruthy();

    await openDesignerDetail(page, pid!);
    const renameResponse = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/dashboards/${pid}`) &&
        r.request().method() === 'PUT' &&
        r.status() === 200,
      { timeout: 15_000 },
    );
    await editDesignerMeta(page, { title: renamedTitle });
    await page.getByTestId('designer-toolbar-btn-save').click();
    await renameResponse;
    await shot(page, '05-renamed');

    // Readback 1: detail API returns the new title.
    const detail = await fetchDashboard(page, pid!);
    expect(detail.data?.title).toBe(renamedTitle);

    // Readback 2: the management list shows the renamed title.
    await gotoManagementFiltered(page, RUN_ID);
    const row = page.locator('tbody tr', { hasText: renamedTitle });
    await expect(row).toHaveCount(1);
    await shot(page, '06-renamed-list-readback');
  });

  test('update description through designer and read back', async ({ page }) => {
    test.setTimeout(120_000);
    test.expect(pid).toBeTruthy();

    await openDesignerDetail(page, pid!);
    const updateResponse = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/dashboards/${pid}`) &&
        r.request().method() === 'PUT' &&
        r.status() === 200,
      { timeout: 15_000 },
    );
    await editDesignerMeta(page, { title: renamedTitle, description: updateDescription });
    await page.getByTestId('designer-toolbar-btn-save').click();
    await updateResponse;
    await shot(page, '07-updated');

    const detail = await fetchDashboard(page, pid!);
    expect(detail.data?.description).toBe(updateDescription);
    expect(detail.data?.title).toBe(renamedTitle);
  });

  test('list exact set and viewer catalog expose the dashboard', async ({ page }) => {
    test.setTimeout(150_000);
    test.expect(pid).toBeTruthy();

    // Management list: filtering by the run id yields exactly one row.
    await gotoManagementFiltered(page, RUN_ID);
    await expect(page.locator('tbody tr')).toHaveCount(1);
    await expect(page.locator('tbody tr').first()).toContainText(renamedTitle);
    await shot(page, '08-management-list-exact');

    // Publish from the real row action so the viewer catalog picks it up.
    const row = page.locator('tbody tr', { hasText: renamedTitle }).first();
    const publishResponse = page.waitForResponse(
      (r) => r.url().includes('/publish') && r.request().method() === 'POST' && r.status() === 200,
      { timeout: 10_000 },
    );
    await clickRowActionByLocator(page, row, 'publish');
    await publishResponse;

    // DASHBOARD_INDEX surface: the viewer tab bar lists the published dashboard.
    await page.goto(VIEWER_PATH, { waitUntil: 'domcontentloaded' });
    const tabs = page.getByRole('navigation', { name: 'Dashboard tabs' });
    await tabs.locator('button').first().waitFor({ state: 'visible', timeout: 15_000 });
    const runTabs = tabs.locator('button', { hasText: RUN_ID });
    await expect(runTabs).toHaveCount(1);
    await expect(runTabs).toHaveText(renamedTitle);
    await shot(page, '09-viewer-catalog');

    await runTabs.click();
    await expect(page.getByTestId('dashboard-favorite-toggle')).toBeVisible({
      timeout: 10_000,
    });
    await shot(page, '10-viewer-active-tab');
  });

  test('viewer role is denied writes with no side effects', async ({ browser }) => {
    test.setTimeout(120_000);
    test.expect(pid).toBeTruthy();

    const viewerContext = await browser.newContext({
      storageState: process.env.PW_VIEWER_STORAGE_STATE || 'tests/storage/viewer.json',
    });
    const viewerPage = await viewerContext.newPage();

    // UI feedback: the viewer cannot load the dashboard catalog.
    await viewerPage.goto(VIEWER_PATH, { waitUntil: 'domcontentloaded' });
    await viewerPage.waitForLoadState('networkidle').catch(() => undefined);
    await shot(viewerPage, '11-viewer-deny-catalog');

    const token = await getToken(viewerPage);
    const auth = { Authorization: `Bearer ${token}` };

    const createDenied = await viewerPage.request.post('/api/dashboards', {
      headers: { ...auth, 'Content-Type': 'application/json' },
      data: { title: `${RUN_ID} denied create`, scope: 'global' },
    });
    expect(createDenied.status()).toBe(403);

    const renameDenied = await viewerPage.request.put(`/api/dashboards/${pid}`, {
      headers: { ...auth, 'Content-Type': 'application/json' },
      data: { title: `${RUN_ID} denied rename` },
    });
    expect(renameDenied.status()).toBe(403);

    const deleteDenied = await viewerPage.request.delete(`/api/dashboards/${pid}`, {
      headers: auth,
    });
    expect(deleteDenied.status()).toBe(403);

    await viewerPage.close();
    await viewerContext.close();

    // No side effects: an admin readback shows the real title, still existing.
    const adminContext = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const adminPage = await adminContext.newPage();
    const detail = await fetchDashboard(adminPage, pid!);
    expect(detail.data?.title).toBe(renamedTitle);
    await adminPage.close();
    await adminContext.close();
  });

  test('delete from management page is durable', async ({ page }) => {
    test.setTimeout(120_000);
    test.expect(pid).toBeTruthy();

    await gotoManagementFiltered(page, RUN_ID);
    const row = page.locator('tbody tr', { hasText: renamedTitle }).first();
    await expect(row).toBeVisible();

    await clickRowActionByLocator(page, row, 'delete');
    const confirmDialog = page.getByTestId('confirm-dialog');
    await confirmDialog.waitFor({ state: 'visible', timeout: 10_000 });
    await shot(page, '12-delete-confirm');

    const deleteResponse = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/dashboards/${pid}`) &&
        r.request().method() === 'DELETE' &&
        r.status() === 200,
      { timeout: 10_000 },
    );
    await page.getByTestId('confirm-ok').click();
    await deleteResponse;

    await expect(page.locator('tbody tr', { hasText: renamedTitle })).toHaveCount(0, {
      timeout: 10_000,
    });
    await shot(page, '13-after-delete');

    // Readback: the dashboard no longer resolves and the list stays empty.
    const detail = await fetchDashboard(page, pid!);
    expect(detail.data).toBeNull();
    await gotoManagementFiltered(page, RUN_ID);
    await expect(page.locator('tbody tr', { hasText: RUN_ID })).toHaveCount(0);

    pid = null;
  });
});
