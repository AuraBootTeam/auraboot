import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { createCookieSessionStorage } from 'react-router';
import {
  clickRowActionByLocator,
  executeCommandViaApi,
  queryFilteredList,
  uniqueId,
} from '../helpers';

const PASSWORD = 'Test2026x';
const MODEL_CODE = 'crm_account_common';
const JWT_TOKEN_KEY = 'jwtToken';
const DEFAULT_BASE_URL = 'http://127.0.0.1:5173';

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
  pid?: string;
};

type AccountRecord = {
  pid: string;
  name: string;
};

type TenantSpace = {
  id?: string | number;
  tenantId?: string | number;
  type?: string;
  spaceType?: string;
};

test.describe('CRM ownership isolation and explicit record sharing', () => {
  test.setTimeout(120_000);

  test('sales members see self records; owner share/revoke changes recipient list and detail @smoke', async ({
    page,
    browser,
    baseURL,
  }, testInfo) => {
    const resolvedBaseURL = baseURL ?? DEFAULT_BASE_URL;
    const uid = uniqueId('crm_share');
    const owner: TestUser = {
      email: `crm-owner-${uid}@e2e.local`,
      displayName: `CRM Owner ${uid}`,
      password: PASSWORD,
    };
    const recipient: TestUser = {
      email: `crm-recipient-${uid}@e2e.local`,
      displayName: `CRM Recipient ${uid}`,
      password: PASSWORD,
    };

    owner.pid = await provisionSalesUser(page, owner);
    recipient.pid = await provisionSalesUser(page, recipient);

    const ownerContext = await newAuthenticatedContext(browser, resolvedBaseURL, owner);
    const recipientContext = await newAuthenticatedContext(browser, resolvedBaseURL, recipient);
    const ownerPage = await ownerContext.newPage();
    const recipientPage = await recipientContext.newPage();

    try {
      const ownerAccount = await createAccount(ownerPage, `Owner Account ${uid}`);
      const recipientAccount = await createAccount(recipientPage, `Recipient Account ${uid}`);

      await expectAccountVisibility(ownerPage, ownerAccount, true, 'owner sees own account');
      await expectAccountVisibility(
        ownerPage,
        recipientAccount,
        false,
        'owner cannot see recipient account',
      );
      await expectAccountVisibility(
        recipientPage,
        recipientAccount,
        true,
        'recipient sees own account',
      );
      await expectAccountVisibility(
        recipientPage,
        ownerAccount,
        false,
        'recipient cannot see owner account',
      );

      const escalation = await recipientPage.request.post('/api/record-share', {
        data: {
          resourceCode: MODEL_CODE,
          recordPid: ownerAccount.pid,
          subjectType: 'member',
          subjectPid: recipient.pid,
          permissionMask: 'read',
        },
      });
      const escalationBody = await escalation.json().catch(() => ({}));
      expect(
        escalation.ok() && isSuccessBody(escalationBody),
        'a non-owner must not share another sales member record',
      ).toBe(false);

      await openAccountDetailFromMenu(ownerPage, ownerAccount.name);
      await ownerPage.locator('[data-testid$="share-btn"]').click();
      const dialog = ownerPage.getByTestId('record-share-dialog');
      await expect(dialog).toBeVisible();

      await dialog.getByTestId('member-picker-add').click();
      await dialog.getByTestId('member-picker-search-input').fill(recipient.email);
      const recipientOption = dialog.getByTestId(`member-picker-option-${recipient.pid}`);
      await expect(recipientOption).toBeVisible({ timeout: 10_000 });
      await recipientOption.click();

      const shareResponse = ownerPage.waitForResponse(
        (response) =>
          response.url().endsWith('/api/record-share') && response.request().method() === 'POST',
      );
      await dialog.getByTestId('record-share-add-btn').click();
      expect((await shareResponse).ok(), 'owner share request').toBe(true);
      const shareRow = dialog
        .locator('[data-testid^="record-share-row-"]')
        .filter({ hasText: recipient.pid! });
      await expect(shareRow).toBeVisible({ timeout: 10_000 });
      await testInfo.attach('owner-share-dialog', {
        body: await ownerPage.screenshot(),
        contentType: 'image/png',
      });

      await expectAccountVisibility(
        recipientPage,
        ownerAccount,
        true,
        'read share adds recipient list/detail visibility',
      );

      const deniedUpdate = await recipientPage.request.post(
        '/api/meta/commands/execute/crm:update_account',
        {
          data: {
            operationType: 'update',
            targetRecordPid: ownerAccount.pid,
            payload: { crm_acc_name: `Escalated ${uid}` },
          },
        },
      );
      const deniedUpdateBody = await deniedUpdate.json().catch(() => ({}));
      expect(
        deniedUpdate.ok() && isSuccessBody(deniedUpdateBody),
        'a read share must not authorize update',
      ).toBe(false);

      await openAccountListFromMenu(recipientPage);
      await searchAccountList(recipientPage, ownerAccount.name);
      await expect(
        recipientPage.locator('tbody tr', { hasText: ownerAccount.name }).first(),
      ).toBeVisible();
      await testInfo.attach('recipient-shared-account-visible', {
        body: await recipientPage.screenshot(),
        contentType: 'image/png',
      });

      const shareId = Number(
        (await shareRow.getAttribute('data-testid'))?.replace('record-share-row-', ''),
      );
      expect(Number.isFinite(shareId), 'share row exposes a stable share id').toBe(true);
      const revokeResponse = ownerPage.waitForResponse(
        (response) =>
          response.url().endsWith(`/api/record-share/${shareId}`) &&
          response.request().method() === 'DELETE',
      );
      await dialog.getByTestId(`record-share-remove-${shareId}`).click();
      expect((await revokeResponse).ok(), 'owner revoke request').toBe(true);
      await expect(dialog.getByTestId(`record-share-row-${shareId}`)).toHaveCount(0);

      await expectAccountVisibility(
        recipientPage,
        ownerAccount,
        false,
        'revoke removes recipient list/detail visibility',
      );
      await openAccountListFromMenu(recipientPage);
      await searchAccountList(recipientPage, ownerAccount.name);
      await expect(recipientPage.locator('tbody tr', { hasText: ownerAccount.name })).toHaveCount(
        0,
      );
      await testInfo.attach('recipient-account-hidden-after-revoke', {
        body: await recipientPage.screenshot(),
        contentType: 'image/png',
      });
    } finally {
      await ownerContext.close();
      await recipientContext.close();
    }
  });
});

