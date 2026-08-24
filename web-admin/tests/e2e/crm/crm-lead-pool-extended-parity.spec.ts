import {
  expect,
  test,
  type FileChooser,
  type Locator,
  type Page,
  type Response,
  type TestInfo,
} from '@playwright/test';
import fs from 'node:fs';
import { read as readXlsx, utils as XLSXUtils, write as writeXlsx } from 'xlsx';

const ADMIN_EMAIL = 'admin@auraboot.com';
const PASSWORD = 'Test2026x';

type CommandResult = Record<string, unknown>;

async function login(page: Page): Promise<void> {
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle', { timeout: 10_000 }).catch(() => {});
  const identifier = page
    .locator('input[placeholder*="用户名"], input[name="identifier"], input[type="email"]')
    .first();
  if (await identifier.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await identifier.fill(ADMIN_EMAIL);
    await page.getByRole('textbox', { name: '密码' }).fill(PASSWORD);
    await page.getByRole('button', { name: '立即登录', exact: true }).click();
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 });
  }
  const me = await page.request.get('/api/auth/me');
  const body = await me.json().catch(() => ({}));
  expect(me.ok() && String(body?.code) === '0', 'real administrator session').toBe(true);
}

async function command(
  page: Page,
  code: string,
  payload: Record<string, unknown> = {},
  targetRecordPid?: string,
  operationType?: string,
): Promise<CommandResult> {
  const response = await page.request.post(`/api/meta/commands/execute/${code}`, {
    data: {
      payload,
      ...(targetRecordPid ? { targetRecordPid } : {}),
      ...(operationType ? { operationType } : {}),
    },
  });
  const body = await response.json().catch(() => ({}));
  expect(
    response.ok() && String(body?.code) === '0',
    `${code}: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 1_000)}`,
  ).toBe(true);
  return body?.data?.data ?? body?.data ?? {};
}

async function rejectedCommand(
  page: Page,
  code: string,
  targetRecordPid: string,
): Promise<Record<string, unknown>> {
  const response = await page.request.post(`/api/meta/commands/execute/${code}`, {
    data: { targetRecordPid, payload: {} },
  });
  const body = await response.json().catch(() => ({}));
  expect(String(body?.code), `${code} must reject this governed state`).not.toBe('0');
  return body;
}

function pid(result: CommandResult): string {
  return String(result.recordId ?? result.recordPid ?? result.publicRecordId ?? result.pid ?? '');
}

async function getRecord(page: Page, model: string, recordPid: string) {
  const response = await page.request.get(`/api/dynamic/${model}/${recordPid}`);
  const body = await response.json().catch(() => ({}));
  expect(response.ok() && String(body?.code) === '0', `${model}/${recordPid}`).toBe(true);
  return body?.data as Record<string, unknown>;
}

async function listRows(page: Page, model: string): Promise<Record<string, unknown>[]> {
  const response = await page.request.get(`/api/dynamic/${model}/list?pageNum=1&pageSize=500`);
  const body = await response.json().catch(() => ({}));
  expect(response.ok() && String(body?.code) === '0', `${model} list`).toBe(true);
  const data = body?.data;
  return (data?.records ?? data?.rows ?? data?.content ?? data ?? []) as Record<string, unknown>[];
}

async function createLeadInPool(
  page: Page,
  poolPid: string,
  ownerPid: string,
  company: string,
  index: number,
) {
  const lead = await command(
    page,
    'crm:create_lead',
    {
      crm_lead_company: company,
      crm_lead_contact_name: `扩展联系人 ${index}`,
      crm_lead_contact_phone: `1389000${String(index).padStart(4, '0')}`,
      crm_lead_source: 'website',
      crm_lead_score: 70 + index,
      crm_lead_status: 'new',
      crm_lead_assigned_to: ownerPid,
    },
    undefined,
    'create',
  );
  const leadPid = pid(lead);
  const moved = await command(
    page,
    'crm:move_lead_to_pool',
    { poolId: poolPid, reason: 'Cordys extended operation parity' },
    leadPid,
  );
  return { leadPid, itemPid: String(moved.poolItemId ?? '') };
}

async function attachScreenshot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  await testInfo.attach(name, { body: await page.screenshot(), contentType: 'image/png' });
}

