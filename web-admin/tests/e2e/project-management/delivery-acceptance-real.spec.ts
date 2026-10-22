import { loginDeliveryMember } from '../../helpers/delivery-session';
import { completeDeliveryApproval } from '../../helpers/delivery-approval';
import { requireRuntimeUrl } from '../../helpers/environments';
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
async function dynamicRecords(page: Page, model: string) {
  const records: any[] = [];
  let total = 0;
  do {
    const result = await data(await page.request.get(`/api/dynamic/${model}/list?pageNum=${Math.floor(records.length / 100) + 1}&pageSize=100`));
    total = Number(result.total);
    expect(Number.isInteger(total)).toBe(true);
    expect(Array.isArray(result.records)).toBe(true);
    if (total > records.length) expect(result.records.length).toBeGreaterThan(0);
    records.push(...result.records);
  } while (records.length < total);
  expect(records).toHaveLength(total);
  expect(new Set(records.map(record => record.pid)).size).toBe(total);
  return {records, total};
}
function canonicalInputMappings(mappings: any[]) {
  return mappings.map(mapping => {
    const source = {...mapping.source};
    // FIELD sources do not consume literal values. DTO serialization includes
    // a null value while the structured editor omits that unused property.
    if (source.kind === 'FIELD' && source.value == null) delete source.value;
    return {...mapping, source};
  });
}
async function login(page: Page, email = process.env.ADMIN_EMAIL!, tenant = process.env.AURA_ADMIN_TENANT_ID) {
  return (await loginDeliveryMember(page, email, tenant)).user;
}

async function menu(page: Page, name: string) {
  await page.getByRole('navigation').getByRole('link', {name, exact:true}).click();
}
async function shot(page: Page, id: string) {
  await mkdir(join(evidence!, 'screenshots'), {recursive:true});
  if (['d01','d02','d03','d04','d02-published','d02-compact'].includes(id)) await page.getByTestId('decisionops-console').evaluate(el=>el.scrollIntoView({block:'start'}));
  if (['d13','d13-assigned'].includes(id)) await page.getByTestId('detail-pdf-target').scrollIntoViewIfNeeded();
  const details=page.getByRole('tab',{name:'验收详情',exact:true});
  if(await details.isVisible() && await details.getAttribute('aria-selected')==='true') await expect(page.getByTestId('bpm-bound-form').getByTestId('dsl-form-readonly')).toBeVisible();
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
  await page.getByRole('option', {name:process.env.PW_ACCEPTANCE_PROJECT_NAME || '华辰制造 · 供应链协同平台',exact:true}).click();
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
  await expect(page.getByTestId('detail-persistent-header').getByTestId('bpm-panel').filter({has:page.getByTestId('bpm-section-operations')})).toHaveAttribute('data-state','ready');
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
  const comment = action==='approve'?'交付范围、完成标准和材料一致，批准验收。':'缺少培训签到记录，请补正后重新提交。';
  await data(await completeDeliveryApproval(page, action, comment));
  await page.getByRole('tab',{name:'验收详情',exact:true}).click();
  const expected = action==='approve'?'accepted':'returned';
  await expect.poll(async ()=>(await row(page,pid)).tpm_ac_status).toBe(expected);
  // The detail must update through the contributed block callback, without manual reload.
  await expect(page.getByTestId('detail-persistent-header').getByTestId('bpm-section-operations')).toHaveCount(0);
  await expect(page.getByTestId('detail-persistent-header').getByTestId('bpm-sla-summary')).toBeVisible();
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
  if(action==='approve') {
    await expect.poll(async ()=>(await row(observer,pid)).tpm_ac_registered, {timeout:15000}).toBe(true);
    const accepted=await row(observer,pid);
    const all=await dynamicRecords(observer, 'tpm_acceptance');
    const sum=all.records.filter((r:any)=>r.tpm_ac_project===accepted.tpm_ac_project&&r.tpm_ac_status==='accepted').reduce((sum:number,r:any)=>sum+Number(r.tpm_ac_amount),0);
    const projects=await dynamicRecords(observer, 'tpm_project');
    const project=projects.records.find((r:any)=>r.pid===accepted.tpm_ac_project);
    expect(Number(project.tpm_pj_accepted_total)).toBe(sum);
    const checks=await dynamicRecords(observer, 'tdv_check');
    expect(checks.records.filter((r:any)=>r.tdv_ck_acceptance===pid)).toHaveLength(1);
    const replayRequest={targetRecordPid:project.pid,payload:{acceptancePid:pid},clientRequestId:`registration-replay:${pid}`};
    await data(await observer.request.post('/api/meta/commands/execute/tdv:register_acceptance',{data:replayRequest}));
    const replay=await data(await observer.request.post('/api/meta/commands/execute/tdv:register_acceptance',{data:replayRequest}));
    expect(replay.idempotentReplay).toBe(true);
    const afterReplay=await dynamicRecords(observer, 'tdv_check');
    expect(afterReplay.records.filter((r:any)=>r.tdv_ck_acceptance===pid)).toHaveLength(1);
    const projectsAfterReplay=await dynamicRecords(observer, 'tpm_project');
    expect(Number(projectsAfterReplay.records.find((r:any)=>r.pid===project.pid).tpm_pj_accepted_total)).toBe(sum);
    let actions:any[]=[];
    await expect.poll(async()=>{
      const logs=await data(await observer.request.get('/api/event-policy/action-logs?policyCode=tpm_acceptance_registered&size=100'));
      actions=logs.filter((log:any)=>log.contextPayload?.record?.recordPid===pid);
      return actions.filter((log:any)=>log.status==='SUCCESS').length;
    },{timeout:15000}).toBe(2);
    expect(actions.map((log:any)=>log.actionType).sort()).toEqual(['EXECUTE_COMMAND','NOTIFY']);
    expect(actions.find((log:any)=>log.actionType==='NOTIFY').resultPayload.sentCount).toBe(1);
    receipts.push({case:'C02',acceptancePid:pid,projectPid:project.pid,acceptedTotal:sum,checkCount:1});
    await writeFile(join(evidence!,'acceptance-runtime-receipts.json'),JSON.stringify(receipts,null,2));
  }
  return sla;
}


async function professionalTab(page: Page, key: string) {
  const tab = page.getByTestId(`doc-tab-${key}`);
  if (!await tab.isVisible()) await page.locator('details.decisionops-professional-entry > summary').click();
  await tab.click();
  await expect(page.getByTestId(`doc-panel-${key}`)).toBeVisible();
}

