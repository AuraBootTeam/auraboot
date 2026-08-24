import { expect, test, type Page } from '../../fixtures';
import type { TestInfo } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import * as XLSX from 'xlsx';
import {
  clickRowActionByLocator,
  executeCommandViaApi,
  uniqueId,
  waitForFormReady,
} from '../helpers';

const MODEL = 'crm_account_common';
const SOURCE_IDS = [
  'api:customer:customer:get-module-form-config',
  'api:customer:customer:list',
  'api:customer:customer:get',
  'api:customer:customer:add',
  'api:customer:customer:update',
  'api:customer:customer:delete',
  'api:customer:customer:batch-transfer',
  'api:customer:customer:batch-update',
  'api:customer:customer:batch-delete',
  'api:customer:customer:batch-to-pool',
  'api:customer:customer:to-pool',
  'api:customer:customer:get-customer-options',
  'api:customer:customer:get-tab-enable-config',
  'api:customer:customer:opportunity-export-all',
  'api:customer:customer:opportunity-export-select',
  'api:customer:customer:chart',
  'route:web:customer:10',
] as const;
const completed = new Set<string>();
const screenshots: string[] = [];
const failedRuntimeRequests: string[] = [];

function trackRuntimeFailures(page: Page): void {
  page.on('response', (response) => {
    if (response.status() >= 500 && response.url().includes('/api/')) {
      failedRuntimeRequests.push(
        `${response.request().method()} ${response.url()} HTTP ${response.status()}`,
      );
    }
  });
}

async function openAccountsFromMenu(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const link = page.locator('nav a[href="/p/crm_account_common"]').first();
  await expect(link).toBeVisible({ timeout: 15_000 });
  const response = page.waitForResponse(
    (candidate) => candidate.url().includes(`/api/dynamic/${MODEL}/list`) && candidate.ok(),
    { timeout: 15_000 },
  );
  await link.click();
  await response;
  await expect(page).toHaveURL(new RegExp(`/p/${MODEL}$`));
  await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 15_000 });
  completed.add('api:customer:customer:list');
  completed.add('route:web:customer:10');
}

async function search(page: Page, keyword: string): Promise<void> {
  const input = page
    .locator(
      '[data-testid="list-search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]',
    )
    .first();
  const response = page.waitForResponse(
    (candidate) => candidate.url().includes(`/api/dynamic/${MODEL}/list`) && candidate.ok(),
  );
  await input.fill(keyword);
  await input.press('Enter');
  await response;
}

function row(page: Page, name: string) {
  return page.locator('tbody tr').filter({ hasText: name });
}

async function clickBulkAction(page: Page, code: string): Promise<void> {
  const action = page.getByTestId(`bulk-action-${code}`);
  if (!(await action.isVisible().catch(() => false))) {
    await page.getByTestId('bulk-more-actions-btn').click();
  }
  await action.click();
}

async function shot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const output = testInfo.outputPath(`${name}.png`);
  await page.screenshot({ path: output, fullPage: true });
  screenshots.push(output);
  await testInfo.attach(name, { path: output, contentType: 'image/png' });
}

async function readRecord(page: Page, pid: string): Promise<Record<string, unknown> | null> {
  const response = await page.request.get(`/api/dynamic/${MODEL}/${pid}`);
  if (!response.ok()) return null;
  const body = await response.json();
  return body?.data ?? null;
}

async function createAccount(page: Page, name: string, uid: string) {
  const created = await executeCommandViaApi(
    page,
    'crm:create_account',
    {
      crm_acc_name: name,
      crm_acc_industry: 'technology',
      crm_acc_rating: 'A',
      crm_acc_status: 'active',
      crm_acc_phone: '13800004444',
      crm_acc_remark: `PAR-05 account management ${uid}`,
    },
    undefined,
    'create',
  );
  completed.add('api:customer:customer:add');
  return created;
}

