import { loginDeliveryMember } from '../../helpers/delivery-session';
import { completeDeliveryApproval } from '../../helpers/delivery-approval';
import { requireRuntimeUrl } from '../../helpers/environments';
import {test, expect, type Page, type APIResponse, type Response} from '@playwright/test';
import {mkdir, writeFile} from 'node:fs/promises';
import {join} from 'node:path';

const evidence=process.env.AURA_EVIDENCE_DIR;
if(!evidence || !process.env.ADMIN_EMAIL || !process.env.ADMIN_PASSWORD || !process.env.AURA_CORE_PROJECT_ROOT) throw Error('Bind the owned scenario sources, evidence and administrator.');
const receipts:unknown[]=[];
let prefix='华辰制造';
let projectName='华辰制造 · 交付核验专项';
const createCommand='tdv:create_check';
const verificationCommand='tdv:verify_delivery';
test.beforeEach(async({page})=>{page.setDefaultTimeout(20000);});
async function data(response:APIResponse|Response) {
  expect(response.status()).toBe(200);const result=await response.json();expect(String(result.code)).toBe('0');return result.data;
}
async function login(page: Page, email = process.env.ADMIN_EMAIL!, tenant = process.env.AURA_ADMIN_TENANT_ID) {
  return (await loginDeliveryMember(page, email, tenant)).user;
}

async function menu(page:Page,name:string) {
  const link=page.getByRole('navigation').getByRole('link',{name,exact:true});
  const href=await link.getAttribute('href');expect(href).toBeTruthy();
  const pathname=new URL(href!,page.url()).pathname;await link.click();
  await expect(page).toHaveURL(url=>url.pathname===pathname);
}
async function shot(page:Page,id:string) {
  await mkdir(join(evidence!,'screenshots'),{recursive:true});
  await expect(page.getByTestId('toast-stack').getByRole('alert')).toHaveCount(0,{timeout:15000});
  await page.screenshot({path:join(evidence!,'screenshots',`${id}.png`),fullPage:true});
}
async function record(page:Page,model:string,pid:string) {
  const list=await data(await page.request.get(`/api/dynamic/${model}/list`,{params:{pageSize:500}}));
  const row=list.records.find((item:any)=>item.pid===pid);expect(row).toBeTruthy();return row;
}
async function open(page:Page,menuName:string,name:string) {
  await menu(page,menuName);const row=page.getByRole('row').filter({hasText:name});await expect(row).toHaveCount(1);
  await row.getByRole('button',{name:'查看',exact:true}).click();await expect(page).toHaveURL(/\/view\/[^/]+$/);await expect(page.getByRole('main')).toContainText(name);
}
async function upload(page:Page) {
  const sent=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/file/upload'&&r.request().method()==='POST');
  await page.getByTestId('form-field-tpm_ac_files').locator('input[type=file]').setInputFiles({name:'交付核验材料.txt',mimeType:'text/plain',buffer:Buffer.from('Evidence: migration, permission review and training completed.')});
  expect((await data(await sent)).fileId).toBeTruthy();await expect(page.getByTestId('form-field-tpm_ac_files')).toContainText('交付核验材料.txt');
}
async function createAcceptance(page:Page,user:any,name:string,amount:number,files:boolean) {
  await menu(page,'交付验收');await page.getByRole('button',{name:'新建验收',exact:true}).click();
  await page.locator('#tpm_ac_name').fill(name);await page.getByTestId('select-trigger-tpm_ac_project').click();
  await page.getByRole('option',{name:projectName,exact:true}).click();
  await page.locator('#tpm_ac_scope').fill('会员迁移、权限配置和培训完成，按交付清单核对。');await page.locator('#tpm_ac_amount').fill(String(amount));
  await page.getByTestId('select-trigger-tpm_ac_risk').click();await page.getByRole('option',{name:'普通',exact:true}).click();
  await page.getByTestId('user-select-trigger-tpm_ac_reviewer').click();
  await page.getByTestId(`user-select-option-tpm_ac_reviewer-${user.pid}`).click();if(files)await upload(page);
  const sent=page.waitForResponse(r=>r.url().includes('/execute/tpm:create_acceptance')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'保存验收草稿',exact:true}).click();const result=await data(await sent);const pid=result.data.recordPid;
  expect(pid).toBeTruthy();await expect(page).not.toHaveURL(/\/new$/);await open(page,'交付验收',name);return pid;
}
async function createCheck(page:Page,name:string,acceptanceName:string) {
  await menu(page,'交付核验');await page.getByRole('button',{name:'新建核验',exact:true}).click();
  await expect(page).toHaveURL(/\/p\/tdv_check\/new$/);
  await page.getByRole('button',{name:'保存核验记录',exact:true}).click();await expect(page).toHaveURL(/\/new$/);
  await expect(page.getByTestId('form-field-tdv_ck_name').locator('p.text-status-red')).toContainText('核验名称');
  await expect(page.getByTestId('form-field-tdv_ck_acceptance').locator('p.text-status-red')).toContainText('关联验收单');await shot(page,'v04');
  await page.locator('#tdv_ck_name').fill(name);await page.getByTestId('select-trigger-tdv_ck_acceptance').click();
  await page.getByRole('option',{name:acceptanceName,exact:true}).click();await shot(page,'v03');
  const sent=page.waitForResponse(r=>r.url().includes(`/execute/${createCommand}`)&&r.request().method()==='POST');
  await page.getByRole('button',{name:'保存核验记录',exact:true}).click();const response=await sent;
  expect(response.request().postDataJSON().payload.tdv_ck_acceptance).toBeTruthy();const pid=(await data(response)).data.recordPid;
  expect(pid).toBeTruthy();await expect(page).not.toHaveURL(/\/new$/);await open(page,'交付核验',name);await expect(page).toHaveURL(/\/p\/tdv_check\/view\/[^/]+$/);return pid;
}

