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
 const title='客户项目交付简报 · 验证副本';await page.getByRole('textbox',{name:'报表标题',exact:true}).fill(title);
 const save=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/report-definitions'&&r.request().method()==='POST');
 await page.getByTestId('report-designer-toolbar-btn-save').click();const saved=await save;expect(saved.status()).toBe(200);
 const body=await saved.json();expect(String(body.code)).toBe('0');const pid=body.data.pid;
 expect(saved.request().postDataJSON().dsl.dataSources.projects.queryCode).toBe('tpm_delivery_brief');
 await page.reload();await expect(page.getByRole('textbox',{name:'报表标题',exact:true})).toHaveValue(title);
 const toolbar=page.getByTestId('report-designer-toolbar');
 const query=page.waitForResponse(r=>r.url().includes('tpm_delivery_brief')&&r.request().method()==='GET');
 await toolbar.getByRole('button',{name:'预览',exact:true}).click();const queried=await query;expect(queried.status()).toBe(200);const response=await queried.json();expect(String(response.code)).toBe('0');
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
  if(format==='pdf'){expect(bytes.subarray(0,5).toString()).toBe('%PDF-');text=execFileSync('pdftotext',['-layout',output,'-'],{encoding:'utf8'});}
  if(format==='html'){const parsed=await page.evaluate(source=>{const doc=new DOMParser().parseFromString(source,'text/html');return {text:doc.body.textContent||'',tables:doc.querySelectorAll('table').length,scripts:doc.querySelectorAll('script').length};},text);expect(parsed.tables).toBe(3);expect(parsed.scripts).toBe(0);text=parsed.text;}
  if(format==='markdown'){expect(text).toMatch(/^# /);expect(text.match(/\| 客户名称 \| 客户联系人 \|/g)).toHaveLength(1);}
  const compact=(s:string)=>s.normalize('NFKC').replace(/\s+/g,'');
  for(const data of Object.values(context) as any[]){expect(compact(text)).toContain(compact(data.contact));expect(compact(text)).toContain(compact(data.risk));expect(compact(text)).toContain(compact(data.nextAction));}
  for(const owner of ['林晓','周宁','陈晨','王越'])expect(text).toContain(owner);
  expect(text).toContain('594');expect(text).not.toContain('tpm_pj_');
  await writeFile(output+'.parsed.txt',text);artifacts.push({format,output,bytes:bytes.length});await info.attach(format,{path:output,contentType:res.headers()['content-type']});
 }
 await writeFile(join(evidence,'delivery-brief-receipt.json'),JSON.stringify({pid,title,source:'portable DSL with governed runtime queries',provesLiveAI:false,artifacts},null,2));
});
