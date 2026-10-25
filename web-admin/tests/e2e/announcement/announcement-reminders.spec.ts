import { test, expect, type Page } from '../../fixtures';
import type { APIResponse } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { Client } from 'pg';
import { PG_CONN } from '../../helpers/environments';
import { authenticateBffSession } from '../../helpers/bff-auth-session';

test.use({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });

async function successful(response: Pick<APIResponse, 'status' | 'text' | 'json'>) {
  expect(response.status(), await response.text()).toBe(200);
  const body = await response.json();
  expect(body.code).toBe('0');
  return body.data;
}

async function command(page: Page, code: string, pid: string, version: number, payload = {}) {
  return (await successful(await page.request.post(`/api/meta/commands/execute/${code}`, {
    data: { clientRequestId: randomUUID(), targetRecordPid: pid, expectedVersion: version,
      operationType: 'UPDATE', payload },
  }))).data;
}

/** API calls establish an owned publication; reminder acceptance and status use the visible UI. */
async function publishedFixture(page: Page, db: Client) {
  const actor = (await successful(await page.request.get('/api/auth/me'))).user;
  expect(actor.pid).toEqual(expect.any(String));
  const policy = await successful(await page.request.get('/api/announcements/policy'));
  expect(policy.forceApproval, 'This fixture requires the explicitly configured exempt-publication policy').toBe(false);
  const title = `Reminder golden ${randomUUID()}`;
  const created = await successful(await page.request.post('/api/announcements', { data: {
    operationId: randomUUID(), title, content: 'An independently owned reminder fixture.', priority: 'important',
  } }));
  const saved = await command(page, 'announcement:update_announcement', created.pid, created.row_version, {
    audience_kind: 'selected', audience_users: [actor.pid], ack_required: true,
    approval_mode: 'exempt', exemption_reason: 'Authorized reminder acceptance fixture.',
  });
  const accepted = await command(page, 'announcement:publish', created.pid, saved.row_version);
  await expect.poll(async () => (await db.query(
    'SELECT publication_state FROM ab_announcement_version_control WHERE tenant_id=$1 AND version_pid=$2',
    [actor.tenantId, accepted.versionPid],
  )).rows[0]?.publication_state, { timeout: 20_000 }).toBe('published');
  const recipient = await successful(await page.request.get(`/api/announcements/versions/${accepted.versionPid}/recipient`));
  await successful(await page.request.post(`/api/announcements/versions/${accepted.versionPid}/read`, {
    data: { snapshotHash: recipient.snapshotHash },
  }));
  await expect.poll(async () => (await db.query(
    "SELECT status FROM ab_announcement_delivery WHERE tenant_id=$1 AND version_pid=$2 AND user_pid=$3 AND kind='publication' AND channel='in_app'",
    [actor.tenantId, accepted.versionPid, actor.pid],
  )).rows[0]?.status, { timeout: 20_000 }).toBe('completed');
  const actorName = (await db.query(`SELECT COALESCE(NULLIF(nick_name,''),user_name) AS name FROM ab_user WHERE pid=$1`, [actor.pid])).rows[0]?.name;
  expect(actorName).toEqual(expect.any(String));
  return { actorName: actorName as string, pid: created.pid as string, version: accepted.versionPid as string, title, tenant: actor.tenantId };
}

async function enterFromMenu(page: Page, title: string) {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav');
  await expect(nav).toHaveCount(1);
  const leaf = nav.locator('a[href="/p/ab_announcement"]');
  if (!await leaf.isVisible()) await nav.getByText('公告管理', { exact: true }).click();
  await leaf.click();
  await page.getByTestId('filters-toggle').click();
  await page.getByTestId('search-area').getByPlaceholder('例如：周末系统维护', { exact: true }).fill(title);
  await page.getByTestId('filter-search').click();
  const row = page.locator('table tbody tr').filter({ hasText: title });
  await expect(row).toHaveCount(1);
  await row.getByRole('button', { name: '查看', exact: true }).click();
}