test('professional event policy publish, execution failure and recovery preserve real outcomes @critical', async ({page}) => {
  test.setTimeout(120000);
  const user = await login(page);
  const policy = `sla_review_${Date.now()}`;
  await menu(page, '事件策略');
  await page.getByTestId('epl-new-policy').click();
  for (const [field, value] of Object.entries({code:policy,name:'交付规则 · 专业配置核验', 'event-type':`SHOWCASE_REVIEW_${policy}`, 'target-type':'MODEL', 'target-key':'tpm_acceptance'})) {
    await page.getByTestId(`epl-policy-${field}`).fill(value);
  }
  const created = page.waitForResponse(r => new URL(r.url()).pathname === '/api/event-policy/definitions' && r.request().method() === 'POST');
  await page.getByTestId('epl-save-policy').click();
  expect((await data(await created)).policyCode).toBe(policy);
  await page.getByTestId(`epl-open-designer-${policy}`).click();
  await expect(page.getByTestId('epd-trigger-context')).toContainText('交付规则 · 专业配置核验');
  await shot(page, 'R10');
  await page.getByTestId('epd-step-rules').click();
  await expect(page.getByTestId('policy-rules-editor')).toBeVisible();
  await page.getByLabel('rule-name-0',{exact:true}).fill('交付金额触发提醒');
  await page.getByTestId('cb-add').click();
  await page.getByLabel('model-0',{exact:true}).selectOption('record:record');
  await page.getByLabel('field-0',{exact:true}).selectOption('record:data.amount');
  await page.getByLabel('operator-0',{exact:true}).selectOption('GTE');
  await page.getByLabel('value-0',{exact:true}).fill('1000');
  await shot(page, 'R11');
  await page.getByTestId('epd-step-actions').click();
  await page.getByTestId('epd-add-action').click();
  await page.getByLabel('action-type-0',{exact:true}).selectOption('NOTIFY');
  await page.getByLabel('action-target-0', {exact:true}).fill(`USER:${user.pid}`);
  await page.getByLabel('action-field-0-payload.title', {exact:true}).fill('专业试算通知');
  await page.getByLabel('action-field-0-payload.content', {exact:true}).fill(`本轮真实通知 ${policy}`);
  await page.getByTestId('epd-step-publish').click();
  async function saveVersion() {
    const response = page.waitForResponse(r => new URL(r.url()).pathname === `/api/event-policy/definitions/${policy}/versions` && r.request().method() === 'POST');
    await page.getByTestId('epd-save-draft').click();
    return data(await response);
  }
  const draft = await saveVersion();
  const validatePath = `/api/event-policy/versions/${draft.pid}/validate`;
  await page.route(`**${validatePath}`, route => route.fulfill({status:503, contentType:'application/json', body:JSON.stringify({code:'503',message:'测试注入：校验服务暂不可用'})}));
  await page.getByTestId('epd-validate-version').click();
  await expect(page.getByTestId('epd-error')).toContainText('校验服务暂不可用');
  expect((await data(await page.request.get(`/api/event-policy/definitions/${policy}/versions`))).find((v:any)=>v.pid===draft.pid).status).toBe('DRAFT');
  await shot(page, 'R14-error');
  await page.unroute(`**${validatePath}`);
  const validated = page.waitForResponse(r => new URL(r.url()).pathname === validatePath && r.request().method() === 'POST');
  await page.getByTestId('epd-validate-version').click();
  expect((await data(await validated)).status).toBe('VALIDATED');
  await expect(page.getByTestId('epd-error')).toHaveCount(0);
  await shot(page, 'R14');
  async function publishVersion(pid: string) {
    const response = page.waitForResponse(r => new URL(r.url()).pathname === `/api/event-policy/versions/${pid}/publish` && r.request().method() === 'POST');
    await page.getByTestId('epd-publish-version').click();
    expect((await data(await response)).status).toBe('PUBLISHED');
  }
  await publishVersion(draft.pid);
  await page.getByTestId('epd-step-history').click();await expect(page.getByTestId('epd-history-panel')).toContainText('已发布');await expect(page.getByTestId('epd-history-panel')).toContainText(`v${draft.version}`);await shot(page, 'R07');
  await page.getByTestId('epd-step-test').click();
  async function run() {
    const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/event-policy/run-and-execute' && r.request().method() === 'POST');
    await page.getByTestId('epd-run-published').click();return data(await response);
  }
  const success = await run();
  expect(success.execution.overallStatus).toBe('ALL_SUCCESS');
  expect(success.execution.actions).toHaveLength(1);
  expect(success.execution.actions[0].resultPayload.sentCount).toBe(1);
  await expect(page.getByTestId('epd-action-diagnostics-0')).not.toHaveAttribute('open');
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('成功');await page.getByTestId('epd-action-execution-0').scrollIntoViewIfNeeded();await shot(page, 'R13');
  // Real invalid recipient, not a fulfilled success response. This must fail at runtime.
  await page.getByTestId('epd-step-actions').click();
  await page.getByLabel('action-target-0', {exact:true}).fill('USER:missing_rule_sla_recipient');
  await page.getByTestId('epd-step-publish').click();const failing = await saveVersion();
  const check = page.waitForResponse(r => new URL(r.url()).pathname === `/api/event-policy/versions/${failing.pid}/validate`);
  await page.getByTestId('epd-validate-version').click();await data(await check);await publishVersion(failing.pid);
  await page.getByTestId('epd-step-test').click();const failed = await run();
  expect(failed.execution.overallStatus).not.toBe('ALL_SUCCESS');
  expect(failed.execution.actions[0].status).toBe('FAILED');
  expect(failed.execution.actions[0].error).toBeTruthy();
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('失败');await page.getByTestId('epd-action-execution-0').scrollIntoViewIfNeeded();await shot(page, 'R16');
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('接收人不可用');
  await expect(page.getByTestId('epd-action-error-0')).not.toHaveAttribute('open');
  await page.getByTestId('epd-action-error-0').locator('summary').click();
  await expect(page.getByTestId('epd-action-error-0')).toHaveAttribute('open');
  const logs = await data(await page.request.get(`/api/event-policy/action-logs?policyCode=${policy}&size=100`));
  expect(logs.filter((l:any)=>l.status==='SUCCESS')).toHaveLength(1);
  expect(logs.filter((l:any)=>l.status==='FAILED')).toHaveLength(1);
  await writeFile(join(evidence!, 'professional-policy-receipt.json'), JSON.stringify({policy,success,failed,logs},null,2));
  const trace=await data(await page.request.post('/api/decision/evaluate',{data:{decisionCode:'tpm_acceptance_route',context:{record:{recordPid:`trace-${policy}`,data:{tpm_ac_amount:68000,tpm_ac_risk:'normal',tpm_ac_open_issues:0,tpm_ac_materials_complete:true}}}}}));
  expect(trace.traceId).toBeTruthy();
  const traceLogs=await data(await page.request.get(`/api/decision/logs?traceId=${encodeURIComponent(trace.traceId)}`));
  const ownedLogs=traceLogs.filter((log:any)=>log.decisionCode==='tpm_acceptance_route');expect(ownedLogs).toHaveLength(1);
  await menu(page,'执行日志');await expect(page.getByTestId('elta-business-results')).toBeVisible();
  const tracePath=/\/api\/decision\/logs\?traceId=/;
  await page.route(tracePath,route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'503',message:'测试注入：执行链路暂不可用'})}));
  await page.getByTestId(`elta-open-trace-${ownedLogs[0].pid ?? ownedLogs[0].traceId}`).click();
  await expect(page.getByTestId('elta-trace-error')).toBeVisible();await shot(page,'R17-error');
  await expect(page.getByTestId('elta-trace-chain').locator('li')).toHaveCount(0);
  await page.unroute(tracePath);await page.getByTestId('elta-trace-error').getByRole('button',{name:'重新加载',exact:true}).click();

  await expect(page.getByTestId('elta-trace-drawer')).toBeVisible();
  await expect(page.getByTestId('elta-trace-chain')).toBeVisible();
  const captured=page.getByTestId(`elta-input-snapshot-${ownedLogs[0].pid}`);await expect(captured).toContainText('当次输入');await expect(captured).toContainText('68000');
  expect(ownedLogs[0].traceSnapshot.inputSnapshot['record.data.tpm_ac_amount'].value).toBe(68000);
  const summary=page.getByTestId(`elta-rule-summary-${ownedLogs[0].pid}`);
  await expect(summary).toHaveText('命中规则数: 1');await expect(summary).not.toContainText('standard');
  const identifiers=page.getByTestId(`elta-rule-identifiers-${ownedLogs[0].pid}`);
  await expect(identifiers).toBeHidden();await shot(page, 'R17');
  const technical=page.getByTestId(`elta-chain-technical-${ownedLogs[0].pid}`);
  await technical.locator('summary').click();await expect(identifiers).toBeVisible();await expect(identifiers).toContainText('standard');
  await shot(page,'R17-identifiers');await technical.locator('summary').click();
});

