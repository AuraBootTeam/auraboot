import {test, expect, type Page, type APIResponse} from '@playwright/test';
import {mkdir, writeFile} from 'node:fs/promises';
import {join} from 'node:path';

const evidence = process.env.AURA_EVIDENCE_DIR;
if (!evidence || !process.env.ADMIN_EMAIL || !process.env.ADMIN_PASSWORD) throw Error('Bind the owned runtime, evidence round and local administrator.');
const receipts: unknown[] = [];
async function data(response: APIResponse) {
  const body = await response.json();
  expect(response.status(), JSON.stringify(body)).toBe(200);
  expect(String(body.code)).toBe('0');
  return body.data;
}
async function login(page: Page) {
  await page.goto('/login');
  await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true');
  await page.locator('#identifier').fill(process.env.ADMIN_EMAIL!);
  await page.locator('#password').fill(process.env.ADMIN_PASSWORD!);
  await page.getByRole('button', {name:'立即登录', exact:true}).click();
  await page.waitForURL(url => !url.pathname.includes('/login'));
  return (await data(await page.request.get('/api/auth/me'))).user;
}
async function menu(page: Page, name: string) {
  await page.getByRole('navigation').getByRole('link', {name, exact:true}).click();
}
async function shot(page: Page, id: string) {
  await mkdir(join(evidence!, 'screenshots'), {recursive:true});
  await page.screenshot({path:join(evidence!, 'screenshots', `${id}.png`),fullPage:true});
}
async function row(page: Page, pid: string) {
  const result = await data(await page.request.get('/api/dynamic/tpm_acceptance/list'));
  const record = result.records.find((item:any) => item.pid === pid);
  expect(record).toBeTruthy();
  return record;
}
async function openRecord(page: Page, name: string) {
  await menu(page, '交付验收');
  const record = page.getByRole('row').filter({hasText:name});
  await expect(record).toHaveCount(1);
  await record.getByRole('button', {name:/查看|详情/}).click();
  await expect(page.getByTestId('toolbar-btn-edit')).toBeVisible();
}
async function create(page: Page, user: any, name: string, amount: number, risk: string, attachment: boolean) {
  await menu(page, '交付验收');
  await page.getByRole('button', {name:'新建验收',exact:true}).click();
  await page.locator('#tpm_ac_name').fill(name);
  await page.getByTestId('select-trigger-tpm_ac_project').click();
  await page.getByRole('option', {name:'华辰制造 · 供应链协同平台',exact:true}).click();
  await page.locator('#tpm_ac_scope').fill('会员迁移、权限配置和培训交付完成，按清单核对验收标准。');
  await page.locator('#tpm_ac_amount').fill(String(amount));
  await page.getByTestId('select-trigger-tpm_ac_risk').click();
  await page.getByRole('option', {name:risk,exact:true}).click();
  await page.getByTestId('user-select-trigger-tpm_ac_reviewer').click();
  await page.getByTestId(`user-select-option-tpm_ac_reviewer-${user.pid}`).click();
  if (attachment) await upload(page);
  await shot(page, attachment ? 'd05' : 'd06-draft');
  const saved = page.waitForResponse(r => r.url().includes('/execute/tpm:create_acceptance') && r.request().method()==='POST');
  await page.getByRole('button', {name:'保存验收草稿',exact:true}).click();
  const response = await saved;
  const result = await data(response);
  const payload = response.request().postDataJSON().payload;
  expect(payload.tpm_ac_name).toBe(name);
  expect(String(payload.tpm_ac_reviewer)).toBe(user.pid);
  const pid = result.recordPid;
  expect(pid).toBeTruthy();
  await expect(page).not.toHaveURL(/\/new$/);
  await openRecord(page,name);
  expect((await row(page,pid)).tpm_ac_status).toBe('draft');
  return pid;
}
async function upload(page: Page) {
  const uploaded = page.waitForResponse(r => new URL(r.url()).pathname === '/api/file/upload' && r.request().method()==='POST');
  await page.getByTestId('form-field-tpm_ac_files').locator('input[type=file]').setInputFiles({name:'交付验收清单.txt',mimeType:'text/plain',buffer:Buffer.from('Deliverables: migration, permissions, training. Criteria: reviewed and accepted.')});
  const file = await data(await uploaded);
  expect(file.fileId).toBeTruthy();
  await expect(page.getByTestId('form-field-tpm_ac_files')).toContainText('交付验收清单.txt');
}
async function submit(page: Page, pid: string, minutes: number, node: string) {
  const sent = page.waitForResponse(r => r.url().includes('/execute/tpm:submit_delivery_acceptance') && r.request().method()==='POST');
  await page.getByTestId('toolbar-btn-submit_acceptance').click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button',{name:/确认|确定/}).click();
  const response = await sent;
  expect(response.request().postDataJSON().targetRecordPid).toBe(pid);
  await data(response);
  await expect(page.getByTestId('toolbar-btn-submit_acceptance')).toHaveCount(0);
  const record = await row(page,pid);
  expect(record.tpm_ac_status).toBe('submitted');
  expect(record.tpm_ac_process).toBeTruthy();
  const sla = await data(await page.request.get(`/api/bpm/monitor/instances/${record.tpm_ac_process}/sla`));
  expect(sla).toHaveLength(1);
  expect(sla[0].nodeId).toBe(node);
  expect((Date.parse(sla[0].deadlineTime)-Date.parse(sla[0].startTime))/60000).toBe(minutes);
  receipts.push({pid,record,sla});
  await writeFile(join(evidence!,'acceptance-runtime-receipts.json'),JSON.stringify(receipts,null,2));
  await expect(page.getByTestId('bpm-panel')).toHaveAttribute('data-state','ready');
  return sla[0];
}
async function decide(page: Page, pid: string, action: 'approve'|'reject') {
  const pending = await row(page,pid);
  const sent = page.waitForResponse(r => new URL(r.url()).pathname.match(new RegExp(`/api/bpm/tasks/[^/]+/${action}$`))!==null && r.request().method()==='POST');
  await page.getByTestId(`bpm-operations-${action}`).click();
  await page.getByTestId(`bpm-${action}-comment`).fill(action==='approve'?'交付范围、完成标准和材料一致，批准验收。':'缺少培训签到记录，请补正后重新提交。');
  await page.getByTestId(`bpm-${action}-confirm`).click();
  await data(await sent);
  const expected = action==='approve'?'accepted':'returned';
  await expect.poll(async ()=>(await row(page,pid)).tpm_ac_status).toBe(expected);
  // The detail must update through the contributed block callback, without manual reload.
  await expect(page.getByTestId('bpm-panel')).toHaveAttribute('data-state','ready');
  await expect(page.getByRole('main')).toContainText(action==='approve'?'审批通过，交付材料已确认。':'审批退回，请补充交付材料后重新提交。');
  const sla = await data(await page.request.get(`/api/bpm/monitor/instances/${pending.tpm_ac_process}/sla`));
  expect(sla.every((item:any)=>item.status==='completed' && !item.active)).toBe(true);
  await page.reload();
  await expect(page.getByRole('main')).toContainText(action==='approve'?'审批通过，交付材料已确认。':'审批退回，请补充交付材料后重新提交。');
  return sla;
}

