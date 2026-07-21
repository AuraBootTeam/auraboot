import fs from 'node:fs';
import path from 'node:path';
import * as XLSX from 'xlsx';
import type { Browser, BrowserContext, Response } from '@playwright/test';
import { test, expect, type Page } from '../../fixtures';
import {
  clickRowActionByLocator,
  ensureSidebarExpanded,
  findRowInPaginatedList,
  waitForDynamicPageLoad,
} from '../helpers';
import { loginViaUI } from '../../helpers/wd-fixtures';
import {
  clickSidebarPage,
  isTransientViteDynamicImportIssue,
  openQuoteRolePage,
  queryDynamicRecords,
  readDynamicRecord,
  seedBomWorkbench,
  QUOTE_ROLE_TEST_PASSWORD,
  type BomWorkbenchSeed,
  type QuoteRoleUser,
} from './quote-e2e-helpers';

/**
 * BOM workbench deep golden AS THE ENGINEERING ROLE (bom_engineering), not admin.
 *
 * The admin twin (bom-workbench-golden.spec.ts) proves the workbench works for a user who can
 * never hit a permission wall. This spec re-drives the same deep closed-loop — open workbench
 * from the sidebar → candidate confirm (state flips, DB-verified) → undo (state reverts) →
 * download the regenerated standard BOM and parse the workbook — logged in as the fixed
 * smoke_eng account, with a zero-401/403 collector across the whole session
 * ("管理员能用 ≠ 系统能用", DDR-2026-06-29 §8).
 *
 * Seeding stays admin/API (synthetic deterministic evidence); the ROLE performs every UI step.
 * DB-side verification of bom_review_decision uses the admin session (engineering deliberately
 * has no read on that model); everything the UI itself needs must pass as the role.
 *
 * RUN (local host-first stack, business roles reconciled):
 *   PLAYWRIGHT_BASE_URL=http://127.0.0.1:<web> BACKEND_URL=http://127.0.0.1:<be> PW_SKIP_WEBSERVER=1 \
 *     node_modules/.bin/playwright test tests/e2e/pcba-solution/bom-workbench-role-eng-golden.spec.ts \
 *     --project=chromium --no-deps
 */

const ADMIN_EMAIL = process.env.ADMIN_EMAIL || 'admin@auraboot.com';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Test2026x';

const ENG_USER: QuoteRoleUser = {
  key: 'smoke_eng',
  email: 'smoke-eng@e2e.local',
  displayName: 'Smoke Engineering',
  password: QUOTE_ROLE_TEST_PASSWORD,
  roleCodes: ['bom_engineering'],
};

function sheetRows(workbook: XLSX.WorkBook, sheetName: string): unknown[][] {
  const sheet = workbook.Sheets[sheetName];
  expect(sheet, `sheet ${sheetName} exists`).toBeTruthy();
  return XLSX.utils.sheet_to_json(sheet, { header: 1, raw: false, defval: '' }) as unknown[][];
}

function cell(row: unknown[] | undefined, index: number): string {
  return String(row?.[index] ?? '').trim();
}

function validateStandardBomWorkbook(filePath: string, created: BomWorkbenchSeed): void {
  const workbook = XLSX.read(fs.readFileSync(filePath), {
    type: 'buffer',
    cellText: false,
    sheetStubs: true,
  });
  expect(workbook.SheetNames).toEqual(['BOM', '变更记录', '转换明细']);

  const bomRows = sheetRows(workbook, 'BOM');
  expect(bomRows.length).toBeGreaterThanOrEqual(6);
  expect(bomRows[3]).toEqual([
    '序号',
    '层级',
    '物料编码',
    '物料名称',
    '规格描述',
    '单位',
    '用量',
    '位置',
    '工段',
    '品牌/制造商',
    '原料号',
    '备注',
  ]);

  // Source-truth semantics (plugins #144): undo restores the raw-line source values onto
  // the standard row, so after this spec's confirm → undo flow the exported row carries the
  // raw name — locate by stable refdes and pin the restored source name.
  const resistorRow = bomRows.find((row) => cell(row, 7) === 'R1,R2');
  expect(resistorRow, 'standard BOM should include unresolved resistor row (refdes R1,R2)').toBeTruthy();
  expect(cell(resistorRow, 3), 'undo restores the raw-source material name').toBe('10K resistor raw');
  expect(cell(resistorRow, 2), 'unconfirmed multi-candidate material code stays blank').toBe('');

  const mcuRow = bomRows.find((row) => cell(row, 3) === 'MCU direct copy');
  expect(mcuRow, 'standard BOM should include direct-copy MCU row').toBeTruthy();
  expect(cell(mcuRow, 2)).toMatch(/^E2E-U1-/);

  const detailRows = sheetRows(workbook, '转换明细');
  expect(detailRows[0]).toEqual([
    '原始行号',
    '原始描述',
    '系统分类',
    '提取属性',
    '匹配编码',
    '候选编码',
    '颜色',
    '错误原因',
    '数量语义证据',
  ]);
  const resistorDetail = detailRows.find((row) => cell(row, 1) === '10K 1% 0603');
  expect(resistorDetail, 'detail sheet should include unresolved resistor evidence').toBeTruthy();
  expect(cell(resistorDetail, 5)).toContain(created.candidateCode);
  expect(cell(resistorDetail, 6)).toBe('YELLOW');

  const flat = JSON.stringify(bomRows.slice(0, 6)) + JSON.stringify(detailRows.slice(0, 2));
  expect(flat, 'no raw bom_* field codes leak into the workbook').not.toMatch(/bom_(std|task|raw)_[a-z_]+/);
}

