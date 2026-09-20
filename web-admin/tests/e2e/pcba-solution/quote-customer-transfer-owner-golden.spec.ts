import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  ensureQuoteRoleUser,
  executeCommand,
  makeQuoteRoleUser,
  openQuoteRolePage,
  queryDynamicRecords,
  type CreatedRows,
  type QuoteRoleUser,
} from './quote-e2e-helpers';
import { waitForDynamicPageLoad } from '../helpers';
import { uniqueId } from '../helpers';

/**
 * Quote/BOM 真机 — 客户转移负责人 self 范围合同 (B01-03).
 * 产品面:桌面端为列表页批量动作「批量转移负责人」(bulk_field_command,
 * 底层合同是 crm:update_account + crm_acc_owner,MemberPicker 只负责输入收集);
 * 移动端表单页 crm_account_transfer_owner_form 为 mobileOnly 入口。
 * 断言:
 * - 原负责人(sales A)创建客户并可见(转移前基线,同一会话即旧 token)。
 * - admin 转移后新负责人(sales B)可见:UI 列表出现、API 可读、owner 字段为新成员。
 * - 原负责人按 self 范围失效:转移前已打开的旧会话(token)刷新后 UI 不可见,
 *   API 读取为空;重新登录后同样不可见。
 */
const uid = uniqueId('transfer').replace(/_/g, '-');

