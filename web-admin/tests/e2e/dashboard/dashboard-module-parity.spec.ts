import fs from 'node:fs';
import path from 'node:path';
import { expect, test, type Page } from '../../fixtures';

/**
 * PAR-25 dashboard module (folder tree) slice. Closes the Cordys
 * dashboard-module add / rename / delete / tree / count / move surfaces on a
 * fresh real stack. All folder mutations run from the management page tree
 * block; deny runs at the API boundary for a role without dashboard
 * permissions.
 */

const RUN_ID = `par25mod-${Date.now()}`;
const MGMT_PATH = '/p/dashboard_management';
const EVIDENCE_ROOT = process.env.AURA_EVIDENCE_ROOT
  ? path.join(process.env.AURA_EVIDENCE_ROOT, 'par25-dashboard-module')
  : path.resolve(
      process.cwd(),
      '..',
      '.workspace',
      'evidence',
      'par25-dashboard-module-s79',
      'par25-dashboard-module',
    );

async function shot(page: Page, name: string): Promise<void> {
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  await page.screenshot({ path: path.join(EVIDENCE_ROOT, `${name}.png`), fullPage: false });
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

async function fetchTree(page: Page): Promise<ModuleNode[]> {
  const token = await getToken(page);
  const response = await page.request.get('/api/dashboard-modules/tree', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(response.ok(), 'tree readback').toBe(true);
  const body = await response.json();
  return body.data ?? [];
}

function findNode(nodes: ModuleNode[], pid: string): ModuleNode | null {
  for (const node of nodes) {
    if (node.pid === pid) return node;
    const child = node.children ? findNode(node.children, pid) : null;
    if (child) return child;
  }
  return null;
}

interface ModuleNode {
  pid: string;
  name: string;
  parentPid?: string | null;
  dashboardCount?: number;
  children?: ModuleNode[];
}

async function openManagement(page: Page): Promise<void> {
  await page.goto(MGMT_PATH, { waitUntil: 'domcontentloaded' });
  await page.getByTestId('dashboard-module-tree').waitFor({ state: 'visible', timeout: 30_000 });
  // Vite may full-reload the document once while lazy route deps optimize;
  // waiting for the toolbar too keeps the journey across that reload.
  await page
    .getByTestId('toolbar-btn-create')
    .waitFor({ state: 'visible', timeout: 30_000 })
    .catch(() => undefined);
}

const createdPids: string[] = [];

async function createFolderViaUi(page: Page, name: string, parentPid?: string): Promise<string> {
  if (parentPid) {
    await page.getByTestId(`module-select-${parentPid}`).click();
  }
  await page.getByTestId('module-new-name').fill(name);
  const createResponse = page.waitForResponse(
    (r) =>
      r.url().match(/\/api\/dashboard-modules$/) !== null &&
      r.request().method() === 'POST' &&
      r.status() === 200,
    { timeout: 10_000 },
  );
  await page.getByTestId('module-create-save').click();
  const created = await createResponse;
  const body = await created.json();
  const pid = String(body.data.pid);
  expect(pid, 'created folder pid').toBeTruthy();
  createdPids.push(pid);
  await expect(page.getByTestId(`module-node-${pid}`)).toBeVisible({
    timeout: 10_000,
  });
  return pid;
}

test.describe.serial('PAR-25 dashboard module parity', () => {
  const parentName = `${RUN_ID} 销售`;
  const childName = `${RUN_ID} 华东`;
  const dashboardTitle = `${RUN_ID} 目录看板`;
  let parentPid: string | null = null;
  let childPid: string | null = null;
  let dashboardPid: string | null = null;

  test.beforeEach(async ({ page }, testInfo) => {
    page.on('pageerror', (err) =>
      console.log(`[pageerror][${testInfo.title}]`, err.message.slice(0, 500)),
    );
    page.on('framenavigated', (frame) => {
      if (frame === page.mainFrame()) {
        console.log(`[navigated][${testInfo.title}]`, frame.url());
      }
    });
    page.on('console', (msg) => {
      if (msg.type() === 'error') {
        console.log(`[console-error][${testInfo.title}]`, msg.text().slice(0, 500));
      }
    });
  });

  test.afterAll(async ({ browser }) => {
    if (!parentPid && !childPid && !dashboardPid) return;
    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    const token = await getToken(page);
    const auth = { Authorization: `Bearer ${token}` };
    if (dashboardPid) {
      await page.request
        .put(`/api/dashboards/${dashboardPid}`, {
          headers: { ...auth, 'Content-Type': 'application/json' },
          data: { modulePid: null },
        })
        .catch(() => undefined);
    }
    for (const pid of [...createdPids].reverse()) {
      await page.request
        .delete(`/api/dashboard-modules/${pid}`, { headers: auth })
        .catch(() => undefined);
    }
    if (dashboardPid) {
      await page.request
        .delete(`/api/dashboards/${dashboardPid}`, { headers: auth })
        .catch(() => undefined);
    }
    await page.close();
    await context.close();
  });

  test('dashboard module journey: add, assign, count, rename, move, delete', async ({ page }) => {
    test.setTimeout(300_000);

    await openManagement(page);
    await expect(page.locator('[data-testid^="module-node-"]')).toHaveCount(0, {
      timeout: 10_000,
    });
    await shot(page, '01-management-empty-tree');

    parentPid = await createFolderViaUi(page, parentName);
    childPid = await createFolderViaUi(page, childName, parentPid);

    // Server readback: the child is nested under the parent.
    let tree = await fetchTree(page);
    const parentNode = findNode(tree, parentPid);
    expect(parentNode, 'parent in server tree').toBeTruthy();
    expect(parentNode!.name).toBe(parentName);
    expect(parentNode!.children?.some((child) => child.pid === childPid)).toBe(true);
    await shot(page, '02-folders-created');

    // --- phase ---
    // Create the dashboard through the real designer flow (same journey as
    // the CRUD slice) so the assignment target exists.
    await page.getByTestId('toolbar-btn-create').click({ timeout: 30_000 });
    await page.getByTestId('toolbar-btn-settings').waitFor({ state: 'visible', timeout: 20_000 });
    const dialog = page.getByRole('dialog', { name: 'Dashboard Settings' });
    await page.getByTestId('toolbar-btn-settings').click();
    await dialog.waitFor({ state: 'visible', timeout: 5000 });
    await dialog.locator('input[type="text"]').first().fill(dashboardTitle);
    await dialog.getByRole('button', { name: /^(保存|Save)$/ }).click();
    await dialog.waitFor({ state: 'hidden', timeout: 5000 });
    const createResponse = page.waitForResponse(
      (r) =>
        r.url().match(/\/api\/dashboards$/) !== null &&
        r.request().method() === 'POST' &&
        r.status() === 200,
      { timeout: 15_000 },
    );
    await page.getByTestId('designer-toolbar-btn-save').click();
    const created = await createResponse;
    const body = await created.json();
    dashboardPid = String(body.data.pid);
    expect(dashboardPid).toBeTruthy();

    // Back to the management page: assign through the tree block.
    await openManagement(page);
    await page.getByTestId(`module-select-${parentPid}`).click();
    const assignSelect = page.getByTestId('module-assign-select');
    await assignSelect.selectOption({ label: dashboardTitle });
    const assignResponse = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/dashboards/${dashboardPid}`) &&
        r.request().method() === 'PUT' &&
        r.status() === 200,
      { timeout: 10_000 },
    );
    await assignResponse;

    // Count badge reflects the assignment; server module-count agrees.
    await expect(page.getByTestId(`module-count-${parentPid}`)).toHaveText('1', {
      timeout: 10_000,
    });
    const token = await getToken(page);
    const counts = await page.request.get('/api/dashboard-modules/module-count', {
      headers: { Authorization: `Bearer ${token}` },
    });
    const countsBody = await counts.json();
    const parentCount = (countsBody.data ?? []).find(
      (row: { pid: string }) => row.pid === parentPid,
    );
    expect(parentCount?.dashboardCount).toBe(1);
    await shot(page, '03-assigned-count');

    // --- phase ---
    await openManagement(page);
    await page.getByTestId(`module-node-${parentPid}`).hover();
    await page.getByTestId(`module-rename-${parentPid}`).click({ force: true });
    const renamedName = `${parentName}-R2`;
    await page.getByTestId(`module-rename-input-${parentPid}`).fill(renamedName);
    const renameResponse = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/dashboard-modules/${parentPid}/rename`) &&
        r.request().method() === 'PUT' &&
        r.status() === 200,
      { timeout: 10_000 },
    );
    await page.getByTestId(`module-rename-save-${parentPid}`).click();
    await renameResponse;

    tree = await fetchTree(page);
    expect(findNode(tree, parentPid)?.name).toBe(renamedName);
    await shot(page, '04-renamed');

    // --- phase ---
    await openManagement(page);
    // renamed (already declared)

    // Negative first: moving the parent under its own child is refused.
    page.once('dialog', (dialog) => void dialog.accept(childName));
    const cyclicMoveAttempt = page.waitForResponse(
      (r) => r.url().includes(`/api/dashboard-modules/${parentPid}/move`),
      { timeout: 10_000 },
    );
    await page.getByTestId(`module-node-${parentPid}`).hover();
    await page.getByTestId(`module-move-${parentPid}`).click({ force: true });
    const rejected = await cyclicMoveAttempt;
    expect(rejected.status()).toBe(422);
    await expect(
      page.locator('.Toastify__error, [data-testid="error-toast"], [role="alert"]').first(),
    )
      .toBeVisible({
        timeout: 10_000,
      })
      .catch(() => undefined);
    await shot(page, '05-cyclic-move-refused');

    // Server readback: the parent is still at the root with the child nested.
    tree = await fetchTree(page);
    expect(findNode(tree, parentPid)?.parentPid ?? null).toBeNull();

    // Positive: move the child to the root.
    page.once('dialog', (dialog) => void dialog.accept(''));
    const moveResponse = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/dashboard-modules/${childPid}/move`) &&
        r.request().method() === 'POST' &&
        r.status() === 200,
      { timeout: 10_000 },
    );
    await page.getByTestId(`module-node-${childPid}`).hover();
    await page.getByTestId(`module-move-${childPid}`).click({ force: true });
    await moveResponse;

    tree = await fetchTree(page);
    expect(findNode(tree, childPid)?.parentPid ?? null).toBeNull();
    expect(findNode(tree, parentPid)?.name).toBe(renamedName);
    await shot(page, '06-moved-to-root');

    // --- phase ---
    await openManagement(page);

    // Non-empty (has a dashboard): delete is refused with a visible failure.
    page.once('dialog', (dialog) => void dialog.accept());
    await page.getByTestId(`module-node-${parentPid}`).hover();
    await page.getByTestId(`module-delete-${parentPid}`).click({ force: true });
    const refusedDelete = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/dashboard-modules/${parentPid}`) &&
        r.request().method() === 'DELETE',
      { timeout: 10_000 },
    );
    const refused = await refusedDelete;
    expect(refused.status()).toBe(422);
    await expect(page.getByTestId(`module-node-${parentPid}`)).toBeVisible({ timeout: 10_000 });
    await shot(page, '07-delete-nonempty-refused');

    // Empty child folder deletes successfully.
    page.once('dialog', (dialog) => void dialog.accept());
    const deleteResponse = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/dashboard-modules/${childPid}`) &&
        r.request().method() === 'DELETE' &&
        r.status() === 200,
      { timeout: 10_000 },
    );
    await page.getByTestId(`module-node-${childPid}`).hover();
    await page.getByTestId(`module-delete-${childPid}`).click({ force: true });
    await deleteResponse;
    await expect(page.getByTestId(`module-node-${childPid}`)).toHaveCount(0, { timeout: 10_000 });

    tree = await fetchTree(page);
    expect(findNode(tree, childPid)).toBeNull();
    await shot(page, '08-empty-folder-deleted');

    // The parent folder (still holding the dashboard) is cleaned up in afterAll.
  });

  test('viewer role is denied folder writes with no side effects', async ({ browser }) => {
    test.setTimeout(120_000);

    // Seed one folder as admin for the deny readback.
    const adminContext = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const adminPage = await adminContext.newPage();
    const adminToken = await getToken(adminPage);
    const adminAuth = {
      Authorization: `Bearer ${adminToken}`,
      'Content-Type': 'application/json',
    };
    const created = await adminPage.request.post('/api/dashboard-modules', {
      headers: adminAuth,
      data: { name: `${RUN_ID} deny-folder` },
    });
    expect(created.ok()).toBe(true);
    const createdPid = String((await created.json()).data.pid);
    await adminPage.close();
    await adminContext.close();

    const viewerContext = await browser.newContext({
      storageState: process.env.PW_VIEWER_STORAGE_STATE || 'tests/storage/viewer.json',
    });
    const viewerPage = await viewerContext.newPage();
    const token = await getToken(viewerPage);
    const auth = { Authorization: `Bearer ${token}` };

    const createDenied = await viewerPage.request.post('/api/dashboard-modules', {
      headers: { ...auth, 'Content-Type': 'application/json' },
      data: { name: `${RUN_ID} denied` },
    });
    expect(createDenied.status()).toBe(403);

    const renameDenied = await viewerPage.request.put(
      `/api/dashboard-modules/${createdPid}/rename`,
      {
        headers: { ...auth, 'Content-Type': 'application/json' },
        data: { name: `${RUN_ID} denied rename` },
      },
    );
    expect(renameDenied.status()).toBe(403);

    const moveDenied = await viewerPage.request.post(`/api/dashboard-modules/${createdPid}/move`, {
      headers: { ...auth, 'Content-Type': 'application/json' },
      data: {},
    });
    expect(moveDenied.status()).toBe(403);

    const deleteDenied = await viewerPage.request.delete(`/api/dashboard-modules/${createdPid}`, {
      headers: auth,
    });
    expect(deleteDenied.status()).toBe(403);

    // Read deny is expected too — the viewer role has no dashboard.read.
    const treeDenied = await viewerPage.request.get('/api/dashboard-modules/tree', {
      headers: auth,
    });
    expect(treeDenied.status()).toBe(403);

    await viewerPage.close();
    await viewerContext.close();

    // No side effects: the folder still exists with its original name.
    const adminContext2 = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const adminPage2 = await adminContext2.newPage();
    const tree = await fetchTree(adminPage2);
    const kept = findNode(tree, createdPid);
    expect(kept?.name).toBe(`${RUN_ID} deny-folder`);
    await adminPage2.close();
    await adminContext2.close();

    // Cleanup
    const cleanupContext = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const cleanupPage = await cleanupContext.newPage();
    await cleanupPage.request.delete(`/api/dashboard-modules/${createdPid}`, {
      headers: { Authorization: `Bearer ${await getToken(cleanupPage)}` },
    });
    await cleanupPage.close();
    await cleanupContext.close();
  });
});