type ForbiddenHit = { step: string; url: string; status: number };

test.describe('BOM workbench deep golden as bom_engineering @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(300_000);

  let created: BomWorkbenchSeed;
  let adminContext: BrowserContext;
  let adminPage: Page;

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    adminContext = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    adminPage = await adminContext.newPage();
    await loginViaUI(adminPage, ADMIN_EMAIL, ADMIN_PASSWORD);
    // fixed smoke account (idempotent — created by the role-menu smoke; tolerate exists)
    const resp = await adminPage.request.post('/api/admin/users', {
      data: {
        email: ENG_USER.email,
        displayName: ENG_USER.displayName,
        initialPassword: ENG_USER.password,
        roleCodes: ENG_USER.roleCodes,
        sendInviteEmail: false,
      },
      timeout: 20_000,
    });
    if (!resp.ok()) {
      const text = await resp.text().catch(() => '');
      expect(
        /已存在|exists|duplicate|重复|conflict/i.test(text) || resp.status() === 409,
        `ensure smoke_eng failed: HTTP ${resp.status()} ${text.slice(0, 300)}`,
      ).toBe(true);
    }
    // Seed synthetic BOM data as admin, but hand ownership of the self-scoped records
    // (conversion task + project + account) to the eng role so it survives the `self` data
    // scope on bom_conversion_task_pcba read (business-roles.json #243). Child lines are not
    // self-scoped, so they stay admin-owned yet remain readable by eng (all-fallback).
    created = await seedBomWorkbench(adminPage, { ownerEmail: ENG_USER.email });
  });

  test.afterAll(async () => {
    await adminContext?.close();
  });

  test('eng: workbench candidate confirm → undo → export download, zero forbidden', async ({ browser }, testInfo) => {
    const { context, page } = await openQuoteRolePage(browser, ENG_USER);
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
      // 1. sidebar → workbench list shows the seeded task. bom_conversion_task_pcba read is
      //    self-scoped for bom_engineering, so the task was reassigned to this eng account at
      //    seed time (see beforeAll); eng sees it because eng now owns it.
      step = 'open workbench list';
      await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
      await ensureSidebarExpanded(page);
      await clickSidebarPage(
        page,
        '/p/bom_conversion_task_pcba_workbench',
        /BOM 工作台|Workbench/i,
      );
      await expect(page.locator('main')).toContainText(created.marker, { timeout: 20_000 });

      // 2. open the workbench detail via the row action
      step = 'open workbench detail';
      const workbenchRow = await findRowInPaginatedList(page, created.marker, 20_000);
      await Promise.all([
        page
          .waitForURL(
            (url) => url.pathname === `/p/bom_conversion_task_pcba_workbench/view/${created.taskId}`,
            { timeout: 20_000 },
          )
          .catch(() => null),
        clickRowActionByLocator(page, workbenchRow, 'open_workbench', '打开'),
      ]);
      await waitForDynamicPageLoad(page, 20_000);
      await expect(page.getByTestId('workbench-action-download_new_bom')).toBeVisible({ timeout: 20_000 });
      await expect(page.getByTestId('metric-strip-bom_workbench_metrics')).toBeVisible({ timeout: 20_000 });
      await expect(page.getByTestId('metric-strip-item-green')).toContainText('1');
      await expect(page.getByTestId('metric-strip-item-yellow')).toContainText('1');

      // 3. multi-candidate line → review drawer → confirm candidate
      step = 'confirm candidate';
      await expect(page.getByTestId('metric-strip-item-reason_multi_candidate')).toContainText('1');
      await page.getByTestId('metric-strip-item-reason_multi_candidate').click();
      await page.locator('table tbody tr').first().click();
      await expect(page.getByTestId('review-drawer')).toBeVisible({ timeout: 20_000 });
      await expect(page.getByTestId('review-drawer')).toContainText('10K resistor canonical');
      await expect(page.getByTestId(`review-drawer-candidate-${created.primaryEvidenceId}`)).toBeVisible({
        timeout: 20_000,
      });
      await page.getByTestId(`review-drawer-candidate-${created.primaryEvidenceId}`).click();
      const confirmResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/bom:confirm_candidate') &&
          response.request().method() === 'POST',
        { timeout: 30_000 },
      );
      await page.getByTestId('review-drawer-candidate-action-confirm_candidate').click();
      const confirmBody = await (await confirmResponsePromise).json().catch(() => ({}));
      expect(
        String((confirmBody as { code?: unknown }).code),
        `bom:confirm_candidate response: ${JSON.stringify(confirmBody).slice(0, 600)}`,
      ).toBe('0');

      // decision state flips — verified from the ROLE's own read surface
      await expect
        .poll(
          async () => {
            const row = await readDynamicRecord(page, 'bom_standard_line_pcba', created.standardLineId);
            return {
              materialCode: row.bom_std_material_code,
              manualConfirmed: String(row.bom_std_manual_confirmed),
            };
          },
          { timeout: 20_000, intervals: [500, 1000, 1500] },
        )
        .toEqual({ materialCode: created.candidateCode, manualConfirmed: 'true' });

      // 4. undo — state reverts (decision trail verified via admin: eng has no bom_review_decision read)
      // The drawer does not always refresh its decision state in place after confirm
      // (current-main behavior, reproduced with admin too) — re-open the line the way a
      // real user would before undoing.
      step = 'undo decision';
      await page.reload({ waitUntil: 'domcontentloaded' });
      await waitForDynamicPageLoad(page, 20_000);
      await page.locator('tbody tr').filter({ hasText: '10K resistor canonical' }).first().click();
      await expect(page.getByTestId('review-drawer')).toBeVisible({ timeout: 20_000 });
      await expect(page.getByTestId('review-drawer-candidate-action-undo_decision')).toBeEnabled({
        timeout: 20_000,
      });
      const undoResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/bom:undo_decision') &&
          response.request().method() === 'POST',
        { timeout: 30_000 },
      );
      await page.getByTestId('review-drawer-candidate-action-undo_decision').click();
      const undoBody = await (await undoResponsePromise).json().catch(() => ({}));
      expect(
        String((undoBody as { code?: unknown }).code),
        `bom:undo_decision response: ${JSON.stringify(undoBody).slice(0, 600)}`,
      ).toBe('0');
      await expect
        .poll(
          async () => {
            const row = await readDynamicRecord(page, 'bom_standard_line_pcba', created.standardLineId);
            return {
              materialCode: String(row.bom_std_material_code ?? ''),
              manualConfirmed: String(row.bom_std_manual_confirmed),
            };
          },
          { timeout: 20_000, intervals: [500, 1000, 1500] },
        )
        .toEqual({ materialCode: '', manualConfirmed: 'false' });
      const decisions = await queryDynamicRecords(adminPage, 'bom_review_decision', [
        { fieldName: 'bom_rd_task_id', operator: 'EQ', value: created.taskId },
      ]);
      expect(decisions.map((d) => d.bom_rd_decision_type).sort()).toEqual(['manual_confirm', 'undo']);

      // 5. regenerate + download the standard BOM as the role; parse the workbook.
      // The drawer is still open (modal) — use its own regenerate-and-download action,
      // which drives the same bom:regenerate_export command.
      step = 'download export';
      const downloadPromise = page.waitForEvent('download', { timeout: 45_000 });
      const regeneratePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/bom:regenerate_export') &&
          response.request().method() === 'POST',
        { timeout: 45_000 },
      );
      const drawerRegenerate = page
        .getByTestId('review-drawer')
        .getByRole('button', { name: /重新生成并下载|Regenerate/ });
      if (await drawerRegenerate.count()) {
        await drawerRegenerate.first().click();
      } else {
        await page.getByTestId('workbench-action-download_new_bom').click();
      }
      const regenBody = await (await regeneratePromise).json().catch(() => ({}));
      expect(String((regenBody as { code?: unknown }).code)).toBe('0');
      const download = await downloadPromise;
      const exportPath = path.join(testInfo.outputDir, `role-eng-standard-bom-${created.taskId}.xlsx`);
      await download.saveAs(exportPath);
      validateStandardBomWorkbook(exportPath, created);

      // 6. hard gates: no console runtime errors, no forbidden API responses as the role
      expect(consoleIssues, `console issues:\n${consoleIssues.join('\n')}`).toEqual([]);
      const hits = forbidden.map((h) => `[${h.step}] ${h.status} ${h.url}`);
      expect(hits, `forbidden API hits as bom_engineering:\n${hits.join('\n')}`).toEqual([]);
    } finally {
      await context.close();
    }
  });
});
