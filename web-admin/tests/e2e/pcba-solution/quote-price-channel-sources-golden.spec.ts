import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import {
  cleanupRows,
  dynamicCreate,
  executeCommand,
  queryDynamicRecords,
  seedQuoteScaffold,
  setYunhanMockScenario,
  type CreatedRows,
} from './quote-e2e-helpers';

/**
 * Quote/BOM 真机 — 价格渠道来源合同 (Q06-03, real-stack api 载体).
 * 种子:一张报价单两条行(云汉 mock 目录命中行 + notFoundKeywords 未命中行),
 * 两支 MPN 各配一行本地近期采购价(qo_offline_material_price_common)。
 * 断言(与 hermetic QuotePriceChannelsGoldenTest 同源的生产路径):
 * - Q06-03 hit: source_price 仍对外查询云汉,且本地近期价与云汉两候选都保留
 *   (sources = purchase_analysis_recent_price + yunhan),证据无 manual。
 * - Q06-03 miss: 云汉 miss 但本地有价 → sourced 仍为 true,
 *   sources 仅 purchase_analysis_recent_price(不进入 manual exception),
 *   云汉证据行 status=not_found,证据无 manual。
 */
const HIT_MPN = '1N4148W';
const MISS_MPN = 'NO-SUCH-MPN-ZZZ9999';
const RECENT_PRICE = 1.2;

function quoteLineSeeds() {
  return [
    {
      sourceRef: 'channel-source-hit',
      sourceRowNo: 1,
      description: 'Yunhan catalog hit line',
      refdes: 'R1',
      mpn: HIT_MPN,
      packageName: 'SOD-123',
      qty: 10,
      smtPoints: 0,
      thtPoints: 0,
    },
    {
      sourceRef: 'channel-source-miss',
      sourceRowNo: 2,
      description: 'Yunhan guaranteed miss line',
      refdes: 'R2',
      mpn: MISS_MPN,
      packageName: 'SOD-123',
      qty: 10,
      smtPoints: 0,
      thtPoints: 0,
    },
  ];
}

async function seedOfflineRecentPrice(page: Page, mpn: string, rows: CreatedRows['rows']) {
  await dynamicCreate(page, 'qo_offline_material_price_common', {
    qo_omp_part_no: mpn,
    qo_omp_mpn: mpn,
    qo_omp_description: `E2E local recent-purchase candidate ${mpn}`,
    qo_omp_unit_price: RECENT_PRICE,
    qo_omp_recent_purchase_price: RECENT_PRICE,
    qo_omp_currency: 'CNY',
    qo_omp_status: 'active',
    qo_omp_source_filename: `channel-source-${mpn}.xlsx`,
    qo_omp_source_row_no: 2,
  }, rows);
  // source_price 通过 bom_material_master 把金蝶料号解析成云汉 MPN;缺映射会
  // 回退到描述搜索(生产合同),夹具必须带上与生产一致的映射,否则 miss 行
  // 会被 mock 的 echo 默认产品误判为命中。
  await dynamicCreate(page, 'bom_material_master', {
    bom_mm_material_code: mpn,
    bom_mm_mpn: mpn,
    bom_mm_material_name: `E2E channel-source material ${mpn}`,
    bom_mm_spec_model: 'E2E channel source spec',
    bom_mm_unit: 'PCS',
    bom_mm_brand: 'Mock Manufacturer',
    bom_mm_package: 'SOD-123',
    bom_mm_category: 'resistor',
    bom_mm_enabled: true,
  }, rows);
}

test.describe('quote price channel sources golden (Q06-03) @smoke', () => {
  test.describe.configure({ mode: 'serial', timeout: 300_000 });

  let created: CreatedRows | undefined;

  test.afterAll(async ({ browser }) => {
    if (!created) return;
    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    try {
      await cleanupRows(page, created);
    } finally {
      await context.close();
    }
  });

  test('source_price keeps local recent and yunhan candidates on hit, and stays sourced from local on yunhan miss', async ({ browser }) => {
    const adminContext = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const adminPage = await adminContext.newPage();
    try {
      await setYunhanMockScenario(adminPage, 'release-default');
      created = await seedQuoteScaffold(adminPage, 'CHANSRC', quoteLineSeeds());
      const seededRows: CreatedRows['rows'] = [];
      await seedOfflineRecentPrice(adminPage, HIT_MPN, seededRows);
      await seedOfflineRecentPrice(adminPage, MISS_MPN, seededRows);
      created.rows.push(...seededRows);

      const lines = await queryDynamicRecords(adminPage, 'qo_quote_line_common', [
        { fieldName: 'qo_ql_quote_id', operator: 'EQ', value: created.quoteId },
      ]);
      const hitLine = lines.find((l) => String(l.qo_ql_mpn ?? '') === HIT_MPN);
      const missLine = lines.find((l) => String(l.qo_ql_mpn ?? '') === MISS_MPN);
      expect(hitLine, 'hit line persisted').toBeTruthy();
      expect(missLine, 'miss line persisted').toBeTruthy();

      // 云汉命中:本地近期价不短路云汉,两候选都保留
      const hitResult = (await executeCommand(
        adminPage,
        'qo_quote_line_common:source_price',
        {},
        String(hitLine!.pid),
      )) as Record<string, unknown>;
      expect(hitResult.sourced, JSON.stringify(hitResult).slice(0, 400)).toBe(true);
      expect(hitResult.sources, 'hit keeps both the local recent candidate and the yunhan candidate').toEqual(
        ['purchase_analysis_recent_price', 'yunhan'],
      );
      const hitEvidence = await queryDynamicRecords(adminPage, 'qo_price_evidence_common', [
        { fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: String(hitLine!.pid) },
      ]);
      expect(new Set(hitEvidence.map((row) => String(row.qo_pe_source)))).toEqual(
        new Set(['purchase_analysis_recent_price', 'yunhan']),
      );
      expect(hitEvidence.some((row) => String(row.qo_pe_source) === 'manual'),
        'hit line must not create manual evidence').toBe(false);

      // 云汉 miss:本地有价 → 仍 sourced 且不进入 manual exception
      const missResult = (await executeCommand(
        adminPage,
        'qo_quote_line_common:source_price',
        {},
        String(missLine!.pid),
      )) as Record<string, unknown>;
      expect(missResult.sourced, JSON.stringify(missResult).slice(0, 400)).toBe(true);
      expect(missResult.sources, 'miss line stays on the local recent channel only').toEqual(
        ['purchase_analysis_recent_price'],
      );
      const missEvidence = await queryDynamicRecords(adminPage, 'qo_price_evidence_common', [
        { fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: String(missLine!.pid) },
      ]);
      expect(missEvidence.some((row) =>
        String(row.qo_pe_source) === 'yunhan' && String(row.qo_pe_status ?? '') === 'not_found',
      ), 'the yunhan miss is recorded as a not_found evidence row').toBe(true);
      expect(missEvidence.some((row) => String(row.qo_pe_source) === 'manual'),
        'miss line must not fall back to a manual exception').toBe(false);
    } finally {
      await adminContext.close();
    }
  });
});
