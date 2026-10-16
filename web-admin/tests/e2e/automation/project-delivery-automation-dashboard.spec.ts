import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { test, expect, type Page } from '@playwright/test';
import {
  ensureSidebarExpanded,
  navigateToMenuByClick,
  executeCommandViaApi,
} from '../helpers';
import {
  fillNodeConfig,
  saveAutomation,
  dragNodeToCanvas,
  connectEdge,
  setEdgeCondition,
} from '../_helpers/flow-designer-harness';

// Enterprise composition must install the real Aura BPM workflow capability.
// Setup creates only task fixtures; authoring, enabling, firing and JSON transfer are UI-driven.
test.use({
  storageState:
    process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
  locale: 'zh-CN',
});
test.setTimeout(270000);
const marker = '[AuraBoot demo] Archived after delivery confirmation';
const original = '[AuraBoot demo] Waiting for archival';
const run = randomUUID().slice(0, 8);
const automationName = `客户交付自动归档-${run}`;
const dashboardTitle = `客户交付看板-${run}`;
const fixtureRoot = path.resolve(
  process.cwd(),
  '../plugins/project-management/examples',
);

async function record(page: Page, pid: string) {
  const response = await page.request.get(`/api/dynamic/tpm_task/${pid}`);
  expect(response.ok()).toBe(true);
  const body = await response.json();
  expect(String(body.code)).toBe('0');
  return body.data;
}
async function setupTask(page: Page, title: string, projectPid: string) {
  const created = await executeCommandViaApi(page, 'tpm:create_task', {
    tpm_ts_title: title,
    tpm_ts_project_id: projectPid,
    tpm_ts_description: original,
    tpm_ts_priority: 'medium',
  });
  expect(created.code).toBe('0');
  expect(created.recordId).toMatch(/^[0-9A-Z]{26}$/);
  for (const command of ['tpm:start_task', 'tpm:review_task']) {
    const before = await record(page, created.recordId);
    expect(Number.isInteger(Number(before.row_version))).toBe(true);
    const result = await executeCommandViaApi(
      page,
      command,
      {},
      created.recordId,
      'state_transition',
      { expectedVersion: Number(before.row_version) },
    );
    expect(result.code).toBe('0');
  }
  expect((await record(page, created.recordId)).tpm_ts_status).toBe(
    'in_review',
  );
  return created.recordId;
}
async function createReviewTaskThroughUi(page: Page, title: string, projectPid: string) {
  const fixture = JSON.parse(fs.readFileSync(process.env.DELIVERY_FIXTURE_PATH!, 'utf8'));
  const project = fixture.projects.find((row: {pid: string}) => row.pid === projectPid);
  expect(project).toBeDefined();
  await menu(page, ['项目交付', '任务']);
  await page.getByRole('button', {name: '新建', exact: true}).click();
  await page.locator('#tpm_ts_title').fill(title);
  await page.getByTestId('select-trigger-tpm_ts_project_id').click();
  await expect(page.getByRole('option', {name: project.name, exact: true})).toBeVisible();
  await page.screenshot({path: path.join(process.env.AURA_EVIDENCE_DIR!, 'TASK01-project-name-picker.png'), fullPage: true});
  await page.getByRole('option', {name: project.name, exact: true}).click();
  await expect(page.getByTestId('select-trigger-tpm_ts_project_id')).toHaveText(project.name);
  await page.locator('#tpm_ts_description').fill(original);
  const creating = page.waitForResponse(response => new URL(response.url()).pathname === '/api/meta/commands/execute/tpm:create_task' && response.request().method() === 'POST');
  await page.getByRole('button', {name: '保存任务', exact: true}).click();
  const response = await creating;
  expect(response.status()).toBe(200);
  expect(response.request().postDataJSON().payload).toMatchObject({tpm_ts_title: title, tpm_ts_project_id: projectPid});
  expect((await response.json()).data.phaseReached).toBe('completed');
  const row = page.getByRole('row').filter({has: page.getByText(title, {exact: true})});
  await expect(row).toBeVisible();
  await row.getByRole('button', {name: '查看', exact: true}).click();
  await expect(page).toHaveURL(/\/p\/tpm_task\/view\/[0-9A-Z]{26}$/);
  const pid = new URL(page.url()).pathname.split('/').at(-1)!;
  for (const [command, label, status] of [['tpm:start_task','开始任务','in_progress'],['tpm:review_task','提交审核','in_review']]) {
    const transition = page.waitForResponse(result => new URL(result.url()).pathname === `/api/meta/commands/execute/${command}` && result.request().method() === 'POST');
    await page.getByRole('button', {name: label, exact: true}).click();
    const result = await transition; expect(result.status()).toBe(200);
    expect(result.request().postDataJSON().targetRecordPid).toBe(pid);
    expect((await result.json()).data.phaseReached).toBe('completed');
    expect((await record(page, pid)).tpm_ts_status).toBe(status);
  }
  await page.reload();
  await expect(page.getByText(title, {exact: true})).toBeVisible();
  const persisted = await record(page, pid);
  expect(persisted).toMatchObject({tpm_ts_title: title, tpm_ts_project_id: projectPid, tpm_ts_status: 'in_review', tpm_ts_description: original});
  await page.screenshot({path: path.join(process.env.AURA_EVIDENCE_DIR!, 'TASK02-created-review-task.png'), fullPage: true});
  return pid;
}
async function menu(page: Page, labels: string[]) {
  await ensureSidebarExpanded(page);
  await navigateToMenuByClick(page, labels);
}
async function completeTaskViaUi(page: Page, title: string, pid: string) {
  await menu(page, ['项目交付', '任务']);
  const row = page
    .getByRole('row')
    .filter({ has: page.getByText(title, { exact: true }) });
  await expect(row).toHaveCount(1);
  await row.getByRole('button', { name: '查看', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/p/tpm_task/view/${pid}$`));
  const request = page.waitForResponse(
    (r) =>
      r.url().includes('/api/meta/commands/execute/tpm:complete_task') &&
      r.request().method() === 'POST',
  );
  await page.getByRole('button', { name: '完成', exact: true }).click();
  const response = await request;
  expect(response.ok()).toBe(true);
  const payload = response.request().postDataJSON();
  expect(payload.targetRecordPid).toBe(pid);
  expect(String((await response.json()).code)).toBe('0');
}
async function savedDashboard(page: Page, pid: string) {
  const response = await page.request.get(`/api/dashboards/${pid}`);
  expect(response.ok()).toBe(true);
  const body = await response.json();
  expect(String(body.code)).toBe('0');
  return body.data;
}

test('delivery automation and dashboard form a real, bounded customer-project loop', async ({
  page,
}, info) => {
  const projectPid = process.env.DELIVERY_PROJECT_PID;
  expect(
    projectPid,
    'Set DELIVERY_PROJECT_PID to a project created by the shared scenario initializer',
  ).toMatch(/^[0-9A-Z]{26}$/);
  await page.goto('/');
  const activeTitle = `【演示】交付归档-${run}`;
  const disabledTitle = `【演示】停用验证-${run}`;
  const otherTitle = `【演示】非目标验证-${run}`;
  const active = await setupTask(page, activeTitle, projectPid!);
  const disabled = await setupTask(page, disabledTitle, projectPid!);
  const other = await setupTask(page, otherTitle, projectPid!);

  await menu(page, ['系统管理', '自动化']);
  await page.getByTestId('btn-create-automation').click();
  await expect(page.getByTestId('automation-editor-name-input')).toBeVisible();
  await page.getByTestId('automation-editor-name-input').fill(automationName);
  const description = `仅修改本次专属演示任务「${activeTitle}」「${disabledTitle}」的描述；完成后覆盖为演示归档说明。其他任务保持不变。默认停用，确认此范围后再启用。`;
  await page
    .getByTestId('automation-editor-description-input')
    .fill(description);
  const trigger = await dragNodeToCanvas(page, 'trigger-state-change', {
    x: 60,
    y: 100,
  });
  const guard = await dragNodeToCanvas(page, 'control-condition', {
    x: 300,
    y: 100,
  });
  const archive = await dragNodeToCanvas(page, 'action-update-record', {
    x: 570,
    y: 40,
  });
  const ignore = await dragNodeToCanvas(page, 'action-write-audit', {
    x: 570,
    y: 280,
  });
  const condition = `recordId == '${active}' || recordId == '${disabled}'`;
  await fillNodeConfig(page, trigger, {
    modelCode: '任务',
    stateField: '状态',
    fromStates: ['审核中'],
    toStates: ['已完成'],
  });
  await fillNodeConfig(page, guard, { expression: condition });
  await fillNodeConfig(page, archive, {
    modelCode: '任务',
    recordPid: '${trigger.recordPid}',
    fields: JSON.stringify({ tpm_ts_description: marker }),
  });
  await fillNodeConfig(page, ignore, {
    message: 'Skipped: task is outside this demonstration scope',
  });
  await connectEdge(page, trigger, guard);
  const yes = await connectEdge(page, guard, archive, 'true');
  const no = await connectEdge(page, guard, ignore, 'false');
  await setEdgeCondition(page, yes, condition);
  await setEdgeCondition(page, no, `!(${condition})`);
  await expect(
    page.getByTestId('automation-editor-description-input'),
  ).toHaveValue(description);
  await page.screenshot({
    path: info.outputPath('AD01-explicit-archive-scope.png'),
  });
  const { pid: automationPid } = await saveAutomation(page);
  await expect(page).toHaveURL(new RegExp(`/automation/${automationPid}$`));
  await page.reload();
  await expect(page.getByTestId('automation-editor-name-input')).toHaveValue(
    automationName,
  );
  const savedRule = await (
    await page.request.get(`/api/automations/${automationPid}`)
  ).json();
  expect(savedRule.data.enabled).toBe(false);
  expect(
    savedRule.data.flowConfig.nodes.find((n: any) => n.id === archive).data
      .config.fields.tpm_ts_description,
  ).toBe(marker);
  const [automationDownload] = await Promise.all([
    page.waitForEvent('download'),
    page.getByTestId('btn-export-automation').click(),
  ]);
  expect(automationDownload.suggestedFilename()).toMatch(/\.json$/);
  const automationPath = info.outputPath('automation-design.json');
  await automationDownload.saveAs(automationPath);
  const automationExport = JSON.parse(fs.readFileSync(automationPath, 'utf8'));
  expect(automationExport.flowConfig.nodes).toHaveLength(4);
  await menu(page, ['系统管理', '自动化']);
  const importing = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/automations' &&
      r.request().method() === 'POST',
  );
  await page.getByTestId('input-import-automation').setInputFiles({
    name: 'automation-copy.json',
    mimeType: 'application/json',
    buffer: Buffer.from(
      JSON.stringify({
        ...automationExport,
        name: `${automationName}-导入副本`,
        enabled: true,
      }),
    ),
  });
  const importResponse = await importing;
  expect(importResponse.ok()).toBe(true);
  expect(importResponse.request().postDataJSON().enabled).toBe(false);
  // Observe the actual imported row, then read back that exact public PID.
  // Do not depend on the old browser/CDP response body to establish persistence.
  const copyLink = page.getByRole('link', { name: `${automationName}-导入副本`, exact: true });
  await expect(copyLink).toBeVisible();
  const copyHref = await copyLink.getAttribute('href');
  expect(copyHref).toMatch(/^\/automation\/[0-9A-Z]{26}$/);
  const copyPid = copyHref!.split('/').pop()!;
  expect(copyPid).not.toBe(automationPid);
  const copyResponse = await page.request.get(`/api/automations/${copyPid}`, { timeout: 15000 });
  expect(copyResponse.ok()).toBe(true);
  const copyResult = await copyResponse.json();
  expect(String(copyResult.code)).toBe('0');
  const copy = copyResult.data;
  expect(copy.pid).toBe(copyPid);
  expect(copy.enabled).toBe(false);
  expect(copy.flowConfig).toEqual(automationExport.flowConfig);
  await copyLink.click();
  await page
    .getByTestId('automation-editor-name-input')
    .fill(`${automationName}-副本微调`);
  await saveAutomation(page);
  await page.reload();
  await expect(page.getByTestId('automation-editor-name-input')).toHaveValue(
    `${automationName}-副本微调`,
  );
  await page.screenshot({
    path: info.outputPath('AD01b-automation-round-trip.png'),
  });

  await menu(page, ['元数据管理', '仪表盘管理']);
  await page.getByTestId('toolbar-btn-create').click();
  const design = JSON.parse(
    fs
      .readFileSync(
        path.join(fixtureRoot, 'dashboard-task-archive.json'),
        'utf8',
      )
      .replaceAll('REPLACE_WITH_OWN_DEMO_TASK_PID', active),
  );
  design.dashboard.title = dashboardTitle;
  await page.getByTestId('dashboard-design-import-file').setInputFiles({
    name: 'delivery-dashboard.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(design)),
  });
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.screenshot({
    path: info.outputPath('AD02-import-design-confirmation.png'),
  });
  await page.getByRole('button', { name: '导入为新草稿', exact: true }).click();
  await expect(page.getByTestId('designer-toolbar')).toContainText(
    dashboardTitle,
  );
  const archivedWidget = page.getByTestId('designer-canvas').locator('[data-widget-id="archived-tasks"]');
  await expect(archivedWidget).toHaveCount(1);
  await archivedWidget.click();
  const inspector = page.getByTestId('widget-property-panel');
  await expect(inspector).toHaveAttribute('data-widget-id', 'archived-tasks');
  await expect(inspector.getByTestId('widget-prop-title-zh')).toHaveValue('自动归档');
  await inspector.getByTestId('widget-prop-title-zh').fill('自动归档任务');
  await expect(page.getByTestId('designer-canvas')).toContainText(
    '自动归档任务',
  );
  const saving = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/dashboards' &&
      r.request().method() === 'POST',
  );
  await page.getByTestId('designer-toolbar-btn-save').click();
  const saveResponse = await saving;
  expect(saveResponse.ok()).toBe(true);
  const saved = await saveResponse.json();
  expect(String(saved.code)).toBe('0');
  const dashboardPid = saved.data.pid;
  const baseline = await savedDashboard(page, dashboardPid);
  expect(baseline.scope).toBe('personal');
  expect(baseline.status).toBe('draft');
  expect(
    baseline.widgets.find((widget: any) => widget.id === 'archived-tasks')
      .config.title['zh-CN'],
  ).toBe('自动归档任务');
  await expect(
    page.getByTestId('designer-canvas').getByText('1', { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: info.outputPath('AD03-baseline-dashboard.png'),
  });

  await menu(page, ['系统管理', '自动化']);
  const enabling = page.waitForResponse(
    (r) =>
      r.url().includes(`/api/automations/${automationPid}/toggle`) &&
      r.request().method() === 'POST',
  );
  await page.getByTestId(`btn-toggle-${automationPid}`).click();
  expect((await enabling).ok()).toBe(true);
  await completeTaskViaUi(page, activeTitle, active);
  await expect
    .poll(async () => (await record(page, active)).tpm_ts_description)
    .toBe(marker);
  expect((await record(page, active)).tpm_ts_status).toBe('done');
  const logResponse = await page.request.get(
    `/api/automations/${automationPid}/logs`,
  );
  const logs = (await logResponse.json()).data;
  const activeLogs = logs.filter(
    (entry: any) => entry.triggerRecordPid === active,
  );
  expect(activeLogs, 'One command event must produce one target execution').toHaveLength(1);
  const activeLog = activeLogs[0];
  expect(activeLog.status).toBe('success');
  const nodeResponse = await page.request.get(
    `/api/automation/executions/by-log/${activeLog.id}/node-statuses`,
  );
  const nodes = (await nodeResponse.json()).data;
  expect(nodes.find((entry: any) => entry.nodeId === archive).status).toBe(
    'completed',
  );
  await page.reload();
  await expect(page.getByText(marker, { exact: true })).toBeVisible();
  await page.screenshot({
    path: info.outputPath('AD04-task-persisted-archive.png'),
  });

  await completeTaskViaUi(page, otherTitle, other);
  await expect
    .poll(async () => {
      const rows = (
        await (
          await page.request.get(`/api/automations/${automationPid}/logs`)
        ).json()
      ).data;
      return rows.find((entry: any) => entry.triggerRecordPid === other)
        ?.status;
    })
    .toBe('success');
  expect((await record(page, other)).tpm_ts_description).toBe(original);

  await menu(page, ['系统管理', '自动化']);
  const disabling = page.waitForResponse(
    (r) =>
      r.url().includes(`/api/automations/${automationPid}/toggle`) &&
      r.request().method() === 'POST',
  );
  await page.getByTestId(`btn-toggle-${automationPid}`).click();
  expect((await disabling).ok()).toBe(true);
  await completeTaskViaUi(page, disabledTitle, disabled);
  let observations = 0;
  await expect
    .poll(
      async () => {
        expect((await record(page, disabled)).tpm_ts_description).toBe(
          original,
        );
        const rows = (
          await (
            await page.request.get(`/api/automations/${automationPid}/logs`)
          ).json()
        ).data;
        expect(
          rows.some((entry: any) => entry.triggerRecordPid === disabled),
        ).toBe(false);
        return ++observations;
      },
      { intervals: [250], timeout: 5000 },
    )
    .toBe(5);

  const finalLogs = (
    await (await page.request.get(`/api/automations/${automationPid}/logs`)).json()
  ).data;
  expect(finalLogs.filter((entry: any) => entry.triggerRecordPid === active)).toHaveLength(1);
  expect(finalLogs.filter((entry: any) => entry.triggerRecordPid === other)).toHaveLength(1);
  expect(finalLogs.filter((entry: any) => entry.triggerRecordPid === disabled)).toHaveLength(0);

  await menu(page, ['元数据管理', '仪表盘管理']);
  await page
    .getByRole('row')
    .filter({ has: page.getByText(dashboardTitle, { exact: true }) })
    .getByRole('button', { name: '编辑', exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp(`/dashboard-designer/${dashboardPid}$`));
  await expect(page.getByTestId('designer-canvas')).toBeVisible();
  await page.reload();
  await expect(
    page.getByTestId('designer-canvas').getByText('1', { exact: true }),
  ).toHaveCount(3);
  const dataResponses: any[] = [];
  page.on('response', async (response) => {
    if (
      new URL(response.url()).pathname === '/api/meta/chart-data' &&
      response.request().method() === 'POST'
    ) {
      const body = await response.json();
      dataResponses.push({
        url: response.url(),
        request: response.request().postDataJSON(),
        body,
      });
    }
  });
  await page.reload();
  await expect(
    page.getByTestId('designer-canvas').getByText('1', { exact: true }),
  ).toHaveCount(3);
  await expect.poll(() => dataResponses.length).toBeGreaterThanOrEqual(3);
  for (const evidence of dataResponses) {
    expect(evidence.request.modelCode).toBe('tpm_task');
    expect(evidence.request.filters).toContainEqual({
      field: 'pid',
      operator: 'eq',
      value: active,
    });
    expect(String(evidence.body.code)).toBe('0');
    expect(evidence.body.data.rows).toHaveLength(1);
    expect(Number(evidence.body.data.rows[0].n)).toBe(1);
  }
  await page.screenshot({
    path: info.outputPath('AD05-refreshed-real-aggregate.png'),
  });
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByTestId('dashboard-design-export').click(),
  ]);
  expect(download.suggestedFilename()).toMatch(/dashboard-.+\.json$/);
  const artifact = info.outputPath('dashboard-design.json');
  await download.saveAs(artifact);
  const exported = JSON.parse(fs.readFileSync(artifact, 'utf8'));
  expect(exported.format).toBe('auraboot.dashboard');
  expect(exported.version).toBe(1);
  expect(exported.dashboard.widgets).toHaveLength(3);
  expect(exported.dashboard).not.toHaveProperty('pid');
  expect(exported.dashboard).not.toHaveProperty('tenantId');
  const firstSource = exported.dashboard.widgets[0].config.dataSource;
  expect(firstSource.modelCode).toBe('tpm_task');
  expect(firstSource.filters).toContainEqual({
    field: 'pid',
    operator: 'eq',
    value: active,
  });
  await info.attach('scenario-ledger', {
    body: Buffer.from(
      JSON.stringify(
        {
          automationPid,
          dashboardPid,
          active,
          disabled,
          other,
          activeLog,
          nodes,
          dataResponses,
        },
        null,
        2,
      ),
    ),
    contentType: 'application/json',
  });
});


test('portable archive template selects a task once and binds every reference before a disabled import', async ({page}, info) => {
  const workspace = process.env.AURA_WORKSPACE_PROJECT_ROOT;
  expect(workspace, 'Bind the owned workspace source containing the portable template').toBeTruthy();
  const template = JSON.parse(fs.readFileSync(path.join(workspace!, 'docs/product-docs/strategy/enterprise-seven-scenario-assets/task-archive.automation.json'), 'utf8'));
  const issue = new Intl.DateTimeFormat('zh-CN', {month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(new Date());
  const name = `交付材料完成后归档 · ${issue}`; const title = `交付材料检查 · ${issue}`;
  expect(process.env.DELIVERY_PROJECT_PID).toMatch(/^[0-9A-Z]{26}$/);
  await page.goto('/');
  const taskPid = await createReviewTaskThroughUi(page, title, process.env.DELIVERY_PROJECT_PID!);
  await menu(page, ['系统管理', '自动化']);
  const beforeRows = (await (await page.request.get('/api/automations?pageNum=1&pageSize=500')).json()).data;
  const upload = async () => page.getByTestId('input-import-automation').setInputFiles({name: '任务归档.automation.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(template))});
  await upload();
  const dialog = page.getByRole('dialog', {name: '配置导入参数'});
  await expect(dialog).toBeVisible(); await expect(dialog.getByRole('button', {name: '导入停用副本'})).toBeDisabled();
  await page.screenshot({path: info.outputPath('IMPORT01-任务参数必填.png')});
  await dialog.getByRole('button', {name: '取消', exact: true}).click(); await expect(dialog).not.toBeVisible();
  expect((await (await page.request.get('/api/automations?pageNum=1&pageSize=500')).json()).data).toEqual(beforeRows);
  await upload(); await dialog.getByLabel('副本名称').fill(name);
  await dialog.locator('button#targetTask').click();
  await dialog.getByPlaceholder('搜索记录名称').fill(`__no_task_${run}__`);
  await expect(dialog.getByText('暂无可选记录', {exact: true})).toBeVisible();
  await expect(dialog.getByRole('button', {name: '导入停用副本'})).toBeDisabled();
  await page.screenshot({path: info.outputPath('IMPORT01b-搜索无结果与恢复.png')});
  await dialog.getByPlaceholder('搜索记录名称').fill(title);
  await expect(dialog.getByRole('button', {name: title, exact: true})).toBeVisible();
  await page.screenshot({path: info.outputPath('IMPORT01c-具名任务选择器.png')});
  await dialog.getByRole('button', {name: title, exact: true}).click();
  await expect(dialog.locator('button#targetTask')).toHaveText(title);
  await expect(dialog).not.toContainText(taskPid);
  await page.screenshot({path: info.outputPath('IMPORT02-选择具名演示任务.png')});
  const creating = page.waitForResponse(response => new URL(response.url()).pathname === '/api/automations' && response.request().method() === 'POST');
  await dialog.getByRole('button', {name: '导入停用副本'}).click();
  const response = await creating; expect(response.ok()).toBe(true);
  expect(response.request().postDataJSON()).toMatchObject({enabled: false, templateValues: {targetTask: taskPid}});
  const created = (await response.json()).data; expect(created.enabled).toBe(false);
  await expect(dialog).not.toBeVisible(); await page.reload();
  const imported = (await (await page.request.get(`/api/automations/${created.pid}`)).json()).data;
  const bound = JSON.stringify(imported.flowConfig); expect(bound).not.toContain('{{template.'); expect(bound.split(taskPid)).toHaveLength(4);
  expect((await record(page, taskPid)).tpm_ts_description).toBe(original);
  await page.getByRole('link', {name, exact: true}).click();
  const exported = page.waitForEvent('download'); await page.getByTestId('btn-export-automation').click();
  const download = await exported; const file = info.outputPath('绑定后的任务归档.automation.json'); await download.saveAs(file);
  expect(JSON.parse(fs.readFileSync(file, 'utf8')).flowConfig).toEqual(imported.flowConfig);
  await menu(page, ['系统管理', '自动化']);
  const toggled = page.waitForResponse(r => r.url().includes(`/api/automations/${created.pid}/toggle`) && r.request().method() === 'POST');
  await page.getByTestId(`btn-toggle-${created.pid}`).click(); expect((await toggled).ok()).toBe(true);
  await completeTaskViaUi(page, title, taskPid);
  await expect.poll(async () => (await record(page, taskPid)).tpm_ts_description).toBe(marker);
  await page.reload(); await expect(page.getByText(marker, {exact: true})).toBeVisible();
  await page.screenshot({path: info.outputPath('IMPORT03-真实任务完成自动归档.png')});
  const logs = (await (await page.request.get(`/api/automations/${created.pid}/logs`)).json()).data.filter((entry: any) => entry.triggerRecordPid === taskPid);
  expect(logs).toHaveLength(1); expect(logs[0].status).toBe('success');
  await menu(page, ['系统管理', '自动化']);
  const stopped = page.waitForResponse(r => r.url().includes(`/api/automations/${created.pid}/toggle`) && r.request().method() === 'POST');
  await page.getByTestId(`btn-toggle-${created.pid}`).click(); expect((await stopped).ok()).toBe(true);
  expect((await (await page.request.get(`/api/automations/${created.pid}`)).json()).data.enabled).toBe(false);
  // Direct submissions cannot use missing, unresolvable or expression-injecting references.
  for (const templateValues of [{}, {targetTask: '01AAAAAAAAAAAAAAAAAAAAAAAA'}, {targetTask: "' OR true"}]) {
    const denied = await page.request.post('/api/automations', {data: {...template, templateValues, enabled: true}});
    const payload = await denied.json(); expect(String(payload.code)).not.toBe('0'); expect(denied.status()).toBe(422);
  }
  await info.attach('Template-binding-receipt', {body: JSON.stringify({automationPid: created.pid, taskPid, boundReferenceCount: 3, defaultDisabled: true, finalDisabled: true, executionCount: logs.length, executionStatus: logs[0].status}), contentType: 'application/json'});
});
