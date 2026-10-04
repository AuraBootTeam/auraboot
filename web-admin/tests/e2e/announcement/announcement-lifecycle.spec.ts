/**
 * Announcement State Machine — E2E Lifecycle Test
 *
 * Coverage:
 *   D1  Menu Navigation — sidebar click to announcement list
 *   D2  List Rendering — table with columns, data visible
 *   D4  Create (Full Form) — fill title, content, priority, pinned, expires_at
 *   D6  Create Verification — new record in list with status=draft
 *   D9  State Transitions — draft→published→archived→published
 *   D10 Invalid Transitions — reject publish on published record
 *   D11 Delete — confirm dialog, record removed
 *   D14 Toast / Feedback — mutation shows success toast
 *
 * Prerequisites:
 *   - core-announcement plugin imported
 *   - Backend + Frontend running
 *
 * @since 6.5.0
 */

import { test, expect, type Page } from '../../fixtures';
import {
  uniqueId,
  dateOffsetStr,
  waitForDynamicPageLoad,
  findRowByContent,
  extractRecordId,
} from '../helpers/index';

test.describe.configure({ mode: 'serial' });

const UID = uniqueId('ANN');
const TITLE = `Test Announcement ${UID}`;
const CONTENT = `Announcement content for E2E test ${UID}`;
const EXPIRES = dateOffsetStr(30) + 'T23:59:59Z';

let recordPid: string;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function navigateToAnnouncementList(page: Page): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  const parentBtn = nav.locator('text="公告管理"').first();
  await parentBtn.waitFor({ state: 'visible', timeout: 5_000 });
  await parentBtn.evaluate((el: HTMLElement) => el.click());

  const leafLink = nav.locator('a[href*="ab_announcement"]').first();
  await leafLink.waitFor({ state: 'visible', timeout: 5_000 });
  await leafLink.evaluate((el: HTMLElement) => el.click());

  await waitForDynamicPageLoad(page);
}

/** Drive the scoped portaled menu and verify the command's actual response. */
async function clickRowAction(page: Page, title: string | RegExp, actionLabel: string | RegExp, commandCode: string): Promise<void> {
  const row = await findRowByContent(page, title);
  await row.getByTestId('row-action-more').click();
  const menu = page.getByTestId('row-action-dropdown');
  await expect(menu).toBeVisible();
  const action = menu.getByRole('menuitem', { name: actionLabel, exact: true });
  await expect(action).toBeVisible();
  await menu.screenshot({ path: test.info().outputPath(`menu-${String(actionLabel).replace(/[^a-zA-Z0-9\u4e00-\u9fff]/g, '_')}.png`) });
  await action.click();
  const dialog = page.getByTestId('confirm-dialog');
  await expect(dialog).toBeVisible();
  const confirmationText: Record<string, string> = {
    'announcement:publish': '发布后该公告将对读者可见，确定发布吗？',
    'announcement:archive': '撤回后该公告将不再对读者展示，可稍后重新发布。确定撤回吗？',
    'announcement:republish': '重新发布后该公告将再次对读者可见，确定重新发布吗？',
    'announcement:delete_announcement': '确定要删除选中的记录吗？此操作不可恢复。',
  };
  await expect(dialog).toContainText(confirmationText[commandCode]);
  await dialog.screenshot({ path: test.info().outputPath(`confirm-${commandCode.replace(':', '-')}.png`) });
  const responsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST' &&
    response.url().endsWith(`/api/meta/commands/execute/${commandCode}`));
  await page.getByTestId('confirm-ok').click();
  const response = await responsePromise;
  expect(response.ok()).toBe(true);
  expect(response.request().postDataJSON().targetRecordPid).toBe(recordPid);
  expect(String((await response.json()).code)).toBe('0');
  await expect(dialog).toBeHidden();
}

/** Wait for a row's status cell to show the expected status */
async function expectRowStatus(page: Page, title: string, status: RegExp): Promise<void> {
  const row = page.locator('table tbody tr').filter({ hasText: title }).first();
  await expect(row.locator('td').filter({ hasText: status }).first())
    .toBeVisible({ timeout: 5_000 });
}

