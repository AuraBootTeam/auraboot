import { test, expect, type Page } from '../../fixtures';
import type { APIResponse } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
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

/** API calls establish one owned precondition; all statistics/export actions use the visible UI. */
async function publishedFixture(page: Page, db: Client) {
  const actor = (await successful(await page.request.get('/api/auth/me'))).user;
  expect(actor.pid).toEqual(expect.any(String));
  const policy = await successful(await page.request.get('/api/announcements/policy'));
  expect(policy.forceApproval, 'This fixture requires the explicitly configured exempt-publication policy').toBe(false);
  const title = `Statistics golden ${randomUUID()}`;
  const created = await successful(await page.request.post('/api/announcements', { data: {
    operationId: randomUUID(), title, content: 'A fixed-time statistics fixture.', priority: 'important',
  } }));
  const saved = await command(page, 'announcement:update_announcement', created.pid, created.row_version, {
    audience_kind: 'selected', audience_users: [actor.pid], ack_required: true,
    approval_mode: 'exempt', exemption_reason: 'Authorized statistics acceptance fixture.',
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

test('fixed statistics and a browser CSV export survive an uncertain response and reload', async ({ page }, info) => {
  test.setTimeout(60_000);
  const expectedDatabase = process.env.PGDATABASE || process.env.PG_DB;
  expect(expectedDatabase, 'An explicit owned test database is required').toMatch(/^[a-z][a-z0-9_]*$/);
  const db = new Client(PG_CONN);
  await db.connect();
  expect((await db.query('SELECT current_database() AS name')).rows[0].name).toBe(expectedDatabase);
  let fixture: Awaited<ReturnType<typeof publishedFixture>> | undefined;
  const screenshot = (name: string) => page.screenshot({ path: info.outputPath(`${name}.png`), fullPage: true, animations: 'disabled' });
  const response = (suffix: string, method = 'POST') => page.waitForResponse(r =>
    new URL(r.url()).pathname.endsWith(suffix) && r.request().method() === method);
  try {
    fixture = await publishedFixture(page, db);
    await enterFromMenu(page, fixture.title);
    const captured = response('/statistics/snapshots');
    await page.getByRole('button', { name: '接收统计', exact: true }).click();
    const original = await successful(await captured);
    expect(original.summary).toMatchObject({ actualRecipients: 1, read: 1, acknowledged: 0, filteredRows: 1 });
    await expect(page).toHaveURL(new RegExp(`/p/announcement_statistics/view/${original.snapshotToken}$`));
    await expect(page.getByRole('heading', { name: fixture.title, exact: true })).toBeVisible();
    await expect(page.getByTestId('metric-strip-item-actualRecipients')).toContainText('1');
    await expect(page.getByTestId('table-server-pagination')).toBeVisible();
    await expect(page.getByText('Page Unavailable', { exact: true })).toHaveCount(0);
    await screenshot('statistics-overview');
    await page.getByRole('button', { name: '核对导出请求', exact: true }).click();
    await expect(page.getByText('当前没有待核对的导出请求。', { exact: true })).toBeVisible();
    const table = page.getByTestId('table-block');
    await table.scrollIntoViewIfNeeded();
    await expect(table.getByText('无可显示部门', { exact: true })).toHaveCount(1);
    await expect(table.getByText('无可显示角色', { exact: true })).toHaveCount(1);
    const lines = await table.getByRole('row').nth(1).getByRole('cell').nth(4).evaluate(cell => {
      const walker = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT); const counts: number[] = [];
      while (walker.nextNode()) {
        const node = walker.currentNode;
        if (!/\d{4}[-/]\d{2}[-/]\d{2}/.test(node.textContent || '')) continue;
        const range = document.createRange(); range.selectNodeContents(node); counts.push(range.getClientRects().length);
      }
      return counts;
    });
    expect(lines.length).toBeGreaterThan(0); for (const count of lines) expect(count).toBe(1);
    await table.screenshot({ path: info.outputPath('statistics-recipients-left.png'), animations: 'disabled' });
    await table.evaluate(element => { element.scrollLeft = element.scrollWidth; });
    await expect(table.getByRole('columnheader', { name: '成员资格', exact: true })).toBeInViewport();
    await expect(table.getByRole('cell', { name: '有效', exact: true })).toHaveCount(1);
    await expect(table.getByRole('cell', { name: '提醒已生成', exact: true })).toHaveCount(1);
    await screenshot('statistics-recipients-right');

    await page.getByRole('button', { name: '筛选明细', exact: true }).click();
    let dialog = page.getByRole('dialog');
    await dialog.getByTestId('form-dialog-field-query').fill('No_matching_recipient_7d3d');
    await screenshot('statistics-filter-open');
    const filtered = response('/statistics/snapshots');
    await dialog.getByTestId('form-dialog-submit').click();
    const empty = await successful(await filtered);
    expect(empty.summary).toMatchObject({ actualRecipients: 1, filteredRows: 0 });
    await expect(page.getByTestId('metric-strip-item-actualRecipients')).toContainText('1');
    await expect(page.getByText('没有匹配的接收人', { exact: true })).toBeVisible();
    await page.getByText('没有匹配的接收人', { exact: true }).scrollIntoViewIfNeeded();
    await screenshot('statistics-filtered-empty');
    await page.getByRole('button', { name: '筛选明细', exact: true }).click();
    dialog = page.getByRole('dialog');
    await dialog.getByTestId('form-dialog-field-query').fill('');
    const cleared = response('/statistics/snapshots');
    await dialog.getByTestId('form-dialog-submit').click();
    const clear = await successful(await cleared);
    expect(clear.summary.filteredRows).toBe(1);
    await expect(page.getByTestId('table-server-pagination')).toBeVisible();

    let firstWire: any, firstAccepted: any, dropped = false;
    let firstDone!: () => void, firstFailed!: (error: unknown) => void;
    const upstreamAccepted = new Promise<void>((resolve, reject) => { firstDone = resolve; firstFailed = reject; });
    await page.route('**/api/meta/commands/execute/announcement:export_statistics', async route => {
      try {
        const upstream = await route.fetch();
        if (!dropped) {
          dropped = true; firstWire = route.request().postDataJSON(); firstAccepted = await upstream.json();
          expect(upstream.status()).toBe(200); expect(firstAccepted.code).toBe('0');
          await route.abort('failed'); firstDone();
        } else await route.fulfill({ response: upstream });
      } catch (error) { firstFailed(error); throw error; }
    });
    await page.getByRole('button', { name: '导出当前明细', exact: true }).click();
    await upstreamAccepted;
    const actionError = page.getByTestId('ab:detail:ab_announcement:action-error');
    await expect(actionError).toContainText('网络连接中断，暂时无法确认操作结果。');
    await expect(actionError).not.toContainText('Failed to fetch');
    await actionError.scrollIntoViewIfNeeded(); await screenshot('export-uncertain-network');
    const checked=response(`/operations/${firstWire.clientRequestId}`, 'GET');
    await page.getByRole('button', { name: '核对导出请求', exact: true }).click();
    const check=await checked;
    expect(new URL(check.url()).searchParams.get('commandCode')).toBe('announcement:export_statistics');
    expect(await successful(check)).toMatchObject({found:true,operationState:'accepted',exportTaskPid:firstAccepted.data.data.exportTaskPid});
    await expect(page).toHaveURL(new RegExp(`/p/announcement_statistics_export/view/${firstAccepted.data.data.exportTaskPid}$`));
    expect((await db.query("SELECT count(*)::int AS count FROM ab_export_task WHERE tenant_id=$1 AND request_params->'prepared'->>'reference'=$2",[fixture.tenant,clear.snapshotToken])).rows[0].count).toBe(1);
    await page.goBack({waitUntil:'domcontentloaded'});
    await expect(page).toHaveURL(new RegExp(`/p/announcement_statistics/view/${clear.snapshotToken}$`));
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('metric-strip-item-filteredRows')).toContainText('1');
    const accepted = response('/api/meta/commands/execute/announcement:export_statistics');
    await page.getByRole('button', { name: '导出当前明细', exact: true }).click();
    const acceptance = await accepted; const acceptedBody = await successful(acceptance);
    expect(acceptance.request().postDataJSON()).toEqual(firstWire);
    expect(firstWire).toMatchObject({ targetRecordPid: fixture.pid, payload: { snapshotToken: clear.snapshotToken } });
    expect(acceptedBody.idempotentReplay).toBe(true);
    expect(acceptedBody.data.exportTaskPid).toBe(firstAccepted.data.data.exportTaskPid);
    const job = acceptedBody.data.exportTaskPid;
    await expect(page).toHaveURL(new RegExp(`/p/announcement_statistics_export/view/${job}$`));
    await expect(page.getByText('文件与任务', { exact: true })).toBeVisible();
    await expect.poll(async () => {
      const status = response(`/api/exports/domain/${job}`, 'GET');
      await page.getByRole('button', { name: '刷新进度', exact: true }).click();
      return (await successful(await status)).status;
    }, { timeout: 20_000, intervals: [250, 500, 1000] }).toBe('completed');
    const downloadButton = page.getByRole('button', { name: '下载 CSV', exact: true });
    await expect(downloadButton).toBeEnabled(); await screenshot('export-completed');
    const downloaded = page.waitForEvent('download'); await downloadButton.click();
    const file = await downloaded; expect(file.suggestedFilename()).toMatch(/\.csv$/i);
    const target = info.outputPath('actual-download.csv'); await file.saveAs(target);
    const bytes = readFileSync(target); expect(bytes.length).toBeGreaterThan(3);
    expect([...bytes.subarray(0, 3)]).toEqual([239, 187, 191]);
    const artifacts = (await db.query(`SELECT file_size,request_params->>'checksumSha256' AS checksum,
      request_params->'prepared'->'evidence'->'snapshot'->'metadata' AS metadata
      FROM ab_export_task WHERE tenant_id=$1 AND request_params->>'sourceType'='domain'
      AND request_params->'prepared'->>'reference'=$2`, [fixture.tenant, clear.snapshotToken])).rows;
    expect(artifacts).toHaveLength(1);
    const { createHash } = await import('node:crypto');
    expect(createHash('sha256').update(bytes).digest('hex')).toBe(artifacts[0].checksum);
    expect(Number(artifacts[0].file_size)).toBe(bytes.length);
    const parsed = JSON.parse(execFileSync('python3', ['-c',
      'import csv,json,sys; f=open(sys.argv[1],encoding="utf-8-sig",newline=""); r=list(csv.DictReader(f)); print(json.dumps({"columns":list(r[0]),"rows":r},ensure_ascii=False))', target], { encoding: 'utf8' }));
    expect(parsed.columns).toHaveLength(15); expect(parsed.rows).toHaveLength(1);
    expect(parsed.rows[0]).toMatchObject({ '公告标题': fixture.title, '版本号': '1', '阅读状态': '已读', '知悉状态': '待确认' });
    expect(parsed.rows[0]['发布时间']).toContain(`[${artifacts[0].metadata.timezone}]`);
    writeFileSync(info.outputPath('artifact-validation.json'), JSON.stringify({ columns: parsed.columns.length,
      rows: parsed.rows.length, byteCount: bytes.length, persistedDigestMatches: true, duplicateTasks: 0,
      uncertainResponseReplayed: true, timezone: artifacts[0].metadata.timezone }, null, 2));
    await expect(downloadButton).toBeEnabled(); await screenshot('export-downloaded');
    await page.getByRole('button', { name: '返回公告', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/p/ab_announcement/view/${fixture.pid}$`));
    await expect(page.getByText(fixture.title, { exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: fixture.actorName, exact: true })).toBeVisible();
    await page.getByRole('button', { name: '接收统计', exact: true }).click();
    await expect(page.getByTestId('metric-strip-item-actualRecipients')).toContainText('1');
    const historyResponse=response(`/versions/${fixture.version}/delivery-summary`,'GET');
    await page.getByRole('button', { name: '渠道记录', exact: true }).click();
    expect(await successful(await historyResponse)).toMatchObject({recordPid:fixture.pid,versionPid:fixture.version});
    await expect(page).toHaveURL(new RegExp(`/p/announcement_delivery_history/view/${fixture.version}$`));
    const history=page.getByTestId('table-block');
    await expect(history.getByRole('row')).toHaveCount(2);
    await expect(history.getByRole('cell', {name:'站内通知',exact:true})).toBeVisible();
    await expect(history.getByRole('cell', {name:'发布通知',exact:true})).toBeVisible();
    await expect(history.getByRole('cell', {name:'提醒已生成',exact:true})).toBeVisible();
    await screenshot('channel-history');
    await page.reload({waitUntil:'domcontentloaded'});
    await expect(history.getByRole('row')).toHaveCount(2);
    const historyReload=response(`/versions/${fixture.version}/deliveries`,'GET');
    await page.getByRole('button', {name:'刷新记录',exact:true}).click();
    expect((await successful(await historyReload)).total).toBe(1);
    expect((await db.query('SELECT count(*)::int AS count FROM ab_announcement_delivery WHERE tenant_id=$1 AND version_pid=$2',[fixture.tenant,fixture.version])).rows[0].count).toBe(1);
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
