import { test, expect, type Page } from '@playwright/test';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { navigateToMenuByClick } from '../helpers';
import { loginDeliveryMember } from '../../helpers/delivery-session';

test.use({ storageState: { cookies: [], origins: [] } });
const evidence = process.env.AURA_EVIDENCE_DIR;
if (!evidence) throw Error('Bind the owned evidence round');
async function start(page: Page) {
  await navigateToMenuByClick(page, ['客户交付案例', '开始体验']);
  await expect(page.getByRole('main')).toContainText('把一次客户交付完整跑通');
}
async function shot(page: Page, id: string) {
  await mkdir(evidence!, { recursive: true });
  await expect(page.getByRole('main')).not.toContainText(/加载中|加载活动记录/);
  await page.screenshot({ path: join(evidence!, id + '.png'), fullPage: true });
}

async function cleanSnapshot(page: Page) {
  if (process.env.PW_REQUIRE_CLEAN_SHOWCASE !== '1') return undefined;
  const fixture = JSON.parse(await readFile(process.env.DELIVERY_FIXTURE_PATH!, 'utf8'));
  const snapshot: Record<string, unknown> = {};
  for (const [model, total] of [['tpm_customer', 8], ['tpm_project', 8], ['tpm_task', 24], ['tpm_milestone', 32]] as const) {
    const response = await page.request.get(`/api/dynamic/${model}/list?pageSize=100`);
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(String(body.code)).toBe('0');
    expect(body.data.total, model).toBe(total);
    expect(body.data.records).toHaveLength(total);
    snapshot[model] = body.data.records.map((row: Record<string, unknown>) =>
      Object.fromEntries(Object.entries(row).filter(([key]) => key === 'pid' || key.startsWith('tpm_'))),
    ).sort((a: { pid: string }, b: { pid: string }) => a.pid.localeCompare(b.pid));
    if (model === 'tpm_project') {
      expect(body.data.records.map((row: { pid: string }) => row.pid).sort())
        .toEqual(fixture.projects.map((row: { pid: string }) => row.pid).sort());
      expect(body.data.records.map((row: { tpm_pj_name: string }) => row.tpm_pj_name).sort())
        .toEqual(fixture.projects.map((row: { name: string }) => row.name).sort());
    }
  }
  return snapshot;
}


/** Optional deep recovery input is captured before backup; navigation-only evidence is labeled separately. */
async function restoredBusiness(page: Page, readonly: boolean) {
  if (process.env.ENTERPRISE_SCENARIO_PROFILE !== 'showcase-recovery') return;
  const input=process.env.DELIVERY_RECOVERY_CASE_PATH;
  if (!input) {
    await writeFile(join(evidence!,`recovery-business-${readonly?'viewer':'admin'}.json`),JSON.stringify({scope:'navigation-only',originalRecordAssertions:'did_not_run'}));
    return;
  }
  const expected=JSON.parse(await readFile(input,'utf8'));
  expect(expected.format).toBe('auraboot.recovery-business-case.v1');
  expect(expected.tenantId).toBe(process.env.AURA_ADMIN_TENANT_ID);
  const get=async(route:string)=>{const response=await page.request.get(route);expect(response.status()).toBe(200);const body=await response.json();expect(String(body.code)).toBe('0');return body.data;};
  const actual=await get('/api/dynamic/tpm_acceptance/'+expected.acceptance.pid);
  for(const [key,value] of Object.entries(expected.acceptance.fields))expect(actual[key],key).toEqual(value);
  const project=await get('/api/dynamic/tpm_project/'+expected.project.pid);
  expect(Number(project.tpm_pj_accepted_total)).toBe(expected.project.acceptedTotal);
  await navigateToMenuByClick(page,['项目交付','交付验收']);
  const row=page.getByRole('row').filter({hasText:expected.acceptance.name});await expect(row).toHaveCount(1);
  await row.getByRole('button',{name:/查看|详情/}).click();
  await expect(page).toHaveURL(url=>url.pathname==='/p/tpm_acceptance/view/'+expected.acceptance.pid);
  await expect(page.getByTestId('dsl-form-readonly')).toBeVisible();
  await expect(page.getByTestId('dsl-form-readonly')).toContainText(expected.acceptance.fields.tpm_ac_scope);
  for(const file of expected.files) {
    await expect(page.getByRole('main')).toContainText(file.name);
    expect(file.url).toMatch(/^\/api\/file\/download\/[0-9A-Z]{26}$/);
    const downloaded=await page.request.get(file.url);expect(downloaded.status()).toBe(200);
    expect(createHash('sha256').update(await downloaded.body()).digest('hex')).toBe(file.sha256);
    const link=page.getByTestId('attachment-download-'+expected.files.indexOf(file));
    await expect(link).toHaveAttribute('href',file.url);
    const event=page.waitForEvent('download');await link.click();const download=await event;
    expect(await download.failure()).toBeNull();expect(download.suggestedFilename()).toBe(file.name);
    const local=await download.path();expect(local).toBeTruthy();
    expect(createHash('sha256').update(await readFile(local!)).digest('hex')).toBe(file.sha256);
  }
  if(readonly) {
    await expect(page.getByTestId('toolbar-btn-edit')).toHaveCount(0);
    const denied=await page.request.post('/api/meta/commands/execute/tpm:update_acceptance',{data:{operationType:'update',targetRecordPid:expected.acceptance.pid,payload:{tpm_ac_amount:1}}});
    expect(denied.status()).toBe(403);
    expect((await get('/api/dynamic/tpm_acceptance/'+expected.acceptance.pid)).tpm_ac_amount).toBe(actual.tpm_ac_amount);
  } else {
    await page.getByRole('tab',{name:'审批记录',exact:true}).click();
    await expect(page.getByTestId('bpm-history-container')).toContainText(expected.approval.actor);
    await expect(page.getByTestId('bpm-history-container')).toContainText(expected.approval.comment);
    await navigateToMenuByClick(page,['项目交付','交付验收']);
    await page.getByRole('row').filter({hasText:expected.sla.acceptanceName}).getByRole('button',{name:/查看|详情/}).click();
    await expect(page).toHaveURL(url=>url.pathname==='/p/tpm_acceptance/view/'+expected.sla.acceptancePid);
    await expect(page.getByTestId('bpm-sla-summary')).toContainText('超时办结');
    await expect(page.getByTestId('bpm-sla-summary')).toContainText('已停止计时');
    await expect(page.getByTestId('bpm-sla-summary')).toContainText('已触发 2 次');
    const sla=await get('/api/bpm/monitor/sla-records/'+expected.sla.pid);
    for(const [key,value] of Object.entries(expected.sla.fields))expect(sla[key],key).toEqual(value);
  }
  await shot(page,readonly?'RC02-original-readonly-and-files':'RC01-original-history-and-sla');
  await writeFile(join(evidence!,`recovery-business-${readonly?'viewer':'admin'}.json`),JSON.stringify({scope:'original-business-records',status:'passed',acceptancePid:expected.acceptance.pid,projectPid:expected.project.pid,slaPid:expected.sla.pid,files:expected.files.map((file:any)=>({name:file.name,sha256:file.sha256})),readonly},null,2));
}

