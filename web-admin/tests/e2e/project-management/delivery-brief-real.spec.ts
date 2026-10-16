import {test,expect} from '@playwright/test';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {navigateToMenuByClick} from '../helpers';
const templatePath=fileURLToPath(new URL('../../../../plugins/project-management/examples/customer-delivery-brief.report-template.json',import.meta.url));
const contextsPath=fileURLToPath(new URL('../../../../plugins/project-management/scripts/delivery-project-context.json',import.meta.url));
test('delivery brief imports through the designer and exports the same governed project facts',async({page},info)=>{
 test.setTimeout(180000);
 const evidence=process.env.AURA_EVIDENCE_DIR!;expect(evidence).toBeTruthy();await mkdir(evidence,{recursive:true});
 const context=JSON.parse(await readFile(contextsPath,'utf8')).customers;
 await page.goto('/login');await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated','true');
 await page.locator('#identifier').fill(process.env.ADMIN_EMAIL!);await page.locator('#password').fill(process.env.ADMIN_PASSWORD!);
 await page.locator('form button[type=submit]').click();await page.waitForURL(url=>!url.pathname.includes('/login'));
 await navigateToMenuByClick(page,['元数据管理','报表管理']);
 await page.getByRole('button',{name:'新建报表',exact:true}).click();
 await expect(page.getByTestId('report-template-import')).toBeVisible();
 const chooser=page.waitForEvent('filechooser');await page.getByTestId('report-template-import').click();await(await chooser).setFiles(templatePath);
 await expect(page.getByTestId('report-template-imported')).toBeVisible();
 const title=`客户项目交付简报 · 验证 ${Date.now()}`;await page.getByRole('textbox',{name:'报表标题',exact:true}).fill(title);
 let creates=0;page.on('request',request=>{if(new URL(request.url()).pathname==='/api/report-definitions'&&request.method()==='POST')creates++;});
 const save=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/report-definitions'&&r.request().method()==='POST');
 await page.getByTestId('report-designer-toolbar-btn-save').click();const saved=await save;expect(saved.status()).toBe(200);
 const body=await saved.json();expect(String(body.code)).toBe('0');const pid=body.data.pid;
 expect(saved.request().postDataJSON().dsl.dataSources.projects.queryCode).toBe('tpm_delivery_brief');
 await saved.finished();
 await expect(page).toHaveURL(new RegExp(`/report-designer/${pid}$`));
 await expect(page.getByRole('textbox',{name:'报表标题',exact:true})).toHaveValue(title);
 const reload=page.waitForResponse(r=>new URL(r.url()).pathname===`/api/report-definitions/${pid}`&&r.request().method()==='GET');
 await page.reload();await expect(page.getByRole('textbox',{name:'报表标题',exact:true})).toHaveValue(title);
 const reloaded=await reload;expect(reloaded.status()).toBe(200);
 expect((await reloaded.json()).data.dsl.dataSources.projects.queryCode).toBe('tpm_delivery_brief');
 await page.screenshot({path:join(evidence,'report-save-first-save-refresh.png'),fullPage:true});
 const updatedTitle=title+' · 已更新';
 await page.getByRole('textbox',{name:'报表标题',exact:true}).fill(updatedTitle);
 const update=page.waitForResponse(r=>new URL(r.url()).pathname===`/api/report-definitions/${pid}`&&r.request().method()==='PUT');
 await page.getByTestId('report-designer-toolbar-btn-save').click();const updated=await update;
 expect(updated.status()).toBe(200);expect(String((await updated.json()).code)).toBe('0');
 expect(updated.request().postDataJSON().title).toBe(updatedTitle);
 await expect(page).toHaveURL(new RegExp(`/report-designer/${pid}$`));
 await expect(page.getByTestId('report-designer-toolbar-btn-save')).toBeDisabled();
 await page.reload();await expect(page.getByRole('textbox',{name:'报表标题',exact:true})).toHaveValue(updatedTitle);
 await page.screenshot({path:join(evidence,'report-save-update-same-pid.png'),fullPage:true});
 const toolbar=page.getByTestId('report-designer-toolbar');
 const query=page.waitForResponse(r=>r.url().includes('tpm_delivery_brief')&&r.request().method()==='GET');
 await toolbar.getByRole('button',{name:'预览',exact:true}).click();const queried=await query;expect(queried.status()).toBe(200);const response=await queried.json();expect(String(response.code)).toBe('0');
 const rows=response.data?.records??response.data;expect(Array.isArray(rows)).toBe(true);
 expect(rows.length).toBeGreaterThanOrEqual(8);expect(rows.length).toBeLessThanOrEqual(9);
 const budget=rows.reduce((sum:number,row:any)=>sum+Number(row.budget),0);expect([594000,630000]).toContain(budget);
 const main=page.getByRole('main');
 await expect(main).toContainText('一、项目交付概况');await expect(main).toContainText('二、交付风险与下一步行动');await expect(main).toContainText('三、客户联系信息');
 for(const [customer,data] of Object.entries(context) as [string,any][]){await expect(main).toContainText(customer);await expect(main).toContainText(data.contact);await expect(main).toContainText(data.risk);await expect(main).toContainText(data.nextAction);}
 for(const name of ['林晓','周宁','陈晨','王越'])await expect(main).toContainText(name);
 await page.screenshot({path:join(evidence,'D3-delivery-brief-preview.png'),fullPage:true});
 const artifacts=[];
 for(const [format,label,ext] of [['markdown','Markdown','md'],['html','HTML','html'],['pdf','PDF','pdf']]){
  const download=page.waitForEvent('download');const request=page.waitForResponse(r=>new URL(r.url()).pathname===`/api/reports/export/${format}`&&r.request().method()==='POST');
  await toolbar.getByRole('button',{name:`导出 ${label}`,exact:true}).click();const res=await request;expect(res.status()).toBe(200);expect(res.request().postDataJSON().reportPid).toBe(pid);
  const file=await download;expect(await file.failure()).toBeNull();expect(file.suggestedFilename()).toMatch(new RegExp(`\\.${ext}$`));
  const output=join(evidence,`客户项目交付简报.${ext}`);await file.saveAs(output);const bytes=await readFile(output);expect(bytes.length).toBeGreaterThan(100);
  let text=bytes.toString('utf8');
  if(format==='pdf'){expect(bytes.subarray(0,5).toString()).toBe('%PDF-');const metadata=execFileSync('pdfinfo',[output],{encoding:'utf8'});expect(metadata.match(/Pages:\s+(\d+)/)?.[1]).toBe('2');const dimensions=metadata.match(/Page size:\s+([\d.]+) x ([\d.]+)/);expect(dimensions).not.toBeNull();expect(Number(dimensions![1])).toBeGreaterThan(Number(dimensions![2]));text=execFileSync('pdftotext',['-raw',output,'-'],{encoding:'utf8'});for(const pdfPage of text.split('\f')){if(pdfPage.includes('二、交付风险与下一步行动'))expect(pdfPage).toContain('交付风险 下一步行动');}}
  if(format==='html'){const parsed=await page.evaluate(source=>{const doc=new DOMParser().parseFromString(source,'text/html');return {text:doc.body.textContent||'',tables:doc.querySelectorAll('table').length,scripts:doc.querySelectorAll('script').length};},text);expect(parsed.tables).toBe(3);expect(parsed.scripts).toBe(0);text=parsed.text;}
  if(format==='markdown'){expect(text).toMatch(/^# /);expect(text.match(/\| 客户名称 \| 客户联系人 \|/g)).toHaveLength(1);}
  const compact=(s:string)=>s.normalize('NFKC').replace(/\s+/g,'');
  for(const data of Object.values(context) as any[]){expect(compact(text)).toContain(compact(data.contact));expect(compact(text)).toContain(compact(data.risk));expect(compact(text)).toContain(compact(data.nextAction));}
  for(const owner of ['林晓','周宁','陈晨','王越'])expect(text).toContain(owner);
  expect(text.replace(/,/g,'')).toContain(String(budget));expect(text).not.toContain('tpm_pj_');
  await writeFile(output+'.parsed.txt',text);artifacts.push({format,output,bytes:bytes.length});await info.attach(format,{path:output,contentType:res.headers()['content-type']});
 }
 await navigateToMenuByClick(page,['元数据管理','报表管理']);
 const savedRow=page.getByRole('row').filter({hasText:updatedTitle});await expect(savedRow).toHaveCount(1);
 await savedRow.getByText('打开',{exact:true}).click();await expect(page).toHaveURL(new RegExp(`/report-designer/${pid}$`));
 await expect(page.getByRole('textbox',{name:'报表标题',exact:true})).toHaveValue(updatedTitle);
 expect(creates).toBe(1);
 await writeFile(join(evidence,'delivery-brief-receipt.json'),JSON.stringify({pid,title,updatedTitle,firstSaveDirectRefresh:true,updateSamePid:true,source:'portable DSL with governed runtime queries',provesLiveAI:false,artifacts},null,2));
});

test('a new report auto-saves to its canonical URL and survives immediate refresh',async({page})=>{
 test.setTimeout(90000);
 const evidence=process.env.AURA_EVIDENCE_DIR!;expect(evidence).toBeTruthy();
 await page.goto('/login');await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated','true');
 await page.locator('#identifier').fill(process.env.ADMIN_EMAIL!);await page.locator('#password').fill(process.env.ADMIN_PASSWORD!);
 await page.locator('form button[type=submit]').click();await page.waitForURL(url=>!url.pathname.includes('/login'));
 await navigateToMenuByClick(page,['元数据管理','报表管理']);await page.getByRole('button',{name:'新建报表',exact:true}).click();
 const title=`自动保存刷新验证 ${Date.now()}`;await page.getByRole('textbox',{name:'报表标题',exact:true}).fill(title);
 const saved=await page.waitForResponse(r=>new URL(r.url()).pathname==='/api/report-definitions'&&r.request().method()==='POST',{timeout:45000});
 expect(saved.status()).toBe(200);const body=await saved.json();expect(String(body.code)).toBe('0');const pid=body.data.pid;
 expect(saved.request().postDataJSON().title).toBe(title);
 await expect(page).toHaveURL(new RegExp(`/report-designer/${pid}$`));
 await expect(page.getByRole('textbox',{name:'报表标题',exact:true})).toHaveValue(title);
 await page.reload();await expect(page.getByRole('textbox',{name:'报表标题',exact:true})).toHaveValue(title);
 await page.screenshot({path:join(evidence,'report-save-autosave.png'),fullPage:true});
 await writeFile(join(evidence,'report-autosave-receipt.json'),JSON.stringify({pid,title,manualSaveClicks:0,directRefresh:true},null,2));
});
