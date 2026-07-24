/**
 * SavedView Personal-only management golden path.
 *
 * API calls in this file are limited to setup/readback/cleanup. User-visible
 * create, switch, rename, duplicate, set-default, delete, quota, and capability
 * diagnostics are driven through the actual list-page UI.
 */

import { expect, test, type Page } from '@playwright/test';
import { selectSavedViewByName, uniqueId } from '../helpers';
import { navigateToOrderViaSidebar } from './helpers';

import { acquireSavedViewLock, releaseSavedViewLock } from './_saved-view-lock';

// Serialize e2et_order saved-view specs — they share the model's per-user view
// state (active view / created views) under the shared admin storageState.
test.beforeAll(async () => { await acquireSavedViewLock('saved-view-management'); });
test.afterAll(() => { releaseSavedViewLock('saved-view-management'); });

const MODEL_CODE = 'e2et_order';
const PAGE_KEY = 'e2et_order_list';
const SHOTS = 'test-results/saved-view-personal-golden';
const RUN_PREFIX = `SV个人视图${uniqueId()}`;
const CLEANUP_PREFIXES = [
  'SV个人视图',
  'SV Showcase',
  'TL_',
  'Modified This Week preset_',
  'Default View',
  'E2E Calendar View',
  'E2E Kanban Board',
  'E2E Gantt Timeline',
  'BF_',
  'CF_',
  'FV_',
  'KG_',
  'QF_',
  'RH_',
  'SF_',
  'SV_Tree_',
  'UX_',
  '树视图视图',
  '树视图表格视图',
  'UX_FormView_e2e_',
  RUN_PREFIX,
  'TestView_',
  'DeleteMe_',
  'FilterView1_',
  'FilterView2_',
  'Personal_',
  'Global_',
  'DefaultTest_',
  'Original_',
  'Copy_Original_',
  'Persist_',
  'SortPersist_',
  'GroupPersist_',
  'Density_',
  'PageSize_',
  'Frozen_',
];

interface SavedViewApiRecord {
  pid: string;
  name: string;
  scope?: string;
  viewType?: string;
  isDefault?: boolean;
  isImplicit?: boolean;
  viewConfig?: Record<string, unknown>;
}

async function apiJson<T>(
  page: Page,
  method: 'get' | 'post' | 'put' | 'delete',
  url: string,
  data?: unknown,
): Promise<T> {
  const response = await page.request[method](url, data == null ? undefined : { data });
  if (!response.ok()) {
    const body = await response.text().catch(() => '<body unavailable>');
    throw new Error(`${method.toUpperCase()} ${url} failed: ${response.status()} ${body}`);
  }
  if (method === 'delete') return undefined as T;
  const body = await response.json();
  return (body.data ?? body) as T;
}

async function listViews(page: Page): Promise<SavedViewApiRecord[]> {
  const params = new URLSearchParams({ modelCode: MODEL_CODE, pageKey: PAGE_KEY });
  return apiJson<SavedViewApiRecord[]>(page, 'get', `/api/views/accessible?${params.toString()}`);
}

async function getView(page: Page, pid: string): Promise<SavedViewApiRecord> {
  return apiJson<SavedViewApiRecord>(page, 'get', `/api/views/${pid}`);
}

async function createView(
  page: Page,
  name: string,
  options: Partial<SavedViewApiRecord> = {},
): Promise<string> {
  const body = await apiJson<SavedViewApiRecord>(page, 'post', '/api/views', {
    name,
    modelCode: MODEL_CODE,
    pageKey: PAGE_KEY,
    scope: options.scope ?? 'personal',
    viewType: options.viewType ?? 'table',
    viewConfig: options.viewConfig ?? {},
    isDefault: options.isDefault ?? false,
  });
  expect(body.pid, `created view pid for ${name}`).toBeTruthy();
  return body.pid;
}

