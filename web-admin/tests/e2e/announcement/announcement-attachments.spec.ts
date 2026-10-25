import { test, expect, type Page } from '../../fixtures';
import type { APIResponse, Download } from '@playwright/test';
import { randomUUID, createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { Client } from 'pg';
import { PG_CONN } from '../../helpers/environments';
import { authenticateBffSession } from '../../helpers/bff-auth-session';

test.use({ locale: 'zh-CN' });
async function successful(response: Pick<APIResponse, 'status' | 'json'>) {
  expect(response.status()).toBe(200);
  const body=await response.json();expect(String(body.code)).toBe('0');return body.data;
}
async function enter(page: Page, reader=false) {
  await page.goto('/home',{waitUntil:'domcontentloaded'});
  const nav=page.locator('nav');const link=nav.locator(`a[href="/p/${reader ? 'announcement_reader' : 'ab_announcement'}"]`);
  if(!await link.isVisible()) await nav.getByText('公告管理',{exact:true}).click();
  await link.click();
}

test('native upload freezes retained bytes and an ordinary recipient downloads through the controlled version link',async({page,browser},info)=>{
  test.setTimeout(90_000);
  const database=process.env.PGDATABASE||process.env.PG_DB;
  expect(database,'An explicit owned database is required').toBe('auraboot_1');
  const db=new Client(PG_CONN);await db.connect();
  expect((await db.query('SELECT current_database() AS name')).rows[0].name).toBe(database);
  const suffix=randomUUID();const title=`Retained attachment ${suffix}`;
  const fileName='公告附件-原始版本.txt';const bytes=Buffer.from('Frozen announcement attachment\n政策附件原始字节\n','utf8');
  let recordPid:string|undefined,version:string|undefined,tenant:number|undefined;
  const contexts:Awaited<ReturnType<typeof browser.newContext>>[]=[];
  try {
    const actor=(await successful(await page.request.get('/api/auth/me'))).user;tenant=actor.tenantId;
    const roleCode=`attachment_reader_${suffix.replaceAll('-','').slice(0,12)}`;
    await successful(await page.request.post('/api/roles',{data:{code:roleCode,name:'Attachment recipient',type:'custom',status:'active',scopeType:'tenant',defaultDataScopeType:'self'}}));
    const identities=[];
    for(const kind of ['recipient','outsider']) {
      const email=`att-${kind[0]}-${suffix.replaceAll('-','').slice(0,16)}@example.com`;
      const user=await successful(await page.request.post('/api/admin/users',{data:{email,displayName:`Attachment ${kind} ${suffix.slice(0,8)}`,initialPassword:'Test2026x',roleCodes:[roleCode],sendInviteEmail:false}}));
      identities.push({email,userPid:user.userPid,name:`Attachment ${kind} ${suffix.slice(0,8)}`});
    }
    await enter(page);
    const readingLimits=page.waitForResponse(response=>response.request().method()==='GET'&&new URL(response.url()).pathname==='/api/file/limits');
    await page.getByRole('button',{name:'新建公告',exact:true}).click();
    const limits=await successful(await readingLimits);
    expect(limits.maxFileBytes).toBeGreaterThan(0);
    await expect(page.getByText(`当前单文件上限：${limits.maxFileBytes/(1024*1024)} MB`,{exact:true})).toBeVisible();
    await page.getByTestId('form-field-title').locator('input').fill(title);
    const editor=page.getByTestId('form-field-content').getByRole('textbox');
    await editor.fill('Attachment review text.');await editor.press('ControlOrMeta+A');
    await page.getByRole('button',{name:'加粗',exact:true}).click();
    await expect(page.getByTestId('attachment-file-input')).toBeEnabled();
    const uploaded=page.waitForResponse(response=>response.request().method()==='POST'&&new URL(response.url()).pathname==='/api/file/upload');
    await page.getByTestId('attachment-file-input').setInputFiles({name:fileName,mimeType:'text/plain',buffer:bytes});
    const file=await successful(await uploaded);expect(file.fileId).toEqual(expect.any(String));
    await expect(page.getByText(fileName,{exact:true})).toBeVisible();
    await page.getByText(fileName,{exact:true}).scrollIntoViewIfNeeded();await expect(page.getByText(fileName,{exact:true})).toBeInViewport();
    await page.screenshot({path:info.outputPath('attachment-uploaded-draft.png'),fullPage:true,animations:'disabled'});
    const creating=page.waitForResponse(response=>response.request().method()==='POST'&&response.url().endsWith('/api/meta/commands/execute/announcement:create_announcement'));
    await page.getByRole('button',{name:'保存',exact:true}).click();
    const createResponse=await creating;
    expect(createResponse.request().postDataJSON().payload.content).toBe('<p><strong>Attachment review text.</strong></p>');
    expect(JSON.parse(createResponse.request().postDataJSON().payload.attachments)).toEqual([
      expect.objectContaining({fileId:file.fileId,name:fileName,size:bytes.length}),
    ]);
    const created=await successful(createResponse);recordPid=created.data.pid;
    await enter(page);
    await page.getByTestId('filters-toggle').click();
    await page.getByTestId('search-area').getByPlaceholder('例如：周末系统维护',{exact:true}).fill(title);
    await page.getByTestId('filter-search').click();
    const row=page.locator('table tbody tr').filter({hasText:title});await expect(row).toHaveCount(1);
    await row.getByTestId('row-action-more').click();
    await page.getByTestId('row-action-dropdown').getByRole('menuitem',{name:'通知设置',exact:true}).click();
    const settings=page.getByRole('dialog');
    await settings.getByTestId('form-dialog-field-audience_users').getByRole('searchbox').fill(identities[0].name);
    await settings.getByTestId('form-dialog-field-audience_users').getByRole('checkbox',{name:identities[0].name,exact:true}).check();
    await settings.getByLabel('要求确认已知悉',{exact:true}).check();
    await settings.getByLabel('免审原因',{exact:true}).fill('Authorized retained attachment acceptance.');
    const saving=page.waitForResponse(response=>response.request().method()==='POST'&&response.url().endsWith('/api/meta/commands/execute/announcement:update_announcement'));
    await settings.getByTestId('form-dialog-submit').click();const configured=await successful(await saving);await expect(settings).toBeHidden();
    // Exercise hostile stored input through the real protected API, independently of the native formatting editor.
    await successful(await page.request.post('/api/meta/commands/execute/announcement:update_announcement',{data:{
      targetRecordPid:recordPid,expectedVersion:configured.data.row_version,clientRequestId:randomUUID(),payload:{
        content:'<p>Attachment review text.</p><img src="/android-chrome-192x192.png?announcement-body=1" width="32" height="32" alt="Policy illustration" onerror="window.__announcementXss=7"><script>window.__announcementXss=9</script>',
      },
    }}));
    await page.reload({waitUntil:'domcontentloaded'});await expect(row).toHaveCount(1);
    await row.getByTestId('row-action-more').click();
    await page.getByTestId('row-action-dropdown').getByRole('menuitem',{name:'免审发布',exact:true}).click();
    const publishing=page.waitForResponse(response=>response.request().method()==='POST'&&response.url().endsWith('/api/meta/commands/execute/announcement:publish'));
    await page.getByTestId('confirm-ok').click();const accepted=await successful(await publishing);version=accepted.data.versionPid;
    await expect.poll(async()=>(await db.query('SELECT status FROM ab_announcement WHERE tenant_id=$1 AND pid=$2',[tenant,recordPid])).rows[0]?.status,{timeout:20_000}).toBe('published');
    const frozen=(await db.query('SELECT snapshot::text,snapshot_hash FROM ab_announcement_version WHERE tenant_id=$1 AND pid=$2',[tenant,version])).rows[0];
    expect((await db.query('SELECT retention_locked,deleted_flag FROM ab_file WHERE tenant_id=$1 AND pid=$2',[tenant,file.fileId])).rows[0]).toEqual({retention_locked:true,deleted_flag:false});
    const deletion=await page.request.delete(`/api/file/${file.fileId}`);
    expect(deletion.status()).toBe(422);expect(await deletion.text()).toContain('retention locked');
    const baseURL=new URL(page.url()).origin;
    for(const identity of identities) {
      const context=await browser.newContext({baseURL,locale:'zh-CN',viewport:{width:1440,height:1000}});contexts.push(context);
      await authenticateBffSession(context.request,baseURL,identity.email,'Test2026x');
    }
    expect((await contexts[0].request.get(`/api/file/${file.fileId}`)).status()).toBe(403);
    const path=`/api/announcements/versions/${version}/attachments/${file.fileId}`;
    const direct=await contexts[0].request.get(path);expect(direct.status()).toBe(200);
    expect(await direct.body()).toEqual(bytes);
    expect(direct.headers()['cache-control']).toBe('private, no-store');expect(direct.headers()['x-content-type-options']).toBe('nosniff');
    const unread=(await db.query('SELECT first_read_at,acknowledged_at FROM ab_announcement_recipient WHERE tenant_id=$1 AND version_pid=$2 AND user_pid=$3',[tenant,version,identities[0].userPid])).rows[0];
    expect(unread).toEqual({first_read_at:null,acknowledged_at:null});
    const denied=await contexts[1].request.get(path);expect(denied.status()).toBe(422);
    expect(await denied.text()).not.toContain(fileName);
    expect((await contexts[0].request.get(`/api/announcements/versions/${version}/attachments/guessed-unrelated-file`)).status()).toBe(403);
    const reader=await contexts[0].newPage();
    await reader.addInitScript(()=>{(window as any).__announcementXss=0;});
    await reader.route('**/android-chrome-192x192.png?announcement-body=1',route=>route.abort('failed'));
    await enter(reader,true);
    const received=reader.locator('table tbody tr').filter({hasText:title});await expect(received).toHaveCount(1);
    await received.getByRole('button',{name:'查看公告',exact:true}).click();
    await expect(reader).toHaveURL(new RegExp(`/p/announcement_reader/view/${version}$`));
    const link=reader.getByRole('link',{name:fileName,exact:false});
    await expect(link).toHaveAttribute('href',path);await expect(link).toContainText(fileName);
    await expect(reader.getByText('Attachment review text.',{exact:true})).toBeVisible();
    await expect(reader.getByRole('alert').filter({hasText:'正文图片未能完整加载'})).toBeVisible();
    await expect(reader.getByRole('button',{name:'确认已知悉',exact:true})).toBeDisabled();
    expect(await reader.evaluate(()=>(window as any).__announcementXss)).toBe(0);
    await expect(reader.locator('img[onerror],a[href^="javascript:"]')).toHaveCount(0);
    let actual:Download|undefined;
    const capture=(download:Download)=>{actual=download;};
    reader.on('download',capture);
    contexts[0].on('page',popup=>popup.on('download',capture));
    await link.click();
    await expect.poll(()=>actual?.suggestedFilename(),{timeout:15_000}).toBe(fileName);
    expect(actual).toBeDefined();
    const saved=info.outputPath('retained-attachment.txt');await actual!.saveAs(saved);expect(readFileSync(saved)).toEqual(bytes);
    const beforeRecovery=(await db.query('SELECT first_read_at,acknowledged_at FROM ab_announcement_recipient WHERE tenant_id=$1 AND version_pid=$2 AND user_pid=$3',[tenant,version,identities[0].userPid])).rows[0];
    expect(beforeRecovery).toEqual({first_read_at:null,acknowledged_at:null});
    await reader.screenshot({path:info.outputPath('recipient-body-failed-read-blocked.png'),fullPage:true,animations:'disabled'});
    await reader.unroute('**/android-chrome-192x192.png?announcement-body=1');
    const recording=reader.waitForResponse(response=>response.request().method()==='POST'&&new URL(response.url()).pathname===`/api/announcements/versions/${version}/read`);
    await reader.getByRole('button',{name:'重试',exact:true}).click();
    const recorded=await recording;await successful(recorded);
    expect(recorded.request().postDataJSON()).toEqual({snapshotHash:frozen.snapshot_hash});
    await expect(reader.getByRole('button',{name:'确认已知悉',exact:true})).toBeEnabled();
    const firstRead=(await db.query('SELECT first_read_at FROM ab_announcement_recipient WHERE tenant_id=$1 AND version_pid=$2 AND user_pid=$3',[tenant,version,identities[0].userPid])).rows[0].first_read_at;
    await reader.reload({waitUntil:'domcontentloaded'});
    await expect(link).toHaveAttribute('href',path);await expect(reader.getByRole('button',{name:'确认已知悉',exact:true})).toBeEnabled();
    const fact=(await db.query('SELECT first_read_at,acknowledged_at FROM ab_announcement_recipient WHERE tenant_id=$1 AND version_pid=$2 AND user_pid=$3',[tenant,version,identities[0].userPid])).rows[0];
    expect(fact.first_read_at).toEqual(firstRead);expect(firstRead).toBeTruthy();expect(fact.acknowledged_at).toBeNull();
    const contentBottom=await reader.getByTestId('form-field-attachments').boundingBox();
    const acknowledgeBounds=await reader.getByRole('button',{name:'确认已知悉',exact:true}).boundingBox();
    expect(contentBottom).not.toBeNull();expect(acknowledgeBounds).not.toBeNull();
    expect(acknowledgeBounds!.y).toBeGreaterThanOrEqual(contentBottom!.y+contentBottom!.height);
    expect((await db.query('SELECT snapshot::text,snapshot_hash FROM ab_announcement_version WHERE tenant_id=$1 AND pid=$2',[tenant,version])).rows[0]).toEqual(frozen);
    await reader.screenshot({path:info.outputPath('recipient-frozen-attachment.png'),fullPage:true,animations:'disabled'});
    await reader.setViewportSize({width:390,height:844});
    // Network faults preserve real backend facts and recover through the same header controls.
    const notificationRead=async()=>(await db.query(`SELECT n.is_read FROM ab_notification n JOIN ab_user u ON u.id=n.user_id
      WHERE n.tenant_id=$1 AND n.source_type='announcement' AND n.source_id=$2 AND u.pid=$3`,[tenant,version,identities[0].userPid])).rows;
    await expect.poll(async()=>(await notificationRead()).length,{timeout:20_000}).toBe(1);
    expect(await notificationRead()).toEqual([{is_read:false}]);
    await reader.route(url=>url.pathname==='/api/notifications',route=>route.abort('failed'),{times:1});
    await reader.getByTestId('notification-bell').click();
    const bell=reader.getByTestId('notification-dropdown-panel');
    await expect(bell.getByRole('alert')).toContainText('无法加载通知');
    await reader.screenshot({path:info.outputPath('recipient-narrow-bell-load-error.png'),fullPage:true,animations:'disabled'});
    const bellLoaded=reader.waitForResponse(response=>response.request().method()==='GET'&&new URL(response.url()).pathname==='/api/notifications');
    await bell.getByRole('button',{name:'重试',exact:true}).click();
    expect((await successful(await bellLoaded)).records).toEqual(expect.arrayContaining([expect.objectContaining({sourceId:version})]));
    await expect(bell.getByTestId('notification-item').filter({hasText:title})).toBeVisible();
    await reader.route('**/api/notifications/read-all',route=>route.abort('failed'),{times:1});
    await bell.getByTestId('mark-all-read-btn').click();
    await expect(bell.getByRole('alert')).toContainText('未能确认全部已读');
    expect(await notificationRead()).toEqual([{is_read:false}]);
    const bellBounds=await bell.boundingBox();expect(bellBounds!.x).toBeGreaterThanOrEqual(0);
    expect(bellBounds!.x+bellBounds!.width).toBeLessThanOrEqual(390);
    await reader.screenshot({path:info.outputPath('recipient-narrow-bell-write-error.png'),fullPage:true,animations:'disabled'});
    const markingNotifications=reader.waitForResponse(response=>response.request().method()==='PUT'&&new URL(response.url()).pathname==='/api/notifications/read-all');
    await bell.getByTestId('mark-all-read-btn').click();await successful(await markingNotifications);
    await expect.poll(notificationRead).toEqual([{is_read:true}]);
    await reader.keyboard.press('Escape');await expect(bell).toBeHidden();
    await expect(reader.getByTestId('notification-bell')).toBeFocused();
    await reader.route(url=>url.pathname==='/api/inbox',route=>route.abort('failed'),{times:1});
    await reader.getByTestId('inbox-badge').click();
    const inbox=reader.getByTestId('inbox-dropdown');await expect(inbox.getByRole('alert')).toBeVisible();
    const inboxLoaded=reader.waitForResponse(response=>response.request().method()==='GET'&&new URL(response.url()).pathname==='/api/inbox');
    await inbox.getByRole('button',{name:'重试',exact:true}).click();
    const pending=(await successful(await inboxLoaded)).records.find((item:any)=>item.sourceId===version);
    expect(pending).toMatchObject({itemType:'announcement_ack',status:'pending'});
    await expect(inbox.getByTestId(`inbox-item-${pending.id}`)).toBeVisible();
    const inboxBounds=await inbox.boundingBox();expect(inboxBounds!.x).toBeGreaterThanOrEqual(0);
    expect(inboxBounds!.x+inboxBounds!.width).toBeLessThanOrEqual(390);
    await reader.screenshot({path:info.outputPath('recipient-narrow-inbox-pending.png'),fullPage:true,animations:'disabled'});
    await reader.keyboard.press('Escape');await expect(inbox).toBeHidden();await expect(reader.getByTestId('inbox-badge')).toBeFocused();
    expect((await db.query('SELECT acknowledged_at FROM ab_announcement_recipient WHERE tenant_id=$1 AND version_pid=$2 AND user_pid=$3',[tenant,version,identities[0].userPid])).rows[0].acknowledged_at).toBeNull();
    const acknowledge=reader.getByRole('button',{name:'确认已知悉',exact:true});
    await expect(acknowledge).toBeVisible();
    expect(await reader.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
    await link.scrollIntoViewIfNeeded();await expect(link).toBeInViewport();await expect(acknowledge).toBeInViewport();
    await reader.screenshot({path:info.outputPath('recipient-narrow-attachment.png'),fullPage:true,animations:'disabled'});
    await acknowledge.focus();await reader.keyboard.press('Enter');
    await expect(reader.getByTestId('confirm-dialog')).toBeVisible();
    await reader.screenshot({path:info.outputPath('recipient-narrow-confirmation.png'),fullPage:true,animations:'disabled'});
    await reader.keyboard.press('Escape');
    await expect(reader.getByTestId('confirm-dialog')).toBeHidden();await expect(acknowledge).toBeFocused();
    expect((await db.query('SELECT acknowledged_at FROM ab_announcement_recipient WHERE tenant_id=$1 AND version_pid=$2 AND user_pid=$3',[tenant,version,identities[0].userPid])).rows[0].acknowledged_at).toBeNull();
    writeFileSync(info.outputPath('attachment-validation.json'),JSON.stringify({fileName,byteCount:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex'),retentionDeletionDenied:true,ordinaryRecipientDownload:true,outsiderDenied:true,downloadDidNotMarkRead:true,acknowledgementRemainsIndependent:true,xssDidNotExecute:true},null,2));
  } finally {
    try {
      for(const context of contexts) await context.close();
      if(recordPid&&tenant) {
        const root=(await db.query('SELECT status,row_version FROM ab_announcement WHERE tenant_id=$1 AND pid=$2',[tenant,recordPid])).rows[0];
        const command=root?.status==='published' ? 'announcement:archive' : ['unpublished','scheduled'].includes(root?.status) ? 'announcement:cancel_schedule' : null;
        if(command) await successful(await page.request.post(`/api/meta/commands/execute/${command}`,{data:{targetRecordPid:recordPid,expectedVersion:root.row_version,clientRequestId:randomUUID(),payload:{}}}));
      }
    } finally {await db.end();}
  }
});
