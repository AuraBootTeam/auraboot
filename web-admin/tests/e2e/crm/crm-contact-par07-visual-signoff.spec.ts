import fs from 'node:fs';
import path from 'node:path';
import type { TestInfo } from '@playwright/test';
import { expect, test, type Page } from '../../fixtures';
import {
  clickRowActionByLocator,
  ensureSidebarExpanded,
  executeCommandViaApi,
  waitForDynamicPageLoad,
} from '../helpers';

const MODEL = 'crm_contact_common';
const ACCOUNT_NAME = 'T20 机会治理客户';
const CONTACT_NAME = 'T20 决策联系人';
const CONTACT_EMAIL = 't20@example.test';
const CONTACT_MOBILE = '13800002020';
const CONTACT_TITLE = '决策联系人';
const EVIDENCE_ROOT = path.join(
  process.env.AURA_EVIDENCE_ROOT || process.cwd(),
  't20-par07-side-by-side',
  'aura',
);

async function capture(page: Page, testInfo: TestInfo, name: string): Promise<string> {
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  const output = path.join(EVIDENCE_ROOT, `${name}.png`);
  await page.screenshot({ path: output, fullPage: true });
  await testInfo.attach(name, { path: output, contentType: 'image/png' });
  return output;
}

async function openContactList(page: Page): Promise<void> {
  if ((page.viewportSize()?.width ?? 1440) < 600) {
    await page.goto(`/p/${MODEL}`, { waitUntil: 'domcontentloaded' });
    await waitForDynamicPageLoad(page, 15_000);
    return;
  }
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const link = page.locator('nav a[href="/p/crm_contact_common"]').first();
  await expect(link).toBeVisible({ timeout: 15_000 });
  await link.click();
  await expect(page).toHaveURL(/\/p\/crm_contact_common$/, { timeout: 15_000 });
  await waitForDynamicPageLoad(page, 15_000);
}

async function searchExactContact(page: Page): Promise<ReturnType<Page['locator']>> {
  const input = page
    .locator(
      '[data-testid="list-search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]',
    )
    .first();
  await expect(input).toBeVisible();
  const response = page.waitForResponse(
    (candidate) => candidate.url().includes(`/api/dynamic/${MODEL}/list`) && candidate.ok(),
  );
  await input.fill(CONTACT_NAME);
  await input.press('Enter');
  await response;
  const contactRow = page.locator('tbody tr').filter({ hasText: CONTACT_NAME }).first();
  await expect(contactRow).toBeVisible({ timeout: 12_000 });
  await expect(contactRow).toContainText(ACCOUNT_NAME);
  await expect(contactRow).toContainText(CONTACT_EMAIL);
  await expect(contactRow).toContainText(CONTACT_MOBILE);
  return contactRow;
}

async function listRecords(page: Page, model: string): Promise<Array<Record<string, unknown>>> {
  const response = await page.request.get(`/api/dynamic/${model}/list?pageNum=1&pageSize=500`);
  expect(response.ok(), `${model} list`).toBe(true);
  const body = await response.json();
  const data = body?.data;
  return (data?.records ?? data?.rows ?? data?.list ?? data?.content ?? data ?? []) as Array<
    Record<string, unknown>
  >;
}

function recordPid(record: Record<string, unknown>): string {
  return String(record.pid ?? record.recordPid ?? record.publicRecordId ?? '');
}

