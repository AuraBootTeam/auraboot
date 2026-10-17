import {test, expect, type Page, type APIResponse, type Response} from '@playwright/test';
import {mkdir, writeFile, readFile} from 'node:fs/promises';
import {join} from 'node:path';

const evidence = process.env.AURA_EVIDENCE_DIR;
if (!evidence || !process.env.ADMIN_EMAIL || !process.env.ADMIN_PASSWORD) throw Error('Bind the owned runtime, evidence round and local administrator.');
const receipts: unknown[] = [];
async function data(response: APIResponse | Response) {
  const body = await response.json();
  expect(response.status(), JSON.stringify(body)).toBe(200);
  expect(String(body.code)).toBe('0');
  return body.data;
}
async function login(page: Page, email = process.env.ADMIN_EMAIL!, tenant?: string) {
  await page.goto('/login');
  await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true');
  await page.locator('#identifier').fill(email);
  await page.locator('#password').fill(process.env.ADMIN_PASSWORD!);
  await page.getByRole('button', {name:'立即登录', exact:true}).click();
  await page.waitForURL(url => !url.pathname.includes('/login'));
  if (new URL(page.url()).pathname === '/tenant-selection') {
    expect(tenant).toBeTruthy();await page.getByTestId(`space-business-${tenant}`).click();
    await expect(page).not.toHaveURL(/tenant-selection/);
  }
  return (await data(await page.request.get('/api/auth/me'))).user;
}
async function menu(page: Page, name: string) {
  await page.getByRole('navigation').getByRole('link', {name, exact:true}).click();
}
async function shot(page: Page, id: string) {
  await mkdir(join(evidence!, 'screenshots'), {recursive:true});
  if (['d01','d02','d03','d04','d02-published','d02-compact'].includes(id)) await page.getByTestId('decisionops-console').evaluate(el=>el.scrollIntoView({block:'start'}));
  if (['d13','d13-assigned'].includes(id)) await page.getByTestId('detail-pdf-target').scrollIntoViewIfNeeded();
  await expect(page.getByTestId('toast-stack').getByRole('alert')).toHaveCount(0,{timeout:15000});
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
  await expect(page).toHaveURL(/\/p\/tpm_acceptance\/view\//);
  await expect(page.getByRole('main')).toContainText(name);
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
  const pid = result.data.recordPid;
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
  await expect(page.getByTestId('detail-persistent-header').getByTestId('bpm-panel')).toHaveAttribute('data-state','ready');
  await expect(page.getByRole('tab',{name:'验收详情',exact:true})).toHaveAttribute('aria-selected','true');
  await expect(page.getByTestId('bpm-rule-trace-status')).toHaveCount(0);
  await expect(page.getByTestId('bpm-history-container')).toHaveCount(0);
  await expect(page.getByTestId('approval-chain-report')).toHaveCount(0);
  await expect(page.getByRole('tab',{name:'变更历史',exact:true})).toHaveCount(0);
  await expect(page.getByTestId('bpm-status-instance-id')).toHaveCount(0);
  await page.getByRole('tab',{name:'运行记录',exact:true}).click();
  await expect(page.getByTestId('bpm-rule-trace-status')).toContainText('命中');
  await expect(page.getByTestId('detail-persistent-header').getByTestId('bpm-operations-approve')).toBeVisible();
  await page.reload();
  await expect(page.getByRole('tab',{name:'运行记录',exact:true})).toHaveAttribute('aria-selected','true');
  await page.getByRole('tab',{name:'验收详情',exact:true}).click();
  await page.getByTestId('detail-pdf-target').scrollIntoViewIfNeeded();
  return sla[0];
}
async function decide(page: Page, pid: string, action: 'approve'|'reject', observer: Page = page) {
  const pending = await row(page,pid);
  const sent = page.waitForResponse(r => (new URL(r.url()).pathname.match(new RegExp(`/api/bpm/tasks/[^/]+/${action}$`))!==null || /\/api\/bpm\/forms\/task\/[^/]+\/submit$/.test(new URL(r.url()).pathname)) && r.request().method()==='POST');
  await page.getByTestId(`bpm-operations-${action}`).click();
  const comment = action==='approve'?'交付范围、完成标准和材料一致，批准验收。':'缺少培训签到记录，请补正后重新提交。';
  if (await page.getByTestId('bpm-task-drawer').count()) {
    await page.getByTestId('bpm-task-drawer').locator('textarea').fill(comment);
    await page.getByTestId(`bpm-drawer-${action}-btn`).click();
  } else {
    await page.getByTestId(`bpm-${action}-comment`).fill(comment);
    await page.getByTestId(`bpm-${action}-confirm`).click();
  }
  await data(await sent);
  await page.getByRole('tab',{name:'验收详情',exact:true}).click();
  const expected = action==='approve'?'accepted':'returned';
  await expect.poll(async ()=>(await row(page,pid)).tpm_ac_status).toBe(expected);
  // The detail must update through the contributed block callback, without manual reload.
  await expect(page.getByTestId('detail-persistent-header')).toHaveCount(0);
  await expect(page.getByRole('main')).toContainText(action==='approve'?'审批通过，交付材料已确认。':'审批退回，请补充交付材料后重新提交。');
  const sla = await data(await observer.request.get(`/api/bpm/monitor/instances/${pending.tpm_ac_process}/sla`));
  expect(sla.every((item:any)=>item.status==='completed' && !item.active)).toBe(true);
  await page.reload();
  await expect(page.getByRole('main')).toContainText(action==='approve'?'审批通过，交付材料已确认。':'审批退回，请补充交付材料后重新提交。');
  await page.getByTestId('detail-pdf-target').scrollIntoViewIfNeeded();
  await page.getByRole('tab',{name:'审批记录',exact:true}).click();
  await expect(page.getByTestId('bpm-history-container')).toBeVisible();
  await expect(page.getByTestId('bpm-history-container')).not.toContainText('Access forbidden');
  await expect(page.getByTestId('bpm-rule-trace-status')).toHaveCount(0);
  await page.getByRole('tab',{name:'验收详情',exact:true}).click();
  return sla;
}

test('real decision drafts, readiness and boundary outputs from the rule workbench @critical', async({page})=>{
  await login(page);
  expect((await data(await page.request.get('/api/dynamic/tpm_acceptance/list'))).records).toHaveLength(0);
  expect((await data(await page.request.get('/api/dynamic/tpm_project/list'))).records).toHaveLength(8);
  await menu(page,'策略工作台');await expect(page.getByTestId('strategy-business-decision').locator('option')).toHaveCount(3);
  await shot(page,'d16');
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
  for(const width of [1280,1440]) {
    await page.setViewportSize({width,height:1000});
    const panel=await page.getByTestId('strategy-test-panel').boundingBox();
    const editor=await page.getByTestId('strategy-dmn-panel').boundingBox();
    expect(panel).toBeTruthy();expect(editor).toBeTruthy();
    expect(editor!.x+editor!.width).toBeLessThanOrEqual(panel!.x);
    expect(panel!.x+panel!.width).toBeLessThanOrEqual(width);
    expect(editor!.width).toBeGreaterThan(450);
    if(width===1280) await shot(page,'d02-compact');
  }
  await shot(page,'d02');
  await page.getByTestId('dt-row-1').locator('td').filter({has:page.getByLabel('feel-1-amount',{exact:true})}).getByText('表达式',{exact:true}).click();
  await page.getByLabel('feel-1-amount',{exact:true}).fill('>< invalid');
  const invalid=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/decision/test-run'&&r.request().method()==='POST');
  await page.getByTestId('strategy-run-test').click();expect((await data(await invalid)).status).toBe('ERROR');
  await expect(page.getByTestId('strategy-operation-status')).toHaveAttribute('data-tone','error');
  await expect(page.getByTestId('strategy-operation-status')).toContainText('Invalid FEEL');await shot(page,'d03');
  await page.getByLabel('feel-1-amount',{exact:true}).fill('');
  await page.getByTestId('strategy-check-readiness').click();
  await expect(page.getByTestId('strategy-operation-status')).toHaveAttribute('data-tone','success');
  await page.getByText('字段与输出配置',{exact:true}).click();
  await page.getByLabel('input-label-0').fill('交付金额（草稿修改）');
  await expect(page.getByTestId('strategy-studio')).toContainText('草稿尚未验证或已修改');
  await shot(page,'d04');
  await page.getByTestId('strategy-check-readiness').click();
  await expect(page.getByTestId('strategy-operation-status')).toHaveAttribute('data-tone','success');
  const published=page.waitForResponse(r=>/\/api\/decision\/versions\/[^/]+\/publish$/.test(new URL(r.url()).pathname)&&r.request().method()==='POST');
  await page.getByTestId('strategy-publish').click();const version=await data(await published);expect(version.status).toBe('PUBLISHED');
  await page.reload();await page.getByTestId('strategy-business-decision').selectOption('tpm_acceptance_route');
  await page.getByText('字段与输出配置',{exact:true}).click();
  await expect(page.getByLabel('input-label-0',{exact:true})).toHaveValue('交付金额（草稿修改）');
  await shot(page,'d02-published');
});

test('standard delivery enforces materials, blocks forged outcomes, approves and closes SLA @critical',async({page})=>{
  const user=await login(page);const name='华辰制造 · 供应链平台第一阶段验收';
  const pid=await create(page,user,name,68000,'普通',false);
  const denied=page.waitForResponse(r=>r.url().includes('/execute/tpm:submit_delivery_acceptance')&&r.request().method()==='POST');
  await page.getByTestId('toolbar-btn-submit_acceptance').click();
  const rejection=await denied;expect(rejection.ok()).toBe(false);
  await expect(page.getByTestId('ab:detail:tpm_acceptance:action-error')).toContainText('请上传交付材料后再提交验收。');
  expect((await row(page,pid)).tpm_ac_status).toBe('draft');await shot(page,'d06');
  await page.getByRole('button',{name:'关闭',exact:true}).click();
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
  const user=await login(page);const name='华辰制造 · 高风险交付补正';
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
  await change({...config,name:'加强审核 · 30秒加速验证',deadlineMode:'FIXED',deadlineValue:'PT30S',ruleBinding:{...config.ruleBinding,enabled:false}});
  try {
    const name='SLA 加速验证 · 30秒交付验收';const pid=await create(page,user,name,100000,'普通',true);
    const sla=await submit(page,pid,0.5,'tpm_enhanced_review');await shot(page,'d10');
    let expired:any;
    await expect.poll(async()=>{expired=await data(await page.request.get(`/api/bpm/monitor/sla-records/${sla.pid}`));return expired.status;},{timeout:65000,intervals:[3000]}).toBe('overdue');
    expect(expired.currentWarningLevel).toBe(2);expect(expired.warningHistory.length).toBeGreaterThanOrEqual(2);
    let actual:any[]=[];
    await expect.poll(async()=>{const notifications=await data(await page.request.get('/api/bpm/notify/received?type=urge'));actual=notifications.filter((item:any)=>item.taskId===sla.taskId);return actual.length;},{timeout:10000}).toBe(2);expect(actual.every((item:any)=>String(item.processInstanceId)===sla.processInstanceId)).toBe(true);
    let action:any;await expect.poll(async()=>{const logs=await data(await page.request.get(`/api/event-policy/action-logs?size=100&correlationId=sla:${sla.pid}`));action=logs.find((item:any)=>item.correlationId===`sla:${sla.pid}`);return action?.status;},{timeout:10000}).toBe('SUCCESS');
    expect(action.resultPayload.sentCount).toBeGreaterThan(0);
    await writeFile(join(evidence!,'sla-notification-evidence.json'),JSON.stringify({notifications:actual,action},null,2));
    await menu(page,'流程监控');await page.getByTestId('sla-refresh').click();await expect(page.getByRole('main')).toContainText('30秒');await expect(page.getByTestId('sla-record-row').filter({hasText:name})).toContainText('逾期');await shot(page,'d11');
    await openRecord(page,name);await decide(page,pid,'approve');
    const completed=await data(await page.request.get(`/api/bpm/monitor/sla-records/${sla.pid}`));
    const closedAt=Date.parse(completed.completedTime);
    await expect.poll(async()=>{const next=await data(await page.request.get(`/api/bpm/monitor/sla-records/${sla.pid}`));expect(next.status).toBe('completed');expect(next.warningHistory).toEqual(completed.warningHistory);return Date.now()-closedAt;},{timeout:25000,intervals:[3000]}).toBeGreaterThan(16000);
    const after=await data(await page.request.get('/api/bpm/notify/received?type=urge'));expect(after.filter((item:any)=>item.taskId===sla.taskId)).toHaveLength(2);
    const actionAfter=await data(await page.request.get(`/api/event-policy/action-logs?correlationId=sla:${sla.pid}`));expect(actionAfter).toHaveLength(1);
    receipts.push({accelerated:true,sla:expired,completed});await writeFile(join(evidence!,'acceptance-runtime-receipts.json'),JSON.stringify(receipts,null,2));
  } finally { await change(config); }
});


test('readonly viewers and unassigned reviewers cannot change another acceptance @critical',async({page,browser})=>{
  const admin=await login(page);const name='华辰制造 · 验收协作权限验证';
  const pid=await create(page,admin,name,68000,'普通',true);const sla=await submit(page,pid,480,'tpm_standard_review');
  const tree=await data(await page.request.get('/api/permissions/tree'));const ids=new Map<string,number>();
  const collect=(nodes:any[])=>nodes.forEach(node=>{ids.set(node.code,node.id);collect(node.children||[]);});collect(tree);
  for(const reviewer of [false,true]) {
    const code=`acceptance_${reviewer?'unassigned':'readonly'}_${Date.now()}`;
    const role=await data(await page.request.post('/api/roles',{data:{code,name:code,type:'custom',status:'active',scopeType:'tenant',defaultDataScopeType:'all'}}));
    const grants=['tpm.acceptance.read','model.tpm_acceptance.read','tpm.project.read','model.tpm_project.read',...(reviewer?['tpm.acceptance.manage','bpm.process.read','bpm.process.execute']:[])];
    await data(await page.request.put(`/api/permissions/matrix/${role.pid}/batch`,{data:grants.map(code=>{expect(ids.has(code),code).toBe(true);return {permissionId:ids.get(code),granted:true};})}));
    const email=`${code}@e2e.local`;
    await data(await page.request.post('/api/admin/users',{data:{email,displayName:reviewer?'验收协作成员':'交付只读成员',initialPassword:process.env.ADMIN_PASSWORD,roleCodes:[code],sendInviteEmail:false}}));
    const context=await browser.newContext({baseURL:process.env.PLAYWRIGHT_BASE_URL,storageState:{cookies:[],origins:[]},locale:'zh-CN',viewport:{width:1440,height:1000}});
    try {
      const actor=await context.newPage();const actorUser=await login(actor,email,String(admin.tenantId));
      const identity=await data(await actor.request.get('/api/auth/me'));expect(identity.permissions.roles.map((r:any)=>r.code)).not.toContain('tenant_admin');
      await openRecord(actor,name);
      await expect(actor.getByRole('tab',{name:'运行记录',exact:true})).toHaveCount(0);
      await expect(actor.getByRole('tab',{name:'核验结果',exact:true})).toHaveCount(0);
      if(reviewer) {
        await expect(actor.getByTestId('bpm-operations-approve')).toBeDisabled();
        const refused=await actor.request.post(`/api/bpm/tasks/${sla.taskId}/approve`,{data:{comment:'An unassigned reviewer must be refused',variables:{}}});expect(refused.status()).toBe(403);
      } else {
        await expect(actor.getByTestId('toolbar-btn-edit')).toHaveCount(0);
        await expect(actor.getByTestId('toolbar-btn-submit_acceptance')).toHaveCount(0);
        await expect(actor.getByTestId('bpm-panel')).toHaveCount(0);
        const refused=await actor.request.post('/api/meta/commands/execute/tpm:accept_acceptance',{data:{operationType:'update',targetRecordPid:pid,payload:{}}});expect(refused.status()).toBe(403);
      }
      expect((await row(page,pid)).tpm_ac_status).toBe('submitted');await shot(actor,reviewer?'d13':'d13-readonly');
      if(reviewer) {
        const assignedName='华辰制造 · 指定协作成员审批';
        const assignedPid=await create(page,actorUser,assignedName,68000,'普通',true);
        await submit(page,assignedPid,480,'tpm_standard_review');
        await openRecord(actor,assignedName);await expect(actor.getByTestId('bpm-operations-approve')).toBeEnabled();
        await actor.getByRole('tab',{name:'审批记录',exact:true}).click();
        await decide(actor,assignedPid,'approve',page);await shot(actor,'d13-assigned');
      }
    } finally {await context.close();}
  }
  await openRecord(page,name);
  await decide(page,pid,'approve');
});

test('missing route and deadline rules fail closed and can recover after configuration is restored @critical',async({page})=>{
  const user=await login(page);const root=process.env.AURA_CORE_PROJECT_ROOT!;
  const bundle=JSON.parse(await readFile(join(root,'plugins/project-management/examples/acceptance-workflow.bpm-package.json'),'utf8'));
  const workflow=(await data(await page.request.get('/api/bpm/process-definitions?size=1000'))).records.find((p:any)=>p.processKey===bundle.processKey);
  const deploy=async(value:any)=>{
    const imported=await page.request.post('/api/bpm/import/execute',{data:{package:value,strategy:'overwrite'}});expect(imported.status()).toBe(200);expect((await imported.json()).success).toBe(true);
    await data(await page.request.post(`/api/bpm/process-definitions/${workflow.pid}/deploy`,{data:{}}));
  };
  const broken=JSON.parse(JSON.stringify(bundle));
  broken.processDefinition.bpmnContent=broken.processDefinition.bpmnContent.replaceAll('tpm_acceptance_route','absent_acceptance_route');
  // The canonical graph drives package import and deploy; keep it aligned with XML.
  broken.processDefinition.extension.designerJson=broken.processDefinition.extension.designerJson.replaceAll('tpm_acceptance_route','absent_acceptance_route');
  const pid=await create(page,user,'华辰制造 · 规则故障恢复验证',68000,'普通',true);
  const failSubmit=async()=>{
    const rejected=page.waitForResponse(r=>r.url().includes('/execute/tpm:submit_delivery_acceptance')&&r.request().method()==='POST');
    await page.getByTestId('toolbar-btn-submit_acceptance').click();const response=await rejected;expect(response.ok()).toBe(false);
    await expect(page.getByTestId('ab:detail:tpm_acceptance:action-error')).toContainText('请管理员检查');
    await expect(page.getByTestId('ab:detail:tpm_acceptance:action-error')).not.toContainText(/rollback-only|unable to resolve/);
    const unchanged=await row(page,pid);expect(unchanged.tpm_ac_status).toBe('draft');expect(unchanged.tpm_ac_process).toBeFalsy();
    await shot(page,'d14');await page.getByRole('button',{name:'关闭',exact:true}).click();
  };
  try {await deploy(broken);await failSubmit();} finally {await deploy(bundle);}
  const config=(await data(await page.request.get('/api/bpm/sla-configs'))).find((c:any)=>c.targetKey==='tpm_standard_review');
  const set=async(value:any)=>data(await page.request.put(`/api/bpm/sla-configs/${config.pid}`,{data:value}));
  try {
    await set({...config,deadlineValue:'absent_deadline_rule',ruleBinding:{...config.ruleBinding,decisionBinding:{...config.ruleBinding.decisionBinding,decisionCode:'absent_deadline_rule'}}});await failSubmit();
  } finally {await set(config);}
  await submit(page,pid,480,'tpm_standard_review');await decide(page,pid,'approve');
});

test('accepted project list form and detail remain available through their existing menu @smoke',async({page})=>{
  await login(page);await menu(page,'项目');await expect(page.getByRole('row').filter({hasText:'华辰制造 · 供应链协同平台'})).toBeVisible();
  await shot(page,'d15-list');await page.getByRole('button',{name:'新建项目',exact:true}).click();await expect(page.locator('#tpm_pj_name')).toBeVisible();
  await expect(page.getByTestId('ai-fill-banner')).toHaveCount(0);await shot(page,'d15-form');await page.getByTestId('form-btn-cancel').click();
  await page.getByRole('row').filter({hasText:'华辰制造 · 供应链协同平台'}).getByRole('button',{name:/查看|详情/}).click();
  await expect(page.getByTestId('object-summary')).toContainText('林晓');await expect(page.getByRole('main')).toContainText('华辰制造');await expect(page.getByTestId('collection-completion')).toBeVisible();await shot(page,'d15');
});

test('acceptance detail tabs keep business facts primary and maintenance evidence secondary @critical',async({page})=>{
  const user=await login(page);
  const pid=await create(page,user,'华辰制造 · 第二阶段交付验收',68000,'高风险',true);
  await submit(page,pid,120,'tpm_enhanced_review');
  await expect(page.getByRole('tab')).toHaveCount(4);
  await shot(page,'tabs-overview-1440');
  await page.setViewportSize({width:1280,height:1000});
  await expect(page.getByTestId('bpm-operations-approve')).toBeVisible();
  await shot(page,'tabs-overview-1280');
  await page.setViewportSize({width:1440,height:1000});
  await page.getByRole('tab',{name:'审批记录',exact:true}).click();
  await expect(page.getByTestId('bpm-history-container')).toContainText('提交业务审批');
  await expect(page.getByTestId('bpm-rule-trace-status')).toHaveCount(0);
  await shot(page,'tabs-approvals');
  await page.getByRole('tab',{name:'核验结果',exact:true}).click();
  await expect(page.getByTestId('subtable-empty-state')).toContainText('暂未核验');
  await shot(page,'tabs-verification-empty');
  await page.getByRole('tab',{name:'运行记录',exact:true}).click();
  await expect(page.getByTestId('bpm-rule-trace-status')).toContainText('命中');
  await expect(page.getByTestId('approval-chain-report')).toHaveCount(0);
  await shot(page,'tabs-execution');
  await page.getByTestId('bpm-section-audit-report').locator('summary').click();
  await expect(page.getByTestId('approval-chain-entry').first()).toBeVisible();
  const downloading=page.waitForEvent('download');
  await page.getByTestId('approval-chain-download').click();
  const download=await downloading;
  const destination=join(evidence!,'approval-chain-report.json');
  await download.saveAs(destination);
  const report=JSON.parse(await readFile(destination,'utf8'));
  expect(report.processInstanceId).toBe((await row(page,pid)).tpm_ac_process);
  expect(report.totalSteps).toBe(report.chain.length);expect(report.totalSteps).toBeGreaterThan(0);
  await shot(page,'tabs-audit-expanded');
  await page.getByRole('tab',{name:'验收详情',exact:true}).click();
  await expect(page.getByTestId('approval-chain-report')).toHaveCount(0);
});


test('associated DSL form freezes structure, denies node writes and keeps the business layout @critical', async ({page}) => {
  const user = await login(page, process.env.ADMIN_EMAIL!, process.env.AURA_ADMIN_TENANT_ID);
  // Explicit fixture update affects new submissions only; existing instance snapshots stay untouched.
  const definitions = await data(await page.request.get('/api/bpm/process-definitions?size=1000'));
  const process = definitions.records.find((row:any) => row.processKey === 'tpm_delivery_acceptance');
  expect(process?.pid).toBeTruthy();
  const bundle = JSON.parse(await readFile(join(process.env.AURA_CORE_PROJECT_ROOT!, 'plugins/project-management/examples/acceptance-workflow.bpm-package.json'), 'utf8'));
  await data(await page.request.put(`/api/bpm/process-definitions/${process.pid}/form-bindings`, {data:bundle.processDefinition.formBindings}));
  const pid = await create(page, user, `华辰制造 · DSL关联验收 ${Date.now()}`, 68000, '高风险', true);
  const bound = page.getByTestId('bpm-bound-form');
  await expect(bound).toHaveAttribute('data-version-source', 'current');
  await expect(bound.getByTestId('dsl-form-readonly')).toBeVisible();
  await expect(bound).toContainText('会员迁移、权限配置和培训交付完成');
  await expect(bound.locator('input,textarea,button[type=submit]')).toHaveCount(0);
  await shot(page, 'form-draft-readonly');
  await submit(page, pid, 120, 'tpm_enhanced_review');
  await expect(bound).toHaveAttribute('data-version-source', 'frozen');
  await expect(bound.getByTestId('dsl-form-readonly')).toBeVisible();
  const record = await row(page, pid);
  const instanceId = String(record.tpm_ac_process);
  const frozen = await data(await page.request.get(`/api/bpm/forms/instance/${instanceId}?formRef=tpm_acceptance_form`));
  expect(frozen.snapshotStatus).toBe('FROZEN');
  expect(frozen.schemaSnapshot.pageKey).toBe('tpm_acceptance_form');
  expect(frozen.record.pid).toBe(pid);
  const tasks = await data(await page.request.get(`/api/bpm/tasks/by-process/${instanceId}`));
  const taskId = tasks[0]?.instanceId;
  expect(taskId).toBeTruthy();
  const taskForm = await data(await page.request.get(`/api/bpm/forms/task/${taskId}`));
  expect(taskForm.formBinding.schemaSnapshot).toEqual(frozen.schemaSnapshot);
  // Direct requests here are negative security probes, not a substitute for the UI approval below.
  for (const forged of [{businessData:{tpm_ac_amount:1}}, {saveStrategy:'business_only'}]) {
    const denied = await page.request.post(`/api/bpm/forms/task/${taskId}/submit`, {data:{...forged,variables:{decision:'approve'}}});
    expect(denied.ok()).toBe(false);
    expect((await row(page,pid)).tpm_ac_status).toBe('submitted');
    expect((await row(page,pid)).tpm_ac_amount).toBe(68000);
  }
  await shot(page, 'form-submitted-frozen');
  // Republish setup via the public API; the frozen form is verified through a real browser reload.
  const current = await data(await page.request.get('/api/pages/key/tpm_acceptance_form'));
  const changedBlocks = structuredClone(current.blocks);
  changedBlocks.find((block:any) => block.id==='identity').title = {'zh-CN':'新版验收范围','en':'Updated acceptance scope'};
  try {
    await data(await page.request.put(`/api/pages/${current.pid}`, {data:{blocks:changedBlocks,rowVersion:current.rowVersion}}));
    await data(await page.request.post(`/api/pages/${current.pid}/publish`, {data:{}}));
    await page.reload();
    await expect(bound).toHaveAttribute('data-version-source','frozen');
    await expect(bound.getByTestId('object-section-identity')).toContainText('验收范围');
    await expect(bound).not.toContainText('新版验收范围');
    const after = await data(await page.request.get(`/api/bpm/forms/instance/${instanceId}?formRef=tpm_acceptance_form`));
    expect(after.schemaSnapshot).toEqual(frozen.schemaSnapshot);
    await writeFile(join(evidence!, 'associated-form-receipt.json'), JSON.stringify({pid,instanceId,taskId,frozen,after}, null,2));
    await shot(page,'form-republished-old-instance');
  } finally {
    const latest = await data(await page.request.get('/api/pages/key/tpm_acceptance_form'));
    await data(await page.request.put(`/api/pages/${current.pid}`, {data:{blocks:current.blocks,rowVersion:latest.rowVersion}}));
    await data(await page.request.post(`/api/pages/${current.pid}/publish`, {data:{}}));
  }
  await decide(page,pid,'approve');
  await expect(page.getByTestId('bpm-bound-form')).toHaveAttribute('data-version-source','frozen');
  await page.reload();
  await expect(page.getByTestId('bpm-bound-form').getByTestId('dsl-form-readonly')).toBeVisible();
  await shot(page,'form-approved-readonly');
});
