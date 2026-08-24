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

const CONTACT = 'crm_contact_common';
const EXPECTED_SCENARIOS = ['contact-form-detail-analysis-export', 'contact-list-bulk-governance'];
const CONTACT_MANAGEMENT_COVERAGE = {
  pages: ['crm_contact_common_list', 'crm_contact_common_form', 'crm_contact_common_detail'],
  blocks: [
    'crm_contact_common_list:crm_contact_toolbar',
    'crm_contact_common_list:crm_contact_table',
    'crm_contact_common_form:identity_and_ownership',
    'crm_contact_common_form:channels_and_notes',
    'crm_contact_common_form:buttons',
    'crm_contact_common_detail:section_basic',
    'crm_contact_common_detail:crm_contact_detail_toolbar',
  ],
  fields: [
    'crm_contact_common_list:crm_contact_table:crm_ct_name',
    'crm_contact_common_list:crm_contact_table:crm_ct_account_id',
    'crm_contact_common_list:crm_contact_table:crm_ct_title',
    'crm_contact_common_list:crm_contact_table:crm_ct_email',
    'crm_contact_common_list:crm_contact_table:crm_ct_phone',
    'crm_contact_common_list:crm_contact_table:crm_ct_mobile',
    'crm_contact_common_form:identity_and_ownership:crm_ct_account_id',
    'crm_contact_common_form:identity_and_ownership:crm_ct_name',
    'crm_contact_common_form:identity_and_ownership:crm_ct_title',
    'crm_contact_common_form:identity_and_ownership:crm_ct_owner',
    'crm_contact_common_form:channels_and_notes:crm_ct_email',
    'crm_contact_common_form:channels_and_notes:crm_ct_phone',
    'crm_contact_common_form:channels_and_notes:crm_ct_mobile',
    'crm_contact_common_form:channels_and_notes:crm_ct_is_primary',
    'crm_contact_common_form:channels_and_notes:crm_ct_remark',
    'crm_contact_common_detail:section_basic:crm_ct_account_id',
    'crm_contact_common_detail:section_basic:crm_ct_name',
    'crm_contact_common_detail:section_basic:crm_ct_title',
    'crm_contact_common_detail:section_basic:crm_ct_email',
    'crm_contact_common_detail:section_basic:crm_ct_phone',
    'crm_contact_common_detail:section_basic:crm_ct_mobile',
    'crm_contact_common_detail:section_basic:crm_ct_remark',
  ],
  uiActions: [
    'crm_contact_common_list:crm_contact_toolbar:create',
    'crm_contact_common_list:crm_contact_table:bulk_delete_contacts',
    'crm_contact_common_list:crm_contact_table:view',
    'crm_contact_common_list:crm_contact_table:edit',
    'crm_contact_common_form:buttons:submit',
    'crm_contact_common_form:buttons:cancel',
    'crm_contact_common_detail:crm_contact_detail_toolbar:edit',
  ],
  commands: [
    'crm:create_account',
    'crm:create_contact',
    'crm:update_contact',
    'crm:delete_contact',
  ],
};
const completedScenarios: string[] = [];
const evidenceScreenshots: string[] = [];
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

async function openContactsFromMenu(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const link = page.locator('nav a[href="/p/crm_contact_common"]').first();
  await expect(link).toBeVisible({ timeout: 15_000 });
  await link.click();
  await expect(page).toHaveURL(new RegExp(`/p/${CONTACT}$`), { timeout: 15_000 });
  await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 15_000 });
}

async function search(page: Page, keyword: string): Promise<void> {
  const input = page
    .locator(
      '[data-testid="list-search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]',
    )
    .first();
  await expect(input).toBeVisible();
  const response = page.waitForResponse(
    (candidate) => candidate.url().includes(`/api/dynamic/${CONTACT}/list`) && candidate.ok(),
  );
  await input.fill(keyword);
  await input.press('Enter');
  await response;
}

function row(page: Page, name: string) {
  return page.locator('tbody tr').filter({ hasText: name });
}

async function selectSingleOption(page: Page, field: string, label: string): Promise<void> {
  await page.getByTestId(`select-trigger-${field}`).click();
  const searchInput = page.getByTestId(`select-search-${field}`);
  await expect(searchInput).toBeVisible();
  await searchInput.fill(label);
  await page.getByRole('option', { name: label, exact: true }).click();
}

