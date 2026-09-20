import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  executeCommand,
  makeQuoteRoleUser,
  openQuoteRolePage,
  queryDynamicRecords,
  ensureQuoteRoleUser,
  type CreatedRows,
  type QuoteRoleUser,
} from './quote-e2e-helpers';
import { waitForDynamicPageLoad } from '../helpers';

/**
 * B01-01: 销售打开自己客户的详情并查看关联项目列表。
 * 产品面:客户详情页「关联项目」页签(sub-table,按 bom_project_customer_id 关联
 * req_requirement_set_pcba_bom)。断言:
 * - 该客户的记录展示在页签中;刷新后一致;截图留档;
 * - 数据范围:其他客户的项目不出现在该客户的页签中(API EQ 集合一致)。
 */
const uid = `${Date.now()}`;

test.describe('customer related-projects tab golden (B01-01) @smoke', () => {
  test.describe.configure({ mode: 'serial', timeout: 240_000 });

  const users: Record<string, QuoteRoleUser> = {};
  const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };

  test.afterAll(async ({ browser }) => {
    if (!created.rows.length) return;
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

  test('related-projects tab lists only the owned customer projects and stays consistent on reload', async ({ browser }, info) => {
    const sales = makeQuoteRoleUser('projects_sales', uid, ['qo_sales']);
    const adminContext = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const adminPage = await adminContext.newPage();
    await ensureQuoteRoleUser(adminPage, sales);
    await adminContext.close();

    const own = await openQuoteRolePage(browser, sales);
    try {
      // 销售自建客户 A/B 与各自项目(项目归属 = 客户)
      const accountA = await executeCommand(own.page, 'crm:create_account', {
        crm_acc_name: `E2E B01-01 Customer A ${uid}`, crm_acc_industry: 'electronics', crm_acc_rating: 'A',
      }, undefined, 'create');
      const customerA = String(accountA.recordId ?? accountA.pid ?? accountA.id ?? '');
      const accountB = await executeCommand(own.page, 'crm:create_account', {
        crm_acc_name: `E2E B01-01 Customer B ${uid}`, crm_acc_industry: 'electronics', crm_acc_rating: 'A',
      }, undefined, 'create');
      const customerB = String(accountB.recordId ?? accountB.pid ?? accountB.id ?? '');
      created.rows.push({ model: 'crm_account_common', pid: customerA });
      created.rows.push({ model: 'crm_account_common', pid: customerB });

      const projectA = await executeCommand(own.page, 'bom:create_project', {
        bom_project_name: `E2E B01-01 Project A ${uid}`, bom_project_customer_id: customerA,
        bom_pcba_code: `PCBA-A-${uid}`, bom_project_library_source: 'excel_current_library',
      }, undefined, 'create');
      const projectAId = String(projectA.recordId ?? projectA.pid ?? projectA.projectId ?? '');
      const projectB = await executeCommand(own.page, 'bom:create_project', {
        bom_project_name: `E2E B01-01 Project B ${uid}`, bom_project_customer_id: customerB,
        bom_pcba_code: `PCBA-B-${uid}`, bom_project_library_source: 'excel_current_library',
      }, undefined, 'create');
      const projectBId = String(projectB.recordId ?? projectB.pid ?? projectB.projectId ?? '');
      created.rows.push({ model: 'req_requirement_set_pcba_bom', pid: projectAId });
      created.rows.push({ model: 'req_requirement_set_pcba_bom', pid: projectBId });

      // 打开客户 A 详情 → 关联项目页签:仅显示 A 的项目
      await own.page.goto(`/p/crm_account_common/view/${customerA}`, { waitUntil: 'domcontentloaded' });
      await waitForDynamicPageLoad(own.page, 20_000);
      await own.page.getByText('关联项目', { exact: true }).first().click();
      await expect(
        own.page.getByRole('row').filter({ hasText: `E2E B01-01 Project A ${uid}` }).first(),
        'customer A detail lists its own project',
      ).toBeVisible({ timeout: 20_000 });
      expect(
        await own.page.getByText(`E2E B01-01 Project B ${uid}`).count(),
        'customer B project must not leak into customer A related projects',
      ).toBe(0);

      // 刷新后一致
      await own.page.reload({ waitUntil: 'domcontentloaded' });
      await waitForDynamicPageLoad(own.page, 20_000);
      await own.page.getByText('关联项目', { exact: true }).first().click();
      await expect(
        own.page.getByRole('row').filter({ hasText: `E2E B01-01 Project A ${uid}` }).first(),
        'related project survives reload',
      ).toBeVisible({ timeout: 20_000 });
      expect(
        await own.page.getByText(`E2E B01-01 Project B ${uid}`).count(),
        'customer B project still absent after reload',
      ).toBe(0);
      await info.attach('B01-01-related-projects', {
        body: await own.page.screenshot({ fullPage: true }), contentType: 'image/png',
      });

      // 与后台集合一致:A 名下项目集合与页签展示同源
      const backendProjects = await queryDynamicRecords(own.page, 'req_requirement_set_pcba_bom', [
        { fieldName: 'bom_project_customer_id', operator: 'EQ', value: customerA },
      ]);
      expect(backendProjects.map((p) => String(p.pid)).sort()).toEqual([projectAId].sort());
    } finally {
      await own.context.close();
    }
  });
});