async function deleteView(
  page: Page,
  pid: string,
  options: { bestEffort?: boolean } = {},
): Promise<void> {
  const response = await page.request.delete(`/api/views/${pid}`);
  if (
    options.bestEffort &&
    (response.ok() || response.status() === 400 || response.status() === 404)
  ) {
    return;
  }
  if (!response.ok() && response.status() !== 404) {
    const body = await response.text().catch(() => '<body unavailable>');
    throw new Error(`DELETE /api/views/${pid} failed: ${response.status()} ${body}`);
  }
}

async function cleanupRunViews(page: Page): Promise<void> {
  const views = await listViews(page).catch(() => []);
  for (const view of views) {
    if (
      view.pid &&
      (view.isImplicit || CLEANUP_PREFIXES.some((prefix) => view.name?.startsWith(prefix)))
    ) {
      await deleteView(page, view.pid, { bestEffort: true });
    }
  }
}

async function personalManualViewCount(page: Page): Promise<number> {
  const views = await listViews(page);
  return views.filter((view) => view.scope === 'personal' && !(view as any).isImplicit).length;
}

async function fillPersonalQuota(page: Page): Promise<void> {
  const currentCount = await personalManualViewCount(page);
  for (let index = currentCount; index < 10; index += 1) {
    await createView(page, `${RUN_PREFIX}-配额-${index + 1}`);
  }
}

async function openSelector(page: Page): Promise<void> {
  await page.getByTestId('view-selector-trigger').click();
  await expect(page.getByTestId('view-selector-search')).toBeVisible();
}

async function openManagePanel(page: Page): Promise<void> {
  await openSelector(page);
  await page.getByTestId('view-selector-manage').click();
  await expect(page.getByTestId('saved-view-manage-panel')).toBeVisible();
}

async function clickStableTestId(page: Page, testId: string): Promise<void> {
  await expect
    .poll(
      async () => {
        try {
          await page.getByTestId(testId).click({ timeout: 750 });
          return true;
        } catch {
          return false;
        }
      },
      { timeout: 5000 },
    )
    .toBe(true);
}

async function navigateToPersonalTableView(page: Page, nameSuffix: string): Promise<string> {
  const viewName = `${RUN_PREFIX}-${nameSuffix}`;
  const pid = await createView(page, viewName, {
    viewType: 'table',
    viewConfig: { rowHeight: 'medium' },
  });
  await navigateToOrderViaSidebar(page);
  expect(await selectSavedViewByName(page, viewName)).toBe(true);
  await expect(page.getByTestId('quick-filters')).toBeVisible({ timeout: 5000 });
  return pid;
}

async function createTableViewThroughUi(page: Page): Promise<string> {
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/views',
    { timeout: 5000 },
  );
  await page.getByTestId('saved-view-create-personal').click();
  await expect(page.getByTestId('saved-view-quota-status')).toContainText('个人视图：');
  await page.getByTestId('saved-view-type-table').click();
  const response = await responsePromise;
  expect(response.ok()).toBeTruthy();
  const body = await response.json();
  const pid = body.data?.pid ?? body.pid;
  expect(pid).toBeTruthy();
  await expect(page).toHaveURL(new RegExp(`view=${pid}`), { timeout: 5000 });
  return String(pid);
}

