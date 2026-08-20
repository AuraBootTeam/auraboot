import {
  expect,
  test,
  type APIResponse,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';
import fs from 'node:fs';
import { createCookieSessionStorage } from 'react-router';
import { Pool } from 'pg';
import { executeCommandViaApi, uniqueId } from '../helpers';
import { PG_CONN } from '../../helpers/environments';

const PASSWORD = 'Test2026x';
const MODEL_CODE = 'crm_activity_common';
const JWT_TOKEN_KEY = 'jwtToken';
const DEFAULT_BASE_URL = 'http://127.0.0.1:5173';
const EXPECTED_SCENARIOS = [
  'empty-state',
  'mention-picker-and-public-pid',
  'root-comment-create',
  'mention-notification-dedup',
  'notification-center-render',
  'notification-deep-link',
  'non-author-controls-hidden',
  'two-level-reply',
  'reply-notification',
  'author-edit',
  'thread-lineage-persistence',
  'root-cascade-delete',
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

type TenantSpace = {
  id?: string | number;
  tenantId?: string | number;
  type?: string;
  spaceType?: string;
};

type NotificationRow = {
  id: number;
  title: string;
  content?: string;
  sourceType?: string;
  sourceId?: string;
  isRead?: boolean;
};

async function expectOk(response: APIResponse, label: string): Promise<void> {
  const body = await response.json().catch(() => ({}));
  expect(
    response.ok() && ['0', '200', 'success'].includes(String(body?.code ?? '').toLowerCase()),
    `${label}: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
}

async function provisionCrmAdmin(adminPage: Page, user: TestUser): Promise<string> {
  const response = await adminPage.request.post('/api/admin/users', {
    data: {
      email: user.email,
      displayName: user.displayName,
      initialPassword: user.password,
      roleCodes: ['crm_admin'],
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
  const users = Array.isArray(searchBody?.data) ? searchBody.data : [];
  const created = users.find((candidate: { email?: string }) => candidate.email === user.email);
  expect(created?.pid, `public user pid for ${user.email}`).toBeTruthy();
  return String(created.pid);
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

async function notificationsFor(page: Page, recordPid: string): Promise<NotificationRow[]> {
  const response = await page.request.get('/api/notifications?pageNum=1&pageSize=100');
  await expectOk(response, 'list notifications');
  const body = await response.json();
  const rows = (body?.data?.records ?? []) as NotificationRow[];
  return rows.filter((row) => row.sourceType === MODEL_CODE && String(row.sourceId) === recordPid);
}

async function attachScreenshot(page: Page, testInfo: TestInfo, name: string): Promise<string> {
  const path = testInfo.outputPath(`${name}.png`);
  await page.screenshot({ path, fullPage: true });
  await testInfo.attach(name, { path, contentType: 'image/png' });
  return path;
}

test.describe('CRM follow-up comments — Cordys PAR-12 parity', () => {
  test.setTimeout(150_000);

  test('PAR12-UI-01: mention, reply, author controls, notification deep-link and cascade delete @critical @golden', async ({
    page,
    browser,
    baseURL,
  }, testInfo) => {
    const resolvedBaseURL = baseURL ?? DEFAULT_BASE_URL;
    const uid = uniqueId('crm_comment');
    const teammate: TestUser = {
      email: `crm-comment-${uid}@e2e.local`,
      displayName: `CRM Reviewer ${uid}`,
      password: PASSWORD,
    };
    teammate.pid = await provisionCrmAdmin(page, teammate);

    const activity = await executeCommandViaApi(page, 'crm:create_activity', {
      crm_act_type: 'task',
      crm_act_subject: `PAR-12 客户回访 ${uid}`,
      crm_act_content: `确认评论协作闭环 ${uid}`,
      crm_act_source: 'manual',
      crm_act_status: 'open',
      crm_act_priority: 'high',
    });
    expect(activity.recordId, 'self-seeded activity public PID').toBeTruthy();
    const activityPid = activity.recordId;
    const rootText = `@${teammate.displayName} 核对回访结论 ${uid}`;
    const replyText = `已核对，建议明天继续跟进 ${uid}`;
    const editedRootText = `${rootText}（已补充客户预算信息）`;
    const screenshots: string[] = [];

    const teammateContext = await newAuthenticatedContext(browser, resolvedBaseURL, teammate);
    const teammatePage = await teammateContext.newPage();

    try {
      await page.goto(`/p/${MODEL_CODE}/view/${activityPid}`, { waitUntil: 'domcontentloaded' });
      const thread = page.getByTestId('record-comments');
      await expect(
        thread,
        'formal activity detail renders the reusable comment thread',
      ).toBeVisible({
        timeout: 20_000,
      });
      await expect(thread.getByTestId('comment-empty')).toBeVisible();
      screenshots.push(await attachScreenshot(page, testInfo, '01-activity-comment-empty'));

      const rootInput = thread.getByTestId('comment-input').first();
      await rootInput.fill(`@${uid}`);
      const suggestionPanel = thread.getByTestId('mention-suggestions');
      await expect(suggestionPanel).toBeVisible({ timeout: 10_000 });
      await expect(suggestionPanel.getByText(teammate.displayName)).toBeVisible();
      screenshots.push(await attachScreenshot(page, testInfo, '02-mention-picker'));
      await suggestionPanel.getByText(teammate.displayName).click();
      await rootInput.fill(rootText);

      const createResponse = page.waitForResponse(
        (response) =>
          response.url().endsWith(`/api/records/${MODEL_CODE}/${activityPid}/comments`) &&
          response.request().method() === 'POST',
      );
      await thread.getByTestId('comment-submit').first().click();
      expect((await createResponse).ok(), 'root comment create response').toBe(true);
      const rootCard = thread
        .locator('article[data-testid^="comment-"]')
        .filter({ hasText: uid })
        .first();
      await expect(rootCard).toContainText(rootText);
      await expect(thread.getByTestId('comment-empty')).toHaveCount(0);
      screenshots.push(await attachScreenshot(page, testInfo, '03-root-comment-with-mention'));

      await expect
        .poll(async () => (await notificationsFor(teammatePage, activityPid)).length, {
          message: 'mention creates exactly one deduplicated notification for the teammate',
        })
        .toBe(1);
      const mentionNotifications = await notificationsFor(teammatePage, activityPid);
      expect(mentionNotifications[0]).toMatchObject({
        title: '你在评论中被 @ 提及',
        sourceType: MODEL_CODE,
        sourceId: activityPid,
        isRead: false,
      });

      await teammatePage.goto('/notifications', { waitUntil: 'domcontentloaded' });
      const notificationCard = teammatePage
        .locator('.group.relative')
        .filter({ hasText: '你在评论中被 @ 提及' })
        .filter({ hasText: uid })
        .first();
      await expect(notificationCard, 'notification center shows the new mention').toBeVisible({
        timeout: 15_000,
      });
      screenshots.push(
        await attachScreenshot(teammatePage, testInfo, '04-mention-notification'),
      );
      await notificationCard.getByText('你在评论中被 @ 提及').click();
      await expect(teammatePage).toHaveURL(
        new RegExp(`/p/${MODEL_CODE}/view/${activityPid.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`),
        { timeout: 10_000 },
      );

      const teammateThread = teammatePage.getByTestId('record-comments');
      await expect(teammateThread).toBeVisible({ timeout: 15_000 });
      const teammateRoot = teammateThread
        .locator('article[data-testid^="comment-"]')
        .filter({ hasText: rootText })
        .first();
      await expect(teammateRoot.getByRole('button', { name: /回复|Reply/ })).toBeVisible();
      await expect(teammateRoot.getByRole('button', { name: /编辑|Edit/ })).toHaveCount(0);
      await expect(teammateRoot.getByRole('button', { name: /删除|Delete/ })).toHaveCount(0);

      await teammateRoot.getByRole('button', { name: /回复|Reply/ }).click();
      const replyInput = teammateRoot.getByTestId('comment-input');
      await replyInput.fill(replyText);
      const replyResponse = teammatePage.waitForResponse(
        (response) =>
          response.url().endsWith(`/api/records/${MODEL_CODE}/${activityPid}/comments`) &&
          response.request().method() === 'POST',
      );
      await teammateRoot.getByTestId('comment-submit').click();
      expect((await replyResponse).ok(), 'reply create response').toBe(true);
      await expect(teammateThread.getByText(replyText)).toBeVisible();
      await expect(teammateThread.getByText(/回复|replied to/).last()).toBeVisible();
      screenshots.push(
        await attachScreenshot(teammatePage, testInfo, '05-two-level-reply-thread'),
      );

      await expect
        .poll(
          async () => {
            const notifications = await notificationsFor(page, activityPid);
            return notifications.filter((row) => row.title === '你的评论收到了回复').length;
          },
          { message: 'root author receives one reply notification' },
        )
        .toBe(1);

      await page.reload({ waitUntil: 'domcontentloaded' });
      const refreshedThread = page.getByTestId('record-comments');
      const refreshedRoot = refreshedThread
        .locator('article[data-testid^="comment-"]')
        .filter({ hasText: rootText })
        .first();
      await expect(refreshedThread.getByText(replyText)).toBeVisible({ timeout: 15_000 });
      await refreshedRoot.getByRole('button', { name: /编辑|Edit/ }).click();
      const editInput = refreshedRoot.getByTestId('comment-input');
      await editInput.fill(editedRootText);
      await refreshedRoot.getByTestId('comment-submit').click();
      await expect(refreshedRoot).toContainText(editedRootText);
      await expect(refreshedRoot).toContainText(/已编辑|edited/);

      const pool = new Pool(PG_CONN);
      try {
        const persisted = await pool.query<{
          pid: string;
          parent_pid: string | null;
          reply_to_user_pid: string | null;
          deleted_flag: boolean;
        }>(
          `SELECT pid, parent_pid, reply_to_user_pid, deleted_flag
           FROM ab_record_comment
           WHERE model_code = $1 AND record_pid = $2
           ORDER BY created_at ASC, id ASC`,
          [MODEL_CODE, activityPid],
        );
        expect(persisted.rows, 'one root and one normalized direct reply').toHaveLength(2);
        expect(persisted.rows[0].parent_pid).toBeNull();
        expect(persisted.rows[1].parent_pid).toBe(persisted.rows[0].pid);
        expect(persisted.rows[1].reply_to_user_pid).toBeTruthy();
        expect(persisted.rows.every((row) => row.deleted_flag === false)).toBe(true);
      } finally {
        await pool.end();
      }

      await refreshedRoot.getByRole('button', { name: /删除|Delete/ }).click();
      await expect(refreshedRoot).toContainText(/确认删除|Delete this comment/);
      screenshots.push(
        await attachScreenshot(page, testInfo, '06-inline-cascade-delete-confirmation'),
      );
      await refreshedRoot.getByRole('button', { name: /确认|Confirm/ }).click();
      await expect(refreshedThread.getByTestId('comment-empty')).toBeVisible();
      await expect(refreshedThread.getByText(replyText)).toHaveCount(0);

      const poolAfterDelete = new Pool(PG_CONN);
      try {
        const deleted = await poolAfterDelete.query<{ deleted_count: string }>(
          `SELECT count(*) FILTER (WHERE deleted_flag = true)::text AS deleted_count
           FROM ab_record_comment
           WHERE model_code = $1 AND record_pid = $2`,
          [MODEL_CODE, activityPid],
        );
        expect(deleted.rows[0]?.deleted_count, 'root deletion soft-deletes its reply').toBe('2');
      } finally {
        await poolAfterDelete.end();
      }

      fs.writeFileSync(
        testInfo.outputPath(`crm-followup-comments-parity-${uid}.json`),
        `${JSON.stringify(
          {
            runId: uid,
            verdict: 'pass',
            technicalVerdict: 'pass',
            fixtureMode: 'self-seeded',
            dataMigration: 'out-of-scope-development-stage',
            expectedScenarios: EXPECTED_SCENARIOS,
            completedScenarios: EXPECTED_SCENARIOS,
            screenshots,
            failedRuntimeRequests: [],
          },
          null,
          2,
        )}\n`,
      );
    } finally {
      await teammateContext.close();
    }
  });
});
