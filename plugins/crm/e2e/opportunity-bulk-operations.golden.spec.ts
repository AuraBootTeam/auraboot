import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { Pool } from 'pg';
import * as XLSX from 'xlsx';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5231';
const BE = process.env.BACKEND_URL || 'http://127.0.0.1:6531';
const RUN = process.env.CRM_BULK_RUN_ID || `crm-bulk-${Date.now().toString(36)}`;
const EVIDENCE_DIR =
  process.env.CRM_BULK_EVIDENCE_DIR || path.join('/tmp', `crm-opportunity-bulk-${RUN}`);
const ADMIN_EMAIL = 'admin@auraboot.com';
const PASSWORD = 'Test2026x';
const TARGET_EMAIL = `${RUN}-owner@e2e.local`;
const OUTSIDER_EMAIL = `${RUN}-outsider@e2e.local`;
const TARGET_DISPLAY_NAME = `目标负责人 ${RUN.split('-').at(-2)?.toUpperCase() || 'E2E'}`;

const names = {
  transferA: `${RUN} 批量转移甲`,
  transferB: `${RUN} 批量转移乙`,
  deleteAllowed: `${RUN} 允许删除`,
  deleteBlocked: `${RUN} 禁止删除`,
  editA: `${RUN} 批量编辑甲`,
  editB: `${RUN} 批量编辑乙`,
};

const ids = {
  adminUser: '',
  targetUser: '',
  outsiderUser: '',
  account: '',
  transferA: '',
  transferB: '',
  deleteAllowed: '',
  deleteBlocked: '',
  editA: '',
  editB: '',
};

let adminJwt = '';
const screenshots: string[] = [];
const coverageRows = [
  ['BO-01', 'bulk transfer modal discovers only active members of the current tenant'],
  ['BO-02', 'bulk transfer submits the exact selected user PID to every selected record'],
  ['BO-03', 'successful owner transfer is persisted and visible with a human label'],
  ['BO-04', 'forged cross-tenant owner PID is rejected before persistence'],
  ['BO-05', 'safe bulk edit updates a declared numeric field on every selected record'],
  ['BO-06', 'selected export contains exactly the selected opportunity facts'],
  ['BO-07', 'command-owned bulk delete permits discovery opportunities'],
  ['BO-08', 'command-owned bulk delete rejects proposal opportunities'],
  ['BO-09', 'mixed-result delete explains per-record success and failure'],
  ['BO-10', 'built-in generic delete is absent so lifecycle rules cannot be bypassed'],
] as const;
const completed = new Set<string>();
const failed = new Set<string>();
const skipped = new Set<string>();
const coverageByTest = new Map<string, readonly string[]>([
  [
    'bulk transfer uses tenant member discovery and persists exact owner facts',
    ['BO-01', 'BO-02', 'BO-03', 'BO-04', 'BO-10'],
  ],
  [
    'safe bulk edit and selected export keep the exact selected fact set',
    ['BO-05', 'BO-06'],
  ],
  [
    'command-owned bulk delete reports a mixed lifecycle result',
    ['BO-07', 'BO-08', 'BO-09'],
  ],
]);

function findValue(value: unknown, keys: string[]): unknown {
  if (Array.isArray(value)) {
    for (const child of value) {
      const found = findValue(child, keys);
      if (found !== undefined && found !== null && found !== '') return found;
    }
  } else if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    for (const key of keys) {
      if (record[key] !== undefined && record[key] !== null && record[key] !== '') {
        return record[key];
      }
    }
    for (const child of Object.values(record)) {
      const found = findValue(child, keys);
      if (found !== undefined && found !== null && found !== '') return found;
    }
  }
  return undefined;
}

