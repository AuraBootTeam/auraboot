import { expect, test, type Page } from '../../fixtures';
import fs from 'node:fs';
import * as XLSX from 'xlsx';
import { clickRowActionByLocator, executeCommandViaApi, uniqueId } from '../helpers';

const CONTACT = 'crm_contact_common';

async function openContactsFromMenu(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const link = page.locator('nav a[href="/p/crm_contact_common"]').first();
  await expect(link).toBeVisible({ timeout: 15_000 });
  const response = page.waitForResponse(
    (candidate) => candidate.url().includes(`/api/dynamic/${CONTACT}`) && candidate.ok(),
  );
  await link.click();
  await response;
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
    (candidate) =>
      candidate.url().includes(`/api/dynamic/${CONTACT}/list`) && candidate.ok(),
  );
  await input.fill(keyword);
  await input.press('Enter');
  await response;
}

function row(page: Page, name: string) {
  return page.locator('tbody tr').filter({ hasText: name });
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

  test('list, update, bulk edit/export/delete and governed failures @critical @golden', async ({
    page,
  }, testInfo) => {
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

    await page.screenshot({ path: testInfo.outputPath('contact-management-final.png'), fullPage: true });
  });
});