test('professional rule impact, metadata filters and load-error recovery remain usable @critical', async ({page}) => {
  test.setTimeout(90000);await login(page);await menu(page,'策略工作台');
  await page.getByTestId('ddl-impact-tpm_acceptance_route').click();
  await expect(page.getByTestId('ddl-impact-drawer')).toContainText('tpm_acceptance_route');
  const columns=page.locator('.impact-graph-columns');
  await expect(columns).toHaveCSS('align-items','start');
  const heights=await columns.locator('.impact-graph-column').evaluateAll(nodes=>nodes.map(node=>node.getBoundingClientRect().height));
  expect(heights).toHaveLength(2);expect(heights[0]).toBeLessThan(heights[1]);
  await expect(page.getByTestId('ddl-version-actions')).toBeVisible();await shot(page,'R02');
  await page.getByTestId('ddl-impact-close').click();
  await page.getByTestId('ddl-open-tpm_acceptance_route').click();
  await page.getByTestId('strategy-workspace-tab-dmn').click();
  const expression=page.getByLabel('feel-0-amount',{exact:true});
  await expression.locator('..').locator('summary').click();
  await expression.fill('this is not valid (');
  await page.getByTestId('strategy-workspace-tab-test').click();
  // A malformed authored expression must fail, rather than claiming a matched business outcome.
  await page.getByTestId('strategy-run-test').click();
  await expect(page.getByTestId('strategy-operation-status')).toHaveAttribute('data-tone','error');
  await expect(page.getByTestId('strategy-test-outputs')).toHaveCount(0);await shot(page,'R05');
  await professionalTab(page,'model');
  await page.getByRole('combobox',{name:'model-filter',exact:true}).selectOption('tpm_acceptance');
  await page.getByRole('combobox',{name:'entity-filter',exact:true}).selectOption('record');
  await page.getByRole('textbox',{name:'field-search',exact:true}).fill('tpm_ac_amount');
  await expect(page.getByTestId('dmv-count')).toHaveText('1');
  await expect(page.getByTestId('data-model-viewer')).toContainText('tpm_ac_amount');await shot(page,'A03');
  await professionalTab(page,'permissions');
  const permissions=page.getByTestId('permission-matrix');await expect(permissions).toBeVisible();expect(await permissions.getByRole('button').count()).toBeGreaterThan(0);
  for(const button of await permissions.getByRole('button').all()) await expect(button).toBeDisabled();await shot(page,'A04');
  await professionalTab(page,'connectors');await page.getByRole('textbox',{name:'connector-search',exact:true}).fill('本轮不存在的连接器');
  await expect(page.getByTestId('cl-empty')).toBeVisible();await shot(page,'A05');
  await menu(page,'Webhook 治理');await expect(page).toHaveURL(/\/p\/decisionops_webhooks(?:[?#].*)?$/);
  await expect(page.getByTestId('dynamic-list')).toHaveAttribute('data-model-code','webhook');
  await expect(page.getByTestId('dynamic-list')).toHaveAttribute('data-ready','true');
  await expect(page.getByRole('main')).not.toContainText(/Page Unavailable|加载失败/);
  await expect(page.getByRole('main')).toContainText(/Webhook|订阅/);await shot(page,'A06');
  // Failure-only interception: normal requests after unroute must recover from the real server.
  const definitionPath=/\/api\/decision\/definitions(?:\?.*)?$/;
  await page.route(definitionPath,route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'503',message:'测试注入：规则目录暂不可用'})}));
  await menu(page,'策略工作台');
  await expect(page.getByTestId('decisionops-console')).toBeVisible();
  const unavailable = page.waitForResponse(response => definitionPath.test(new URL(response.url()).pathname) && response.status() === 503);
  await page.reload();await unavailable;
  await expect(page.getByTestId('ddl-error')).toBeVisible({timeout:20000});await shot(page,'A02');
  await page.unroute(definitionPath);await page.getByTestId('ddl-error').getByRole('button',{name:'重新加载',exact:true}).click();
  await expect(page.getByTestId('ddl-row-tpm_acceptance_route')).toBeVisible();await expect(page.getByTestId('ddl-error')).toHaveCount(0);await shot(page,'A02-recovered');
  await page.getByTestId('ddl-open-tpm_acceptance_deadline').click();
  await expect(page.getByTestId('strategy-business-decision')).toHaveValue('tpm_acceptance_deadline');
  await page.getByTestId('strategy-workspace-tab-dmn').click();
  await expect(page.getByTestId('strategy-studio')).toContainText('截止分钟');await shot(page,'R08');
});

test('SLA paused completed and failed-action detail states use actual lifecycle records @critical', async ({page}) => {
  test.setTimeout(180000);const user=await login(page);const runLabel=new Date().toISOString().slice(11,23);const name=`华辰制造 · 暂停恢复核验 · ${runLabel}`;
  const pid=await create(page,user,name,68000,'普通',true);const sla=await submit(page,pid,480,'tpm_standard_review');
  async function processAction(action:'suspend'|'resume') {
    await menu(page,'任务中心');await page.getByRole('button',{name:'我发起的',exact:true}).click();
    const process=page.getByRole('row').filter({hasText:pid});await expect(process).toHaveCount(1);
    await process.getByRole('button').click();
    const response=page.waitForResponse(r=>new URL(r.url()).pathname.endsWith(`/${sla.processInstanceId}/${action}`)&&r.request().method()==='POST');
    await page.getByRole('button',{name:action==='suspend'?'暂停流程':'恢复流程',exact:true}).click();await data(await response);
  }
  await processAction('suspend');
  const paused=await data(await page.request.get(`/api/bpm/monitor/sla-records/${sla.pid}`));expect(paused.status).toBe('paused');
  const dashboardPath=/\/api\/bpm\/monitor\/dashboard(?:\?.*)?$/;
  await page.route(dashboardPath,route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'503',message:'测试注入：监控暂不可用'})}));
  await menu(page,'流程监控');await expect(page.getByTestId('sla-dashboard-error')).toBeVisible();
  await expect(page.getByTestId('sla-dashboard-active-records')).toHaveCount(0);await expect(page.getByText('暂无监控数据',{exact:true})).toHaveCount(0);await shot(page,'S00-load-error');
  await page.unroute(dashboardPath);await page.getByTestId('sla-refresh').click();
  await expect(page.getByTestId('sla-dashboard-active-records')).toBeVisible();await expect(page.getByTestId('sla-dashboard-error')).toHaveCount(0);
  const policiesPath=/\/api\/bpm\/sla-configs(?:\?.*)?$/;
  await page.route(policiesPath,route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'503',message:'测试注入：SLA策略目录暂不可用'})}));
  await page.getByTestId('sla-refresh').click();await expect(page.getByTestId('sla-strategy-error')).toBeVisible();await shot(page,'S00-policy-error');
  await expect(page.getByTestId('sla-strategy-chain')).not.toContainText('暂无已启用的 SLA 策略');
  await page.unroute(policiesPath);await page.getByTestId('sla-refresh').click();await expect(page.getByTestId('sla-strategy-error')).toHaveCount(0);
  await page.getByTestId('sla-stat-paused').click();
  await page.getByTestId('sla-record-row').filter({hasText:name}).getByTestId('sla-record-detail').click();
  await expect(page.getByTestId('sla-item-detail')).toContainText('已暂停');await shot(page,'S05');
  await processAction('resume');expect((await data(await page.request.get(`/api/bpm/monitor/sla-records/${sla.pid}`))).status).toBe('running');
  await openRecord(page,name);await decide(page,pid,'approve');
  await menu(page,'流程监控');await page.getByRole('combobox',{name:'筛选状态',exact:true}).selectOption('completed');
  await page.getByTestId('sla-record-row').filter({hasText:name}).getByTestId('sla-record-detail').click();
  await expect(page.getByTestId('sla-item-detail')).toContainText('处理完成，计时已停止');await shot(page,'S06');
  const configs=await data(await page.request.get('/api/bpm/sla-configs'));const config=configs.find((c:any)=>c.targetKey==='tpm_enhanced_review');expect(config).toBeTruthy();
  await data(await page.request.put(`/api/bpm/sla-configs/${config.pid}`,{data:{...config,deadlineMode:'FIXED',deadlineValue:'PT5S',ruleBinding:{...config.ruleBinding,enabled:false},warningRules:[],actionPolicy:{trigger:'SLA_TIMEOUT',failureStrategy:'FAIL_FAST',actions:[{type:'NOTIFY',order:10,target:'USER:missing_rule_sla_recipient',payload:{title:'无效收件人失败核验',content:'必须失败，不能伪装已送达'}}]}}}));
  try {
    const failName=`华辰制造 · 催办失败诊断核验 · ${runLabel}`;const failedPid=await create(page,user,failName,100000,'高风险',true);const failedSla=await submit(page,failedPid,5/60,'tpm_enhanced_review');
    let logs:any[]=[];await expect.poll(async()=>{logs=await data(await page.request.get(`/api/event-policy/action-logs?correlationId=sla:${failedSla.pid}&size=100`));return logs.length;},{timeout:45000,intervals:[2000]}).toBe(1);
    expect(logs[0].status).toBe('FAILED');expect(logs[0].errorMessage).toBeTruthy();
    await menu(page,'流程监控');await page.getByTestId('sla-refresh').click();
    await page.getByTestId('sla-record-row').filter({hasText:failName}).getByTestId('sla-record-detail').click();
    const detail=page.getByTestId('sla-item-detail');await expect(detail).toContainText('需要检查');await expect(detail).not.toContainText('已执行成功');
    await detail.getByText('技术详情',{exact:true}).click();await expect(detail).toContainText(logs[0].errorMessage);await shot(page,'S04');
    await detail.getByText('策略依据与执行诊断',{exact:true}).click();await expect(detail).toContainText(failedSla.processInstanceId);await shot(page,'S03');
    await writeFile(join(evidence!,'sla-lifecycle-failure-receipt.json'),JSON.stringify({paused,failedSla,logs},null,2));
    await openRecord(page,failName);await decide(page,failedPid,'reject');
  } finally {await data(await page.request.put(`/api/bpm/sla-configs/${config.pid}`,{data:config}));}
});

