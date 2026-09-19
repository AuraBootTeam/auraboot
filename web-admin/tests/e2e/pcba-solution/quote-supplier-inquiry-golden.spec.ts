import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  ensureQuoteRoleUser,
  makeQuoteRoleUser,
  openQuoteRolePage,
  queryDynamicRecords,
  searchBusinessList,
  type CreatedRows,
} from './quote-e2e-helpers';

test.describe('PCBA quote supplier inquiry golden', () => {
  test.describe.configure({ timeout: 120_000 });

  const uid = `${Date.now()}`;
  const users: Record<string, import('./quote-e2e-helpers').QuoteRoleUser> = {};

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    users['sales'] = makeQuoteRoleUser('qo_sales', uid, ['qo_sales']);
    await ensureQuoteRoleUser(page, users['sales']);
    await ctx.close();
  });

  // Q19-02: 供应商报价回填到询价行——回填与行关联正确;重复回填幂等;状态流转按合同。
  test('Q19-02 supplier quote backfill persists on the inquiry line, stays idempotent and flows status', async ({
    page,
  }) => {
    const marker = `SRFE2E-${Date.now()}`;
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    try {
      // 询价单头 + 询价行(挂接真实报价行)
      const head = await page.request.post('/api/dynamic/qo_supplier_request_common/create', {
        data: { qo_sr_code: marker, qo_sr_category: 'pcb', qo_sr_status: 'draft', qo_sr_supplier: 'E2E Supplier' },
      });
      expect(head.ok(), await head.text()).toBe(true);
      const headPid = String(((await head.json()).data ?? {}).pid ?? '');

      const quoteLine = (
        await queryDynamicRecords(page, 'qo_quote_line_common', [], { pageSize: 1 })
      )[0];
      expect(quoteLine, 'fixture must contain a quote line').toBeTruthy();

      const line = await page.request.post('/api/dynamic/qo_supplier_request_line_common/create', {
        data: {
          qo_srl_part_no: `${marker}-PART`,
          qo_srl_qty: 100,
          qo_srl_status: 'draft',
          qo_srl_currency: 'CNY',
          qo_srl_supplier: 'E2E Supplier',
          qo_srl_quote_id: String(quoteLine.pid),
          qo_srl_quote_line_id: String(quoteLine.pid),
          qo_srl_supplier_rfq_id: headPid,
          qo_srl_supplier_rfq_code: marker,
        },
      });
      expect(line.ok(), await line.text()).toBe(true);
      const linePid = String(((await line.json()).data ?? {}).pid ?? '');
      created.rows.push({ model: 'qo_supplier_request_common', pid: headPid });
      created.rows.push({ model: 'qo_supplier_request_line_common', pid: linePid });

      // 供应商报价回填:价格/引用/状态
      const backfill = { qo_srl_unit_price: 1.23, qo_srl_status: 'quoted', qo_srl_supplier_quote_ref: 'QUO-REF-1' };
      const firstFill = await page.request.put(
        `/api/dynamic/qo_supplier_request_line_common/${linePid}`,
        { data: backfill },
      );
      expect(firstFill.ok(), 'first backfill persists').toBe(true);
      const afterFirst = await queryDynamicRecords(page, 'qo_supplier_request_line_common', [
        { fieldName: 'pid', operator: 'EQ', value: linePid },
      ]);
      expect(Number(afterFirst[0].qo_srl_unit_price)).toBe(1.23);
      expect(String(afterFirst[0].qo_srl_status)).toBe('quoted');
      expect(String(afterFirst[0].qo_srl_supplier_quote_ref)).toBe('QUO-REF-1');
      // 行关联正确:报价行引用保持
      expect(String(afterFirst[0].qo_srl_quote_line_id)).toBe(String(quoteLine.pid));

      // 重复回填幂等:同值再填,业务值不变(版本号可递增,内容收敛)
      await page.request.put(`/api/dynamic/qo_supplier_request_line_common/${linePid}`, {
        data: backfill,
      });
      const afterSecond = await queryDynamicRecords(page, 'qo_supplier_request_line_common', [
        { fieldName: 'pid', operator: 'EQ', value: linePid },
      ]);
      expect(Number(afterSecond[0].qo_srl_unit_price)).toBe(1.23);
      expect(String(afterSecond[0].qo_srl_status)).toBe('quoted');
      expect(String(afterSecond[0].qo_srl_supplier_quote_ref)).toBe('QUO-REF-1');
      // 状态流转按合同:回填后即为 quoted(draft -> quoted)
      expect(String(afterSecond[0].qo_srl_status)).not.toBe('draft');
    } finally {
      await cleanupRows(page, created);
    }
  });

  // Q19-03: 非采购角色调用询价维护接口——按角色拒绝且记录不变。
  test('Q19-03 non-procurement role is denied inquiry maintenance and the record stays unchanged', async ({
    browser,
  }) => {
    const marker = `SRFDENY-${Date.now()}`;
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    const adminCtx = await browser.newContext();
    const adminPage = await adminCtx.newPage();
    try {
      const head = await adminPage.request.post('/api/dynamic/qo_supplier_request_common/create', {
        data: { qo_sr_code: marker, qo_sr_category: 'pcb', qo_sr_status: 'draft', qo_sr_supplier: 'E2E Supplier' },
      });
      expect(head.ok()).toBe(true);
      const headPid = String(((await head.json()).data ?? {}).pid ?? '');
      created.rows.push({ model: 'qo_supplier_request_common', pid: headPid });

      const before = await queryDynamicRecords(adminPage, 'qo_supplier_request_common', [
        { fieldName: 'qo_sr_code', operator: 'EQ', value: marker },
      ]);
      expect(before).toHaveLength(1);
      const beforeVersion = Number(before[0].row_version);

      // 非采购角色(qo_sales)直接调用询价单维护更新 → 拒绝且记录不变
      const { context, page } = await openQuoteRolePage(browser, users['sales']);
      try {
        const denied = await page.request.put(`/api/dynamic/qo_supplier_request_common/${headPid}`, {
          data: { qo_sr_supplier: 'Hijacked Supplier' },
        });
        expect([403, 400], `denied status, got ${denied.status()}`).toContain(denied.status());
        const afterDenied = await queryDynamicRecords(adminPage, 'qo_supplier_request_common', [
          { fieldName: 'qo_sr_code', operator: 'EQ', value: marker },
        ]);
        expect(String(afterDenied[0].qo_sr_supplier ?? '')).not.toBe('Hijacked Supplier');
        expect(Number(afterDenied[0].row_version)).toBe(beforeVersion);
      } finally {
        await context.close();
      }
    } finally {
      await cleanupRows(adminPage, created);
      await adminCtx.close();
    }
  });
});