async function attachScreenshot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const path = testInfo.outputPath(`${name}.png`);
  await page.screenshot({ path, fullPage: true });
  evidenceScreenshots.push(path);
  await testInfo.attach(name, { path, contentType: 'image/png' });
}

async function commandFailure(
  page: Page,
  command: string,
  payload: Record<string, unknown>,
  targetRecordPid?: string,
): Promise<{ status: number; body: Record<string, unknown> }> {
  const response = await page.request.post(`/api/meta/commands/execute/${command}`, {
    data: { payload, targetRecordPid },
  });
  return {
    status: response.status(),
    body: (await response.json().catch(() => ({}))) as Record<string, unknown>,
  };
}

test.describe('CRM contact management — Cordys PAR-07 Web parity', () => {
  test.setTimeout(180_000);

  test.afterAll(() => {
    const evidenceRoot = process.env.AURA_EVIDENCE_ROOT;
    if (!evidenceRoot) return;
    const completed = EXPECTED_SCENARIOS.every((scenario) => completedScenarios.includes(scenario));
    const verdict = completed && failedRuntimeRequests.length === 0 ? 'pass' : 'fail';
    const receipt = {
      runId: `crm-contact-management-${Date.now()}`,
      verdict,
      technicalVerdict: verdict,
      dataMigration: 'out-of-scope-development-stage',
      fixtureMode: 'self-seeded',
      expectedScenarios: EXPECTED_SCENARIOS,
      completedScenarios,
      screenshots: evidenceScreenshots,
      failedRuntimeRequests,
      coverage: Object.fromEntries(
        Object.entries(CONTACT_MANAGEMENT_COVERAGE).map(([axis, expected]) => [
          axis,
          { expected, completed: verdict === 'pass' ? expected : [] },
        ]),
      ),
    };
    fs.mkdirSync(evidenceRoot, { recursive: true });
    fs.writeFileSync(
      path.join(evidenceRoot, `crm-contact-management-${Date.now()}.json`),
      `${JSON.stringify(receipt, null, 2)}\n`,
    );
  });

  test('form, detail, analysis and full export stay reachable from product entries @critical @golden', async ({
    page,
  }, testInfo) => {
    trackRuntimeFailures(page);
    const uid = uniqueId('crm_contact_form');
    const accountName = `Contact Form Account ${uid}`;
    const contactName = `Contact Form Person ${uid}`;
    const account = await executeCommandViaApi(
      page,
      'crm:create_account',
      {
        crm_acc_name: accountName,
        crm_acc_industry: 'manufacturing',
        crm_acc_rating: 'A',
      },
      undefined,
      'create',
    );
    expect(account.recordId).toBeTruthy();

    await openContactsFromMenu(page);
    await page.getByTestId('toolbar-btn-create').click();
    await expect(page).toHaveURL(/\/p\/crm_contact_common\/new$/);
    await waitForFormReady(page, 15_000);

    const requiredFields = ['crm_ct_account_id', 'crm_ct_name', 'crm_ct_owner'];
    const optionalFields = [
      'crm_ct_title',
      'crm_ct_email',
      'crm_ct_phone',
      'crm_ct_mobile',
      'crm_ct_is_primary',
      'crm_ct_remark',
    ];
    for (const field of [...requiredFields, ...optionalFields]) {
      await expect(page.getByTestId(`form-field-${field}`)).toBeVisible();
    }
    await expect(page.getByTestId('form-btn-cancel')).toBeEnabled();
    await page.getByTestId('form-btn-submit').click();
    await expect(page.getByTestId('form-field-crm_ct_account_id')).toContainText(
      /请选择所属客户|account.*required/i,
    );
    await expect(page.getByTestId('form-field-crm_ct_name')).toContainText(
      /请填写联系人姓名|contact name.*required/i,
    );
    await expect(page.getByTestId('form-field-crm_ct_owner')).toContainText(
      /请选择负责人|owner.*required/i,
    );
    await expect(page).toHaveURL(/\/p\/crm_contact_common\/new$/);
    await expect(page.getByTestId('toast-stack').getByRole('alert')).toHaveCount(0);
    await attachScreenshot(page, testInfo, '01-contact-required-validation');

    await selectSingleOption(page, 'crm_ct_account_id', accountName);
    await selectSingleOption(page, 'crm_ct_owner', 'Admin');
    await page.getByTestId('form-field-crm_ct_name').locator('input').fill(contactName);
    await page.getByTestId('form-field-crm_ct_title').locator('input').fill('采购总监');
    await page.getByTestId('form-field-crm_ct_email').locator('input').fill(`${uid}@example.com`);
    await page.getByTestId('form-field-crm_ct_phone').locator('input').fill('0755-12345678');
    await page.getByTestId('form-field-crm_ct_mobile').locator('input').fill('13800001111');
    await page
      .getByTestId('form-field-crm_ct_remark')
      .locator('textarea')
      .fill('关注交期，周二下午便于联系');
    await expect(page.getByTestId('toast-stack').getByRole('alert')).toHaveCount(0);
    await attachScreenshot(page, testInfo, '02-contact-full-form');

    const createResponse = page.waitForResponse(
      (candidate) =>
        candidate.url().includes('/api/meta/commands/execute/crm:create_contact') && candidate.ok(),
    );
    await page.getByTestId('form-btn-submit').click();
    const created = await createResponse;
    const createdBody = await created.json();
    const contactPid = String(createdBody?.data?.data?.recordPid || '');
    expect(contactPid).toBeTruthy();
    await expect(page).toHaveURL(/\/p\/crm_contact_common$/);

    await search(page, uid);
    const createdRow = row(page, contactName);
    await expect(createdRow).toHaveCount(1);
    await expect(createdRow).toContainText(accountName);
    await expect(createdRow).toContainText('采购总监');
    await expect(createdRow).toContainText(`${uid}@example.com`);
    await expect(createdRow).toContainText('0755-12345678');
    await expect(createdRow).toContainText('13800001111');

    await page.getByTestId('view-analysis-open').click();
    const analysis = page.getByTestId('view-analysis-drawer');
    await expect(analysis).toBeVisible();
    await expect(analysis.getByTestId('view-analysis-error')).toHaveCount(0);
    await expect(analysis.locator('[data-testid^="view-analysis-breakdown-"]').first()).toBeVisible(
      { timeout: 15_000 },
    );
    await analysis.getByTestId('view-analysis-chart-donut').click();
    await expect(analysis.getByTestId('view-analysis-chart-donut')).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await expect(analysis.locator('canvas').first()).toBeVisible({ timeout: 10_000 });
    await attachScreenshot(page, testInfo, '03-contact-configurable-analysis');
    await analysis.getByTestId('view-analysis-close').click();

    const fullExportResponse = page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' &&
        candidate.url().includes(`/api/dynamic/${CONTACT}/export`),
    );
    const fullDownload = page.waitForEvent('download');
    await page.getByTestId('toolbar-more-menu').click();
    await page.getByTestId('more-menu-export-excel').click();
    const [exported, download] = await Promise.all([fullExportResponse, fullDownload]);
    expect(exported.ok()).toBe(true);
    const workbookPath = testInfo.outputPath('all-matching-contacts.xlsx');
    await download.saveAs(workbookPath);
    const workbookBytes = fs.readFileSync(workbookPath);
    const workbook = XLSX.read(workbookBytes, { type: 'buffer' });
    const exportedText = JSON.stringify(
      XLSX.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]]),
    );
    expect(exportedText).toContain(contactName);
    expect(exportedText).toContain(accountName);
    await testInfo.attach('all-matching-contacts.xlsx', {
      body: workbookBytes,
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });

    await clickRowActionByLocator(page, createdRow, 'view', '详情');
    await expect(page).toHaveURL(new RegExp(`/p/${CONTACT}/view/${contactPid}$`));
    await expect(page.getByText(contactName, { exact: true })).toBeVisible();
    await expect(page.getByText(accountName, { exact: true })).toBeVisible();
    await expect(page.getByText('采购总监', { exact: true })).toBeVisible();
    await expect(page.getByText(`${uid}@example.com`, { exact: true })).toBeVisible();
    await expect(page.getByText('0755-12345678', { exact: true })).toBeVisible();
    await expect(page.getByText('13800001111', { exact: true })).toBeVisible();
    await expect(page.getByText('关注交期，周二下午便于联系', { exact: true })).toBeVisible();
    await attachScreenshot(page, testInfo, '04-contact-business-detail');

    await page.getByTestId('toolbar-btn-edit').click();
    await expect(page).toHaveURL(new RegExp(`/p/${CONTACT}/edit/${contactPid}$`));
    await waitForFormReady(page, 15_000);
    await page.getByTestId('form-btn-cancel').click();
    await expect(page).toHaveURL(new RegExp(`/p/${CONTACT}/view/${contactPid}$`));
    completedScenarios.push('contact-form-detail-analysis-export');
  });

  test('list, update, bulk edit/export/delete and governed failures @critical @golden', async ({
    page,
  }, testInfo) => {
    trackRuntimeFailures(page);
    const uid = uniqueId('crm_contact_t05');
    const account = await executeCommandViaApi(
      page,
      'crm:create_account',
      {
        crm_acc_name: `T05 Account ${uid}`,
        crm_acc_industry: 'manufacturing',
        crm_acc_rating: 'A',
      },
      undefined,
      'create',
    );
    const opportunity = await executeCommandViaApi(
      page,
      'crm:create_opportunity',
      {
        crm_opp_code: `T05-${uid}`,
        crm_opp_name: `T05 Opportunity ${uid}`,
        crm_opp_account_id: account.recordId,
        crm_opp_stage: 'discovery',
      },
      undefined,
      'create',
    );
    const names = {
      primary: `T05 Primary ${uid}`,
      linked: `T05 Linked ${uid}`,
      editable: `T05 Editable ${uid}`,
      removable: `T05 Removable ${uid}`,
    };
    const contacts = await Promise.all(
      Object.entries(names).map(([key, name], index) =>
        executeCommandViaApi(
          page,
          'crm:create_contact',
          {
            crm_ct_account_id: account.recordId,
            crm_ct_name: name,
            crm_ct_email: `${uid}-${key}@example.com`,
            crm_ct_title: `Title ${index}`,
            crm_ct_is_primary: key === 'primary',
          },
          undefined,
          'create',
        ),
      ),
    );
    const ids = Object.fromEntries(
      Object.keys(names).map((key, index) => [key, contacts[index].recordId]),
    );
    await executeCommandViaApi(
      page,
      'crm:add_opp_contact',
      {
        crm_oc_opportunity_id: opportunity.recordId,
        crm_oc_contact_id: ids.linked,
        crm_oc_role: 'decision_maker',
        crm_oc_is_primary: false,
      },
      undefined,
      'create',
    );

    const missingAccount = await commandFailure(page, 'crm:create_contact', {
      crm_ct_name: `T05 Missing Account ${uid}`,
      crm_ct_email: `${uid}-missing@example.com`,
    });
    expect(missingAccount.status).toBeGreaterThanOrEqual(400);
    expect(JSON.stringify(missingAccount.body)).toMatch(
      /必须关联客户|belong to an account|crm_ct_account_id.*required/i,
    );

    const duplicate = await commandFailure(page, 'crm:create_contact', {
      crm_ct_account_id: account.recordId,
      crm_ct_name: `T05 Duplicate ${uid}`,
      crm_ct_email: `${uid}-editable@example.com`.toUpperCase(),
    });
    expect(duplicate.status).toBeGreaterThanOrEqual(400);
    expect(JSON.stringify(duplicate.body)).toMatch(/相同联系方式|same contact detail/i);

    await page.goto(`/p/crm_account_common/view/${account.recordId}`, {
      waitUntil: 'domcontentloaded',
    });
    const contactsTab = page.getByRole('tab', { name: /联系人|Contacts/i });
    await expect(contactsTab).toBeVisible({ timeout: 15_000 });
    await contactsTab.click();
    for (const name of Object.values(names)) {
      await expect(row(page, name)).toHaveCount(1, { timeout: 15_000 });
    }

    await openContactsFromMenu(page);
    await search(page, uid);
    for (const name of Object.values(names)) {
      await expect(row(page, name)).toHaveCount(1);
    }

    const editableRow = row(page, names.editable);
    await clickRowActionByLocator(page, editableRow, 'edit', '编辑');
    const titleInput = page
      .locator('[data-testid="form-field-crm_ct_title"] input, input[name="crm_ct_title"]')
      .first();
    await expect(titleInput).toBeVisible();
    await titleInput.fill(`Updated ${uid}`);
    const updateResponse = page.waitForResponse(
      (candidate) =>
        candidate.url().includes('/api/meta/commands/execute/crm:update_contact') && candidate.ok(),
    );
    await page.getByTestId('form-btn-submit').click();
    await updateResponse;
    await openContactsFromMenu(page);
    await search(page, uid);
    await expect(row(page, names.editable)).toContainText(`Updated ${uid}`);

    for (const name of [names.editable, names.removable]) {
      await row(page, name).locator('input[type="checkbox"]').check();
    }
    await page.getByTestId('bulk-edit-btn').click();
    const dialog = page.getByTestId('bulk-edit-dialog');
    await dialog.getByTestId('bulk-edit-field').selectOption('crm_ct_title');
    await dialog.getByTestId('bulk-edit-value').fill(`Bulk ${uid}`);
    const bulkUpdate = page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'PUT' &&
        candidate.url().includes(`/api/dynamic/${CONTACT}/batch`),
    );
    await dialog.getByRole('button', { name: /Update 2 records|更新 2 条记录/i }).click();
    expect((await bulkUpdate).ok()).toBe(true);
    await expect(row(page, names.editable)).toContainText(`Bulk ${uid}`);
    await expect(row(page, names.removable)).toContainText(`Bulk ${uid}`);

    for (const name of [names.editable, names.removable]) {
      await row(page, name).locator('input[type="checkbox"]').check();
    }
    const exportResponse = page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' &&
        candidate.url().includes(`/api/dynamic/${CONTACT}/export`),
    );
    const downloadPromise = page.waitForEvent('download');
    await page.getByTestId('bulk-more-actions-btn').click();
    await page.getByTestId('bulk-export-selected-btn').click();
    const [exported, download] = await Promise.all([exportResponse, downloadPromise]);
    expect(exported.ok()).toBe(true);
    expect((await exported.json()).data.recordCount).toBe(2);
    const workbookPath = testInfo.outputPath('selected-contacts.xlsx');
    await download.saveAs(workbookPath);
    const workbookBytes = fs.readFileSync(workbookPath);
    const workbook = XLSX.read(workbookBytes, { type: 'buffer' });
    const exportedText = JSON.stringify(
      XLSX.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]]),
    );
    expect(exportedText).toContain(names.editable);
    expect(exportedText).toContain(names.removable);
    expect(exportedText).not.toContain(names.primary);
    await testInfo.attach('selected-contacts.xlsx', {
      body: workbookBytes,
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    await page.getByTestId('bulk-clear-selection-btn').click();

    for (const name of [names.primary, names.linked, names.removable]) {
      await row(page, name).locator('input[type="checkbox"]').check();
    }
    await page.getByTestId('bulk-more-actions-btn').click();
    await page.getByTestId('bulk-action-bulk_delete_contacts').click();
    await page
      .getByRole('dialog')
      .getByRole('button', { name: /确认|确定|Confirm|Delete/ })
      .last()
      .click();
    const result = page.getByTestId('bulk-action-result-dialog');
    await expect(result).toBeVisible({ timeout: 20_000 });
    await expect(result).toContainText(/成功 1 条|1 succeeded/i);
    await expect(result).toContainText(/失败 2 条|2 failed/i);
    await expect(result).toContainText(/主联系人不能直接删除|primary contact cannot be deleted/i);
    await expect(result).toContainText(/仍关联商机|linked to opportunities/i);
    await expect(row(page, names.removable)).toHaveCount(0);
    await expect(row(page, names.primary)).toHaveCount(1);
    await expect(row(page, names.linked)).toHaveCount(1);

    await attachScreenshot(page, testInfo, '05-contact-management-final');
    completedScenarios.push('contact-list-bulk-governance');
  });
});
