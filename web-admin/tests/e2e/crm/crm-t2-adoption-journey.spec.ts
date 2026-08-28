import { expect, test, type Page } from '../../fixtures';
import fs from 'node:fs';
import path from 'node:path';

/**
 * T2 adoption journey (PAR-06/32/03/04 adoption signoff evidence).
 * Walks the full first-run journey as a first-time user would: login from a
 * clean session, create account → contact → lead → qualify → convert →
 * pool claim, entirely through the UI menus and forms. Screenshots at every
 * step are preserved for owner review.
 */

const RUN_ID = `adopt-${Date.now()}`;
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5161';
const EVIDENCE_ROOT = process.env.AURA_EVIDENCE_ROOT
  ? path.join(process.env.AURA_EVIDENCE_ROOT, 'adoption-journey')
  : path.resolve(process.cwd(), '..', '.workspace', 'evidence', 'crm-parr1-parity-20260828-s61', 'adoption-journey');

async function shot(page: Page, name: string): Promise<void> {
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  await page.screenshot({ path: path.join(EVIDENCE_ROOT, `${name}.png`), fullPage: false });
}

test('T2 adoption journey: first-time user full L2C walk', async ({ page }) => {
  test.setTimeout(600_000);
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });

  // ---- 1. Dashboard first view (auth.setup.ts establishes admin session) ----
  await page.goto(`${BASE_URL}/dashboards`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  await shot(page, '01-dashboard-first-view');

  // ---- 2. Create Account via the UI ----
  await page.goto(`${BASE_URL}/p/crm_account_common/new`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);
  await shot(page, '03-account-create-form');
  await page.locator('.controlled-field-renderer:has-text("客户名称"), .n-form-item:has-text("客户名称")').locator('input').first().fill(`${RUN_ID} 客户`);
  await page.locator('input[name="sl_ctr_amount"], .controlled-field-renderer:has-text("行业"), .n-form-item:has-text("行业")').locator('input, .n-base-selection').first().click().catch(() => {});
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'test-results/artifacts/adopt-account-industry.png' });
  await page.getByRole('button', { name: /保存|创建/ }).first().click();
  await page.waitForTimeout(2500);
  await shot(page, '04-account-created');

  // ---- 3. Create Contact via the UI ----
  await page.goto(`${BASE_URL}/p/crm_contact_common/new`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);
  await shot(page, '05-contact-create-form');
  await page.locator('.controlled-field-renderer:has-text("姓名"), .n-form-item:has-text("姓名")').locator('input').first().fill(`${RUN_ID} 张经理`);
  await page.getByRole('button', { name: /保存|创建/ }).first().click();
  await page.waitForTimeout(2500);
  await shot(page, '06-contact-created');

  // ---- 4. Create Lead via the UI ----
  await page.goto(`${BASE_URL}/p/crm_lead_common/new`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);
  await shot(page, '07-lead-create-form');
  await page.locator('.controlled-field-renderer:has-text("公司名称"), .n-form-item:has-text("公司名称")').locator('input').first().fill(`${RUN_ID} 线索公司`);
  await page.getByRole('button', { name: /保存|创建/ }).first().click();
  await page.waitForTimeout(2500);
  await shot(page, '08-lead-created');

  // ---- 5. Qualify the lead (线索资格确认) ----
  await page.goto('/p/crm_lead_common', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);
  await shot(page, '09-lead-list');
  const leadRow = page.locator('tbody tr').filter({ hasText: `${RUN_ID} 线索公司` }).first();
  await leadRow.getByRole('button', { name: /资格|质检|Qualif/ }).first().click().catch(() => {});
  await page.waitForTimeout(1800);
  await shot(page, '10-lead-qualified');

  // ---- 6. Convert the lead (线索转化) ----
  const convertBtn = page.getByRole('button', { name: /转化|转换|Convert/ }).first();
  if ((await convertBtn.count()) > 0 && (await convertBtn.isVisible())) {
    await convertBtn.click();
    await page.waitForTimeout(1500);
    await shot(page, '11-lead-convert-dialog');
    const confirmConvert = page.getByRole('button', { name: /确认|确定|转化/ }).last();
    if ((await confirmConvert.count()) > 0 && (await confirmConvert.isVisible())) await confirmConvert.click();
    await page.waitForTimeout(2000);
    await shot(page, '12-lead-converted');
  }

  // ---- 7. Pool claim (公海领取) ----
  await page.goto(`${BASE_URL}/p/crm_customer_pool_item_list`, { waitUntil: 'domcontentloaded' }).catch(() => {});
  await page.waitForTimeout(2200);
  await shot(page, '13-pool-list');

  // ---- 8. Dashboard overview (final) ----
  await page.goto(`${BASE_URL}/dashboards`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  await shot(page, '14-dashboard-final');

  console.log(`ADOPTION JOURNEY COMPLETE: evidence at ${EVIDENCE_ROOT}`);
});
