import { test, expect, type Page } from '../../fixtures';
import type { APIResponse } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { Client } from 'pg';
import { PG_CONN } from '../../helpers/environments';

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
    await screenshot('reminder-history-page-one');
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
