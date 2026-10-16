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
 if(new URL(page.url()).pathname==='/tenant-selection'){await page.getByTestId(`space-business-${tenant}`).click();await expect(page).not.toHaveURL(/tenant-selection/);}
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
 const sla:any[]=[];for(const a of acceptances.records){if(a.tpm_ac_process){const rows=await data(await page.request.get(`/api/bpm/monitor/instances/${a.tpm_ac_process}/sla`));sla.push(...rows);}}
 const oracle={pending_count:acceptances.records.filter((a:any)=>a.tpm_ac_status==='submitted').length,returned_count:acceptances.records.filter((a:any)=>a.tpm_ac_status==='returned').length,overdue_count:sla.filter(a=>a.status==='overdue').length,late_completed_count:sla.filter(a=>a.status==='completed'&&Date.parse(a.completedTime)>Date.parse(a.deadlineTime)).length};
 const widgetResponses:Promise<any>[]=[];
 page.on('response',r=>{if(/\/api\/dashboards\/[^/]+\/widgets\/[^/]+\/data$/.test(new URL(r.url()).pathname))widgetResponses.push(data(r).then(body=>({path:new URL(r.url()).pathname,body})));});
 await page.getByRole('navigation').getByRole('link',{name:'交付经营看板',exact:true}).click();
 await expect(page.getByRole('heading',{name:'交付经营看板',exact:true})).toBeVisible();
 await expect(page.getByText('交付行动清单 · 点击行定位验收单',{exact:true})).toBeVisible();
 await expect(page.getByText('数据加载失败',{exact:true})).toHaveCount(0);
 await expect.poll(()=>widgetResponses.length).toBeGreaterThanOrEqual(7);
 const widgets=await Promise.all(widgetResponses);const summary=widgets.find(w=>w.path.includes('/pending_count/')).body.rows[0];
 for(const [key,value]of Object.entries(oracle))expect(Number(summary[key]),key).toBe(value);
 const factRows=widgets.find(w=>w.path.includes('/delivery-actions/')).body.rows;
 expect(factRows).toHaveLength(acceptances.records.length);expect(new Set(factRows.map((r:any)=>r.acceptance_pid)).size).toBe(factRows.length);
 for(const a of acceptances.records){const f=factRows.find((r:any)=>r.acceptance_pid===a.pid);expect(f.acceptance_name).toBe(a.tpm_ac_name);expect(Number(f.amount)).toBe(Number(a.tpm_ac_amount));expect(f.acceptance_status).toBe(a.tpm_ac_status);}
 await shot(page,'OP01');
 const before=widgetResponses.length;await page.getByRole('button',{name:'刷新',exact:true}).click();await expect.poll(()=>widgetResponses.length).toBeGreaterThanOrEqual(before+7);await shot(page,'OP03');
 await page.setViewportSize({width:1280,height:900});await expect(page.getByRole('heading',{name:'交付经营看板',exact:true})).toBeVisible();await shot(page,'OP04');await page.setViewportSize({width:1440,height:1000});
 const target=factRows[0];const table=page.locator('div').filter({has:page.getByRole('heading',{name:'交付行动清单 · 点击行定位验收单',exact:true})});
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