test('native reminder preview confirms, replays a lost response, and shows persisted batch and plan facts', async ({ page }, info) => {
  test.setTimeout(60_000);
  const expectedDatabase = process.env.PGDATABASE || process.env.PG_DB;
  expect(expectedDatabase, 'An explicit owned test database is required').toMatch(/^[a-z][a-z0-9_]*$/);
  const db = new Client(PG_CONN); await db.connect();
  expect((await db.query('SELECT current_database() AS name')).rows[0].name).toBe(expectedDatabase);
  let fixture: Awaited<ReturnType<typeof publishedFixture>> | undefined;
  const screenshot = (name: string) => page.screenshot({ path: info.outputPath(`${name}.png`), fullPage: true, animations: 'disabled' });
  const count = async () => Number((await db.query("SELECT count(*) AS count FROM ab_announcement_reminder_batch WHERE tenant_id=$1 AND version_pid=$2 AND mode='MANUAL'", [fixture!.tenant, fixture!.version])).rows[0].count);
  try {
    fixture = await publishedFixture(page, db); await enterFromMenu(page, fixture.title);
    const captured = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/reminders/preview'));
    await page.getByRole('button', { name: '预览催办', exact: true }).click();
    const preview = await successful(await captured); expect(preview.eligible).toBe(1);
    await expect(page).toHaveURL(new RegExp(`/p/announcement_reminder_preview/view/${preview.previewToken}$`));
    await expect(page.getByRole('heading', { name: fixture.title, exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '当前可催办', exact: true })).toBeVisible();
    await expect(page.getByText('Page Unavailable', { exact: true })).toHaveCount(0);
    await screenshot('reminder-preview-ready');
    await page.getByRole('button', { name: '确认催办', exact: true }).click();
    const dialog = page.getByTestId('confirm-dialog'); await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('提交时会重新检查'); await screenshot('reminder-confirm-open');
    await page.getByTestId('confirm-cancel').click(); await expect(dialog).toBeHidden();
    expect(await count()).toBe(0);
    const endpoint = '**/api/meta/commands/execute/announcement:remind';
    let accepted: Record<string, any> | undefined; let requestId: string | undefined;
    await page.route(endpoint, async route => {
      const wire = route.request().postDataJSON(); requestId = wire.clientRequestId;
      expect(wire.targetRecordPid).toBe(fixture!.pid); expect(wire.payload).toEqual({ previewToken: preview.previewToken });
      const result = await route.fetch(); expect(result.status()).toBe(200);
      accepted = (await result.json()).data.data;
      expect(accepted?.actualCount).toBe(1); await route.abort('failed');
    }, { times: 1 });
    await page.getByRole('button', { name: '确认催办', exact: true }).click(); await expect(dialog).toBeVisible();
    await page.getByTestId('confirm-ok').click();
    await expect(page.getByTestId('ab:detail:ab_announcement:action-error')).toContainText('网络连接中断，暂时无法确认操作结果。');
    expect(await count()).toBe(1); await screenshot('reminder-uncertain-response');
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('button', { name: '确认催办', exact: true }).click(); await expect(dialog).toBeVisible();
    const replayed = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/commands/execute/announcement:remind'));
    await page.getByTestId('confirm-ok').click(); const response = await replayed;
    expect(response.request().postDataJSON().clientRequestId).toBe(requestId);
    const replay = await successful(response); expect(replay.idempotentReplay).toBe(true);
    expect(replay.data.batchPid).toBe(accepted?.batchPid); expect(await count()).toBe(1);
    await expect(page).toHaveURL(new RegExp(`/p/announcement_reminder_batch/view/${accepted!.batchPid}$`));
    await expect.poll(async () => (await db.query("SELECT status FROM ab_announcement_delivery WHERE tenant_id=$1 AND batch_pid=$2 AND kind='reminder'", [fixture!.tenant, accepted!.batchPid])).rows[0]?.status, { timeout: 20_000 }).toBe('completed');
    await page.getByRole('button', { name: '刷新状态', exact: true }).click();
    const table = page.getByTestId('table-block');
    await expect(table.getByRole('columnheader', { name: '提醒已生成', exact: true })).toBeVisible();
    await expect(table.getByRole('row').nth(1).getByRole('cell').nth(2)).toHaveText('1');
    await expect(page.getByRole('button', { name: '刷新状态', exact: true })).toBeEnabled();
    await screenshot('reminder-batch-completed'); await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(table.getByRole('row').nth(1).getByRole('cell').nth(2)).toHaveText('1');
    expect(await count()).toBe(1); await screenshot('reminder-batch-reloaded');
    // Additional explicit zero-intention operations are preconditions for actual history paging.
    const emptyPreview = await successful(await page.request.post(`/api/announcements/records/${fixture.pid}/versions/${fixture.version}/reminders/preview`, {
      data: { query: '', state: 'all', departmentPid: null },
    }));
    expect(emptyPreview.eligible).toBe(0);
    for (let index = 0; index < 25; index++) {
      const extra = await successful(await page.request.post('/api/meta/commands/execute/announcement:remind', {
        data: { clientRequestId: randomUUID(), targetRecordPid: fixture.pid, payload: { previewToken: emptyPreview.previewToken } },
      }));
      expect(extra.data.actualCount).toBe(0); expect(extra.data.operationState).toBe('skipped');
    }
    expect(await count()).toBe(26);
    await page.getByRole('button', { name: '返回公告', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/p/ab_announcement/view/${fixture.pid}$`));
    await expect(page.getByText(fixture.title, { exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: fixture.actorName, exact: true })).toBeVisible();
    await screenshot('reminder-management-return');
    await page.getByRole('button', { name: '自动催办计划', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/p/announcement_reminder_plan/view/${fixture.version}$`));
    await expect(page.getByRole('button', { name: '恢复自动催办', exact: true })).toHaveCount(0);
    await expect(page.getByRole('columnheader', { name: '额度上限', exact: true })).toBeVisible();
    const planFacts = page.getByTestId('table-block').filter({ has: page.getByRole('columnheader', { name: '额度上限', exact: true }) });
    await expect(planFacts.getByRole('row').nth(1).getByRole('cell').nth(1)).toHaveText('3');
    await expect(page.getByRole('button', { name: '返回公告', exact: true })).toBeEnabled();
    await screenshot('reminder-plan-active');
    const history = page.getByTestId('table-block').filter({ has: page.getByRole('columnheader', { name: '实际受理人数', exact: true }) });
    await history.scrollIntoViewIfNeeded();
    await expect(history.getByRole('row')).toHaveCount(21);
    await expect(page.getByTestId('table-server-pagination')).toContainText('共 26 条');
    await history.screenshot({ path: info.outputPath('reminder-history-page-one.png'), animations: 'disabled' });
    await page.getByTestId('table-server-pagination').screenshot({ path: info.outputPath('reminder-history-page-one-pagination.png'), animations: 'disabled' });
    const secondPage = page.waitForResponse(r => r.request().method() === 'GET' && new URL(r.url()).pathname.endsWith('/reminders/batches') && new URL(r.url()).searchParams.get('pageNum') === '2');
    await page.getByTestId('table-server-pagination').getByRole('button', { name: '下一页', exact: true }).click();
    const historyData = await successful(await secondPage);
    expect(historyData).toMatchObject({ pageNum: 2, pageSize: 20, total: 26 }); expect(historyData.records).toHaveLength(6);
    await expect(history.getByRole('row')).toHaveCount(7); await screenshot('reminder-history-page-two');
    const acceptedRow = history.getByRole('row').filter({ has: page.getByRole('cell', { name: '1', exact: true }) });
    await expect(acceptedRow).toHaveCount(1);
    await acceptedRow.getByRole('button', { name: '查看批次', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/p/announcement_reminder_batch/view/${accepted!.batchPid}$`));
    await expect(page.getByTestId('table-block').getByRole('row').nth(1).getByRole('cell').nth(2)).toHaveText('1');
    await screenshot('reminder-history-exact-batch');
    const intentions = (await db.query("SELECT count(*) AS count FROM ab_announcement_delivery WHERE tenant_id=$1 AND version_pid=$2 AND kind='reminder'", [fixture.tenant, fixture.version])).rows[0].count;
    expect(Number(intentions)).toBe(1);
    writeFileSync(info.outputPath('reminder-validation.json'), JSON.stringify({ originalReplayUsesSameBatch: true,
      originalReplayCreatesOneIntention: Number(intentions) === 1, explicitSkippedOperations: 25, batchCount: await count(), historyPages: 2 }, null, 2));
    await page.getByRole('button', { name: '返回公告', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/p/ab_announcement/view/${fixture.pid}$`));
    await expect(page.getByText(fixture.title, { exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: fixture.actorName, exact: true })).toBeVisible();
    await page.getByRole('button', { name: '预览催办', exact: true }).click();
    await expect(page.getByRole('button', { name: '确认催办', exact: true })).toHaveCount(0);
    await expect(page.getByTestId('table-block').getByRole('row').nth(1).getByRole('cell').nth(2)).toHaveText('1');
    await expect(page.getByText('去重窗口内，暂无新增提醒', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '重新预览', exact: true })).toBeEnabled();
    await screenshot('reminder-preview-throttled');
    await page.getByRole('button', { name: '返回公告', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/p/ab_announcement/view/${fixture.pid}$`));
    await expect(page.getByText(fixture.title, { exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: fixture.actorName, exact: true })).toBeVisible();
  } finally {
    try {
      if (fixture) {
        const record = (await db.query('SELECT row_version,status FROM ab_announcement WHERE tenant_id=$1 AND pid=$2', [fixture.tenant, fixture.pid])).rows[0];
        if (record?.status === 'published') await command(page, 'announcement:archive', fixture.pid, record.row_version);
      }
    } finally { await db.end(); }
  }
});

test('published pinning persists through the native detail without changing its frozen version or notifications', async ({ page }, info) => {
  test.setTimeout(45_000);
  const db = new Client(PG_CONN); await db.connect();
  expect((await db.query('SELECT current_database() AS name')).rows[0].name).toBe(process.env.PGDATABASE || process.env.PG_DB);
  let fixture: Awaited<ReturnType<typeof publishedFixture>> | undefined;
  try {
    fixture = await publishedFixture(page, db);
    const before = (await db.query('SELECT row_version,editor_revision FROM ab_announcement WHERE tenant_id=$1 AND pid=$2', [fixture.tenant, fixture.pid])).rows[0];
    const frozen = (await db.query('SELECT snapshot_hash,snapshot FROM ab_announcement_version WHERE tenant_id=$1 AND pid=$2', [fixture.tenant, fixture.version])).rows[0];
    const count = async () => Number((await db.query('SELECT count(*) AS count FROM ab_announcement_delivery WHERE tenant_id=$1 AND version_pid=$2', [fixture!.tenant, fixture!.version])).rows[0].count);
    const originalCount = await count();
    await enterFromMenu(page, fixture.title);
    await page.getByRole('button', { name: '置顶', exact: true }).click();
    await expect(page.getByTestId('confirm-dialog')).toContainText('不会重新通知读者');
    await page.screenshot({ path: info.outputPath('announcement-pin-confirm.png'), fullPage: true, animations: 'disabled' });
    await page.getByTestId('confirm-cancel').click();
    expect((await db.query('SELECT row_version,pinned FROM ab_announcement WHERE tenant_id=$1 AND pid=$2', [fixture.tenant, fixture.pid])).rows[0]).toEqual({ row_version: before.row_version, pinned: false });
    await page.getByRole('button', { name: '置顶', exact: true }).click();
    const pinned = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/commands/execute/announcement:set_pinned'));
    await page.getByTestId('confirm-ok').click();
    const response = await pinned;
    expect(response.request().postDataJSON()).toMatchObject({ targetRecordPid: fixture.pid, expectedVersion: before.row_version, payload: { pinned: true } });
    expect(Object.keys(response.request().postDataJSON().payload)).toEqual(['pinned']);
    await successful(response);
    await expect(page.getByRole('button', { name: '取消置顶', exact: true })).toBeVisible();
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('button', { name: '取消置顶', exact: true })).toBeVisible();
    await page.screenshot({ path: info.outputPath('announcement-pinned.png'), fullPage: true, animations: 'disabled' });
    expect((await successful(await page.request.get(`/api/announcements/versions/${fixture.version}/recipient`))).pinned).toBe(true);
    await page.getByRole('button', { name: '取消置顶', exact: true }).click();
    const unpinned = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/commands/execute/announcement:set_pinned'));
    await page.getByTestId('confirm-ok').click(); await successful(await unpinned);
    await expect(page.getByRole('button', { name: '置顶', exact: true })).toBeVisible();
    expect((await db.query('SELECT snapshot_hash,snapshot FROM ab_announcement_version WHERE tenant_id=$1 AND pid=$2', [fixture.tenant, fixture.version])).rows[0]).toEqual(frozen);
    expect((await db.query('SELECT row_version,editor_revision,pinned FROM ab_announcement WHERE tenant_id=$1 AND pid=$2', [fixture.tenant, fixture.pid])).rows[0]).toEqual({ row_version: before.row_version + 2, editor_revision: before.editor_revision, pinned: false });
    expect(await count()).toBe(originalCount);
    expect(Number((await db.query("SELECT count(*) AS count FROM ab_announcement_audit WHERE tenant_id=$1 AND announcement_pid=$2 AND command_code='announcement:set_pinned'", [fixture.tenant, fixture.pid])).rows[0].count)).toBe(2);
  } finally {
    try {
      if (fixture) {
        const record = (await db.query('SELECT row_version,status FROM ab_announcement WHERE tenant_id=$1 AND pid=$2', [fixture.tenant, fixture.pid])).rows[0];
        if (record?.status === 'published') await command(page, 'announcement:archive', fixture.pid, record.row_version);
      }
    } finally { await db.end(); }
  }
});

test('a recovered original member resumes its original publication and automatic reminder plan through native actions', async ({ page, browser, baseURL }, info) => {
  test.setTimeout(180_000);
  expect(baseURL).toBeTruthy();
  const db = new Client(PG_CONN);await db.connect();
  try {
  expect((await db.query('SELECT current_database() AS name')).rows[0].name).toBe(process.env.PGDATABASE || process.env.PG_DB);
  const code = `notice_recover_${randomUUID().replaceAll('-', '').slice(0, 10)}`;
  const role = await successful(await page.request.post('/api/roles', { data: {
    code, name: 'Publication recovery publisher', type: 'custom', status: 'active', scopeType: 'tenant', defaultDataScopeType: 'self',
  } }));
  const codes = ['announcement.announcement.edit', 'announcement.announcement.publish', 'announcement.announcement.exempt',
    'announcement.announcement.read', 'announcement.announcement.withdraw', 'model.ab_announcement.read', 'meta.command.execute'];
  const permissions = (await db.query('SELECT p.id,p.code FROM ab_permission p JOIN ab_role r ON r.tenant_id=p.tenant_id WHERE r.pid=$1 AND p.code=ANY($2::text[]) AND p.deleted_flag=FALSE', [role.pid, codes])).rows;
  expect(permissions.map(p => p.code).sort()).toEqual([...codes].sort());
  await successful(await page.request.put(`/api/permissions/matrix/${role.pid}/batch`, { data: permissions.map(p => ({ permissionId: p.id, granted: true })) }));
  for (const actionCode of ['read', 'publish']) {
    await successful(await page.request.put(`/api/permissions/matrix/${role.pid}/scope`, {
      data: { resourceCode: 'ab_announcement', actionCode, scopeType: 'self', mergeStrategy: 'MAX' },
    }));
  }
  const email = `${code}@example.com`;
  const user = await successful(await page.request.post('/api/admin/users', { data: {
    email, displayName: 'Publication recovery publisher', initialPassword: 'Test2026x', roleCodes: [code], sendInviteEmail: false,
  } }));
  expect(user.memberPid).toEqual(expect.any(String));
  const context = await browser.newContext({ baseURL, locale: 'zh-CN', viewport: { width: 1440, height: 1000 }, storageState: { cookies: [], origins: [] } });
  const publisher = await context.newPage();
  const title = `Publication recovery ${randomUUID()}`;
  let recordPid: string | undefined;let versionPid: string | undefined;let suspended = false;
  const status = async (action: 'active' | 'suspended') => successful(await page.request.put(`/api/tenant/members/${user.memberPid}/status`, {
    data: { action, reason: 'Owned publication recovery verification.' },
  }));
  const query = async () => (await db.query('SELECT a.row_version,a.status,r.pid AS release_pid,r.fencing_token,r.actor_member_pid,r.attempts,r.status AS release_status FROM ab_announcement a JOIN ab_announcement_release r ON r.tenant_id=a.tenant_id AND r.version_pid=a.current_version_pid WHERE a.pid=$1', [recordPid])).rows[0];
  try {
    await authenticateBffSession(context.request, baseURL!, email, 'Test2026x');
    const created = await successful(await publisher.request.post('/api/announcements', { data: {
      operationId: randomUUID(), title, content: 'Recover the original member and release, keeping all frozen business facts.',
    } }));
    recordPid = created.pid;
    const saved = await command(publisher, 'announcement:update_announcement', recordPid!, created.row_version, {
      audience_kind: 'selected', audience_users: [user.userPid], approval_mode: 'exempt',
      exemption_reason: 'Authorized original-member publication recovery fixture.', scheduled_at: new Date(Date.now() + 20_000).toISOString(),
    });
    const preview = await successful(await publisher.request.post('/api/announcements/audience-preview', { data: { recordPid } }));
    expect(preview.scopeType).toBe('self');expect(preview.recipients.map((recipient: any) => recipient.userPid)).toEqual([user.userPid]);
    const accepted = await command(publisher, 'announcement:publish', recordPid!, saved.row_version);
    versionPid = accepted.versionPid;const original = await query();
    expect(original.actor_member_pid).toBe(user.memberPid);
    await status('suspended');suspended = true;
    await expect.poll(async () => (await query()).release_status, { timeout: 30_000 }).toBe('blocked');
    const blocked = await query();
    expect(Number((await db.query('SELECT count(*) AS count FROM ab_announcement_recipient WHERE version_pid=$1', [versionPid])).rows[0].count)).toBe(0);
    await status('active');suspended = false;
    await authenticateBffSession(context.request, baseURL!, email, 'Test2026x');
    await enterFromMenu(publisher, title);
    const execution = publisher.getByTestId('table-block').filter({ has: publisher.getByRole('columnheader', { name: '任务状态', exact: true }) });
    await expect(execution.getByRole('cell', { name: '发布阻断', exact: true })).toBeVisible();
    await expect(execution.getByRole('cell', { name: '原发布者暂不可用', exact: true })).toBeVisible();
    await publisher.screenshot({ path: info.outputPath('publication-blocked.png'), fullPage: true, animations: 'disabled' });
    await execution.getByRole('button', { name: '恢复发布任务', exact: true }).click();
    await expect(publisher.getByTestId('confirm-dialog')).toContainText('沿用原版本、操作者和时间窗口');
    await publisher.screenshot({ path: info.outputPath('publication-retry-confirm.png'), fullPage: true, animations: 'disabled' });
    await publisher.getByTestId('confirm-cancel').click();
    expect(await query()).toEqual(blocked);
    await execution.getByRole('button', { name: '恢复发布任务', exact: true }).click();
    const retry = publisher.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/commands/execute/announcement:retry_publication'));
    await publisher.getByTestId('confirm-ok').click();const response = await retry;
    expect(response.request().postDataJSON()).toMatchObject({ targetRecordPid: recordPid, expectedVersion: blocked.row_version, payload: {} });
    expect(response.request().postDataJSON().clientRequestId).toMatch(/^[a-f0-9-]{36}$/);
    const result = await successful(response);expect(result.data.releaseAttemptPid).toBe(original.release_pid);
    await expect.poll(async () => (await query()).release_status, { timeout: 20_000 }).toBe('succeeded');
    await publisher.getByRole('button', { name: '刷新发布状态', exact: true }).click();
    await expect(execution.getByRole('cell', { name: '发布完成', exact: true })).toBeVisible();
    await expect(execution.getByRole('button', { name: '恢复发布任务', exact: true })).toHaveCount(0);
    await publisher.reload({ waitUntil: 'domcontentloaded' });
    await expect(execution.getByRole('cell', { name: '发布完成', exact: true })).toBeVisible();
    const finished = await query();expect(finished.release_pid).toBe(original.release_pid);expect(finished.actor_member_pid).toBe(user.memberPid);
    expect(finished.attempts).toBe(2);expect(Number(finished.fencing_token)).toBeGreaterThan(Number(blocked.fencing_token));
    expect(Number((await db.query("SELECT count(*) AS count FROM ab_announcement_audit WHERE announcement_pid=$1 AND command_code='announcement:publication_completed'", [recordPid])).rows[0].count)).toBe(1);
    expect(Number((await db.query('SELECT count(*) AS count FROM ab_announcement_recipient WHERE version_pid=$1', [versionPid])).rows[0].count)).toBe(1);
    await expect.poll(async () => (await db.query("SELECT status FROM ab_announcement_delivery WHERE version_pid=$1 AND kind='publication' AND channel='in_app'", [versionPid])).rows[0]?.status, { timeout: 20_000 }).toBe('completed');
    expect(Number((await db.query("SELECT count(*) AS count FROM ab_announcement_delivery WHERE version_pid=$1 AND kind='publication' AND channel='in_app'", [versionPid])).rows[0].count)).toBe(1);
    await publisher.screenshot({ path: info.outputPath('publication-recovered.png'), fullPage: true, animations: 'disabled' });
    // Publication authority does not imply reminder authority. The real scanner blocks this original actor.
    const plan = async () => (await db.query('SELECT status,error_code,automatic_batches,resume_after FROM ab_announcement_reminder_plan WHERE version_pid=$1', [versionPid])).rows[0];
    await expect.poll(async () => (await plan())?.status, {timeout: 75_000}).toBe('blocked');
    const originalPlan = await plan();expect(originalPlan.error_code).toBe('ACTOR_OR_VERSION_UNAVAILABLE');
    const reminderPermission = (await db.query('SELECT p.id FROM ab_permission p JOIN ab_role r ON r.tenant_id=p.tenant_id WHERE r.pid=$1 AND p.code=$2 AND p.deleted_flag=FALSE', [role.pid, 'announcement.announcement.remind'])).rows;
    expect(reminderPermission).toHaveLength(1);
    await successful(await page.request.put(`/api/permissions/matrix/${role.pid}/batch`, {data: [{permissionId: reminderPermission[0].id, granted: true}]}));
    await successful(await page.request.put(`/api/permissions/matrix/${role.pid}/scope`, {data: {resourceCode: 'ab_announcement', actionCode: 'remind', scopeType: 'self', mergeStrategy: 'MAX'}}));
    await authenticateBffSession(context.request, baseURL!, email, 'Test2026x');
    await enterFromMenu(publisher, title);
    await publisher.getByRole('button', {name: '\u81ea\u52a8\u50ac\u529e\u8ba1\u5212', exact: true}).click();
    await expect(publisher).toHaveURL(new RegExp(`/p/announcement_reminder_plan/view/${versionPid}$`));
    const resumePlan = publisher.getByRole('button', {name: '\u6062\u590d\u81ea\u52a8\u50ac\u529e', exact: true});
    await expect(resumePlan).toBeVisible();
    await publisher.screenshot({path: info.outputPath('reminder-original-plan-blocked.png'), fullPage: true, animations: 'disabled'});
    await resumePlan.click();await expect(publisher.getByTestId('confirm-dialog')).toBeVisible();
    await publisher.getByTestId('confirm-cancel').click();expect(await plan()).toEqual(originalPlan);
    await resumePlan.click();
    await publisher.screenshot({path: info.outputPath('reminder-original-plan-resume-confirm.png'), fullPage: true, animations: 'disabled'});
    const resumingPlan = publisher.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/meta/commands/execute/announcement:resume_reminders');
    await publisher.getByTestId('confirm-ok').click();const resumedPlanResponse = await resumingPlan;
    expect(resumedPlanResponse.request().postDataJSON()).toMatchObject({targetRecordPid: recordPid, payload: {versionPid}});
    const resumedPlanResult = await successful(resumedPlanResponse);
    expect(resumedPlanResult.data).toMatchObject({operationState: 'resumed', actorMemberPid: user.memberPid});
    await expect.poll(async () => (await plan()).status).toBe('active');
    await publisher.reload({waitUntil: 'domcontentloaded'});await expect(resumePlan).toHaveCount(0);
    expect((await plan()).automatic_batches).toBe(0);
    expect(Number((await db.query('SELECT count(*) AS count FROM ab_announcement_reminder_batch WHERE version_pid=$1', [versionPid])).rows[0].count)).toBe(0);
    expect((await query()).actor_member_pid).toBe(user.memberPid);
    await publisher.screenshot({path: info.outputPath('reminder-original-plan-resumed.png'), fullPage: true, animations: 'disabled'});

  } finally {
    try {
      if (suspended) { await status('active');await authenticateBffSession(context.request, baseURL!, email, 'Test2026x'); }
      if (recordPid) {
        const record = (await db.query('SELECT row_version,status FROM ab_announcement WHERE pid=$1', [recordPid])).rows[0];
        if (record?.status === 'published') await command(publisher, 'announcement:archive', recordPid, record.row_version);
        else if (['unpublished', 'scheduled'].includes(record?.status)) await command(publisher, 'announcement:cancel_schedule', recordPid, record.row_version);
      }
    } finally {await context.close();}
  }
  } finally {await db.end();}
});
