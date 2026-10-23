import {dashboardRow} from '../../helpers/delivery-dashboard';
import { loginDeliveryMember } from '../../helpers/delivery-session';
import { completeDeliveryApproval } from '../../helpers/delivery-approval';
import { requireRuntimeUrl } from '../../helpers/environments';
import {test,expect,type Page,type APIResponse,type Response} from '@playwright/test';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {execFileSync} from 'node:child_process';
import {navigateToMenuByClick} from '../helpers';

const root=process.env.AURA_ENTERPRISE_PROJECT_ROOT;
const evidence=process.env.AURA_EVIDENCE_DIR;
if(!root||!evidence||!process.env.ADMIN_EMAIL||!process.env.ADMIN_PASSWORD)throw Error('Bind the owned enterprise source, evidence and real local login.');
const templatePath=join(root,'plugins/project-delivery-operations/examples/delivery-retrospective.report-template.json');
async function data(r:APIResponse|Response){const b=await r.json();expect(r.status(),JSON.stringify(b)).toBe(200);expect(String(b.code)).toBe('0');return b.data;}
async function login(page: Page, email = process.env.ADMIN_EMAIL!, tenant = process.env.AURA_ADMIN_TENANT_ID) {
  return loginDeliveryMember(page, email, tenant);
}

async function shot(page:Page,id:string){await mkdir(join(evidence!,'screenshots'),{recursive:true});await page.screenshot({path:join(evidence!,'screenshots',`${id}.png`),fullPage:true});}
const compact=(s:string)=>s.normalize('NFKC').replace(/\s+/g,'');