test('professional rollout compares exact versions and audits activation pause and rollback @critical', async ({page}) => {
  test.setTimeout(120000);await login(page);
  const code=`rule_sla_rollout_${Date.now()}`;
  await data(await page.request.post('/api/decision/definitions',{data:{decisionCode:code,decisionName:'交付时限 · 版本灰度核验',scopeType:'SLA',ownerModule:'decision',enabled:true}}));
  const versions:any[]=[];
  for(const minutes of [480,120]) {
    const version=await data(await page.request.post(`/api/decision/definitions/${code}/versions`,{data:{kind:'DECISION_TABLE',runtimeAdapter:'PLATFORM_DECISION_TABLE',contentJson:{hitPolicy:'FIRST',inputs:[{id:'amount',label:'交付金额',scope:'record',path:'data.amount',dataType:'decimal'}],outputs:[{id:'deadlineMinutes',label:'截止分钟数',dataType:'integer'}],rules:[{ruleId:'deadline',priority:10,when:{},then:{deadlineMinutes:minutes}}]},outputSchemaJson:{outputs:[{id:'deadlineMinutes',label:'截止分钟数',dataType:'integer'}]}}}));
    expect((await data(await page.request.post(`/api/decision/versions/${version.pid}/validate`))).valid).toBe(true);
    await data(await page.request.post(`/api/decision/versions/${version.pid}/publish`,{data:{impactAcknowledged:true,note:'Owned review versions'}}));versions.push(version);
  }
  await menu(page,'策略工作台');await professionalTab(page,'rollouts');
  const choicesPath=/\/api\/decision\/definitions(?:\?.*)?$/;
  await page.route(choicesPath,route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'503',message:'测试注入：发布规则目录暂不可用'})}));
  await expect(page.getByTestId('decision-rollout-monitor')).toBeVisible();await expect(page).toHaveURL(/[?&]tab=rollouts(?:&|$)/);await page.reload();
  await expect(page.getByTestId('rollout-definitions-error')).toBeVisible();await shot(page,'R06-catalog-error');
  await page.unroute(choicesPath);await page.getByTestId('rollout-definitions-error').getByRole('button',{name:'重新加载',exact:true}).click();
  await expect(page.getByTestId('rollout-definitions-error')).toHaveCount(0);
  await page.getByLabel('rollout-decision-code',{exact:true}).selectOption(code);
  await expect(page.getByTestId('rollout-advanced')).not.toHaveAttribute('open');
  await page.getByTestId('rollout-advanced').locator('summary').click();await expect(page.getByLabel('rollout-routing-key',{exact:true})).toBeVisible();await shot(page,'R06-advanced');
  await page.getByTestId('rollout-advanced').locator('summary').click();

  await page.getByLabel('rollout-baseline-version',{exact:true}).fill(String(versions[0].version));
  await page.getByLabel('rollout-candidate-version',{exact:true}).fill(String(versions[1].version));
  await page.getByLabel('rollout-percentage',{exact:true}).fill('100');
  const created=page.waitForResponse(r=>new URL(r.url()).pathname===`/api/decision/definitions/${code}/rollouts`&&r.request().method()==='POST');
  await page.getByTestId('rollout-create').click();const rollout=await data(await created);
  expect(rollout.baselineVersion).toBe(versions[0].version);expect(rollout.candidateVersion).toBe(versions[1].version);
  async function transition(action:string, expected:string) {
    const response=page.waitForResponse(r=>new URL(r.url()).pathname===`/api/decision/rollouts/${rollout.pid}/${action}`&&r.request().method()==='POST');
    await page.getByTestId(`rollout-${action}-${rollout.pid}`).click();
    if (action === 'rollback') {
      await expect(page.getByTestId('rollout-confirm-panel')).toBeVisible();
      await page.getByLabel('rollout-action-note',{exact:true}).fill(`本轮专属版本核验：${action}`);
      await shot(page,`R06-confirm-${action}`);
      await page.getByTestId('rollout-confirm-action').click();
    }
    expect((await data(await response)).status).toBe(expected);
    await expect(page.getByTestId(`rollout-row-${rollout.pid}`)).toContainText(({ACTIVE:'运行中',PAUSED:'已暂停',ROLLED_BACK:'已回滚'} as Record<string,string>)[expected]);
  }
  await transition('activate','ACTIVE');await transition('pause','PAUSED');await transition('activate','ACTIVE');await transition('rollback','ROLLED_BACK');
  await expect(page.getByTestId('rollout-audit-panel')).toContainText('本轮专属版本核验');await shot(page,'R06');
  await page.reload();await page.getByLabel('rollout-decision-code',{exact:true}).selectOption(code);await expect(page.getByTestId(`rollout-row-${rollout.pid}`)).toContainText('已回滚');
  const actual=await data(await page.request.get(`/api/decision/definitions/${code}/rollouts`));
  expect(actual.find((r:any)=>r.pid===rollout.pid).status).toBe('ROLLED_BACK');
  await writeFile(join(evidence!,'professional-rollout-receipt.json'),JSON.stringify({versions,actual},null,2));
});

test('SLA policies use business summaries and save fixed durations through the existing DSL command @critical',async({page})=>{
  const user=await login(page);
  const configs=await data(await page.request.get('/api/bpm/sla-configs'));
  const config=configs.find((item:any)=>item.targetKey==='tpm_standard_review');expect(config).toBeTruthy();
  try {
    await menu(page,'SLA 配置');
    await expect(page.getByTestId('sla-policy-workspace')).toBeVisible();
    await expect(page.getByTestId('sla-policy-workspace')).not.toContainText(/tpm_standard_review|PT2H/);
    await shot(page,'S07');
    await page.getByTestId('sla-policy-row').filter({hasText:config.name}).getByRole('button',{name:'查看策略',exact:true}).click();
    await expect(page.getByTestId('sla-policy-summary')).toContainText('交付负责人 · 常规验收');
    await expect(page.getByTestId('sla-policy-summary')).toContainText('交付验收 · 处理时限');await shot(page,'S08');
    await page.getByRole('tab',{name:'专业配置',exact:true}).click();await shot(page,'S12');
    await page.getByRole('button',{name:'编辑配置',exact:true}).click();
    await expect(page.getByTestId('sla-timing-config')).toBeVisible();
    await expect(page.getByTestId('select-trigger-target_type')).toContainText('节点');
    await expect(page.getByTestId('custom-section-sla_rule_binding')).not.toHaveAttribute('open','');
    const deadlineRule=page.getByTestId('sla-deadline-input').getByRole('combobox');
    await expect(deadlineRule).toHaveValue('tpm_acceptance_deadline');await expect(deadlineRule).toBeEnabled();await expect(deadlineRule).toContainText('交付验收 · 处理时限');await shot(page,'S09');
    await page.getByTestId('custom-section-sla_rule_binding').locator('summary').first().click();
    const mappingTab=page.getByTestId('decision-rule-section-tab-decision');
    if(await mappingTab.isVisible()) await mappingTab.click();
    expect(config.ruleBinding.decisionBinding.inputMappings.length).toBeGreaterThan(0);
    await expect(page.getByLabel('mapping-input-0',{exact:true})).toHaveValue(config.ruleBinding.decisionBinding.inputMappings[0].input);
    const mappedField=await page.getByLabel('mapping-field-0',{exact:true}).inputValue();
    expect(mappedField).not.toBe('');await page.getByLabel('mapping-field-0',{exact:true}).selectOption(mappedField);
    await expect(deadlineRule).toBeEnabled();await expect(deadlineRule).toContainText('交付验收 · 处理时限');
    await shot(page,'S12');
    const cataloguePath='**/api/decision/definitions?size=1000';
    await page.route(cataloguePath,route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'503',message:'测试注入：时限规则目录暂不可用'})}));
    await page.reload();await expect(page.getByTestId('sla-deadline-input').getByRole('alert')).toBeVisible();await expect(deadlineRule).toBeDisabled();await shot(page,'S09-catalog-error');
    await page.unroute(cataloguePath);await page.reload();await expect(deadlineRule).toBeEnabled();await expect(deadlineRule).toContainText('交付验收 · 处理时限');
    await page.getByRole('radio',{name:'固定时长',exact:true}).click();
    await page.getByLabel('时长单位',{exact:true}).selectOption('minutes');await page.getByLabel('时长数值',{exact:true}).fill('15');await shot(page,'S10');
    const saved=page.waitForResponse(response=>response.url().includes('/execute/admin:update_sla_config')&&response.request().method()==='POST');
    await page.getByRole('button',{name:'保存',exact:true}).click();const response=await saved;await data(response);
    const payload=response.request().postDataJSON().payload;expect(payload).toMatchObject({deadline_mode:'FIXED',deadline_value:'PT900S'});const binding=typeof payload.rule_binding==='string'?JSON.parse(payload.rule_binding):payload.rule_binding;expect(binding).toMatchObject({enabled:false,decisionBinding:{enabled:false}});expect(Object.keys(binding)).not.toContain('0');expect(canonicalInputMappings(binding.decisionBinding.inputMappings)).toEqual(canonicalInputMappings(config.ruleBinding.decisionBinding.inputMappings));
    const actual=await data(await page.request.get(`/api/bpm/sla-configs/${config.pid}`));expect(actual.deadlineMode).toBe('FIXED');expect(actual.deadlineValue).toBe('PT900S');expect(actual.ruleBinding.enabled).toBe(false);expect(canonicalInputMappings(actual.ruleBinding.decisionBinding.inputMappings)).toEqual(canonicalInputMappings(config.ruleBinding.decisionBinding.inputMappings));
    await menu(page,'SLA 配置');await expect(page.getByTestId('sla-policy-row').filter({hasText:config.name})).toContainText('15 分钟');await shot(page,'S11');
    await page.getByTestId('sla-policy-row').filter({hasText:config.name}).getByRole('button',{name:'编辑',exact:true}).click();await expect(page.getByLabel('时长数值',{exact:true})).toHaveValue('15');await expect(page.getByLabel('时长单位',{exact:true})).toHaveValue('minutes');
    const refused=await page.request.post('/api/meta/commands/execute/admin:update_sla_config',{data:{targetRecordPid:config.pid,payload:{deadline_mode:'FIXED',deadline_value:'invalid',business_calendar:false,rule_binding:{enabled:false}},clientRequestId:`invalid-duration:${config.pid}`}});
    expect(refused.ok()).toBe(false);expect((await data(await page.request.get(`/api/bpm/sla-configs/${config.pid}`))).deadlineValue).toBe('PT900S');
    const pid=await create(page,user,'华辰制造 · 固定时限配置联动验证',68000,'普通',true);await submit(page,pid,15,'tpm_standard_review');await decide(page,pid,'approve');
    await menu(page,'SLA 配置');await page.getByRole('button',{name:'新建策略',exact:true}).click();
    const temporaryName='配置保存验证 · 流程级时限';await page.locator('#name').fill(temporaryName);await page.getByTestId('select-trigger-target_type').click();await page.getByRole('option',{name:'流程',exact:true}).click();await page.locator('#target_key').fill('tpm_delivery_acceptance');
    await page.getByRole('radio',{name:'固定时长',exact:true}).click();await page.getByLabel('时长单位',{exact:true}).selectOption('hours');await page.getByLabel('时长数值',{exact:true}).fill('1');
    const created=page.waitForResponse(response=>response.url().includes('/execute/admin:create_sla_config')&&response.request().method()==='POST',{timeout:15000});await page.getByRole('button',{name:'保存',exact:true}).click();const createdResult=await data(await created);const temporaryPid=createdResult.data.recordPid;expect(temporaryPid).toBeTruthy();
    await menu(page,'SLA 配置');await page.getByTestId('sla-policy-row').filter({hasText:temporaryName}).getByRole('button',{name:'查看策略',exact:true}).click();await page.getByRole('tab',{name:'专业配置',exact:true}).click();
    const deleted=page.waitForResponse(response=>response.url().includes('/execute/admin:delete_sla_config')&&response.request().method()==='POST',{timeout:15000});await page.getByRole('button',{name:'删除策略',exact:true}).click();await expect(page.getByTestId('confirm-dialog')).toBeVisible();await shot(page,'S12-delete-confirm');await page.getByTestId('confirm-ok').click();await data(await deleted);
    await expect(page).toHaveURL(/\/p\/sla_config(?:[?#].*)?$/);await expect(page.getByTestId('sla-policy-workspace')).toBeVisible();await expect(page.getByTestId('sla-policy-row').filter({hasText:temporaryName})).toHaveCount(0);await shot(page,'S11-policy-creation-closed');
    expect((await data(await page.request.get('/api/bpm/sla-configs'))).some((row:any)=>row.pid===temporaryPid)).toBe(false);

  } finally {await data(await page.request.put(`/api/bpm/sla-configs/${config.pid}`,{data:config}));}
});

