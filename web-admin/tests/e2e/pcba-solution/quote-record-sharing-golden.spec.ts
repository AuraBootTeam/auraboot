import { test, expect } from '../../fixtures';
import { ensureQuoteRoleUser, openQuoteRolePage, openQuoteDetailFromList,
  seedBomPriceManualReviewQuote, type QuoteRoleUser } from './quote-e2e-helpers';

const recipients: QuoteRoleUser[] = [
  {key:'share_a', email:'share-a@e2e.local', displayName:'Sharing Sales A', password:'Test2026x', roleCodes:['qo_sales']},
  {key:'share_b', email:'share-b@e2e.local', displayName:'Sharing Sales B', password:'Test2026x', roleCodes:['qo_sales']},
  {key:'share_proc', email:'share-proc@e2e.local', displayName:'Sharing Procurement', password:'Test2026x', roleCodes:['qo_procurement']},
];

test('quote sharing release gate: multiple members, role access and revocation through UI', async ({ page, browser }, testInfo) => {
  test.setTimeout(180_000);
  const quote = await seedBomPriceManualReviewQuote(page);
  for (const user of recipients) await ensureQuoteRoleUser(page, user);
  const viewers: Awaited<ReturnType<typeof openQuoteRolePage>>[] = [];
  for (const user of recipients) viewers.push(await openQuoteRolePage(browser, user));
  const root = `/api/dynamic/qo_quote_common/${quote.quoteId}`;
  const shareParams = `resourceCode=qo_quote_common&recordPid=${quote.quoteId}`;
  const refreshRevokedViewer = async (index: number) => {
    const client = viewers[index].page;
    const recordResponse = client.waitForResponse(response => new URL(response.url()).pathname === root && response.request().method() === 'GET');
    await client.reload();
    expect((await recordResponse).status()).toBe(403);
    const denied = client.getByTestId('ab:detail:qo_quote_common:container');
    await expect(denied.getByRole('heading', { level: 2 })).toBeVisible();
    await expect(denied.getByRole('heading', { level: 2 })).toHaveText('无法访问此记录');
    await expect(denied.locator('p')).toHaveText('当前账号没有访问权限，请联系记录负责人。');
    await expect(client.getByTestId(`table-row-${quote.lineId}`)).toHaveCount(0);
    await client.screenshot({path:testInfo.outputPath(`quote-revoked-viewer-${index}.png`), fullPage:true});
    await testInfo.attach(`revoked-viewer-${index}`, { body: await client.screenshot(), contentType: 'image/png' });
  };
  const probe = async (index: number, allowed: boolean) => {
    const client = viewers[index].page;
    expect((await client.request.get(root)).status()).toBe(allowed ? 200 : 403);
    for (const query of ['qo_quote_bom_price_metrics', 'qo_quote_process_fee_unassigned_facts']) {
      const response = await client.request.post(`/api/meta/named-queries/${query}/execute`, {
        data: { parameters: { quoteId: quote.quoteId } },
      });
      // Quote collaboration grants every quote tab for this exact shared root only.
      expect(response.status()).toBe(allowed ? 200 : 403);
    }
    const capability = await client.request.get(`/api/record-share/manage-capability?${shareParams}`);
    expect(capability.status()).toBe(allowed ? 200 : 403);
    if (allowed) expect((await capability.json()).data.canManage).toBe(false);
  };
  for (let i=0;i<3;i++) await probe(i,false);
  await openQuoteDetailFromList(page, quote);
  await page.getByTestId('ab:detail:qo_quote_common:share-btn').click();
  const dialog = page.getByTestId('record-share-dialog');
  await expect(dialog).toBeVisible();
  for (const user of recipients.slice(0,2)) {
    await dialog.getByTestId('member-picker-add').click();
    await page.getByTestId('member-picker-search-input').fill(user.email);
    await page.locator('[data-testid^="member-picker-option-"]').filter({hasText:user.displayName}).click();
    // Multiple selection keeps the picker open; dismiss before the next selection.
    await dialog.getByRole('heading', {name:'添加协作成员'}).click();
  }
  const membersSaved = page.waitForResponse(response => response.url().endsWith('/api/record-share') && response.request().method()==='POST');
  await dialog.getByTestId('record-share-add-btn').click();
  const memberResponse = await membersSaved;
  expect(memberResponse.ok()).toBe(true);
  const memberPayload = memberResponse.request().postDataJSON();
  expect(memberPayload.recordPid).toBe(quote.quoteId);
  expect(memberPayload.subjectType).toBe('member');
  expect(memberPayload.subjectPids).toHaveLength(2);
  expect(memberPayload.permissionMask).toBe('read,update');
  await expect(dialog.locator('[data-testid^="record-share-row-"]')).toHaveCount(2);
  await page.screenshot({path:testInfo.outputPath('quote-member-sharing.png'), fullPage:true});
  for(let i=0;i<2;i++) await probe(i,true);
  await probe(2,false);
  await viewers[0].page.goto('/p/qo_quote_common');
  await expect(viewers[0].page.getByText(quote.quoteCode, {exact:false}).first()).toBeVisible();
  await viewers[0].page.goto(`/p/qo_quote_common/view/${quote.quoteId}#bom_price`);
  for (const tabName of ['资料上传','BOM价格计算','加工点数','Gerber校验','报价Excel']) {
    await expect(viewers[0].page.getByRole('tab',{name:tabName,exact:true})).toBeVisible();
  }
  await viewers[0].page.screenshot({path:testInfo.outputPath('quote-collaborator-all-tabs.png'), fullPage:true});
  await expect(viewers[0].page.getByTestId('ab:detail:qo_quote_common:share-btn')).toHaveCount(0);
  await viewers[0].page.getByRole('tab',{name:'BOM价格计算',exact:true}).click();
  await expect(viewers[0].page.getByTestId(`table-row-${quote.lineId}`)).toContainText(quote.mpn);
  await expect(viewers[0].page.getByTestId(`table-row-${quote.lineId}`)).toContainText(/1\.1111|1\.111|1\.11/);
  expect((await viewers[0].page.request.put(root,{data:{qo_quote_customer:'Denied shared edit'}})).status()).toBe(403);
  const reader = viewers[0].page;
  await reader.getByRole('button',{name:/修改套数/}).click();
  await reader.getByTestId('form-dialog-field-qo_quote_set_count').fill('2');
  const sharedEdit = reader.waitForResponse(response => response.url().includes('/api/meta/commands/execute/qo_quote_common:recompute_quantities') && response.request().method()==='POST');
  await reader.getByTestId('form-dialog-submit').click();
  const sharedEditResponse = await sharedEdit;
  expect(sharedEditResponse.ok(), await sharedEditResponse.text()).toBe(true);
  await expect.poll(async () => (await (await page.request.get(root)).json()).data.qo_quote_set_count).toBe(2);

  const forbidden = await viewers[0].page.request.post('/api/record-share', {data: memberPayload});
  expect(forbidden.status()).toBe(403);
  const revokeAll = async () => {
    await dialog.getByTestId('record-share-select-all').check();
    await dialog.getByTestId('record-share-batch-remove').click();
    await dialog.getByTestId('record-share-batch-confirm-ok').click();
    await expect(dialog.getByTestId('record-share-empty')).toBeVisible();
  };
  await revokeAll();
  for(let i=0;i<3;i++) await probe(i,false);
  await refreshRevokedViewer(0);
  await dialog.getByRole('button',{name:'指定角色',exact:true}).click();
  const options = await page.request.get(`/api/record-share/roles?${shareParams}`);
  expect(options.ok()).toBe(true);
  const available = (await options.json()).data as Array<{pid:string;code:string;name:string}>;
  const procurement = available.find(role => role.code === 'qo_procurement');
  expect(procurement).toBeTruthy();
  await expect(dialog.getByRole('checkbox')).toHaveCount(4);
  for (const businessRole of ['销售', '采购', '工程', '商业审批人']) {
    await expect(dialog.getByRole('checkbox',{name:businessRole,exact:true})).toBeVisible();
  }
  await expect(dialog.getByText(/pe_qdp_release_manager|E2E No Business/)).toHaveCount(0);
  await dialog.getByRole('checkbox',{name:procurement!.name,exact:true}).check();
  await dialog.getByTestId('record-share-permission-read-update').click();
  await dialog.getByTestId('record-share-expiry-7d').click();
  const roleSaved = page.waitForResponse(response => response.url().endsWith('/api/record-share') && response.request().method()==='POST');
  await dialog.getByTestId('record-share-add-btn').click();
  const roleResponse = await roleSaved;
  expect(roleResponse.ok()).toBe(true);
  expect(roleResponse.request().postDataJSON()).toMatchObject({resourceCode:'qo_quote_common',recordPid:quote.quoteId,subjectType:'role',subjectPids:[procurement!.pid],permissionMask:'read,update'});
  await expect(dialog.locator('[data-testid^="record-share-row-"]')).toHaveCount(1);
  await expect(dialog.getByTestId('record-share-list')).toContainText(procurement!.name);
  await probe(2,true);
  // Sharing does not grant absent model-level CRUD privileges.
  expect((await viewers[2].page.request.put(root,{data:{qo_quote_customer:'Denied generic edit'}})).status()).toBe(403);
  const collaborator = viewers[2].page;
  await collaborator.goto(`/p/qo_quote_common/view/${quote.quoteId}#bom_price`);
  await collaborator.getByRole('button',{name:/修改套数/}).click();
  await collaborator.getByTestId('form-dialog-field-qo_quote_set_count').fill('3');
  const edited = collaborator.waitForResponse(response => response.url().includes('/api/meta/commands/execute/qo_quote_common:recompute_quantities') && response.request().method()==='POST');
  await collaborator.getByTestId('form-dialog-submit').click();
  const editResponse = await edited;
  expect(editResponse.ok(),await editResponse.text()).toBe(true);
  expect((await editResponse.json()).code).toBe('0');
  const completion = collaborator.locator('div.fixed.inset-0').filter({hasText:/修改套数\/价格系数已完成/}).first();
  await expect(completion).toBeVisible({timeout:60_000});
  await completion.getByRole('button',{name:'关闭',exact:true}).last().click();
  await expect.poll(async () => (await (await page.request.get(root)).json()).data.qo_quote_set_count).toBe(3);
  await probe(0,false);
  await dialog.getByRole('heading',{name:'添加协作成员',exact:true}).scrollIntoViewIfNeeded();
  await page.screenshot({path:testInfo.outputPath('quote-role-sharing.png'), fullPage:true});
  await dialog.getByTestId('record-share-dialog-close').click();
  await page.reload();
  await page.getByTestId('ab:detail:qo_quote_common:share-btn').click();
  await expect(dialog.getByTestId('record-share-list')).toContainText(procurement!.name);
  await dialog.locator('[data-testid^="record-share-remove-"]').click();
  await expect(dialog.getByTestId('record-share-empty')).toBeVisible();
  await probe(2,false);
  await refreshRevokedViewer(2);
  for(const viewer of viewers) await viewer.context.close();
});
