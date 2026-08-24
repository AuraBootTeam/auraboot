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

const MODEL = 'crm_lead_common';
const SOURCE_IDS = [
  'api:clue:clue:get-module-form-config',
  'api:clue:clue:list',
  'api:clue:clue:update',
  'api:clue:clue:delete',
  'api:clue:clue:batch-update',
  'api:clue:clue:batch-delete',
  'api:clue:clue:batch-transfer',
  'api:clue:clue:batch-to-pool',
  'api:clue:clue:to-pool',
  'api:clue:clue:transition-customer-page',
  'api:clue:clue:batch-transition',
  'api:clue:clue:get-tab-enable-config',
  'api:clue:clue:export-all',
  'api:clue:clue:export-select',
  'api:clue:clue:chart',
  'route:web:clue:2',
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

async function openLeadsFromMenu(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const link = page.locator('nav a[href="/p/crm_lead_common"]').first();
  await expect(link).toBeVisible({ timeout: 15_000 });
  const listResponse = page.waitForResponse(
    (response) => response.url().includes(`/api/dynamic/${MODEL}/list`) && response.ok(),
  );
  await link.click();
  await listResponse;
  await expect(page).toHaveURL(new RegExp(`/p/${MODEL}$`));
  await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 15_000 });
  completed.add('api:clue:clue:list');
  completed.add('route:web:clue:2');
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