// API setup isolates budget accounting from the neighboring approval/SLA profile.
// All acceptance/check creation and verification below still use real UI actions.
async function prepareProject(page:Page) {
  const projects=await data(await page.request.get('/api/dynamic/tpm_project/list',{params:{pageSize:500}}));
  const existing=projects.records.find((row:any)=>row.tpm_pj_name===projectName);if(existing)return existing.pid;
  const source=projects.records.find((row:any)=>row.tpm_pj_name==='华辰制造 · 供应链协同平台');expect(source).toBeTruthy();
  const created=await data(await page.request.post('/api/meta/commands/execute/tpm:create_project',{data:{operationType:'create',payload:{tpm_pj_name:projectName,tpm_pj_customer_id:source.tpm_pj_customer_id,tpm_pj_owner_user:source.tpm_pj_owner_user,tpm_pj_budget:128000}}}));
  expect(created.data.recordPid).toBeTruthy();return created.data.recordPid;
}
async function verify(page:Page,pid:string,expected:'passed'|'blocked') {
  const before=await data(await page.request.get('/api/dynamic/tdv_check/list',{params:{pageSize:500}}));
  const sent=page.waitForResponse(r=>r.url().includes(`/execute/${verificationCommand}`)&&r.request().method()==='POST');
  await page.getByTestId('toolbar-btn-verify_delivery').click();const response=await sent;
  expect(response.request().postDataJSON().targetRecordPid).toBe(pid);await data(response);
  await expect(page.getByRole('main')).toContainText(expected==='passed'?'核验通过':'需要补正');
  const saved=await record(page,'tdv_check',pid);expect(saved.tdv_ck_status).toBe(expected);expect(saved.tdv_ck_checked_at).toBeTruthy();
  const after=await data(await page.request.get('/api/dynamic/tdv_check/list',{params:{pageSize:500}}));expect(after.total).toBe(before.total);
  await page.reload();await expect(page.getByRole('main')).toContainText(expected==='passed'?'核验通过':'需要补正');
  receipts.push(saved);await writeFile(join(evidence!,'verification-receipts.json'),JSON.stringify(receipts,null,2));return saved;
}