async function provisionSalesUser(adminPage: Page, user: TestUser): Promise<string> {
  const response = await adminPage.request.post('/api/admin/users', {
    data: {
      email: user.email,
      displayName: user.displayName,
      initialPassword: user.password,
      roleCodes: ['crm_sales'],
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

async function createAccount(page: Page, name: string): Promise<AccountRecord> {
  const result = await executeCommandViaApi(page, 'crm:create_account', {
    crm_acc_name: name,
    crm_acc_industry: 'technology',
    crm_acc_rating: 'A',
    crm_acc_remark: `Ownership/share E2E ${name}`,
  });
  expect(result.recordId, `created account pid for ${name}`).toBeTruthy();
  return { pid: result.recordId, name };
}

async function expectAccountVisibility(
  page: Page,
  account: AccountRecord,
  shouldAllow: boolean,
  label: string,
): Promise<void> {
  const records = await queryFilteredList(page, MODEL_CODE, 'crm_acc_name', account.name, {
    operator: 'EQ',
  });
  expect(
    records.some((record) => String(record.pid) === account.pid),
    `${label}: list`,
  ).toBe(shouldAllow);

  const detailResponse = await page.request.get(`/api/dynamic/${MODEL_CODE}/${account.pid}`);
  const detailBody = await detailResponse.json().catch(() => ({}));
  expect(
    detailResponse.ok() &&
      isSuccessBody(detailBody) &&
      String(detailBody?.data?.pid) === account.pid,
    `${label}: detail`,
  ).toBe(shouldAllow);
}

async function openAccountListFromMenu(page: Page): Promise<void> {
  await page.goto('/dashboards');
  await page.waitForLoadState('domcontentloaded');
  const accountLink = page.locator('nav a[href="/p/crm_account_common"]').first();
  await expect(accountLink).toBeVisible({ timeout: 10_000 });
  const listResponse = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/dynamic/${MODEL_CODE}`) && response.status() === 200,
  );
  await accountLink.click();
  await listResponse;
  await expect(page.locator('table').first()).toBeVisible({ timeout: 10_000 });
}

async function openAccountDetailFromMenu(page: Page, accountName: string): Promise<void> {
  await openAccountListFromMenu(page);
  await searchAccountList(page, accountName);
  const row = page.locator('tbody tr', { hasText: accountName }).first();
  await expect(row).toBeVisible({ timeout: 10_000 });
  await clickRowActionByLocator(page, row, 'view', 'detail');
  await expect(page.locator('[data-testid$="share-btn"]')).toBeVisible({ timeout: 10_000 });
}

async function searchAccountList(page: Page, keyword: string): Promise<void> {
  const input = page
    .locator(
      '[data-testid="list-search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]',
    )
    .first();
  await expect(input).toBeVisible({ timeout: 10_000 });
  const response = page.waitForResponse(
    (candidate) =>
      candidate.url().includes(`/api/dynamic/${MODEL_CODE}/list`) && candidate.status() === 200,
  );
  await input.fill(keyword);
  await input.press('Enter');
  await response;
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