test('real decision drafts, readiness and boundary outputs from the rule workbench @critical', async({page})=>{
  await login(page);
  await menu(page,'策略工作台');
  await expect(page.getByTestId('decision-definition-list')).toBeVisible();
  await shot(page,'R01');
  await page.getByRole('button',{name:'交付验收 · 审批分级',exact:true}).click();
  await expect(page.getByTestId('strategy-dmn-panel')).toBeVisible();
  await shot(page,'R03');
  await page.getByTestId('strategy-workspace-tab-test').click();
  const inputs=page.getByTestId('strategy-test-inputs');
  for(const [amount,risk,issues,materials,route] of [
    ['68000','normal','0','true','standard'], ['68000','high','0','true','enhanced'],
    ['100000','normal','1','true','enhanced'], ['100000','normal','0','true','standard'],
    ['99999.99','normal','1','true','standard'], ['68000','normal','0','false','enhanced'],
  ]) {
    await inputs.getByRole('spinbutton',{name:'交付金额',exact:true}).fill(amount);
    await inputs.getByRole('combobox',{name:'风险等级',exact:true}).selectOption(risk);
    await inputs.getByRole('spinbutton',{name:'未解决问题数',exact:true}).fill(issues);
    await inputs.getByRole('combobox',{name:'材料内容已齐全',exact:true}).selectOption(materials);
    const executed=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/decision/test-run'&&r.request().method()==='POST');
    await page.getByTestId('strategy-run-test').click();
    const response=await executed;const result=await data(response);
    expect(result.outputs.route).toBe(route);
    expect(response.request().postDataJSON().context.record.data).toMatchObject({tpm_ac_amount:Number(amount),tpm_ac_open_issues:Number(issues),tpm_ac_materials_complete:materials==='true'});
    await expect(page.getByTestId('strategy-test-outputs')).toContainText(route==='standard'?'常规审核':'加强审核');
  }
  await expect(page.getByTestId('strategy-test-reason')).toContainText('材料内容已齐全');await expect(page.getByTestId('strategy-test-reason')).toContainText('待补充');await shot(page,'R04');
  for(const width of [1280,1440]) {
    await page.setViewportSize({width,height:1000});
    await page.getByTestId('strategy-workspace-tab-dmn').click();
    const editor=await page.getByTestId('strategy-dmn-panel').boundingBox();
    expect(editor!.width).toBeGreaterThan(width-540);
    expect(editor!.x+editor!.width).toBeLessThanOrEqual(width);
    await expect(page.getByTestId('strategy-test-panel')).not.toBeVisible();
    await shot(page,`R03-${width}`);
  }
  await page.getByText('字段与输出配置',{exact:true}).click();
  await page.getByLabel('input-label-0').fill('本次交付金额');
  await page.getByTestId('strategy-workspace-tab-test').click();
  await page.getByTestId('strategy-check-readiness').click();
  await expect(page.getByTestId('strategy-operation-status')).toHaveAttribute('data-tone','success');
  const published=page.waitForResponse(r=>/\/api\/decision\/versions\/[^/]+\/publish$/.test(new URL(r.url()).pathname)&&r.request().method()==='POST');
  await page.getByTestId('strategy-publish').click();expect((await data(await published)).status).toBe('PUBLISHED');
  await page.reload();
  await page.getByTestId('strategy-business-decision').selectOption('tpm_acceptance_route');
  await page.getByTestId('strategy-workspace-tab-dmn').click();
  await page.getByText('字段与输出配置',{exact:true}).click();
  await expect(page.getByLabel('input-label-0',{exact:true})).toHaveValue('本次交付金额');
  await shot(page,'rule-published-roundtrip');
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
  await menu(page,'流程监控');await page.getByTestId('sla-professional-diagnostics').locator('summary').first().click();await expect(page.getByTestId('sla-strategy-chain')).toContainText('交付验收');await shot(page,'d12');
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
  test.setTimeout(240000);const user=await login(page);
  const publishedDesign = await publishAcceptanceDesign(page);
  const publishedAction = await configureAcceptanceModelAction(page, true);
  const configs=await data(await page.request.get('/api/bpm/sla-configs'));
  const config=configs.find((item:any)=>item.targetKey==='tpm_enhanced_review');expect(config).toBeTruthy();
  const change=async(value:any)=>data(await page.request.put(`/api/bpm/sla-configs/${config.pid}`,{data:value}));
  await change({...config,name:'加强审核 · 30秒加速验证',deadlineMode:'FIXED',deadlineValue:'PT30S',ruleBinding:{...config.ruleBinding,enabled:false}});
  try {
    const prefix=process.env.PW_ACCEPTANCE_PROJECT_NAME ? `${process.env.PW_ACCEPTANCE_PROJECT_NAME} · 时限复盘` : 'SLA 加速验证 · 30秒交付验收';
    const previous=(await dynamicRecords(page,'tpm_acceptance')).records.filter((record:any)=>record.tpm_ac_name.startsWith(prefix));
    const name=`${prefix} · 第${previous.length+1}次`;const pid=await create(page,user,name,100000,'高风险',true);
    const sla=await submit(page,pid,0.5,'tpm_enhanced_review');await shot(page,'d10');
    let expired:any;
    await expect.poll(async()=>{expired=await data(await page.request.get(`/api/bpm/monitor/sla-records/${sla.pid}`));return expired.status;},{timeout:65000,intervals:[3000]}).toBe('overdue');
    expect(expired.currentWarningLevel).toBe(2);expect(expired.warningHistory.map((item:any)=>item.level)).toEqual([1,2]);expect(expired.warningHistory.every((item:any)=>Number.isFinite(Date.parse(item.triggeredAt)))).toBe(true);
    let actual:any[]=[];
    await expect.poll(async()=>{const notifications=await data(await page.request.get('/api/bpm/notify/received?type=urge'));actual=notifications.filter((item:any)=>item.taskId===sla.taskId);return actual.length;},{timeout:10000}).toBe(2);expect(actual.every((item:any)=>String(item.processInstanceId)===sla.processInstanceId)).toBe(true);
    let action:any;await expect.poll(async()=>{const logs=await data(await page.request.get(`/api/event-policy/action-logs?size=100&correlationId=sla:${sla.pid}`));action=logs.find((item:any)=>item.correlationId===`sla:${sla.pid}`);return action?.status;},{timeout:10000}).toBe('SUCCESS');
    expect(action.resultPayload.sentCount).toBeGreaterThan(0);
    await writeFile(join(evidence!,'sla-notification-evidence.json'),JSON.stringify({notifications:actual,action},null,2));
    await menu(page,'流程监控');await page.getByTestId('sla-refresh').click();await expect(page.getByRole('main')).toContainText('SLA');await expect(page.getByTestId('sla-record-row').filter({hasText:name})).toContainText('逾期');await shot(page,'S01');await page.getByTestId('sla-record-row').filter({hasText:name}).getByTestId('sla-record-detail').click();await expect(page.getByTestId('sla-item-detail')).toContainText('已执行成功');await expect(page.getByTestId('sla-item-detail')).not.toContainText(/NaN|undefined/);await expect(page.getByTestId('sla-item-detail')).toContainText('第 1 级');await shot(page,'S02');
    await openRecord(page,name);
    await expect(page.getByTestId('bpm-sla-summary')).toContainText('已超时');
    await expect(page.getByTestId('bpm-sla-summary')).toContainText('已触发 2 次');
    await expect(page.getByTestId('bpm-sla-summary')).toContainText('不会自动通过或驳回');
    await shot(page,'S03-overdue-acceptance');
    await decide(page,pid,'approve');
    await page.reload();
    await expect(page.getByTestId('bpm-sla-summary')).toContainText('超时办结');
    await expect(page.getByTestId('bpm-sla-summary')).toContainText('已停止计时');
    await shot(page,'S04-completed-acceptance');
    const completed=await data(await page.request.get(`/api/bpm/monitor/sla-records/${sla.pid}`));
    const closedAt=Date.parse(completed.completedTime);
    await expect.poll(async()=>{const next=await data(await page.request.get(`/api/bpm/monitor/sla-records/${sla.pid}`));expect(next.status).toBe('completed');expect(next.warningHistory).toEqual(completed.warningHistory);return Date.now()-closedAt;},{timeout:25000,intervals:[3000]}).toBeGreaterThan(16000);
    const after=await data(await page.request.get('/api/bpm/notify/received?type=urge'));expect(after.filter((item:any)=>item.taskId===sla.taskId)).toHaveLength(2);
    const actionAfter=await data(await page.request.get(`/api/event-policy/action-logs?correlationId=sla:${sla.pid}`));expect(actionAfter).toHaveLength(1);
    receipts.push({accelerated:true,sla:expired,completed,publishedAction,publishedDesign});await writeFile(join(evidence!,'acceptance-runtime-receipts.json'),JSON.stringify(receipts,null,2));
  } finally { await change(config); }
});


