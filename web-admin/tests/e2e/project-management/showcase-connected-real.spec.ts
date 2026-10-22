import { loginDeliveryMember } from '../../helpers/delivery-session';
import { requireRuntimeUrl } from '../../helpers/environments';
/** Owner-reviewed showcase. API fixture/maintenance steps are recorded separately from UI actions. */
import { test, expect, type Page, type APIResponse, type Response } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { navigateToMenuByClick } from '../helpers';

const evidence = process.env.AURA_EVIDENCE_DIR;
if (
  !evidence ||
  !process.env.ADMIN_EMAIL ||
  !process.env.ADMIN_PASSWORD ||
  !process.env.AURA_ADMIN_TENANT_ID
)
  throw Error('Bind the registered runtime, evidence, administrator and exact business tenant.');
async function data(response: APIResponse | Response) {
  const body = await response.json();
  expect(response.status(), JSON.stringify(body)).toBe(200);
  expect(String(body.code)).toBe('0');
  return body.data;
}
async function login(page: Page, email = process.env.ADMIN_EMAIL!, tenant = process.env.AURA_ADMIN_TENANT_ID) {
  return loginDeliveryMember(page, email, tenant);
}

async function menu(page: Page, name: string) {
  await navigateToMenuByClick(page, [name]);
}
async function shot(page: Page, name: string) {
  await mkdir(join(evidence!, 'screenshots'), { recursive: true });
  await page.screenshot({
    path: join(evidence!, 'screenshots', name + '.png'),
    fullPage: true,
  });
}