test.describe('customer transfer owner self-scope golden (B01-03) @smoke', () => {
  test.describe.configure({ mode: 'serial', timeout: 300_000 });

  const users: Record<string, QuoteRoleUser> = {};
  let created: CreatedRows | undefined;
  const accountName = `E2E Transfer Owner Customer ${uid}`;

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

  test('transfer flips self-scope visibility: new owner sees the account, old owner loses it on the old token and after re-login', async ({ browser }) => {
    const adminContext = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const adminPage = await adminContext.newPage();
    try {
      // ab_user.email 是 VARCHAR(64):key 与 uid 都取短,避免超长导致 500。
      const shortUid = uid.slice(-14);
      users['oldOwner'] = makeQuoteRoleUser('transfer_old', `${shortUid}a`, ['qo_sales']);
      users['newOwner'] = makeQuoteRoleUser('transfer_new', `${shortUid}b`, ['qo_sales']);
      await ensureQuoteRoleUser(adminPage, users['oldOwner']);

      // crm_acc_owner 引用 sys_user.pid(不是 tenant_member.pid),创建用户时直接捕获。
      const createdUser = await adminPage.request.post('/api/admin/users', {
        data: {
          email: users['newOwner'].email,
          displayName: users['newOwner'].displayName,
          initialPassword: users['newOwner'].password,
          roleCodes: users['newOwner'].roleCodes,
          sendInviteEmail: false,
        },
        timeout: 20_000,
      });
      const createdBody = await createdUser.json().catch(() => ({}));
      expect(createdUser.ok(), `create new owner user: ${JSON.stringify(createdBody).slice(0, 300)}`).toBe(true);
      const newOwnerUserPid = String((createdBody as any).data?.userPid ?? '');
      expect(newOwnerUserPid, 'created user carries a sys_user pid').toBeTruthy();

      // 原负责人(sales A)创建客户:创建 + 基线可见断言在同一个旧会话内完成,
      // 该会话跨转移保持打开,即「旧 token」腿。
      const oldSession = await openQuoteRolePage(browser, users['oldOwner']);
      const accountResult = await executeCommand(
        oldSession.page,
        'crm:create_account',
        {
          crm_acc_name: accountName,
          crm_acc_industry: 'electronics',
          crm_acc_rating: 'A',
        },
        undefined,
        'create',
      );
      const accountId = String(
        accountResult.recordId ?? accountResult.pid ?? accountResult.id ?? '',
      );
      expect(accountId, 'sales old owner can create a customer').toBeTruthy();
      created = { quoteId: '', quoteCode: '', rows: [{ model: 'crm_account_common', pid: accountId }] };
      await oldSession.page.goto('/p/crm_account_common', { waitUntil: 'domcontentloaded' });
      await waitForDynamicPageLoad(oldSession.page, 20_000);
      await expect(
        oldSession.page.getByText(accountName).first(),
        'old owner sees the account before the transfer (baseline)',
      ).toBeVisible({ timeout: 20_000 });

      // admin 执行转移:动态字段更新(crm_acc_owner 引用 sys_user)。
      // 产品缺陷登记:crm:update_account 虽声明 crm_acc_owner 为输入字段,但命令路径
      // 静默丢弃该字段(返回成功、owner 不变);字段更新走 dynamic PUT 才生效。
      const transferResponse = await adminPage.request.put(
        `/api/dynamic/crm_account_common/${accountId}`,
        { data: { crm_acc_owner: newOwnerUserPid }, timeout: 20_000 },
      );
      expect(
        transferResponse.ok(),
        `owner transfer via dynamic PUT: ${JSON.stringify(await transferResponse.json().catch(() => ({}))).slice(0, 300)}`,
      ).toBe(true);
      const afterRows = await queryDynamicRecords(adminPage, 'crm_account_common', [
        { fieldName: 'crm_acc_name', operator: 'EQ', value: accountName },
      ]);
      expect(String(afterRows[0]?.crm_acc_owner ?? ''), 'ownership moved to the new owner user').toBe(
        newOwnerUserPid,
      );

      // 新负责人(sales B):UI 列表可见(self 范围生效)
      const newSession = await openQuoteRolePage(browser, users['newOwner']);
      try {
        await newSession.page.goto('/p/crm_account_common', { waitUntil: 'domcontentloaded' });
        await waitForDynamicPageLoad(newSession.page, 20_000);
        await expect(
          newSession.page.getByText(accountName).first(),
          'new owner sees the transferred account in their own list',
        ).toBeVisible({ timeout: 20_000 });
        await test.info().attach('B01-03-new-owner-sees', {
          body: await newSession.page.screenshot({ fullPage: true }), contentType: 'image/png',
        });
      } finally {
        await newSession.context.close();
      }

      // 原负责人旧 token:同一会话刷新后 UI 不可见,API 读取为空
      await oldSession.page.goto('/p/crm_account_common', { waitUntil: 'domcontentloaded' });
      await waitForDynamicPageLoad(oldSession.page, 20_000);
      await oldSession.page.waitForTimeout(2_000);
      expect(
        await oldSession.page.getByText(accountName).count(),
        'old token no longer sees the account after transfer',
      ).toBe(0);
      const oldRecords = await queryDynamicRecords(oldSession.page, 'crm_account_common', [
        { fieldName: 'crm_acc_name', operator: 'EQ', value: accountName },
      ]);
      expect(oldRecords.length, 'old owner API read returns nothing after transfer').toBe(0);
      await test.info().attach('B01-03-old-owner-blinded', {
        body: await oldSession.page.screenshot({ fullPage: true }), contentType: 'image/png',
      });
      await oldSession.context.close();

      // 原负责人重新登录:仍不可见
      const oldRelogin = await openQuoteRolePage(browser, users['oldOwner']);
      try {
        await oldRelogin.page.goto('/p/crm_account_common', { waitUntil: 'domcontentloaded' });
        await waitForDynamicPageLoad(oldRelogin.page, 20_000);
        await oldRelogin.page.waitForTimeout(2_000);
        expect(
          await oldRelogin.page.getByText(accountName).count(),
          'old owner still cannot see the account after re-login',
        ).toBe(0);
      } finally {
        await oldRelogin.context.close();
      }

      // admin 视角:记录仍在(未删除,只是换主)
      const adminRows = await queryDynamicRecords(adminPage, 'crm_account_common', [
        { fieldName: 'crm_acc_name', operator: 'EQ', value: accountName },
      ]);
      expect(adminRows.length, 'account still exists for admin after transfer').toBe(1);
    } finally {
      await adminContext.close();
    }
  });
});
