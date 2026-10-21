import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';
import { createHash } from 'node:crypto';
import type { Page } from '@playwright/test';

const HERE = path.dirname(fileURLToPath(import.meta.url));
import { test, expect } from '../../fixtures';
import { requireRuntimeUrl } from '../../helpers/environments';
import { uniqueId } from '../helpers';
import {
  makeQuoteRoleUser,
  ensureQuoteRoleUser,
  openQuoteRolePage,
  readDynamicRecord,
  writeMiniBoardZip,
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

test('restored material snapshot exposes matching counts and sync state through read-only APIs', async ({ browser }, testInfo) => {
  const manifestFile = process.env.BOM_KINGDEE_MATERIAL_SNAPSHOT_MANIFEST;
  const pluginsRoot = process.env.AURA_PLUGINS_PROJECT_ROOT;
  const baseURL = testInfo.project.use.baseURL;
  expect(manifestFile, 'The original release must bind its approved development material snapshot').toBeTruthy();
  expect(baseURL).toBeTruthy();
  expect(pluginsRoot).toBeTruthy();
  expect(requireRuntimeUrl('base')).toBeTruthy();
  expect(new URL(baseURL!).origin).toBe(new URL(requireRuntimeUrl('base')!).origin);
  const bytes = fs.readFileSync(manifestFile!);
  const manifest = JSON.parse(bytes.toString('utf8'));
  expect(manifest.masterRows).toBeGreaterThan(0);
  expect(manifest.failedRows).toBe(0);
  const expectedFinished = manifest.sourceDump.kingdeeSyncState.lastSuccessfulSyncAt;
  expect(expectedFinished, 'Snapshot provenance must make its timestamp timezone explicit').toMatch(/(?:Z|[+-]\d{2}:\d{2})$/);
  const { normalizeInstant } = await import(pathToFileURL(path.join(pluginsRoot!,
    'bom-standardization/scripts/kingdee-material-snapshot.mjs')).href);
  const toInstant = (value: unknown) => {
    expect(typeof value).toBe('string');
    const date = Date.parse(normalizeInstant(String(value), 'sync state timestamp'));
    expect(Number.isFinite(date)).toBe(true);
    return date;
  };
  const context = await browser.newContext({ baseURL,
    storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
  const evidence: unknown[] = [];
  try {
    const list = async (model: string, pageSize: number, filters: unknown[] = []) => {
      const url = `/api/dynamic/${model}/list?pageNum=1&pageSize=${pageSize}&filters=${encodeURIComponent(JSON.stringify(filters))}`;
      const response = await context.request.get(url);
      const body = await response.json();
      evidence.push({ method: 'GET', target: response.url(), status: response.status(), body });
      expect(response.status()).toBe(200);
      expect(String(body.code)).toBe('0');
      expect(Array.isArray(body.data.records)).toBe(true);
      return body.data;
    };
    const materials = await list('bom_material_master', 1,
      [{ fieldName: 'bom_mm_source_type', operator: 'EQ', value: 'KINGDEE' }]);
    expect(materials.records[0].bom_mm_source_type).toBe('KINGDEE');
    expect(Number(materials.total)).toBe(manifest.masterRows);
    const states = await list('bom_material_sync_state', 100);
    expect(Number(states.total)).toBe(states.records.length);
    const succeeded = states.records.filter((row: Record<string, unknown>) =>
      row.bom_mss_source_type === 'KINGDEE' && row.bom_mss_last_status === 'succeeded');
    expect(succeeded.length).toBeGreaterThan(0);
    const latest = succeeded.reduce((left: Record<string, unknown>, right: Record<string, unknown>) =>
      toInstant(left.bom_mss_last_finished_at) >= toInstant(right.bom_mss_last_finished_at) ? left : right);
    expect(toInstant(latest.bom_mss_last_finished_at)).toBe(toInstant(expectedFinished));
    expect(Number(latest.bom_mss_last_failed_count)).toBe(0);
    expect(latest.bom_mss_last_error_message ?? '').toBe('');
  } finally {
    await testInfo.attach('restored-material-read-only-api-proof', {
      body: JSON.stringify({ runtime: process.env.AURA_RUNTIME_NAME, snapshotId: manifest.snapshotId,
        snapshotSha256: createHash('sha256').update(bytes).digest('hex'), expectedFinished,
        expectedMasterRows: manifest.masterRows, evidence }, null, 2), contentType: 'application/json',
    });
    await context.close();
  }
});

test('plugin page import API publishes only explicitly imported source revisions', async ({ browser }, testInfo) => {
  const quoteRoot = process.env.AURA_QUOTE_ROOT;
  const pluginsRoot = process.env.AURA_PLUGINS_PROJECT_ROOT;
  const runtime = process.env.AURA_RUNTIME_NAME;
  const baseURL = testInfo.project.use.baseURL;
  for (const value of [quoteRoot, pluginsRoot, runtime, baseURL, requireRuntimeUrl('base')]) {
    expect(value, 'The original gate must supply its exact source roots, runtime and target').toBeTruthy();
  }
  expect(new URL(baseURL!).origin).toBe(new URL(requireRuntimeUrl('base')!).origin);
  const namespace = `x02${uniqueId('import').replace(/[^a-z0-9]/gi, '').toLowerCase()}`;
  const pageKey = `${namespace}_runtime_list`;
  const fixture = path.join(quoteRoot!, '.workspace', 'e2e-fixtures', namespace);
  fs.mkdirSync(path.join(fixture, 'config'), { recursive: true });
  const original = JSON.parse(fs.readFileSync(path.join(pluginsRoot!, 'bom-standardization/config/pages.json'), 'utf8'))[0];
  expect(original.modelCode).toBe('req_requirement_set_pcba_bom');
  const table = original.blocks.find((block: { blockType: string }) => block.blockType === 'table');
  expect(table).toBeTruthy();
  const schema = { ...original, pageKey, 'name:zh-CN': '页面导入验证', 'name:en': 'Page import verification',
    blocks: [{ ...table, columns: table.columns.filter((column: { isActionColumn?: boolean }) => !column.isActionColumn) }] };
  const sourceFile = path.join(fixture, 'config', 'pages.json');
  fs.writeFileSync(path.join(fixture, 'plugin.json'), JSON.stringify({
    pluginId: `com.auraboot.test.${namespace}`, namespace, version: '1.0.0', dslVersion: 1,
    pluginType: 'config', displayName: 'Page import API evidence', minPlatformVersion: '1.0.0',
    dependencies: ['com.auraboot.bom-standardization'], resourceDirs: { pages: 'config/pages.json' },
  }, null, 2));
  const writeRevision = async (revision: 'a' | 'b') => {
    schema.blocks[0].id = `${namespace}_published_${revision}`;
    const bytes = JSON.stringify([schema], null, 2);
    fs.writeFileSync(sourceFile, bytes);
    await testInfo.attach(`page-source-${revision}`, { body: bytes, contentType: 'application/json' });
  };
  const context = await browser.newContext({ baseURL,
    storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
  const evidence: unknown[] = [];
  try {
    const importPage = async () => {
      const payload = { path: fixture, conflictStrategy: 'OVERWRITE', autoPublishModels: true,
        autoPublishFields: true, autoPublishCommands: true, autoPublishPages: true, deferReferenceValidation: true };
      const response = await context.request.post('/api/plugins/import/import-directory-sync', { data: payload });
      const body = await response.json();
      evidence.push({ operation: 'import', runtime, target: response.url(), payload, status: response.status(), body });
      expect(response.status()).toBe(200);
      expect(body.success).toBe(true);
    };
    const expectRevision = async (phase: string, revision: 'a' | 'b') => {
      const response = await context.request.get(`/api/pages/key/${pageKey}`);
      const body = await response.json();
      evidence.push({ operation: 'read-runtime-page', phase, runtime, target: response.url(), status: response.status(), body });
      expect(response.status()).toBe(200);
      expect(String(body.code)).toBe('0');
      expect(body.data.pageKey).toBe(pageKey);
      expect(body.data.blocks[0].id).toBe(`${namespace}_published_${revision}`);
    };
    await writeRevision('a');
    await importPage();
    await expectRevision('initial-import', 'a');
    await writeRevision('b');
    await expectRevision('source-changed-without-import', 'a');
    await importPage();
    await expectRevision('explicit-reimport', 'b');
  } finally {
    try {
      const pluginsResponse = await context.request.get('/api/plugins');
      expect(pluginsResponse.status()).toBe(200);
      const plugins = await pluginsResponse.json();
      expect(String(plugins.code)).toBe('0');
      const owned = plugins.data.filter((plugin: { pluginId: string }) => (
        plugin.pluginId === `com.auraboot.test.${namespace}`
      ));
      expect(owned.length, 'the unique import fixture must never resolve to multiple plugins').toBeLessThanOrEqual(1);
      if (owned.length === 1) {
        const pluginPid = owned[0].pid;
        const uninstall = await context.request.post(`/api/plugins/${pluginPid}/uninstall`, {
          data: { removeData: false, force: false, decisions: { [pageKey]: 'DELETE' } },
        });
        const uninstalled = await uninstall.json();
        evidence.push({ operation: 'withdraw-owned-fixture', status: uninstall.status(), body: uninstalled });
        expect(uninstall.status()).toBe(200);
        expect(uninstalled.success).toBe(true);
        expect(uninstalled.deletedResources).toEqual([pageKey]);
        const disable = await context.request.post(`/api/plugins/${pluginPid}/disable`);
        const disabled = await disable.json();
        expect(disable.status()).toBe(200);
        expect(String(disabled.code)).toBe('0');
        const reread = await context.request.get(`/api/plugins/${pluginPid}`);
        const persisted = await reread.json();
        evidence.push({ operation: 'reread-retired-fixture', status: reread.status(), body: persisted });
        expect(reread.status()).toBe(200);
        expect(persisted.data.pluginId).toBe(`com.auraboot.test.${namespace}`);
        expect(persisted.data.status).toBe('stopped');
      }
    } finally {
      await testInfo.attach('page-import-api-boundary', {
        body: JSON.stringify({ fixture, pageKey, runtime, evidence }, null, 2), contentType: 'application/json',
      });
      await context.close();
      fs.rmSync(fixture, { recursive: true });
    }
  }
});

function findSampleBom(): string | undefined {
  const rel = 'aura-quote/docs/ref/10款GERBER加坐标';
  const candidates = [
    process.env.QUOTE_BOM_SAMPLES_DIR,
    path.resolve(HERE, '../../../../../' + rel),  // <ws>/auraboot/web-admin/tests/e2e/pcba-solution → <ws>
    path.resolve(HERE, '../../../../../../' + rel),
    '/home/local-oss-dev/work/auraboot/' + rel,
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

async function waitForQuoteListSettled(page: Page): Promise<void> {
  await expect.poll(async () => {
    const searchReady = await page.locator('[data-testid="list-search-input"], input[placeholder*="查询"], input[placeholder*="搜索"]').first().count();
    const tableReady = await page.locator('table, [role="table"]').first().count();
    const loading = await page.locator('[aria-busy="true"], .ant-spin-spinning, [data-loading="true"]').count();
    return (searchReady > 0 || tableReady > 0) && loading === 0;
  }).toBe(true);
}

async function triggerQuoteSearch(page: Page, marker: string): Promise<void> {
  const response = page.waitForResponse((r) => (
    r.url().includes('/api/dynamic/qo_quote_common/list') && r.request().method() === 'GET'
  ), { timeout: 5000 }).catch(() => null);
  const btn = page.locator('button:has-text("搜索"), [data-testid="search-button"]').first();
  if (await btn.count() > 0) await btn.click(); else await page.keyboard.press('Enter');
  await response;
  await expect.poll(async () => {
    const loading = await page.locator('[aria-busy="true"], .ant-spin-spinning, [data-loading="true"]').count();
    const text = await page.locator('table tbody, [role="table"]').first().innerText().catch(() => '');
    return loading === 0 && (text.includes(marker) || !text.includes('加载'));
  }).toBe(true);
}

async function listHasMarker(page: Page, marker: string): Promise<boolean> {
  await page.goto(QUOTE_LIST, { waitUntil: 'domcontentloaded' });
  await waitForQuoteListSettled(page);
  const q = page.locator('[data-testid="list-search-input"], input[placeholder*="查询"], input[placeholder*="搜索"]').first();
  if (await q.count() > 0) {
    await q.click(); await q.fill(''); await q.pressSequentially(marker, { delay: 12 });
    await triggerQuoteSearch(page, marker);
  }
  return (await page.locator(`table tbody tr:has-text("${marker}")`).count()) > 0;
}

// provision a quote through the real command pipeline using the role's browser-context session cookie
async function provisionQuote(page: Page, code: string): Promise<void> {
  const suffix = code;
  const proj = await page.context().request.post('/api/meta/commands/execute/bom:create_project', {
    data: { payload: { bom_project_name: `W2B ${suffix}`, bom_pcba_code: `W2B-${suffix}`, bom_project_remark: 'qo-iso' }, operationType: 'create' },
  });
  const projBody = await proj.json();
  expect(proj.status()).toBe(200);
  expect(String(projBody.code)).toBe('0');
  const projId = projBody?.data?.recordPid || projBody?.data?.data?.recordPid || projBody?.data?.recordId;
  expect(projId, `project created for ${code}`).toBeTruthy();

  const buf = fs.readFileSync(SAMPLE_BOM!);
  const up = await page.context().request.post('/api/file/upload', {
    multipart: { file: { name: 'bom.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: buf } },
  });
  const upBody = await up.json();
  expect(up.status()).toBe(200);
  expect(String(upBody.code)).toBe('0');
  const fileId = upBody?.data?.fileId || upBody?.data?.id;
  expect(fileId, `BOM uploaded for ${code}`).toBeTruthy();

  const corrected = JSON.stringify([{ name: 'bom.xlsx', url: `/api/file/download/${fileId}`, size: buf.length, type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', fileId }]);
  // Use actual uploaded board/coordinate files. This test proves record scope,
  // not parser geometry or coordinate association; those have separate gates.
  const gerberName = `e2e-gerber-${suffix}.zip`;
  const cplName = `e2e-cpl-${suffix}.csv`;
  const boardPath = writeMiniBoardZip(test.info().outputPath(gerberName));
  async function uploadFixture(name: string, mimeType: string, buffer: Buffer): Promise<string> {
    const response = await page.context().request.post('/api/file/upload', {
      multipart: { file: { name, mimeType, buffer } },
    });
    const body = await response.json();
    expect(response.status()).toBe(200);
    expect(String(body.code)).toBe('0');
    expect(body.data?.fileId).toBeTruthy();
    return String(body.data.fileId);
  }
  const gerberId = await uploadFixture(gerberName, 'application/zip', fs.readFileSync(boardPath));
  const cplId = await uploadFixture(cplName, 'text/csv', Buffer.from('Designator,Mid X,Mid Y,Layer\nR1,0,0,Top\n'));
  const cr = await page.context().request.post('/api/meta/commands/execute/qo_quote_common:create', {
    data: { payload: { qo_quote_code: code, qo_quote_customer: `W2B ${suffix}`, qo_quote_project_id: projId, corrected_bom_file: corrected, corrected_bom_file_id: fileId, corrected_bom_filename: 'bom.xlsx', gerber_source_file_id: gerberId, gerber_source_filename: gerberName, cpl_source_file_id: cplId, cpl_source_filename: cplName }, operationType: 'create' },
  });
  expect(cr.status(), `quote created for ${code}`).toBe(200);
  const created = await cr.json();
  expect(String(created.code)).toBe('0');
  const result = created.data?.data ?? created.data;
  const quoteId = String(result?.recordPid ?? result?.recordId ?? result?.quote?.pid ?? '');
  expect(quoteId, 'scope assertions require the exact newly created quote').toBeTruthy();
  const saved = await readDynamicRecord(page, 'qo_quote_common', quoteId);
  expect(saved.qo_quote_code).toBe(code);
  expect(saved.qo_quote_project_id).toBe(projId);
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