test('SC12 actual failed automation is diagnosed, repaired and rerun without losing failure history', async ({
  page,
  browser,
}) => {
  test.setTimeout(240000);
  page.setDefaultTimeout(20000);
  await mkdir(evidence!, { recursive: true });
  await login(page);
  // Setup uses public APIs. The actual failing/recovered execution and diagnosis are browser driven.
  const projects = (
    await data(await page.request.get('/api/dynamic/tpm_project/list?pageSize=100'))
  ).records;
  const project = projects.find((p: any) => p.tpm_pj_name === '华辰制造 · 供应链协同平台');
  expect(project).toBeTruthy();
  const suffix = randomUUID().replaceAll('-', '').slice(0, 10),
    taskName = '华辰制造 · 交付材料归档';
  const original = '验收材料已整理，等待交付归档。',
    recovered = '验收材料已核对，交付归档完成。';
  const created = await data(
    await page.request.post('/api/meta/commands/execute/tpm:create_task', {
      data: {
        operationType: 'create',
        payload: {
          tpm_ts_title: taskName,
          tpm_ts_project_id: project.pid,
          tpm_ts_description: original,
          tpm_ts_priority: 'medium',
        },
      },
    }),
  );
  const taskPid = created.data.recordPid;
  expect(taskPid).toMatch(/^[0-9A-Z]{26}$/);
  const task = async () => data(await page.request.get(`/api/dynamic/tpm_task/${taskPid}`));
  expect((await task()).tpm_ts_description).toBe(original);
  const roleCode = `showcase_recovery_${suffix}`;
  const role = await data(
    await page.request.post('/api/roles', {
      data: {
        code: roleCode,
        name: '交付自动化运维 ' + suffix,
        type: 'custom',
        status: 'active',
        scopeType: 'tenant',
        defaultDataScopeType: 'all',
      },
    }),
  );
  const tree = await data(await page.request.get('/api/permissions/tree')),
    ids = new Map<string, number>();
  const collect = (nodes: any[]) =>
    nodes.forEach((n) => {
      ids.set(n.code, n.id);
      collect(n.children || []);
    });
  collect(tree);
  const grants = [
    'system_management',
    'automation.automation.read',
    'automation.read',
    'automation.update',
    'automation.admin',
    'model.tpm_task.read',
    'tpm.task.read',
    'tpm.project.read',
    'model.tpm_project.read',
  ];
  for (const code of [...grants, 'tpm.task.manage']) expect(ids.has(code), code).toBe(true);
  const setManage = async (granted: boolean) =>
    data(
      await page.request.put(`/api/permissions/matrix/${role.pid}/batch`, {
        data: [
          ...grants.map((code) => ({
            permissionId: ids.get(code),
            granted: true,
          })),
          { permissionId: ids.get('tpm.task.manage'), granted },
        ],
      }),
    );
  await setManage(false);
  const email = roleCode + '@e2e.local';
  await data(
    await page.request.post('/api/admin/users', {
      data: {
        email,
        displayName: '交付自动化操作员',
        initialPassword: process.env.ADMIN_PASSWORD,
        roleCodes: [roleCode],
        sendInviteEmail: false,
      },
    }),
  );
  const automation = await data(
    await page.request.post('/api/automations', {
      data: {
        name: '华辰制造 · 交付归档与恢复',
        description: '仅更新专属交付任务的归档说明。权限不足时保留失败，修正后通过设计器再次执行。',
        modelCode: 'tpm_task',
        triggerType: 'webhook',
        triggerConfig: { testRecordPid: taskPid },
        enabled: true,
        actions: [
          {
            type: 'execute_command',
            sequence: 1,
            label: '更新交付归档说明',
            config: {
              commandCode: 'tpm:update_task',
              params: { tpm_ts_description: recovered },
            },
          },
        ],
      },
    }),
  );
  const ctx = await browser.newContext({
      baseURL: requireRuntimeUrl('base'),
      storageState: { cookies: [], origins: [] },
      locale: 'zh-CN',
      viewport: { width: 1440, height: 1000 },
    }),
    actor = await ctx.newPage();
  try {
    const identity = await login(actor, email);
    expect(identity.permissions.roles.map((r: any) => r.code)).not.toContain('tenant_admin');
    expect(identity.permissions.permissionCodes).not.toContain('tpm.task.manage');
    await menu(actor, '自动化');
    await actor.getByTestId(`btn-edit-${automation.pid}`).click();
    await expect(actor.getByTestId('btn-test-run')).toBeVisible();
    const run = async () => {
      const pending = actor.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === `/api/automations/${automation.pid}/trigger` &&
          r.request().method() === 'POST',
      );
      await actor.getByTestId('btn-test-run').click();
      const response = await pending;
      expect(response.request().postDataJSON().recordPid).toBe(taskPid);
      return {
        log: await data(response),
        traceId: response.headers()['x-trace-id'] || null,
      };
    };
    const failed = await run();
    expect(failed.log.status).toBe('failed');
    expect(failed.log.errorMessage).toContain('tpm.task.manage');
    expect(failed.log.actionResults).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          actionType: 'execute_command',
          status: 'failed',
        }),
      ]),
    );
    expect((await task()).tpm_ts_description).toBe(original);
    await expect(actor.getByTestId('automation-run-status')).toContainText(/失败|Failed/i);
    await expect(actor.getByTestId('flow-node-action_0')).toHaveAttribute('data-runtime-status', 'failed');
    await expect(actor.getByTestId('automation-test-run-result')).toContainText(
      '缺少执行该业务动作所需的权限',
    );
    await expect
      .poll(async () => {
        const canvas = actor.getByTestId('flow-canvas-shell');
        const bounds = await canvas.boundingBox();
        const nodes = await canvas.locator('.react-flow__node').evaluateAll((items) =>
          items.map((item) => {
            const r = item.getBoundingClientRect();
            return { x: r.x, right: r.right, y: r.y, bottom: r.bottom };
          }),
        );
        return (
          !!bounds &&
          nodes.length === 2 &&
          (nodes[0].right + 16 <= nodes[1].x || nodes[1].right + 16 <= nodes[0].x) &&
          nodes.every(
            (node) =>
              node.x >= bounds.x &&
              node.right <= bounds.x + bounds.width &&
              node.y >= bounds.y &&
              node.bottom <= bounds.y + bounds.height,
          )
        );
      })
      .toBe(true);
    await shot(actor, 'SC12-failed');
    await actor.getByTestId('automation-runtime-trace-link').click();
    await expect(actor).toHaveURL(new RegExp(`logId=${failed.log.id}`));
    await expect(actor.getByTestId('automation-run-status')).toContainText(/失败|Failed/i);
    await shot(actor, 'SC12-diagnosis');
    expect(failed.log.traceId).toBe(failed.traceId);
    expect(failed.traceId).toMatch(/^[a-f0-9]{32}$/);
    const traceLink = actor.getByTestId('automation-distributed-trace-link');
    await expect(traceLink).toHaveAttribute(
      'href',
      `/ops/troubleshooting?traceId=${failed.traceId}`,
    );
    const correlatedResponse = actor.waitForResponse(
      (r) => new URL(r.url()).pathname === `/api/observability/correlation/${failed.traceId}`,
    );
    await traceLink.click();
    const correlated = await (await correlatedResponse).json();
    expect(
      correlated.commandAudits.some(
        (a: any) => a.commandCode === 'tpm:update_task' && a.success === false,
      ),
    ).toBe(true);
    await expect(actor.getByTestId('troubleshooting-page')).toContainText('tpm:update_task');
    await shot(actor, 'SC12-trace');
    await actor.goBack();
    await expect(actor.getByTestId('automation-run-status')).toContainText(/失败|Failed/i);

    // The privileged maintenance change is explicit HTTP evidence, not a fabricated permission-editor UI action.
    await setManage(true);
    const updated = await data(await actor.request.get('/api/auth/me'));
    expect(updated.permissions.permissionCodes).toContain('tpm.task.manage');
    const successful = await run();
    expect(successful.log.status).toBe('success');
    expect(successful.log.pid).not.toBe(failed.log.pid);
    expect(successful.log.triggerRecordPid).toBe(taskPid);
    expect((await task()).tpm_ts_description).toBe(recovered);
    await expect(actor.getByTestId('automation-run-status')).toContainText(/成功|Success/i);
    await expect(actor.getByTestId('flow-node-action_0')).toHaveAttribute('data-runtime-status', 'completed');
    await expect(actor.getByTestId('flow-node-action_0-status-badge')).toHaveAttribute('aria-label', 'runtime status: completed');
    await shot(actor, 'SC12-recovered');
    const logs = await data(await page.request.get(`/api/automations/${automation.pid}/logs`));
    expect(
      logs.filter((l: any) => [failed.log.pid, successful.log.pid].includes(l.pid)),
    ).toHaveLength(2);
    expect(logs.find((l: any) => l.pid === failed.log.pid).status).toBe('failed');
    await menu(actor, '任务');
    // Retained runs may share a business title; verify the exact created record.
    await actor.goto(`/p/tpm_task/view/${taskPid}`);
    await expect(actor).toHaveURL(new RegExp(`/p/tpm_task/view/${taskPid}$`));
    await actor.reload();
    await expect(actor.getByRole('main')).toContainText(recovered);
    await shot(actor, 'SC12-persisted');
    await writeFile(
      join(evidence!, 'SC12-recovery-receipt.json'),
      JSON.stringify(
        {
          projectPid: project.pid,
          taskPid,
          taskName,
          automationPid: automation.pid,
          rolePid: role.pid,
          email,
          failed,
          successful,
          businessBefore: original,
          businessAfter: recovered,
          maintenanceDriver: 'public permission-matrix API; no owner/admin role changed',
          recoveryDriver: 'browser Test Run creates new run',
          historicalFailureRetained: true,
        },
        null,
        2,
      ),
    );
  } finally {
    await ctx.close();
  }
});