test.describe.serial('SavedView Personal-only management', () => {
  test.beforeEach(async ({ page }) => {
    await cleanupRunViews(page);
  });

  test.afterEach(async ({ page }) => {
    await cleanupRunViews(page);
  });

  test('SV-PER-001: selector and management panel expose only personal views @smoke', async ({
    page,
  }) => {
    const personalName = `${RUN_PREFIX}-选择器`;
    const globalName = `${RUN_PREFIX}-全员不应出现`;
    const personalPid = await createView(page, personalName);
    await createView(page, globalName, { scope: 'global' }).catch(() => '');

    await navigateToOrderViaSidebar(page);
    await expect(page.getByTestId('dynamic-list')).toBeVisible();
    await expect(page.getByTestId('quick-filters')).toHaveCount(1);
    await page.screenshot({ path: `${SHOTS}/01-data-view.png`, fullPage: true });

    await openSelector(page);
    const selector = page.getByRole('listbox', { name: /选择视图|Select View/ });
    await expect(selector).toContainText('个人视图');
    await expect(selector).toContainText(personalName);
    await expect(selector).not.toContainText(globalName);
    await expect(selector).not.toContainText(
      /团队共享|全员视图|Team Views|Global Views|New View|Manage Views/,
    );
    await page.getByTestId('view-selector-search').fill('选择器');
    await expect(page.getByTestId(`view-option-${personalPid}`)).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/02-personal-selector.png`, fullPage: true });

    await page.getByTestId('view-selector-manage').click();
    const panel = page.getByTestId('saved-view-manage-panel');
    await expect(panel).toBeVisible();
    await expect(panel).toContainText('管理视图');
    await expect(panel).toContainText('新建个人视图');
    await expect(panel).toContainText(personalName);
    await expect(panel).not.toContainText(globalName);
    await expect(panel).not.toContainText(
      /View Management|New View|Configure|Skip|Done|Team Views|Global Views/,
    );
    await panel.getByTestId('saved-view-manage-search').fill(RUN_PREFIX);
    await expect(panel).toContainText(personalName);
    await expect(panel).not.toContainText('Default View');
    await page.screenshot({ path: `${SHOTS}/03-personal-management.png`, fullPage: true });
  });

  test('SV-PER-002: create, switch, rename, duplicate, set default, and delete through UI', async ({
    page,
  }) => {
    await navigateToOrderViaSidebar(page);
    await openManagePanel(page);

    const createdPid = await createTableViewThroughUi(page);
    const created = await getView(page, createdPid);
    expect(created.scope).toBe('personal');
    expect(created.viewType ?? 'table').toBe('table');

    await openManagePanel(page);
    const renamed = `${RUN_PREFIX}-已重命名`;
    await page.getByTestId(`saved-view-action-edit-${createdPid}`).click();
    await page.getByTestId(`saved-view-edit-name-${createdPid}`).fill(renamed);
    await page.getByTestId(`saved-view-edit-save-${createdPid}`).click();
    await expect(page.getByTestId(`saved-view-row-${createdPid}`)).toContainText(renamed);
    expect((await getView(page, createdPid)).name).toBe(renamed);

    const duplicateName = `${RUN_PREFIX}-副本`;
    const duplicateResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes(`/api/views/${createdPid}/duplicate`),
      { timeout: 5000 },
    );
    await page.getByTestId(`saved-view-action-copy-${createdPid}`).click();
    await page.getByTestId(`saved-view-duplicate-name-${createdPid}`).fill(duplicateName);
    await page.getByTestId(`saved-view-duplicate-submit-${createdPid}`).click();
    const duplicateBody = await (await duplicateResponse).json();
    const duplicatePid = duplicateBody.data?.pid ?? duplicateBody.pid;
    expect(duplicatePid).toBeTruthy();
    await expect(page.getByTestId(`saved-view-row-${duplicatePid}`)).toContainText(duplicateName);

    await page.getByTestId(`saved-view-action-set-default-${duplicatePid}`).click();
    await expect
      .poll(async () => (await getView(page, duplicatePid)).isDefault === true)
      .toBe(true);

    await page.getByTestId(`saved-view-select-${createdPid}`).click();
    await expect(page).toHaveURL(new RegExp(`view=${createdPid}`), { timeout: 5000 });

    await page.getByTestId(`saved-view-action-delete-${createdPid}`).click();
    await expect(page.getByTestId('confirm-dialog')).toBeVisible();
    await page.getByTestId('confirm-ok').click();
    await expect(page.getByTestId(`saved-view-row-${createdPid}`)).toHaveCount(0);
    await expect
      .poll(async () => (await listViews(page)).some((view) => view.pid === createdPid))
      .toBe(false);
  });

  test('SV-PER-003: personal view local changes can be saved current or as a new personal view', async ({
    page,
  }) => {
    const sourceName = `${RUN_PREFIX}-保存链路`;
    const sourcePid = await createView(page, sourceName);

    await navigateToOrderViaSidebar(page);
    await openSelector(page);
    await page.getByTestId(`view-option-${sourcePid}`).click();
    await expect(page).toHaveURL(new RegExp(`view=${sourcePid}`), { timeout: 5000 });

    await page.getByTestId('row-height-btn').click();
    await page.getByTestId('row-height-option-tall').click();
    await expect(page.getByTestId('personal-view-draft-banner')).toBeVisible();
    await expect(page.getByTestId('personal-view-draft-banner')).toContainText('当前个人视图');
    await page.screenshot({ path: `${SHOTS}/04-personal-draft-save.png`, fullPage: true });

    await page.getByTestId('personal-view-save-current').click();
    await expect(page.getByTestId('personal-view-draft-banner')).toHaveCount(0);
    await expect
      .poll(async () => (await getView(page, sourcePid)).viewConfig?.rowHeight)
      .toBe('tall');

    await page.getByTestId('row-height-btn').click();
    await page.getByTestId('row-height-option-extra-tall').click();
    await expect(page.getByTestId('personal-view-draft-banner')).toBeVisible();
    const createCopyResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/views',
      { timeout: 5000 },
    );
    await page.getByTestId('personal-view-save-as-new').click();
    const body = await (await createCopyResponse).json();
    const copiedPid = body.data?.pid ?? body.pid;
    expect(copiedPid).toBeTruthy();
    await expect(page).toHaveURL(new RegExp(`view=${copiedPid}`), { timeout: 5000 });
    const copied = await getView(page, copiedPid);
    expect(copied.scope).toBe('personal');
    expect(copied.name).toContain('副本');
    expect(copied.viewConfig?.rowHeight).toBe('extra-tall');
  });

  test('SV-PER-003b: discard restores the selected personal view state and clears transient sort URL', async ({
    page,
  }) => {
    const sourceName = `${RUN_PREFIX}-放弃排序`;
    const sourcePid = await createView(page, sourceName, {
      viewType: 'table',
      viewConfig: { rowHeight: 'medium', sorts: [] },
    });

    // URL-specific regression: a shared link may carry both an explicit view and
    // a transient sort. Discard must restore the saved view, not restage the URL sort.
    await page.goto(`/p/e2et_order?view=${sourcePid}&sort=e2et_order_amount%3Adesc`);
    await expect(page.getByTestId('dynamic-list')).toBeVisible();
    await expect(page.getByTestId('view-selector-trigger')).toHaveAttribute(
      'data-current-view-name',
      sourceName,
    );
    await expect(page.getByTestId('personal-view-draft-banner')).toBeVisible();
    await expect(page.getByTestId('personal-view-draft-banner')).toContainText('排序 1 项');

    await page.getByTestId('personal-view-discard-draft').click();

    await expect(page).not.toHaveURL(/sort=/);
    await expect(page.getByTestId('personal-view-draft-banner')).toHaveCount(0);
    expect((await getView(page, sourcePid)).viewConfig?.sorts ?? []).toEqual([]);

    await page.reload();
    await expect(page.getByTestId('view-selector-trigger')).toHaveAttribute(
      'data-current-view-name',
      sourceName,
    );
    await expect(page.getByTestId('personal-view-draft-banner')).toHaveCount(0);
    await expect(page).not.toHaveURL(/sort=/);
  });

  test('SV-PER-003c: default view column context hide updates immediately and persists', async ({
    page,
  }) => {
    const fieldCode = 'e2et_order_title';

    await navigateToOrderViaSidebar(page);
    await openSelector(page);
    await page.getByTestId('view-option-default').click();
    await expect(page.getByTestId('dynamic-list')).toBeVisible();
    await expect(page.getByTestId(`table-header-${fieldCode}`)).toBeVisible();

    const header = page.getByTestId(`table-header-${fieldCode}`);
    await header.click({ button: 'right' });
    await expect(page.getByTestId('column-context-menu')).toBeVisible();
    await page.getByTestId('column-context-menu-hide-column').click();

    await expect(page.getByTestId('column-context-menu')).toHaveCount(0);
    await expect(page.getByTestId(`table-header-${fieldCode}`)).toHaveCount(0);
    await expect(page.getByTestId('personal-view-draft-banner')).toHaveCount(0);

    await expect
      .poll(async () => {
        const implicitView = (await listViews(page)).find((view) => view.isImplicit);
        const columns = (implicitView?.viewConfig?.columns ?? []) as Array<{
          fieldCode?: string;
          visible?: boolean;
        }>;
        return columns.find((column) => column.fieldCode === fieldCode)?.visible;
      })
      .toBe(false);

    await page.reload();
    await expect(page.getByTestId('dynamic-list')).toBeVisible();
    await expect(page.getByTestId(`table-header-${fieldCode}`)).toHaveCount(0);
  });

  test('SV-PER-004: quick filter can be saved as a personal view from the toolbar', async ({
    page,
  }) => {
    await navigateToOrderViaSidebar(page);
    await openSelector(page);
    await page.getByTestId('view-option-default').click();
    await expect(page.getByTestId('quick-filters')).toBeVisible();

    const listReload = page
      .waitForResponse(
        (response) =>
          response.url().includes('/api/dynamic/e2et_order/list') && response.status() === 200,
        { timeout: 5000 },
      )
      .catch(() => null);
    await clickStableTestId(page, 'quick-filter-my_records');
    await listReload;

    const createResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/views',
      { timeout: 10000 },
    );
    await clickStableTestId(page, 'preset-view-save-as-personal');
    const body = await (await createResponse).json();
    const pid = body.data?.pid ?? body.pid;
    expect(pid).toBeTruthy();
    await expect(page).toHaveURL(new RegExp(`view=${pid}`), { timeout: 5000 });
    const savedPreset = await getView(page, pid);
    expect(savedPreset.scope).toBe('personal');
    expect(savedPreset.viewConfig?.meta).toMatchObject({ originPresetKey: 'my_records' });
    await expect(page.getByTestId('quick-filter-my_records')).toHaveAttribute(
      'data-preset-active',
      'false',
    );
    await expect(page.getByTestId('preset-view-save-as-personal')).toHaveCount(0);
    await deleteView(page, pid);
  });

  test('SV-PER-005: personal quota blocks new views and capability gate explains blocked/degraded views', async ({
    page,
  }) => {
    await navigateToOrderViaSidebar(page);
    await openManagePanel(page);
    await page.getByTestId('saved-view-create-personal').click();
    await page.getByTestId('saved-view-type-gallery').click();
    await expect(page.getByTestId('view-capability-blocked-gallery')).toBeVisible();
    await expect(page.getByTestId('view-capability-blocked-gallery')).toContainText(
      /缺少|图片|附件|封面/,
    );
    await page.screenshot({ path: `${SHOTS}/05-capability-blocked.png`, fullPage: true });

    await page.getByTestId('saved-view-type-kanban').click();
    await expect(page.getByTestId('view-capability-degraded-kanban')).toBeVisible();
    await expect(page.getByTestId('view-capability-degraded-kanban')).toContainText(
      /拖拽|状态更新/,
    );
    await page.screenshot({ path: `${SHOTS}/06-capability-degraded-create.png`, fullPage: true });

    await cleanupRunViews(page);
    await fillPersonalQuota(page);
    await page.reload();
    await expect(page.getByTestId('view-selector-trigger')).toBeVisible({ timeout: 5000 });
    await navigateToOrderViaSidebar(page);
    await openManagePanel(page);
    await page.getByTestId('saved-view-create-personal').click();
    await expect(page.getByTestId('saved-view-quota-status')).toContainText('个人视图：10/10');
    await expect(page.getByTestId('saved-view-quota-limit-reached')).toContainText(
      '已达到 10 个个人视图上限',
    );
    await expect(page.getByTestId('saved-view-type-table')).toBeDisabled();
    await page.screenshot({ path: `${SHOTS}/07-personal-quota.png`, fullPage: true });
  });
});
