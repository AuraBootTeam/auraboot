import { randomUUID } from 'node:crypto';
import { test, expect, type Page } from '../../fixtures';
async function ok(response: any) {
  expect(response.ok()).toBe(true);
  const body = await response.json();
  expect(String(body.code)).toBe('0');
  return body.data;
}
const title = (category: string, runId: string) => `E2E ${category} notification [${runId}]`;
async function seedNotifications(page: Page) {
  const me = await ok(await page.request.get('/api/auth/me'));
  const runId = `NOTIF_${randomUUID()}`;
  const response = await page.request.post('/api/test/fixture', { data: {
    name: 'notifications', testRunId: runId,
    params: { userId: String(me.user.id), tenantId: String(me.user.tenantId) },
  } });
  expect(response.ok(), 'fixture HTTP admission').toBe(true);
  const body = await response.json();
  expect(body.success).toBe(true);
  expect(body.testRunId).toBe(runId);
  expect(body.recordsCreated).toBe(4);
  expect(body.recordPids).toHaveLength(4);
  const data = await ok(await page.request.get('/api/notifications?pageNum=1&pageSize=100'));
  const owned = data.records.filter((row: any) => row.title.includes(runId));
  expect(owned).toHaveLength(4);
  expect(owned.map((row: any) => String(row.id)).sort()).toEqual([...body.recordPids].sort());
  for (const category of ['approval', 'system', 'alert', 'business']) {
    const row = owned.find((row: any) => row.category === category);
    expect(row.title).toBe(title(category, runId));
    expect(row.isRead).toBe(category === 'business');
  }
  return { runId, owned };
}
const listResponse = (page: Page, match: (url: URL) => boolean = () => true) => page.waitForResponse(r =>
  r.request().method() === 'GET' && new URL(r.url()).pathname === '/api/notifications' && match(new URL(r.url())));
async function openNotificationCentre(page: Page) {
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true');
  await page.getByTestId('notification-bell').click();
  await expect(page.getByTestId('notification-dropdown-panel')).toBeVisible();
  const loaded = listResponse(page);
  await page.getByTestId('view-all-notifications').click();
  await ok(await loaded);
  await expect(page.getByRole('heading', { name: '通知中心', exact: true })).toBeVisible();
}

test.describe('notification centre actions', () => {
test('NC-1: seeded notifications render with their categories', async ({ page }) => {
  const { runId } = await seedNotifications(page);
  await openNotificationCentre(page);
  for (const category of ['approval', 'system', 'alert', 'business'])
    await expect(page.getByRole('heading', { name: title(category, runId), exact: true })).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('30-notif-list.png'), fullPage: true });
});
test('NC-2: category tab actually filters (was: tab highlighted, list unchanged)', async ({ page }) => {
  const { runId } = await seedNotifications(page);
  await openNotificationCentre(page);
  const loaded = listResponse(page, u => u.searchParams.get('category') === 'approval');
  await page.getByRole('button', { name: '审批', exact: true }).click();
  const data = await ok(await loaded);
  expect(data.records.length).toBeGreaterThan(0);
  for (const row of data.records) expect(row.category).toBe('approval');
  await expect(page.getByRole('heading', { name: title('approval', runId), exact: true })).toBeVisible();
  for (const category of ['system', 'alert', 'business']) await expect(page.getByRole('heading', { name: title(category, runId), exact: true })).toHaveCount(0);
  await page.screenshot({ path: test.info().outputPath('31-notif-tab-approval.png'), fullPage: true });
});
test('NC-3: unread filter excludes the already-read row', async ({ page }) => {
  const { runId } = await seedNotifications(page);
  await openNotificationCentre(page);
  const loaded = listResponse(page, u => u.searchParams.get('isRead') === 'false');
  await page.getByRole('button', { name: '未读', exact: true }).click();
  const data = await ok(await loaded);
  expect(data.records.length).toBeGreaterThan(0);
  for (const row of data.records) expect(row.isRead).toBe(false);
  await expect(page.getByRole('heading', { name: title('business', runId), exact: true })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: title('system', runId), exact: true })).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('32-notif-unread.png'), fullPage: true });
});
test('NC-4: read filter shows only read rows (was: returned everything)', async ({ page }) => {
  const { runId } = await seedNotifications(page);
  await openNotificationCentre(page);
  const loaded = listResponse(page, u => u.searchParams.get('isRead') === 'true');
  await page.getByRole('button', { name: '未读', exact: true }).locator('..').getByRole('button', { name: '已读', exact: true }).click();
  const data = await ok(await loaded);
  expect(data.records.length).toBeGreaterThan(0);
  for (const row of data.records) expect(row.isRead).toBe(true);
  await expect(page.getByRole('heading', { name: title('business', runId), exact: true })).toBeVisible();
  for (const category of ['approval', 'system', 'alert']) await expect(page.getByRole('heading', { name: title(category, runId), exact: true })).toHaveCount(0);
  await page.screenshot({ path: test.info().outputPath('33-notif-read.png'), fullPage: true });
});
test('NC-5: delete selected removes the row for real (was: DELETE 404)', async ({ page }) => {
  const { runId, owned } = await seedNotifications(page);
  const id = String(owned.find((row: any) => row.category === 'approval').id);
  await openNotificationCentre(page);
  const row = page.locator('div.group.relative').filter({ has: page.getByRole('heading', { name: title('approval', runId), exact: true }) });
  await expect(row).toHaveCount(1);
  await row.getByRole('checkbox').check();
  const deleted = page.waitForResponse(r => r.request().method() === 'DELETE' && new URL(r.url()).pathname === '/api/notifications/batch');
  await page.getByRole('button', { name: /删除/ }).click();
  const response = await deleted;
  expect(new URL(response.url()).searchParams.get('ids')).toBe(id);
  expect(await ok(response)).toBe(1);
  await expect(page.getByRole('heading', { name: title('approval', runId), exact: true })).toHaveCount(0);
  const loaded = listResponse(page);
  await page.reload({ waitUntil: 'domcontentloaded' });
  const data = await ok(await loaded);
  expect(data.records.some((record: any) => String(record.id) === id)).toBe(false);
  await expect(page.getByRole('heading', { name: title('approval', runId), exact: true })).toHaveCount(0);
  await page.screenshot({ path: test.info().outputPath('34-notif-after-delete.png'), fullPage: true });
});
test('NC-6: header exposes a notification bell that opens the dropdown', async ({ page }) => {
  const { runId } = await seedNotifications(page);
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true');
  await page.getByTestId('notification-bell').click();
  const panel = page.getByTestId('notification-dropdown-panel');
  await expect(panel).toBeVisible();
  await expect(panel.getByText(title('approval', runId), { exact: true })).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('35-notif-bell.png'), fullPage: true });
});
test('NC-7: the header actually opens the notification SSE stream', async ({ page }) => {
  const stream = page.waitForResponse(r => new URL(r.url()).pathname === '/api/notifications/stream');
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('notification-bell')).toBeVisible();
  const response = await stream;
  expect(response.status()).toBe(200);
  expect(response.headers()['content-type']).toContain('text/event-stream');
  await page.getByTestId('notification-bell').click();
  await expect(page.getByTestId('notification-dropdown-panel')).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('36-notif-sse-header.png'), fullPage: true });
});

});
