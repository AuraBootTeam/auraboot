import {test,expect,type Page} from '../../fixtures';
import {Client} from 'pg';
import {PG_CONN} from '../../helpers/environments';

test.use({locale:'zh-CN'});
test.describe.configure({mode:'serial'});
const endpoint='/api/meta/commands/execute/announcement:update_policy';
const success='公告政策已保存；已有公告的提醒时间保持不变。';
let db:Client;
test.beforeEach(async()=>{
  const expected=process.env.PGDATABASE||process.env.PG_DB;
  expect(expected,'Explicit owned database is required before policy UI writes').toMatch(/^[a-z][a-z0-9_]*$/);
  db=new Client(PG_CONN);await db.connect();
  expect((await db.query('SELECT current_database() AS name')).rows[0].name).toBe(expected);
});
test.afterEach(async()=>{await db?.end();});
async function openPolicy(page:Page) {
  await page.goto('/home',{waitUntil:'domcontentloaded'});
  const nav=page.locator('nav');await expect(nav).toHaveCount(1);
  const link=nav.locator('a[href="/p/announcement_policy/view/current"]');
  if(!await link.isVisible())await nav.getByText('公告管理',{exact:true}).click();
  await expect(link).toBeVisible();
  const loaded=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/announcements/policy'&&r.request().method()==='GET');
  await link.click();const response=await loaded;expect(response.status()).toBe(200);
  const body=await response.json();expect(body.code).toBe('0');
  await expect(page.getByText('当前政策',{exact:true})).toBeVisible();
  await expect(page.getByText('公告政策仅作用于当前租户。安全规则在执行时实时复查；提醒时刻、次数和免打扰窗口仅供新提交版本冻结使用，不追溯修改已有公告。',{exact:true})).toBeVisible();
  await expect(page.locator('main').getByRole('link',{name:'返回',exact:true})).toHaveCount(0);
  await expect(page.getByText('记录不存在',{exact:true})).toHaveCount(0);
  return body.data;
}
async function edit(page:Page) {
  await page.getByRole('button',{name:'编辑政策',exact:true}).click();
  const dialog=page.getByRole('dialog');await expect(dialog).toBeVisible();return dialog;
}
async function submit(page:Page,dialog:ReturnType<Page['getByRole']>,expectRefresh=true) {
  const saved=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname===endpoint);
  const refreshed=expectRefresh ? page.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname==='/api/announcements/policy') : null;
  await dialog.getByTestId('form-dialog-submit').click();const response=await saved;
  if(refreshed) {
    const refresh=await refreshed;expect(refresh.status()).toBe(200);expect((await refresh.json()).code).toBe('0');
    await expect(page.getByRole('button',{name:'编辑政策',exact:true})).toBeEnabled();
  }
  return response;
}
async function reloadPolicy(page:Page) {
  const loaded=page.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname==='/api/announcements/policy');
  await page.reload({waitUntil:'domcontentloaded'});const response=await loaded;expect(response.status()).toBe(200);
  const body=await response.json();expect(body.code).toBe('0');
  await expect(page.getByText('公告政策仅作用于当前租户。安全规则在执行时实时复查；提醒时刻、次数和免打扰窗口仅供新提交版本冻结使用，不追溯修改已有公告。',{exact:true})).toBeVisible();
  return body.data;
}
test('tenant policy opens from its real menu and cancelling creates no operation',async({page},info)=>{
  const before=await openPolicy(page);const writes:string[]=[];
  page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname===endpoint)writes.push(r.url());});
  await page.screenshot({path:info.outputPath('policy-current.png'),fullPage:true,animations:'disabled'});
  const dialog=await edit(page);
  await expect(dialog.getByTestId('form-dialog-field-reminderAt')).toHaveValue(before.reminderAt);
  await dialog.getByTestId('form-dialog-field-reminderAt').fill('10:30');
  await page.screenshot({path:info.outputPath('policy-edit-cancel.png'),fullPage:true,animations:'disabled'});
  await dialog.getByRole('button',{name:'取消',exact:true}).click();await expect(dialog).not.toBeVisible();
  expect(writes).toEqual([]);expect(await reloadPolicy(page)).toEqual(before);
});
test('policy save and explicit quiet-hour clear persist CAS and immutable audit without changing security generation',async({page},info)=>{
  test.setTimeout(60000);
  const before=await openPolicy(page);let dialog=await edit(page);
  const nextTime=before.reminderAt==='10:30' ? '11:15' : '10:30';
  await dialog.getByTestId('form-dialog-field-reminderAt').fill(nextTime);
  await dialog.getByTestId('form-dialog-field-quietStart').fill('22:00');
  await dialog.getByTestId('form-dialog-field-quietEnd').fill('08:00');
  const response=await submit(page,dialog);expect(response.status(),await response.text()).toBe(200);
  const wire=response.request().postDataJSON();expect(wire).not.toHaveProperty('targetRecordPid');
  expect(wire.clientRequestId).toMatch(/^[0-9a-f-]{36}$/);
  expect(wire.payload).toMatchObject({expectedPolicyRevision:before.revision,reminderAt:nextTime,quietStart:'22:00',quietEnd:'08:00'});
  await expect(page.getByText(success,{exact:true})).toBeVisible();
  const saved=await reloadPolicy(page);
  expect(saved).toMatchObject({revision:before.revision+1,securityRevision:before.securityRevision,reminderAt:nextTime,quietStart:'22:00',quietEnd:'08:00'});
  const audit=await db.query('SELECT previous_revision,resulting_revision,previous_policy,resulting_policy FROM ab_announcement_policy_audit WHERE operation_id=$1',[wire.clientRequestId]);
  expect(audit.rows).toHaveLength(1);expect(audit.rows[0]).toMatchObject({previous_revision:before.revision,resulting_revision:saved.revision,
    previous_policy:{reminderAt:before.reminderAt},resulting_policy:{reminderAt:nextTime,securityRevision:before.securityRevision}});
  await page.screenshot({path:info.outputPath('policy-saved-reloaded.png'),fullPage:true,animations:'disabled'});
  dialog=await edit(page);
  for(const [key,value] of [['reminderAt',before.reminderAt],['quietStart',''],['quietEnd','']])
    await dialog.getByTestId(`form-dialog-field-${key}`).fill(value);
  const restoredResponse=await submit(page,dialog);expect(restoredResponse.status(),await restoredResponse.text()).toBe(200);
  expect(restoredResponse.request().postDataJSON().clientRequestId).not.toBe(wire.clientRequestId);
  await expect(page.getByText(success,{exact:true})).toBeVisible();
  const restored=await reloadPolicy(page);
  expect(restored).toEqual({...before,quietStart:null,quietEnd:null,revision:before.revision+2});
  await page.screenshot({path:info.outputPath('policy-restored-explicit-clear.png'),fullPage:true,animations:'disabled'});
});
test('invalid reminder time is rejected visibly and cannot append policy audit',async({page},info)=>{
  const before=await openPolicy(page);const dialog=await edit(page);
  await dialog.getByTestId('form-dialog-field-reminderAt').fill('25:00');
  const response=await submit(page,dialog,false);expect(response.status()).toBe(422);
  const operation=response.request().postDataJSON().clientRequestId;
  await expect(page.getByTestId('ab:detail:ab_announcement:action-error').getByText('公告输入不符合要求。',{exact:true})).toBeVisible();
  expect((await db.query('SELECT count(*)::int AS count FROM ab_announcement_policy_audit WHERE operation_id=$1',[operation])).rows[0].count).toBe(0);
  await page.screenshot({path:info.outputPath('policy-invalid-time.png'),fullPage:true,animations:'disabled'});
  expect(await reloadPolicy(page)).toEqual(before);
});