test('real decision drafts, readiness and boundary outputs from the rule workbench @critical', async({page})=>{
  await login(page);await menu(page,'策略工作台');
  await expect(page.getByTestId('strategy-studio')).toContainText('草稿尚未验证或已修改');
  await shot(page,'d01');
  await page.getByTestId('strategy-business-decision').selectOption('tpm_acceptance_route');
  await expect(page.getByTestId('strategy-test-inputs').getByRole('textbox',{name:'交付金额',exact:true})).toBeVisible();
  for(const [amount,risk,route] of [['68000','normal','standard'],['68000','high','enhanced'],['100000','normal','enhanced'],['99999.99','normal','standard']]) {
    await page.getByRole('textbox',{name:'交付金额',exact:true}).fill(amount);
    await page.getByRole('textbox',{name:'风险等级',exact:true}).fill(risk);
    const executed=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/decision/test-run'&&r.request().method()==='POST');
    await page.getByTestId('strategy-run-test').click();
    const response=await executed;const result=await data(response);
    expect(result.outputs.route).toBe(route);
    expect(response.request().postDataJSON().context.record.data.tpm_ac_amount).toBe(Number(amount));
    await expect(page.getByTestId('strategy-test-outputs')).toContainText(route);
  }
  await shot(page,'d02');
  await page.getByTestId('strategy-check-readiness').click();
  await expect(page.getByTestId('strategy-operation-status')).toHaveAttribute('data-tone','success');
  await page.getByLabel('input-label-0').fill('交付金额（草稿修改）');
  await expect(page.getByTestId('strategy-studio')).toContainText('草稿尚未验证或已修改');
  await shot(page,'d04');
});