test('PK complete business entry and secondary professional actions navigate through real DSL menus', async ({
  page,
}) => {
  await loginDeliveryMember(page);
  const before = await cleanSnapshot(page);
  await start(page);
  await expect(page.getByRole('main')).toContainText('04 AI 整理与经营分析');
  await shot(page, 'PK01');
  await page.getByRole('main').getByRole('button', { name: '专业配置与诊断', exact: true }).scrollIntoViewIfNeeded();
  await shot(page, 'PK10');
  await page.getByRole('main').getByRole('heading', { name: '客户交付 · 从项目到复盘', exact: true }).scrollIntoViewIfNeeded();
  const business: [string, string][] = [
    ['查看项目', '/p/tpm_project'],
    ['查看交付工作台', '/p/c/cde_delivery_workspace'],
    ['打开交付验收', '/p/tpm_acceptance'],
    ['处理我的审批', '/bpm/task-center'],
    ['交付核验', '/p/tdv_check'],
    ['查看交付经营看板', '/dashboards/view/tdo_delivery_operations'],
    ['查看超时与催办', '/bpm/sla-monitor'],
    ['打开复盘报告', '/p/c/report_management'],
    ['整理客户资料', '/p/c/tpm_customer_ai_intake'],
    ['整理交付需求', '/p/c/tpm_project_ai_intake'],
    ['打开项目并分析', '/p/tpm_project'],
  ];
  for (const [name, route] of business) {
    await page.getByRole('main').getByRole('button', { name, exact: true }).click();
    await expect(page).toHaveURL((url) => url.pathname === route);
    await expect(page.getByRole('main')).toBeVisible();
    await expect(page.getByRole('main')).not.toContainText('页面未找到');
    if (name === '查看项目') {
      await expect(page.getByRole('main')).toContainText('华辰制造 · 供应链协同平台');
      await shot(page, 'PK02');
      const project = page.getByRole('row').filter({ hasText: '华辰制造 · 供应链协同平台' });
      await project.getByRole('button', { name: '查看', exact: true }).click();
      await expect(page.getByRole('heading', { name: '华辰制造 · 供应链协同平台', exact: true })).toBeVisible();
      await expect(page.getByRole('main')).toContainText('林晓');
      await shot(page, 'PK09');
      await page.getByRole('button', { name: '编辑项目', exact: true }).click();
      await expect(page.getByRole('textbox', { name: '项目名称', exact: true })).toHaveValue('华辰制造 · 供应链协同平台');
      await expect(page.getByRole('textbox', { name: '项目预算', exact: true })).toHaveValue('128,000.00');
      await expect(page.getByRole('combobox', { name: '所属客户', exact: true })).toContainText('华辰制造');
      await expect(page.getByRole('main').getByText('林晓', { exact: true }).first()).toBeVisible();
      await shot(page, 'PK08');
      await page.getByRole('button', { name: '取消', exact: true }).click();
      await expect(page.getByRole('heading', { name: '华辰制造 · 供应链协同平台', exact: true })).toBeVisible();
    }
    if (name === '查看交付经营看板') {
      await expect(page.getByRole('main')).toContainText('交付行动清单');
      await expect(page.getByRole('main')).not.toContainText('加载中');
      await shot(page, 'PK04');
    }
    if (name === '打开复盘报告')
      await expect(page.getByRole('main')).toContainText('客户交付经营复盘');
    if (name === '整理客户资料' || name === '整理交付需求') {
      await expect(page.getByTestId('ai-fill-trigger')).toBeVisible();
      await expect(page.getByRole('textbox', { name: name === '整理客户资料' ? '客户名称' : '项目名称', exact: true })).toBeEditable();
      await expect(page.getByRole('main')).toContainText('核对');
      await expect(page.getByRole('main')).toContainText('保存');
      await shot(page, name === '整理客户资料' ? 'PK06' : 'PK07');
    }
    await start(page);
  }
  await page.getByRole('main').getByRole('button', { name: '专业配置与诊断', exact: true }).click();
  await page.getByRole('main').getByRole('button', { name: '返回开始体验', exact: true }).click();
  await expect(page.getByRole('main')).toContainText('把一次客户交付完整跑通');
  const professional: [string, string][] = [
    ['数据模型', '/meta/models'],
    ['页面设计', '/p/page_schema'],
    ['仪表盘设计', '/p/dashboard_management'],
    ['报告设计', '/p/c/report_management'],
    ['规则中心', '/decision-ops'],
    ['自动化', '/automations'],
    ['审批流程', '/p/bpm_process_management'],
    ['时限策略', '/p/sla_config'],
    ['插件与扩展', '/plugins'],
    ['外部集成', '/p/c/open_platform_publications'],
    ['故障定位', '/ops/troubleshooting'],
    ['配置模型服务', '/aurabot/providers'],
    ['查看 AI 运行记录', '/aurabot/runs'],
  ];
  for (const [name, route] of professional) {
    await page
      .getByRole('main')
      .getByRole('button', { name: '专业配置与诊断', exact: true })
      .click();
    await expect(page.getByRole('main')).toContainText('从业务结果追溯配置');
    if (name === '数据模型') await shot(page, 'PK03');
    await page.getByRole('main').getByRole('button', { name, exact: true }).click();
    await expect(page).toHaveURL((url) => url.pathname === route);
    await expect(page.getByRole('main')).not.toContainText('页面未找到');
    await start(page);
  }
  await page.reload();
  await expect(page.getByRole('main')).toContainText('把一次客户交付完整跑通');
  await restoredBusiness(page,false);
  if (before) {
    const after = await cleanSnapshot(page);
    expect(after).toEqual(before);
    await writeFile(join(evidence!, 'clean-experience.json'), JSON.stringify({ status: 'passed', totals: { customers: 8, projects: 8, tasks: 24, milestones: 32 }, before, after, navigationMutatedBusiness: false }, null, 2));
  }
});