test.describe('CRM PAR-07 — Aura same-data visual signoff against authenticated Cordys baseline', () => {
  test.setTimeout(180_000);

  test('Administrator opens the same contact list, detail and edit task at desktop and compact sizes @critical @golden', async ({
    page,
  }, testInfo) => {
    const runtimeFailures: string[] = [];
    page.on('response', (response) => {
      if (response.status() >= 500 && response.url().includes('/api/')) {
        runtimeFailures.push(`${response.status()} ${response.request().method()} ${response.url()}`);
      }
    });

    const existingContacts = await listRecords(page, MODEL);
    const existingContact = existingContacts.find((row) => row.crm_ct_name === CONTACT_NAME);
    let contactId = existingContact ? recordPid(existingContact) : '';
    if (!contactId) {
      const existingAccounts = await listRecords(page, 'crm_account_common');
      const existingAccount = existingAccounts.find((row) => row.crm_acc_name === ACCOUNT_NAME);
      let accountId = existingAccount ? recordPid(existingAccount) : '';
      if (!accountId) {
        const account = await executeCommandViaApi(
          page,
          'crm:create_account',
          {
            crm_acc_name: ACCOUNT_NAME,
            crm_acc_industry: 'manufacturing',
            crm_acc_status: 'active',
          },
          undefined,
          'create',
        );
        accountId = account.recordId;
      }
      const contact = await executeCommandViaApi(
        page,
        'crm:create_contact',
        {
          crm_ct_account_id: accountId,
          crm_ct_name: CONTACT_NAME,
          crm_ct_title: CONTACT_TITLE,
          crm_ct_email: CONTACT_EMAIL,
          crm_ct_mobile: CONTACT_MOBILE,
        },
        undefined,
        'create',
      );
      contactId = contact.recordId;
    }
    expect(contactId).toBeTruthy();

    const screenshots: string[] = [];
    await page.setViewportSize({ width: 1440, height: 900 });
    await openContactList(page);
    let contactRow = await searchExactContact(page);
    screenshots.push(await capture(page, testInfo, '01-aura-contact-list-desktop'));

    await clickRowActionByLocator(page, contactRow, 'view', '详情');
    await expect(page).toHaveURL(new RegExp(`/p/${MODEL}/view/${contactId}$`));
    for (const value of [CONTACT_NAME, ACCOUNT_NAME, CONTACT_TITLE, CONTACT_EMAIL, CONTACT_MOBILE]) {
      await expect(page.getByText(value, { exact: true }).first()).toBeVisible();
    }
    screenshots.push(await capture(page, testInfo, '02-aura-contact-detail-desktop'));

    await page.getByTestId('toolbar-btn-edit').click();
    await expect(page).toHaveURL(new RegExp(`/p/${MODEL}/edit/${contactId}$`));
    await expect(page.getByTestId('form-field-crm_ct_name').locator('input')).toHaveValue(CONTACT_NAME);
    screenshots.push(await capture(page, testInfo, '03-aura-contact-edit-desktop'));

    await page.setViewportSize({ width: 390, height: 844 });
    await openContactList(page);
    contactRow = await searchExactContact(page);
    await expect(page.locator('body')).not.toHaveCSS('overflow-x', 'scroll');
    screenshots.push(await capture(page, testInfo, '04-aura-contact-list-compact'));

    const compactViewButton = contactRow.getByRole('button', { name: '查看', exact: true });
    await expect(compactViewButton).toBeVisible();
    await compactViewButton.click();
    await expect(page).toHaveURL(new RegExp(`/p/${MODEL}/view/${contactId}$`), { timeout: 15_000 });
    await expect(page.getByRole('heading', { name: '联系人详情', exact: true })).toBeVisible();
    await expect(page.getByText(CONTACT_NAME, { exact: true }).first()).toBeVisible();
    await expect(page.getByText(ACCOUNT_NAME, { exact: true }).first()).toBeVisible();
    screenshots.push(await capture(page, testInfo, '05-aura-contact-detail-compact'));

    await page.getByTestId('toolbar-btn-edit').click();
    await expect(page).toHaveURL(new RegExp(`/p/${MODEL}/edit/${contactId}$`), { timeout: 15_000 });
    await expect(page.getByTestId('form-field-crm_ct_name').locator('input')).toHaveValue(CONTACT_NAME);
    await expect(page.getByTestId('form-field-crm_ct_email').locator('input')).toHaveValue(CONTACT_EMAIL);
    await expect(page.getByTestId('form-field-crm_ct_mobile').locator('input')).toHaveValue(CONTACT_MOBILE);
    screenshots.push(await capture(page, testInfo, '06-aura-contact-edit-compact'));

    expect(runtimeFailures).toEqual([]);
    const receipt = {
      verdict: 'pass',
      runtime: process.env.AURA_RUNTIME_NAME || 'crm-t20-par07-visual-20260824-s47',
      role: 'Administrator',
      competitorBaseline: 'CordysCRM v1.8.1 ab96c96f524985ea84f112c7a6b03970711f921e',
      dataMigration: 'out-of-scope-development-stage',
      sameData: {
        account: ACCOUNT_NAME,
        contact: CONTACT_NAME,
        email: CONTACT_EMAIL,
        mobile: CONTACT_MOBILE,
      },
      tasks: ['official-menu-contact-list', 'contact-detail', 'contact-edit'],
      viewports: ['1440x900', '390x844'],
      screenshots,
      runtimeFailures,
    };
    fs.writeFileSync(
      path.join(EVIDENCE_ROOT, 't20-par07-aura-final.json'),
      `${JSON.stringify(receipt, null, 2)}\n`,
    );
  });
});
