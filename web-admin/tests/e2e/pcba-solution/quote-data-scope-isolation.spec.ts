import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import type { Page } from '@playwright/test';

const HERE = path.dirname(fileURLToPath(import.meta.url));
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';
import {
  makeQuoteRoleUser,
  ensureQuoteRoleUser,
  openQuoteRolePage,
  type QuoteRoleUser,
} from './quote-e2e-helpers';

/**
 * Quote/BOM 真机 Wave 2b — 报价单数据隔离 self/all (QO-10/11/12).
 * Provisions a quote via the real command pipeline (bom:create_project → upload BOM →
 * qo_quote_common:create) AS sales A, then verifies in the browser list that sales B + procurement
 * canNOT see it (self), while admin can (all). Closes the gap flagged in the testcase matrix
 * (quote self/all not covered even at L2).
 */
const QUOTE_LIST = '/p/qo_quote_common';
const uid = uniqueId('w2b').replace(/_/g, '-');
const users: Record<string, QuoteRoleUser> = {};

function findSampleBom(): string | undefined {
  const rel = 'aura-quote/docs/ref/10款GERBER加坐标';
  const candidates = [
    process.env.QUOTE_BOM_SAMPLES_DIR,
    path.resolve(HERE, '../../../../../' + rel),  // <ws>/auraboot/web-admin/tests/e2e/pcba-solution → <ws>
    path.resolve(HERE, '../../../../../../' + rel),
    '/Users/ghj/work/auraboot/' + rel,
  ].filter(Boolean) as string[];
  for (const root of candidates) {
    if (!fs.existsSync(root)) continue;
    for (const sample of fs.readdirSync(root)) {
      const bomDir = path.join(root, sample, 'BOM');
      if (fs.existsSync(bomDir)) {
        const xlsx = fs.readdirSync(bomDir).find((f) => /\.xlsx$/i.test(f));
        if (xlsx) return path.join(bomDir, xlsx);
      }
    }
  }
  return undefined;
}
const SAMPLE_BOM = findSampleBom();

async function listHasMarker(page: Page, marker: string): Promise<boolean> {
  await page.goto(QUOTE_LIST, { waitUntil: 'domcontentloaded' });
  await page.locator('table tbody tr').first().waitFor({ state: 'visible', timeout: 15000 }).catch(() => {});
  const q = page.locator('[data-testid="list-search-input"], input[placeholder*="查询"], input[placeholder*="搜索"]').first();
  if (await q.count() > 0) {
    await q.click(); await q.fill(''); await q.pressSequentially(marker, { delay: 12 });
    const btn = page.locator('button:has-text("搜索"), [data-testid="search-button"]').first();
    if (await btn.count() > 0) await btn.click(); else await page.keyboard.press('Enter');
    await page.waitForTimeout(2000);
  }
  return (await page.locator(`table tbody tr:has-text("${marker}")`).count()) > 0;
}

// provision a quote through the real command pipeline using the role's session cookie (page.request)
async function provisionQuote(page: Page, code: string): Promise<void> {
  const suffix = code;
  const proj = await page.request.post('/api/meta/commands/execute/bom:create_project', {
    data: { payload: { bom_project_name: `W2B ${suffix}`, bom_pcba_code: `W2B-${suffix}`, bom_project_remark: 'qo-iso' }, operationType: 'create' },
  });
  const projBody = await proj.json();
  const projId = projBody?.data?.recordPid || projBody?.data?.data?.recordPid || projBody?.data?.recordId;
  expect(projId, `project created for ${code}`).toBeTruthy();

  const buf = fs.readFileSync(SAMPLE_BOM);
  const up = await page.request.post('/api/file/upload', {
    multipart: { file: { name: 'bom.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: buf } },
  });
  const upBody = await up.json();
  const fileId = upBody?.data?.fileId || upBody?.data?.id;
  expect(fileId, `BOM uploaded for ${code}`).toBeTruthy();

  const corrected = JSON.stringify([{ name: 'bom.xlsx', url: `/api/file/download/${fileId}`, size: buf.length, type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', fileId }]);
  const cr = await page.request.post('/api/meta/commands/execute/qo_quote_common:create', {
    data: { payload: { qo_quote_code: code, qo_quote_customer: `W2B ${suffix}`, qo_quote_project_id: projId, corrected_bom_file: corrected, corrected_bom_file_id: fileId, corrected_bom_filename: 'bom.xlsx' }, operationType: 'create' },
  });
  expect(cr.status(), `quote created for ${code}`).toBe(200);
}

test.describe('Quote data-scope isolation self/all @smoke', () => {
  test.describe.configure({ mode: 'serial', timeout: 180_000 });

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    users['sales_a'] = makeQuoteRoleUser('qo_sales', `${uid}a`, ['qo_sales']);
    users['sales_b'] = makeQuoteRoleUser('qo_sales', `${uid}b`, ['qo_sales']);
    users['proc'] = makeQuoteRoleUser('qo_procurement', `${uid}p`, ['qo_procurement']);
    for (const k of ['sales_a', 'sales_b', 'proc']) await ensureQuoteRoleUser(page, users[k]);
    await ctx.close();
  });

  test('QO-10/11/12 quote self isolation (sales B + procurement denied) + admin all', async ({ browser }) => {
    expect(SAMPLE_BOM, 'sample BOM fixture present').toBeTruthy();
    const code = `QO-W2B-${uid}`.slice(0, 28);
    // sales A provisions a quote
    const a = await openQuoteRolePage(browser, users['sales_a']);
    try {
      await provisionQuote(a.page, code);
      expect(await listHasMarker(a.page, code), 'sales A sees own quote').toBeTruthy();
    } finally {
      await a.context.close();
    }
    // QO-10: sales B must NOT see it (self)
    const b = await openQuoteRolePage(browser, users['sales_b']);
    try {
      expect(await listHasMarker(b.page, code), 'QO-10: sales B must NOT see sales A quote (self)').toBeFalsy();
    } finally {
      await b.context.close();
    }
    // QO-12: procurement (cross business role) must NOT see it (self is per-user)
    const p = await openQuoteRolePage(browser, users['proc']);
    try {
      expect(await listHasMarker(p.page, code), 'QO-12: procurement must NOT see sales A quote (per-user self)').toBeFalsy();
    } finally {
      await p.context.close();
    }
    // QO-11: admin sees it (all)
    const adminCtx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    try {
      const ap = await adminCtx.newPage();
      expect(await listHasMarker(ap, code), 'QO-11: admin sees the quote (all)').toBeTruthy();
    } finally {
      await adminCtx.close();
    }
  });
});
