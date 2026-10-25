/**
 * Announcement State Machine — E2E Lifecycle Test
 *
 * Coverage:
 *   D1  Menu Navigation — sidebar click to announcement list
 *   D2  List Rendering — table with columns, data visible
 *   D4  Create (Full Form) — fill title, content, priority, pinned, expires_at
 *   D6  Create Verification — new record in list with status=draft
 *   D9  S1 draft edits, protected writes and unavailable publication
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
import { randomUUID } from 'node:crypto';
import { Client } from 'pg';
import { PG_CONN } from '../../helpers/environments';
import { authenticateBffSession } from '../../helpers/bff-auth-session';
import {
  uniqueId,
  dateOffsetStr,
  waitForDynamicPageLoad,
  findRowByContent,
  extractRecordId,
} from '../helpers/index';

test.use({ locale: 'zh-CN' });
test.describe.configure({ mode: 'serial' });

const UID = uniqueId('ANN');
const TITLE = `Test Announcement ${UID}`;
const CONTENT = `Announcement content for E2E test ${UID}`;
const EXPIRES = dateOffsetStr(30) + 'T23:59:59Z';

let recordPid: string;
let recipientLogin: { email: string; userPid: string };

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function navigateToAnnouncementList(page: Page): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  const parentBtn = nav.locator('text="公告管理"').first();
  await parentBtn.waitFor({ state: 'visible', timeout: 5_000 });
  const leafLink = nav.locator('a[href="/p/ab_announcement"]');
  if (!(await leafLink.isVisible())) await parentBtn.click();
  await leafLink.waitFor({ state: 'visible', timeout: 5_000 });
  await leafLink.click();

  await waitForDynamicPageLoad(page);
}

/** Drive the scoped portaled menu and verify the command's actual response. */
async function clickRowAction(
  page: Page,
  title: string | RegExp,
  actionLabel: string | RegExp,
  commandCode: string,
  targetPid = recordPid,
): Promise<void> {
  const row = await findRowByContent(page, title);
  await row.getByTestId('row-action-more').click();
  const menu = page.getByTestId('row-action-dropdown');
  await expect(menu).toBeVisible();
  const action = menu.getByRole('menuitem', { name: actionLabel, exact: true });
  await expect(action).toBeVisible();
  await menu.screenshot({
    animations: 'disabled',
    path: test
      .info()
      .outputPath(`menu-${String(actionLabel).replace(/[^a-zA-Z0-9\u4e00-\u9fff]/g, '_')}.png`),
  });
  await action.click();
  const dialog = page.getByTestId('confirm-dialog');
  await expect(dialog).toBeVisible();
  const confirmationText: Record<string, string> = {
    'announcement:publish': '发布后该公告将对读者可见，确定发布吗？',
    'announcement:cancel_schedule': '取消后不会发布，冻结版本和历史记录仍会保留。确定取消吗？',
    'announcement:archive': '撤回后该公告将不再对读者展示。历史版本保留；重新发布须建立修订草稿并重新提交。确定撤回吗？',
    'announcement:republish': '将基于历史版本建立可编辑草稿，保存不会直接发布。确定建立吗？',
    'announcement:delete_announcement': '确定要删除选中的记录吗？此操作不可恢复。',
  };
  await expect(dialog).toContainText(confirmationText[commandCode]);
  await dialog.screenshot({
    animations: 'disabled',
    path: test.info().outputPath(`confirm-${commandCode.replace(':', '-')}.png`),
  });
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().endsWith(`/api/meta/commands/execute/${commandCode}`),
  );
  await page.getByTestId('confirm-ok').click();
  const response = await responsePromise;
  expect(response.ok()).toBe(true);
  expect(response.request().postDataJSON().targetRecordPid).toBe(targetPid);
  expect(String((await response.json()).code)).toBe('0');
  await expect(dialog).toBeHidden();
}

