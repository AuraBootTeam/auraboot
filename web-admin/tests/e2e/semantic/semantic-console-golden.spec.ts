import { test, expect } from '../../fixtures';
test.use({ storageState: 'tests/storage/admin.json' });
test.describe.configure({ timeout: 120000 });

test('semantic console: author → validate → publish → query end-to-end', async ({ page }) => {
  const errors: string[] = [];
  page.on('console', m => { if (m.type()==='error' && !m.text().includes('Optimize Dep')) errors.push(m.text()); });
  await page.goto('/semantic/models', { waitUntil: 'domcontentloaded' });
  // Let hydration + the post-hydration loader re-run settle (it resets React state)
  // before interacting — the same race the aurabot chat-bi golden documents.
  await page.waitForTimeout(2500);
  await expect(page.getByTestId('semantic-models-page')).toBeVisible({ timeout: 10000 });
  // wait for the initial catalog load to SETTLE (empty state visible) so the
  // tab click below does not race the in-flight reload()
  await expect(page.getByTestId('semantic-models-empty')).toBeVisible({ timeout: 15000 });
  await expect(page.getByTestId('semantic-models-error')).toHaveCount(0);

  // Author: load example → validate → publish
  await page.getByTestId('semantic-tab-author').click();
  await page.getByTestId('semantic-load-example').click();
  await expect(page.getByTestId('semantic-yaml-editor')).not.toHaveValue('');
  await page.getByTestId('semantic-validate').click();
  await expect(page.getByTestId('semantic-author-ok')).toBeVisible({ timeout: 10000 });
  const validateText = await page.getByTestId('semantic-author-ok').innerText();
  console.log('VALIDATE:', validateText.replace(/\n/g,' '));
  await page.getByTestId('semantic-publish').click();
  await expect(page.getByTestId('semantic-author-ok')).toContainText(/发布成功|01/, { timeout: 10000 });
  console.log('PUBLISH:', (await page.getByTestId('semantic-author-ok').innerText()).replace(/\n/g,' '));

  // The published model appears in the list
  await expect(page.getByTestId('semantic-model-item-demo_roles')).toBeVisible({ timeout: 10000 });

  // Browse + run query
  await page.getByTestId('semantic-model-item-demo_roles').click();
  await expect(page.getByTestId('semantic-tab-browse')).toBeVisible();
  await page.getByTestId('semantic-dim-status').click();
  await page.getByTestId('semantic-run-query').click();
  await expect(page.getByTestId('semantic-query-result')).toBeVisible({ timeout: 10000 });
  const rows = await page.locator('[data-testid="semantic-query-result"] tbody tr').count();
  console.log('QUERY_ROWS:', rows);
  await page.screenshot({ path: process.env.SHOT + '/console-01-query.png', fullPage: true });
  expect(rows).toBeGreaterThan(0);
  console.log('CONSOLE_ERRORS:', JSON.stringify(errors.slice(0,5)));
});
