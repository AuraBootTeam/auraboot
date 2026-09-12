/** Report definition save/read integration; replaces the retired dual-write contract. */
import { readFile } from 'node:fs/promises';
import { expect, test } from '../../tests/fixtures';

test.use({
  storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
  locale: 'zh-CN',
});

test('report menu supports save, reopen, version rollback and canonical JSON download', async ({
  page,
}) => {
  await page.goto('/home');
  const entry = page.locator('a[href="/p/c/report_management"]').first();
  await expect(entry).toBeVisible();
  await entry.click();
  await page.getByRole('button', { name: /新建报表|New report/ }).click();
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
  await page.getByRole('button', { name: /^(保存|Save)$/ }).click();
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
  await page.goto('/home');
  await page.locator('a[href="/p/c/report_management"]').first().click();
  const row = page.getByRole('row').filter({ hasText: title });
  await expect(row).toBeVisible();
  await expect(page.getByTestId('list-toolbar')).toHaveCount(0);
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/report-list.png`,
    fullPage: true,
  });
  await row.getByRole('button', { name: /打开|Open/ }).click();
  await expect(page).toHaveURL(new RegExp(`/report-designer/${created.pid}`));
  await expect(page.getByPlaceholder('Report Title')).toHaveValue(title);
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/report-reopened.png`,
    fullPage: true,
  });
  await page.getByPlaceholder('Report Title').fill(`${title} revised`);
  const updatedResponse = page.waitForResponse(
    (r) =>
      r.request().method() === 'PUT' &&
      new URL(r.url()).pathname === `/api/report-definitions/${created.pid}`,
  );
  await page.getByRole('button', { name: /^(保存|Save)$/ }).click();
  expect((await updatedResponse).status()).toBe(200);
  await page.getByRole('button', { name: 'Version History' }).click();
  await page.getByRole('button', { name: /^v1\b/ }).click();
  await page.getByRole('button', { name: /^(回滚|Rollback)$/ }).click();
  const rollbackResponse = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().includes('/rollback'),
  );
  await page.getByRole('button', { name: 'Confirm Rollback', exact: true }).click();
  expect((await rollbackResponse).status()).toBe(200);
  await expect(page.getByPlaceholder('Report Title')).toHaveValue(title);
  const restored = await page.request.get(`/api/report-definitions/${created.pid}`);
  expect((await restored.json()).data.dsl).toEqual(written.dsl);
  await page.getByRole('button', { name: /关闭版本面板|Close version/ }).click();
  const downloadEvent = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export JSON', exact: true }).click();
  const download = await downloadEvent;
  const exportedPath = `${process.env.AURA_EVIDENCE_DIR}/${created.pid}.report.json`;
  await download.saveAs(exportedPath);
  const exported = JSON.parse(await readFile(exportedPath, 'utf8'));
  expect(exported.reportPid).toBe(created.pid);
  expect(exported.reportDsl).toEqual(written.dsl);
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/report-saved.png`,
    fullPage: true,
  });
});
