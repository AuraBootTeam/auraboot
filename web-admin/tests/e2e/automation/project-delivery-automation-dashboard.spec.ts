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
  const copy = (await importResponse.json()).data;
  expect(copy.pid).not.toBe(automationPid);
  expect(copy.enabled).toBe(false);
  await page
    .getByRole('link', { name: `${automationName}-导入副本`, exact: true })
    .click();
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
  await page
    .getByTestId('designer-canvas')
    .getByText('自动归档', { exact: true })
    .click();
  await page.getByTestId('widget-prop-title-zh').fill('自动归档任务');
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
  const activeLog = logs.find(
    (entry: any) => entry.triggerRecordPid === active,
  );
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

  await menu(page, ['元数据管理', '仪表盘管理']);
  await page
    .getByRole('row')
    .filter({ has: page.getByText(dashboardTitle, { exact: true }) })
    .getByText(dashboardTitle, { exact: true })
    .click();
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