test('standard delivery enforces materials, blocks forged outcomes, approves and closes SLA @critical',async({page})=>{
  const user=await login(page);const name=`华辰制造 · 第一阶段验收 ${Date.now()}`;
  const pid=await create(page,user,name,68000,'普通',false);
  const denied=page.waitForResponse(r=>r.url().includes('/execute/tpm:submit_delivery_acceptance')&&r.request().method()==='POST');
  await page.getByTestId('toolbar-btn-submit_acceptance').click();
  await page.getByRole('dialog').getByRole('button',{name:/确认|确定/}).click();
  const rejection=await denied;expect(rejection.ok()).toBe(false);
  await expect(page.getByText('请上传交付材料后再提交验收。',{exact:false})).toBeVisible();
  expect((await row(page,pid)).tpm_ac_status).toBe('draft');await shot(page,'d06');
  await page.getByTestId('toolbar-btn-edit').click();await upload(page);
  const updated=page.waitForResponse(r=>r.url().includes('/execute/tpm:update_acceptance')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'保存验收草稿',exact:true}).click();await data(await updated);
  if(!new URL(page.url()).pathname.includes('/view/'))await openRecord(page,name);
  const sla=await submit(page,pid,480,'tpm_standard_review');await shot(page,'d08-pending');
  const forged=await page.request.post('/api/meta/commands/execute/tpm:accept_acceptance',{data:{operationType:'update',targetRecordPid:pid,payload:{processInstanceId:sla.processInstanceId,workflowOnly:true}}});
  expect(forged.status()).toBe(403);expect((await row(page,pid)).tpm_ac_status).toBe('submitted');
  const duplicate=await page.request.post('/api/meta/commands/execute/tpm:submit_delivery_acceptance',{data:{operationType:'update',targetRecordPid:pid,payload:{}}});
  expect(duplicate.ok()).toBe(false);expect((await row(page,pid)).tpm_ac_process).toBe(sla.processInstanceId);
  await decide(page,pid,'approve');await shot(page,'d08');
  await menu(page,'流程监控');await expect(page.getByTestId('sla-strategy-chain')).toContainText('交付验收');await shot(page,'d12');
});

test('high-risk rejection can be corrected and resubmitted through a new owned instance @critical',async({page})=>{
  const user=await login(page);const name=`华辰制造 · 风险补正验收 ${Date.now()}`;
  const pid=await create(page,user,name,68000,'高风险',true);
  const first=await submit(page,pid,120,'tpm_enhanced_review');await shot(page,'d07');
  await decide(page,pid,'reject');await shot(page,'d09-returned');
  await page.getByTestId('toolbar-btn-edit').click();
  await page.locator('#tpm_ac_scope').fill('已补齐培训签到和操作说明，重新核对会员迁移与权限配置。');
  const saved=page.waitForResponse(r=>r.url().includes('/execute/tpm:update_acceptance')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'保存验收草稿',exact:true}).click();await data(await saved);
  if(!new URL(page.url()).pathname.includes('/view/'))await openRecord(page,name);
  const second=await submit(page,pid,120,'tpm_enhanced_review');expect(second.processInstanceId).not.toBe(first.processInstanceId);
  await decide(page,pid,'approve');await shot(page,'d09');
  const old=await data(await page.request.get(`/api/bpm/monitor/instances/${first.processInstanceId}/sla`));expect(old[0].status).toBe('completed');
});

test('accelerated SLA uses the real scheduler and stops after approval @critical',async({page})=>{
  test.setTimeout(180000);const user=await login(page);
  const configs=await data(await page.request.get('/api/bpm/sla-configs'));
  const config=configs.find((item:any)=>item.targetKey==='tpm_enhanced_review');expect(config).toBeTruthy();
  const change=async(value:any)=>data(await page.request.put(`/api/bpm/sla-configs/${config.pid}`,{data:value}));
  await change({...config,name:'加强审核 · 30秒加速验证',deadlineMode:'FIXED',deadlineValue:'PT30S'});
  try {
    const name=`SLA 加速验证 · 30秒 ${Date.now()}`;const pid=await create(page,user,name,100000,'普通',true);
    const sla=await submit(page,pid,0.5,'tpm_enhanced_review');await shot(page,'d10');
    let expired:any;
    await expect.poll(async()=>{expired=await data(await page.request.get(`/api/bpm/monitor/sla-records/${sla.pid}`));return expired.status;},{timeout:65000,intervals:[3000]}).toBe('overdue');
    expect(expired.currentWarningLevel).toBe(2);expect(expired.warningHistory.length).toBeGreaterThanOrEqual(2);
    await menu(page,'流程监控');await page.getByTestId('sla-refresh').click();await expect(page.getByRole('main')).toContainText('30秒');await shot(page,'d11');
    await openRecord(page,name);await decide(page,pid,'approve');
    const completed=await data(await page.request.get(`/api/bpm/monitor/sla-records/${sla.pid}`));
    const closedAt=Date.parse(completed.completedTime);
    await expect.poll(async()=>{const next=await data(await page.request.get(`/api/bpm/monitor/sla-records/${sla.pid}`));expect(next.status).toBe('completed');expect(next.warningHistory).toEqual(completed.warningHistory);return Date.now()-closedAt;},{timeout:25000,intervals:[3000]}).toBeGreaterThan(16000);
    receipts.push({accelerated:true,sla:expired,completed});await writeFile(join(evidence!,'acceptance-runtime-receipts.json'),JSON.stringify(receipts,null,2));
  } finally { await change(config); }
});
