// Platform list interaction golden for MES/WMS handover L3.
//
// This is intentionally action-driven: API is setup only. The browser enters from the sidebar
// menu, types a unique work-order name, observes the real list request and row-count change, then
// clicks the row's View action and verifies detail navigation/content.
//
//   BASE=http://127.0.0.1:5163 BACKEND_URL=http://127.0.0.1:6463 \
//   PG_DB=auraboot_63 node list-interaction-golden.mjs
import { chromium, expect } from '@playwright/test';
import { login as apiLogin, execCommand, makeReporter, uid } from '../harness.mjs';

const BASE = process.env.BASE || 'http://127.0.0.1:5163';
const OUT = new URL('.', import.meta.url).pathname;
const R = makeReporter();

async function seedWorkOrders() {
  const token = await apiLogin();
  const run = uid('LIST');
  const product = await execCommand(token, 'prod:create_product', {
    prod_name: `List material ${run}`,
    prod_type: 'raw_material',
    prod_unit: 'pcs',
  }, undefined, 'create', { allowError: true });
  const bom = await execCommand(token, 'eng_bom_pcba_mbom:create', {
    eng_bom_name: `List BOM ${run}`,
    eng_bom_product_id: product.recordId,
    eng_bom_version: 'A',
    eng_bom_output_qty: 1,
  }, undefined, 'create', { allowError: true });
  await execCommand(token, 'eng_bom_line_pcba_mbom:create', {
    eng_bom_line_bom_id: bom.recordId,
    eng_bom_line_material_id: product.recordId,
    eng_bom_line_qty: 1,
    eng_bom_line_unit: 'pcs',
  }, undefined, 'create', { allowError: true });

  const targetName = `Search Target ${run}`;
  const otherName = `Search Other ${run}`;
  const target = await execCommand(token, 'mfg_work_order_pcba_execution:create', {
    mfg_wo_name: targetName,
    mfg_wo_product_id: product.recordId,
    mfg_wo_bom_id: bom.recordId,
    mfg_wo_plan_qty: 10,
  }, undefined, 'create', { allowError: true });
  const other = await execCommand(token, 'mfg_work_order_pcba_execution:create', {
    mfg_wo_name: otherName,
    mfg_wo_product_id: product.recordId,
    mfg_wo_bom_id: bom.recordId,
    mfg_wo_plan_qty: 20,
  }, undefined, 'create', { allowError: true });
  if (!target.recordId || !other.recordId) {
    throw new Error(`work-order seed failed: target=${target.recordId} other=${other.recordId}`);
  }
  return { targetName, otherName, targetPid: target.recordId };
}

async function browserLogin(page) {
  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
  const sidebar = page.getByTestId('sidebar');
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const identifier = page.locator('#identifier');
    const password = page.locator('#password');
    await expect(identifier).toBeVisible({ timeout: 20_000 });
    await identifier.fill('admin@auraboot.com');
    await password.fill('Test2026x');
    await expect(identifier).toHaveValue('admin@auraboot.com');
    await expect(password).toHaveValue('Test2026x');
    await page.locator('button[type="submit"]').click();
    try {
      await expect(sidebar).toBeVisible({ timeout: 8_000 });
      return;
    } catch (error) {
      if (attempt === 3) throw error;
    }
  }
}

const seed = await seedWorkOrders();
const browser = await chromium.launch();
const context = await browser.newContext({
  ignoreHTTPSErrors: true,
  locale: 'zh-CN',
  viewport: { width: 1440, height: 900 },
});
const page = await context.newPage();

try {
  await browserLogin(page);

  const menu = page.getByRole('link', { name: /^(生产工单|Work Orders)$/ }).first();
  await expect(menu).toBeVisible({ timeout: 15_000 });
  await Promise.all([
    page.waitForURL(/\/p\/mfg_work_order_pcba_execution(?:\?|$)/, { timeout: 20_000 }),
    menu.click(),
  ]);
  await expect(page.getByTestId('list-search-input')).toBeVisible({ timeout: 20_000 });
  await expect.poll(() => page.locator('tbody tr').count(), { timeout: 20_000 }).toBeGreaterThan(1);
  const before = await page.locator('tbody tr').count();

  const listResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET'
      && response.url().includes('/api/dynamic/mfg_work_order_pcba_execution/list'),
    { timeout: 20_000 },
  );
  await page.getByTestId('list-search-input').fill(seed.targetName);
  const response = await listResponse;
  R.check('L3', 'typing search drives the real work-order list API', response.ok(),
    `status=${response.status()}`);
  await expect.poll(() => page.locator('tbody tr').count(), { timeout: 20_000 }).toBe(1);
  const after = await page.locator('tbody tr').count();
  const row = page.locator('tbody tr').first();
  await expect(row).toContainText(seed.targetName);
  await expect(row).not.toContainText(seed.otherName);
  R.check('L3', 'typing narrows row count and returns only the matching work order',
    after === 1 && after < before, `rows ${before}→${after}`);
  await page.screenshot({ path: `${OUT}/list-interaction-filtered.png`, fullPage: true });

  const viewAction = row.getByTestId('row-action-detail');
  await expect(viewAction).toBeVisible({ timeout: 10_000 });
  const detailResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET'
      && response.url().includes(
        `/api/dynamic/mfg_work_order_pcba_execution/${seed.targetPid}`,
      ),
    { timeout: 20_000 },
  );
  const [, detailApiResponse] = await Promise.all([
    page.waitForURL(
      new RegExp(`/p/mfg_work_order_pcba_execution/view/${seed.targetPid}(?:\\?|$)`),
      { timeout: 20_000 },
    ),
    detailResponse,
    viewAction.click(),
  ]);
  await expect(detailApiResponse.ok()).toBeTruthy();
  await expect(page.getByText(seed.targetName, { exact: true }).first()).toBeVisible({
    timeout: 20_000,
  });
  R.check('L3', 'row View action navigates to the exact detail record',
    page.url().includes(`/view/${seed.targetPid}`), page.url());
  await page.screenshot({ path: `${OUT}/list-interaction-detail.png`, fullPage: true });
} catch (error) {
  R.check('L3', 'browser list interaction completes without exception', false,
    String(error?.message || error).slice(0, 240));
  await page.screenshot({ path: `${OUT}/list-interaction-error.png`, fullPage: true }).catch(() => {});
} finally {
  await browser.close();
}

const summary = R.summary();
console.log(`\n=== LIST INTERACTION GOLDEN: ${summary.pass}/${summary.total} pass ===`);
process.exit(summary.fail > 0 ? 1 : 0);
