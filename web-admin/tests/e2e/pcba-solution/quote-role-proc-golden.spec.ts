import fs from 'node:fs';
import * as XLSX from 'xlsx';
import type { Browser, BrowserContext, Response } from '@playwright/test';
import { test, expect, type Page } from '../../fixtures';
import { ensureSidebarExpanded } from '../helpers';
import { loginViaUI } from '../../helpers/wd-fixtures';
import {
  isTransientViteDynamicImportIssue,
  openQuoteRolePage,
  queryDynamicRecords,
  QUOTE_ROLE_TEST_PASSWORD,
  type QuoteRoleUser,
} from './quote-e2e-helpers';

/**
 * Purchase price library deep golden AS THE PROCUREMENT ROLE (qo_procurement), not admin.
 *
 * Drives the procurement person's core flow: open the 采购价格库 menu from the sidebar,
 * import a purchase-price Excel through the toolbar action (real file upload through the
 * command dialog), and verify the imported rows land (list UI + dynamic record readback).
 * A zero-401/403 collector runs across the whole session (DDR-2026-06-29 §8).
 *
 * RUN (local host-first stack, business roles reconciled):
 *   PLAYWRIGHT_BASE_URL=http://127.0.0.1:<web> BACKEND_URL=http://127.0.0.1:<be> PW_SKIP_WEBSERVER=1 \
 *     node_modules/.bin/playwright test tests/e2e/pcba-solution/quote-role-proc-golden.spec.ts \
 *     --project=chromium --no-deps
 */

const ADMIN_EMAIL = process.env.ADMIN_EMAIL || 'admin@auraboot.com';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Test2026x';

const PROC_USER: QuoteRoleUser = {
  key: 'smoke_proc',
  email: 'smoke-proc@e2e.local',
  displayName: 'Smoke Procurement',
  password: QUOTE_ROLE_TEST_PASSWORD,
  roleCodes: ['qo_procurement'],
};

const PRICE_LIBRARY_PATH = '/p/qo_offline_material_price_common';

type ForbiddenHit = { step: string; url: string; status: number };

function createPurchasePriceWorkbook(filePath: string, partNos: string[]): string {
  const rows: unknown[][] = [
    ['物料编码', '规格描述', '供应商', '单价', '币种', '备注'],
    ...partNos.map((partNo, index) => [
      partNo,
      `Smoke proc import spec ${index + 1}`,
      'Smoke Supplier Co.',
      (1.5 + index * 0.25).toFixed(4),
      'CNY',
      'role golden import',
    ]),
  ];
  const sheet = XLSX.utils.aoa_to_sheet(rows);
  const workbook = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(workbook, sheet, 'prices');
  // this xlsx ESM build has no fs binding — write to a buffer and persist via node:fs
  const bytes = XLSX.write(workbook, { bookType: 'xlsx', type: 'buffer' }) as Buffer;
  fs.writeFileSync(filePath, bytes);
  return filePath;
}