async function menu(page:Page,name:string){await page.getByRole('navigation').getByRole('link',{name,exact:true}).click();}
async function openAcceptance(page:Page,name:string){await menu(page,'交付验收');await page.getByRole('row').filter({hasText:name}).getByRole('button',{name:/查看|详情/}).click();await expect(page.getByRole('main')).toContainText(name);}
async function createAcceptance(page:Page,user:any,name:string){
 await menu(page,'交付验收');await page.getByRole('button',{name:'新建验收',exact:true}).click();await page.locator('#tpm_ac_name').fill(name);await page.getByTestId('select-trigger-tpm_ac_project').click();await page.getByRole('option',{name:'华辰制造 · 供应链协同平台',exact:true}).click();
 await page.locator('#tpm_ac_scope').fill('完成供应商数据联调，核对交付清单、权限和验收材料。');await page.locator('#tpm_ac_amount').fill('18000');await page.getByTestId('select-trigger-tpm_ac_risk').click();await page.getByRole('option',{name:'高风险',exact:true}).click();await page.getByTestId('user-select-trigger-tpm_ac_reviewer').click();await page.getByTestId(`user-select-option-tpm_ac_reviewer-${user.pid}`).click();
 const upload=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/file/upload'&&r.request().method()==='POST');await page.getByTestId('form-field-tpm_ac_files').locator('input[type=file]').setInputFiles({name:'供应商联调验收清单.txt',mimeType:'text/plain',buffer:Buffer.from('Delivered: supplier integration, access review, training. Reviewed evidence attached.')});expect((await data(await upload)).fileId).toBeTruthy();
 const save=page.waitForResponse(r=>r.url().includes('/execute/tpm:create_acceptance')&&r.request().method()==='POST');await page.getByRole('button',{name:'保存验收草稿',exact:true}).click();const pid=(await data(await save)).data.recordPid;await expect(page).not.toHaveURL(/\/new$/);await openAcceptance(page,name);
 const submit=page.waitForResponse(r=>r.url().includes('/execute/tpm:submit_delivery_acceptance')&&r.request().method()==='POST');await page.getByTestId('toolbar-btn-submit_acceptance').click();await data(await submit);await expect(page.getByTestId('detail-persistent-header').getByTestId('bpm-panel').filter({has:page.getByTestId('bpm-section-operations')})).toHaveAttribute('data-state','ready');
 const rows=await data(await page.request.get('/api/dynamic/tpm_acceptance/list',{params:{pageSize:'500'}}));return rows.records.find((a:any)=>a.pid===pid);
}
async function prepareOperations(page:Page,user:any){
 const control=await data(await page.request.get('/api/dynamic/tpm_acceptance/list',{params:{pageSize:'500'}}));const example=control.records[0];expect(example).toBeTruthy();
 const tombstone=await data(await page.request.post('/api/meta/commands/execute/tpm:create_acceptance',{data:{operationType:'create',payload:{tpm_ac_name:'已撤销的统计对照',tpm_ac_project:example.tpm_ac_project,tpm_ac_scope:'Source exclusion control',tpm_ac_amount:1,tpm_ac_risk:'normal',tpm_ac_reviewer:user.pid}}}));await data(await page.request.delete(`/api/dynamic/tpm_acceptance/${tombstone.data.recordPid}`));
 const returned=await createAcceptance(page,user,'华辰制造 · 供应商联调补正');
 await data(await completeDeliveryApproval(page,'reject','缺少供应商对账差异说明，请补齐后重新提交。'));await page.getByRole('tab',{name:'审批记录',exact:true}).click();await expect(page.getByTestId('bpm-history-container')).toContainText('缺少供应商对账差异说明，请补齐后重新提交。');
 const configs=await data(await page.request.get('/api/bpm/sla-configs'));const config=configs.find((c:any)=>c.targetKey==='tpm_enhanced_review');expect(config).toBeTruthy();
 await data(await page.request.put(`/api/bpm/sla-configs/${config.pid}`,{data:{...config,deadlineMode:'FIXED',deadlineValue:'PT30S',ruleBinding:{...config.ruleBinding,enabled:false}}}));
 let pending:any;
 try{pending=await createAcceptance(page,user,'华辰制造 · 第二阶段交接审核');}finally{await data(await page.request.put(`/api/bpm/sla-configs/${config.pid}`,{data:config}));}
 await expect.poll(async()=>{const sla=await data(await page.request.get(`/api/bpm/monitor/instances/${pending.tpm_ac_process}/sla`));return sla[0]?.status;},{timeout:65000,intervals:[3000]}).toBe('overdue');
 const checks=await data(await page.request.get('/api/dynamic/tdv_check/list',{params:{pageSize:'500'}}));const passed=checks.records.find((c:any)=>c.tdv_ck_status==='passed');expect(passed).toBeTruthy();
 const acceptances=await data(await page.request.get('/api/dynamic/tpm_acceptance/list',{params:{pageSize:'500'}}));const source=acceptances.records.find((a:any)=>a.pid===passed.tdv_ck_acceptance);expect(source.tpm_ac_status).toBe('draft');
 await openAcceptance(page,source.tpm_ac_name);await page.getByTestId('toolbar-btn-edit').click();await page.locator('#tpm_ac_scope').fill('补充第二阶段交付范围：增加供应商对账核对；原核验快照需重新确认。');const update=page.waitForResponse(r=>r.url().includes('/execute/tpm:update_acceptance')&&r.request().method()==='POST');await page.getByRole('button',{name:'保存验收草稿',exact:true}).click();await data(await update);await expect(page).not.toHaveURL(/\/edit\//);
 await writeFile(join(evidence!,'operations-created-cases.json'),JSON.stringify({returned,pending,staleAcceptance:source.pid,checkPid:passed.pid},null,2));
}

test('OP delivery dashboard drills into real acceptances and its report exports the same approval SLA verification facts',async({page},info)=>{
 test.setTimeout(240000);page.setDefaultTimeout(20000);await mkdir(evidence!,{recursive:true});const identity=await login(page);await prepareOperations(page,identity.user);
 const acceptances=await data(await page.request.get('/api/dynamic/tpm_acceptance/list',{params:{pageNum:'1',pageSize:'500'}}));
 const checks=await data(await page.request.get('/api/dynamic/tdv_check/list',{params:{pageNum:'1',pageSize:'500'}}));
 expect(acceptances.records.length).toBeGreaterThan(0);expect(Number(acceptances.total)).toBe(acceptances.records.length);
 expect(checks.records.length).toBeGreaterThan(0);
 const audits=new Map<string,any[]>(); const sla:any[]=[];for(const a of acceptances.records){if(a.tpm_ac_process){const rows=await data(await page.request.get(`/api/bpm/monitor/instances/${a.tpm_ac_process}/sla`));sla.push(...rows);audits.set(a.pid,await data(await page.request.get(`/api/bpm/monitor/instances/${a.tpm_ac_process}/audit`)));}}
 const oracle={acceptance_count:acceptances.records.length,accepted_amount:acceptances.records.filter((a:any)=>a.tpm_ac_status==='accepted').reduce((n:number,a:any)=>n+Number(a.tpm_ac_amount),0),pending_count:acceptances.records.filter((a:any)=>a.tpm_ac_status==='submitted').length,returned_count:acceptances.records.filter((a:any)=>a.tpm_ac_status==='returned').length,overdue_count:sla.filter(a=>a.status==='overdue').length,late_completed_count:sla.filter(a=>a.status==='completed'&&Date.parse(a.completedTime)>Date.parse(a.deadlineTime)).length};
 const widgetResponses:Promise<any>[]=[];
 page.on('response',r=>{if(new URL(r.url()).pathname==='/api/meta/chart-data'&&r.request().postDataJSON()?.queryCode?.startsWith('tdo_'))widgetResponses.push(data(r).then(body=>({queryCode:r.request().postDataJSON().queryCode,body})));});
 await page.getByRole('navigation').getByRole('link',{name:'交付经营看板',exact:true}).click();
 await expect(page.getByRole('heading',{name:'交付经营看板',exact:true})).toBeVisible();
 await expect(page.getByText('交付行动清单 · 点击行定位验收单',{exact:true})).toBeVisible();
 await expect(page.getByText('数据加载失败',{exact:true})).toHaveCount(0);
 await expect.poll(()=>widgetResponses.length).toBeGreaterThanOrEqual(7);
 const widgets=await Promise.all(widgetResponses);const summary=widgets.find(w=>w.queryCode==='tdo_delivery_summary').body.rows[0];
 for(const [key,value]of Object.entries(oracle))expect(Number(summary[key]),key).toBe(value);
 const factRows=widgets.find(w=>w.queryCode==='tdo_delivery_facts').body.rows;
 expect(factRows).toHaveLength(acceptances.records.length);expect(factRows.some((r:any)=>r.acceptance_name==='已撤销的统计对照')).toBe(false);expect(new Set(factRows.map((r:any)=>r.acceptance_pid)).size).toBe(factRows.length);expect(Number(summary.pending_count)).toBeGreaterThan(0);expect(Number(summary.overdue_count)).toBeGreaterThan(0);expect(Number(summary.returned_count)).toBeGreaterThan(0);expect(Number(summary.late_completed_count)).toBeGreaterThan(0);expect(factRows.some((r:any)=>r.verification_result==='资料已更新 · 需复核')).toBe(true);
 for(const a of acceptances.records){const f=factRows.find((r:any)=>r.acceptance_pid===a.pid);expect(f.acceptance_name).toBe(a.tpm_ac_name);expect(Number(f.amount)).toBe(Number(a.tpm_ac_amount));expect(f.acceptance_status).toBe(a.tpm_ac_status);
 const audit=(audits.get(a.pid)||[]).filter(r=>['task_approve','task_reject','task_complete'].includes(r.operation)&&r.result==='success').sort((x,y)=>Date.parse(y.createdAt)-Date.parse(x.createdAt));
 if(audit.length){expect(f.approval_result).toBe({task_approve:'同意',task_reject:'退回',task_complete:'已处理'}[audit[0].operation as 'task_approve']);expect(f.approval_comment).toBe(audit[0].details?.comment||'未填写意见');expect(Number(f.approval_minutes)).toBeGreaterThanOrEqual(0);}
 if(a.tpm_ac_status==='accepted')expect(audit.length,'approved acceptance must have an actual approval decision').toBeGreaterThan(0);
 const associated=checks.records.filter((c:any)=>c.tdv_ck_acceptance===a.pid).sort((x:any,y:any)=>Date.parse(y.created_at)-Date.parse(x.created_at)||String(y.id).localeCompare(String(x.id)));
 const check=associated[0];if(check?.tdv_ck_checked_at){expect(f.checks_passed).toBe(check.tdv_ck_passed);if(Date.parse(check.tdv_ck_checked_at)<Date.parse(a.updated_at))expect(f.verification_result).toBe('资料已更新 · 需复核');}
 }
 await shot(page,'OP01');await page.getByTestId('dashboard-block-approval-results').scrollIntoViewIfNeeded();await shot(page,'OP13');await page.getByTestId('dashboard-block-verification-results').scrollIntoViewIfNeeded();await expect(page.getByTestId('dashboard-block-verification-results')).toContainText('资料已更新 · 需复核');await shot(page,'OP14');await page.getByRole('heading',{name:'交付经营看板',exact:true}).scrollIntoViewIfNeeded();
 const before=widgetResponses.length;await page.getByRole('button',{name:'刷新',exact:true}).click();await expect.poll(()=>widgetResponses.length).toBeGreaterThanOrEqual(before+7);await shot(page,'OP03');
 await page.setViewportSize({width:1280,height:900});await expect.poll(async()=>{const box=await page.getByTestId('dashboard-block-late_completed_count').boundingBox();return box?Math.round(box.x+box.width):9999;}).toBeLessThanOrEqual(1280);await expect(page.getByRole('heading',{name:'交付经营看板',exact:true})).toBeVisible();await shot(page,'OP04');await page.setViewportSize({width:1440,height:1000});
 const target=factRows.find((row:any)=>Number(row.current_overdue)>0);expect(target).toBeTruthy();await expect(page.getByTestId('dashboard-block-delivery-actions').locator('tbody tr').first()).toContainText(target.acceptance_name);
 // Use the first actual rendered business row; the server fact is an independent identity oracle.
 const firstRow=page.getByTestId('dashboard-block-delivery-actions').getByRole('row').filter({hasText:target.acceptance_name});await firstRow.click();
 await expect(page).toHaveURL(/\/p\/tpm_acceptance\?/);await expect(page.getByRole('row').filter({hasText:target.acceptance_name})).toHaveCount(1);
 await page.getByRole('row').filter({hasText:target.acceptance_name}).getByRole('button',{name:/查看|详情/}).click();await expect(page).toHaveURL(new RegExp(`/p/tpm_acceptance/view/${target.acceptance_pid}$`));await expect(page.getByRole('main')).toContainText(target.acceptance_name);await expect(page.getByTestId('detail-persistent-header').getByTestId('bpm-panel').filter({has:page.getByTestId('bpm-section-operations')})).toHaveAttribute('data-state','ready');await shot(page,'OP02');
 await navigateToMenuByClick(page,['元数据管理','报表管理']);await page.getByRole('button',{name:'新建报表',exact:true}).click();
 const chooser=page.waitForEvent('filechooser');await page.getByTestId('report-template-import').click();await(await chooser).setFiles(templatePath);await expect(page.getByTestId('report-template-imported')).toBeVisible();
 const title='客户项目交付复盘';await page.getByRole('textbox',{name:'报表标题',exact:true}).fill(title);
 const saved=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/report-definitions'&&r.request().method()==='POST');await page.getByTestId('report-designer-toolbar-btn-save').click();const definition=await data(await saved);const pid=definition.pid;
 await expect(page).toHaveURL(new RegExp(`/report-designer/${pid}$`));await page.reload();await expect(page.getByRole('textbox',{name:'报表标题',exact:true})).toHaveValue(title);await shot(page,'OP07');
 const toolbar=page.getByTestId('report-designer-toolbar');await toolbar.getByRole('button',{name:'预览',exact:true}).click();await expect(page.getByRole('main')).toContainText('四、核验快照与跟进行动');await expect(page.getByRole('main')).toContainText(target.acceptance_name);await shot(page,'OP08');
 const artifacts=[];
 for(const [format,label,ext,id]of [['markdown','Markdown','md','OP09'],['html','HTML','html','OP10'],['pdf','PDF','pdf','OP11']]){
  const response=page.waitForResponse(r=>new URL(r.url()).pathname===`/api/reports/export/${format}`&&r.request().method()==='POST');const download=page.waitForEvent('download');await toolbar.getByRole('button',{name:`导出 ${label}`,exact:true}).click();const exported=await response;expect(exported.status()).toBe(200);expect(exported.request().postDataJSON().reportPid).toBe(pid);
  const output=join(evidence!,`delivery-retrospective.${ext}`);const file=await download;expect(await file.failure()).toBeNull();await file.saveAs(output);const bytes=await readFile(output);let text=bytes.toString('utf8');if(format==='pdf'){expect(bytes.subarray(0,5).toString()).toBe('%PDF-');text=execFileSync('pdftotext',['-layout',output,'-'],{encoding:'utf8'});}if(format==='html')text=await page.evaluate(html=>new DOMParser().parseFromString(html,'text/html').body.textContent||'',text);
  for(const row of factRows){for(const key of ['acceptance_name','approval_result','sla_result','verification_result','next_action'])expect(compact(text),`${format}: ${key}`).toContain(compact(row[key]));}
  for(const heading of ['一、验收与责任人','二、审批执行结果','三、SLA 实际结果','四、核验快照与跟进行动'])expect(compact(text)).toContain(compact(heading));
  await writeFile(`${output}.parsed.txt`,text);await shot(page,id);await info.attach(`retrospective-${format}`,{path:output,contentType:exported.headers()['content-type']});artifacts.push({format,path:output,bytes:bytes.length});
 }
 await writeFile(join(evidence!,'operations-receipt.json'),JSON.stringify({reportPid:pid,oracle,summary,factRows,acceptances:acceptances.records,checks:checks.records,sla,artifacts},null,2));
});

test('OP source grants independently protect native SLA and verification facts in dashboard and all report formats',async({page,browser})=>{
 test.setTimeout(240000);const admin=await login(page);const tenant=String(admin.user.tenantId);
 const receipt=JSON.parse(await readFile(process.env.AURA_OPERATIONS_REPORT_RECEIPT||join(evidence!,'operations-receipt.json'),'utf8'));const reportPid=receipt.reportPid;
 const tree=await data(await page.request.get('/api/permissions/tree'));const ids=new Map<string,number>();const collect=(nodes:any[])=>nodes.forEach(n=>{ids.set(n.code,n.id);collect(n.children||[]);});collect(tree);
 const suffix=crypto.randomUUID().replaceAll('-','').slice(0,12),code=`tdo_review_${suffix}`;
 const role=await data(await page.request.post('/api/roles',{data:{code,name:'交付复盘审阅',type:'custom',status:'active',scopeType:'tenant',defaultDataScopeType:'all'}}));
 const grants=JSON.parse(await readFile(join(root!,'plugins/project-delivery-operations/config/roles.json'),'utf8'))[0].permissions as string[];expect(grants).toContain('data.datasource.read');expect(grants).not.toContain('report.definition.manage');
 for(const code of grants)expect(ids.has(code),code).toBe(true);
 const grant=async(missing:string|null)=>{await data(await page.request.put(`/api/permissions/matrix/${role.pid}/batch`,{data:grants.map(code=>({permissionId:ids.get(code),granted:code!==missing}))}));};
 await grant(null);const email=`${code}@e2e.local`;await data(await page.request.post('/api/admin/users',{data:{email,displayName:'交付复盘审阅员',initialPassword:process.env.ADMIN_PASSWORD,roleCodes:[code],sendInviteEmail:false}}));
 const context=await browser.newContext({baseURL:requireRuntimeUrl('base'),storageState:{cookies:[],origins:[]},locale:'zh-CN',viewport:{width:1440,height:1000}});const actor=await context.newPage();
 try{
  const identity=await login(actor,email,tenant);expect(identity.permissions.roles.map((r:any)=>r.code)).not.toContain('tenant_admin');
  for(const missing of [null,'bpm.monitor.read','model.tdv_check.read',null]){
   await grant(missing);await actor.reload();await menu(actor,'项目');
   await expect(actor).toHaveURL(/\/p\/tpm_project$/);
   await expect(actor.getByRole('main').getByRole('table')).toBeVisible();
   const queries:Promise<any>[]=[];const observe=(r:Response)=>{if(new URL(r.url()).pathname==='/api/meta/chart-data'&&r.request().postDataJSON()?.queryCode?.startsWith('tdo_'))queries.push(r.finished().then(()=>({status:r.status()})));};actor.on('response',observe);
   await actor.getByRole('navigation').getByRole('link',{name:'交付经营看板',exact:true}).click();await expect.poll(()=>queries.length).toBeGreaterThanOrEqual(7);const outcomes=await Promise.all(queries);actor.off('response',observe);
   for(const result of outcomes)expect(result.status).toBe(missing?403:200);
   if(missing){await expect(actor.getByTestId('dashboard-block-delivery-actions').getByRole('alert')).toContainText('无权读取数据');await expect(actor.getByTestId('dashboard-block-pending_count').getByRole('alert')).toContainText('请联系管理员');await expect(actor.getByRole('main')).not.toContainText('Access forbidden');}else {
    const target=receipt.factRows[0];
    const row=await dashboardRow(actor.getByTestId('dashboard-block-delivery-actions'),target.acceptance_name);
    await expect(row).toContainText(target.acceptance_label);
    await expect(row).toContainText(Number(target.amount).toLocaleString('en-US',{minimumFractionDigits:2,maximumFractionDigits:2}));
   }
   await shot(actor,missing==='bpm.monitor.read'?'OP05':'OP05-verification-'+(missing?'denied':'allowed'));
   for(const format of ['markdown','html','pdf']){const r=await actor.request.post(`/api/reports/export/${format}`,{data:{reportPid}});expect(r.status(),`${missing}: ${format}`).toBe(missing?403:200);if(missing)expect(await r.text()).not.toContain(receipt.factRows[0].acceptance_name);}
   if(missing){
    await navigateToMenuByClick(actor,['元数据管理','报表管理']);await actor.getByRole('row').filter({hasText:'客户项目交付复盘'}).last().getByRole('button',{name:/打开|Open/}).click();
    const toolbar=actor.getByTestId('report-reader-toolbar').filter({visible:true});await expect(toolbar).toBeVisible();
    for(const format of ['Markdown','HTML','PDF'])await expect(toolbar.getByRole('button',{name:`导出 ${format}`,exact:true})).toBeDisabled();
    await expect(actor.getByRole('alert')).toContainText('当前没有可用结果');await expect(actor.getByRole('main')).not.toContainText('暂无数据');
    await shot(actor,'OP12-'+missing.replaceAll('.','-'));
   }
  }
 }finally{await context.close();}
});

test('OP empty business space cannot query another tenant or open its retrospective',async({page})=>{
 test.setTimeout(180000);page.setDefaultTimeout(20000);
 const fixture=process.env.AURA_VERIFICATION_EVIDENCE_DIR;if(!fixture||!process.env.AURA_BPM_PROJECT_ROOT)throw Error('Run the verification profile first and bind its tenant-isolation receipt and owned BPM source.');
 const tenants=JSON.parse(await readFile(join(fixture,'verification-tenant-isolation.json'),'utf8'));const receipt=JSON.parse(await readFile(process.env.AURA_OPERATIONS_REPORT_RECEIPT||join(evidence!,'operations-receipt.json'),'utf8'));
 const identity=await login(page,process.env.ADMIN_EMAIL!,String(tenants.foreignTenant));expect(String(identity.user.tenantId)).toBe(String(tenants.foreignTenant));
 for(const directory of [join(process.env.AURA_CORE_PROJECT_ROOT!,'plugins/platform-admin'),join(process.env.AURA_CORE_PROJECT_ROOT!,'plugins/core-dashboard'),join(process.env.AURA_BPM_PROJECT_ROOT!,'plugin'),join(root!,'plugins/project-delivery-operations')]){const r=await page.request.post('/api/plugins/import/import-directory-sync',{data:{path:directory,conflictStrategy:'OVERWRITE',deferReferenceValidation:true},timeout:180000});const imported=await r.json();expect(r.status(),JSON.stringify(imported)).toBe(200);expect(imported.success,JSON.stringify(imported)).toBe(true);}
 await page.reload();await page.getByRole('navigation').getByRole('link',{name:'交付经营看板',exact:true}).click();await expect(page.getByTestId('dashboard-block-delivery-actions')).toContainText('暂无交付验收，请先创建并提交验收单');await expect(page.getByTestId('dashboard-block-pending_count').getByText('0',{exact:true})).toBeVisible();
 await expect(page.getByRole('main')).not.toContainText(receipt.factRows[0].acceptance_name);await shot(page,'OP06');
 const response=await page.request.post('/api/meta/named-queries/tdo_delivery_facts/execute',{data:{parameters:{tenantId:String(tenants.originalTenant)},page:1,size:500}});const results=await data(response);expect(results.records).toEqual([]);
 const foreignReport=await page.request.get(`/api/report-definitions/${receipt.reportPid}`);expect(foreignReport.status()).toBe(404);
 for(const format of ['markdown','html','pdf']){const denied=await page.request.post(`/api/reports/export/${format}`,{data:{reportPid:receipt.reportPid}});expect(denied.status()).toBe(404);expect(await denied.text()).not.toContain(receipt.factRows[0].acceptance_name);}
 await writeFile(join(evidence!,'operations-tenant-isolation.json'),JSON.stringify({originalTenant:tenants.originalTenant,foreignTenant:tenants.foreignTenant,forgedTenantRows:results.records.length,foreignReport:foreignReport.status()},null,2));
});

test('OP responsive dashboard and compact retrospective preserve the existing real business results',async({page},info)=>{
 test.setTimeout(180000);page.setDefaultTimeout(20000);await mkdir(evidence!,{recursive:true});await login(page);
 const input=process.env.AURA_OPERATIONS_REPORT_RECEIPT||join(evidence!,'operations-receipt.json');const receipt=JSON.parse(await readFile(input,'utf8'));
 await menu(page,'交付经营看板');await expect(page.getByTestId('dashboard-block-delivery-actions')).toContainText('催办当前审批');
 for(const width of [1280,1440]){
  await page.setViewportSize({width,height:width===1280?900:1000});await expect.poll(async()=>{const box=await page.getByTestId('dashboard-block-late_completed_count').boundingBox();return box?Math.round(box.x+box.width):9999;}).toBeLessThanOrEqual(width);
  await expect(page.getByTestId('dashboard-block-delivery-actions')).toContainText('补充资料后重新提交');await shot(page,width===1280?'OP04':'OP01');
 }
 const dashboard=JSON.parse(await readFile(join(root!,'plugins/project-delivery-operations/config/dashboards/tdo_delivery_operations.json'),'utf8'));
 const pageSize=dashboard.widgets.find((w:any)=>w.id==='delivery-actions').config.pageSize;expect(pageSize).toBeGreaterThan(0);
 const totalPages=Math.ceil(receipt.factRows.length/pageSize);expect(totalPages).toBeGreaterThan(1);
 const actions=page.getByTestId('dashboard-block-delivery-actions');const pendingName=receipt.factRows.find((r:any)=>Number(r.current_overdue)>0).acceptance_name;
 await actions.getByRole('button',{name:'下一页',exact:true}).click();await expect(actions).toContainText(`2 / ${totalPages}`);await expect(actions.getByRole('row').filter({hasText:pendingName})).toHaveCount(0);await actions.scrollIntoViewIfNeeded();await shot(page,'OP16');await actions.getByRole('button',{name:'上一页',exact:true}).click();await expect(actions).toContainText(`1 / ${totalPages}`);await expect(actions.locator('tbody tr').first()).toContainText(pendingName);
 await actions.getByRole('columnheader',{name:/^验收金额/}).click();await actions.getByRole('columnheader',{name:/^验收金额/}).click();await expect(actions.locator('tbody tr').first()).toContainText('100,000.00');await shot(page,'OP15');await page.getByRole('button',{name:'刷新',exact:true}).click();await expect(actions.locator('tbody tr').first()).toContainText(pendingName);await expect(page.getByRole('main').getByText('加载中...', {exact:true})).toHaveCount(0);await shot(page,'OP03');
 const overdue=receipt.factRows.find((r:any)=>Number(r.current_overdue)>0);expect(overdue).toBeTruthy();await page.getByTestId('dashboard-block-delivery-actions').getByRole('row').filter({hasText:overdue.acceptance_name}).click();await expect(page).toHaveURL(/\/p\/tpm_acceptance\?/);await page.getByRole('row').filter({hasText:overdue.acceptance_name}).getByRole('button',{name:/查看|详情/}).click();await expect(page).toHaveURL(new RegExp(`/p/tpm_acceptance/view/${overdue.acceptance_pid}$`));await expect(page.getByRole('main')).toContainText(overdue.acceptance_name);await expect(page.getByTestId('detail-persistent-header').getByTestId('bpm-panel').filter({has:page.getByTestId('bpm-section-operations')})).toHaveAttribute('data-state','ready');await shot(page,'OP02');
 await navigateToMenuByClick(page,['元数据管理','报表管理']);await page.getByRole('row').filter({hasText:'客户项目交付复盘'}).last().getByRole('button',{name:/打开|Open/}).click();await expect(page).toHaveURL(new RegExp(`/report-designer/${receipt.reportPid}$`));
 const chooser=page.waitForEvent('filechooser');await page.getByTestId('report-template-import').click();await(await chooser).setFiles(templatePath);await expect(page.getByTestId('report-template-imported')).toBeVisible();
 const save=page.waitForResponse(r=>new URL(r.url()).pathname===`/api/report-definitions/${receipt.reportPid}`&&r.request().method()==='PUT');await page.getByTestId('report-designer-toolbar-btn-save').click();const saved=await save;await data(saved);expect(saved.request().postDataJSON().dsl.page.margin).toEqual({top:10,right:10,bottom:10,left:10});await page.reload();await expect(page.getByRole('textbox',{name:'报表标题',exact:true})).toHaveValue('客户项目交付复盘');await shot(page,'OP07');
 const toolbar=page.getByTestId('report-designer-toolbar');await toolbar.getByRole('button',{name:'预览',exact:true}).click();await expect(page.getByRole('main')).toContainText(overdue.acceptance_name);await expect(page.getByRole('main')).toContainText('资料已更新 · 需复核');await shot(page,'OP08');
 const factRows=(await data(await page.request.post('/api/meta/named-queries/tdo_delivery_facts/execute',{data:{parameters:{},size:500}}))).records;expect(factRows.map((r:any)=>r.acceptance_pid).sort()).toEqual(receipt.factRows.map((r:any)=>r.acceptance_pid).sort());
 const artifacts=[];
 for(const [format,label,ext,id]of [['markdown','Markdown','md','OP09'],['html','HTML','html','OP10'],['pdf','PDF','pdf','OP11']]){
  const sent=page.waitForResponse(r=>new URL(r.url()).pathname===`/api/reports/export/${format}`&&r.request().method()==='POST');const download=page.waitForEvent('download');await toolbar.getByRole('button',{name:`导出 ${label}`,exact:true}).click();const response=await sent;expect(response.status()).toBe(200);expect(response.request().postDataJSON().reportPid).toBe(receipt.reportPid);const file=await download;expect(await file.failure()).toBeNull();const output=join(evidence!,`delivery-retrospective.${ext}`);await file.saveAs(output);const bytes=await readFile(output);let text=bytes.toString();if(format==='pdf'){expect(bytes.subarray(0,5).toString()).toBe('%PDF-');text=execFileSync('pdftotext',['-layout',output,'-'],{encoding:'utf8'});const metadata=execFileSync('pdfinfo',[output],{encoding:'utf8'});expect(Number(metadata.match(/^Pages:\s+(\d+)/m)?.[1])).toBeLessThanOrEqual(3);}if(format==='html')text=await page.evaluate(html=>new DOMParser().parseFromString(html,'text/html').body.textContent||'',text);for(const row of factRows)for(const key of ['acceptance_name','approval_result','sla_result','verification_result','next_action'])expect(compact(text)).toContain(compact(row[key]));await writeFile(`${output}.parsed.txt`,text);await shot(page,id);await info.attach(`compact-${format}`,{path:output,contentType:response.headers()['content-type']});artifacts.push({format,path:output,bytes:bytes.length});
 }
 await writeFile(join(evidence!,'operations-receipt.json'),JSON.stringify({...receipt,factRows,artifacts,reusedBusinessJourneyReceipt:input},null,2));
});
