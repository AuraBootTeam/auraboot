import { test, expect, type Page, type APIRequestContext } from '../../fixtures';
import { createCookieSessionStorage } from 'react-router';
import { randomUUID } from 'node:crypto';
import { BACKEND_URL, BASE_URL as WEB_BASE_URL } from '../../helpers/environments';
const sessionStorage = createCookieSessionStorage({
  cookie: {
    name: '__session',
    httpOnly: true,
    path: '/',
    sameSite: 'lax',
    secrets: [process.env.SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'],
    secure: false,
  },
});

interface Session {
  jwt: string;
}

async function apiLogin(
  context: APIRequestContext,
  email: string,
  password: string,
): Promise<Session> {
  const loginResponse = await context.post(`${BACKEND_URL}/api/auth/login`, {
    data: { email, password },
  });
  expect(loginResponse.ok(), `login failed for ${email}: ${loginResponse.status()}`).toBeTruthy();
  const login = (await loginResponse.json()).data;
  let jwt = login.jwt as string;
  if (!login.tenantId) {
    const spacesResponse = await context.get(`${BACKEND_URL}/api/tenant-selection/my-spaces`, {
      headers: { Authorization: `Bearer ${jwt}` },
    });
    expect(spacesResponse.ok()).toBeTruthy();
    const spaces = (await spacesResponse.json()).data as Array<{
      tenantId: string;
      spaceType: string;
    }>;
    const businessTenant = spaces.find((space) => space.spaceType === 'business');
    expect(businessTenant, `no business tenant for ${email}`).toBeTruthy();
    const selectionResponse = await context.post(`${BACKEND_URL}/api/tenant-selection/process`, {
      headers: { Authorization: `Bearer ${jwt}` },
      data: { action: 'select', tenantId: businessTenant!.tenantId },
    });
    expect(selectionResponse.ok()).toBeTruthy();
    jwt = (await selectionResponse.json()).data.jwt;
  }
  expect(jwt).toEqual(expect.any(String));
  return { jwt };
}

async function installSessionCookie(page: Page, session: Session) {
  const state = await sessionStorage.getSession();
  state.set('jwtToken', session.jwt);
  const set = await sessionStorage.commitSession(state, { maxAge: 604800 });
  const value = set.match(/__session=([^;]+)/)?.[1];
  expect(value).toBeTruthy();
  await page.context().addCookies([
    { name: '__session', value: value!, url: WEB_BASE_URL, httpOnly: true, sameSite: 'Lax' },
    { name: 'locale', value: 'zh-CN', url: WEB_BASE_URL, sameSite: 'Lax' },
  ]);
  await page.addInitScript(() => localStorage.setItem('locale', 'zh-CN'));
}

async function loginAs(page: Page, account: { email: string; password: string }) {
  const session = await apiLogin(page.request, account.email, account.password);
  await installSessionCookie(page, session);
  return session;
}

async function ok(response: any) {
  expect(response.ok()).toBe(true);
  const body = await response.json();
  expect(String(body.code)).toBe('0');
  return body.data;
}
test('SSE-REAL-01: own UI read action reaches another open header over real SSE', async ({
  page,
  browser,
}) => {
  test.setTimeout(60000);
  const runId = `SSE_${randomUUID()}`;
  const account = { email: `${runId.toLowerCase()}@sse-closure.test`, password: 'Closure2026x!' };
  const created = await ok(
    await page.request.post('/api/meta/commands/execute/admin:create_member', {
      data: { payload: { name: runId, ...account } },
    }),
  );
  expect(created.data.userPid).toEqual(expect.any(String));
  const context = await browser.newContext({
    baseURL: WEB_BASE_URL,
    storageState: { cookies: [], origins: [] },
    locale: 'zh-CN',
    viewport: { width: 1440, height: 1000 },
  });
  try {
    const consumer = await context.newPage();
    await loginAs(consumer, account);
    const me = await ok(await consumer.request.get('/api/auth/me'));
    const seeded = await page.request.post('/api/test/fixture', {
      data: {
        name: 'notifications',
        testRunId: runId,
        params: { userId: String(me.user.id), tenantId: String(me.user.tenantId) },
      },
    });
    expect(seeded.ok()).toBe(true);
    const seed = await seeded.json();
    expect(seed.success).toBe(true);
    expect(seed.recordsCreated).toBe(4);
    const baseline = await ok(await consumer.request.get('/api/notifications/unread-count'));
    expect(baseline.count).toBe(3);
    if (process.env.SSE_DROP_UI_LISTENER === '1')
      await consumer.addInitScript(() => {
        const original = EventSource.prototype.addEventListener;
        EventSource.prototype.addEventListener = function (type: any, ...args: any[]) {
          if (type === 'unread-count') return;
          return original.call(this, type, ...(args as [any, any]));
        };
      });
    const cdp = await context.newCDPSession(consumer);
    await cdp.send('Network.enable');
    const streamIds = new Set<string>();
    const frames: Array<{ event: string; count: number; timestamp: number }> = [];
    cdp.on('Network.requestWillBeSent', (r) => {
      if (new URL(r.request.url).pathname === '/api/notifications/stream')
        streamIds.add(r.requestId);
    });
    cdp.on('Network.eventSourceMessageReceived', (r) => {
      if (streamIds.has(r.requestId) && r.eventName === 'unread-count') {
        const v = JSON.parse(r.data);
        frames.push({ event: r.eventName, count: v.count, timestamp: r.timestamp });
      }
    });
    const consumerReads: string[] = [];
    consumer.on('request', (r) => {
      if (new URL(r.url()).pathname === '/api/notifications/unread-count')
        consumerReads.push(r.url());
    });
    await consumer.goto('/');
    await expect(consumer.locator('header[data-hydrated]')).toHaveAttribute(
      'data-hydrated',
      'true',
    );
    await expect.poll(() => frames.some((f) => f.count === 3)).toBe(true);
    await expect(consumer.getByTestId('notification-bell').locator('span')).toHaveText('3');
    const producer = await context.newPage();
    await producer.goto('/');
    await expect(producer.locator('header[data-hydrated]')).toHaveAttribute(
      'data-hydrated',
      'true',
    );
    await producer.getByTestId('notification-bell').click();
    await producer.getByTestId('view-all-notifications').click();
    await expect(producer.getByRole('heading', { name: '通知中心', exact: true })).toBeVisible();
    const title = `E2E approval notification [${runId}]`;
    const records = await ok(
      await consumer.request.get('/api/notifications?pageNum=1&pageSize=100'),
    );
    const own = records.records.filter((x: any) => x.title.includes(runId));
    expect(own).toHaveLength(4);
    const target = own.find((x: any) => x.title === title);
    expect(target.isRead).toBe(false);
    const row = producer
      .locator('div.group.relative')
      .filter({ has: producer.getByRole('heading', { name: title, exact: true }) });
    await expect(row).toHaveCount(1);
    await row.hover();
    const beforeFrames = frames.length;
    const beforeReads = consumerReads.length;
    const mutation = producer.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new URL(r.url()).pathname === `/api/notifications/${target.id}/read`,
    );
    await row.getByRole('button', { name: '已读', exact: true }).click();
    await ok(await mutation);
    await expect.poll(() => frames.slice(beforeFrames).some((f) => f.count === 2)).toBe(true);
    const persisted = await ok(
      await consumer.request.get('/api/notifications?pageNum=1&pageSize=100'),
    );
    expect(persisted.records.find((x: any) => String(x.id) === String(target.id)).isRead).toBe(
      true,
    );
    expect(consumerReads.length).toBe(beforeReads);
    await test.info().attach('real-sse-frames', {
      body: JSON.stringify({
        targetId: String(target.id),
        beforeCount: 3,
        afterCount: 2,
        frames,
        consumerUnreadPollsDuringAction: consumerReads.length - beforeReads,
        authority:
          'real UI producer + real persisted read + CDP real SSE; direct fixture only seeds existing records',
      }),
      contentType: 'application/json',
    });
    await expect(consumer.getByTestId('notification-bell').locator('span')).toHaveText('2', {
      timeout: 5000,
    });
    await consumer.getByTestId('notification-bell').click();
    await expect(consumer.getByTestId('notification-dropdown-panel')).toBeVisible();
    for (const category of ['system', 'alert'])
      await expect(
        consumer
          .getByTestId('notification-dropdown-panel')
          .getByText(`E2E ${category} notification [${runId}]`, { exact: true }),
      ).toBeVisible();
    await consumer.screenshot({
      path: test.info().outputPath('SSE-REAL-01-consumer.png'),
      fullPage: true,
    });
  } finally {
    await context.close();
  }
});