test.describe('Purchase price library deep golden as qo_procurement @smoke', () => {
  test.setTimeout(240_000);

  let adminContext: BrowserContext;

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    adminContext = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const adminPage = await adminContext.newPage();
    await loginViaUI(adminPage, ADMIN_EMAIL, ADMIN_PASSWORD);
    const resp = await adminPage.request.post('/api/admin/users', {
      data: {
        email: PROC_USER.email,
        displayName: PROC_USER.displayName,
        initialPassword: PROC_USER.password,
        roleCodes: PROC_USER.roleCodes,
        sendInviteEmail: false,
      },
      timeout: 20_000,
    });
    if (!resp.ok()) {
      const text = await resp.text().catch(() => '');
      expect(
        /已存在|exists|duplicate|重复|conflict/i.test(text) || resp.status() === 409,
        `ensure smoke_proc failed: HTTP ${resp.status()} ${text.slice(0, 300)}`,
      ).toBe(true);
    }
    await adminContext.close();
  });

  test('proc: import purchase price Excel via UI and see rows in the library, zero forbidden', async ({ browser }, testInfo) => {
    const { context, page } = await openQuoteRolePage(browser, PROC_USER);
    const forbidden: ForbiddenHit[] = [];
    let step = 'login';
    page.on('response', (resp: Response) => {
      const status = resp.status();
      if ((status === 401 || status === 403) && resp.url().includes('/api/')) {
        forbidden.push({ step, url: resp.url(), status });
      }
    });
    const consoleIssues: string[] = [];
    page.on('console', (message) => {
      const text = message.text();
      if (isTransientViteDynamicImportIssue(text)) return;
      if (/Expression evaluation failed|Cannot read properties|ReferenceError|TypeError/i.test(text)) {
        consoleIssues.push(`${message.type()}: ${text}`);
      }
    });

    try {
      const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 6)}`;
      const partNos = [`SMK-PROC-A-${suffix}`, `SMK-PROC-B-${suffix}`];
      const workbookPath = createPurchasePriceWorkbook(
        testInfo.outputPath('proc-role-purchase-prices.xlsx'),
        partNos,
      );

      // 1. sidebar → purchase price library list renders for procurement
      step = 'open price library';
      await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
      await ensureSidebarExpanded(page);
      const sidebar = page.getByTestId('sidebar');
      await expect(sidebar.locator(`a[href="${PRICE_LIBRARY_PATH}"]`).first()).toBeVisible({ timeout: 15_000 });
      await sidebar.locator(`a[href="${PRICE_LIBRARY_PATH}"]`).first().click();
      await page.waitForURL((url) => url.pathname.startsWith(PRICE_LIBRARY_PATH), { timeout: 20_000 });
      await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {});
      await expect(page.locator('main')).not.toContainText(/Access forbidden|加载失败/, { timeout: 10_000 });

      // 2. toolbar import action → command dialog → real file upload → submit
      step = 'import purchase price excel';
      const importButton = page.getByTestId('toolbar-btn-import_purchase_price_analysis');
      await expect(importButton, 'import toolbar button visible for procurement').toBeVisible({ timeout: 15_000 });

      const importResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/qo_offline_material_price_common:import_excel') &&
          response.request().method() === 'POST',
        { timeout: 90_000 },
      );
      const uploadResponsePromise = page.waitForResponse(
        (response) => response.url().includes('/api/file/upload') && response.request().method() === 'POST',
        { timeout: 60_000 },
      );
      // the toolbar action may open a native file chooser directly, or a command dialog
      const chooserPromise = page.waitForEvent('filechooser', { timeout: 8_000 }).catch(() => null);
      await importButton.click();
      const chooser = await chooserPromise;
      if (chooser) {
        await chooser.setFiles(workbookPath);
      } else {
        const dialog = page.getByTestId('form-dialog');
        await expect(dialog, 'import command dialog opens (no file chooser fired)').toBeVisible({ timeout: 10_000 });
        const fileInput = dialog.locator('input[type="file"]').first();
        if ((await fileInput.count()) > 0) {
          await fileInput.setInputFiles(workbookPath);
        } else {
          const lateChooser = page.waitForEvent('filechooser', { timeout: 10_000 });
          await dialog.locator('button:has-text("上传"), [role="button"]:has-text("上传"), button:has-text("选择")').first().click();
          await (await lateChooser).setFiles(workbookPath);
        }
      }
      expect((await uploadResponsePromise).ok(), 'price workbook upload succeeds').toBe(true);

      // some variants execute the command right after upload; others need an explicit submit
      const submitButton = page.getByTestId('form-dialog-submit');
      const raced = await Promise.race([
        importResponsePromise.then(() => 'import' as const),
        submitButton.waitFor({ state: 'visible', timeout: 10_000 }).then(() => 'submit' as const).catch(() => 'none' as const),
      ]);
      if (raced === 'submit') {
        await submitButton.click();
      }
      const importBody = (await (await importResponsePromise).json().catch(() => ({}))) as Record<string, unknown>;
      expect(String(importBody.code), `import_excel response: ${JSON.stringify(importBody).slice(0, 600)}`).toBe('0');

      // 3. rows land: dynamic record readback as the role + list UI shows the imported part no
      step = 'verify imported rows';
      await expect
        .poll(
          async () => {
            const rows = await queryDynamicRecords(page, 'qo_offline_material_price_common', [
              { fieldName: 'qo_omp_part_no', operator: 'EQ', value: partNos[0] },
            ]);
            return rows.length;
          },
          { timeout: 30_000, intervals: [1000, 2000] },
        )
        .toBeGreaterThanOrEqual(1);
      const imported = await queryDynamicRecords(page, 'qo_offline_material_price_common', [
        { fieldName: 'qo_omp_part_no', operator: 'EQ', value: partNos[0] },
      ]);
      expect(String(imported[0].qo_omp_supplier_name ?? '')).toBe('Smoke Supplier Co.');
      expect(Number(imported[0].qo_omp_unit_price)).toBeCloseTo(1.5, 4);
      expect(String(imported[0].qo_omp_status ?? '')).toBe('active');

      await page.reload({ waitUntil: 'domcontentloaded' });
      await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {});
      const search = page.getByTestId('list-search-input');
      if ((await search.count()) > 0) {
        await search.first().click();
        await search.first().pressSequentially(partNos[0], { delay: 10 });
        const searchButton = page.getByTestId('search-button');
        if ((await searchButton.count()) > 0) await searchButton.first().click();
        else await page.keyboard.press('Enter');
      }
      await expect(page.locator(`table tbody tr:has-text("${partNos[0]}")`).first()).toBeVisible({
        timeout: 20_000,
      });

      // 4. hard gates
      expect(consoleIssues, `console issues:\n${consoleIssues.join('\n')}`).toEqual([]);
      const hits = forbidden.map((h) => `[${h.step}] ${h.status} ${h.url}`);
      expect(hits, `forbidden API hits as qo_procurement:\n${hits.join('\n')}`).toEqual([]);
    } finally {
      await context.close();
    }
  });
});