test('optional PF4J verifier reads stored evidence and recomputes after actual form correction @critical',async({page})=>{
  const user=await login(page);
  const beforeProjects=await data(await page.request.get('/api/dynamic/tpm_project/list',{params:{pageSize:500}}));
  expect(beforeProjects.total).toBe(beforeProjects.records.length);
  const batches=beforeProjects.records.map((row:any)=>/^华辰制造 · 第(\d+)批交付核验 · 专项项目$/.exec(row.tpm_pj_name)?.[1]).filter(Boolean).map(Number);
  prefix=`华辰制造 · 第${Math.max(0,...batches)+1}批交付核验`;
  projectName=prefix+' · 专项项目';
  expect(beforeProjects.records.filter((row:any)=>row.tpm_pj_name===projectName)).toHaveLength(0);
  const projectPid=await prepareProject(page);await menu(page,'交付核验');
  await expect(page).toHaveURL(/\/p\/tdv_check$/);
  const emptyRead=page.waitForResponse(response=>{const url=new URL(response.url());return url.pathname==='/api/dynamic/tdv_check/list' && url.searchParams.get('keyword')===prefix;});
  await page.getByTestId('list-search-input').fill(prefix);
  await page.getByTestId('list-search-input').press('Enter');
  expect((await data(await emptyRead)).total).toBe(0);
  await expect(page.getByTestId('empty-state')).toBeVisible();
  await expect(page.getByRole('main')).toContainText('还没有交付核验');
  const afterProjects=await data(await page.request.get('/api/dynamic/tpm_project/list',{params:{pageSize:500}}));
  expect(afterProjects.total).toBe(beforeProjects.total+1);
  expect(afterProjects.records.map((row:any)=>row.pid).sort()).toEqual([...beforeProjects.records.map((row:any)=>row.pid),projectPid].sort());
  expect(afterProjects.records.find((row:any)=>row.pid===projectPid)).toMatchObject({tpm_pj_name:projectName,tpm_pj_budget:128000});
  expect((await data(await page.request.get('/api/dynamic/tpm_acceptance/list',{params:{pageSize:500}}))).records.filter((row:any)=>row.tpm_ac_project===projectPid)).toHaveLength(0);
  expect((await data(await page.request.get('/api/dynamic/tdv_check/list',{params:{keyword:prefix}}))).total).toBe(0);await shot(page,'v01');
  const name=prefix+' · 补齐材料';const acceptance=await createAcceptance(page,user,name,68000,false);
  const pid=await createCheck(page,name,name);let result=await verify(page,pid,'blocked');
  expect(result.tdv_ck_materials).toBe('blocked');expect(result.tdv_ck_passed).toBe(3);
  await expect(page.getByRole('main')).toContainText('请上传交付材料');
  await expect(page.getByTestId('object-section-source')).not.toContainText(/\d{4}-\d{2}-\d{2}T/);
  await expect(page.getByTestId('object-section-source')).toContainText(/\d{4}年\d{2}月\d{2}日/);
  await expect(page.getByTestId('object-section-budget')).toContainText('128,000.00');
  await expect(page.getByTestId('activity-timeline')).not.toContainText('客户互动时间线');await shot(page,'v05');
  await open(page,'交付验收',name);await page.getByTestId('toolbar-btn-edit').click();await upload(page);
  const updated=page.waitForResponse(r=>r.url().includes('/execute/tpm:update_acceptance')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'保存验收草稿',exact:true}).click();await data(await updated);
  await expect(page).not.toHaveURL(/\/edit\//);await open(page,'交付核验',name);result=await verify(page,pid,'passed');expect(result.tdv_ck_passed).toBe(4);
  expect(Number(result.tdv_ck_amount)).toBe(68000);expect((await record(page,'tpm_acceptance',acceptance)).tpm_ac_status).toBe('draft');await shot(page,'v06');
  await verify(page,pid,'passed');
  await open(page,'交付验收',name);
  await page.getByRole('tab',{name:'核验结果',exact:true}).click();
  await expect(page.getByRole('row').filter({hasText:name})).toHaveCount(1);
  await expect(page.getByRole('row').filter({hasText:name})).toContainText('核验通过');
  await page.reload();
  await expect(page.getByRole('tab',{name:'核验结果',exact:true})).toHaveAttribute('aria-selected','true');
  const persistedRow=page.getByTestId('subtable-viewer').getByRole('row').filter({hasText:name});
  await expect(persistedRow).toBeVisible();
  await expect(persistedRow).toContainText('核验通过');
  await shot(page,'v06-acceptance-tab');
  const activities=await data(await page.request.get('/api/activities',{params:{objectModel:'tdv_check',objectRecord:pid,limit:100}}));
  expect(activities.length).toBeGreaterThan(0);await writeFile(join(evidence!,'verification-activity.json'),JSON.stringify(activities,null,2));
  await menu(page,'交付核验');await expect(page.getByRole('row').filter({hasText:name})).toContainText('核验通过');
  await page.getByTestId('tab-passed').click();await expect(page.getByRole('row').filter({hasText:name})).toHaveCount(1);
  for(const tab of ['pending','blocked']) {await page.getByTestId(`tab-${tab}`).click();await expect(page.getByRole('row').filter({hasText:name})).toHaveCount(0);}
  await page.getByTestId('tab-all').click();await expect(page.getByRole('row').filter({hasText:name})).toHaveCount(1);
  await page.getByTestId('list-search-input').fill('不存在的交付核验');await expect(page.getByRole('row').filter({hasText:name})).toHaveCount(0);
  await page.getByTestId('list-search-input').fill('');await expect(page.getByRole('row').filter({hasText:name})).toHaveCount(1);await shot(page,'v02');
});

