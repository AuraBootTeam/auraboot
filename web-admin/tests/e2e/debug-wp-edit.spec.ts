import { test, expect } from '@playwright/test';

test('debug wp edit URL', async ({ page, request }) => {
  // login
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  // get a work package pid
  const resp = await page.request.get('/api/dynamic/ap_work_package/list?pageSize=1');
  const body = await resp.json();
  const pid = body?.data?.records?.[0]?.pid || body?.data?.records?.[0]?.id;
  console.log('WP pid:', pid);

  if (pid) {
    await page.goto(`/p/ap_work_package/${pid}/edit`, { waitUntil: 'domcontentloaded' });
    console.log('URL after goto:', page.url());
    await page.waitForTimeout(2000);
    console.log('URL after wait:', page.url());
    const heading = await page
      .locator('h1, h2, [data-testid="dynamic-page-edit"]')
      .first()
      .textContent()
      .catch(() => 'not found');
    console.log('Heading:', heading);
  }
});
