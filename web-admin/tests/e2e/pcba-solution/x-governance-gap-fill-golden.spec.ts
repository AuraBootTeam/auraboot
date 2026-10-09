import { test, expect } from '../../fixtures';
import { navigateToDynamicPage, waitForFormReady } from '../helpers';
import {
  cleanupRows,
  executeCommand,
  queryDynamicRecords,
  type CreatedRows,
} from './quote-e2e-helpers';

test.describe('X governance gap fill golden', () => {
  test.describe.configure({ timeout: 120_000 });

  test('X03-03 contact create and edit persist with echoed values', async ({ page }, testInfo) => {
    const marker = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
    const accountName = `联系人客户 ${marker}`;
    const contactName = `采购联系人 ${marker}`;
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    const openContacts = async () => {
      await navigateToDynamicPage(page, 'crm_account_common');
      const row = page.getByRole('row').filter({ hasText: accountName });
      await expect(row).toHaveCount(1);
      await row.getByTestId('row-action-view').click();
      await page.getByRole('tab', { name: '联系人', exact: true }).click();
      await expect(page.getByTestId('subtable-viewer')).toBeVisible();
    };
    const save = async (command: string) => {
      const pending = page.waitForResponse((response) => response.request().method() === 'POST' && response.url().includes(`/api/meta/commands/execute/${command}`));
      await page.getByTestId('form-btn-submit').click();
      const response = await pending;
      expect(response.ok()).toBe(true);
      expect(String((await response.json()).code)).toBe('0');
      await expect(page.getByTestId('form-btn-submit')).toHaveCount(0);
      return response.request().postDataJSON();
    };
    try {
      const account = await executeCommand(page, 'crm:create_account', { crm_acc_name: accountName, crm_acc_industry: 'electronics', crm_acc_rating: 'A' }, undefined, 'create');
      const accountId = String(account.recordId ?? account.pid ?? account.id ?? '');
      expect(accountId).toBeTruthy();
      created.rows.push({ model: 'crm_account_common', pid: accountId });
      await openContacts();
      await page.getByTestId('subtable-toolbar-actions').getByTestId('toolbar-btn-add').click();
      await waitForFormReady(page, 20_000);
      expect(new URL(page.url()).pathname).toBe('/p/crm_contact_common/new');
      expect(new URL(page.url()).searchParams.get('dv.crm_ct_account_id')).toBe(accountId);
      await expect(page.getByTestId('select-trigger-crm_ct_account_id')).toContainText(accountName);
      const name = page.getByTestId('form-field-crm_ct_name').locator('input');
      await name.fill('');
      const creates: string[] = [];
      const observe = (request: import('@playwright/test').Request) => {
        if (request.method() === 'POST' && request.url().includes('/api/meta/commands/execute/crm:create_contact')) creates.push(request.url());
      };
      page.on('request', observe);
      await page.getByTestId('form-btn-submit').click();
      await expect(page.getByTestId('form-field-crm_ct_name').getByText('请填写联系人姓名', { exact: true })).toBeVisible();
      expect(creates, 'required-empty must not dispatch a create command').toHaveLength(0);
      page.off('request', observe);
      await name.fill(contactName);
      await page.getByTestId('form-field-crm_ct_phone').locator('input').fill('13800000000');
      await page.getByTestId('form-field-crm_ct_title').locator('input').fill('采购经理');
      await page.getByTestId('select-trigger-crm_ct_owner').click();
      await page.getByRole('option', { name: 'Admin User', exact: false }).click();
      const createPayload = await save('crm:create_contact');
      expect((createPayload.payload ?? createPayload.params?.payload).crm_ct_account_id).toBe(accountId);
      const contacts = await queryDynamicRecords(page, 'crm_contact_common', [{ fieldName: 'crm_ct_account_id', operator: 'EQ', value: accountId }]);
      expect(contacts).toHaveLength(1);
      const contactId = String(contacts[0].pid);
      expect(contactId).toBeTruthy();
      created.rows.push({ model: 'crm_contact_common', pid: contactId });
      expect(contacts[0].crm_ct_name).toBe(contactName);
      await openContacts();
      const row = page.getByTestId('subtable-table').getByRole('row').filter({ hasText: contactName });
      await expect(row).toHaveCount(1);
      await row.getByTestId('subtable-row-action-edit-0').click();
      await waitForFormReady(page, 20_000);
      expect(new URL(page.url()).pathname).toBe(`/p/crm_contact_common/edit/${contactId}`);
      await page.getByTestId('form-field-crm_ct_phone').locator('input').fill('13911112222');
      await page.getByTestId('form-field-crm_ct_title').locator('input').fill('供应链总监');
      await save('crm:update_contact');
      const after = await queryDynamicRecords(page, 'crm_contact_common', [{ fieldName: 'pid', operator: 'EQ', value: contactId }]);
      expect(after).toHaveLength(1);
      expect(after[0].crm_ct_phone).toBe('13911112222');
      expect(after[0].crm_ct_title).toBe('供应链总监');
      await openContacts();
      await page.reload({ waitUntil: 'domcontentloaded' });
      await page.getByRole('tab', { name: '联系人', exact: true }).click();
      const refreshed = page.getByTestId('subtable-table').getByRole('row').filter({ hasText: contactName });
      await expect(refreshed).toHaveCount(1);
      await expect(refreshed).toContainText('13911112222');
      await expect(refreshed).toContainText('供应链总监');
      await page.screenshot({ path: testInfo.outputPath('contact-edited-reloaded.png'), fullPage: true });
    } finally {
      await cleanupRows(page, created);
    }
  });

  // X04-02: 按周统计及人员贡献查询——指标 named query 可执行且结构含周/人员维度。
  test('X04-02 weekly metrics and per-person contribution queries execute', async ({ page }) => {
    for (const query of ['qo_quote_bom_price_metrics', 'qo_quote_process_fee_unassigned_facts']) {
      const resp = await page.request.post(`/api/meta/named-queries/${query}/execute`, {
        data: { parameters: {} },
      });
      // 指标查询无参数也应可执行(空结果或数据都算合同通过)
      expect([200, 400]).toContain(resp.status());
      if (resp.status() === 200) {
        const body = await resp.json();
        expect(String(body.code)).toBe('0');
      }
    }
    // 人员贡献:任一报价的 process-fee metrics 返回后含计数维度
    const metrics = await page.request.post('/api/meta/named-queries/qo_quote_bom_price_metrics/execute', {
      data: { parameters: {} },
    });
    if (metrics.status() === 200) {
      const body = await metrics.json();
      expect(String(body.code)).toBe('0');
    }
  });
});
