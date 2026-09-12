/** Report definition save/read integration; replaces the retired dual-write contract. */
import { expect, test } from '../../tests/fixtures';

test.use({
  storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
  locale: 'zh-CN',
});

test('report designer saves one canonical definition and reopens the complete DSL', async ({
  page,
}) => {
  await page.goto('/home');
  const entry = page.locator('a[href="/report-designer"]').first();
  await expect(entry).toBeVisible();
  await entry.click();
  await expect(page.getByTestId('report-canvas')).toBeVisible();
  const title = `Analytics report ${Date.now()}`;
  await page.getByPlaceholder('Report Title').fill(title);
  await expect(page.getByPlaceholder('Report Title')).toHaveValue(title);
  const pageWrites: string[] = [];
  page.on('request', (request) => {
    if (
      /\/api\/pages(?:\/|$)/.test(new URL(request.url()).pathname) &&
      ['POST', 'PUT'].includes(request.method())
    )
      pageWrites.push(request.url());
  });
  const savedResponse = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/report-definitions',
  );
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  const saved = await savedResponse;
  expect(saved.status()).toBe(200);
  const written = saved.request().postDataJSON();
  expect(written.dsl.title).toBe(title);
  const created = (await saved.json()).data;
  expect(created.pid).toBeTruthy();
  expect(pageWrites).toEqual([]);
  const read = await page.request.get(`/api/report-definitions/${created.pid}`);
  expect(read.status()).toBe(200);
  expect((await read.json()).data.dsl).toEqual(written.dsl);
  await page.goto(`/report-designer/${created.pid}`);
  await expect(page.getByPlaceholder('Report Title')).toHaveValue(title);
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/report-saved.png`,
    fullPage: true,
  });
});