test('SSE-REAL-02: production record-share persists a new notification and updates an open recipient header', async ({
  page,
  browser,
}) => {
  test.setTimeout(60000);
  const runId = `SSE_NEW_${randomUUID()}`;
  const account = { email: `${runId.toLowerCase()}@sse-closure.test`, password: 'Closure2026x!' };
  const created = await ok(
    await page.request.post('/api/meta/commands/execute/admin:create_member', {
      data: { payload: { name: runId, ...account } },
    }),
  );
  const userPid = created.data.userPid;
  expect(userPid).toEqual(expect.any(String));
  const asset = await ok(
    await page.request.post('/api/dynamic/tasset_asset/create', {
      data: {
        tasset_as_code: runId,
        tasset_as_name: runId,
        tasset_as_serial: runId,
        tasset_as_status: 'available',
        tasset_as_location: 'SSE isolated verification',
      },
    }),
  );
  expect(asset.pid).toEqual(expect.any(String));
  const context = await browser.newContext({
    baseURL: WEB_BASE_URL,
    storageState: { cookies: [], origins: [] },
    locale: 'zh-CN',
    viewport: { width: 1440, height: 1000 },
  });
  try {
    const consumer = await context.newPage();
    await loginAs(consumer, account);
    expect((await ok(await consumer.request.get('/api/notifications/unread-count'))).count).toBe(0);
    const cdp = await context.newCDPSession(consumer);
    await cdp.send('Network.enable');
    const streamIds = new Set<string>();
    const counts: number[] = [];
    cdp.on('Network.requestWillBeSent', (r) => {
      if (new URL(r.request.url).pathname === '/api/notifications/stream')
        streamIds.add(r.requestId);
    });
    cdp.on('Network.eventSourceMessageReceived', (r) => {
      if (streamIds.has(r.requestId) && r.eventName === 'unread-count')
        counts.push(JSON.parse(r.data).count);
    });
    const polls: string[] = [];
    consumer.on('request', (r) => {
      if (new URL(r.url()).pathname === '/api/notifications/unread-count') polls.push(r.url());
    });
    await consumer.goto('/');
    await expect(consumer.locator('header[data-hydrated]')).toHaveAttribute(
      'data-hydrated',
      'true',
    );
    await expect.poll(() => counts.includes(0)).toBe(true);
    await expect(consumer.getByTestId('notification-bell').locator('span')).toHaveCount(0);
    const before = counts.length;
    const beforePolls = polls.length;
    // Only the newly created test member receives this in-app test notification.
    await ok(
      await page.request.post('/api/record-share', {
        data: {
          resourceCode: 'tasset_asset',
          recordPid: asset.pid,
          subjectType: 'member',
          subjectPid: userPid,
          permissionMask: 'read',
        },
      }),
    );
    await expect.poll(() => counts.slice(before).includes(1)).toBe(true);
    await expect(consumer.getByTestId('notification-bell').locator('span')).toHaveText('1');
    expect(polls.length).toBe(beforePolls);
    const records = await ok(
      await consumer.request.get('/api/notifications?pageNum=1&pageSize=100'),
    );
    const own = records.records.filter(
      (n: any) => n.sourceType === 'tasset_asset' && n.sourceId === asset.pid,
    );
    expect(own).toHaveLength(1);
    expect(own[0].isRead).toBe(false);
    expect(own[0].category).toBe('business');
    expect(own[0].title).toBe('记录协作权限已更新');
    expect(own[0].content).toBe('你已获得一条共享记录的仅查看权限，请从对应的协作视图查看。');
    await consumer.getByTestId('notification-bell').click();
    await expect(
      consumer.getByTestId('notification-dropdown-panel').getByText(own[0].title, { exact: true }),
    ).toBeVisible();
    await test.info().attach('new-production-notification', {
      body: JSON.stringify({
        assetPid: asset.pid,
        recipientPid: userPid,
        notificationId: String(own[0].id),
        counts,
        consumerPollsDuringSend: polls.length - beforePolls,
        authority:
          'real production record-share API -> NotificationService/InAppChannel -> persisted notification -> real SSE -> browser header/dropdown; producer UI not covered',
      }),
      contentType: 'application/json',
    });
    await consumer.screenshot({
      path: test.info().outputPath('SSE-REAL-02-new-notification.png'),
      fullPage: true,
    });
  } finally {
    await context.close();
  }
});
