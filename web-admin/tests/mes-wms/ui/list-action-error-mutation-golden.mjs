// Mutation-discriminating browser golden for OSS #1501 / handover L1.
//
// Run once against a Vite source mutation whose ListPageContent action onError calls
// setError(err.message), expecting a full-page ErrorAlert; then run against the fixed source,
// expecting the rejected action to remain a toast while the list stays rendered.
//
//   BASE=http://127.0.0.1:5264 EXPECT_ACTION_ERROR=blanked node list-action-error-mutation-golden.mjs
//   BASE=http://127.0.0.1:5263 EXPECT_ACTION_ERROR=retained node list-action-error-mutation-golden.mjs
import { chromium, expect } from '@playwright/test';
import { login as apiLogin, execCommand, makeReporter, uid } from '../harness.mjs';

const BASE = process.env.BASE || 'http://127.0.0.1:5163';
const EXPECTED = process.env.EXPECT_ACTION_ERROR || 'retained';
if (!['retained', 'blanked'].includes(EXPECTED)) {
  throw new Error(`EXPECT_ACTION_ERROR must be retained or blanked, got ${EXPECTED}`);
}
const OUT = new URL('.', import.meta.url).pathname;
const R = makeReporter();

async function seedRejectedOperation() {
  const token = await apiLogin();
  const run = uid('MUT');
  const product = await execCommand(token, 'prod:create_product', {
    prod_name: `Mutation material ${run}`,
    prod_type: 'raw_material',
    prod_unit: 'pcs',
  }, undefined, 'create', { allowError: true });
  const bom = await execCommand(token, 'eng_bom_pcba_mbom:create', {
    eng_bom_name: `Mutation BOM ${run}`,
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
  const workOrder = await execCommand(token, 'mfg_work_order_pcba_execution:create', {
    mfg_wo_name: `Mutation WO ${run}`,
    mfg_wo_product_id: product.recordId,
    mfg_wo_bom_id: bom.recordId,
    mfg_wo_plan_qty: 10,
  }, undefined, 'create', { allowError: true });
  const operationName = `Mutation Op ${run}`;
  const operation = await execCommand(token, 'mfg_work_order_operation_pcba_execution:create', {
    mfg_wop_work_order_id: workOrder.recordId,
    mfg_wop_seq: 10,
    mfg_wop_name: operationName,
    mfg_wop_planned_qty: 10,
    mfg_wop_operator: 'Mutation Operator',
  }, undefined, 'create', { allowError: true });
  if (!operation.recordId) {
    throw new Error(`operation seed failed: ${JSON.stringify(operation.raw).slice(0, 300)}`);
  }
  return { operationName };
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

const seed = await seedRejectedOperation();
const browser = await chromium.launch();
const context = await browser.newContext({
  ignoreHTTPSErrors: true,
  locale: 'zh-CN',
  viewport: { width: 1440, height: 900 },
});
const page = await context.newPage();

try {
  await browserLogin(page);
  const menu = page.getByRole('link', { name: /^(工序执行|Work Order Ops)$/ }).first();
  await expect(menu).toBeVisible({ timeout: 15_000 });
  await Promise.all([
    page.waitForURL(/\/p\/mfg_work_order_operation_pcba_execution(?:\?|$)/, { timeout: 20_000 }),
    menu.click(),
  ]);
  await expect(page.getByTestId('list-search-input')).toBeVisible({ timeout: 20_000 });

  const listResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET'
      && response.url().includes('/api/dynamic/mfg_work_order_operation_pcba_execution/list'),
    { timeout: 20_000 },
  );
  await page.getByTestId('list-search-input').fill(seed.operationName);
  await listResponse;
  // This page's current DSL does not apply the global keyword to every operation field.
  // L3 owns list-filter semantics; this mutation test only needs the exact seeded action row.
  const row = page.locator('tbody tr').filter({ hasText: seed.operationName });
  await expect(row).toHaveCount(1, { timeout: 20_000 });
  await expect(row).toContainText(seed.operationName);
  const start = row.getByTestId('row-action-start');
  await expect(start).toBeVisible({ timeout: 10_000 });

  const rejectedResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST'
      && decodeURIComponent(response.url()).includes(
        '/api/meta/commands/execute/mfg_work_order_operation_pcba_execution:start',
      ),
    { timeout: 20_000 },
  );
  await start.click();
  const response = await rejectedResponse;
  R.check('L1', 'row Start action reaches backend and is rejected by the interlock',
    !response.ok(), `status=${response.status()}`);

  const errorHeading = page.getByRole('heading', { name: '加载失败' });
  if (EXPECTED === 'blanked') {
    await expect(errorHeading).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('tbody tr')).toHaveCount(0);
    R.check('L1', 'MUTANT: rejected action promotes error to full-page loading failure',
      await errorHeading.isVisible(), `base=${BASE}`);
  } else {
    await expect(errorHeading).toHaveCount(0);
    await expect(row).toBeVisible({ timeout: 15_000 });
    await expect(row).toContainText(seed.operationName);
    R.check('L1', 'FIXED: rejected action leaves the list and matching row rendered',
      await row.isVisible(), `base=${BASE}`);
  }
  await page.screenshot({
    path: `${OUT}/list-action-error-${EXPECTED}.png`,
    fullPage: true,
  });
} catch (error) {
  R.check('L1', `${EXPECTED} browser run completes without exception`, false,
    String(error?.message || error).slice(0, 240));
  await page.screenshot({
    path: `${OUT}/list-action-error-${EXPECTED}-error.png`,
    fullPage: true,
  }).catch(() => {});
} finally {
  await browser.close();
}

const summary = R.summary();
console.log(`\n=== LIST ACTION ERROR ${EXPECTED.toUpperCase()}: ${summary.pass}/${summary.total} pass ===`);
process.exit(summary.fail > 0 ? 1 : 0);
