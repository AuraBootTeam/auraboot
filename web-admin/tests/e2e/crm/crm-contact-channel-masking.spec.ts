import fs from 'node:fs/promises';
import path from 'node:path';
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { createCookieSessionStorage } from 'react-router';
import {
  clickRowActionByLocator,
  executeCommandViaApi,
  queryFilteredList,
  uniqueId,
} from '../helpers';

const PASSWORD = 'Test2026x';
const DEFAULT_BASE_URL = 'http://127.0.0.1:5173';
const JWT_TOKEN_KEY = 'jwtToken';
const ACCOUNT_MODEL = 'crm_account_common';
const CONTACT_MODEL = 'crm_contact_common';
const LEAD_MODEL = 'crm_lead_common';

const authSessionStorage = createCookieSessionStorage({
  cookie: {
    name: '__session',
    httpOnly: true,
    path: '/',
    sameSite: 'lax',
    secrets: [process.env.SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'],
    secure: process.env.NODE_ENV === 'production',
  },
});

type TestUser = {
  email: string;
  displayName: string;
  password: string;
  roleCode: 'crm_sales' | 'crm_viewer';
  pid?: string;
};

type TenantSpace = {
  id?: string | number;
  tenantId?: string | number;
  type?: string;
  spaceType?: string;
};

type ContactChannels = {
  accountPhone: string;
  contactEmail: string;
  contactPhone: string;
  contactMobile: string;
  leadPhone: string;
  leadEmail: string;
};

const raw: ContactChannels = {
  accountPhone: '13912345678',
  contactEmail: '',
  contactPhone: '07551234567',
  contactMobile: '13887654321',
  leadPhone: '13611112222',
  leadEmail: '',
};

const masked: ContactChannels = {
  accountPhone: '139****5678',
  contactEmail: '',
  contactPhone: '075****4567',
  contactMobile: '138****4321',
  leadPhone: '136****2222',
  leadEmail: '',
};

test.describe('CRM contact-channel field masking', () => {
  test.setTimeout(180_000);

  test('permission exemption is consistent across list, detail, UI, and CSV export @smoke', async ({
    page,
    browser,
    baseURL,
  }, testInfo) => {
    const resolvedBaseURL = baseURL ?? DEFAULT_BASE_URL;
    const uid = uniqueId('crm_mask');
    const sales: TestUser = {
      email: `crm-mask-sales-${uid}@e2e.local`,
      displayName: `CRM Mask Sales ${uid}`,
      password: PASSWORD,
      roleCode: 'crm_sales',
    };
    const viewer: TestUser = {
      email: `crm-mask-viewer-${uid}@e2e.local`,
      displayName: `CRM Mask Viewer ${uid}`,
      password: PASSWORD,
      roleCode: 'crm_viewer',
    };
    raw.contactEmail = `mask-${uid}@example.com`;
    raw.leadEmail = `lead-${uid}@example.net`;
    masked.contactEmail = 'ma***@example.com';
    masked.leadEmail = 'le***@example.net';

    sales.pid = await provisionUser(page, sales);
    viewer.pid = await provisionUser(page, viewer);

    const salesContext = await newAuthenticatedContext(browser, resolvedBaseURL, sales);
    const viewerContext = await newAuthenticatedContext(browser, resolvedBaseURL, viewer);
    const salesPage = await salesContext.newPage();
    const viewerPage = await viewerContext.newPage();

    try {
      const accountName = `Mask Account ${uid}`;
      const contactName = `Mask Contact ${uid}`;
      const leadCompany = `Mask Lead ${uid}`;
      const accountPid = await createAccount(salesPage, accountName);
      const contactPid = await createContact(salesPage, accountPid, contactName);
      const leadPid = await createLead(salesPage, leadCompany, sales.pid!);

      await expectChannelAccess(
        salesPage,
        { accountName, accountPid, contactName, contactPid, leadCompany, leadPid },
        raw,
        'sales exemption returns raw channels',
      );
      await expectChannelAccess(
        viewerPage,
        { accountName, accountPid, contactName, contactPid, leadCompany, leadPid },
        masked,
        'viewer receives masked channels',
      );

      await openContactListFromMenu(viewerPage);
      await searchContactList(viewerPage, contactName);
      const viewerRow = viewerPage.locator('tbody tr', { hasText: contactName }).first();
      await expect(viewerRow).toBeVisible({ timeout: 10_000 });
      await expect(viewerRow).toContainText(masked.contactEmail);
      await expect(viewerRow).toContainText(masked.contactPhone);
      await expect(viewerRow).toContainText(masked.contactMobile);
      await expect(viewerRow).not.toContainText(raw.contactEmail);
      await expect(viewerRow).not.toContainText(raw.contactPhone);
      await expect(viewerRow).not.toContainText(raw.contactMobile);
      const listScreenshot = path.join(testInfo.outputDir, 'viewer-contact-list-masked.png');
      await viewerPage.screenshot({ path: listScreenshot, fullPage: true });
      await testInfo.attach('viewer-contact-list-masked', {
        path: listScreenshot,
        contentType: 'image/png',
      });

      await clickRowActionByLocator(viewerPage, viewerRow, 'view', 'detail');
      await expect(viewerPage.getByText(masked.contactEmail).first()).toBeVisible({
        timeout: 10_000,
      });
      await expect(viewerPage.getByText(masked.contactPhone).first()).toBeVisible();
      await expect(viewerPage.getByText(masked.contactMobile).first()).toBeVisible();
      await expect(viewerPage.getByText(raw.contactEmail)).toHaveCount(0);
      await expect(viewerPage.getByText(raw.contactPhone)).toHaveCount(0);
      await expect(viewerPage.getByText(raw.contactMobile)).toHaveCount(0);
      const detailScreenshot = path.join(testInfo.outputDir, 'viewer-contact-detail-masked.png');
      await viewerPage.screenshot({ path: detailScreenshot, fullPage: true });
      await testInfo.attach('viewer-contact-detail-masked', {
        path: detailScreenshot,
        contentType: 'image/png',
      });

      const viewerCsv = await exportContactCsv(viewerPage, testInfo.outputDir, 'viewer');
      expect(viewerCsv).toContain(masked.contactEmail);
      expect(viewerCsv).toContain(masked.contactPhone);
      expect(viewerCsv).toContain(masked.contactMobile);
      expect(viewerCsv).not.toContain(raw.contactEmail);
      expect(viewerCsv).not.toContain(raw.contactPhone);
      expect(viewerCsv).not.toContain(raw.contactMobile);
      await testInfo.attach('viewer-contact-export.csv', {
        body: Buffer.from(viewerCsv),
        contentType: 'text/csv',
      });

      const salesCsv = await exportContactCsv(salesPage, testInfo.outputDir, 'sales');
      expect(salesCsv).toContain(raw.contactEmail);
      expect(salesCsv).toContain(raw.contactPhone);
      expect(salesCsv).toContain(raw.contactMobile);
      await testInfo.attach('sales-contact-export.csv', {
        body: Buffer.from(salesCsv),
        contentType: 'text/csv',
      });
    } finally {
      await salesContext.close();
      await viewerContext.close();
    }
  });
});

async function provisionUser(adminPage: Page, user: TestUser): Promise<string> {
  const response = await adminPage.request.post('/api/admin/users', {
    data: {
      email: user.email,
      displayName: user.displayName,
      initialPassword: user.password,
      roleCodes: [user.roleCode],
      sendInviteEmail: false,
    },
  });
  await expectOk(response, `provision ${user.email}`);
  const body = await response.json().catch(() => ({}));
  const directPid = body?.data?.pid ?? body?.data?.userPid;
  if (directPid) return String(directPid);

  const searchResponse = await adminPage.request.get(
    `/api/admin/users/search?keyword=${encodeURIComponent(user.email)}&size=20`,
  );
  await expectOk(searchResponse, `resolve pid for ${user.email}`);
  const searchBody = await searchResponse.json().catch(() => ({}));
  const users = searchBody?.data?.content ?? searchBody?.data ?? [];
  const created = users.find((candidate: any) => candidate?.email === user.email);
  expect(created?.pid, `public user pid for ${user.email}`).toBeTruthy();
  return String(created.pid);
}

async function newAuthenticatedContext(
  browser: Browser,
  baseURL: string,
  user: TestUser,
): Promise<BrowserContext> {
  const loginContext = await browser.newContext({
    baseURL,
    storageState: { cookies: [], origins: [] },
  });
  const loginPage = await loginContext.newPage();
  let jwt: string;
  try {
    jwt = await loginAndResolveJwt(loginPage, baseURL, user);
  } finally {
    await loginContext.close();
  }

  const session = await authSessionStorage.getSession();
  session.set(JWT_TOKEN_KEY, jwt);
  const setCookie = await authSessionStorage.commitSession(session, { maxAge: 60 * 60 * 24 * 7 });
  const cookieValue = setCookie.match(/__session=([^;]+)/)?.[1];
  expect(cookieValue, `session cookie for ${user.email}`).toBeTruthy();

  const context = await browser.newContext({ baseURL });
  await context.addCookies([
    {
      name: '__session',
      value: cookieValue!,
      httpOnly: true,
      sameSite: 'Lax',
      expires: Math.floor(Date.now() / 1000) + 604800,
      url: baseURL,
    },
  ]);
  return context;
}

async function loginAndResolveJwt(page: Page, baseURL: string, user: TestUser): Promise<string> {
  const loginResponse = await page.request.post(`${baseURL}/api/auth/login`, {
    data: { email: user.email, password: user.password },
  });
  await expectOk(loginResponse, `login ${user.email}`);
  const loginBody = await loginResponse.json();
  const loginJwt = String(loginBody?.data?.jwt ?? '');
  expect(loginJwt, `login jwt for ${user.email}`).toBeTruthy();
  if (loginBody?.data?.tenantId) return loginJwt;

  const spacesResponse = await page.request.get(`${baseURL}/api/tenant-selection/my-spaces`, {
    headers: { Authorization: `Bearer ${loginJwt}` },
  });
  await expectOk(spacesResponse, `tenant spaces for ${user.email}`);
  const spacesBody = await spacesResponse.json().catch(() => ({}));
  const spaces: TenantSpace[] = Array.isArray(spacesBody?.data) ? spacesBody.data : [];
  const space =
    spaces.find(
      (candidate) => String(candidate.spaceType ?? candidate.type).toLowerCase() === 'business',
    ) ?? spaces.find((candidate) => candidate.tenantId ?? candidate.id);
  const tenantId = space?.tenantId ?? space?.id;
  expect(tenantId, `business tenant for ${user.email}`).toBeTruthy();

  const selectResponse = await page.request.post(`${baseURL}/api/tenant-selection/process`, {
    headers: { Authorization: `Bearer ${loginJwt}` },
    data: { action: 'select', tenantId },
  });
  await expectOk(selectResponse, `select tenant for ${user.email}`);
  const selectBody = await selectResponse.json().catch(() => ({}));
  return String(selectBody?.data?.jwt ?? loginJwt);
}

async function createAccount(page: Page, name: string): Promise<string> {
  const result = await executeCommandViaApi(page, 'crm:create_account', {
    crm_acc_name: name,
    crm_acc_industry: 'technology',
    crm_acc_phone: raw.accountPhone,
    crm_acc_rating: 'A',
    crm_acc_remark: `Contact-channel masking E2E ${name}`,
  });
  expect(result.recordId, `created account pid for ${name}`).toBeTruthy();
  return result.recordId;
}

async function createContact(page: Page, accountPid: string, name: string): Promise<string> {
  const result = await executeCommandViaApi(page, 'crm:create_contact', {
    crm_ct_account_id: accountPid,
    crm_ct_name: name,
    crm_ct_title: 'Security Contact',
    crm_ct_email: raw.contactEmail,
    crm_ct_phone: raw.contactPhone,
    crm_ct_mobile: raw.contactMobile,
    crm_ct_is_primary: true,
    crm_ct_remark: `Contact-channel masking E2E ${name}`,
  });
  expect(result.recordId, `created contact pid for ${name}`).toBeTruthy();
  return result.recordId;
}

async function createLead(page: Page, company: string, assigneePid: string): Promise<string> {
  const result = await executeCommandViaApi(page, 'crm:create_lead', {
    crm_lead_company: company,
    crm_lead_contact_name: `Lead Contact ${company}`,
    crm_lead_contact_phone: raw.leadPhone,
    crm_lead_contact_email: raw.leadEmail,
    crm_lead_source: 'website',
    crm_lead_assigned_to: assigneePid,
    crm_lead_requirement: `Contact-channel masking E2E ${company}`,
  });
  expect(result.recordId, `created lead pid for ${company}`).toBeTruthy();
  return result.recordId;
}

async function expectChannelAccess(
  page: Page,
  records: {
    accountName: string;
    accountPid: string;
    contactName: string;
    contactPid: string;
    leadCompany: string;
    leadPid: string;
  },
  expected: ContactChannels,
  label: string,
): Promise<void> {
  const account = await expectListAndDetail(
    page,
    ACCOUNT_MODEL,
    'crm_acc_name',
    records.accountName,
    records.accountPid,
    label,
  );
  expect(account.list.crm_acc_phone, `${label}: account list phone`).toBe(expected.accountPhone);
  expect(account.detail.crm_acc_phone, `${label}: account detail phone`).toBe(
    expected.accountPhone,
  );

  const contact = await expectListAndDetail(
    page,
    CONTACT_MODEL,
    'crm_ct_name',
    records.contactName,
    records.contactPid,
    label,
  );
  expect(contact.list.crm_ct_email, `${label}: contact list email`).toBe(expected.contactEmail);
  expect(contact.list.crm_ct_phone, `${label}: contact list phone`).toBe(expected.contactPhone);
  expect(contact.list.crm_ct_mobile, `${label}: contact list mobile`).toBe(expected.contactMobile);
  expect(contact.detail.crm_ct_email, `${label}: contact detail email`).toBe(expected.contactEmail);
  expect(contact.detail.crm_ct_phone, `${label}: contact detail phone`).toBe(expected.contactPhone);
  expect(contact.detail.crm_ct_mobile, `${label}: contact detail mobile`).toBe(
    expected.contactMobile,
  );

  const lead = await expectListAndDetail(
    page,
    LEAD_MODEL,
    'crm_lead_company',
    records.leadCompany,
    records.leadPid,
    label,
  );
  expect(lead.list.crm_lead_contact_phone, `${label}: lead list phone`).toBe(expected.leadPhone);
  expect(lead.list.crm_lead_contact_email, `${label}: lead list email`).toBe(expected.leadEmail);
  expect(lead.detail.crm_lead_contact_phone, `${label}: lead detail phone`).toBe(
    expected.leadPhone,
  );
  expect(lead.detail.crm_lead_contact_email, `${label}: lead detail email`).toBe(
    expected.leadEmail,
  );
}

async function expectListAndDetail(
  page: Page,
  modelCode: string,
  filterField: string,
  filterValue: string,
  recordPid: string,
  label: string,
): Promise<{ list: Record<string, any>; detail: Record<string, any> }> {
  const records = await queryFilteredList(page, modelCode, filterField, filterValue, {
    operator: 'EQ',
  });
  const listRecord = records.find((record) => String(record.pid) === recordPid);
  expect(listRecord, `${label}: ${modelCode} list record`).toBeTruthy();

  const detailResponse = await page.request.get(`/api/dynamic/${modelCode}/${recordPid}`);
  await expectOk(detailResponse, `${label}: ${modelCode} detail`);
  const detailBody = await detailResponse.json().catch(() => ({}));
  expect(isSuccessBody(detailBody), `${label}: ${modelCode} detail success body`).toBe(true);
  expect(String(detailBody?.data?.pid), `${label}: ${modelCode} detail pid`).toBe(recordPid);
  return { list: listRecord!, detail: detailBody.data };
}

async function openContactListFromMenu(page: Page): Promise<void> {
  await page.goto('/dashboards');
  await page.waitForLoadState('domcontentloaded');
  const contactLink = page.locator('nav a[href="/p/crm_contact_common"]').first();
  await expect(contactLink).toBeVisible({ timeout: 10_000 });
  const listResponse = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/dynamic/${CONTACT_MODEL}`) && response.status() === 200,
  );
  await contactLink.click();
  await listResponse;
  await expect(page.locator('table').first()).toBeVisible({ timeout: 10_000 });
}

async function searchContactList(page: Page, keyword: string): Promise<void> {
  const input = page
    .locator(
      '[data-testid="list-search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]',
    )
    .first();
  await expect(input).toBeVisible({ timeout: 10_000 });
  const response = page.waitForResponse(
    (candidate) =>
      candidate.url().includes(`/api/dynamic/${CONTACT_MODEL}/list`) && candidate.status() === 200,
  );
  await input.fill(keyword);
  await input.press('Enter');
  await response;
}

async function exportContactCsv(page: Page, outputDir: string, label: string): Promise<string> {
  await openContactListFromMenu(page);
  const moreButton = page.getByTestId('toolbar-more-menu').first();
  await expect(moreButton).toBeVisible({ timeout: 10_000 });
  await moreButton.click();
  const csvOption = page.getByTestId('more-menu-export-csv');
  await expect(csvOption).toBeVisible({ timeout: 5_000 });

  const downloadPromise = page.waitForEvent('download');
  await csvOption.click();
  const download = await downloadPromise;
  expect(download.suggestedFilename(), `${label} CSV filename`).toMatch(/\.csv$/i);
  const destination = path.join(outputDir, `${label}-crm-contact-export.csv`);
  await download.saveAs(destination);
  const csv = await fs.readFile(destination, 'utf8');
  expect(csv.trim(), `${label} CSV content`).not.toBe('');
  return csv;
}

function isSuccessBody(body: any): boolean {
  return body?.success === true || String(body?.code) === '0';
}

async function expectOk(
  response: { ok(): boolean; status(): number; text(): Promise<string> },
  label: string,
): Promise<void> {
  if (!response.ok()) {
    throw new Error(`${label}: HTTP ${response.status()} ${await response.text().catch(() => '')}`);
  }
}
