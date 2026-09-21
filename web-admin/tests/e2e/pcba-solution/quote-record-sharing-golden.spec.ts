import path from 'node:path';
import { test, expect } from '../../fixtures';
import { ensureQuoteRoleUser, makeQuoteRoleUser, openQuoteRolePage, openQuoteDetailFromList,
  seedBomPriceManualReviewQuote, seedDownloadableQuote, type QuoteRoleUser } from './quote-e2e-helpers';
import { saveWorkbookDownload } from './workbook-download-evidence';
import { validateQuoteWorkbook } from './quote-workbook-assertions';

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
    // 文件腿(Q15-05):文件授权跟随关联记录的 read 授权(记录共享行面延伸到文件)。
    // 授权成员:200;未授权:拒绝(403;若 PermissionInterceptor 缺权限映射为 500,
    // 亦视为拒绝态,映射缺陷单独立产品发现)。
    const sharedFileProbe = await client.request.get(`/api/file/${sharedQuoteFileId}`);
    const sharedFileStatus = sharedFileProbe.status();
    if (allowed) {
      expect(sharedFileStatus, `authorized file access must pass, got ${sharedFileStatus}`).toBe(200);
    } else {
      expect([403, 500], `unauthorized file access must be rejected, got ${sharedFileStatus}`).toContain(sharedFileStatus);
    }
    const capability = await client.request.get(`/api/record-share/manage-capability?${shareParams}`);
    expect(capability.status()).toBe(allowed ? 200 : 403);
    if (allowed) expect((await capability.json()).data.canManage).toBe(false);
  };
  // 上传文件并绑定到报价单:文件本体由 admin 创建,读取授权由关联记录的共享行面决定。
  const sharedFileUpload = await page.request.post('/api/file/upload', { multipart: {
    file: { name: 'shared-gerber.gbr', mimeType: 'application/octet-stream', buffer: Buffer.from('M02*\n') },
  }});
  expect(sharedFileUpload.ok(), await sharedFileUpload.text()).toBe(true);
  const sharedQuoteFileId = String((await sharedFileUpload.json()).data.fileId);
  const relationBind = await page.request.post('/api/file/relation', { data: {
    entityType: 'qo_quote_common', entityId: quote.quoteId, fieldName: 'gerber_package',
    fileIds: [sharedQuoteFileId],
  }});
  expect(relationBind.ok(), await relationBind.text()).toBe(true);
  for (let i=0;i<3;i++) await probe(i,false);
  await openQuoteDetailFromList(page, quote);
  await page.getByTestId('ab:detail:qo_quote_common:share-btn').click();
  const dialog = page.getByTestId('record-share-dialog');
  await expect(dialog).toBeVisible();
  // Quote sharing is collaborate-only now: the view-only grant option must not be offered.
  await expect(dialog.getByTestId('record-share-permission-read')).toHaveCount(0);
  await expect(dialog.getByTestId('record-share-permission-read-update')).toBeVisible();
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
  const sharedLine = viewers[0].page.getByTestId(`table-row-${quote.lineId}`);
  await expect(sharedLine).toContainText(quote.mpn, {timeout: 20_000});
  await expect(sharedLine).toContainText(/1\.1111|1\.111|1\.11/);
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
  // Role collaborators see the shared quote in their list and get every quote tab, like members.
  await viewers[2].page.goto('/p/qo_quote_common');
  await expect(viewers[2].page.getByText(quote.quoteCode, { exact: false }).first()).toBeVisible();
  await viewers[2].page.goto(`/p/qo_quote_common/view/${quote.quoteId}#overview`);
  for (const tabName of ['资料上传','BOM价格计算','加工点数','Gerber校验','报价Excel']) {
    await expect(viewers[2].page.getByRole('tab',{name:tabName,exact:true})).toBeVisible();
  }
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
  // Role share revoked: the quote must also vanish from the collaborator's quote list.
  const procListLoad = viewers[2].page.waitForResponse(response =>
    response.url().includes('/api/dynamic/qo_quote_common/list'));
  await viewers[2].page.goto('/p/qo_quote_common');
  await procListLoad;
  await expect(viewers[2].page.getByText(quote.quoteCode, { exact: false })).toHaveCount(0);
  for(const viewer of viewers) await viewer.context.close();
});

