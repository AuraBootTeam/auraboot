import { test, expect } from '../../fixtures';
import { clickRowActionByLocator } from '../helpers';
import {
  cleanupRows,
  ensureQuoteRoleUser,
  makeQuoteRoleUser,
  openQuoteRolePage,
  queryDynamicRecords,
  reassignRecordOwnerByEmail,
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
    users['procurement'] = makeQuoteRoleUser('inquiry_procurement', uid, ['qo_procurement']);
    await ensureQuoteRoleUser(page, users['procurement']);
    const code = `e2e_inquiry_reader_${uid}`;
    const roleResponse = await page.request.post('/api/roles', { data: { code, name: code, type: 'custom' } });
    expect(roleResponse.ok()).toBe(true);
    const roleBody = await roleResponse.json();
    expect(String(roleBody.code)).toBe('0');
    const role = roleBody.data;
    expect(role.pid).toBeTruthy();
    expect((await page.request.put(`/api/permissions/matrix/${role.pid}/default-scope`, {
      data: { scopeType: 'self' },
    })).status()).toBe(200);
    expect((await page.request.put(`/api/permission/capabilities?rolePid=${role.pid}`, {
      data: ['qo.cap.read'],
    })).status()).toBe(200);
    users['reader'] = makeQuoteRoleUser('inquiry_reader', uid, [code]);
    await ensureQuoteRoleUser(page, users['reader']);
    await ctx.close();
  });

  // Q19-02: 供应商报价回填到询价行——回填与行关联正确;重复回填幂等;状态流转按合同。
  test('Q19-02 supplier quote backfill persists on the inquiry line, stays idempotent and flows status', async ({
    page,
  }) => {
    const marker = `SRFE2E-${Date.now()}`;
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    try {
      // Q19-01 uses the existing RFQ page and commands; API remains setup/readback only.
      const procurement = await openQuoteRolePage(page.context().browser()!, users['procurement']);
      let headPid = '';
      try {
        const ui = procurement.page;
        await ui.goto('/p/qo_procurement_workbench');
        await ui.getByRole('button', { name: '供应商询价', exact: true }).click();
        await expect(ui).toHaveURL(/\/p\/qo_supplier_request_common$/);
        await ui.getByRole('button', { name: '新建', exact: true }).click();
        const field = (name: string) => ui.getByTestId(`form-field-${name}`).getByRole('textbox');
        const commands: string[] = [];
        ui.on('request', request => {
          if (request.method() === 'POST' && request.url().includes('/api/meta/commands/execute/qo_supplier_request_common:')) commands.push(request.url());
        });
        await ui.getByTestId('form-btn-create').click();
        await expect(ui.getByTestId('form-field-qo_sr_code')).toContainText('此字段为必填项');
        await expect(ui.getByTestId('form-field-qo_sr_category')).toContainText('此字段为必填项');
        expect(commands, 'missing required fields must not send a command').toEqual([]);
        await ui.screenshot({ path: test.info().outputPath('inquiry-required-fields.png'), fullPage: true });
        await field('qo_sr_code').fill(marker);
        await field('qo_sr_category').fill('pcb');
        await field('qo_sr_supplier').fill('E2E Supplier');
        const createdResponse = ui.waitForResponse(response => response.request().method() === 'POST' && response.url().includes('/api/meta/commands/execute/qo_supplier_request_common:create'));
        await ui.getByTestId('form-btn-create').click();
        const createdBody = await (await createdResponse).json();
        expect(String(createdBody.code)).toBe('0');
        const heads = await queryDynamicRecords(page, 'qo_supplier_request_common', [{ fieldName: 'qo_sr_code', operator: 'EQ', value: marker }]);
        expect(heads).toHaveLength(1);
        headPid = String(heads[0].pid);
        created.rows.push({ model: 'qo_supplier_request_common', pid: headPid });
        expect(heads[0].qo_sr_category).toBe('pcb');
        expect(heads[0].qo_sr_supplier).toBe('E2E Supplier');
        expect(heads[0].qo_sr_status).toBe('draft');
        await searchBusinessList(ui, '/p/qo_supplier_request_common', marker);
        await clickRowActionByLocator(ui, ui.getByRole('row').filter({ hasText: marker }), 'edit', '编辑');
        await expect(field('qo_sr_code')).toHaveValue(marker);
        await field('qo_sr_supplier').fill('E2E Supplier Updated');
        const updatedResponse = ui.waitForResponse(response => response.request().method() === 'POST' && response.url().includes('/api/meta/commands/execute/qo_supplier_request_common:update'));
        await ui.getByTestId('form-btn-update').click();
        expect(String((await (await updatedResponse).json()).code)).toBe('0');
        await searchBusinessList(ui, '/p/qo_supplier_request_common', marker);
        await ui.reload();
        const row = ui.getByRole('row').filter({ hasText: marker });
        await expect(row).toContainText('E2E Supplier Updated');
        await expect(row).toContainText('草稿');
        const saved = await queryDynamicRecords(page, 'qo_supplier_request_common', [{ fieldName: 'pid', operator: 'EQ', value: headPid }]);
        expect(saved[0].qo_sr_supplier).toBe('E2E Supplier Updated');
        await ui.screenshot({ path: test.info().outputPath('inquiry-created-edited-reloaded.png'), fullPage: true });
      } finally { await procurement.context.close(); }

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
          qo_srl_quote_id: String(quoteLine.qo_ql_quote_id),
          qo_srl_quote_line_id: String(quoteLine.pid),
          qo_srl_supplier_rfq_id: headPid,
          qo_srl_supplier_rfq_code: marker,
        },
      });
      expect(line.ok(), await line.text()).toBe(true);
      const linePid = String(((await line.json()).data ?? {}).pid ?? '');
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

  // Q19-03: Read-only duty rejects mutation; the domain write duty may maintain inquiries.
  test('Q19-03 read-only inquiry mutation is denied and domain write remains allowed', async ({
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
      await reassignRecordOwnerByEmail([{ model: 'qo_supplier_request_common', pid: headPid }], users['reader'].email);

      const before = await queryDynamicRecords(adminPage, 'qo_supplier_request_common', [
        { fieldName: 'qo_sr_code', operator: 'EQ', value: marker },
      ]);
      expect(before).toHaveLength(1);
      const beforeVersion = Number(before[0].row_version);

      // The target is owned and readable, so this denial proves the functional boundary.
      const { context, page } = await openQuoteRolePage(browser, users['reader']);
      try {
        expect((await page.request.get(`/api/dynamic/qo_supplier_request_common/${headPid}`)).status()).toBe(200);
        const denied = await page.request.put(`/api/dynamic/qo_supplier_request_common/${headPid}`, {
          data: { qo_sr_supplier: 'Hijacked Supplier' },
        });
        expect(denied.status(), 'Read alone cannot mutate an owned inquiry').toBe(403);
        const afterDenied = await queryDynamicRecords(adminPage, 'qo_supplier_request_common', [
          { fieldName: 'qo_sr_code', operator: 'EQ', value: marker },
        ]);
        expect(String(afterDenied[0].qo_sr_supplier ?? '')).not.toBe('Hijacked Supplier');
        expect(Number(afterDenied[0].row_version)).toBe(beforeVersion);
        expect(afterDenied).toEqual(before);
      } finally {
        await context.close();
      }
      // A writer still needs the existing object audience; use its own target for the positive control.
      await reassignRecordOwnerByEmail([{ model: 'qo_supplier_request_common', pid: headPid }], users['sales'].email);
      const writer = await openQuoteRolePage(browser, users['sales']);
      try {
        const allowed = await writer.page.request.put(`/api/dynamic/qo_supplier_request_common/${headPid}`, {
          data: { qo_sr_supplier: 'Domain-authorized Supplier' },
        });
        expect(allowed.status()).toBe(200);
        expect(String((await allowed.json()).code)).toBe('0');
        const afterWrite = await queryDynamicRecords(adminPage, 'qo_supplier_request_common', [
          { fieldName: 'qo_sr_code', operator: 'EQ', value: marker },
        ]);
        expect(afterWrite[0].qo_sr_supplier).toBe('Domain-authorized Supplier');
        expect(Number(afterWrite[0].row_version)).toBe(beforeVersion + 1);
      } finally { await writer.context.close(); }
    } finally {
      await cleanupRows(adminPage, created);
      await adminCtx.close();
    }
  });
});
