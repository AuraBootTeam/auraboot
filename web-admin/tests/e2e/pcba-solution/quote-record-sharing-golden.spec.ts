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
  const viewers = [];
  for (const user of recipients) viewers.push(await openQuoteRolePage(browser, user));
  const root = `/api/dynamic/qo_quote_common/${quote.quoteId}`;
  const shareParams = `resourceCode=qo_quote_common&recordPid=${quote.quoteId}`;
  const probe = async (index: number, allowed: boolean) => {
    const client = viewers[index].page;
    expect((await client.request.get(root)).status()).toBe(allowed ? 200 : 403);
    for (const query of ['qo_quote_bom_price_metrics', 'qo_quote_process_fee_unassigned_facts']) {
      const response = await client.request.post(`/api/meta/named-queries/${query}/execute`, {
        data: { parameters: { quoteId: quote.quoteId } },
      });
      // Record sharing does not grant the process-fee capability missing from sales roles.
      const canReadQuery = allowed && (query !== 'qo_quote_process_fee_unassigned_facts'
        || recipients[index].roleCodes.includes('qo_procurement'));
      expect(response.status()).toBe(canReadQuery ? 200 : 403);
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
  expect(memberPayload.permissionMask).toBe('read');
  await expect(dialog.locator('[data-testid^="record-share-row-"]')).toHaveCount(2);
  for(let i=0;i<2;i++) await probe(i,true);
  await probe(2,false);
  await viewers[0].page.goto(`/p/qo_quote_common/view/${quote.quoteId}#bom_price`);
  await expect(viewers[0].page.getByRole('tab',{name:'BOM价格计算',exact:true})).toBeVisible();
  await expect(viewers[0].page.getByTestId('ab:detail:qo_quote_common:share-btn')).toHaveCount(0);
  await viewers[0].page.getByRole('tab',{name:'BOM价格计算',exact:true}).click();
  await expect(viewers[0].page.getByTestId(`table-row-${quote.lineId}`)).toContainText(quote.mpn);
  await expect(viewers[0].page.getByTestId(`table-row-${quote.lineId}`)).toContainText(/1\.1111|1\.111|1\.11/);
  expect((await viewers[0].page.request.put(root,{data:{qo_quote_customer:'Denied shared edit'}})).status()).toBe(403);
  const reader = viewers[0].page;
  await reader.getByRole('button',{name:/修改套数/}).click();
  await reader.getByTestId('form-dialog-field-qo_quote_set_count').fill('2');
  const deniedEdit = reader.waitForResponse(response => response.url().includes('/api/meta/commands/execute/qo_quote_common:recompute_quantities') && response.request().method()==='POST');
  await reader.getByTestId('form-dialog-submit').click();
  expect((await deniedEdit).status()).toBe(403);
  expect((await (await page.request.get(root)).json()).data.qo_quote_set_count).toBe(1);

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
  await dialog.getByRole('button',{name:'指定角色',exact:true}).click();
  const options = await page.request.get(`/api/record-share/roles?${shareParams}`);
  expect(options.ok()).toBe(true);
  const available = (await options.json()).data as Array<{pid:string;name:string}>;
  // Match the visible business role; no technical role codes are shown in the picker.
  const procurement = available.find(role => /采购/.test(role.name));
  expect(procurement).toBeTruthy();
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
  await collaborator.getByTestId('form-dialog-field-qo_quote_set_count').fill('2');
  const edited = collaborator.waitForResponse(response => response.url().includes('/api/meta/commands/execute/qo_quote_common:recompute_quantities') && response.request().method()==='POST');
  await collaborator.getByTestId('form-dialog-submit').click();
  const editResponse = await edited;
  expect(editResponse.ok(),await editResponse.text()).toBe(true);
  expect((await editResponse.json()).code).toBe('0');
  const completion = collaborator.locator('div.fixed.inset-0').filter({hasText:/修改套数\/价格系数已完成/}).first();
  await expect(completion).toBeVisible({timeout:60_000});
  await completion.getByRole('button',{name:'关闭',exact:true}).last().click();
  await expect.poll(async () => (await (await page.request.get(root)).json()).data.qo_quote_set_count).toBe(2);
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
  for(const viewer of viewers) await viewer.context.close();
});
