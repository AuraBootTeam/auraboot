import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  dynamicCreate,
  queryDynamicRecords,
  type CreatedRows,
} from './quote-e2e-helpers';

test.describe('PCBA quote offline price library golden', () => {
  test.describe.configure({ timeout: 120_000 });

  // Q06-02: 本地离线价格库列表——API/DB/UI 三方数值一致,筛选与分页正确;截图留档。
  // DB 值经动态读接口(与列表同源)回读,UI 值经表格单元格断言。
  test('Q06-02 offline price list: API/DB/UI values agree, keyword filter narrows, pagination traverses', async ({
    page,
  }, info) => {
    const marker = `OFFPRICE${Date.now()}`;
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    const seeded = [
      { mpn: `${marker}-A`, price: '0.125', moq: '1000' },
      { mpn: `${marker}-B`, price: '1.0300', moq: '500' },
      { mpn: `${marker}-C`, price: '12.5000', moq: '2400' },
    ];
    try {
      for (const item of seeded) {
        await dynamicCreate(page, 'qo_offline_material_price_common', {
          qo_omp_mpn: item.mpn,
          qo_omp_part_no: item.mpn,
          qo_omp_description: `offline price golden ${item.mpn}`,
          qo_omp_unit_price: item.price,
          qo_omp_recent_purchase_price: item.price,
          qo_omp_currency: 'CNY',
          qo_omp_moq: item.moq,
          qo_omp_mpq: '1',
          qo_omp_supplier_name: 'E2E Offline Supplier',
          qo_omp_status: 'active',
          qo_omp_source_filename: 'e2e-offline-price.xlsx',
          qo_omp_source_row_no: 1,
          qo_omp_import_version: marker,
        }, created.rows);
      }

      // API 分页合同:pageSize=2 时两页覆盖全部 3 行(分页正确)
      const firstPage = await page.request.get(
        `/api/dynamic/qo_offline_material_price_common/list?pageNum=1&pageSize=2&keyword=${marker}`,
      );
      expect(firstPage.ok()).toBe(true);
      const firstBody = await firstPage.json();
      const firstRows = firstBody.data?.records ?? firstBody.data?.list ?? [];
      expect(firstRows.length, 'page 1 honors pageSize=2').toBe(2);
      const secondPage = await page.request.get(
        `/api/dynamic/qo_offline_material_price_common/list?pageNum=2&pageSize=2&keyword=${marker}`,
      );
      const secondRows = (await (await secondPage.json()).data?.records ?? []) as unknown[];
      expect(firstRows.length + secondRows.length, 'both pages cover all seeded rows').toBe(3);

      // UI:关键字筛选只中本批次行,单元格数值与 API/DB 值一致
      await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
      const listPath = '/p/qo_offline_material_price_common';
      await page.goto(listPath, { waitUntil: 'domcontentloaded' });
      const search = page.getByTestId('list-search-input');
      await expect(search).toBeVisible({ timeout: 20_000 });
      await search.fill(seeded[0].mpn);
      await search.press('Enter');
      const row = page.getByRole('row').filter({ hasText: seeded[0].mpn }).first();
      await expect(row).toBeVisible({ timeout: 20_000 });
      const uiText = await row.innerText();
      expect(uiText).toContain(seeded[0].price);
      expect(uiText).toContain('E2E Offline Supplier');
      // 其他两行不在当前筛选结果里
      await expect(page.getByRole('row').filter({ hasText: seeded[1].mpn })).toHaveCount(0);

      // API 单条回读(DB 值,与列表同源):单价/币种/供应商与 UI 一致
      const apiRows = await queryDynamicRecords(page, 'qo_offline_material_price_common', [
        { fieldName: 'qo_omp_mpn', operator: 'EQ', value: seeded[0].mpn },
      ]);
      expect(apiRows, 'API returns the seeded row').toHaveLength(1);
      expect(String(apiRows[0].qo_omp_unit_price ?? '')).toBe(seeded[0].price);
      expect(String(apiRows[0].qo_omp_supplier_name ?? '')).toBe('E2E Offline Supplier');
      await info.attach('q06-02-offline-price-list', {
        body: await page.screenshot({ fullPage: true }),
        contentType: 'image/png',
      });
    } finally {
      await cleanupRows(page, created);
    }
  });
});
