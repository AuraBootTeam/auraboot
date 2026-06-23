/**
 * G4 — Payout "Post to GL" one-click action L4 UI golden (crm-incentive)
 *
 * Closes the G4 friction from the CRM revamp closure review: a PAID commission payout
 * still needed the GL journal posted by manually running the finance command
 * `fin:generate_journal_from_commission_payout`. The platform extension model has no
 * clean fully-automatic path (see crm/docs/revamp/13-platform-finding-payout-gl-auto.md:
 * winner-take-all handlers, no DB-write in event listeners, no execute_command
 * side-effect), so the realistic, architecture-respecting fix is a one-click action on
 * the payout detail that invokes the existing finance command (finance still owns the
 * posting — dependency direction stays finance→crm).
 *
 * This proves the BUTTON actually works in the real browser (not just that it imports —
 * the §2.2 gate-gap: a button whose action doesn't resolve passes the validator but is a
 * no-op): the "过账总账 / Post to GL" action appears on a PAID payout with a real label
 * (not the bare "execute"), and clicking it posts a balanced fin_journal_entry
 * (DR 6601 / CR 2211) for that payout.
 *
 * Run:
 *   PLAYWRIGHT_BASE_URL=http://localhost:5239 PG_CONTAINER=auraboot-crm-gap-postgres \
 *   NO_PROXY=localhost,127.0.0.1 \
 *   npx playwright test tests/e2e/crm/crm-g4-payout-gl.spec.ts \
 *     --project=chromium-m5 --config=tests/e2e/crm/g4.playwright.config.ts
 */
import { test, expect, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { loadEnv } from '../../helpers/environments';

const BASE = loadEnv('crm-gap').urls.base;
const PG = process.env.PG_CONTAINER || 'auraboot-crm-gap-postgres';
const EMAIL = 'admin@auraboot.com';
const PW = 'Test2026x';
const SHOT = '/tmp/g4-e2e';
const PAYOUT_PID = 'g4po1';
const PAYOUT_CODE = 'G4-PO-1';

function psql(sql: string): string {
  return execFileSync('docker', ['exec', PG, 'psql', '-U', 'auraboot', '-d', 'aura_boot', '-tAc', sql], {
    encoding: 'utf8',
  }).trim();
}

/** Seed a PAID payout with a GL voucher draft so only the "Post to GL" action is offered. */
function seedPaidPayout(): void {
  const tenant = psql('SELECT tenant_id FROM mt_crm_inc_payout LIMIT 1');
  psql(
    `INSERT INTO mt_crm_inc_payout (pid, tenant_id, crm_inc_payout_code, crm_inc_payout_rep_id, ` +
      `crm_inc_payout_period, crm_inc_payout_gross_amount, crm_inc_payout_net_amount, ` +
      `crm_inc_payout_status, crm_inc_payout_paid_date, crm_inc_payout_voucher_status, ` +
      `crm_inc_payout_voucher_debit_account, crm_inc_payout_voucher_credit_account, ` +
      `crm_inc_payout_voucher_amount, created_at, updated_at) VALUES ('${PAYOUT_PID}', ${tenant}, ` +
      `'${PAYOUT_CODE}', 'g4rep', '2026-06', 600, 600, 'paid', '2026-06-04', 'draft', ` +
      `'Sales Expense - Commission', 'Wages Payable', 600, now(), now()) ON CONFLICT (pid) DO NOTHING`,
  );
}

function journalCount(): number {
  return Number(
    psql(
      `SELECT count(*) FROM mt_fin_journal_entry WHERE fin_je_source_type='commission_payout' ` +
        `AND fin_je_source_id='${PAYOUT_PID}'`,
    ),
  );
}

async function uiLogin(page: Page): Promise<void> {
  for (let attempt = 1; attempt <= 3; attempt++) {
    await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
    const emailInput = page.locator('input#identifier, input#email');
    const hasLogin = await emailInput.isVisible({ timeout: 8000 }).catch(() => false);
    if (!hasLogin) break;
    await emailInput.fill(EMAIL);
    await page.locator('input#password').fill(PW);
    await page.locator('button:has-text("立即登录"), button[type="submit"]').first().click();
    await page.waitForURL((u) => !u.pathname.includes('login'), { timeout: 20000 }).catch(() => {});
    if (page.url().includes('tenant-selection')) {
      const enter = page
        .getByRole('button', { name: /进入|选择|Enter|Demo|AuraBoot/ })
        .or(page.getByText(/AuraBoot Demo/).first());
      await enter.first().click({ timeout: 5000 }).catch(() => {});
      await page.waitForURL((u) => !u.pathname.includes('tenant-selection'), { timeout: 15000 }).catch(() => {});
    }
    const stillOnLogin = await emailInput.isVisible({ timeout: 2000 }).catch(() => false);
    if (!stillOnLogin) break;
    if (attempt === 3) throw new Error('UI login failed after 3 attempts');
  }
  await expect(page.locator('input#identifier, input#email')).toHaveCount(0, { timeout: 5000 });
}

test.describe('CRM G4 payout Post-to-GL action (L4 UI golden)', () => {
  test.beforeAll(() => {
    seedPaidPayout();
  });

  test.beforeEach(async ({ page }) => {
    await uiLogin(page);
  });

  test('clicking "过账总账" on a PAID payout posts a balanced GL journal', async ({ page }) => {
    await page.goto(`${BASE}/p/crm_inc_payout`, { waitUntil: 'domcontentloaded' });

    // open the seeded PAID payout detail by clicking its code (not a hardcoded URL)
    await expect(page.getByText(PAYOUT_CODE).first()).toBeVisible({ timeout: 15000 });
    await page.getByText(PAYOUT_CODE).first().click();
    await expect(page.getByText(/概览|Overview/).first()).toBeVisible({ timeout: 20000 });

    // the action carries a real localized label, not the bare "execute" placeholder
    const postGl = page.getByRole('button', { name: /过账总账|Post to GL/ });
    await expect(postGl.first()).toBeVisible({ timeout: 10000 });
    // no GL journal for this payout yet
    expect(journalCount(), 'no journal before clicking').toBe(0);

    await postGl.first().click();
    // wait for the command to post the entry (poll the DB the action wrote through)
    await expect
      .poll(() => journalCount(), { timeout: 15000, intervals: [500, 1000, 1500] })
      .toBe(1);

    // the posted entry is balanced (debit == credit == the payout net 600)
    const totals = psql(
      `SELECT fin_je_total_debit||'|'||fin_je_total_credit FROM mt_fin_journal_entry ` +
        `WHERE fin_je_source_type='commission_payout' AND fin_je_source_id='${PAYOUT_PID}'`,
    );
    const [dr, cr] = totals.split('|').map(Number);
    expect(dr, 'debit total').toBeCloseTo(600, 2);
    expect(cr, 'credit total == debit (balanced)').toBeCloseTo(dr, 2);

    await page.screenshot({ path: `${SHOT}/g4_payout_post_gl.png`, fullPage: true });
  });
});