function row(page: Page, company: string) {
  return page.locator('tbody tr').filter({ hasText: company });
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

test.describe.serial('CRM lead management — Cordys PAR-03 source-bound parity', () => {
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
      path.join(evidenceRoot, `crm-lead-management-${Date.now()}.json`),
      `${JSON.stringify(
        {
          schemaVersion: 1,
          runId: process.env.CRM_LEAD_MANAGEMENT_RUN_ID || `crm-lead-management-${Date.now()}`,
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

  test('menu list, form contract, edit, analysis and full export are executable @critical @golden', async ({
    page,
  }, testInfo) => {
    trackRuntimeFailures(page);
    const uid = uniqueId('crm_lead_manage');
    const original = `Lead Manage ${uid}`;
    const updated = `Lead Updated ${uid}`;
    const created = await executeCommandViaApi(
      page,
      'crm:create_lead',
      {
        crm_lead_company: original,
        crm_lead_contact_name: `Contact ${uid}`,
        crm_lead_contact_phone: '13800002222',
        crm_lead_source: 'website',
        crm_lead_score: 25,
      },
      undefined,
      'create',
    );

    await openLeadsFromMenu(page);
    for (const tab of ['all', 'new', 'contacted', 'qualified', 'converted', 'lost']) {
      await expect(page.getByTestId(`tab-${tab}`)).toBeVisible();
    }
    completed.add('api:clue:clue:get-tab-enable-config');
    await search(page, uid);
    await expect(row(page, original)).toHaveCount(1);

    await clickRowActionByLocator(page, row(page, original), 'edit', '编辑');
    await expect(page).toHaveURL(new RegExp(`/p/${MODEL}/edit/${created.recordId}$`));
    await waitForFormReady(page, 15_000);
    for (const field of [
      'crm_lead_company',
      'crm_lead_contact_name',
      'crm_lead_contact_phone',
      'crm_lead_contact_email',
      'crm_lead_source',
      'crm_lead_score',
      'crm_lead_requirement',
    ]) {
      await expect(page.getByTestId(`form-field-${field}`)).toBeVisible();
    }
    completed.add('api:clue:clue:get-module-form-config');
    await page.getByTestId('form-field-crm_lead_company').locator('input').fill(updated);
    const updateResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/crm:update_lead') && response.ok(),
    );
    await page.getByTestId('form-btn-submit').click();
    await updateResponse;
    await openLeadsFromMenu(page);
    await search(page, uid);
    await expect(row(page, updated)).toHaveCount(1);
    expect((await readRecord(page, created.recordId))?.crm_lead_company).toBe(updated);
    completed.add('api:clue:clue:update');

    await page.getByTestId('view-analysis-open').click();
    const analysis = page.getByTestId('view-analysis-drawer');
    await expect(analysis).toBeVisible();
    await expect(analysis.getByTestId('view-analysis-error')).toHaveCount(0);
    await expect(
      analysis.locator('[data-testid^="view-analysis-breakdown-"]').first(),
    ).toBeVisible();
    await analysis.getByTestId('view-analysis-chart-donut').click();
    await expect(analysis.locator('canvas').first()).toBeVisible();
    completed.add('api:clue:clue:chart');
    await shot(page, testInfo, '01-lead-list-analysis');
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
    const exportPath = testInfo.outputPath('all-matching-leads.xlsx');
    await download.saveAs(exportPath);
    const workbook = XLSX.read(fs.readFileSync(exportPath), { type: 'buffer' });
    expect(
      JSON.stringify(XLSX.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]])),
    ).toContain(updated);
    completed.add('api:clue:clue:export-all');
  });

  test('selected batch update/export/delete and row delete persist exact outcomes @critical @golden', async ({
    page,
  }, testInfo) => {
    trackRuntimeFailures(page);
    const uid = uniqueId('crm_lead_batch');
    const companies = [`Lead Batch A ${uid}`, `Lead Batch B ${uid}`, `Lead Row Delete ${uid}`];
    const records = await Promise.all(
      companies.map((company) =>
        executeCommandViaApi(
          page,
          'crm:create_lead',
          {
            crm_lead_company: company,
            crm_lead_contact_name: `Contact ${uid}`,
            crm_lead_source: 'website',
            crm_lead_score: 20,
          },
          undefined,
          'create',
        ),
      ),
    );

    await openLeadsFromMenu(page);
    await search(page, uid);
    for (const company of companies.slice(0, 2)) {
      await row(page, company).locator('input[type="checkbox"]').check();
    }
    await page.getByTestId('bulk-edit-btn').click();
    const edit = page.getByTestId('bulk-edit-dialog');
    await edit.getByTestId('bulk-edit-field').selectOption('crm_lead_score');
    await edit.getByTestId('bulk-edit-value').fill('66');
    const batchUpdate = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        response.url().includes(`/api/dynamic/${MODEL}/batch`),
    );
    await edit.getByRole('button', { name: /更新 2 条记录|Update 2 records/ }).click();
    expect((await batchUpdate).ok()).toBe(true);
    expect(Number((await readRecord(page, records[0].recordId))?.crm_lead_score)).toBe(66);
    expect(Number((await readRecord(page, records[1].recordId))?.crm_lead_score)).toBe(66);
    completed.add('api:clue:clue:batch-update');

    for (const company of companies.slice(0, 2)) {
      await row(page, company).locator('input[type="checkbox"]').check();
    }
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
    const exportPath = testInfo.outputPath('selected-leads.xlsx');
    await download.saveAs(exportPath);
    const workbook = XLSX.read(fs.readFileSync(exportPath), { type: 'buffer' });
    const text = JSON.stringify(XLSX.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]]));
    expect(text).toContain(companies[0]);
    expect(text).toContain(companies[1]);
    expect(text).not.toContain(companies[2]);
    completed.add('api:clue:clue:export-select');
    await page.getByTestId('bulk-clear-selection-btn').click();

    for (const company of companies.slice(0, 2)) {
      await row(page, company).locator('input[type="checkbox"]').check();
    }
    await page.getByTestId('bulk-more-actions-btn').click();
    await page.getByTestId('bulk-action-bulk_delete_leads').click();
    await page
      .getByRole('dialog')
      .getByRole('button', { name: /确认|确定|Confirm|Delete/ })
      .last()
      .click();
    await expect(row(page, companies[0])).toHaveCount(0);
    await expect(row(page, companies[1])).toHaveCount(0);
    expect(await readRecord(page, records[0].recordId)).toBeNull();
    expect(await readRecord(page, records[1].recordId)).toBeNull();
    completed.add('api:clue:clue:batch-delete');

    await search(page, uid);
    await clickRowActionByLocator(page, row(page, companies[2]), 'delete', '删除');
    await page
      .getByRole('dialog')
      .getByRole('button', { name: /确认|确定|Confirm|Delete/ })
      .last()
      .click();
    await expect(row(page, companies[2])).toHaveCount(0);
    expect(await readRecord(page, records[2].recordId)).toBeNull();
    completed.add('api:clue:clue:delete');
    await shot(page, testInfo, '02-lead-batch-and-row-delete-complete');
  });

  test('owner transfer, lifecycle batch, pool move and customer-only conversion persist @critical @golden', async ({
    page,
  }, testInfo) => {
    trackRuntimeFailures(page);
    const uid = uniqueId('crm_lead_governed');
    const companies = {
      transferA: `Lead Transfer A ${uid}`,
      transferB: `Lead Transfer B ${uid}`,
      poolA: `Lead Pool A ${uid}`,
      poolB: `Lead Pool B ${uid}`,
      singlePool: `Lead Pool Single ${uid}`,
      customer: `Lead Customer Only ${uid}`,
    };
    const ids: Record<string, string> = {};
    for (const [key, company] of Object.entries(companies)) {
      const created = await executeCommandViaApi(
        page,
        'crm:create_lead',
        {
          crm_lead_company: company,
          crm_lead_contact_name: `Contact ${uid}`,
          crm_lead_contact_phone: '13800003333',
          crm_lead_source: 'website',
          crm_lead_score: 30,
        },
        undefined,
        'create',
      );
      ids[key] = created.recordId;
    }
    const meResponse = await page.request.get('/api/auth/me');
    expect(meResponse.ok()).toBe(true);
    const currentAdminPid = String((await meResponse.json())?.data?.user?.pid ?? '');
    expect(currentAdminPid).not.toBe('');
    const pool = await executeCommandViaApi(
      page,
      'crm:create_lead_pool',
      {
        crm_lp_name: `Lead Pool ${uid}`,
        crm_lp_member_user_ids: JSON.stringify([currentAdminPid]),
        crm_lp_admin_user_ids: JSON.stringify([currentAdminPid]),
        crm_lp_daily_pick_limit: 20,
        crm_lp_new_cooldown_days: 0,
        crm_lp_previous_owner_cooldown_days: 0,
        crm_lp_auto_recycle: false,
        crm_lp_recycle_match_mode: 'any',
      },
      undefined,
      'create',
    );

    await openLeadsFromMenu(page);
    await search(page, uid);
    for (const company of [companies.transferA, companies.transferB]) {
      await row(page, company).locator('input[type="checkbox"]').check();
    }
    await clickBulkAction(page, 'bulk_transfer_owner');
    const transfer = page.getByTestId('bulk-field-command-dialog');
    await transfer.getByTestId('member-picker-add').click();
    await transfer.getByTestId('member-picker-search-input').fill('admin@auraboot.com');
    const adminOption = transfer.locator('[data-testid^="member-picker-option-"]').first();
    await expect(adminOption).toBeVisible();
    const adminPid = String(
      (await adminOption.getAttribute('data-testid'))?.replace('member-picker-option-', ''),
    );
    await adminOption.click();
    await transfer.getByTestId('bulk-field-command-submit').click();
    await expect(transfer).toHaveCount(0);
    expect((await readRecord(page, ids.transferA))?.crm_lead_assigned_to).toBe(adminPid);
    expect((await readRecord(page, ids.transferB))?.crm_lead_assigned_to).toBe(adminPid);
    completed.add('api:clue:clue:batch-transfer');

    for (const company of [companies.transferA, companies.transferB]) {
      await row(page, company).locator('input[type="checkbox"]').check();
    }
    await clickBulkAction(page, 'bulk_contact');
    await expect
      .poll(async () => [
        (await readRecord(page, ids.transferA))?.crm_lead_status,
        (await readRecord(page, ids.transferB))?.crm_lead_status,
      ])
      .toEqual(['contacted', 'contacted']);
    completed.add('api:clue:clue:batch-transition');

    for (const company of [companies.poolA, companies.poolB]) {
      await row(page, company).locator('input[type="checkbox"]').check();
    }
    await clickBulkAction(page, 'bulk_move_to_pool');
    const poolDialog = page.getByTestId('bulk-field-command-dialog');
    await poolDialog.getByTestId('select-trigger-crm_lead_last_pool_id').click();
    await page.getByRole('option', { name: `Lead Pool ${uid}`, exact: true }).click();
    await poolDialog.getByTestId('bulk-field-command-submit').click();
    await expect(poolDialog).toHaveCount(0);
    for (const key of ['poolA', 'poolB']) {
      const record = await readRecord(page, ids[key]);
      expect(record?.crm_lead_pool_state).toBe('in_pool');
      expect(record?.crm_lead_last_pool_id).toBe(pool.recordId);
    }
    completed.add('api:clue:clue:batch-to-pool');

    await search(page, companies.singlePool);
    await clickRowActionByLocator(
      page,
      row(page, companies.singlePool),
      'move_to_pool',
      '移入线索池',
    );
    const singlePoolDialog = page.getByRole('dialog');
    await singlePoolDialog
      .getByTestId('form-dialog-field-crm_lead_last_pool_id')
      .selectOption({ label: `Lead Pool ${uid}` });
    await singlePoolDialog
      .getByRole('button', { name: /确认|确定|Confirm|Apply/ })
      .last()
      .click();
    await expect
      .poll(async () => (await readRecord(page, ids.singlePool))?.crm_lead_pool_state)
      .toBe('in_pool');
    completed.add('api:clue:clue:to-pool');

    await executeCommandViaApi(page, 'crm:qualify_lead', {}, ids.customer, 'update');
    await search(page, companies.customer);
    const conversionResponse = page.waitForResponse((response) =>
      response.url().includes('/api/meta/commands/execute/crm:convert_lead_to_customer'),
    );
    await clickRowActionByLocator(
      page,
      row(page, companies.customer),
      'convert_to_customer',
      '转为客户',
    );
    await page
      .getByRole('dialog')
      .getByRole('button', { name: /确认|确定|Confirm/ })
      .last()
      .click();
    const conversion = await conversionResponse;
    expect(conversion.ok()).toBe(true);
    const conversionBody = (await conversion.json())?.data?.data ?? {};
    expect(String(conversionBody.accountId ?? '')).not.toBe('');
    expect(String(conversionBody.contactId ?? '')).not.toBe('');
    expect(String(conversionBody.opportunityId ?? '')).toBe('');
    expect(String(conversionBody.customerRequestId ?? '')).toBe('');
    expect((await readRecord(page, ids.customer))?.crm_lead_status).toBe('converted');
    completed.add('api:clue:clue:transition-customer-page');
    await shot(page, testInfo, '03-lead-governed-operations-complete');
  });
});