test('PK viewer gets readable business entry without installation or professional mutation authority', async ({
  page,
}) => {
  await loginDeliveryMember(page, 'delivery-viewer@example.test');
  await start(page);
  await expect(
    page.getByRole('main').getByRole('button', { name: '查看项目', exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole('main').getByRole('button', { name: '打开交付验收', exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole('main').getByRole('button', { name: '专业配置与诊断', exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole('main').getByRole('button', { name: '处理我的审批', exact: true }),
  ).toHaveCount(0);
  for (const name of ['整理客户资料', '整理交付需求'])
    await expect(page.getByRole('main').getByRole('button', { name, exact: true })).toHaveCount(0);
  await shot(page, 'PK05');
  await page.getByRole('main').getByRole('button',{name:'查看交付工作台',exact:true}).click();
  await expect(page).toHaveURL(url=>url.pathname==='/p/c/cde_delivery_workspace');
  await expect(page.getByRole('button',{name:'登记交付跟进',exact:true})).toHaveCount(0);
  await start(page);
  const denied = await page.request.post('/api/plugins/import/import-directory-sync', {
    data: { path: 'not-authorized', conflictStrategy: 'ERROR' },
  });
  const body = await denied.json();
  expect(denied.status() === 403 || String(body.code) === '403').toBe(true);
  await page.getByRole('main').getByRole('button', { name: '查看项目', exact: true }).click();
  await expect(page.getByRole('main')).toContainText('华辰制造 · 供应链协同平台');
  await expect(page.getByTestId('toolbar-btn-create')).toHaveCount(0);
  await restoredBusiness(page,true);
});
