import { test, expect, type Page, type APIResponse, type Response } from '@playwright/test';
import { readFile, mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { BASE_URL } from '../../helpers/environments';
import { loginDeliveryMember } from '../../helpers/delivery-session';
import { navigateToMenuByClick } from '../helpers';

const evidence = process.env.AURA_EVIDENCE_DIR!;
if (!evidence || !process.env.DELIVERY_FIXTURE_PATH || !process.env.DELIVERY_PROJECT_PID)
  throw Error('Bind the owned delivery fixture and evidence');
test.use({ locale: 'zh-CN', actionTimeout: 15_000, storageState: { cookies: [], origins: [] } });
test.setTimeout(180_000);
async function data(response: APIResponse | Response) {
  const body = await response.json();
  expect(response.status(), JSON.stringify(body)).toBe(200);
  expect(String(body.code)).toBe('0');
  return body.data;
}
async function pluginState(page: Page, response: Response, status: string) {
  expect(response.status()).toBe(200);
  const pid = new URL(response.url()).pathname.split('/').at(-2)!;
  expect(pid).toMatch(/^[0-9A-Z]{26}$/);
  expect((await data(await page.request.get(`/api/plugins/${pid}`))).status).toBe(status);
}
async function shot(page: Page, id: string) {
  await mkdir(join(evidence, 'screenshots'), { recursive: true });
  await page.screenshot({ path: join(evidence, 'screenshots', `${id}.png`) });
}
async function openProject(page: Page) {
  const fixture = JSON.parse(await readFile(process.env.DELIVERY_FIXTURE_PATH!, 'utf8'));
  const project = fixture.projects.find((row: any) => row.pid === process.env.DELIVERY_PROJECT_PID);
  expect(project).toBeTruthy();
  await navigateToMenuByClick(page, ['项目交付', '项目']);
  const row = page.getByRole('row').filter({ has: page.getByText(project.name, { exact: true }) });
  await expect(row).toHaveCount(1);
  await row.getByRole('button', { name: '查看', exact: true }).click();
  await expect(page.getByRole('heading', { name: project.name, exact: true })).toBeVisible();
}
async function installedPlugin(page: Page) {
  await navigateToMenuByClick(page, ['系统管理', '插件管理']);
  await expect(page.getByTestId('plugins-page')).toBeVisible();
  await page.getByTestId('plugins-tab-installed').click();
  const row = page.getByRole('row').filter({ hasText: '客户交付扩展' });
  await expect(row).toHaveCount(1);
  return row;
}

test('customer plugin contributes a real command dialog and workspace, survives reopen and stops cleanly @critical', async ({
  page,
}, info) => {
  await loginDeliveryMember(page);
  const installed = await installedPlugin(page);
  const plugins = await data(await page.request.get('/api/plugins'));
  const extension = plugins.find(
    (row: any) => row.pluginId === 'com.auraboot.customer-delivery-extension',
  );
  expect(extension).toBeTruthy();
  if (extension.status !== 'active') {
    expect(['installed', 'stopped']).toContain(extension.status);
    const enabling = page.waitForResponse(
      (response) =>
        /\/api\/plugins\/[^/]+\/enable$/.test(new URL(response.url()).pathname) &&
        response.request().method() === 'POST',
    );
    await installed.getByRole('button', { name: /启用|Enable/ }).click();
    await pluginState(page, await enabling, 'active');
  } else {
    await expect(installed.getByRole('button', { name: /禁用|停用|Disable/ })).toBeVisible();
  }
  await openProject(page);
  const button = page.getByTestId('workbench-action-cde_register_follow_up');
  await expect(button).toBeVisible();
  await button.scrollIntoViewIfNeeded();
  await shot(page, 'X01-project-contributed-actions');
  await button.click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  const next = `林晓于11月8日前完成客户交付范围确认（${Date.now().toString(36)}）。`;
  await dialog.getByRole('textbox', { name: '下一步行动', exact: true }).fill(next);
  await shot(page, 'X02-contributed-command-dialog');
  const command = page.waitForResponse(
    (response) =>
      response.url().includes('/execute/tpm:update_project') &&
      response.request().method() === 'POST' &&
      response.request().postDataJSON()?.expectedVersion !== undefined,
  );
  await dialog.getByRole('button', { name: '保存跟进', exact: true }).click();
  const applied = await command;
  const payload = applied.request().postDataJSON();
  expect(payload.targetRecordPid).toBe(process.env.DELIVERY_PROJECT_PID);
  expect(payload.payload.tpm_pj_next_action).toBe(next);
  expect((await data(applied)).phaseReached).toBe('completed');
  await page.reload();
  const record = await data(
    await page.request.get(`/api/dynamic/tpm_project/${process.env.DELIVERY_PROJECT_PID}`),
  );
  expect(record.tpm_pj_next_action).toBe(next);
  const conflicting = await page.request.post('/api/meta/commands/execute/tpm:update_project', {
    data: {
      ...payload,
      payload: { tpm_pj_next_action: 'Stale follow-up must not overwrite the saved action' },
    },
  });
  expect(conflicting.status()).toBe(409);
  expect(
    (
      await data(
        await page.request.get(`/api/dynamic/tpm_project/${process.env.DELIVERY_PROJECT_PID}`),
      )
    ).tpm_pj_next_action,
  ).toBe(next);
  await expect(page.getByRole('main')).toContainText(next);
  await page.getByTestId('workbench-action-cde_open_workspace').click();
  await expect(page).toHaveURL(/\/p\/c\/cde_delivery_workspace$/);
  const selected = page.getByRole('row').filter({ has: page.getByText('华辰制造 · 供应链协同平台', { exact: true }) });
  await selected.click();
  await expect(page.getByTestId('workbench-action-cde_open_project')).toBeEnabled();
  await expect(selected).toContainText(next);
  const deliveryDate = selected.getByRole('cell').nth(5);
  await expect(deliveryDate).toHaveText('2026/10/28');
  expect(await deliveryDate.evaluate(cell => cell.scrollWidth <= cell.clientWidth)).toBe(true);
  await shot(page, 'X03-customer-delivery-workspace');
  await page.getByTestId('workbench-action-cde_open_project').click();
  await expect(button).toBeVisible();
  const row = await installedPlugin(page);
  const stopping = page.waitForResponse(
    (response) =>
      /\/api\/plugins\/[^/]+\/disable$/.test(new URL(response.url()).pathname) &&
      response.request().method() === 'POST',
  );
  await row.getByRole('button', { name: /禁用|停用|Disable/ }).click();
  await pluginState(page, await stopping, 'stopped');
  await shot(page, 'X04-plugin-stopped');
  await openProject(page);
  await expect(button).toHaveCount(0);
  await expect(page.getByTestId('object-summary')).toBeVisible();
  await shot(page, 'X05-base-project-without-extension');
  const stopped = await installedPlugin(page);
  const starting = page.waitForResponse(
    (response) =>
      /\/api\/plugins\/[^/]+\/enable$/.test(new URL(response.url()).pathname) &&
      response.request().method() === 'POST',
  );
  await stopped.getByRole('button', { name: /启用|Enable/ }).click();
  await pluginState(page, await starting, 'active');
  await openProject(page);
  await expect(button).toBeVisible();
  expect(
    (
      await data(
        await page.request.get(`/api/dynamic/tpm_project/${process.env.DELIVERY_PROJECT_PID}`),
      )
    ).tpm_pj_next_action,
  ).toBe(next);
  await info.attach('X-command-persistence', {
    body: JSON.stringify({ payload, record }),
    contentType: 'application/json',
  });
});

test('customer extension keeps read-only access and rejects conflicting contributions without losing the valid page @critical', async ({
  page,
  browser,
}, info) => {
  const admin = await loginDeliveryMember(page);
  await openProject(page);
  const code = `delivery_extension_reader_${Date.now()}`;
  const role = await data(
    await page.request.post('/api/roles', {
      data: {
        code,
        name: code,
        type: 'custom',
        status: 'active',
        scopeType: 'tenant',
        defaultDataScopeType: 'all',
      },
    }),
  );
  const tree = await data(await page.request.get('/api/permissions/tree'));
  const ids = new Map<string, number>();
  const collect = (nodes: any[]) =>
    nodes.forEach((node) => {
      ids.set(node.code, node.id);
      collect(node.children || []);
    });
  collect(tree);
  const grants = [
    'tpm.project.read',
    'model.tpm_project.read',
    'tpm.task.read',
    'model.tpm_task.read',
    'tpm.milestone.read',
    'model.tpm_milestone.read',
    'model.tpm_customer.read',
    'meta.designer.read',
    'page.page.read',
  ];
  await data(
    await page.request.put(`/api/permissions/matrix/${role.pid}/batch`, {
      data: grants.map((code) => {
        expect(ids.has(code), code).toBe(true);
        return { permissionId: ids.get(code), granted: true };
      }),
    }),
  );
  const email = `${code}@e2e.local`;
  await data(
    await page.request.post('/api/admin/users', {
      data: {
        email,
        displayName: '客户交付只读成员',
        initialPassword: process.env.ADMIN_PASSWORD,
        roleCodes: [code],
        sendInviteEmail: false,
      },
    }),
  );
  const context = await browser.newContext({
    baseURL: BASE_URL,
    storageState: { cookies: [], origins: [] },
    locale: 'zh-CN',
    viewport: { width: 1440, height: 1000 },
  });
  try {
    const actor = await context.newPage();
    await loginDeliveryMember(actor, email, String(admin.user.tenantId));
    await openProject(actor);
    await expect(actor.getByTestId('workbench-action-cde_register_follow_up')).toHaveCount(0);
    await expect(actor.getByTestId('workbench-action-cde_open_workspace')).toBeVisible();
    const before = await data(
      await page.request.get(`/api/dynamic/tpm_project/${process.env.DELIVERY_PROJECT_PID}`),
    );
    const refused = await actor.request.post('/api/meta/commands/execute/tpm:update_project', {
      data: {
        operationType: 'UPDATE',
        targetRecordPid: process.env.DELIVERY_PROJECT_PID,
        expectedVersion: before.row_version,
        payload: { tpm_pj_next_action: 'Unauthorized forged follow-up' },
      },
    });
    expect(refused.status()).toBe(403);
    await shot(actor, 'X06-reader-project');
    expect(
      (
        await data(
          await page.request.get(`/api/dynamic/tpm_project/${process.env.DELIVERY_PROJECT_PID}`),
        )
      ).tpm_pj_next_action,
    ).toBe(before.tpm_pj_next_action);
    await navigateToMenuByClick(page, ['元数据管理', '页面配置']);
    await page.getByTestId('list-search-input').fill('tpm_project_form');
    await page.getByTestId('list-search-input').press('Enter');
    await page.getByRole('row').filter({ hasText: 'tpm_project_form' }).getByText('统一设计器', { exact: true }).click();
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();
    const designerUrl = page.url();
    const pagePid = new URL(designerUrl).searchParams.get('pid') || new URL(designerUrl).searchParams.get('pageId');
    expect(pagePid).toMatch(/^[0-9A-Z]{26}$/);
    const definition = await data(await page.request.get(`/api/pages/${pagePid}`));
    await actor.goto(designerUrl);
    await expect(actor.getByTestId('unified-designer-workbench')).toBeVisible();
    await expect(actor.getByTestId('designer-save')).toBeDisabled();
    await expect(actor.getByTestId('designer-publish')).toBeDisabled();
    const deniedPage = await actor.request.put(`/api/pages/${pagePid}`, { data: { ...definition, title: 'Forbidden reader page mutation' } });
    expect(deniedPage.status()).toBe(403);
    const retained = await data(await page.request.get(`/api/pages/${pagePid}`));
    expect(retained.title).toEqual(definition.title); expect(retained.blocks).toEqual(definition.blocks);
    await shot(actor, 'X07-reader-designer');
  } finally {
    await context.close();
  }
  const manifest = JSON.parse(
    await readFile(
      join(process.env.AURA_CORE_PROJECT_ROOT!, 'plugins/customer-delivery-extension/plugin.json'),
      'utf8',
    ),
  );
  const contributions = JSON.parse(
    await readFile(
      join(
        process.env.AURA_CORE_PROJECT_ROOT!,
        'plugins/customer-delivery-extension/config/page-contributions.json',
      ),
      'utf8',
    ),
  );
  for (const conflict of ['missing-slot', 'duplicate-block']) {
    const invalid = structuredClone(contributions);
    if (conflict === 'missing-slot') invalid[0].slotId = 'undeclared-slot';
    else invalid[0].payload.id = 'action_bar';
    const response = await page.request.post(
      '/api/plugins/import/execute-direct?conflictStrategy=overwrite',
      { data: { ...manifest, pageContributions: invalid } },
    );
    const body = await response.json();
    expect(
      body.success === false || body.data?.success === false || response.status() >= 400,
      JSON.stringify(body),
    ).toBe(true);
    await info.attach(`X-${conflict}-rejected`, {
      body: JSON.stringify({ status: response.status(), body }),
      contentType: 'application/json',
    });
    await page.reload();
    await expect(page.getByTestId('workbench-action-cde_register_follow_up')).toBeVisible();
  }
});