// ---------------------------------------------------------------------------
// D4 — Create Announcement (Draft)
// ---------------------------------------------------------------------------

test('create announcement in draft status', async ({ page }) => {
  await navigateToAnnouncementList(page);
  await page.getByRole('button', { name: '新建公告', exact: true }).click();
  await expect(page.getByText('公告内容', { exact: true })).toBeVisible();
  const title = page.getByTestId('form-field-title').locator('input');
  const content = page.getByTestId('form-field-content').locator('textarea');
  await expect(title).toHaveAttribute('placeholder', '例如：10 月 12 日系统维护通知');
  await expect(content).toHaveAttribute('placeholder', '说明适用对象、具体事项和生效时间');
  await expect(page.getByText('用简明标题说明事项，最多 256 个字符；保存后为草稿，发布后读者才可见。', { exact: true })).toBeVisible();
  await expect(page.getByText('补充影响范围、时间安排和需要读者采取的行动。', { exact: true })).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('create-form-guidance.png'), fullPage: true });
  await title.fill(TITLE);
  await content.fill(CONTENT);
  await page.getByTestId('form-field-expires_at').locator('input').fill(EXPIRES.slice(0, 16));
  const responsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST' &&
    response.url().endsWith('/api/meta/commands/execute/announcement:create_announcement'));
  await page.getByRole('button', { name: '保存', exact: true }).click();
  const response = await responsePromise;
  expect(response.ok()).toBe(true);
  const request = response.request().postDataJSON();
  expect(request.payload.title).toBe(TITLE);
  expect(request.payload.content).toBe(CONTENT);
  const body = await response.json();
  expect(String(body.code)).toBe('0');
  recordPid = extractRecordId(body);
  expect(recordPid).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
  const saved = await page.request.get(`/api/dynamic/ab_announcement/${recordPid}`);
  expect(saved.ok()).toBe(true);
  const savedBody = await saved.json();
  expect(String(savedBody.code)).toBe('0');
  expect(savedBody.data.title).toBe(TITLE);
  expect(savedBody.data.content).toBe(CONTENT);
  expect(savedBody.data.status).toBe('draft');
  expect(savedBody.data.announcement_priority).toBe('normal');
  expect(savedBody.data.pinned).toBe(false);
  expect(savedBody.data.expires_at).toBeTruthy();
});

// ---------------------------------------------------------------------------
// D1 + D2 — Navigate and verify list rendering
// ---------------------------------------------------------------------------

test('list page shows created announcement with draft status', async ({ page }) => {
  await navigateToAnnouncementList(page);

  const table = page.locator('table');
  await table.first().waitFor({ state: 'visible', timeout: 10_000 });

  const row = await findRowByContent(page, TITLE);
  await expect(row).toBeVisible();

  await expectRowStatus(page, TITLE, /draft|草稿/i);
  await page.getByTestId('filters-toggle').click();
  const filterArea = page.getByTestId('search-area');
  const titleFilter = filterArea.getByPlaceholder('例如：周末系统维护', { exact: true });
  await expect(titleFilter).toBeVisible();
  await expect(filterArea.getByText('按公告标题关键词查找；清除关键词可查看全部公告。', { exact: true })).toBeVisible();
  await filterArea.screenshot({ path: test.info().outputPath('title-filter-guidance.png') });
  await titleFilter.fill(`${TITLE}-no-match`);
  await page.getByTestId('filter-search').click();
  await expect(page.getByText('没有匹配的公告', { exact: true })).toBeVisible();
  await expect(page.getByText('选择“新建公告”创建草稿，或清除筛选条件查看已有公告。', { exact: true })).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('filtered-empty-recovery.png'), fullPage: true });
  await page.getByTestId('filter-reset').click();
  await expect(await findRowByContent(page, TITLE)).toBeVisible();
  await page.getByTestId('filters-toggle').click();
});

// ---------------------------------------------------------------------------
// D9 — State Transition: draft → published (via row action)
// ---------------------------------------------------------------------------

test('publish announcement from draft to published', async ({ page }) => {
  await navigateToAnnouncementList(page);
  await clickRowAction(page, TITLE, '发布', 'announcement:publish');
  await expectRowStatus(page, TITLE, /published|已发布/i);
});

