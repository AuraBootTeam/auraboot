import {test, expect, type Page, type Response, type APIResponse} from '@playwright/test';
import {readFile, mkdir, writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {execFileSync} from 'node:child_process';
import {loginDeliveryMember} from '../../helpers/delivery-session';
import {dashboardRow} from '../../helpers/delivery-dashboard';
import {completeDeliveryApproval} from '../../helpers/delivery-approval';

const evidence=process.env.AURA_EVIDENCE_DIR!;
if(!evidence || !process.env.DELIVERY_FIXTURE_PATH) throw Error('Use the registered owned multirole showcase profile');
async function data(response:Response|APIResponse) {
  const body=await response.json();expect(response.status(),JSON.stringify(body)).toBe(200);expect(String(body.code)).toBe('0');return body.data;
}
async function menu(page:Page,name:string) {
  await page.getByRole('navigation').getByRole('link',{name,exact:true}).click();
}
async function open(page:Page,menuName:string,name:string) {
  await menu(page,menuName);const row=page.getByRole('row').filter({hasText:name});await expect(row).toHaveCount(1);
  await row.getByRole('button',{name:/查看|详情/}).click();await expect(page.getByRole('heading',{name,exact:true})).toBeVisible();await expect(page.getByRole('main')).toContainText(name);
}
async function shot(page:Page,id:string) {
  await mkdir(join(evidence,'screenshots'),{recursive:true});
  await expect(page.getByTestId('toast-stack').getByRole('alert')).toHaveCount(0,{timeout:15000});
  // Dynamic detail blocks load independently of the persistent object header.
  await expect(page.getByRole('main').getByText(/^(加载中(?:\.{3}|…)|加载活动记录)$/)).toHaveCount(0);
  await page.screenshot({path:join(evidence,'screenshots',id+'.png'),fullPage:true});
}
async function command(page:Page,code:string,trigger:()=>Promise<unknown>) {
  const response=page.waitForResponse(r=>new URL(r.url()).pathname===`/api/meta/commands/execute/${code}`&&r.request().method()==='POST');
  await trigger();return data(await response);
}
async function record(page:Page,model:string,pid:string) {return data(await page.request.get(`/api/dynamic/${model}/${pid}`));}
async function upload(page:Page,name:string,text:string) {
  const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/file/upload'&&r.request().method()==='POST');
  await page.getByTestId('form-field-tpm_ac_files').locator('input[type=file]').setInputFiles({name,mimeType:'text/plain',buffer:Buffer.from(text)});
  const file=await data(await response);await expect(page.getByTestId('form-field-tpm_ac_files')).toContainText(name);return file.fileId;
}
async function fact(page:Page,pid:string) {
  const result=await data(await page.request.post('/api/meta/named-queries/tdo_delivery_facts/execute',{data:{parameters:{},size:500}}));
  const row=result.records.find((r:any)=>r.acceptance_pid===pid);expect(row).toBeTruthy();return row;
}

test('named owner verifier reviewer and viewer complete one corrected delivery @critical',async({page,browser,baseURL})=>{
  test.setTimeout(420000);page.setDefaultTimeout(20000);
  const fixture=JSON.parse(await readFile(process.env.DELIVERY_FIXTURE_PATH!,'utf8'));
  expect(fixture.participants).toHaveLength(4);const [lin,zhou,chen]=fixture.participants;
  expect(chen.login).toBe('delivery-chen');expect(lin.login).toBe('delivery-lin');expect(zhou.login).toBe('delivery-zhou');expect(lin.userPid).not.toBe(zhou.userPid);
  const adminContext=await browser.newContext({baseURL,storageState:{cookies:[],origins:[]},locale:'zh-CN'});
  const ownerContext=await browser.newContext({baseURL,storageState:{cookies:[],origins:[]},locale:'zh-CN',viewport:{width:1440,height:1000}});
  const verifierContext=await browser.newContext({baseURL,storageState:{cookies:[],origins:[]},locale:'zh-CN',viewport:{width:1440,height:1000}});
  const reviewerContext=await browser.newContext({baseURL,storageState:{cookies:[],origins:[]},locale:'zh-CN',viewport:{width:1440,height:1000}});
  try {
    const administrator=await adminContext.newPage();await loginDeliveryMember(administrator);
    const plan=JSON.parse(await readFile(join(process.env.AURA_ENTERPRISE_PROJECT_ROOT!,'plugins/customer-delivery-showcase/examples/delivery-participants.json'),'utf8'));
    // Explicit administrator fixture setup for existing installations; ordinary --update never grants roles.
    const roles=await data(await administrator.request.get('/api/roles/all'));
    for(const assignment of plan) {
      const actor=fixture.participants.find((row:any)=>row.login===assignment.login);expect(actor).toBeTruthy();
      const rolePids=assignment.roleCodes.map((code:string)=>{const found=roles.filter((role:any)=>role.code===code);expect(found).toHaveLength(1);return found[0].pid;});
      expect(await data(await administrator.request.put('/api/user-roles/sync-by-pid',{data:{memberPid:actor.memberPid,rolePids}}))).toBe(true);
      expect((await data(await administrator.request.get('/api/user-roles/member/'+actor.memberPid))).sort()).toEqual([...rolePids].sort());
    }
    const owner=await ownerContext.newPage(),reviewer=await reviewerContext.newPage(),verifier=await verifierContext.newPage();
    owner.setDefaultTimeout(20000);reviewer.setDefaultTimeout(20000);verifier.setDefaultTimeout(20000);
    const ownerIdentity=await loginDeliveryMember(owner,lin.login,process.env.AURA_ADMIN_TENANT_ID,process.env.ADMIN_PASSWORD,lin.userPid);
    const reviewerIdentity=await loginDeliveryMember(reviewer,zhou.login,process.env.AURA_ADMIN_TENANT_ID,process.env.ADMIN_PASSWORD,zhou.userPid);
    const verifierIdentity=await loginDeliveryMember(verifier,chen.login,process.env.AURA_ADMIN_TENANT_ID,process.env.ADMIN_PASSWORD,chen.userPid);
    expect(new Set([ownerIdentity.user.pid,reviewerIdentity.user.pid,verifierIdentity.user.pid]).size).toBe(3);
    expect(verifierIdentity.permissions.permissionCodes).not.toContain('bpm.process.execute');
    expect(ownerIdentity.permissions.permissionCodes).not.toContain('tdv.check.manage');
    for(const identity of [ownerIdentity,reviewerIdentity]) {
      expect(identity.permissions.roles.map((r:any)=>r.code)).not.toContain('tenant_admin');
      expect(identity.permissions.permissionCodes).toContain('bpm.process.execute');
      expect(identity.permissions.permissionCodes).not.toContain('bpm.process.manage');
      expect(identity.permissions.permissionCodes).not.toContain('plugin.plugin.manage');
    }
    await menu(owner,'项目');await owner.getByRole('button',{name:'新建项目',exact:true}).click();
    const projects=await data(await owner.request.get('/api/dynamic/tpm_project/list?pageSize=500'));
    expect(projects.records.length).toBe(projects.total);
    const batches=projects.records.map((row:any)=>/^华辰制造 · 第(\d+)批门户交付$/.exec(row.tpm_pj_name)?.[1]).filter(Boolean).map(Number);
    const batch=Math.max(0,...batches)+1;const projectName=`华辰制造 · 第${batch}批门户交付`;
    await owner.locator('#tpm_pj_name').fill(projectName);await owner.getByTestId('select-trigger-tpm_pj_customer_id').click();
    await owner.getByRole('option',{name:'华辰制造',exact:true}).click();await owner.getByTestId('user-select-trigger-tpm_pj_owner_user').click();
    await owner.getByTestId(`user-select-option-tpm_pj_owner_user-${lin.userPid}`).click();
    await owner.locator('#tpm_pj_description').fill('林晓负责客户门户交付，周宁独立审核材料，完成补正、重新核验与验收归档。');
    await owner.locator('#tpm_pj_budget').fill('286000');await owner.locator('#tpm_pj_start_date').fill('2026-10-01');await owner.locator('#tpm_pj_end_date').fill('2026-12-20');
    await owner.getByRole('heading',{name:'新建项目',exact:true}).scrollIntoViewIfNeeded();await shot(owner,'MR00-project-new');
    const createdProject=await command(owner,'tpm:create_project',()=>owner.getByTestId('form-btn-submit').click());
    const projectPid=createdProject.data.recordPid;expect(projectPid).toBeTruthy();await owner.reload();
    await open(owner,'项目',projectName);await expect(owner).toHaveURL(new RegExp(`/p/tpm_project/view/${projectPid}$`));await expect(owner.getByTestId('object-summary')).toContainText('286,000');await expect(owner.getByRole('main')).not.toContainText(/加载中\.\.\.|加载活动记录/);await shot(owner,'MR00b-project-created');
    await owner.getByTestId('toolbar-btn-edit').click();
    const confirmedScope='林晓负责客户门户交付，周宁独立审核材料；客户已确认门户、权限与培训交付范围。';
    await owner.locator('#tpm_pj_description').fill(confirmedScope);await shot(owner,'MR00c-project-edit');
    await expect(owner.getByTestId('form-btn-submit')).toHaveCount(0);
    await expect(owner.getByTestId('form-btn-update')).toBeVisible();
    await command(owner,'tpm:update_project',()=>owner.getByTestId('form-btn-update').click());
    await open(owner,'项目',projectName);await owner.reload();await expect(owner.getByRole('main')).toContainText(confirmedScope);await expect(owner.getByTestId('object-summary')).toContainText('286,000');await expect(owner.getByRole('main')).not.toContainText(/加载中\.\.\.|加载活动记录/);await shot(owner,'MR00d-project-reopened');
    await menu(owner,'交付验收');await owner.getByRole('button',{name:'新建验收',exact:true}).click();const name=`华辰制造 · 第${batch}批交接验收`;
    await owner.locator('#tpm_ac_name').fill(name);await owner.getByTestId('select-trigger-tpm_ac_project').click();await owner.getByRole('option',{name:projectName,exact:true}).click();
    await owner.locator('#tpm_ac_scope').fill('客户门户、权限配置与培训交付完成，提交材料由周宁独立核对。');await owner.locator('#tpm_ac_amount').fill('68000');
    await owner.getByTestId('select-trigger-tpm_ac_risk').click();await owner.getByRole('option',{name:'普通',exact:true}).click();
    await owner.getByTestId('user-select-trigger-tpm_ac_reviewer').click();await owner.getByTestId(`user-select-option-tpm_ac_reviewer-${zhou.userPid}`).click();
    const originalFile=await upload(owner,'交接清单.txt','客户门户、权限配置已交付。培训签到待审核人核对。');
    const created=await command(owner,'tpm:create_acceptance',()=>owner.getByRole('button',{name:'保存验收草稿',exact:true}).click());const pid=created.data.recordPid;
    await open(owner,'交付验收',name);await shot(owner,'MR01-owner-draft');
    await menu(verifier,'交付核验');await verifier.getByRole('button',{name:'新建核验',exact:true}).click();const checkName=name+' · 材料核验';
    await verifier.locator('#tdv_ck_name').fill(checkName);await verifier.getByTestId('select-trigger-tdv_ck_acceptance').click();await verifier.getByRole('option',{name,exact:true}).click();
    const check=await command(verifier,'tdv:create_check',()=>verifier.getByRole('button',{name:'保存核验记录',exact:true}).click());const checkPid=check.data.recordPid;
    const verify=async()=>{
      await open(verifier,'交付核验',checkName);await command(verifier,'tdv:verify_delivery',()=>verifier.getByTestId('toolbar-btn-verify_delivery').click());
      await expect(verifier.getByRole('main')).toContainText('核验通过');await verifier.reload();
      await expect(verifier.getByTestId('object-section-source')).toContainText('核验通过');
      const row=await record(verifier,'tdv_check',checkPid);expect(row.tdv_ck_status).toBe('passed');return row;
    };
    const firstCheck=await verify();
    const ownerDenied=await owner.request.post('/api/meta/commands/execute/tdv:verify_delivery',{data:{operationType:'update',targetRecordPid:checkPid,payload:{}}});
    expect(ownerDenied.status()).toBe(403);expect((await record(verifier,'tdv_check',checkPid)).tdv_ck_checked_at).toBe(firstCheck.tdv_ck_checked_at);
    const submit=async()=>{
      await open(owner,'交付验收',name);await command(owner,'tpm:submit_delivery_acceptance',()=>owner.getByTestId('toolbar-btn-submit_acceptance').click());
      await expect.poll(async()=>(await record(owner,'tpm_acceptance',pid)).tpm_ac_status).toBe('submitted');
      await expect(owner.getByTestId('bpm-operations-approve')).toBeDisabled();return (await record(owner,'tpm_acceptance',pid)).tpm_ac_process;
    };
    const firstProcess=await submit();await shot(owner,'MR02-owner-submitted');
    await open(reviewer,'交付验收',name);await expect(reviewer.getByTestId('bpm-operations-approve')).toBeEnabled();
    const tasks=await data(await reviewer.request.get('/api/bpm/tasks/todo'));
    const allTasks=tasks.records||tasks.content||tasks;expect(Array.isArray(allTasks)).toBe(true);
    const task=allTasks.find((t:any)=>String(t.processInstanceId)===String(firstProcess));expect(task).toBeTruthy();
    expect(task.instanceId).toBeTruthy();
    const forged=await owner.request.post(`/api/bpm/tasks/${task.instanceId}/approve`,{data:{comment:'Owner is not the assigned reviewer',variables:{}}});expect(forged.status()).toBe(403);
    expect((await record(owner,'tpm_acceptance',pid)).tpm_ac_status).toBe('submitted');
    await data(await completeDeliveryApproval(reviewer,'reject','培训签到与培训交付范围不一致，请林晓补齐后重新提交。'));
    await expect.poll(async()=>(await record(owner,'tpm_acceptance',pid)).tpm_ac_status).toBe('returned');await reviewer.reload();
    await expect(reviewer.getByRole('main')).toContainText(name);
    await expect(reviewer.getByTestId('bpm-sla-summary')).toContainText('计时已结束');
    await reviewer.getByRole('tab',{name:'审批记录',exact:true}).click();
    await expect(reviewer.getByTestId('bpm-history-container')).toContainText('培训签到与培训交付范围不一致');await shot(reviewer,'MR03-reviewer-returned');
    await open(owner,'交付验收',name);await owner.getByTestId('toolbar-btn-edit').click();await owner.locator('#tpm_ac_scope').fill('客户门户、权限配置已验收；补齐培训签到及培训范围说明，由周宁再次核对。');
    const correctedFile=await upload(owner,'培训签到与交付说明.txt','培训签到：客户项目组已确认。范围：门户操作、权限管理和供应链交接。');
    await command(owner,'tpm:update_acceptance',()=>owner.getByRole('button',{name:'保存验收草稿',exact:true}).click());
    const stale=await fact(owner,pid);expect(stale.verification_result).toBe('资料已更新 · 需复核');
    await menu(owner,'交付经营看板');
    const staleRow=await dashboardRow(owner.getByTestId('dashboard-block-verification-results'),name);
    await expect(staleRow).toContainText('资料已更新 · 需复核');await staleRow.scrollIntoViewIfNeeded();await shot(owner,'MR04-stale-materials');
    const secondCheck=await verify();expect(Date.parse(secondCheck.tdv_ck_checked_at)).toBeGreaterThan(Date.parse(firstCheck.tdv_ck_checked_at));
    expect((await fact(owner,pid)).verification_result).toBe('核验通过');await shot(verifier,'MR05-reverified');
    const secondProcess=await submit();expect(secondProcess).not.toBe(firstProcess);
    await open(reviewer,'交付验收',name);await data(await completeDeliveryApproval(reviewer,'approve','培训签到与交付范围一致，补正材料已核对，批准本次验收。'));
    await expect.poll(async()=>(await record(owner,'tpm_acceptance',pid)).tpm_ac_registered).toBe(true);
    const acceptance=await record(owner,'tpm_acceptance',pid);expect(acceptance.tpm_ac_status).toBe('accepted');expect(acceptance.tpm_ac_reviewer).toBe(zhou.userPid);
    expect(Number((await record(owner,'tpm_project',projectPid)).tpm_pj_accepted_total)).toBe(68000);
    await reviewer.reload();await reviewer.getByRole('tab',{name:'审批记录',exact:true}).click();await expect(reviewer.getByTestId('bpm-history-container')).toContainText('周宁');await shot(reviewer,'MR06-approved-history');
    // Approval and registration update the source record, so refresh the advisory snapshot explicitly.
    await verify();const finalFact=await fact(owner,pid);expect(finalFact).toMatchObject({reviewer_name:'周宁',acceptance_label:'已通过',approval_result:'同意',verification_result:'核验通过'});
    await menu(owner,'交付经营看板');const finalRow=await dashboardRow(owner.getByTestId('dashboard-block-delivery-actions'),name);await expect(finalRow).toContainText('周宁');await expect(finalRow).toContainText('已通过');await finalRow.scrollIntoViewIfNeeded();await shot(owner,'MR07-business-result');
    await menu(reviewer,'开始体验');await reviewer.getByRole('main').getByRole('button',{name:'打开复盘报告',exact:true}).click();
    await reviewer.getByRole('row').filter({has:reviewer.getByRole('cell',{name:'客户交付经营复盘 · 周会',exact:true})}).getByRole('button',{name:/打开|Open/}).click();
    const toolbar=reviewer.getByTestId('report-reader-toolbar');await expect(toolbar).toBeVisible();
    await expect(toolbar.getByRole('button',{name:'预览',exact:true})).toHaveCount(0);
    await expect(reviewer.getByTestId('report-designer-toolbar-btn-save')).toHaveCount(0);
    await expect(reviewer.getByRole('main')).toContainText(name);await expect(reviewer.getByRole('main')).toContainText('周宁');await shot(reviewer,'MR09-report-result');
    const artifacts=[];
    for(const [format,label,ext] of [['markdown','Markdown','md'],['html','HTML','html'],['pdf','PDF','pdf']]) {
      const download=reviewer.waitForEvent('download');const sent=reviewer.waitForResponse(r=>new URL(r.url()).pathname===`/api/reports/export/${format}`&&r.request().method()==='POST');
      await toolbar.getByRole('button',{name:`导出 ${label}`,exact:true}).click();expect((await sent).status()).toBe(200);
      const file=await download;expect(await file.failure()).toBeNull();const output=join(evidence,`multirole-review.${ext}`);await file.saveAs(output);
      const bytes=await readFile(output);expect(bytes.length).toBeGreaterThan(100);let text=bytes.toString('utf8');
      if(format==='pdf'){expect(bytes.subarray(0,5).toString()).toBe('%PDF-');text=execFileSync('pdftotext',['-layout',output,'-'],{encoding:'utf8'});}
      if(format==='html')text=await reviewer.evaluate(html=>new DOMParser().parseFromString(html,'text/html').body.textContent||'',text);
      for(const value of [name,'周宁','同意','核验通过'])expect(text.replace(/\s/g,''),format).toContain(value.replace(/\s/g,''));
      artifacts.push({format,path:output,bytes:bytes.length});
    }
    await loginDeliveryMember(page,'delivery-viewer@example.test');await open(page,'交付验收',name);await expect(page.getByTestId('toolbar-btn-edit')).toHaveCount(0);
    expect((await page.request.post('/api/meta/commands/execute/tpm:update_acceptance',{data:{operationType:'update',targetRecordPid:pid,payload:{tpm_ac_amount:1}}})).status()).toBe(403);
    expect(Number((await record(owner,'tpm_acceptance',pid)).tpm_ac_amount)).toBe(68000);
    await expect(page.getByTestId('dsl-form-readonly')).toBeVisible();await expect(page.getByRole('main')).not.toContainText('正在加载表单');
    await expect(page.getByTestId('dsl-form-readonly')).toContainText('补齐培训签到');
    for(const reader of [reviewer,page]) {
      const attachments=JSON.parse(acceptance.tpm_ac_files);expect(attachments).toHaveLength(2);
      for(const attachment of attachments) {
        const response=await reader.request.get(attachment.url);expect(response.status()).toBe(200);
        const bytes=await response.body();expect(bytes.length).toBe(attachment.size);
        expect(bytes.toString('utf8')).toContain(attachment.name==='交接清单.txt'?'客户门户、权限配置已交付':'培训签到：客户项目组已确认');
      }
    }
    await shot(page,'MR08-readonly');
    await writeFile(join(evidence,'multirole-receipt.json'),JSON.stringify({projectPid,acceptancePid:pid,checkPid,owner:lin,reviewer:zhou,verifier:chen,roleProvisioning:'explicit-admin-fixture-setup',firstProcess,secondProcess,originalFile,correctedFile,firstCheck,secondCheck,stale,finalFact,acceptedTotal:68000,artifacts},null,2));
  } finally {await ownerContext.close();await reviewerContext.close();await verifierContext.close();await adminContext.close();}
});
