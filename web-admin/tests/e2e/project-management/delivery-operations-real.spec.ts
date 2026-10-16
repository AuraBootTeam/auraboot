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
async function login(page:Page,email=process.env.ADMIN_EMAIL!,tenant?:string){
 await page.goto('/login');await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated','true');
 await page.locator('#identifier').fill(email);await page.locator('#password').fill(process.env.ADMIN_PASSWORD!);await page.getByRole('button',{name:'立即登录',exact:true}).click();await page.waitForURL(u=>!u.pathname.includes('/login'));
 if(new URL(page.url()).pathname==='/tenant-selection'){await page.getByTestId(`space-business-${tenant||process.env.AURA_ADMIN_TENANT_ID}`).click();await expect(page).not.toHaveURL(/tenant-selection/);}
 return data(await page.request.get('/api/auth/me'));
}
async function shot(page:Page,id:string){await mkdir(join(evidence!,'screenshots'),{recursive:true});await page.screenshot({path:join(evidence!,'screenshots',`${id}.png`),fullPage:true});}
const compact=(s:string)=>s.normalize('NFKC').replace(/\s+/g,'');

test('OP delivery dashboard drills into real acceptances and its report exports the same approval SLA verification facts',async({page},info)=>{
 test.setTimeout(240000);await login(page);
 const acceptances=await data(await page.request.get('/api/dynamic/tpm_acceptance/list',{params:{pageNum:'1',pageSize:'500'}}));
 const checks=await data(await page.request.get('/api/dynamic/tdv_check/list',{params:{pageNum:'1',pageSize:'500'}}));
 expect(acceptances.records.length).toBeGreaterThan(0);expect(Number(acceptances.total)).toBe(acceptances.records.length);
 expect(checks.records.length).toBeGreaterThan(0);
 const audits=new Map<string,any[]>(); const sla:any[]=[];for(const a of acceptances.records){if(a.tpm_ac_process){const rows=await data(await page.request.get(`/api/bpm/monitor/instances/${a.tpm_ac_process}/sla`));sla.push(...rows);audits.set(a.pid,await data(await page.request.get(`/api/bpm/monitor/instances/${a.tpm_ac_process}/audit`)));}}
 const oracle={pending_count:acceptances.records.filter((a:any)=>a.tpm_ac_status==='submitted').length,returned_count:acceptances.records.filter((a:any)=>a.tpm_ac_status==='returned').length,overdue_count:sla.filter(a=>a.status==='overdue').length,late_completed_count:sla.filter(a=>a.status==='completed'&&Date.parse(a.completedTime)>Date.parse(a.deadlineTime)).length};
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
 expect(factRows).toHaveLength(acceptances.records.length);expect(new Set(factRows.map((r:any)=>r.acceptance_pid)).size).toBe(factRows.length);
 for(const a of acceptances.records){const f=factRows.find((r:any)=>r.acceptance_pid===a.pid);expect(f.acceptance_name).toBe(a.tpm_ac_name);expect(Number(f.amount)).toBe(Number(a.tpm_ac_amount));expect(f.acceptance_status).toBe(a.tpm_ac_status);
 const audit=(audits.get(a.pid)||[]).filter(r=>['task_approve','task_reject','task_complete'].includes(r.operation)&&r.result==='success').sort((x,y)=>Date.parse(y.createdAt)-Date.parse(x.createdAt));
 if(audit.length){expect(f.approval_result).toBe({task_approve:'同意',task_reject:'退回',task_complete:'已处理'}[audit[0].operation as 'task_approve']);expect(f.approval_comment).toBe(audit[0].details?.comment||'未填写意见');expect(Number(f.approval_minutes)).toBeGreaterThanOrEqual(0);}
 if(a.tpm_ac_status==='accepted')expect(audit.length,'approved acceptance must have an actual approval decision').toBeGreaterThan(0);
 const associated=checks.records.filter((c:any)=>c.tdv_ck_acceptance===a.pid).sort((x:any,y:any)=>Date.parse(y.created_at)-Date.parse(x.created_at)||String(y.id).localeCompare(String(x.id)));
 const check=associated[0];if(check?.tdv_ck_checked_at){expect(f.checks_passed).toBe(check.tdv_ck_passed);if(Date.parse(check.tdv_ck_checked_at)<Date.parse(a.updated_at))expect(f.verification_result).toBe('资料已更新 · 需复核');}
 }
 await shot(page,'OP01');
 const before=widgetResponses.length;await page.getByRole('button',{name:'刷新',exact:true}).click();await expect.poll(()=>widgetResponses.length).toBeGreaterThanOrEqual(before+7);await shot(page,'OP03');
 await page.setViewportSize({width:1280,height:900});await expect(page.getByRole('heading',{name:'交付经营看板',exact:true})).toBeVisible();await shot(page,'OP04');await page.setViewportSize({width:1440,height:1000});
 const target=factRows[0];
 // Use the first actual rendered business row; the server fact is an independent identity oracle.
 const firstRow=page.getByRole('row').filter({hasText:target.acceptance_name}).first();await firstRow.click();
 await expect(page).toHaveURL(/\/p\/tpm_acceptance\?/);await expect(page.getByRole('row').filter({hasText:target.acceptance_name})).toHaveCount(1);
 await page.getByRole('row').filter({hasText:target.acceptance_name}).getByRole('button',{name:/查看|详情/}).click();await expect(page).toHaveURL(new RegExp(`/p/tpm_acceptance/view/${target.acceptance_pid}$`));await shot(page,'OP02');
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
 const receipt=JSON.parse(await readFile(join(evidence!,'operations-receipt.json'),'utf8'));const reportPid=receipt.reportPid;
 const tree=await data(await page.request.get('/api/permissions/tree'));const ids=new Map<string,number>();const collect=(nodes:any[])=>nodes.forEach(n=>{ids.set(n.code,n.id);collect(n.children||[]);});collect(tree);
 const suffix=crypto.randomUUID().replaceAll('-','').slice(0,12),code=`tdo_review_${suffix}`;
 const role=await data(await page.request.post('/api/roles',{data:{code,name:'交付复盘审阅',type:'custom',status:'active',scopeType:'tenant',defaultDataScopeType:'all'}}));
 const grants=['dashboard.read','meta.query.read','report.definition.view','report.export.execute','model.tpm_acceptance.read','model.tpm_project.read','model.tdv_check.read','bpm.monitor.read','tpm.project.read','tpm.acceptance.read'];
 for(const code of grants)expect(ids.has(code),code).toBe(true);
 const grant=async(missing:string|null)=>{await data(await page.request.put(`/api/permissions/matrix/${role.pid}/batch`,{data:grants.map(code=>({permissionId:ids.get(code),granted:code!==missing}))}));};
 await grant(null);const email=`${code}@e2e.local`;await data(await page.request.post('/api/admin/users',{data:{email,displayName:'交付复盘审阅员',initialPassword:process.env.ADMIN_PASSWORD,roleCodes:[code],sendInviteEmail:false}}));
 const context=await browser.newContext({baseURL:process.env.PLAYWRIGHT_BASE_URL,storageState:{cookies:[],origins:[]},locale:'zh-CN',viewport:{width:1440,height:1000}});const actor=await context.newPage();
 try{
  const identity=await login(actor,email,tenant);expect(identity.permissions.roles.map((r:any)=>r.code)).not.toContain('tenant_admin');
  for(const missing of [null,'bpm.monitor.read','model.tdv_check.read']){
   await grant(missing);await actor.reload();
   const queries:Promise<any>[]=[];const observe=(r:Response)=>{if(new URL(r.url()).pathname==='/api/meta/chart-data'&&r.request().postDataJSON()?.queryCode?.startsWith('tdo_'))queries.push(r.finished().then(()=>({status:r.status()})));};actor.on('response',observe);
   await actor.getByRole('navigation').getByRole('link',{name:'交付经营看板',exact:true}).click();await expect.poll(()=>queries.length).toBeGreaterThanOrEqual(7);const outcomes=await Promise.all(queries);actor.off('response',observe);
   for(const result of outcomes)expect(result.status).toBe(missing?403:200);
   if(missing)await expect(actor.getByTestId('dashboard-block-delivery-actions').getByRole('alert')).toBeVisible();else await expect(actor.getByTestId('dashboard-block-delivery-actions')).toContainText(receipt.factRows[0].acceptance_name);
   await shot(actor,missing==='bpm.monitor.read'?'OP05':'OP05-verification-'+(missing?'denied':'allowed'));
   for(const format of ['markdown','html','pdf']){const r=await actor.request.post(`/api/reports/export/${format}`,{data:{reportPid}});expect(r.status(),`${missing}: ${format}`).toBe(missing?403:200);if(missing)expect(await r.text()).not.toContain(receipt.factRows[0].acceptance_name);}
   if(missing){
    await navigateToMenuByClick(actor,['元数据管理','报表管理']);await actor.getByRole('row').filter({hasText:'客户项目交付复盘'}).last().getByRole('button',{name:/打开|Open/}).click();
    const toolbar=actor.getByTestId('report-reader-toolbar').filter({visible:true});await expect(toolbar).toBeVisible();
    for(const format of ['Markdown','HTML','PDF']){const denied=actor.waitForResponse(r=>new URL(r.url()).pathname===`/api/reports/export/${format.toLowerCase()}`&&r.request().method()==='POST');await toolbar.getByRole('button',{name:`导出 ${format}`,exact:true}).click();expect((await denied).status()).toBe(403);}
    await shot(actor,'OP12-'+missing.replaceAll('.','-'));
   }
  }
 }finally{await context.close();}
});