test('accepted delivery reduces budget and one cent over the exact allowance blocks verification @critical',async({page})=>{
  const user=await login(page);await prepareProject(page);const acceptedName=prefix+' · 前次已验收';const acceptedPid=await createAcceptance(page,user,acceptedName,60000,true);
  const submitted=page.waitForResponse(r=>r.url().includes('/execute/tpm:submit_delivery_acceptance')&&r.request().method()==='POST');
  await page.getByTestId('toolbar-btn-submit_acceptance').click();await data(await submitted);
  await expect(page.getByTestId('bpm-operations-approve')).toBeEnabled();
  await data(await completeDeliveryApproval(page,'approve','材料与交付范围一致，确认前阶段验收。'));
  await expect.poll(async()=>(await record(page,'tpm_acceptance',acceptedPid)).tpm_ac_status).toBe('accepted');
  const name=prefix+' · 预算边界';await createAcceptance(page,user,name,68000,true);const pid=await createCheck(page,name,name);
  let result=await verify(page,pid,'passed');expect(Number(result.tdv_ck_accepted)).toBe(60000);expect(Number(result.tdv_ck_available)).toBe(68000);
  await open(page,'交付验收',name);await page.getByTestId('toolbar-btn-edit').click();await page.locator('#tpm_ac_amount').fill('68000.01');
  const saved=page.waitForResponse(r=>r.url().includes('/execute/tpm:update_acceptance')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'保存验收草稿',exact:true}).click();await data(await saved);
  await expect(page).not.toHaveURL(/\/edit\//);await open(page,'交付核验',name);result=await verify(page,pid,'blocked');
  expect(result.tdv_ck_passed).toBe(3);expect(result.tdv_ck_budget_check).toBe('blocked');
  await expect(page.getByRole('main')).toContainText('超出可用预算');await shot(page,'v08');
});

test('a least privilege viewer cannot forge verification results or execute the handler @critical',async({page,browser})=>{
  const admin=await login(page);const list=await data(await page.request.get('/api/dynamic/tdv_check/list',{params:{pageSize:500}}));
  const target=list.records.find((r:any)=>r.tdv_ck_status==='passed');expect(target).toBeTruthy();
  const code=`verification_viewer_${Date.now()}`;
  const role=await data(await page.request.post('/api/roles',{data:{code,name:code,type:'custom',status:'active',scopeType:'tenant',defaultDataScopeType:'all'}}));
  const tree=await data(await page.request.get('/api/permissions/tree'));const ids=new Map<string,number>();
  const collect=(nodes:any[])=>nodes.forEach(n=>{ids.set(n.code,n.id);collect(n.children||[]);});collect(tree);
  const grants=['tdv.check.read','model.tdv_check.read','tpm.acceptance.read','model.tpm_acceptance.read'];
  await data(await page.request.put(`/api/permissions/matrix/${role.pid}/batch`,{data:grants.map(code=>{expect(ids.has(code),code).toBe(true);return{permissionId:ids.get(code),granted:true};})}));
  const email=`${code}@e2e.local`;await data(await page.request.post('/api/admin/users',{data:{email,displayName:'交付核验只读成员',initialPassword:process.env.ADMIN_PASSWORD,roleCodes:[code],sendInviteEmail:false}}));
  const context=await browser.newContext({baseURL:requireRuntimeUrl('base'),storageState:{cookies:[],origins:[]},locale:'zh-CN',viewport:{width:1440,height:1000}});
  try {
    const actor=await context.newPage();await login(actor,email,String(admin.tenantId));await open(actor,'交付核验',target.tdv_ck_name);
    await expect(actor.getByTestId('toolbar-btn-verify_delivery')).toHaveCount(0);await shot(actor,'v07');
    const denied=await actor.request.post('/api/meta/commands/execute/tdv:verify_delivery',{data:{operationType:'update',targetRecordPid:target.pid,payload:{tdv_ck_status:'passed'}}});
    expect(denied.status()).toBe(403);const unchanged=await record(page,'tdv_check',target.pid);
    expect(unchanged.tdv_ck_checked_at).toBe(target.tdv_ck_checked_at);expect(unchanged.tdv_ck_status).toBe(target.tdv_ck_status);
  } finally {await context.close();}
  const managerEmail=`${code}_manager@e2e.local`;
  await data(await page.request.post('/api/admin/users',{data:{email:managerEmail,displayName:'交付核验负责人',initialPassword:process.env.ADMIN_PASSWORD,roleCodes:['tdv_manager'],sendInviteEmail:false}}));
  const managerContext=await browser.newContext({baseURL:requireRuntimeUrl('base'),storageState:{cookies:[],origins:[]},locale:'zh-CN',viewport:{width:1440,height:1000}});
  try {
    const actor=await managerContext.newPage();await login(actor,managerEmail,String(admin.tenantId));
    const identity=await data(await actor.request.get('/api/auth/me'));expect(identity.permissions.roles.map((r:any)=>r.code)).not.toContain('tenant_admin');
    await open(actor,'交付核验',target.tdv_ck_name);await verify(actor,target.pid,'passed');await shot(actor,'v09');
    const blocked=list.records.find((r:any)=>r.tdv_ck_budget_check==='blocked');expect(blocked).toBeTruthy();
    const forged=await data(await actor.request.post(`/api/meta/commands/execute/${verificationCommand}`,{data:{operationType:'update',targetRecordPid:blocked.pid,payload:{tdv_ck_status:'passed',tdv_ck_available:999999}}}));
    expect(forged).toBeTruthy();const recomputed=await record(page,'tdv_check',blocked.pid);
    expect(recomputed.tdv_ck_status).toBe('blocked');expect(Number(recomputed.tdv_ck_available)).toBe(68000);
  } finally {await managerContext.close();}
});