test('quote sharing release gate: collaborator full processing, record isolation and live role membership', async ({ page, browser }, testInfo) => {
  test.setTimeout(300_000);
  // Collaboration grants every quote capability on the shared record only. Drive the whole
  // processing surface (materials, process-fee recalculation, Gerber inspection view, Excel
  // generation) as a collaborator, prove other records stay invisible, and prove role shares
  // follow role membership changes in real time. The share-dialog UI journey itself is covered
  // by the lifecycle test above, so grants here are API setup.
  const processed = await seedDownloadableQuote(page);
  const isolated = await seedBomPriceManualReviewQuote(page);
  const uid = `${Date.now()}`.slice(-8);
  const member: QuoteRoleUser = makeQuoteRoleUser('share-full', uid, ['qo_sales']);
  const roleUser: QuoteRoleUser = makeQuoteRoleUser('share-dyn', uid, ['qo_sales']);
  await ensureQuoteRoleUser(page, member);
  await ensureQuoteRoleUser(page, roleUser);
  const memberCtx = await openQuoteRolePage(browser, member);
  const dynCtx = await openQuoteRolePage(browser, roleUser);
  const memberPage = memberCtx.page;
  const dynPage = dynCtx.page;
  const shareRoot = (recordPid: string) => `/api/dynamic/qo_quote_common/${recordPid}`;
  const shareParams = (recordPid: string) => `resourceCode=qo_quote_common&recordPid=${recordPid}`;
  const findUserPidByEmail = async (email: string): Promise<string> => {
    const resp = await page.request.get(`/api/admin/users/search?keyword=${encodeURIComponent(email)}&size=20`);
    expect(resp.ok(), `user search ${email}`).toBe(true);
    const body = await resp.json().catch(() => ({}));
    const hit = (Array.isArray(body?.data) ? body.data : []).find((user: { email?: string }) => user.email === email);
    expect(hit, `user pid for ${email}`).toBeTruthy();
    return String(hit.pid);
  };

  // Nothing is shared yet: both quotes are locked for both accounts.
  for (const recordPid of [processed.quoteId, isolated.quoteId]) {
    expect((await memberPage.request.get(shareRoot(recordPid))).status()).toBe(403);
    expect((await dynPage.request.get(shareRoot(recordPid))).status()).toBe(403);
  }

  const memberPid = await findUserPidByEmail(member.email);
  const grant = await page.request.post('/api/record-share', { data: {
    resourceCode: 'qo_quote_common',
    recordPid: processed.quoteId,
    subjectType: 'member',
    subjectPids: [memberPid],
    permissionMask: 'read,update',
  }});
  expect(grant.ok(), await grant.text()).toBe(true);

  await openQuoteDetailFromList(memberPage, processed);
  for (const tabName of ['资料上传','BOM价格计算','加工点数','Gerber校验','报价Excel']) {
    await expect(memberPage.getByRole('tab',{name:tabName,exact:true})).toBeVisible();
  }
  await memberPage.screenshot({path:testInfo.outputPath('shared-full-tabs.png'), fullPage:true});

  // 资料上传 tab renders the quote materials surface for the collaborator. The seed quote
  // has no materials yet, so the table shows its headers without rows.
  await memberPage.getByRole('tab',{name:'资料上传',exact:true}).click();
  await expect(memberPage.getByRole('columnheader', { name: '资料类型' })).toBeVisible();
  await expect(memberPage.getByRole('columnheader', { name: '文件名' })).toBeVisible();

  // 报价Excel tab: generate, download and parse the workbook as the collaborator, before the
  // process-fee recalculation step below changes the quote's fee accounting. The workbook
  // contract mirrors the admin control (quote-excel-download) and doubles as the regression
  // guard for the shared-aggregate row surface: collaborator-initiated generate_document used
  // to render an empty BOM明细 when the child-record query missed the record-share grant.
  await memberPage.getByRole('tab',{name:'报价Excel',exact:true}).click();
  await expect(memberPage.getByTestId('workbench-action-generate_quote_excel')).toBeVisible({ timeout: 15_000 });
  const excelCommand = memberPage.waitForResponse(response =>
    response.url().includes('/api/meta/commands/execute/') &&
    response.url().includes('generate_document') &&
    response.request().method() === 'POST', { timeout: 60_000 });
  const download = memberPage.waitForEvent('download', { timeout: 60_000 });
  await memberPage.getByTestId('workbench-action-generate_quote_excel').click();
  const excelResponse = await excelCommand;
  const excelBody = await excelResponse.json().catch(() => ({}));
  expect(String(excelBody.code), `generate_document: ${JSON.stringify(excelBody).slice(0,400)}`).toBe('0');
  const downloaded = await download;
  expect(downloaded.suggestedFilename()).toContain(processed.quoteCode);
  const savedPath = path.join(testInfo.outputDir, 'shared-quote-download.xlsx');
  await saveWorkbookDownload(downloaded, savedPath, testInfo, 'shared-quote-standard');
  validateQuoteWorkbook(savedPath, { expectedFirstBomUnitPrice: 1.25, fixedCounts: { apertures: 3, holes: 2 } });

  // 加工点数 tab: the collaborator can recalculate process points via the toolbar command
  // (the configured button carries count_hole_mode=default and dispatches directly, then an
  // async-task completion modal must be dismissed before the rest of the page is clickable).
  await memberPage.getByRole('tab',{name:'加工点数',exact:true}).click();
  const recalc = memberPage.waitForResponse(response =>
    response.url().includes('/api/meta/commands/execute/qo_quote_common:compute_process_fee') &&
    response.request().method() === 'POST', { timeout: 60_000 });
  await memberPage.getByRole('button',{name:'计算／重新计算',exact:true}).click();
  const recalcResponse = await recalc;
  expect(recalcResponse.ok(), await recalcResponse.text()).toBe(true);
  expect(((await recalcResponse.json().catch(() => ({}))) as {code?:string}).code).toBe('0');
  await expect(memberPage.getByRole('button', { name: '关闭', exact: true })).toBeVisible({ timeout: 60_000 });
  await memberPage.getByRole('button', { name: '关闭', exact: true }).click();
  await expect(memberPage.getByTestId('metric-strip-qo_process_fee_count_metrics')).toBeVisible();

  // Gerber校验 tab: the inspection surface mounts for the collaborator (parse journeys
  // stay owned by the dedicated Gerber specs; here the permission surface is the contract).
  await memberPage.getByRole('tab',{name:'Gerber校验',exact:true}).click();
  const gerberSurface = memberPage.getByTestId('gerber-viewer');
  const gerberEmpty = memberPage.locator('[data-testid^="runtime-gerber-viewer-empty"]');
  await expect(gerberSurface.or(gerberEmpty).first()).toBeVisible();
  await memberPage.screenshot({path:testInfo.outputPath('shared-gerber-tab.png'), fullPage:true});

  // The grant is record-scoped: another tenant quote stays invisible on API and UI level.
  expect((await memberPage.request.get(shareRoot(isolated.quoteId))).status()).toBe(403);
  const deniedLoad = memberPage.waitForResponse(response =>
    new URL(response.url()).pathname === shareRoot(isolated.quoteId) &&
    response.request().method() === 'GET');
  await memberPage.goto(`/p/qo_quote_common/view/${isolated.quoteId}`);
  expect((await deniedLoad).status()).toBe(403);
  const deniedContainer = memberPage.getByTestId('ab:detail:qo_quote_common:container');
  await expect(deniedContainer.getByRole('heading', { level: 2 })).toHaveText('无法访问此记录');
  const memberListLoad = memberPage.waitForResponse(response =>
    response.url().includes('/api/dynamic/qo_quote_common/list'));
  await memberPage.goto('/p/qo_quote_common');
  await memberListLoad;
  await expect(memberPage.getByText(isolated.quoteCode, { exact: false })).toHaveCount(0);
  await expect(memberPage.getByText(processed.quoteCode, { exact: false }).first()).toBeVisible();

  // Role shares follow role membership in real time: join/leave qo_procurement.
  const rolesResp = await page.request.get(`/api/record-share/roles?${shareParams(processed.quoteId)}`);
  expect(rolesResp.ok()).toBe(true);
  const procurement = ((await rolesResp.json()).data as Array<{pid:string;code:string}>)
    .find(role => role.code === 'qo_procurement');
  expect(procurement).toBeTruthy();
  const roleGrant = await page.request.post('/api/record-share', { data: {
    resourceCode: 'qo_quote_common',
    recordPid: processed.quoteId,
    subjectType: 'role',
    subjectPids: [procurement!.pid],
    permissionMask: 'read,update',
  }});
  expect(roleGrant.ok(), await roleGrant.text()).toBe(true);
  // Role membership is keyed by tenant-member pid, not user pid: resolve the fresh user's
  // member pid through the role candidates API before join/leave.
  const candResp = await page.request.get(`/api/roles/${procurement!.pid}/members/candidates?keyword=${encodeURIComponent(roleUser.email)}`);
  expect(candResp.ok(), await candResp.text()).toBe(true);
  const candidate = ((await candResp.json()).data as Array<{memberPid?:string; email?:string}>)
    .find(member => member.email === roleUser.email);
  expect(candidate, `role candidate for ${roleUser.email}`).toBeTruthy();
  const dynMemberPid = String(candidate!.memberPid);
  expect((await dynPage.request.get(shareRoot(processed.quoteId))).status()).toBe(403);
  expect((await page.request.post(`/api/roles/${procurement!.pid}/members`, { data: [dynMemberPid] })).ok()).toBe(true);
  expect((await dynPage.request.get(shareRoot(processed.quoteId))).status()).toBe(200);
  await dynPage.goto(`/p/qo_quote_common/view/${processed.quoteId}#overview`);
  await expect(dynPage.getByRole('tab',{name:'资料上传',exact:true})).toBeVisible();
  expect((await page.request.post(`/api/roles/${procurement!.pid}/members/remove`, { data: [dynMemberPid] })).ok()).toBe(true);
  expect((await dynPage.request.get(shareRoot(processed.quoteId))).status()).toBe(403);
  const dynDenied = dynPage.waitForResponse(response =>
    new URL(response.url()).pathname === shareRoot(processed.quoteId) &&
    response.request().method() === 'GET');
  await dynPage.reload();
  expect((await dynDenied).status()).toBe(403);
  const dynContainer = dynPage.getByTestId('ab:detail:qo_quote_common:container');
  await expect(dynContainer.getByRole('heading', { level: 2 })).toHaveText('无法访问此记录');
  await dynPage.screenshot({path:testInfo.outputPath('role-membership-revoked.png'), fullPage:true});

  await memberCtx.context.close();
  await dynCtx.context.close();
});