/** Wait for a row's status cell to show the expected status */
async function expectRowStatus(page: Page, title: string, status: RegExp): Promise<void> {
  const row = page.locator('table tbody tr').filter({ hasText: title }).first();
  await expect(row.locator('td').filter({ hasText: status }).first()).toBeVisible({
    timeout: 5_000,
  });
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
  await expect(
    page.getByText('可先保存未完成的草稿，再从公告列表的“通知设置”选择受众、发布方式和知悉要求。', { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText('补充影响范围、时间安排和需要读者采取的行动。', { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    animations: 'disabled',
    path: test.info().outputPath('create-form-guidance.png'),
    fullPage: true,
  });
  await expect(
    page.getByTestId('form-field-announcement_priority').getByRole('combobox'),
  ).toContainText('普通');
  await title.fill(TITLE);
  await content.fill(CONTENT);
  await page.getByTestId('form-field-expires_at').locator('input').fill(EXPIRES.slice(0, 16));
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().endsWith('/api/meta/commands/execute/announcement:create_announcement'),
  );
  await page.getByRole('button', { name: '保存', exact: true }).click();
  const response = await responsePromise;
  expect(response.ok()).toBe(true);
  const request = response.request().postDataJSON();
  expect(request.clientRequestId).toBeTruthy();
  expect(request.expectedVersion).toBeUndefined();
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
  const headers=table.first().getByRole('columnheader');
  const expiryIndex=await headers.evaluateAll(items=>items.findIndex(item=>item.textContent?.includes('过期')));
  const actionsIndex=await headers.evaluateAll(items=>items.findIndex(item=>item.textContent?.trim()==='操作'));
  expect(expiryIndex).toBeGreaterThanOrEqual(0);expect(actionsIndex).toBeGreaterThan(expiryIndex);
  const expiryCell=row.getByRole('cell').nth(expiryIndex),actionsCell=row.getByRole('cell').nth(actionsIndex);
  await expect(expiryCell).toContainText(/\d{4}[-/]\d{2}[-/]\d{2} \d{2}:\d{2}/);
  const expiryBox=await expiryCell.boundingBox(),actionsBox=await actionsCell.boundingBox();
  expect(expiryBox).not.toBeNull();expect(actionsBox).not.toBeNull();
  expect(expiryBox!.x+expiryBox!.width,'The expiry timestamp must fit before the sticky actions').toBeLessThanOrEqual(actionsBox!.x+1);
  await page.getByTestId('filters-toggle').click();
  const filterArea = page.getByTestId('search-area');
  const titleFilter = filterArea.getByPlaceholder('例如：周末系统维护', { exact: true });
  await expect(titleFilter).toBeVisible();
  await expect(
    filterArea.getByText('按公告标题关键词查找；清除关键词可查看全部公告。', { exact: true }),
  ).toBeVisible();
  await filterArea.screenshot({
    animations: 'disabled',
    path: test.info().outputPath('title-filter-guidance.png'),
  });
  await titleFilter.fill(`${TITLE}-no-match`);
  await page.getByTestId('filter-search').click();
  await expect(page.getByText('没有匹配的公告', { exact: true })).toBeVisible();
  await expect(
    page.getByText('选择“新建公告”创建草稿，或清除筛选条件查看已有公告。', { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    animations: 'disabled',
    path: test.info().outputPath('filtered-empty-recovery.png'),
    fullPage: true,
  });
  await page.getByTestId('filter-reset').click();
  await expect(await findRowByContent(page, TITLE)).toBeVisible();
  await page.getByTestId('filters-toggle').click();
});

test('edit draft through the menu and retain the observed revision', async ({ page }) => {
  await navigateToAnnouncementList(page);
  const row = await findRowByContent(page, TITLE);
  await row.getByTestId('row-action-more').click();
  await page
    .getByTestId('row-action-dropdown')
    .getByRole('menuitem', { name: '编辑', exact: true })
    .click();
  await expect(page.getByTestId('form-field-title').locator('input')).toHaveValue(TITLE);
  await page.getByTestId('form-field-content').locator('textarea').fill(`${CONTENT} edited`);
  await page.getByTestId('form-field-announcement_priority').getByRole('combobox').click();
  await page.getByRole('option', { name: '重要', exact: true }).click();
  await page.getByTestId('form-field-pinned').getByRole('switch').click();
  await expect(page.getByTestId('form-field-pinned').getByRole('switch')).toHaveAttribute(
    'aria-checked',
    'true',
  );
  await page.screenshot({
    animations: 'disabled',
    path: test.info().outputPath('edit-form-values.png'),
    fullPage: true,
  });
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().endsWith('/api/meta/commands/execute/announcement:update_announcement'),
  );
  await page.getByRole('button', { name: '保存', exact: true }).click();
  const response = await responsePromise;
  expect(response.status(), await response.text()).toBe(200);
  const input = response.request().postDataJSON();
  expect(input.expectedVersion).toBe(1);
  expect(input.payload.announcement_priority).toBe('important');
  expect(input.payload.pinned).toBe(true);
  expect(input.clientRequestId).toBeTruthy();
  expect(Object.keys(input.payload).sort()).toEqual([
    'announcement_priority',
    'attachments',
    'content',
    'expires_at',
    'pinned',
    'title',
  ]);
  expect((await response.json()).data.data.row_version).toBe(2);
  await navigateToAnnouncementList(page);
  const editedRow = await findRowByContent(page, TITLE);
  await editedRow.getByTestId('row-action-view').click();
  await expect(page.getByText(`${CONTENT} edited`, { exact: true })).toBeVisible();
  await expect(page.getByText('重要', { exact: true })).toBeVisible();
  await page.screenshot({
    animations: 'disabled',
    path: test.info().outputPath('draft-detail.png'),
    fullPage: true,
  });
});

test('publication reports unavailable and leaves the draft intact', async ({ page }) => {
  await navigateToAnnouncementList(page);
  const row = await findRowByContent(page, TITLE);
  await row.getByTestId('row-action-more').click();
  await page
    .getByTestId('row-action-dropdown')
    .getByRole('menuitem', { name: '免审发布', exact: true })
    .click();
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().endsWith('/api/meta/commands/execute/announcement:publish'),
  );
  await page.getByTestId('confirm-ok').click();
  const response = await responsePromise;
  expect(response.status()).toBe(422);
  expect(response.request().postDataJSON().payload).toEqual({});
  await expect(
    page
      .getByTestId('ab:list:ab_announcement:action-error')
      .getByText('免审发布需要选择免审模式并填写免审原因。', { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    animations: 'disabled',
    path: test.info().outputPath('publication-unavailable.png'),
    fullPage: true,
  });
  await navigateToAnnouncementList(page);
  await expectRowStatus(page, TITLE, /draft|草稿/i);
});

test('delete draft through its protected command', async ({ page }) => {
  await navigateToAnnouncementList(page);
  await page.getByTestId('filters-toggle').click();
  await page.getByTestId('search-area').getByPlaceholder('例如：周末系统维护',{exact:true}).fill(TITLE);
  await page.getByTestId('filter-search').click();
  await expect(await findRowByContent(page,TITLE)).toBeVisible();
  await clickRowAction(page, TITLE, '删除', 'announcement:delete_announcement');
  await expect(page.locator('table tbody tr').filter({ hasText: TITLE })).toHaveCount(0);
  await expect(page.getByText('没有匹配的公告', { exact: true })).toBeVisible();
  await page.screenshot({
    animations: 'disabled',
    path: test.info().outputPath('deleted-draft.png'),
    fullPage: true,
  });
});

test('withdraw legacy content and create a revision without mutating its frozen version', async ({
  page,
}) => {
  const pid = randomUUID();
  const versionPid = randomUUID();
  const title = `Historical ${TITLE}`;
  const db = new Client(PG_CONN);
  await db.connect();
  try {
    await db.query('BEGIN');
    await db.query(
      `INSERT INTO ab_announcement(pid,tenant_id,owner_user_id,title,content,status,created_by,published_by,published_at)
      SELECT $1,tenant_id,owner_user_id,$2,$3,'published',created_by,created_by,now()
      FROM ab_announcement WHERE pid=$4`,
      [pid, title, CONTENT, recordPid],
    );
    await db.query(
      `INSERT INTO ab_announcement_version(pid,tenant_id,announcement_pid,version_no,source_editor_revision,
      snapshot_schema,snapshot_hash,snapshot,created_by)
      SELECT $1,tenant_id,pid,1,1,'legacy-v1',encode(sha256(convert_to(jsonb_build_object('title',title,'content',content)::text,'UTF8')),'hex'),
      jsonb_build_object('title',title,'content',content),created_by FROM ab_announcement WHERE pid=$2`,
      [versionPid, pid],
    );
    await db.query(
      `INSERT INTO ab_announcement_version_control(tenant_id,version_pid,publication_state,authorization_basis)
      SELECT tenant_id,$1,'published','legacy_existing_content' FROM ab_announcement WHERE pid=$2`,
      [versionPid, pid],
    );
    await db.query(
      'UPDATE ab_announcement SET current_version_pid=$1,active_version_pid=$1 WHERE pid=$2',
      [versionPid, pid],
    );
    await db.query(
      `INSERT INTO ab_announcement_recipient_snapshot(tenant_id,pid,version_pid,origin,recipient_count,sealed)
      SELECT tenant_id,$1,$2,'migration_cohort',1,FALSE FROM ab_announcement WHERE pid=$3`,
      [randomUUID(), versionPid, pid],
    );
    await db.query(
      `INSERT INTO ab_announcement_recipient(tenant_id,version_pid,user_pid,member_pid,display_name_snapshot)
      SELECT a.tenant_id,$1,u.pid,m.pid,u.user_name FROM ab_announcement a
      JOIN ab_user u ON u.id=a.owner_user_id JOIN ab_tenant_member m ON m.tenant_id=a.tenant_id AND m.user_id=u.id
      WHERE a.pid=$2 AND m.status='active' AND m.deleted_flag=FALSE`,
      [versionPid, pid],
    );
    await db.query('UPDATE ab_announcement_recipient_snapshot SET sealed=TRUE WHERE version_pid=$1',[versionPid]);
    await db.query('COMMIT');
    const visible = await page.request.get('/api/announcements?limit=100');
    expect(visible.status()).toBe(200);
    expect(
      (await visible.json()).data.find((row: { pid: string }) => row.pid === pid),
    ).toMatchObject({ title, content: CONTENT, status: 'published' });
    await navigateToAnnouncementList(page);
    await clickRowAction(page, title, '撤回', 'announcement:archive', pid);
    await expectRowStatus(page, title, /withdrawn|已撤回/i);
    const withdrawn = await page.request.get('/api/announcements?limit=100');
    expect((await withdrawn.json()).data.some((row: { pid: string }) => row.pid === pid)).toBe(
      false,
    );
    await clickRowAction(page, title, '建立修订草稿', 'announcement:republish', pid);
    await expectRowStatus(page, title, /draft|草稿/i);
    const row = await findRowByContent(page, title);
    await row.getByTestId('row-action-more').click();
    await expect(
      page.getByTestId('row-action-dropdown').getByRole('menuitem', { name: '删除', exact: true }),
    ).toHaveCount(0);
    await page.screenshot({
      animations: 'disabled',
      path: test.info().outputPath('legacy-revision.png'),
      fullPage: true,
    });
    const frozen = await db.query(
      'SELECT snapshot->>$$title$$ AS title FROM ab_announcement_version WHERE pid=$1',
      [versionPid],
    );
    expect(frozen.rows).toEqual([{ title }]);
    const state = await db.query(
      'SELECT status,row_version,active_version_pid FROM ab_announcement WHERE pid=$1',
      [pid],
    );
    expect(state.rows).toEqual([{ status: 'draft', row_version: 3, active_version_pid: null }]);
  } finally {
    await db.end();
  }
});

test('read-only manager cannot create or modify notices', async ({ page, browser, baseURL }) => {
  expect(baseURL).toBeTruthy();
  const code = `notice_reader_${randomUUID().replaceAll('-', '').slice(0, 10)}`;
  const createdRole = await page.request.post('/api/roles', {
    data: {
      code,
      name: 'Announcement read-only observer',
      type: 'custom',
      status: 'active',
      scopeType: 'tenant',
      defaultDataScopeType: 'all',
    },
  });
  expect(createdRole.status(), await createdRole.text()).toBe(200);
  const rolePid = (await createdRole.json()).data.pid;
  const db = new Client(PG_CONN);
  await db.connect();
  let permissions;
  try {
    permissions = (
      await db.query(
        "SELECT p.id,p.code FROM ab_permission p JOIN ab_role r ON r.tenant_id=p.tenant_id WHERE r.pid=$1 AND p.code IN ('announcement.announcement.read','model.ab_announcement.read') AND p.deleted_flag=FALSE",
        [rolePid],
      )
    ).rows;
    expect(permissions.map((permission) => permission.code).sort()).toEqual(['announcement.announcement.read', 'model.ab_announcement.read']);
  } finally {
    await db.end();
  }
  const grant = await page.request.put(`/api/permissions/matrix/${rolePid}/batch`, {
    data: permissions.map((row) => ({ permissionId: row.id, granted: true })),
  });
  expect(grant.status(), await grant.text()).toBe(200);
  const email = `${code}@example.com`;
  const user = await page.request.post('/api/admin/users', {
    data: {
      email,
      displayName: 'Announcement observer',
      initialPassword: 'Test2026x',
      roleCodes: [code],
      sendInviteEmail: false,
    },
  });
  expect(user.status(), await user.text()).toBe(200);
  const context = await browser.newContext({
    baseURL,
    locale: 'zh-CN',
    storageState: { cookies: [], origins: [] },
  });
  try {
    await authenticateBffSession(context.request, baseURL!, email, 'Test2026x');
    const reader = await context.newPage();
    const loaded = reader.waitForResponse(response => response.request().method() === 'GET'
      && response.url().includes('/api/dynamic/ab_announcement/list'));
    await navigateToAnnouncementList(reader);
    expect((await loaded).ok()).toBe(true);
    await expect(reader.getByRole('columnheader', { name: /优先级$/ })).toBeVisible();
    await expect(reader.getByText('加载中...', { exact: true })).toHaveCount(0);
    await expect(reader.getByRole('button', { name: '新建公告', exact: true })).toHaveCount(0);
    const denied = await context.request.post(
      '/api/meta/commands/execute/announcement:create_announcement',
      {
        headers: { Referer: baseURL! + '/' },
        data: {
          clientRequestId: randomUUID(),
          operationType: 'CREATE',
          payload: { title: 'Unauthorized' },
        },
      },
    );
    expect(denied.status(), await denied.text()).toBe(403);
    await reader.screenshot({
      animations: 'disabled',
      path: test.info().outputPath('permission-denied.png'),
      fullPage: true,
    });
  } finally {
    await context.close();
  }
});


async function setupPublicationDraft(page: Page, suffix: string) {
  const title = `${suffix} ${UID}`;
  const response = await page.request.post('/api/announcements', { data: {
    operationId: randomUUID(), title, content: `Audience policy for ${UID}`, priority: 'important',
  } });
  expect(response.status(), await response.text()).toBe(200);
  return { title, pid: (await response.json()).data.pid as string };
}

async function openNotificationSettings(page: Page, title: string) {
  const row = await findRowByContent(page, title);
  await row.getByTestId('row-action-more').click();
  await page.getByTestId('row-action-dropdown').getByRole('menuitem', { name: '通知设置', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('heading', { name: '通知设置', exact: true })).toBeVisible();
  return dialog;
}

test('scheduled audience settings preserve important acknowledgement and cancellation never creates recipients', async ({ page, browser }) => {
  test.setTimeout(30000);
  const draft = await setupPublicationDraft(page, 'Scheduled audience');
  const code = `notice_recipient_${randomUUID().replaceAll('-', '').slice(0, 10)}`;
  const role = await page.request.post('/api/roles', { data: {
    code, name: 'Announcement recipient', type: 'custom', status: 'active', scopeType: 'tenant', defaultDataScopeType: 'self',
  } });
  expect(role.status(), await role.text()).toBe(200);
  const recipientName = `No employee ${UID}`;
  const email = `${code}@example.com`;
  const user = await page.request.post('/api/admin/users', { data: {
    email, displayName: recipientName, initialPassword: 'Test2026x', roleCodes: [code], sendInviteEmail: false,
  } });
  expect(user.status(), await user.text()).toBe(200);
  const db = new Client(PG_CONN); await db.connect();
  try {
    const person = (await db.query(`SELECT u.pid,m.pid AS member_pid FROM ab_user u
      JOIN ab_tenant_member m ON m.user_id=u.id WHERE u.email=$1 AND m.status='active' AND m.deleted_flag=FALSE`, [email])).rows[0];
    expect(person).toBeTruthy();
    recipientLogin = { email, userPid: person.pid };
    expect((await db.query('SELECT count(*)::int AS count FROM mt_org_employee WHERE org_emp_member_id=$1 AND deleted_flag=FALSE', [person.member_pid])).rows[0].count).toBe(0);
    await navigateToAnnouncementList(page);
    let dialog = await openNotificationSettings(page, draft.title);
    const modes=dialog.getByTestId('form-dialog-field-approval_mode');
    await expect(modes.getByRole('radio', { name: '免审发布', exact: true })).toBeEnabled();
    await expect(modes.getByRole('radio', { name: /^审批后发布/ })).toBeDisabled();
    await expect(modes.getByRole('radio', { name: /^审批后发布/ })).toContainText(/未接入可用审批服务|需要相应权限/);
    await modes.screenshot({ path: test.info().outputPath('approval-modes-provider-unavailable.png'), animations: 'disabled' });
    await expect(dialog.getByLabel('要求确认已知悉', { exact: true })).toBeChecked();
    await dialog.getByTestId('form-dialog-field-audience_users').getByRole('checkbox', { name: recipientName, exact: true }).check();
    await dialog.getByTestId('form-dialog-field-audience_kind').scrollIntoViewIfNeeded();
    await dialog.screenshot({ path: test.info().outputPath('notification-settings-recipient.png'), animations: 'disabled' });
    await dialog.getByLabel('免审原因', { exact: true }).fill('Scheduled maintenance approved by the tenant.');
    await dialog.getByLabel('预约发布时间', { exact: true }).fill(dateOffsetStr(2) + 'T12:00');
    await dialog.screenshot({ path: test.info().outputPath('notification-settings-audience.png'), animations: 'disabled' });
    const savedResponse = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/api/meta/commands/execute/announcement:update_announcement'));
    await dialog.getByTestId('form-dialog-submit').click();
    const saved = await savedResponse;
    expect(saved.status(), await saved.text()).toBe(200);
    const payload = saved.request().postDataJSON();
    expect(payload.expectedVersion).toBe(1);
    expect(payload.payload.audience_users).toEqual([person.pid]);
    expect(payload.payload.ack_required).toBe(true);
    expect(payload.payload.scheduled_at).toMatch(/Z$/);
    expect(payload.payload.status).toBeUndefined();
    await expect(dialog).toBeHidden();
    dialog = await openNotificationSettings(page, draft.title);
    await expect(dialog.getByTestId('form-dialog-field-audience_users').getByRole('checkbox', { name: recipientName, exact: true })).toBeChecked();
    await expect(dialog.getByLabel('要求确认已知悉', { exact: true })).toBeChecked();
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    await clickRowAction(page, draft.title, '免审发布', 'announcement:publish', draft.pid);
    await expectRowStatus(page, draft.title, /定时待发布/);
    await page.screenshot({ path: test.info().outputPath('scheduled-publication.png'), animations: 'disabled', fullPage: true });
    await clickRowAction(page, draft.title, '取消发布安排', 'announcement:cancel_schedule', draft.pid);
    await expectRowStatus(page, draft.title, /已撤回/);
    const facts = await db.query(`SELECT r.status,(SELECT count(*)::int FROM ab_announcement_recipient p
      WHERE p.tenant_id=r.tenant_id AND p.version_pid=r.version_pid) AS recipients
      FROM ab_announcement_release r WHERE announcement_pid=$1`, [draft.pid]);
    expect(facts.rows).toEqual([{ status: 'canceled', recipients: 0 }]);
    await page.screenshot({ path: test.info().outputPath('canceled-publication.png'), animations: 'disabled', fullPage: true });
    const emptyContext=await browser.newContext({baseURL:new URL(page.url()).origin,locale:'zh-CN'});
    try {
      await authenticateBffSession(emptyContext.request,new URL(page.url()).origin,email,'Test2026x');
      const emptyPage=await emptyContext.newPage();await emptyPage.goto('/home',{waitUntil:'domcontentloaded'});
      const menu=emptyPage.locator('nav').first();const mine=menu.locator('a[href="/p/announcement_reader"]');
      if(!await mine.isVisible())await menu.getByText('公告管理',{exact:true}).click();
      const loaded=emptyPage.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname==='/api/announcements/received');
      await mine.click();const response=await loaded;expect(response.status()).toBe(200);
      expect((await response.json()).data.records).toEqual([]);
      await expect(emptyPage.getByText('暂无符合条件的公告',{exact:true})).toBeVisible();
      await emptyPage.screenshot({path:test.info().outputPath('recipient-empty-after-schedule-cancel.png'),fullPage:true,animations:'disabled'});
    } finally {await emptyContext.close();}

  } finally { await db.end(); }
});

test('immediate exempt publication seals the real tenant audience and dispatches independent projections', async ({ page, browser }) => {
  // Includes bounded worker waits and two independently authenticated recipient journeys.
  test.setTimeout(90000);
  const draft = await setupPublicationDraft(page, 'Immediate audience');
  await navigateToAnnouncementList(page);
  const dialog = await openNotificationSettings(page, draft.title);
  await dialog.getByRole('combobox', { name: '通知范围*', exact: true }).selectOption('all');
  await expect(dialog.getByTestId('form-dialog-field-audience_users')).toHaveCount(0);
  await dialog.getByLabel('免审原因', { exact: true }).fill('Authorized tenant-wide policy announcement.');
  const savedResponse = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/api/meta/commands/execute/announcement:update_announcement'));
  await dialog.getByTestId('form-dialog-submit').click();
  const saved = await savedResponse; expect(saved.status(), await saved.text()).toBe(200);
  expect(saved.request().postDataJSON().payload.audience_kind).toBe('all');
  await clickRowAction(page, draft.title, '免审发布', 'announcement:publish', draft.pid);
  const db = new Client(PG_CONN); await db.connect();
  try {
    await expect.poll(async () => (await db.query('SELECT status FROM ab_announcement WHERE pid=$1', [draft.pid])).rows[0].status,
      { timeout: 20000 }).toBe('published');
    const version = (await db.query('SELECT active_version_pid,tenant_id FROM ab_announcement WHERE pid=$1', [draft.pid])).rows[0];
    const expected = (await db.query(`SELECT count(DISTINCT u.pid)::int AS count FROM ab_tenant_member m JOIN ab_user u ON u.id=m.user_id
      WHERE m.tenant_id=$1 AND m.status='active' AND m.deleted_flag=FALSE AND u.is_enabled=TRUE AND u.deleted_flag=FALSE`, [version.tenant_id])).rows[0].count;
    expect(expected).toBeGreaterThan(0);
    expect((await db.query('SELECT recipient_count::int,sealed FROM ab_announcement_recipient_snapshot WHERE tenant_id=$1 AND version_pid=$2', [version.tenant_id, version.active_version_pid])).rows)
      .toEqual([{ recipient_count: expected, sealed: true }]);
    await expect.poll(async () => (await db.query("SELECT count(*)::int AS count FROM ab_notification WHERE tenant_id=$1 AND source_type='announcement' AND source_id=$2", [version.tenant_id, version.active_version_pid])).rows[0].count,
      { timeout: 20000 }).toBe(expected);
    await expect.poll(async () => (await db.query("SELECT count(*)::int AS count FROM ab_inbox_item WHERE tenant_id=$1 AND source_type='announcement' AND source_id=$2 AND item_type='announcement_ack' AND status='pending'", [version.tenant_id, version.active_version_pid])).rows[0].count,
      { timeout: 20000 }).toBe(expected);
    await navigateToAnnouncementList(page);
    await expectRowStatus(page, draft.title, /已发布/);
    await page.screenshot({ path: test.info().outputPath('published-and-projected.png'), animations: 'disabled', fullPage: true });
    // Recipient path starts from its real menu; list GET must not manufacture reading facts.
    await page.goto('/home', { waitUntil: 'domcontentloaded' });
    const nav = page.locator('nav').first();
    const receiverLink = nav.locator('a[href="/p/announcement_reader"]');
    if (!(await receiverLink.isVisible())) await nav.getByText('公告管理', { exact: true }).click();
    const receivedList = page.waitForResponse(r => r.request().method() === 'GET' && r.url().includes('/api/announcements/received'));
    await receiverLink.click();const listResult = await receivedList;
    expect(listResult.ok()).toBe(true);
    const own = (await listResult.json()).data.records.find((r: any) => r.versionPid === version.active_version_pid);
    expect(own).toMatchObject({ title: draft.title, readState: 'unread', acknowledgementState: 'pending', first_read_at: null });
    const receiverRow = await findRowByContent(page, draft.title);
    await expect(receiverRow).toContainText('未读');
    await expect(receiverRow).toContainText(/\d{4}[-/]\d{2}[-/]\d{2} \d{2}:\d{2}/);
    const publishedCell=receiverRow.locator('td').nth(4);
    const geometry=await publishedCell.evaluate(cell=>[cell,...Array.from(cell.querySelectorAll('*'))]
      .filter(element=>element.clientWidth>0).map(element=>({visible:element.clientWidth,content:element.scrollWidth})));
    expect(geometry.length).toBeGreaterThan(0);
    for(const box of geometry)expect(box.content,'Published timestamp must be fully readable without ellipsis').toBeLessThanOrEqual(box.visible);
    await page.screenshot({ path: test.info().outputPath('recipient-unread-list.png'), animations: 'disabled', fullPage: true });
    const readRequest = page.waitForRequest(r => r.method() === 'POST' && r.url().endsWith(`/api/announcements/versions/${version.active_version_pid}/read`));
    await receiverRow.getByRole('button', { name: '查看公告', exact: true }).click();
    const request = await readRequest;
    await expect(page.getByTestId('form-field-content')).toContainText(`Audience policy for ${UID}`);
    expect(request.postDataJSON()).toEqual({ snapshotHash: own.snapshotHash });
    const acknowledgement = page.getByTestId('toolbar-btn-acknowledge');
    await expect(acknowledgement).toBeEnabled();
    const recipient = async () => (await db.query(`SELECT first_read_at,acknowledged_at,acknowledged_hash FROM ab_announcement_recipient
      WHERE tenant_id=$1 AND version_pid=$2 AND user_pid=(SELECT pid FROM ab_user WHERE email='admin@auraboot.com')`, [version.tenant_id, version.active_version_pid])).rows[0];
    const readFact = await recipient();expect(readFact.first_read_at).toBeTruthy();expect(readFact.acknowledged_at).toBeNull();
    await page.screenshot({ path: test.info().outputPath('recipient-body-read-awaiting-ack.png'), animations: 'disabled', fullPage: true });
    await page.getByTestId('inbox-badge').click();
    await page.getByTestId('inbox-mark-all-read').click();
    await expect(page.getByTestId('inbox-badge')).toHaveAttribute('title', /待处理/);
    await page.getByTestId('inbox-badge').click();
    expect((await recipient()).acknowledged_at).toBeNull();
    await acknowledgement.click();
    const confirm = page.getByTestId('confirm-dialog');await expect(confirm).toContainText('确认你已阅读并知悉本版本公告');
    await confirm.screenshot({ path: test.info().outputPath('acknowledgement-confirmation.png'), animations: 'disabled' });
    const ackResponse = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith(`/api/announcements/versions/${version.active_version_pid}/acknowledge`));
    await page.getByTestId('confirm-ok').click();const acknowledged = await ackResponse;
    expect(acknowledged.ok()).toBe(true);expect(String((await acknowledged.json()).code)).toBe('0');
    expect(acknowledged.request().postDataJSON()).toEqual({ snapshotHash: own.snapshotHash });
    await expect(acknowledgement).toHaveCount(0);
    await expect(page.getByText('你已确认知悉，首次确认时间会保留。', { exact: true })).toBeVisible();
    const ackFact = await recipient();expect(ackFact.acknowledged_at).toBeTruthy();expect(ackFact.acknowledged_hash).toBe(own.snapshotHash);
    expect(ackFact.first_read_at).toEqual(readFact.first_read_at);
    await page.reload();await expect(page.getByText('你已确认知悉，首次确认时间会保留。', { exact: true })).toBeVisible();
    expect((await recipient()).acknowledged_at).toEqual(ackFact.acknowledged_at);
    await page.screenshot({ path: test.info().outputPath('recipient-acknowledged-persisted.png'), animations: 'disabled', fullPage: true });
    // A recipient with a custom role and no announcement management grants follows the same real menu path.
    const memberContext = await browser.newContext({ baseURL: new URL(page.url()).origin, locale: 'zh-CN' });
    try {
      await authenticateBffSession(memberContext.request, new URL(page.url()).origin, recipientLogin.email, 'Test2026x');
      const memberPage = await memberContext.newPage();await memberPage.goto('/home', { waitUntil: 'domcontentloaded' });
      const memberNav = memberPage.locator('nav').first();const mine = memberNav.locator('a[href="/p/announcement_reader"]');
      if (!(await mine.isVisible())) await memberNav.getByText('公告管理', { exact: true }).click();
      await expect(memberNav.locator('a[href="/p/ab_announcement"]')).toHaveCount(0);
      await expect(memberNav.locator('a[href="/p/announcement_policy/view/current"]')).toHaveCount(0);
      expect((await memberPage.request.get('/api/announcements/policy')).status()).toBe(403);
      await mine.click();const memberRow = await findRowByContent(memberPage, draft.title);
      await expect(memberRow).toContainText('未读');
      const bellLoaded=memberPage.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname==='/api/notifications');
      await memberPage.getByTestId('notification-bell').click();const bellResponse=await bellLoaded;expect(bellResponse.status()).toBe(200);
      expect((await bellResponse.json()).data.records.some((item:any)=>item.sourceId===version.active_version_pid)).toBe(true);
      await expect(memberPage.getByTestId('notification-item').filter({hasText:draft.title})).toBeVisible();
      await memberPage.screenshot({path:test.info().outputPath('recipient-bell-version-link.png'),fullPage:true,animations:'disabled'});
      const memberRead = memberPage.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith(`/api/announcements/versions/${version.active_version_pid}/read`));
      await memberPage.getByTestId('notification-item').filter({hasText:draft.title}).click();
      expect((await memberRead).ok()).toBe(true);
      await expect(memberPage).toHaveURL(new RegExp(`/p/announcement_reader/view/${version.active_version_pid}$`));
      await expect(memberPage.getByTestId('form-field-content')).toContainText(`Audience policy for ${UID}`);
      await expect(memberPage.locator('main').getByRole('link',{name:'返回',exact:true})).toHaveCount(0);
      const returned=memberPage.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname==='/api/announcements/received');
      await memberPage.getByRole('button',{name:'返回我的公告',exact:true}).click();expect((await returned).status()).toBe(200);
      await expect(memberPage).toHaveURL(url=>url.pathname==='/p/announcement_reader');
      const bellAgain=memberPage.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname==='/api/notifications');
      await memberPage.getByTestId('notification-bell').click();expect((await bellAgain).status()).toBe(200);
      const bellMarked=memberPage.waitForResponse(r=>r.request().method()==='PUT'&&new URL(r.url()).pathname==='/api/notifications/read-all');
      await memberPage.getByTestId('mark-all-read-btn').click();expect((await bellMarked).status()).toBe(200);
      await memberPage.getByTestId('notification-bell').click();
      expect((await db.query('SELECT is_read FROM ab_notification WHERE tenant_id=$1 AND source_id=$2 AND user_id=(SELECT id FROM ab_user WHERE pid=$3)',[version.tenant_id,version.active_version_pid,recipientLogin.userPid])).rows).toEqual([{is_read:true}]);
      expect((await db.query('SELECT acknowledged_at FROM ab_announcement_recipient WHERE tenant_id=$1 AND version_pid=$2 AND user_pid=$3',[version.tenant_id,version.active_version_pid,recipientLogin.userPid])).rows).toEqual([{acknowledged_at:null}]);
      const inboxLoaded=memberPage.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname==='/api/inbox');
      await memberPage.getByTestId('inbox-badge').click();const inboxResponse=await inboxLoaded;expect(inboxResponse.status()).toBe(200);
      const pending=(await inboxResponse.json()).data.records.find((item:any)=>item.sourceId===version.active_version_pid);
      expect(pending).toBeTruthy();
      await expect(memberPage.getByTestId(`inbox-item-${pending.id}`)).toBeVisible();
      await memberPage.screenshot({path:test.info().outputPath('recipient-inbox-version-link.png'),fullPage:true,animations:'disabled'});
      const inboxDetail=memberPage.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname===`/api/announcements/versions/${version.active_version_pid}/recipient`);
      await memberPage.getByTestId(`inbox-item-${pending.id}`).click();const viaInbox=await inboxDetail;expect(viaInbox.status()).toBe(200);
      expect((await viaInbox.json()).data).toMatchObject({versionPid:version.active_version_pid,content:`Audience policy for ${UID}`,acknowledgementState:'pending'});
      await expect(memberPage).toHaveURL(new RegExp(`/p/announcement_reader/view/${version.active_version_pid}$`));
      await expect(memberPage.getByTestId('form-field-content')).toContainText(`Audience policy for ${UID}`);
      await memberPage.screenshot({ path: test.info().outputPath('nonmanager-recipient-body.png'), animations: 'disabled', fullPage: true });
      await memberPage.getByTestId('toolbar-btn-acknowledge').click();await expect(memberPage.getByTestId('confirm-dialog')).toBeVisible();
      const memberAck = memberPage.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith(`/api/announcements/versions/${version.active_version_pid}/acknowledge`));
      await memberPage.getByTestId('confirm-ok').click();expect((await memberAck).ok()).toBe(true);
      await expect(memberPage.getByText('你已确认知悉，首次确认时间会保留。', { exact: true })).toBeVisible();
      expect((await db.query('SELECT acknowledged_hash FROM ab_announcement_recipient WHERE tenant_id=$1 AND version_pid=$2 AND user_pid=$3', [version.tenant_id, version.active_version_pid, recipientLogin.userPid])).rows).toEqual([{ acknowledged_hash: own.snapshotHash }]);
      await memberPage.screenshot({ path: test.info().outputPath('nonmanager-recipient-acknowledged.png'), animations: 'disabled', fullPage: true });
      await memberPage.getByRole('button',{name:'返回我的公告',exact:true}).click();
      const completedRow=await findRowByContent(memberPage,draft.title);await expect(completedRow).toContainText('已读');await expect(completedRow).toContainText('已知悉');
      await memberPage.screenshot({path:test.info().outputPath('recipient-read-acknowledged-list.png'),fullPage:true,animations:'disabled'});
    } finally { await memberContext.close(); }


  } finally { await db.end(); }
});