async function api(pathname: string, init: RequestInit = {}, jwt = adminJwt): Promise<any> {
  const headers = new Headers(init.headers);
  if (jwt) headers.set('Authorization', `Bearer ${jwt}`);
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${BE}${pathname}`, { ...init, headers });
  const body = await response.json().catch(() => ({}));
  return { response, body };
}

function assertOk(result: any, label: string): any {
  expect(result.response.ok, `${label}: HTTP ${result.response.status} ${JSON.stringify(result.body)}`)
    .toBeTruthy();
  expect(String(result.body?.code), `${label}: ${JSON.stringify(result.body)}`).toBe('0');
  return result.body;
}

async function executeCreate(code: string, payload: Record<string, unknown>): Promise<string> {
  const body = assertOk(
    await api(`/api/meta/commands/execute/${code}`, {
      method: 'POST',
      body: JSON.stringify({ payload, operationType: 'create' }),
    }),
    code,
  );
  const pid = findValue(body?.data?.data ?? body?.data, [
    'recordId',
    'recordPid',
    'publicRecordId',
    'pid',
  ]);
  expect(pid, `${code} must return a public record PID`).toBeTruthy();
  return String(pid);
}

async function executeTransition(code: string, targetRecordPid: string): Promise<void> {
  assertOk(
    await api(`/api/meta/commands/execute/${code}`, {
      method: 'POST',
      body: JSON.stringify({ payload: {}, targetRecordPid, operationType: 'update' }),
    }),
    code,
  );
}

async function getRecord(pid: string): Promise<Record<string, any>> {
  return assertOk(
    await api(`/api/dynamic/crm_opportunity_common/${encodeURIComponent(pid)}`),
    `read opportunity ${pid}`,
  ).data;
}

async function provisionUser(email: string, displayName: string): Promise<string> {
  const body = assertOk(
    await api('/api/admin/users', {
      method: 'POST',
      body: JSON.stringify({
        email,
        displayName,
        initialPassword: PASSWORD,
        roleCodes: ['crm_sales'],
        sendInviteEmail: false,
      }),
    }),
    `provision ${email}`,
  );
  const pid = findValue(body?.data, ['pid', 'userPid']);
  expect(pid).toBeTruthy();
  return String(pid);
}

async function moveUserMembershipToIsolatedTenant(userPid: string): Promise<void> {
  const pool = new Pool({
    host: process.env.PGHOST || process.env.PG_HOST || '127.0.0.1',
    port: Number(process.env.PGPORT || process.env.PG_PORT || 5432),
    user: process.env.PGUSER || process.env.PG_USER || 'auraboot',
    database: process.env.PGDATABASE || process.env.PG_DB || 'aura_boot',
    password: process.env.PGPASSWORD || process.env.PG_PASSWORD || 'auraboot',
  });
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    // The provisioning API commits the user and its membership in separate
    // transactions; poll briefly so an immediate membership read does not
    // race the commit.
    let membershipId: string | undefined;
    for (let attempt = 0; attempt < 60; attempt++) {
      const membership = await client.query<{ member_id: string }>(
        `SELECT tm.id::text AS member_id
         FROM ab_tenant_member tm
         INNER JOIN ab_user u ON u.id = tm.user_id
         WHERE u.pid = $1 AND tm.status = 'active' AND tm.deleted_flag = false
         LIMIT 1`,
        [userPid],
      );
      if (membership.rowCount === 1) {
        membershipId = membership.rows[0].member_id;
        break;
      }
      await client.query('COMMIT');
      await new Promise((resolve) => setTimeout(resolve, 500));
      await client.query('BEGIN');
    }
    expect(membershipId, `no active tenant membership for ${userPid}`).toBeTruthy();
    const tenantId = (
      BigInt(Date.now()) * 1_000_000n + BigInt(Math.floor(Math.random() * 1_000_000))
    ).toString();
    await client.query(
      `INSERT INTO ab_tenant
         (id, pid, name, display_name, status, deleted_flag, created_at, updated_at)
       VALUES ($1, substr(md5(random()::text || clock_timestamp()::text), 1, 26),
               $2, $3, 'active', false, now(), now())`,
      [tenantId, `${RUN}-isolated`, `${RUN} Isolated`],
    );
    await client.query(
      `UPDATE ab_user_role SET deleted_flag = true, updated_at = now() WHERE member_id = $1`,
      [membershipId!],
    );
    await client.query(
      `UPDATE ab_tenant_member SET tenant_id = $1, updated_at = now() WHERE id = $2`,
      [tenantId, membershipId!],
    );
    await client.query('COMMIT');
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
    await pool.end();
  }
}

async function uiLogin(page: Page): Promise<void> {
  const response = await page.request.post(`${BASE}/login`, {
    form: { email: ADMIN_EMAIL, password: PASSWORD, remember: 'on', redirectTo: '/' },
    maxRedirects: 0,
  });
  expect([302, 303]).toContain(response.status());
  await page.goto(`${BASE}/`, { waitUntil: 'load' });
  if (page.url().includes('tenant-selection')) {
    await page.getByRole('button', { name: /进入|选择|Enter|AuraBoot/ }).first().click();
    await page.waitForURL((url) => !url.pathname.includes('tenant-selection'));
  }
}

async function openRunList(page: Page): Promise<void> {
  await page.goto(`${BASE}/p/crm_opportunity_common?keyword=${encodeURIComponent(RUN)}`, {
    waitUntil: 'domcontentloaded',
  });
  await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 20_000 });
  const tableMode = page.getByTestId('list-view-mode-table');
  if ((await tableMode.getAttribute('aria-checked')) !== 'true') await tableMode.click();
  await page.getByRole('button', { name: '全部', exact: true }).click();
}

function row(page: Page, name: string) {
  return page.locator('tr').filter({ hasText: name });
}

async function selectRows(page: Page, selectedNames: string[]): Promise<void> {
  for (const name of selectedNames) {
    const target = row(page, name);
    await expect(target).toBeVisible();
    await target.locator('input[type="checkbox"]').click();
  }
}

async function shot(page: Page, testInfo: TestInfo, name: string): Promise<string> {
  const output = path.join(EVIDENCE_DIR, name);
  mkdirSync(path.dirname(output), { recursive: true });
  await page.screenshot({ path: output, fullPage: true });
  await testInfo.attach(name, { path: output, contentType: 'image/png' });
  screenshots.push(output);
  return output;
}

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  mkdirSync(EVIDENCE_DIR, { recursive: true });
  const login = assertOk(
    await api(
      '/api/auth/login',
      { method: 'POST', body: JSON.stringify({ email: ADMIN_EMAIL, password: PASSWORD }) },
      '',
    ),
    'admin login',
  );
  adminJwt = String(findValue(login.data, ['jwt']) || '');
  const me = assertOk(await api('/api/auth/me'), 'current admin').data?.user;
  ids.adminUser = String(me?.pid || '');
  ids.targetUser = await provisionUser(TARGET_EMAIL, TARGET_DISPLAY_NAME);
  ids.outsiderUser = await provisionUser(OUTSIDER_EMAIL, `${RUN} 外部负责人`);
  await moveUserMembershipToIsolatedTenant(ids.outsiderUser);

  ids.account = await executeCreate('crm:create_account', {
    crm_acc_name: `${RUN} 批量运营客户`,
    crm_acc_industry: 'manufacturing',
    crm_acc_status: 'active',
  });
  for (const [key, name, amount] of [
    ['transferA', names.transferA, 120000],
    ['transferB', names.transferB, 140000],
    ['deleteAllowed', names.deleteAllowed, 160000],
    ['deleteBlocked', names.deleteBlocked, 180000],
    ['editA', names.editA, 200000],
    ['editB', names.editB, 220000],
  ] as const) {
    ids[key] = await executeCreate('crm:create_opportunity', {
      crm_opp_name: name,
      crm_opp_account_id: ids.account,
      crm_opp_currency_code: 'CNY',
      crm_opp_expected_amount: amount,
      crm_opp_expected_close_date: '2026-12-31T18:00:00+08:00',
      crm_opp_probability: 25,
      crm_opp_owner: ids.adminUser,
      crm_opp_forecast_category: 'pipeline',
    });
  }
  await executeTransition('crm:qualify_opportunity', ids.deleteBlocked);
  await executeTransition('crm:advance_opp_to_proposal', ids.deleteBlocked);
});

test.afterEach(({}, testInfo) => {
  const covered = coverageByTest.get(testInfo.title) ?? [];
  if (testInfo.status === 'skipped') {
    covered.forEach((id) => {
      completed.delete(id);
      skipped.add(id);
    });
  } else if (testInfo.status !== testInfo.expectedStatus) {
    covered.forEach((id) => {
      completed.delete(id);
      failed.add(id);
    });
  }
});

test.afterAll(() => {
  const rows = coverageRows.map(([id, claim]) => ({
    id,
    claim,
    status: failed.has(id)
      ? 'fail'
      : skipped.has(id)
        ? 'skipped'
        : completed.has(id)
          ? 'pass'
          : 'untested',
    evidence: completed.has(id) ? 'this Playwright run' : 'not successfully completed',
  }));
  writeFileSync(
    path.join(EVIDENCE_DIR, 'manifest.json'),
    `${JSON.stringify(
      {
        schemaVersion: 1,
        run: {
          id: RUN,
          runtime: process.env.AURA_RUNTIME_NAME || null,
          baseUrl: BASE,
          backendUrl: BE,
          dataMigration: 'not required; development stage',
        },
        summary: {
          total: rows.length,
          pass: rows.filter((item) => item.status === 'pass').length,
          fail: rows.filter((item) => item.status === 'fail').length,
          skipped: rows.filter((item) => item.status === 'skipped').length,
          untested: rows.filter((item) => item.status === 'untested').length,
        },
        groups: [{ id: 'opportunity-bulk-operations', title: 'Opportunity bulk operations', items: rows }],
        recordIds: ids,
        screenshots,
        technicalVerdict: rows.every((item) => item.status === 'pass') ? 'pass' : 'failed',
        productOwnerScreenshotSignOff: 'pending-human-signature',
      },
      null,
      2,
    )}\n`,
  );
});

test('bulk transfer uses tenant member discovery and persists exact owner facts', async ({ page }, testInfo) => {
  await uiLogin(page);
  await openRunList(page);
  await selectRows(page, [names.transferA, names.transferB]);
  await expect(page.getByTestId('bulk-delete-btn')).toHaveCount(0);
  completed.add('BO-10');

  await page.getByTestId('bulk-action-bulk_transfer_owner').click();
  const dialog = page.getByTestId('bulk-field-command-dialog');
  await expect(dialog).toBeVisible();
  await dialog.getByTestId('member-picker-add').click();
  const outsiderSearch = page.waitForResponse(
    (response) =>
      response.url().includes('/api/tenant/members/search') &&
      response.request().method() === 'POST' &&
      String(response.request().postDataJSON()?.keyword ?? '') === OUTSIDER_EMAIL,
  );
  await dialog.getByTestId('member-picker-search-input').fill(OUTSIDER_EMAIL);
  expect((await outsiderSearch).ok()).toBeTruthy();
  await expect(dialog.getByTestId(`member-picker-option-${ids.outsiderUser}`)).toHaveCount(0);
  await expect(dialog).toContainText(/未找到成员|No members found/);

  const targetSearch = page.waitForResponse(
    (response) =>
      response.url().includes('/api/tenant/members/search') &&
      response.request().method() === 'POST' &&
      String(response.request().postDataJSON()?.keyword ?? '') === TARGET_EMAIL,
  );
  await dialog.getByTestId('member-picker-search-input').fill(TARGET_EMAIL);
  expect((await targetSearch).ok()).toBeTruthy();
  const targetOption = dialog.getByTestId(`member-picker-option-${ids.targetUser}`);
  await expect(targetOption).toBeVisible();
  await expect(targetOption).toContainText(TARGET_DISPLAY_NAME);
  await targetOption.click();
  await expect(dialog.getByTestId(`member-picker-selected-${ids.targetUser}`)).toContainText(
    TARGET_DISPLAY_NAME,
  );
  await shot(page, testInfo, 'opportunity-bulk-transfer-confirm.png');
  completed.add('BO-01');

  const commands: Array<{ targetRecordPid?: string; payload?: Record<string, unknown> }> = [];
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      request.url().includes('/api/meta/commands/execute/crm:update_opportunity')
    ) {
      commands.push(request.postDataJSON());
    }
  });
  await dialog.getByTestId('bulk-field-command-submit').click();
  await expect(dialog).toHaveCount(0, { timeout: 20_000 });
  await expect
    .poll(() => commands.length, { timeout: 20_000 })
    .toBe(2);
  expect(commands.map((command) => command.targetRecordPid).sort()).toEqual(
    [ids.transferA, ids.transferB].sort(),
  );
  expect(commands.every((command) => command.payload?.crm_opp_owner === ids.targetUser)).toBe(true);
  completed.add('BO-02');

  await expect
    .poll(
      async () =>
        (await Promise.all([getRecord(ids.transferA), getRecord(ids.transferB)])).map(
          (record) => record.crm_opp_owner,
        ),
      { timeout: 20_000 },
    )
    .toEqual([ids.targetUser, ids.targetUser]);
  await expect(row(page, names.transferA)).toContainText(TARGET_DISPLAY_NAME);
  await shot(page, testInfo, 'opportunity-bulk-transfer-complete.png');
  completed.add('BO-03');

  const forged = await api('/api/meta/commands/execute/crm:update_opportunity', {
    method: 'POST',
    body: JSON.stringify({
      targetRecordPid: ids.transferA,
      operationType: 'update',
      payload: { crm_opp_owner: ids.outsiderUser },
    }),
  });
  expect(forged.response.ok && String(forged.body?.code) === '0').toBe(false);
  expect((await getRecord(ids.transferA)).crm_opp_owner).toBe(ids.targetUser);
  completed.add('BO-04');
});

test('safe bulk edit and selected export keep the exact selected fact set', async ({ page }, testInfo) => {
  await uiLogin(page);
  await openRunList(page);
  await selectRows(page, [names.editA, names.editB]);
  await page.getByTestId('bulk-edit-btn').click();
  const dialog = page.getByTestId('bulk-edit-dialog');
  await dialog.getByTestId('bulk-edit-field').selectOption('crm_opp_probability');
  await dialog.getByTestId('bulk-edit-value').fill('55');
  const updateResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'PUT' &&
      response.url().includes('/api/dynamic/crm_opportunity_common/batch'),
  );
  await dialog.getByRole('button', { name: /更新 2 条记录|Update 2 records/ }).click();
  const updated = await updateResponse;
  expect(updated.ok()).toBeTruthy();
  const updateBody = await updated.json();
  expect(updateBody.data).toEqual(expect.objectContaining({ total: 2, success: 2, failed: 0 }));
  await expect
    .poll(
      async () =>
        (await Promise.all([getRecord(ids.editA), getRecord(ids.editB)])).map(
          (record) => Number(record.crm_opp_probability),
        ),
      { timeout: 20_000 },
    )
    .toEqual([55, 55]);
  completed.add('BO-05');

  await selectRows(page, [names.editA, names.editB]);
  const exportResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/dynamic/crm_opportunity_common/export'),
  );
  const downloadPromise = page.waitForEvent('download');
  await page.getByTestId('bulk-more-actions-btn').click();
  await expect(page.getByTestId('bulk-more-actions-menu')).toBeVisible();
  await shot(page, testInfo, 'opportunity-selected-export-action-menu.png');
  await page.getByTestId('bulk-export-selected-btn').click();
  const [exported, download] = await Promise.all([exportResponse, downloadPromise]);
  expect(exported.ok()).toBeTruthy();
  const exportPayload = exported.request().postDataJSON();
  expect(exportPayload.conditions).toEqual(
    expect.arrayContaining([
      { field: 'pid', operator: 'IN', value: expect.arrayContaining([ids.editA, ids.editB]) },
    ]),
  );
  expect((await exported.json()).data.recordCount).toBe(2);
  const workbookPath = path.join(EVIDENCE_DIR, 'selected-opportunities.xlsx');
  await download.saveAs(workbookPath);
  const workbook = XLSX.readFile(workbookPath);
  const rows = XLSX.utils.sheet_to_json<Record<string, unknown>>(
    workbook.Sheets[workbook.SheetNames[0]],
  );
  const workbookText = JSON.stringify(rows);
  expect(workbookText).toContain(names.editA);
  expect(workbookText).toContain(names.editB);
  expect(workbookText).not.toContain(names.transferA);
  expect(workbookText).not.toContain(names.deleteBlocked);
  await shot(page, testInfo, 'opportunity-selected-export-complete.png');
  completed.add('BO-06');
});

test('command-owned bulk delete reports a mixed lifecycle result', async ({ page }, testInfo) => {
  await uiLogin(page);
  await openRunList(page);
  await selectRows(page, [names.deleteAllowed, names.deleteBlocked]);
  await page.getByTestId('bulk-more-actions-btn').click();
  await expect(page.getByTestId('bulk-more-actions-menu')).toBeVisible();
  await page.getByTestId('bulk-action-bulk_delete_opportunities').click();
  const confirm = page.getByRole('dialog');
  await expect(confirm).toContainText(/仅发现和资格确认阶段允许删除|Only discovery and qualification/);
  await confirm.getByRole('button', { name: /确认|确定|Confirm|删除|Delete/ }).last().click();

  const result = page.getByTestId('bulk-action-result-dialog');
  await expect(result).toBeVisible({ timeout: 20_000 });
  await expect(result).toContainText('成功 1 条');
  await expect(result).toContainText('失败 1 条');
  await expect(result).toContainText(names.deleteBlocked);
  await shot(page, testInfo, 'opportunity-bulk-delete-mixed-result.png');
  completed.add('BO-07');
  completed.add('BO-08');
  completed.add('BO-09');

  const allowed = await api(`/api/dynamic/crm_opportunity_common/${ids.deleteAllowed}`);
  expect(allowed.response.ok && String(allowed.body?.code) === '0').toBe(false);
  expect((await getRecord(ids.deleteBlocked)).crm_opp_stage).toBe('proposal');
});
