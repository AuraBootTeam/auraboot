import {
  expect,
  test,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';
import { createCookieSessionStorage } from 'react-router';
import fs from 'node:fs';
import { Pool } from 'pg';
import {
  clickRowActionByLocator,
  executeCommandViaApi,
  queryFilteredList,
  uniqueId,
} from '../helpers';
import { PG_CONN } from '../../helpers/environments';

const PASSWORD = 'Test2026x';
const MODEL_CODE = 'crm_account_common';
const JWT_TOKEN_KEY = 'jwtToken';
const DEFAULT_BASE_URL = 'http://127.0.0.1:5173';
const EXPECTED_SCENARIOS = [
  'owner-record-isolation',
  'non-owner-share-denied',
  'cross-tenant-member-hidden',
  'cross-tenant-direct-share-denied',
  'read-share-visible',
  'collaboration-notification-created',
  'read-share-update-denied',
  'collaborative-account-view-exact',
  'expired-share-removes-access',
  'renewal-restores-access',
  'collaboration-upgrade-allows-update',
  'single-stable-public-share-row',
  'batch-delete-requires-confirmation',
  'batch-revoke-removes-access',
  'revoked-account-absent-from-list',
] as const;
const COVERAGE = {
  pages: ['crm_account_common_list', 'crm_account_common_detail'],
  commands: ['crm:create_account', 'crm:update_account'],
  permissions: ['crm.account.read', 'crm.account.manage'],
  blocks: [
    'crm_account_common_list:crm_account_tabs',
    'crm_account_common_list:crm_account_table',
    'crm_account_common_detail:crm_account_tabs',
    'crm_account_common_detail:crm_account_detail_toolbar',
  ],
  uiActions: [
    'crm_account_common_list:platform:collaborative_accounts',
    'crm_account_common_detail:platform:share_record',
  ],
} as const;

const CORDYS_SOURCE_IDS = [
  'api:customer:customer-collaboration:list',
  'api:customer:customer-collaboration:add',
  'api:customer:customer-collaboration:update',
  'api:customer:customer-collaboration:delete',
] as const;

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

test.describe('CRM account collaboration', () => {
  test.setTimeout(120_000);

  test('owner grants read-only, upgrades collaboration, and revokes from the collaborative-account journey @smoke @golden', async ({
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
    const batchRecipient: TestUser = {
      email: `crm-batch-recipient-${uid}@e2e.local`,
      displayName: `CRM Batch ${uid.slice(-12)}`,
      password: PASSWORD,
    };
    const otherTenantMember: TestUser = {
      email: `crm-other-tenant-${uid}@e2e.local`,
      displayName: `CRM Other Tenant ${uid}`,
      password: PASSWORD,
    };

    owner.pid = await provisionSalesUser(page, owner);
    recipient.pid = await provisionSalesUser(page, recipient);
    batchRecipient.pid = await provisionSalesUser(page, batchRecipient);
    otherTenantMember.pid = await provisionSalesUser(page, otherTenantMember);
    await moveUserMembershipToIsolatedTenant(otherTenantMember.pid, uid);

    const ownerContext = await newAuthenticatedContext(browser, resolvedBaseURL, owner);
    const recipientContext = await newAuthenticatedContext(browser, resolvedBaseURL, recipient);
    const ownerPage = await ownerContext.newPage();
    const recipientPage = await recipientContext.newPage();
    const screenshots: string[] = [];
    const failedRuntimeRequests: Array<{ method: string; status: number; url: string }> = [];
    for (const runtimePage of [ownerPage, recipientPage]) {
      runtimePage.on('response', (response) => {
        if (response.status() < 500) return;
        failedRuntimeRequests.push({
          method: response.request().method(),
          status: response.status(),
          url: response.url(),
        });
      });
    }

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
      const crossTenantSearchResponse = ownerPage.waitForResponse(
        (response) =>
          response.url().includes('/api/tenant/members/search') &&
          response.request().method() === 'POST' &&
          String(response.request().postDataJSON()?.keyword ?? '') === otherTenantMember.email,
      );
      await dialog.getByTestId('member-picker-search-input').fill(otherTenantMember.email);
      const completedCrossTenantSearch = await crossTenantSearchResponse;
      expect(completedCrossTenantSearch.ok(), 'cross-tenant member search request').toBe(true);
      const crossTenantSearchBody = await completedCrossTenantSearch.json().catch(() => ({}));
      expect(memberSearchRecords(crossTenantSearchBody)).toEqual([]);
      await expect(dialog.getByTestId(`member-picker-option-${otherTenantMember.pid}`)).toHaveCount(
        0,
      );
      await expect(dialog).not.toContainText(otherTenantMember.email);
      await expect(dialog.getByText(/未找到成员|No members found/i)).toBeVisible();

      const crossTenantShareResponse = await ownerPage.request.post('/api/record-share', {
        data: {
          resourceCode: MODEL_CODE,
          recordPid: ownerAccount.pid,
          subjectType: 'member',
          subjectPid: otherTenantMember.pid,
          permissionMask: 'read',
        },
      });
      const crossTenantShareBody = await crossTenantShareResponse.json().catch(() => ({}));
      expect(
        crossTenantShareResponse.ok() && isSuccessBody(crossTenantShareBody),
        'owner cannot submit a member from another tenant even with its public PID',
      ).toBe(false);
      await expectShareNotPersisted(ownerAccount.pid, otherTenantMember.pid);
      screenshots.push(
        await captureScreenshot(ownerPage, testInfo, '01-cross-tenant-member-hidden'),
      );

      await dialog.getByTestId('member-picker-search-input').fill(recipient.email);
      const recipientOption = dialog.getByTestId(`member-picker-option-${recipient.pid}`);
      await expect(recipientOption).toBeVisible({ timeout: 10_000 });
      await recipientOption.click();

      const shareResponse = ownerPage.waitForResponse(
        (response) =>
          response.url().endsWith('/api/record-share') && response.request().method() === 'POST',
      );
      await dialog.getByTestId('record-share-expiry-7d').click();
      await dialog.getByTestId('record-share-add-btn').click();
      const completedShareResponse = await shareResponse;
      expect(completedShareResponse.ok(), 'owner share request').toBe(true);
      const createSharePayload = completedShareResponse.request().postDataJSON() as Record<
        string,
        unknown
      >;
      expect(createSharePayload).toMatchObject({
        resourceCode: MODEL_CODE,
        recordPid: ownerAccount.pid,
        subjectType: 'member',
        subjectPid: recipient.pid,
        permissionMask: 'read',
      });
      expect(new Date(String(createSharePayload.expiresAt)).getTime()).toBeGreaterThan(
        Date.now() + 6 * 24 * 60 * 60 * 1000,
      );
      const shareRow = dialog
        .locator('[data-testid^="record-share-row-"]')
        .filter({ hasText: recipient.displayName });
      await expect(shareRow).toBeVisible({ timeout: 10_000 });
      await expect(shareRow).toContainText(/仅查看|View only/);
      await expect(shareRow).toContainText(/到期于|Expires/);
      await expect(dialog).not.toContainText(recipient.pid!);
      const sharePid = (await shareRow.getAttribute('data-testid'))?.replace(
        'record-share-row-',
        '',
      );
      expect(sharePid, 'share row exposes a stable public share PID').toMatch(/\S+/);
      expect(sharePid, 'share row must not expose an internal numeric ID').not.toMatch(/^\d+$/);
      screenshots.push(await captureScreenshot(ownerPage, testInfo, '02-owner-share-dialog'));

      await expect
        .poll(
          async () => {
            const response = await recipientPage.request.get(
              '/api/notifications?pageNum=1&pageSize=50',
            );
            if (!response.ok()) return null;
            const body = await response.json().catch(() => ({}));
            const rows = (body?.data?.records ?? []) as Array<Record<string, unknown>>;
            return (
              rows.find(
                (row) =>
                  String(row.sourceType ?? '') === MODEL_CODE &&
                  String(row.sourceId ?? '') === ownerAccount.pid,
              ) ?? null
            );
          },
          { message: 'recipient receives a record-collaboration notification' },
        )
        .toMatchObject({
          category: 'business',
          sourceType: MODEL_CODE,
          sourceId: ownerAccount.pid,
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
      const collaborativeView = recipientPage
        .getByTestId('quick-filters')
        .getByRole('button', { name: /协作客户|Collaborative Accounts/ });
      await expect(collaborativeView).toBeVisible({ timeout: 10_000 });
      const collaborativeListResponse = recipientPage.waitForResponse((response) => {
        if (!response.url().includes(`/api/dynamic/${MODEL_CODE}/list`)) return false;
        const filters = new URL(response.url()).searchParams.get('filters') ?? '';
        return filters.includes('$currentSharedRecordPids') && response.status() === 200;
      });
      await collaborativeView.click();
      await collaborativeListResponse;
      await expect(
        recipientPage.locator('tbody tr', { hasText: ownerAccount.name }).first(),
      ).toBeVisible();
      await expect(
        recipientPage.locator('tbody tr', { hasText: recipientAccount.name }),
      ).toHaveCount(0);
      await clickRowActionByLocator(
        recipientPage,
        recipientPage.locator('tbody tr', { hasText: ownerAccount.name }).first(),
        'view',
        'detail',
      );
      await expect(recipientPage.locator('[data-testid$="share-btn"]')).toHaveCount(0);
      screenshots.push(
        await captureScreenshot(recipientPage, testInfo, '03-recipient-shared-account-visible'),
      );

      await expireOwnShareForClockAdvance(sharePid!);
      await expectAccountVisibility(
        recipientPage,
        ownerAccount,
        false,
        'expired share removes recipient list/detail visibility',
      );

      await dialog.getByTestId('record-share-dialog-close').click();
      await ownerPage.locator('[data-testid$="share-btn"]').click();
      await expect(dialog).toBeVisible();
      const expiredShareRow = dialog.getByTestId(`record-share-row-${sharePid}`);
      await expect(expiredShareRow).toContainText(/已到期|Expired/);
      await expect(expiredShareRow.getByRole('button', { name: /续期|Renew/ })).toBeVisible();
      screenshots.push(
        await captureScreenshot(ownerPage, testInfo, '04-expired-collaboration-visible'),
      );

      await expiredShareRow.getByRole('button', { name: /续期|Renew/ }).click();
      await expect(dialog.getByTestId('record-share-editing-member')).toContainText(
        recipient.displayName,
      );
      await dialog.getByTestId('record-share-expiry-30d').click();
      const renewalResponse = ownerPage.waitForResponse(
        (response) =>
          response.url().endsWith(`/api/record-share/${sharePid}`) &&
          response.request().method() === 'PATCH',
      );
      await dialog.getByTestId('record-share-add-btn').click();
      const completedRenewalResponse = await renewalResponse;
      expect(completedRenewalResponse.ok(), 'owner renewal request').toBe(true);
      const renewalPayload = completedRenewalResponse.request().postDataJSON() as Record<
        string,
        unknown
      >;
      expect(renewalPayload).toEqual({
        permissionMask: 'read',
        expiresAt: expect.any(String),
      });
      expect(new Date(String(renewalPayload.expiresAt)).getTime()).toBeGreaterThan(
        Date.now() + 29 * 24 * 60 * 60 * 1000,
      );
      await expect(expiredShareRow).not.toContainText(/已到期|Expired/);
      await expect(expiredShareRow).toContainText(/到期于|Expires/);
      await expectAccountVisibility(
        recipientPage,
        ownerAccount,
        true,
        'renewed share restores recipient list/detail visibility',
      );
      screenshots.push(await captureScreenshot(ownerPage, testInfo, '05-collaboration-renewed'));

      const existingOwnerToasts = ownerPage.getByRole('button', {
        name: 'Close notification',
      });
      for (let index = await existingOwnerToasts.count(); index > 0; index -= 1) {
        await existingOwnerToasts.nth(index - 1).click();
      }
      await expect(ownerPage.getByTestId('toast-stack').getByRole('alert')).toHaveCount(0);

      await expiredShareRow.getByRole('button', { name: /编辑|Edit/ }).click();
      await dialog.getByTestId('record-share-permission-read-update').click();
      const upgradeResponse = ownerPage.waitForResponse(
        (response) =>
          response.url().endsWith(`/api/record-share/${sharePid}`) &&
          response.request().method() === 'PATCH',
      );
      await dialog.getByTestId('record-share-add-btn').click();
      const completedUpgradeResponse = await upgradeResponse;
      expect(completedUpgradeResponse.ok(), 'owner collaboration upgrade request').toBe(true);
      expect(completedUpgradeResponse.request().postDataJSON()).toEqual({
        permissionMask: 'read,update',
        expiresAt: expect.any(String),
      });
      await expect(shareRow).toContainText(/可协作|Collaborate/);
      await expect(dialog.locator('[data-testid^="record-share-row-"]')).toHaveCount(1);
      screenshots.push(await captureScreenshot(ownerPage, testInfo, '06-collaboration-upgraded'));

      const collaborativeName = `Collaborated Account ${uid}`;
      const allowedUpdate = await recipientPage.request.post(
        '/api/meta/commands/execute/crm:update_account',
        {
          data: {
            operationType: 'update',
            targetRecordPid: ownerAccount.pid,
            payload: { crm_acc_name: collaborativeName },
          },
        },
      );
      const allowedUpdateBody = await allowedUpdate.json().catch(() => ({}));
      expect(
        allowedUpdate.ok() && isSuccessBody(allowedUpdateBody),
        'a collaboration share must authorize update',
      ).toBe(true);
      ownerAccount.name = collaborativeName;

      await dialog.getByTestId('member-picker-add').click();
      await dialog.getByTestId('member-picker-search-input').fill(batchRecipient.email);
      const batchRecipientOption = dialog.getByTestId(`member-picker-option-${batchRecipient.pid}`);
      await expect(batchRecipientOption).toBeVisible({ timeout: 10_000 });
      await batchRecipientOption.click();
      const secondShareResponse = ownerPage.waitForResponse(
        (response) =>
          response.url().endsWith('/api/record-share') && response.request().method() === 'POST',
      );
      await dialog.getByTestId('record-share-add-btn').click();
      expect((await secondShareResponse).ok(), 'second collaborator share request').toBe(true);
      await expect(dialog.locator('[data-testid^="record-share-row-"]')).toHaveCount(2);

      await dialog.getByTestId('record-share-select-all').check();
      await dialog.getByTestId('record-share-batch-remove').click();
      const batchConfirm = dialog.getByTestId('record-share-batch-confirm');
      await expect(batchConfirm).toBeVisible();
      await expect(batchConfirm).toContainText(/2/);
      screenshots.push(
        await captureScreenshot(ownerPage, testInfo, '07-batch-revoke-confirmation'),
      );
      const revokeResponse = ownerPage.waitForResponse(
        (response) =>
          response.url().endsWith('/api/record-share/batch-delete') &&
          response.request().method() === 'POST',
      );
      await dialog.getByTestId('record-share-batch-confirm-ok').click();
      const completedRevokeResponse = await revokeResponse;
      expect(completedRevokeResponse.ok(), 'owner batch revoke request').toBe(true);
      expect(completedRevokeResponse.request().postDataJSON()).toMatchObject({
        sharePids: expect.arrayContaining([sharePid]),
      });
      await expect(dialog.getByTestId(`record-share-row-${sharePid}`)).toHaveCount(0);
      await expect(dialog.locator('[data-testid^="record-share-row-"]')).toHaveCount(0);

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
      await expect(recipientPage.getByText(/加载中|Loading/)).toHaveCount(0);
      screenshots.push(
        await captureScreenshot(recipientPage, testInfo, '08-account-hidden-after-revoke'),
      );

      expect(failedRuntimeRequests).toEqual([]);
      fs.writeFileSync(
        testInfo.outputPath(`crm-account-collaboration-${uid}.json`),
        `${JSON.stringify(
          {
            runId: uid,
            verdict: 'pass',
            technicalVerdict: 'pass',
            fixtureMode: 'self-seeded',
            dataMigration: 'out-of-scope-development-stage',
            cordysSourceEvidence: {
              sourceIds: CORDYS_SOURCE_IDS,
              assertionScope:
                'owner-driven collaboration list, grant, upgrade and revoke with access checks',
            },
            expectedScenarios: EXPECTED_SCENARIOS,
            completedScenarios: EXPECTED_SCENARIOS,
            coverage: Object.fromEntries(
              Object.entries(COVERAGE).map(([axis, expected]) => [
                axis,
                { expected, completed: expected },
              ]),
            ),
            screenshots,
            failedRuntimeRequests,
            recordIds: {
              owner: owner.pid,
              recipient: recipient.pid,
              batchRecipient: batchRecipient.pid,
              otherTenantMember: otherTenantMember.pid,
              ownerAccount: ownerAccount.pid,
              recipientAccount: recipientAccount.pid,
              share: sharePid,
            },
          },
          null,
          2,
        )}\n`,
      );
    } finally {
      await ownerContext.close();
      await recipientContext.close();
    }
  });
});

async function captureScreenshot(page: Page, testInfo: TestInfo, name: string): Promise<string> {
  const screenshotPath = testInfo.outputPath(`${name}.png`);
  await page.screenshot({ path: screenshotPath, fullPage: false });
  await testInfo.attach(name, { path: screenshotPath, contentType: 'image/png' });
  return screenshotPath;
}

async function expireOwnShareForClockAdvance(sharePid: string): Promise<void> {
  const pool = new Pool(PG_CONN);
  try {
    const result = await pool.query(
      `UPDATE ab_record_share
       SET expires_at = NOW() - INTERVAL '1 minute'
       WHERE pid = $1
       RETURNING pid, expires_at`,
      [sharePid],
    );
    expect(result.rowCount, `clock advance found share ${sharePid}`).toBe(1);
    expect(new Date(result.rows[0].expires_at).getTime()).toBeLessThan(Date.now());
  } finally {
    await pool.end();
  }
}

async function moveUserMembershipToIsolatedTenant(userPid: string, uid: string): Promise<void> {
  const pool = new Pool(PG_CONN);
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const membership = await client.query<{
      member_id: string;
    }>(
      `SELECT tm.id::text AS member_id
       FROM ab_tenant_member tm
       INNER JOIN ab_user u ON u.id = tm.user_id
       WHERE u.pid = $1
         AND tm.status = 'active'
         AND tm.deleted_flag = false
       LIMIT 1`,
      [userPid],
    );
    expect(membership.rowCount, `source membership for ${userPid}`).toBe(1);

    const tenantId = (
      BigInt(Date.now()) * 1_000_000n +
      BigInt(Math.floor(Math.random() * 1_000_000))
    ).toString();
    await client.query(
      `INSERT INTO ab_tenant
         (id, pid, name, display_name, status, deleted_flag, created_at, updated_at)
       VALUES ($1, substr(md5(random()::text || clock_timestamp()::text), 1, 26),
               $2, $3, 'active', false, now(), now())`,
      [tenantId, `crm-isolated-${uid}`, `CRM Isolated ${uid}`],
    );
    await client.query(
      `UPDATE ab_user_role
       SET deleted_flag = true, updated_at = now()
       WHERE member_id = $1`,
      [membership.rows[0].member_id],
    );
    const moved = await client.query(
      `UPDATE ab_tenant_member
       SET tenant_id = $1, updated_at = now()
       WHERE id = $2
       RETURNING tenant_id`,
      [tenantId, membership.rows[0].member_id],
    );
    expect(moved.rowCount, `move ${userPid} to isolated tenant`).toBe(1);
    await client.query('COMMIT');
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
    await pool.end();
  }
}

async function expectShareNotPersisted(recordPid: string, subjectPid: string): Promise<void> {
  const pool = new Pool(PG_CONN);
  try {
    const result = await pool.query<{ count: string }>(
      `SELECT count(*)::text AS count
       FROM ab_record_share
       WHERE resource_code = $1
         AND record_pid = $2
         AND subject_pid = $3`,
      [MODEL_CODE, recordPid, subjectPid],
    );
    expect(result.rows[0]?.count, 'cross-tenant rejection leaves no grant residue').toBe('0');
  } finally {
    await pool.end();
  }
}

function memberSearchRecords(body: any): unknown[] {
  const data = body?.data;
  if (Array.isArray(data)) return data;
  if (Array.isArray(data?.records)) return data.records;
  if (Array.isArray(data?.content)) return data.content;
  return [];
}

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