test('readonly viewers and unassigned reviewers cannot change another acceptance @critical',async({page,browser})=>{
  const admin=await login(page);
  const issue = new Intl.DateTimeFormat('zh-CN',{hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(new Date());
  const name=`华辰制造 · 验收协作权限验证 · ${issue}`;
  const pid=await create(page,admin,name,68000,'普通',true);const sla=await submit(page,pid,480,'tpm_standard_review');
  const tree=await data(await page.request.get('/api/permissions/tree'));const ids=new Map<string,number>();
  const collect=(nodes:any[])=>nodes.forEach(node=>{ids.set(node.code,node.id);collect(node.children||[]);});collect(tree);
  for(const reviewer of [false,true]) {
    const code=`acceptance_${reviewer?'unassigned':'readonly'}_${Date.now()}`;
    const role=await data(await page.request.post('/api/roles',{data:{code,name:code,type:'custom',status:'active',scopeType:'tenant',defaultDataScopeType:'all'}}));
    const grants=['tpm.acceptance.read','model.tpm_acceptance.read','tpm.project.read','model.tpm_project.read','decision.definition.read','decision.policy.read',...(reviewer?['tpm.acceptance.manage','bpm.process.read','bpm.process.execute','tdv.delivery.register']:[])];
    await data(await page.request.put(`/api/permissions/matrix/${role.pid}/batch`,{data:grants.map(code=>{expect(ids.has(code),code).toBe(true);return {permissionId:ids.get(code),granted:true};})}));
    const email=`${code}@e2e.local`;
    await data(await page.request.post('/api/admin/users',{data:{email,displayName:reviewer?'验收协作成员':'交付只读成员',initialPassword:process.env.ADMIN_PASSWORD,roleCodes:[code],sendInviteEmail:false}}));
    const context=await browser.newContext({baseURL:requireRuntimeUrl('base'),storageState:{cookies:[],origins:[]},locale:'zh-CN',viewport:{width:1440,height:1000}});
    try {
      const actor=await context.newPage();const actorUser=await login(actor,email,String(admin.tenantId));
      const identity=await data(await actor.request.get('/api/auth/me'));expect(identity.permissions.roles.map((r:any)=>r.code)).not.toContain('tenant_admin');
      await openRecord(actor,name);
      await expect(actor.getByRole('tab',{name:'运行记录',exact:true})).toHaveCount(0);
      await expect(actor.getByRole('tab',{name:'核验结果',exact:true})).toHaveCount(0);
      if(reviewer) {
        await expect(actor.getByTestId('bpm-operations-approve')).toBeDisabled();
        await expect(actor.getByTestId('bpm-sla-summary')).toContainText('没有查看处理时限的权限');
        expect((await actor.request.get(`/api/bpm/monitor/instances/${sla.processInstanceId}/sla`)).status()).toBe(403);
        const refused=await actor.request.post(`/api/bpm/tasks/${sla.taskId}/approve`,{data:{comment:'An unassigned reviewer must be refused',variables:{}}});expect(refused.status()).toBe(403);
      } else {
        await expect(actor.getByTestId('toolbar-btn-edit')).toHaveCount(0);
        await expect(actor.getByTestId('toolbar-btn-submit_acceptance')).toHaveCount(0);
        await expect(actor.getByTestId('bpm-panel')).toHaveCount(0);
        const refused=await actor.request.post('/api/meta/commands/execute/tpm:accept_acceptance',{data:{operationType:'update',targetRecordPid:pid,payload:{}}});expect(refused.status()).toBe(403);
        await menu(actor,'策略工作台');
        await actor.getByTestId('ddl-open-tpm_acceptance_route').click();
        await expect(actor.getByTestId('strategy-save-draft')).toBeDisabled();
        await expect(actor.getByTestId('strategy-publish')).toBeDisabled();
        await expect(actor.getByRole('status').filter({hasText:'只读查看'})).toBeVisible();
        await shot(actor,'A01');
        const configs=await data(await page.request.get('/api/bpm/sla-configs'));const ownedConfig=configs.find((item:any)=>item.targetKey==='tpm_standard_review');expect(ownedConfig).toBeTruthy();
        expect((await actor.request.get('/api/bpm/sla-configs')).status()).toBe(403);
        expect((await actor.request.put(`/api/bpm/sla-configs/${ownedConfig.pid}`,{data:{...ownedConfig,deadlineMode:'FIXED',deadlineValue:'PT1M'}})).status()).toBe(403);
        expect((await actor.request.post('/api/meta/commands/execute/admin:update_sla_config',{data:{targetRecordPid:ownedConfig.pid,payload:{deadline_mode:'FIXED',deadline_value:'PT1M'}}})).status()).toBe(403);
        expect((await data(await page.request.get(`/api/bpm/sla-configs/${ownedConfig.pid}`))).deadlineValue).toBe(ownedConfig.deadlineValue);
        await openRecord(actor,name);
      }
      expect((await row(page,pid)).tpm_ac_status).toBe('submitted');await shot(actor,reviewer?'d13':'d13-readonly');
      if(reviewer) {
        const assignedName=`华辰制造 · 指定协作成员审批 · ${issue}`;
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
  const imported = await page.request.post('/api/plugins/import/import-directory-sync', {data:{
    path:join(process.env.AURA_CORE_PROJECT_ROOT!, 'plugins/project-management'), conflictStrategy:'OVERWRITE',deferReferenceValidation:true
  }});
  expect(imported.ok()).toBe(true);
  expect((await imported.json()).success).toBe(true);
  // Explicit fixture update affects new submissions only; existing instance snapshots stay untouched.
  const definitions = await data(await page.request.get('/api/bpm/process-definitions?size=1000'));
  const definition = definitions.records.find((row:any) => row.processKey === 'tpm_delivery_acceptance');
  expect(definition?.pid).toBeTruthy();
  const bundle = JSON.parse(await readFile(join(process.env.AURA_CORE_PROJECT_ROOT!, 'plugins/project-management/examples/acceptance-workflow.bpm-package.json'), 'utf8'));
  await data(await page.request.put(`/api/bpm/process-definitions/${definition.pid}/form-bindings`, {data:bundle.processDefinition.formBindings}));
  const before = await data(await page.request.get('/api/dynamic/tpm_acceptance/list'));
  const name = `华辰制造 · 第${before.total+1}批交付验收`;
  const pid = await create(page, user, name, 68000, '高风险', true);
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
  for (const forged of [{businessData:{tpm_ac_amount:1}}, {saveStrategy:'business_only'}, {variables:{decision:'approve',data:{tpm_ac_amount:1}}}]) {
    const denied = await page.request.post(`/api/bpm/forms/task/${taskId}/submit`, {data:{variables:{decision:'approve'},...forged}});
    expect(denied.ok()).toBe(false);
    expect((await row(page,pid)).tpm_ac_status).toBe('submitted');
    expect((await row(page,pid)).tpm_ac_amount).toBe(68000);
  }
  await shot(page, 'form-submitted-frozen');
  // Republish setup via the public API; the frozen form is verified through a real browser reload.
  const current = await data(await page.request.get('/api/pages/key/tpm_acceptance_form'));
  const changedBlocks = structuredClone(current.blocks);
  changedBlocks.find((block:any) => block.id==='identity').title = {'zh-CN':'新版验收范围','en':'Updated acceptance scope'};
  changedBlocks.find((block:any) => block.id==='risk').title = {'zh-CN':'新版风险与审批','en':'Updated risk and review'};
  const publishBlocks = async (blocks:any[]) => {
    let managed = await data(await page.request.get(`/api/pages/${current.pid}`));
    if (String(managed.status).toLowerCase()==='published') {
      await data(await page.request.post(`/api/pages/${current.pid}/unpublish`, {data:{}}));
      managed = await data(await page.request.get(`/api/pages/${current.pid}`));
    }
    await data(await page.request.put(`/api/pages/${current.pid}`, {data:{blocks,rowVersion:managed.rowVersion}}));
    await data(await page.request.post(`/api/pages/${current.pid}/publish`, {data:{}}));
  };
  try {
    await publishBlocks(changedBlocks);
    const changedBinding = structuredClone(bundle.processDefinition.formBindings);
    for (const binding of Object.values(changedBinding) as any[]) binding.fieldPermissions.tpm_ac_scope = 'hidden';
    await data(await page.request.put(`/api/bpm/process-definitions/${definition.pid}/form-bindings`, {data:changedBinding}));
    await page.reload();
    await expect(bound).toHaveAttribute('data-version-source','frozen');
    await expect(bound.getByTestId('object-section-identity')).toContainText('验收范围');
    await expect(bound).toContainText('会员迁移、权限配置和培训交付完成');
    await expect(bound).not.toContainText('新版验收范围');
    const after = await data(await page.request.get(`/api/bpm/forms/instance/${instanceId}?formRef=tpm_acceptance_form`));
    expect(after.schemaSnapshot).toEqual(frozen.schemaSnapshot);
    await shot(page,'form-republished-old-instance');
    const nextPid = await create(page, user, `华辰制造 · 第${before.total+2}批交付验收`, 68000, '高风险', true);
    await submit(page, nextPid, 120, 'tpm_enhanced_review');
    await expect(bound).toHaveAttribute('data-version-source','frozen');
    await expect(bound).toContainText('新版风险与审批');
    await expect(bound).not.toContainText('会员迁移、权限配置和培训交付完成');
    const nextRecord = await row(page, nextPid);
    const fresh = await data(await page.request.get(`/api/bpm/forms/instance/${nextRecord.tpm_ac_process}?formRef=tpm_acceptance_form`));
    expect(fresh.schemaSnapshot.blocks.find((block:any)=>block.id==='identity').title['zh-CN']).toBe('新版验收范围');
    expect(fresh.record).not.toHaveProperty('tpm_ac_scope');
    expect(fresh.fieldPermissions.tpm_ac_scope).toBe('hidden');
    const newTasks = await data(await page.request.get(`/api/bpm/tasks/by-process/${nextRecord.tpm_ac_process}`));
    const hiddenTask = await data(await page.request.get(`/api/bpm/forms/task/${newTasks[0].instanceId}`));
    expect(hiddenTask.processVariables).toEqual({});
    const hiddenDenied = await page.request.post(`/api/bpm/forms/task/${newTasks[0].instanceId}/submit`, {data:{businessData:{tpm_ac_scope:'forged hidden field'},variables:{decision:'approve'}}});
    expect(hiddenDenied.status()).toBe(403);
    expect((await row(page,nextPid)).tpm_ac_status).toBe('submitted');
    await shot(page,'form-new-submission-hidden');
    await writeFile(join(evidence!, 'associated-form-receipt.json'), JSON.stringify({pid,instanceId,taskId,frozen,after,nextPid,fresh,hiddenDeniedStatus:hiddenDenied.status()}, null,2));
  } finally {
    await publishBlocks(current.blocks);
    await data(await page.request.put(`/api/bpm/process-definitions/${definition.pid}/form-bindings`, {data:bundle.processDefinition.formBindings}));
  }
  await openRecord(page,name);
  await decide(page,pid,'approve');
  await page.getByRole('tab',{name:'审批记录',exact:true}).click();
  await expect(page.getByTestId('bpm-history-container')).toContainText('审批通过');
  await expect(page.getByTestId('bpm-history-container')).toContainText('交付范围、完成标准和材料一致，批准验收。');
  await expect(page.getByTestId('bpm-history-container')).not.toContainText('task_complete');
  await shot(page,'form-approved-history');
  await page.getByRole('tab',{name:'验收详情',exact:true}).click();
  await expect(page.getByTestId('bpm-bound-form')).toHaveAttribute('data-version-source','frozen');
  await page.reload();
  await expect(page.getByTestId('bpm-bound-form').getByTestId('dsl-form-readonly')).toBeVisible();
  await shot(page,'form-approved-readonly');
});

async function publishAcceptanceDesign(page: Page) {
  await menu(page,'流程定义');
  await page.getByTestId('list-search-input').fill('tpm_delivery_acceptance');
  await page.getByTestId('list-search-input').press('Enter');
  await page.getByRole('row').filter({hasText:'tpm_delivery_acceptance'}).getByText('tpm_delivery_acceptance',{exact:true}).click();
  await page.getByTestId('toolbar-btn-open_bpmn_designer').click();
  await expect(page).toHaveURL(/\/bpm\/designer\?pid=/);
  let definitionPid = new URL(page.url()).searchParams.get('pid');
  expect(definitionPid).toBeTruthy();
  const originalPid = definitionPid;
  const original = await data(await page.request.get(`/api/bpm/process-definitions/${originalPid}`));
  expect(original.status).toBe('deployed');
  await expect(page.locator('.react-flow__node')).toHaveCount(8);
  await page.locator('.react-flow__node[data-id="tpm_enhanced_review"]').click();
  const labelInput = page.getByTestId('node-label-input');
  const reviewLabel = (await labelInput.inputValue()) === '验收负责人 · 加强审核'
    ? '加强审核 · 验收负责人' : '验收负责人 · 加强审核';
  await labelInput.fill(reviewLabel);
  if (await page.getByTestId('bpmn-drawer-backdrop').isVisible()) {
    await page.getByTestId('bpmn-close-inspector').click();
  }
  await page.getByTestId('bpmn-toolbar').getByTestId('bpmn-toolbar-btn-save').click();
  await expect(page.getByTestId('bpmn-save-dialog-panel')).toBeVisible();
  await expect(page.getByTestId('bpmn-save-version-notice')).toContainText('已有实例保持不变');
  await shot(page,'BPM00-new-version-confirmation');
  const saved = page.waitForResponse(r => new URL(r.url()).pathname === '/api/bpm/process-definitions/key/tpm_delivery_acceptance/versions' && r.request().method()==='POST');
  await page.getByTestId('bpmn-save-dialog-submit').click();
  const draft = await data(await saved);
  definitionPid = draft.pid;
  expect(definitionPid).not.toBe(originalPid);
  expect(draft.version).toBe(original.version + 1);
  expect(draft.status).toBe('draft');
  await expect(page).toHaveURL(new RegExp(`/bpm/designer\\?pid=${definitionPid}$`));
  const historical = await data(await page.request.get(`/api/bpm/process-definitions/${originalPid}`));
  expect(historical.designerJson).toBe(original.designerJson);
  expect(historical.bpmnContent).toBe(original.bpmnContent);
  expect(historical.status).toBe('deployed');
  await page.reload();
  await expect(page.locator('.react-flow__node')).toHaveCount(8);
  await expect(page.locator('.react-flow__node[data-id="tpm_enhanced_review"]')).toContainText(reviewLabel);
  await page.getByRole('button',{name:/fit view/i}).click();
  await shot(page,'BPM01-persisted-design');
  const deployed = page.waitForResponse(r => new URL(r.url()).pathname === `/api/bpm/process-definitions/${definitionPid}/deploy` && r.request().method()==='POST');
  await page.getByTestId('bpmn-btn-deploy').click();
  expect((await data(await deployed)).status).toBe('deployed');
  const xml = await data(await page.request.get(`/api/bpm/process-definitions/${definitionPid}/bpmn`));
  expect(xml).toContain('commandCode="tpm:accept_acceptance"');
  expect(xml).toContain('assigneeType="expression"');
  expect(xml).toContain('assignee="#tpm_ac_reviewer"');
  expect(xml).toContain('targetRecordPid="${processBusinessKey}"');
  await shot(page,'BPM02-published-design');
  return {definitionPid,originalPid,version:draft.version,originalUnchanged:true,node:'tpm_enhanced_review',label:'验收负责人 · 加强审核',compiledCommand:'tpm:accept_acceptance'};
}

async function configureAcceptanceModelAction(page: Page, publish = false) {
  await menu(page,'事件策略');
  await page.getByTestId('epl-open-designer-tpm_acceptance_registered').click();
  await expect(page).toHaveURL(/policy=tpm_acceptance_registered/);
  await page.getByTestId('epd-step-actions').click();
  const action=page.getByTestId('epd-action-0');
  await action.getByRole('textbox',{name:'目标模型',exact:true}).fill('项目');
  await action.getByRole('button',{name:/^项目(?:\s|$)/}).click();
  await action.getByRole('textbox',{name:'业务操作',exact:true}).fill('登记验收通过');
  await action.getByRole('button',{name:/^登记验收通过/}).click();
  await action.getByRole('combobox',{name:'目标记录来源',exact:true}).click();
  await expect(page.getByRole('option')).toHaveCount(1);
  await page.getByRole('option',{name:'交付验收 · 所属项目',exact:true}).click();
  await action.getByRole('combobox',{name:'取值方式',exact:true}).click();
  await page.getByRole('option',{name:'业务字段',exact:true}).click();
  await action.getByRole('combobox',{name:/^设置为/}).click();
  await page.getByRole('option',{name:'当前业务记录',exact:true}).click();
  await expect(action).toContainText('验收记录');
  await shot(page,'AC01');
  await page.getByTestId('epd-step-publish').click();
  const saved=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/event-policy/definitions/tpm_acceptance_registered/versions'&&r.request().method()==='POST');
  await page.getByTestId('epd-save-draft').click();
  const response=await saved;const version=await data(response);
  const payload=response.request().postDataJSON().rulesJson[0].actions[0].payload;
  expect(payload).toEqual({modelCode:'tpm_project',commandCode:'tdv:register_acceptance',targetRecordPid:'${record.data.tpm_ac_project}',params:{acceptancePid:'${record.recordPid}'}});
  expect(version.status).toBe('DRAFT');
  await page.reload();
  await page.getByTestId('epd-step-actions').click();
  await expect(page.getByTestId('epd-action-0').getByRole('textbox',{name:'业务操作',exact:true})).toHaveValue('登记验收通过');
  await expect(page.getByTestId('epd-action-0')).toContainText('所属项目');
  await page.getByTestId('epd-action-0').scrollIntoViewIfNeeded();await shot(page,'R12');
  if (publish) {
    await page.getByTestId('epd-step-publish').click();
    const validated = page.waitForResponse(r => new URL(r.url()).pathname === `/api/event-policy/versions/${version.pid}/validate` && r.request().method() === 'POST');
    await page.getByTestId('epd-validate-version').click();
    expect((await data(await validated)).status).toBe('VALIDATED');
    const published = page.waitForResponse(r => new URL(r.url()).pathname === `/api/event-policy/versions/${version.pid}/publish` && r.request().method() === 'POST');
    await page.getByTestId('epd-publish-version').click();
    expect((await data(await published)).status).toBe('PUBLISHED');
    await page.reload();
    await page.getByTestId('epd-step-publish').click();
    await expect(page.getByTestId('epd-publish-status')).toContainText(/已发布|PUBLISHED/);
    await shot(page,'AC02-published');
  }
  return {policyCode:'tpm_acceptance_registered', versionPid:version.pid, payload, published:publish};
}

test('model actions select real metadata, save typed references and reopen the same policy @critical', async({page})=>{
  await login(page);
  await configureAcceptanceModelAction(page);
});





test('rule center primary and professional views remain reachable through their own entries @critical',async({page})=>{
  await login(page);await menu(page,'策略工作台');
  for(const [tab,image,error] of [['definitions','R01','ddl-error'],['policies','R09','epl-error'],['logs','R15',''],['dashboard','R18','dd-error'],['model','A03','dm-error'],['permissions','A04','pm-error'],['connectors','A05','cl-error'],['rollouts','R06',''],['tables','R03-dmn-tools','']]) {
    const entry=page.getByTestId(`doc-tab-${tab}`);
    if(!(await entry.isVisible()))await page.locator('details.decisionops-professional-entry > summary').click();
    await entry.click();await expect(page.getByTestId(`doc-panel-${tab}`)).toBeVisible();
    await expect(page.getByTestId(`doc-panel-${tab}`)).not.toContainText('Failed to load');
    if(error)await expect(page.getByTestId(error)).toHaveCount(0);
    if(tab==='dashboard') {
      await expect(page.getByTestId('decision-dashboard')).toBeVisible();
      const first=await page.getByTestId('dd-card-definitions').boundingBox();
      const second=await page.getByTestId('dd-card-policies').boundingBox();
      expect(first).toBeTruthy();expect(second).toBeTruthy();
      expect(Math.abs(first!.y-second!.y)).toBeLessThan(2);
      expect(second!.x).toBeGreaterThanOrEqual(first!.x+first!.width-2);
    }
    await expect(page.locator('details.decisionops-professional-entry')).not.toHaveAttribute('open');
    if(tab==='logs'){await expect(page.getByTestId('elta-business-results')).toBeVisible();await expect(page.getByTestId('elta-filters')).toHaveCount(0);}
    await shot(page,['R01','A03','A04','A05','R06'].includes(image)?`${image}-navigation`:image);
  }
});


test('SLA business summary exposes loading errors and empty reads and recovers explicitly @critical', async ({page}) => {
  const user=await login(page);
  const prefix='交付验收 · 时限读取与恢复验证';
  const previous=(await dynamicRecords(page,'tpm_acceptance')).records.filter((record:any)=>record.tpm_ac_name.startsWith(prefix));
  const name=`${prefix} · 第${previous.length+1}次`;
  const pid=await create(page,user,name,1000,'普通',true);
  const sla=await submit(page,pid,480,'tpm_standard_review');
  const endpoint=`**/api/bpm/monitor/instances/${sla.processInstanceId}/sla`;
  let release!:()=>void;
  const pending=new Promise<void>(resolve=>{release=resolve;});
  await page.route(endpoint,async route=>{
    await pending;
    await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'503',message:'Injected monitoring outage'})});
  });
  await page.reload();
  await expect(page.getByTestId('bpm-sla-summary')).toContainText('正在读取处理时限');
  await shot(page,'S05-loading-injected');
  release();
  await expect(page.getByTestId('bpm-sla-summary').getByRole('alert')).toContainText('读取失败');
  await expect(page.getByTestId('bpm-sla-summary')).not.toContainText('没有 SLA');
  await shot(page,'S06-error-injected');
  await page.unroute(endpoint);
  await page.getByTestId('bpm-sla-summary').getByRole('button',{name:'刷新状态',exact:true}).click();
  await expect(page.getByTestId('bpm-sla-record')).toContainText('计时中');
  await shot(page,'S07-recovered-real');
  await page.route(endpoint,route=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:'0',data:[]})}));
  await page.getByTestId('bpm-sla-summary').getByRole('button',{name:'刷新状态',exact:true}).click();
  await expect(page.getByTestId('bpm-sla-summary')).toContainText('此审批没有 SLA 计时记录');
  await shot(page,'S08-empty-injected');
  await page.unroute(endpoint);
  await page.getByTestId('bpm-sla-summary').getByRole('button',{name:'刷新状态',exact:true}).click();
  await expect(page.getByTestId('bpm-sla-record')).toContainText('计时中');
});
