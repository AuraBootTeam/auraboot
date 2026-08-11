import { test, expect, type Page } from '@playwright/test';
import { Client } from 'pg';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { PG_CONN } from '../../helpers/environments';
import { loginViaUI } from '../../helpers/wd-fixtures';
import { findRowByContent, uniqueId, waitForDynamicPageLoad } from '../helpers';

type ApiEnvelope<T> = {
  code?: number | string;
  context?: unknown;
  data?: T;
  message?: string;
};

type AuthoringSession = {
  sessionPid: string;
};

type BusinessWriteSnapshot = {
  pid: string;
  title: string;
  status: string;
  published_at: string | null;
  published_by: string | null;
  outbox_count: string;
  behavior_outcome_count: string;
  im_message_count: string;
  webhook_delivery_count: string;
};

test.use({ storageState: { cookies: [], origins: [] } });

test.describe('Contextual authoring PC business-write guard golden', () => {
  test.describe.configure({ mode: 'serial', timeout: 120_000 });

  test.beforeEach(async ({ page }) => {
    await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
    await expect(page.locator('nav')).toBeVisible({ timeout: 15_000 });
    await page.setViewportSize({ width: 1440, height: 900 });
  });

  test('PC-AUTH-011 @critical — UI and direct command deny publish with zero business side effects', async ({
    page,
  }) => {
    const fixture = uniqueId('PC-AUTH-011');
    const title = `Authoring write guard ${fixture}`;
    const recordPid = await createAnnouncementFixture(page, {
      title,
      content: `Business-write denial evidence ${fixture}`,
      announcement_priority: 'normal',
      status: 'draft',
      pinned: false,
    });

    await navigateToAnnouncementList(page);
    const row = await findRowByContent(page, title);
    await expect(row).toContainText(/draft|草稿/i);
    const before = await readBusinessWriteSnapshot(recordPid);
    expect(before.status).toBe('draft');

    const sessionResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/authoring/sessions',
    );
    await page.getByRole('main').first().getByRole('button', { name: '配置此页' }).click();
    const session = await expectApiData<AuthoringSession>(
      await sessionResponse,
      'enter announcement authoring',
    );
    await expect(page.getByTestId('contextual-authoring-surface')).toBeVisible();
    await page.getByRole('button', { name: '交互预览' }).click();

    let publishCommandRequests = 0;
    page.on('request', (request) => {
      if (
        request.method() === 'POST' &&
        new URL(request.url()).pathname === '/api/meta/commands/execute/announcement:publish'
      ) {
        publishCommandRequests += 1;
      }
    });

    const authoringRow = await findRowByContent(page, title);
    await authoringRow.getByTestId('row-action-more').click();
    await page.getByTestId('row-action-publish').click();
    const confirmDialog = page.getByTestId('confirm-dialog');
    if (await confirmDialog.isVisible().catch(() => false)) {
      await page.getByTestId('confirm-ok').click();
    }
    await expect(page.getByTestId('authoring-write-blocked')).toContainText('已拦截真实业务写入');
    expect(publishCommandRequests).toBe(0);
    expect(await readBusinessWriteSnapshot(recordPid)).toEqual(before);

    const directResponse = await page.request.post(
      '/api/meta/commands/execute/announcement:publish',
      {
        headers: {
          'X-Aura-Authoring-Session': session.sessionPid,
        },
        data: {
          payload: {},
          targetRecordPid: recordPid,
          clientRequestId: `pc-auth-011-${fixture}`,
        },
      },
    );
    const directBody = (await directResponse.json()) as ApiEnvelope<unknown>;
    const after = await readBusinessWriteSnapshot(recordPid);

    expect.soft(directResponse.status(), JSON.stringify(directBody)).toBe(409);
    expect.soft(directBody.context).toBe('authoring.preview.business-write-denied');
    expect.soft(after).toEqual(before);
  });
});

async function navigateToAnnouncementList(page: Page): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav').first();
  await nav.getByText('公告管理', { exact: true }).click();
  const leafLink = nav.locator('a[href="/p/ab_announcement"]');
  await expect(leafLink).toBeVisible();
  await leafLink.evaluate((element: HTMLAnchorElement) => element.click());
  await expect(page).toHaveURL(/\/p\/ab_announcement(?:[/?#]|$)/);
  await waitForDynamicPageLoad(page);
}

async function createAnnouncementFixture(
  page: Page,
  data: Record<string, unknown>,
): Promise<string> {
  const response = await page.request.post('/api/dynamic/ab_announcement/create', {
    data,
  });
  const text = await response.text();
  expect(response.ok(), `create announcement fixture: HTTP ${response.status()}: ${text}`).toBe(
    true,
  );
  const body = JSON.parse(text) as ApiEnvelope<{ pid?: string }>;
  expect(String(body.code ?? '0'), `create announcement fixture: ${text}`).toBe('0');
  expect(body.data?.pid, `create announcement fixture pid: ${text}`).toBeTruthy();
  return body.data!.pid!;
}

async function readBusinessWriteSnapshot(recordPid: string): Promise<BusinessWriteSnapshot> {
  const db = new Client(PG_CONN);
  await db.connect();
  try {
    const result = await db.query<BusinessWriteSnapshot>(
      `
        SELECT announcement.pid,
               announcement.title,
               announcement.status,
               announcement.published_at::text,
               announcement.published_by::text,
               (SELECT COUNT(*)::text FROM ab_outbox
                  WHERE tenant_id = announcement.tenant_id) AS outbox_count,
               (SELECT COUNT(*)::text FROM ab_behavior_outcome_outbox
                  WHERE tenant_id = announcement.tenant_id) AS behavior_outcome_count,
               (SELECT COUNT(*)::text FROM ab_im_message
                  WHERE tenant_id = announcement.tenant_id) AS im_message_count,
               (SELECT COUNT(*)::text FROM ab_webhook_delivery_log
                  WHERE tenant_id = announcement.tenant_id) AS webhook_delivery_count
          FROM ab_announcement announcement
         WHERE announcement.pid = $1
           AND (announcement.deleted_flag = FALSE OR announcement.deleted_flag IS NULL)
      `,
      [recordPid],
    );
    expect(result.rows, `announcement ${recordPid} DB snapshot`).toHaveLength(1);
    return result.rows[0];
  } finally {
    await db.end();
  }
}

async function expectApiData<T>(
  response: {
    ok(): boolean;
    status(): number;
    text(): Promise<string>;
  },
  label: string,
): Promise<T> {
  const text = await response.text();
  const body = JSON.parse(text) as ApiEnvelope<T>;
  expect(response.ok(), `${label}: HTTP ${response.status()}: ${text}`).toBe(true);
  expect(String(body.code ?? '0'), `${label}: API envelope ${text}`).toBe('0');
  return body.data as T;
}
