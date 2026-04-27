import { expect, test, type Page } from '../../fixtures';
import {
  clickRowActionByLocator,
  executeCommandViaApi,
  findRowByContent,
  uniqueId,
} from '../helpers/index';

async function navigateToCrmPage(page: Page, leafName: string, modelCode: string): Promise<void> {
  await page.goto('/dashboards');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav');
  const crmButton = nav.getByRole('button', { name: /crm/i }).first();
  await crmButton.scrollIntoViewIfNeeded();
  await crmButton.evaluate((el: HTMLElement) => el.click());

  const leafLink = nav
    .locator(`a[href="/p/${modelCode}"]`)
    .or(nav.getByRole('link', { name: leafName }))
    .first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });
  await leafLink.scrollIntoViewIfNeeded();

  const listResponse = page
    .waitForResponse(
      (r) => r.url().includes(`/api/dynamic/${modelCode}/list`) && r.status() === 200,
      {
        timeout: 15_000,
      },
    )
    .catch(() => null);
  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResponse;

  await expect(
    page.locator('[data-testid="list-toolbar"], table, [class*="ant-table"]').first(),
  ).toBeVisible({
    timeout: 10_000,
  });
}

async function searchList(page: Page, modelCode: string, keyword: string): Promise<void> {
  const search = page.locator('[data-testid="list-search-input"]').first();
  await search.waitFor({ state: 'visible', timeout: 8_000 });
  const listResponse = page
    .waitForResponse(
      (r) =>
        r.url().includes(`/api/dynamic/${modelCode}/list`) &&
        r.url().includes('keyword=') &&
        r.status() === 200,
      { timeout: 15_000 },
    )
    .catch(() => null);
  await search.fill(keyword);
  await page.keyboard.press('Enter');
  await listResponse;
}

test.describe('CRM Agent UI smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  const uid = uniqueId('crmagentui');
  const leadCompany = `Agent UI Lead ${uid}`;
  const leadContact = `Agent UI Contact ${uid}`;
  const complaintDescription = `Agent UI complaint ${uid}`;

  let leadId = '';
  let complaintId = '';
  let complaintCode = '';

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const lead = await executeCommandViaApi(
        page,
        'crm:create_lead',
        {
          crm_lead_company: leadCompany,
          crm_lead_contact_name: leadContact,
          crm_lead_contact_phone: '13800138000',
          crm_lead_contact_email: `${uid}@agent-ui.test`,
          crm_lead_source: 'website',
          crm_lead_industry: 'technology',
          crm_lead_score: 94,
          crm_lead_requirement: `UI smoke ${uid}`,
        },
        undefined,
        'create',
      );
      leadId = lead.recordId;
      expect(leadId).toBeTruthy();

      const account = await executeCommandViaApi(
        page,
        'crm:create_account',
        {
          crm_acc_name: `Agent UI Account ${uid}`,
          crm_acc_industry: 'technology',
        },
        undefined,
        'create',
      );
      expect(account.recordId).toBeTruthy();

      const complaint = await executeCommandViaApi(
        page,
        'crm:create_complaint',
        {
          crm_cmp_account_id: account.recordId,
          crm_cmp_date: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
          crm_cmp_type: 'product_quality',
          crm_cmp_severity: 'high',
          crm_cmp_description: complaintDescription,
        },
        undefined,
        'create',
      );
      complaintId = complaint.recordId;
      expect(complaintId).toBeTruthy();

      const detailResp = await page.request.get(`/api/dynamic/crm_complaint/${complaintId}`);
      const detailBody = await detailResp.json();
      expect(detailBody.code).toBe('0');
      complaintCode = detailBody.data.crm_cmp_code;
      expect(complaintCode).toBeTruthy();
    } finally {
      await ctx.close();
    }
  });

  test('CRM-AGENT-UI-01 @smoke: Lead list row opens detail page', async ({ page }) => {
    await navigateToCrmPage(page, '线索', 'crm_lead');
    await searchList(page, 'crm_lead', leadCompany);

    const row = await findRowByContent(page, leadCompany);
    await expect(row).toContainText(leadContact);
    await clickRowActionByLocator(page, row, 'view', 'detail');

    await expect(page).toHaveURL(new RegExp(`/p/crm_lead/view/${leadId}(?:\\?.*)?$`), {
      timeout: 10_000,
    });
    await expect(page.getByText(leadCompany).first()).toBeVisible({ timeout: 8_000 });
    await expect(page.getByText(leadContact).first()).toBeVisible({ timeout: 8_000 });
    await expect(page.getByText(/94/).first()).toBeVisible({ timeout: 8_000 });
  });

  test('CRM-AGENT-UI-02 @smoke: Complaint list row opens detail page', async ({ page }) => {
    await navigateToCrmPage(page, '投诉', 'crm_complaint');
    await searchList(page, 'crm_complaint', complaintCode);

    const row = await findRowByContent(page, complaintCode);
    await expect(row).toContainText(/high|高/i);
    await clickRowActionByLocator(page, row, 'view', 'detail');

    await expect(page).toHaveURL(new RegExp(`/p/crm_complaint/view/${complaintId}(?:\\?.*)?$`), {
      timeout: 10_000,
    });
    await expect(page.getByText(complaintCode).first()).toBeVisible({ timeout: 8_000 });
    await expect(page.getByText(complaintDescription).first()).toBeVisible({ timeout: 8_000 });
    await expect(page.getByText(/open|开启|待处理|new/i).first()).toBeVisible({ timeout: 8_000 });
  });
});