test('another business space cannot read or execute an existing verification target @critical',async({page,browser})=>{
  const original=await login(page);const list=await data(await page.request.get('/api/dynamic/tdv_check/list',{params:{pageSize:500}}));
  const target=list.records[0];expect(target).toBeTruthy();
  const context=await browser.newContext({baseURL:requireRuntimeUrl('base'),storageState:{cookies:[],origins:[]},locale:'zh-CN',viewport:{width:1440,height:1000}});
  try {
    const actor=await context.newPage();await login(actor,process.env.ADMIN_EMAIL!,String(original.tenantId));
    const created=await data(await actor.request.post('/api/tenant-selection/process',{data:{action:'create',tenantName:prefix+'隔离空间',displayName:'核验隔离验证'}}));
    expect(created.tenantId).toBeTruthy();expect(String(created.tenantId)).not.toBe(String(original.tenantId));
    await actor.goto('/home');await actor.getByTestId('user-menu').getByRole('button').first().click();
    await expect(actor.getByTestId('user-dropdown')).toBeVisible();
    const switched=actor.waitForResponse(r=>new URL(r.url()).pathname==='/_action/switch-space'&&r.request().method()==='POST');
    await actor.getByTestId(`tenant-switch-${created.tenantId}`).click();expect((await switched).status()).toBe(302);
    await actor.waitForLoadState('load');
    const identity=await data(await actor.request.get('/api/auth/me'));expect(String(identity.user.tenantId)).toBe(String(created.tenantId));
    for(const plugin of ['project-management','project-delivery-verification']) {
      const response=await actor.request.post('/api/plugins/import/import-directory-sync',{data:{path:join(process.env.AURA_CORE_PROJECT_ROOT!,'plugins',plugin),conflictStrategy:'OVERWRITE',deferReferenceValidation:true},timeout:180000});
      expect(response.status()).toBe(200);expect((await response.json()).success).toBe(true);
    }
    const foreign=await data(await actor.request.get('/api/dynamic/tdv_check/list'));expect(foreign.total).toBe(0);
    await actor.reload();await menu(actor,'交付核验');await expect(actor.getByRole('main')).toContainText('还没有交付核验');await shot(actor,'v10');
    const read=await actor.request.get(`/api/dynamic/tdv_check/${target.pid}`);
    expect([400,403,404]).toContain(read.status());expect(await read.text()).not.toContain(target.tdv_ck_name);
    const denied=await actor.request.post('/api/meta/commands/execute/tdv:verify_delivery',{data:{operationType:'update',targetRecordPid:target.pid,payload:{}}});
    expect([400,403,404]).toContain(denied.status());expect(await denied.text()).not.toContain(target.tdv_ck_name);
    const unchanged=await record(page,'tdv_check',target.pid);expect(unchanged.tdv_ck_checked_at).toBe(target.tdv_ck_checked_at);
    await writeFile(join(evidence!,'verification-tenant-isolation.json'),JSON.stringify({originalTenant:original.tenantId,foreignTenant:created.tenantId,readStatus:read.status(),commandStatus:denied.status(),unchanged:true},null,2));
  } finally {await context.close();}
});