function leadPoolWorkbook(rows: unknown[][]): Buffer {
  const workbook = XLSXUtils.book_new();
  XLSXUtils.book_append_sheet(
    workbook,
    XLSXUtils.aoa_to_sheet([
      [
        'crm_lead_code',
        'crm_lead_company',
        'crm_lead_contact_name',
        'crm_lead_contact_phone',
        'crm_lead_contact_email',
        'crm_lead_source',
        'crm_lead_industry',
        'crm_lead_score',
        'crm_lead_status',
        'crm_lead_requirement',
      ],
      ...rows,
    ]),
    '线索池导入',
  );
  return writeXlsx(workbook, { type: 'buffer', bookType: 'xlsx' }) as Buffer;
}

async function clickRowAction(
  page: Page,
  row: Locator,
  actionCode: string,
  actionName: RegExp,
): Promise<void> {
  const direct = row.getByRole('button', { name: actionName }).first();
  if (await direct.isVisible().catch(() => false)) {
    await direct.click();
    return;
  }
  await row.getByTestId('row-action-more').click();
  const menuAction = page.getByTestId(`row-action-${actionCode}`).last();
  await expect(menuAction).toBeVisible({ timeout: 5_000 });
  await menuAction.click();
}

async function runImportAction(
  page: Page,
  poolRow: Locator,
  actionCode: 'precheck_import' | 'import_leads',
  commandCode: 'crm:precheck_lead_pool_import' | 'crm:import_lead_pool_leads',
  workbookName: string,
  workbook: Buffer,
): Promise<Response> {
  await clickRowAction(
    page,
    poolRow,
    actionCode,
    actionCode === 'precheck_import'
      ? /预检导入文件|Pre-check Import File/
      : /正式导入线索|Import Leads/,
  );
  const form = page.getByTestId('form-dialog');
  await expect(form).toBeVisible({ timeout: 10_000 });
  const fileInput = form.getByTestId('form-dialog-field-importFileId');
  await expect(fileInput).toHaveAttribute('type', 'file');
  await fileInput.setInputFiles({
    name: workbookName,
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: workbook,
  });
  let secondPickerOpened = false;
  const secondPickerGuard = async (chooser: FileChooser): Promise<void> => {
    secondPickerOpened = true;
    await chooser.setFiles({
      name: workbookName,
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: workbook,
    });
  };
  page.on('filechooser', secondPickerGuard);
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/meta/commands/execute/${commandCode}`),
    { timeout: 30_000 },
  );
  await form.getByTestId('form-dialog-submit').click();
  const response = await responsePromise.finally(() => page.off('filechooser', secondPickerGuard));
  expect(secondPickerOpened, 'inline import must not open a second file picker').toBe(false);
  const body = await response.json().catch(() => ({}));
  expect(response.ok() && String(body?.code) === '0', JSON.stringify(body).slice(0, 1_000)).toBe(
    true,
  );
  return response;
}

async function closeAsyncTaskModal(page: Page): Promise<void> {
  const close = page.getByRole('button', { name: '关闭', exact: true }).last();
  await expect(close).toBeVisible({ timeout: 30_000 });
  await close.click();
}

test.describe('CRM lead-pool Cordys extended operation parity', () => {
  test.setTimeout(180_000);

  test('list, quick policy, guard, item update/delete, batch-equivalent actions, exports and template persist on the real stack', async ({
    page,
  }, testInfo) => {
    await login(page);
    const stamp = String(Date.now());
    const me = await page.request.get('/api/auth/me');
    const meBody = await me.json();
    const adminPid = String(meBody?.data?.user?.pid ?? '');
    expect(adminPid).toBeTruthy();

    const pool = await command(
      page,
      'crm:create_lead_pool',
      {
        crm_lp_name: `Cordys Extended ${stamp}`,
        crm_lp_member_user_ids: JSON.stringify([adminPid]),
        crm_lp_admin_user_ids: JSON.stringify([adminPid]),
        crm_lp_daily_pick_limit: 20,
        crm_lp_new_cooldown_days: 0,
        crm_lp_previous_owner_cooldown_days: 0,
        crm_lp_auto_recycle: false,
        crm_lp_recycle_match_mode: 'all',
        crm_lp_recycle_after_days: 30,
        crm_lp_recycle_basis: 'last_activity',
        crm_lp_description: 'Cordys extended operation evidence',
      },
      undefined,
      'create',
    );
    const poolPid = pid(pool);
    expect(poolPid).toBeTruthy();

    const existingCapacity = (await listRows(page, 'crm_lead_capacity_common')).find(
      (row) => String(row.crm_lcap_user_id) === adminPid,
    );
    const capacityPid = existingCapacity
      ? String(existingCapacity.pid)
      : pid(
          await command(
            page,
            'crm:create_lead_capacity',
            {
              crm_lcap_user_id: adminPid,
              crm_lcap_capacity: 100,
              crm_lcap_status: 'active',
              crm_lcap_remark: `Extended ${stamp}`,
            },
            undefined,
            'create',
          ),
        );
    if (existingCapacity) {
      await command(
        page,
        'crm:update_lead_capacity',
        { crm_lcap_capacity: 100, crm_lcap_status: 'active', crm_lcap_remark: `Reused ${stamp}` },
        capacityPid,
        'update',
      );
    }
    expect(
      (await listRows(page, 'crm_lead_capacity_common')).some((row) => row.pid === capacityPid),
    ).toBe(true);

    const quick = await createLeadInPool(page, poolPid, adminPid, `Extended Quick ${stamp}`, 1);
    const batchA = await createLeadInPool(page, poolPid, adminPid, `Extended Batch A ${stamp}`, 2);
    const batchB = await createLeadInPool(page, poolPid, adminPid, `Extended Batch B ${stamp}`, 3);
    const doomed = await createLeadInPool(page, poolPid, adminPid, `Extended Delete ${stamp}`, 4);

    await page.goto('/p/c/crm_lead_pool_batch_list', { waitUntil: 'domcontentloaded' });
    await expect(
      page.getByRole('cell', { name: `Extended Quick ${stamp}`, exact: true }),
    ).toBeVisible({ timeout: 20_000 });
    await expect(page.getByTestId('view-analysis-open')).toBeVisible();
    await attachScreenshot(page, testInfo, 'lead-pool-extended-list');

    await command(
      page,
      'crm:update_pool_lead',
      {
        crm_lpi_company: `Extended Quick Updated ${stamp}`,
        crm_lpi_source: 'referral',
        crm_lpi_score: 96,
      },
      quick.itemPid,
      'update',
    );
    const updatedLead = await getRecord(page, 'crm_lead_common', quick.leadPid);
    const updatedItem = await getRecord(page, 'crm_lead_pool_item_common', quick.itemPid);
    expect(updatedLead.crm_lead_company).toBe(`Extended Quick Updated ${stamp}`);
    expect(updatedItem.crm_lpi_company).toBe(`Extended Quick Updated ${stamp}`);
    expect(updatedLead.crm_lead_source).toBe('referral');
    expect(updatedItem.crm_lpi_score).toBe(96);

    for (const item of [batchA, batchB]) {
      await command(
        page,
        'crm:update_pool_lead',
        { crm_lead_source: 'event', crm_lead_score: 88 },
        item.itemPid,
        'update',
      );
    }
    expect(
      (await getRecord(page, 'crm_lead_pool_item_common', batchA.itemPid)).crm_lpi_source,
    ).toBe('event');
    expect((await getRecord(page, 'crm_lead_pool_item_common', batchB.itemPid)).crm_lpi_score).toBe(
      88,
    );

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(
      page.getByRole('cell', { name: `Extended Quick Updated ${stamp}`, exact: true }),
    ).toBeVisible({ timeout: 20_000 });

    const exportAllDownload = page.waitForEvent('download', { timeout: 20_000 });
    await page.getByTestId('toolbar-more-menu').click();
    await page.getByTestId('more-menu-export-excel').click();
    const allDownload = await exportAllDownload;
    const allPath = testInfo.outputPath('lead-pool-export-all.xlsx');
    await allDownload.saveAs(allPath);
    const allWorkbook = readXlsx(fs.readFileSync(allPath), { type: 'buffer' });
    const allRows = XLSXUtils.sheet_to_json<unknown[]>(
      allWorkbook.Sheets[allWorkbook.SheetNames[0]],
      { header: 1 },
    );
    expect(JSON.stringify(allRows)).toContain(`Extended Quick Updated ${stamp}`);

    for (const name of [`Extended Batch A ${stamp}`, `Extended Batch B ${stamp}`]) {
      await page
        .getByRole('row', { name: new RegExp(name) })
        .getByRole('checkbox')
        .click();
    }
    const exportSelectedDownload = page.waitForEvent('download', { timeout: 20_000 });
    await page.getByTestId('bulk-more-actions-btn').click();
    await page.getByTestId('bulk-export-selected-btn').click();
    const selectedDownload = await exportSelectedDownload;
    const selectedPath = testInfo.outputPath('lead-pool-export-selected.xlsx');
    await selectedDownload.saveAs(selectedPath);
    const selectedWorkbook = readXlsx(fs.readFileSync(selectedPath), { type: 'buffer' });
    const selectedRows = XLSXUtils.sheet_to_json<unknown[]>(
      selectedWorkbook.Sheets[selectedWorkbook.SheetNames[0]],
      { header: 1 },
    );
    expect(JSON.stringify(selectedRows)).toContain(`Extended Batch A ${stamp}`);
    expect(JSON.stringify(selectedRows)).toContain(`Extended Batch B ${stamp}`);
    expect(JSON.stringify(selectedRows)).not.toContain(`Extended Quick Updated ${stamp}`);

    await command(page, 'crm:assign_pool_lead', { assigneeId: adminPid }, batchA.itemPid, 'update');
    expect(
      (await getRecord(page, 'crm_lead_pool_item_common', batchA.itemPid)).crm_lpi_status,
    ).toBe('assigned');

    await command(page, 'crm:delete_pool_lead', {}, doomed.itemPid, 'DELETE');
    expect(
      (await listRows(page, 'crm_lead_pool_item_common')).some((row) => row.pid === doomed.itemPid),
    ).toBe(false);

    const guard = await rejectedCommand(page, 'crm:delete_lead_pool', poolPid);
    expect(JSON.stringify(guard)).toMatch(/available leads|cannot be deleted|线索/i);
    await command(
      page,
      'crm:update_lead_pool',
      { crm_lp_daily_pick_limit: 25, crm_lp_description: 'Quick policy updated' },
      poolPid,
      'update',
    );
    expect((await getRecord(page, 'crm_lead_pool_common', poolPid)).crm_lp_daily_pick_limit).toBe(
      25,
    );
    await command(page, 'crm:toggle_lead_pool', {}, poolPid);
    expect((await getRecord(page, 'crm_lead_pool_common', poolPid)).crm_lp_status).toBe('disabled');
    await command(page, 'crm:toggle_lead_pool', {}, poolPid);

    const emptyPool = await command(
      page,
      'crm:create_lead_pool',
      {
        crm_lp_name: `Cordys Empty ${stamp}`,
        crm_lp_member_user_ids: JSON.stringify([adminPid]),
        crm_lp_admin_user_ids: JSON.stringify([adminPid]),
        crm_lp_daily_pick_limit: 10,
        crm_lp_new_cooldown_days: 0,
        crm_lp_previous_owner_cooldown_days: 0,
        crm_lp_auto_recycle: false,
        crm_lp_recycle_match_mode: 'all',
        crm_lp_recycle_after_days: 30,
        crm_lp_recycle_basis: 'last_activity',
      },
      undefined,
      'create',
    );
    const emptyPoolPid = pid(emptyPool);
    expect(
      (await listRows(page, 'crm_lead_pool_common')).some((row) => row.pid === emptyPoolPid),
    ).toBe(true);
    await command(page, 'crm:delete_lead_pool', {}, emptyPoolPid, 'DELETE');
    expect(
      (await listRows(page, 'crm_lead_pool_common')).some((row) => row.pid === emptyPoolPid),
    ).toBe(false);

    const template = await command(
      page,
      'crm:download_lead_pool_import_template',
      {},
      poolPid,
      'UPDATE',
    );
    expect(template.fileName).toBe('crm-lead-pool-import-template.xlsx');
    expect(String(template.contentBase64 ?? '').length).toBeGreaterThan(1_000);

    await command(page, 'crm:delete_lead_capacity', {}, capacityPid, 'DELETE');
    expect(
      (await listRows(page, 'crm_lead_capacity_common')).some((row) => row.pid === capacityPid),
    ).toBe(false);

    await testInfo.attach('crm-lead-pool-extended-cordys-source-evidence.json', {
      body: Buffer.from(
        JSON.stringify(
          {
            verdict: 'pass',
            fixtureMode: 'self-seeded',
            sourceIds: [
              'api:clue:clue-capacity:list',
              'api:clue:clue-capacity:delete',
              'api:clue:clue-pool:page',
              'api:clue:clue-pool:quick-update',
              'api:clue:clue-pool:check-no-pick',
              'api:clue:clue-pool:delete',
              'api:clue:clue-pool:switch-status',
              'api:clue:pool-clue:get-pool-options',
              'api:clue:pool-clue:delete',
              'api:clue:pool-clue:batch-assign',
              'api:clue:pool-clue:batch-update',
              'api:clue:pool-clue:batch-delete',
              'api:clue:pool-clue:export-all',
              'api:clue:pool-clue:export-select',
              'api:clue:pool-clue:download-import-tpl',
            ],
            assertionScope:
              'real-stack list and option reads, governed pool/item mutations, batch-equivalent commands, parseable exports, import template and persisted readback',
          },
          null,
          2,
        ),
      ),
      contentType: 'application/json',
    });
  });

  test('XLSX precheck is zero-write and formal import creates the governed pool projection', async ({
    page,
  }, testInfo) => {
    await login(page);
    const stamp = String(Date.now());
    const me = await page.request.get('/api/auth/me');
    const meBody = await me.json();
    const adminPid = String(meBody?.data?.user?.pid ?? '');
    const poolName = `Cordys Import ${stamp}`;
    const pool = await command(
      page,
      'crm:create_lead_pool',
      {
        crm_lp_name: poolName,
        crm_lp_member_user_ids: JSON.stringify([adminPid]),
        crm_lp_admin_user_ids: JSON.stringify([adminPid]),
        crm_lp_daily_pick_limit: 20,
        crm_lp_new_cooldown_days: 0,
        crm_lp_previous_owner_cooldown_days: 0,
        crm_lp_auto_recycle: false,
        crm_lp_recycle_match_mode: 'all',
        crm_lp_recycle_after_days: 30,
        crm_lp_recycle_basis: 'last_activity',
      },
      undefined,
      'create',
    );
    const poolPid = pid(pool);
    const validCode = `LIMP-${stamp}`;
    const validCompany = `Imported Lead ${stamp}`;
    const validRow = [
      validCode,
      validCompany,
      '导入联系人',
      '13911112222',
      'import@example.com',
      'website',
      'manufacturing',
      91,
      'new',
      'Cordys lead-pool import journey',
    ];

    await page.goto('/p/c/crm_lead_pool_list', { waitUntil: 'domcontentloaded' });
    let poolRow = page.getByRole('row', { name: new RegExp(poolName) });
    await expect(poolRow).toBeVisible({ timeout: 20_000 });

    await runImportAction(
      page,
      poolRow,
      'precheck_import',
      'crm:precheck_lead_pool_import',
      `lead-pool-precheck-${stamp}.xlsx`,
      leadPoolWorkbook([
        validRow,
        [`LIMP-BAD-${stamp}`, '', '', '', '', 'event', '', 20, 'new', ''],
      ]),
    );
    await expect(page.getByText(/预检完成，未写入线索数据|Pre-check completed/)).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByText(/Lead company is required/)).toBeVisible();
    expect(
      (await listRows(page, 'crm_lead_common')).some((row) => row.crm_lead_code === validCode),
    ).toBe(false);
    await attachScreenshot(page, testInfo, 'lead-pool-import-precheck-zero-write');
    await closeAsyncTaskModal(page);

    poolRow = page.getByRole('row', { name: new RegExp(poolName) });
    await runImportAction(
      page,
      poolRow,
      'import_leads',
      'crm:import_lead_pool_leads',
      `lead-pool-import-${stamp}.xlsx`,
      leadPoolWorkbook([validRow]),
    );
    await expect(page.getByText(/线索池导入完成|Lead pool import completed/)).toBeVisible({
      timeout: 30_000,
    });
    const imported = (await listRows(page, 'crm_lead_common')).find(
      (row) => row.crm_lead_code === validCode,
    );
    expect(imported?.crm_lead_pool_state).toBe('in_pool');
    expect(imported?.crm_lead_assigned_to ?? '').toBeFalsy();
    const importedPid = String(imported?.pid ?? '');
    const projection = (await listRows(page, 'crm_lead_pool_item_common')).find(
      (row) => row.crm_lpi_lead_id === importedPid,
    );
    expect(projection?.crm_lpi_pool_id).toBe(poolPid);
    expect(projection?.crm_lpi_company).toBe(validCompany);
    await attachScreenshot(page, testInfo, 'lead-pool-import-formal-success');

    await testInfo.attach('crm-lead-pool-import-cordys-source-evidence.json', {
      body: Buffer.from(
        JSON.stringify(
          {
            verdict: 'pass',
            sourceIds: ['api:clue:pool-clue:pre-check', 'api:clue:pool-clue:real-import'],
            assertionScope:
              'real UI upload, asynchronous precheck zero-write, formal import and persisted lead/pool projection',
          },
          null,
          2,
        ),
      ),
      contentType: 'application/json',
    });
  });
});