test.describe.serial('CRM account management — Cordys PAR-05 source-bound parity', () => {
  test.setTimeout(180_000);

  test.afterAll(() => {
    const evidenceRoot = process.env.AURA_EVIDENCE_ROOT;
    if (!evidenceRoot) return;
    const rows = SOURCE_IDS.map((sourceId) => ({
      sourceId,
      verdict: completed.has(sourceId) ? 'pass' : 'untested',
    }));
    const verdict =
      rows.every((item) => item.verdict === 'pass') && failedRuntimeRequests.length === 0
        ? 'pass'
        : 'fail';
    fs.mkdirSync(evidenceRoot, { recursive: true });
    fs.writeFileSync(
      path.join(evidenceRoot, `crm-account-management-${Date.now()}.json`),
      `${JSON.stringify(
        {
          schemaVersion: 1,
          runId:
            process.env.CRM_ACCOUNT_MANAGEMENT_RUN_ID || `crm-account-management-${Date.now()}`,
          runtime: process.env.AURA_RUNTIME_NAME || null,
          verdict,
          technicalVerdict: verdict,
          dataMigration: 'not-required-development-stage',
          runner: { browser: 'chromium', workers: 1, retries: 0 },
          sourceIds: rows,
          screenshots,
          failedRuntimeRequests,
          productOwnerScreenshotSignOff: 'pending-human-signature',
        },
        null,
        2,
      )}\n`,
    );
  });

  test('menu, form, detail, edit, analysis and full export persist @critical @golden', async ({
    page,
  }, testInfo) => {
    trackRuntimeFailures(page);
    const uid = uniqueId('crm_account_manage');
    const original = `Account Manage ${uid}`;
    const updated = `Account Updated ${uid}`;
    const created = await createAccount(page, original, uid);

    await openAccountsFromMenu(page);
    for (const tab of ['all', 'active', 'inactive']) {
      await expect(page.getByTestId(`tab-${tab}`)).toBeVisible();
    }
    completed.add('api:customer:customer:get-tab-enable-config');
    await search(page, uid);
    await expect(row(page, original)).toHaveCount(1);

    const optionsResponse = await page.request.get(
      `/api/dynamic/${MODEL}/list?pageNum=1&pageSize=20&keyword=${encodeURIComponent(uid)}`,
    );
    expect(optionsResponse.ok()).toBe(true);
    expect(JSON.stringify(await optionsResponse.json())).toContain(original);
    completed.add('api:customer:customer:get-customer-options');

    await clickRowActionByLocator(page, row(page, original), 'view', '查看');
    await expect(page).toHaveURL(new RegExp(`/p/${MODEL}/view/${created.recordId}$`));
    await expect(page.getByRole('tab', { name: /概览|Overview/i })).toBeVisible();
    expect((await readRecord(page, created.recordId))?.crm_acc_name).toBe(original);
    completed.add('api:customer:customer:get');

    await page
      .getByRole('button', { name: /编辑|Edit/i })
      .first()
      .click();
    await expect(page).toHaveURL(new RegExp(`/p/${MODEL}/edit/${created.recordId}$`));
    await waitForFormReady(page, 15_000);
    for (const field of [
      'crm_acc_name',
      'crm_acc_industry',
      'crm_acc_website',
      'crm_acc_phone',
      'crm_acc_address',
      'crm_acc_rating',
      'crm_acc_status',
      'crm_acc_remark',
    ]) {
      await expect(page.getByTestId(`form-field-${field}`)).toBeVisible();
    }
    completed.add('api:customer:customer:get-module-form-config');
    await page.getByTestId('form-field-crm_acc_name').locator('input').fill(updated);
    const updateResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/crm:update_account') && response.ok(),
    );
    await page.getByTestId('form-btn-submit').click();
    await updateResponse;
    expect((await readRecord(page, created.recordId))?.crm_acc_name).toBe(updated);
    completed.add('api:customer:customer:update');

    await openAccountsFromMenu(page);
    await search(page, uid);
    await page.getByTestId('view-analysis-open').click();
    const analysis = page.getByTestId('view-analysis-drawer');
    await expect(analysis).toBeVisible();
    await expect(analysis.getByTestId('view-analysis-error')).toHaveCount(0);
    await expect(
      analysis.locator('[data-testid^="view-analysis-breakdown-"]').first(),
    ).toBeVisible();
    completed.add('api:customer:customer:chart');
    await shot(page, testInfo, '01-account-list-analysis');
    await analysis.getByTestId('view-analysis-close').click();

    const exportResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes(`/api/dynamic/${MODEL}/export`),
    );
    const downloadPromise = page.waitForEvent('download');
    await page.getByTestId('toolbar-more-menu').click();
    await page.getByTestId('more-menu-export-excel').click();
    const [exported, download] = await Promise.all([exportResponse, downloadPromise]);
    expect(exported.ok()).toBe(true);
    const exportPath = testInfo.outputPath('all-matching-accounts.xlsx');
    await download.saveAs(exportPath);
    const workbook = XLSX.read(fs.readFileSync(exportPath), { type: 'buffer' });
    expect(
      JSON.stringify(XLSX.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]])),
    ).toContain(updated);
    completed.add('api:customer:customer:opportunity-export-all');
  });

  test('batch update/export/delete and row delete persist exact outcomes @critical @golden', async ({
    page,
  }, testInfo) => {
    trackRuntimeFailures(page);
    const uid = uniqueId('crm_account_batch');
    const names = [`Account Batch A ${uid}`, `Account Batch B ${uid}`, `Account Row Delete ${uid}`];
    const records = await Promise.all(names.map((name) => createAccount(page, name, uid)));

    await openAccountsFromMenu(page);
    await search(page, uid);
    for (const name of names.slice(0, 2))
      await row(page, name).locator('input[type="checkbox"]').check();
    await clickBulkAction(page, 'bulk_update_industry');
    const edit = page.getByTestId('bulk-field-command-dialog');
    await edit.getByTestId('select-trigger-crm_acc_industry').click();
    await page.getByRole('option', { name: /制造业|Manufacturing/i }).click();
    await edit.getByTestId('bulk-field-command-submit').click();
    await expect(edit).toHaveCount(0);
    expect((await readRecord(page, records[0].recordId))?.crm_acc_industry).toBe('manufacturing');
    expect((await readRecord(page, records[1].recordId))?.crm_acc_industry).toBe('manufacturing');
    completed.add('api:customer:customer:batch-update');

    for (const name of names.slice(0, 2))
      await row(page, name).locator('input[type="checkbox"]').check();
    const exportResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes(`/api/dynamic/${MODEL}/export`),
    );
    const downloadPromise = page.waitForEvent('download');
    await page.getByTestId('bulk-more-actions-btn').click();
    await page.getByTestId('bulk-export-selected-btn').click();
    const [exported, download] = await Promise.all([exportResponse, downloadPromise]);
    expect((await exported.json()).data.recordCount).toBe(2);
    const exportPath = testInfo.outputPath('selected-accounts.xlsx');
    await download.saveAs(exportPath);
    const workbook = XLSX.read(fs.readFileSync(exportPath), { type: 'buffer' });
    const text = JSON.stringify(XLSX.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]]));
    expect(text).toContain(names[0]);
    expect(text).toContain(names[1]);
    expect(text).not.toContain(names[2]);
    completed.add('api:customer:customer:opportunity-export-select');
    await page.getByTestId('bulk-clear-selection-btn').click();

    for (const name of names.slice(0, 2))
      await row(page, name).locator('input[type="checkbox"]').check();
    await clickBulkAction(page, 'bulk_delete_accounts');
    await page
      .getByRole('dialog')
      .getByRole('button', { name: /确认|确定|Confirm|Delete/ })
      .last()
      .click();
    await expect(row(page, names[0])).toHaveCount(0);
    await expect(row(page, names[1])).toHaveCount(0);
    expect(await readRecord(page, records[0].recordId)).toBeNull();
    expect(await readRecord(page, records[1].recordId)).toBeNull();
    completed.add('api:customer:customer:batch-delete');

    await search(page, uid);
    await clickRowActionByLocator(page, row(page, names[2]), 'delete', '删除');
    await page
      .getByRole('dialog')
      .getByRole('button', { name: /确认|确定|Confirm|Delete/ })
      .last()
      .click();
    await expect(row(page, names[2])).toHaveCount(0);
    expect(await readRecord(page, records[2].recordId)).toBeNull();
    completed.add('api:customer:customer:delete');
    await shot(page, testInfo, '02-account-batch-and-row-delete-complete');
  });

  test('owner transfer and governed customer-pool moves persist @critical @golden', async ({
    page,
  }, testInfo) => {
    trackRuntimeFailures(page);
    const uid = uniqueId('crm_account_governed');
    const names = {
      transferA: `Account Transfer A ${uid}`,
      transferB: `Account Transfer B ${uid}`,
      poolA: `Account Pool A ${uid}`,
      poolB: `Account Pool B ${uid}`,
      singlePool: `Account Pool Single ${uid}`,
    };
    const ids: Record<string, string> = {};
    for (const [key, name] of Object.entries(names))
      ids[key] = (await createAccount(page, name, uid)).recordId;

    const transferEmail = `crm-transfer-${uid.slice(-12)}@e2e.local`;
    const userResponse = await page.request.post('/api/admin/users', {
      data: {
        email: transferEmail,
        displayName: `Account Transfer ${uid.slice(-8)}`,
        initialPassword: 'Test2026x',
        roleCodes: ['crm_sales'],
        sendInviteEmail: false,
      },
    });
    expect(userResponse.ok()).toBe(true);
    const userBody = await userResponse.json();
    const transferPid = String(userBody?.data?.pid ?? userBody?.data?.userPid ?? '');
    expect(transferPid).not.toBe('');
    const meResponse = await page.request.get('/api/auth/me');
    const currentAdminPid = String((await meResponse.json())?.data?.user?.pid ?? '');
    const pool = await executeCommandViaApi(
      page,
      'crm:create_customer_pool',
      {
        crm_cp_name: `Customer Pool ${uid}`,
        crm_cp_member_user_ids: JSON.stringify([currentAdminPid]),
        crm_cp_admin_user_ids: JSON.stringify([currentAdminPid]),
        crm_cp_daily_pick_limit: 20,
        crm_cp_new_cooldown_days: 0,
        crm_cp_previous_owner_cooldown_days: 0,
        crm_cp_auto_recycle: false,
        crm_cp_recycle_match_mode: 'any',
      },
      undefined,
      'create',
    );

    await openAccountsFromMenu(page);
    await search(page, uid);
    for (const name of [names.transferA, names.transferB])
      await row(page, name).locator('input[type="checkbox"]').check();
    await clickBulkAction(page, 'bulk_transfer_owner');
    const transfer = page.getByTestId('bulk-field-command-dialog');
    await transfer.getByTestId('member-picker-add').click();
    await transfer.getByTestId('member-picker-search-input').fill(transferEmail);
    await transfer.getByTestId(`member-picker-option-${transferPid}`).click();
    await transfer.getByTestId('bulk-field-command-submit').click();
    await expect(transfer).toHaveCount(0);
    expect((await readRecord(page, ids.transferA))?.crm_acc_owner).toBe(transferPid);
    expect((await readRecord(page, ids.transferB))?.crm_acc_owner).toBe(transferPid);
    completed.add('api:customer:customer:batch-transfer');

    for (const name of [names.poolA, names.poolB])
      await row(page, name).locator('input[type="checkbox"]').check();
    await clickBulkAction(page, 'bulk_move_to_customer_pool');
    const poolDialog = page.getByTestId('bulk-field-command-dialog');
    await poolDialog.getByTestId('select-trigger-crm_acc_last_pool_id').click();
    await page.getByRole('option', { name: `Customer Pool ${uid}`, exact: true }).click();
    await poolDialog.getByTestId('bulk-field-command-submit').click();
    await expect(poolDialog).toHaveCount(0);
    for (const key of ['poolA', 'poolB']) {
      const record = await readRecord(page, ids[key]);
      expect(record?.crm_acc_pool_state).toBe('in_pool');
      expect(record?.crm_acc_last_pool_id).toBe(pool.recordId);
    }
    completed.add('api:customer:customer:batch-to-pool');

    await search(page, names.singlePool);
    await clickRowActionByLocator(
      page,
      row(page, names.singlePool),
      'move_to_customer_pool',
      '移入客户公海',
    );
    const singleDialog = page.getByRole('dialog');
    await singleDialog.locator('select').selectOption({ label: `Customer Pool ${uid}` });
    const singleMoveResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes('/api/meta/commands/execute/crm:move_customer_to_pool'),
    );
    await singleDialog
      .getByRole('button', { name: /确认|确定|Submit|Move/ })
      .last()
      .click();
    expect((await singleMoveResponse).ok()).toBe(true);
    await expect(singleDialog).toHaveCount(0);
    await expect
      .poll(async () => (await readRecord(page, ids.singlePool))?.crm_acc_pool_state)
      .toBe('in_pool');
    const single = await readRecord(page, ids.singlePool);
    expect(single?.crm_acc_pool_state).toBe('in_pool');
    expect(single?.crm_acc_last_pool_id).toBe(pool.recordId);
    completed.add('api:customer:customer:to-pool');
    await shot(page, testInfo, '03-account-owner-and-pool-operations-complete');
  });
});
