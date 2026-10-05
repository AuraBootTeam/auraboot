import { randomUUID } from 'node:crypto';
import { test, expect, type Page } from '../../fixtures';

async function bodyOK(response: any) {
  expect(response.ok()).toBe(true);
  const body = await response.json();
  expect(String(body.code)).toBe('0');
  return body.data;
}
async function seed(page: Page, name: string) {
  const me = await bodyOK(await page.request.get('/api/auth/me'));
  expect(me.user.id).toBeTruthy();
  expect(me.user.tenantId).toBeTruthy();
  const response = await page.request.post('/api/test/fixture', {
    data: { name, testRunId: `COLLAB_${randomUUID()}`, params: { count: 2, userId: String(me.user.id), tenantId: String(me.user.tenantId) } },
  });
  expect(response.ok(), 'fixture HTTP admission must succeed').toBe(true);
  const body = await response.json();
  expect(body.success, 'fixture must create real records').toBe(true);
  expect(body.testRunId).toMatch(/\S+/);
  expect(body.recordsCreated).toBe(2);
  expect(body.recordPids).toHaveLength(2);
  for (const id of body.recordPids) {
    expect(id).toMatch(/^\d+$/);
    const saved = await bodyOK(await page.request.get(`/api/inbox/${id}`));
    expect(String(saved.id)).toBe(id);
    expect(saved.status).toBe('pending');
    expect(saved.isRead).toBe(false);
    expect(saved.title).toContain(body.testRunId);
  }
  return body.recordPids as string[];
}
async function openInbox(page: Page) {
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true');
  await page.getByTestId('inbox-badge').click();
  await expect(page.getByTestId('inbox-dropdown')).toBeVisible();
  const response = page.waitForResponse(r => r.request().method() === 'GET' && new URL(r.url()).pathname === '/api/inbox');
  await page.getByTestId('inbox-view-all').click();
  await bodyOK(await response);
  await expect(page.getByTestId('unified-inbox-page')).toBeVisible();
  await expect(page.getByTestId('inbox-loading-skeleton')).toHaveCount(0);
  await expect(page.getByTestId('inbox-error-state')).toHaveCount(0);
}

test.describe('inbox actions', () => {
test('IB-1: the "All" tab shows the real unread total, not a double-count', async ({ page }) => {
  const ids = (await Promise.all(['inbox_items', 'inbox_alert', 'inbox_assignment'].map(name => seed(page, name)))).flat();
  await openInbox(page);
  for (const id of ids) await expect(page.getByTestId(`inbox-item-${id}`)).toBeVisible();
  const summary = await bodyOK(await page.request.get('/api/inbox/unread-summary'));
  expect(summary.total).toBeGreaterThanOrEqual(ids.length);
  const sum = Object.entries(summary).filter(([key]) => key !== 'total').reduce((n, [, value]) => n + Number(value), 0);
  expect(sum).toBe(summary.total);
  await expect(page.getByTestId('inbox-tab-count-all')).toHaveText(String(summary.total));
  expect(Number(await page.getByTestId('inbox-tab-count-all').innerText())).not.toBe(summary.total + sum);
  await page.screenshot({ path: test.info().outputPath('20-inbox-counts.png'), fullPage: true });
});

test('IB-2: dismiss removes the item from the list and from the backend', async ({ page }) => {
  const [id] = await seed(page, 'inbox_alert');
  await openInbox(page);
  await expect(page.getByTestId(`inbox-item-${id}`)).toBeVisible();
  const response = page.waitForResponse(r => r.request().method() === 'PUT' && new URL(r.url()).pathname === `/api/inbox/${id}/dismiss`);
  await page.getByTestId(`inbox-dismiss-${id}`).click();
  await bodyOK(await response);
  await expect(page.getByTestId(`inbox-item-${id}`)).toHaveCount(0);
  const saved = await bodyOK(await page.request.get(`/api/inbox/${id}`));
  expect(saved.status).toBe('dismissed');
  const loaded = page.waitForResponse(r => r.request().method() === 'GET' && new URL(r.url()).pathname === '/api/inbox');
  await page.reload({ waitUntil: 'domcontentloaded' });
  await bodyOK(await loaded);
  await expect(page.getByTestId('inbox-loading-skeleton')).toHaveCount(0);
  await expect(page.getByTestId(`inbox-item-${id}`)).toHaveCount(0);
  await page.screenshot({ path: test.info().outputPath('21-inbox-after-dismiss.png'), fullPage: true });
});

test('IB-3: mark all read drives the unread count to zero', async ({ page }) => {
  const ids = await seed(page, 'inbox_alert');
  await openInbox(page);
  const before = await bodyOK(await page.request.get('/api/inbox/unread-count'));
  expect(before).toBeGreaterThanOrEqual(ids.length);
  for (const id of ids) await expect(page.getByTestId(`inbox-item-${id}`)).toBeVisible();
  const response = page.waitForResponse(r => r.request().method() === 'PUT' && new URL(r.url()).pathname === '/api/inbox/read-all');
  await page.getByTestId('unified-inbox-page').getByTestId('inbox-mark-all-read').click();
  await bodyOK(await response);
  expect(await bodyOK(await page.request.get('/api/inbox/unread-count'))).toBe(0);
  for (const id of ids) expect((await bodyOK(await page.request.get(`/api/inbox/${id}`))).isRead).toBe(true);
  await expect(page.getByTestId('inbox-tab-count-all')).toHaveText('0');
  await expect(page.getByTestId('inbox-badge').locator('span')).toHaveCount(0);
  await expect(page.getByTestId('toast-stack')).toContainText('所有待办已标为已读');
  await page.screenshot({ path: test.info().outputPath('22-inbox-mark-all-read.png'), fullPage: true });
});

test('IB-4: type tab filters the list to that type only', async ({ page }) => {
  const ids = await seed(page, 'inbox_alert');
  const other = await seed(page, 'inbox_assignment');
  await openInbox(page);
  for (const id of [...ids, ...other]) await expect(page.getByTestId(`inbox-item-${id}`)).toBeVisible();
  const response = page.waitForResponse(r => r.request().method() === 'GET' && new URL(r.url()).pathname === '/api/inbox' && new URL(r.url()).searchParams.get('itemType') === 'alert');
  await page.getByTestId('inbox-tab-alert').click();
  const data = await bodyOK(await response);
  expect(data.records.length).toBeGreaterThanOrEqual(ids.length);
  for (const row of data.records) expect(row.itemType).toBe('alert');
  for (const id of ids) await expect(page.getByTestId(`inbox-item-${id}`)).toBeVisible();
  for (const id of other) await expect(page.getByTestId(`inbox-item-${id}`)).toHaveCount(0);
  await page.screenshot({ path: test.info().outputPath('23-inbox-tab-alert.png'), fullPage: true });
});

});