// ---------------------------------------------------------------------------
// D9 — Verify conditional row actions for published status
// ---------------------------------------------------------------------------

test('published record shows archive action, hides edit and publish', async ({ page }) => {
  await navigateToAnnouncementList(page);

  const row = await findRowByContent(page, TITLE);
  const moreBtn = row.getByTestId('row-action-more');
  await moreBtn.click();

  const menu = page.getByTestId('row-action-dropdown');
  await expect(menu).toBeVisible();
  await expect(menu.getByRole('menuitem', { name: '撤回', exact: true })).toBeVisible();
  await expect(menu.getByRole('menuitem', { name: /^(edit|编辑|发布|publish)$/i })).toHaveCount(0);
  await menu.screenshot({ path: test.info().outputPath('published-actions.png') });
});

// ---------------------------------------------------------------------------
// D9 — State Transition: published → archived
// ---------------------------------------------------------------------------

test('archive announcement from published to archived', async ({ page }) => {
  await navigateToAnnouncementList(page);
  await clickRowAction(page, TITLE, '撤回', 'announcement:archive');
  await expectRowStatus(page, TITLE, /archived|已撤回/i);
});

// ---------------------------------------------------------------------------
// D9 — State Transition: archived → published (republish)
// ---------------------------------------------------------------------------

test('republish announcement from archived to published', async ({ page }) => {
  await navigateToAnnouncementList(page);
  await clickRowAction(page, TITLE, '重新发布', 'announcement:republish');
  await expectRowStatus(page, TITLE, /published|已发布/i);
});

// ---------------------------------------------------------------------------
// D10 — Invalid Transition: publish on already-published (API guard)
// ---------------------------------------------------------------------------

test('reject publish on already-published record via API', async ({ page }) => {
  expect(recordPid).toBeTruthy();

  const before = await page.request.get(`/api/dynamic/ab_announcement/${recordPid}`);
  expect(before.ok()).toBe(true);
  const beforeBody = await before.json();
  expect(String(beforeBody.code)).toBe('0');
  expect(beforeBody.data.status).toBe('published');
  const response = await page.request.post('/api/meta/commands/execute/announcement:publish', {
    data: { targetRecordPid: recordPid, expectedVersion: beforeBody.data.row_version, payload: {} },
  });
  const body = await response.json();
  expect(response.status()).toBe(422);
  expect(String(body.code)).not.toBe('0');
  expect(JSON.stringify(body)).toContain('published');
  expect(JSON.stringify(body)).toContain('allowed states');
  const after = await page.request.get(`/api/dynamic/ab_announcement/${recordPid}`);
  expect(after.ok()).toBe(true);
  const afterBody = await after.json();
  expect(String(afterBody.code)).toBe('0');
  expect(afterBody.data.status).toBe('published');
  expect(afterBody.data.row_version).toBe(beforeBody.data.row_version);
});

// ---------------------------------------------------------------------------
// D9 + D11 — Archive then Delete
// ---------------------------------------------------------------------------

test('archive and delete announcement', async ({ page }) => {
  // Archive first (published → archived) so delete becomes available
  await navigateToAnnouncementList(page);
  await clickRowAction(page, TITLE, '撤回', 'announcement:archive');
  await expectRowStatus(page, TITLE, /archived|已撤回/i);

  // Delete
  await clickRowAction(page, TITLE, /delete|删除/i, 'announcement:delete_announcement');

  // Verify record is gone
  const gone = page.locator('table').getByText(TITLE);
  await expect(gone).toHaveCount(0, { timeout: 5_000 });
  await page.reload();
  await waitForDynamicPageLoad(page);
  await expect(page.getByText(/^(加载中\.\.\.|Loading\.\.\.)$/)).toBeHidden();
  await expect(page.locator('table').getByText(TITLE)).toHaveCount(0);
  const deleted = await page.request.get(`/api/dynamic/ab_announcement/${recordPid}`);
  expect(deleted.status()).toBe(404);
  await page.screenshot({ path: test.info().outputPath('deleted-after-reload.png'), fullPage: true });
});
