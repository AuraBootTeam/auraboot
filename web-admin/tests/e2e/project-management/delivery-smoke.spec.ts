import { test, expect } from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';

const evidence = process.env.DELIVERY_EVIDENCE_ROOT;
if (!evidence) throw new Error('Set DELIVERY_EVIDENCE_ROOT to the runtime evidence round.');

test('customer delivery from menu through milestone completion @smoke', async ({ page }) => {
  const fixture = JSON.parse(await fs.readFile(path.join(evidence, 'fixture.json'), 'utf8'));
  const matrix = JSON.parse(await fs.readFile(path.join(evidence, 'acceptance-matrix.json'), 'utf8'));
  const network: unknown[] = [];
  page.on('response', async response => {
    if (/tpm_|datasource|activities/.test(response.url())) {
      try { network.push({ url: response.url(), status: response.status(), body: await response.json() }); } catch { /* Non-JSON assets are not API evidence. */ }
    }
  });
  const shot = async (id: string) => {
    const row = matrix.find((entry: { id: string }) => entry.id === id);
    if (!row) throw new Error(`Unregistered screenshot scenario: ${id}`);
    await fs.mkdir(path.join(evidence, 'screenshots'), { recursive: true });
    await page.screenshot({ path: path.join(evidence, 'screenshots', `${id}.png`) });
    row.status = 'captured';
    await fs.writeFile(path.join(evidence, 'acceptance-matrix.json'), JSON.stringify(matrix, null, 2));
  };
  try {
    await page.goto('/login');
    await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true');
    await page.locator('#identifier').fill(process.env.ADMIN_EMAIL!);
    await page.locator('#password').fill(process.env.ADMIN_PASSWORD!);
    await page.locator('form button[type=submit]').click();
    await page.waitForURL(url => !url.pathname.includes('/login'));
    await page.getByText('项目', { exact: true }).click();
    const projectName = fixture.projects[0].name;
    const projectRow = () => page.getByRole('row').filter({ hasText: projectName });
    await expect(projectRow()).toContainText('青禾零售');
    await expect(page.getByRole('row').filter({ hasText: '松果科技' })).toBeVisible();
    await expect(projectRow()).toContainText('林悦');
    await shot('P01');
    await page.getByRole('button', { name: '新建项目', exact: true }).click();
    await expect(page.locator('#tpm_pj_name')).toBeVisible();
    await expect(page.getByTestId('ai-fill-banner')).toHaveCount(0);
    await expect(page.getByText('客户与交付目标', { exact: true })).toBeVisible();
    await expect(page.getByText('交付计划', { exact: true })).toBeVisible();
    await shot('P02');
    await page.getByTestId('form-btn-submit').click();
    await expect(page.getByText(/请.*项目名称|项目名称.*(必填|不能为空)/).last()).toBeVisible();
    await shot('P03');
    await page.getByTestId('form-btn-cancel').click();
    await projectRow().getByRole('button', { name: /查看|详情/ }).click();
    await expect(page.getByTestId('metric-strip-value-progress')).toHaveText('50');
    await expect(page.getByTestId('detail-workspace-aside')).toBeVisible();
    const milestone = page.getByRole('row').filter({ hasText: '积分联调验收' });
    await expect(milestone).toContainText('待达成');
    await shot('P04');
    await milestone.getByRole('button', { name: '完成里程碑', exact: true }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await shot('P05');
    const commandResponse = page.waitForResponse(response => response.url().includes('/execute/tpm:reach_milestone') && response.request().method() === 'POST');
    await page.getByRole('dialog').getByRole('button', { name: /确认|确定/ }).click();
    const response = await commandResponse;
    const payload = response.request().postDataJSON();
    expect(payload.targetRecordPid).toBe(fixture.projects[0].milestones[2]);
    const body = await response.json();
    expect(body.code).toBe('0');
    expect(body.data.phaseReached).toBe('completed');
    await expect(milestone).toContainText('已达成');
    await expect(milestone.getByRole('button', { name: '完成里程碑', exact: true })).toHaveCount(0);
    await expect(page.getByTestId('metric-strip-value-progress')).toHaveText('75');
    await expect(page.getByTestId('detail-workspace-aside')).toContainText('积分联调验收');
    await shot('P06');
    await page.reload();
    await expect(page.getByTestId('metric-strip-value-progress')).toHaveText('75');
    await expect(milestone).toContainText('已达成');
    await expect(page.getByTestId('detail-workspace-aside')).toContainText('积分联调验收');
    await shot('P07');
    await page.getByText('项目', { exact: true }).click();
    await page.getByRole('button', { name: '规划中', exact: true }).click();
    await expect(page.getByRole('row').filter({ hasText: '松果科技' })).toBeVisible();
    await expect(projectRow()).toHaveCount(0);
    await shot('P08');
    await page.getByRole('button', { name: '新建项目', exact: true }).click();
    await page.getByTestId('user-select-trigger-tpm_pj_owner_user').click();
    await expect(page.getByTestId('user-select-search-tpm_pj_owner_user')).toBeVisible();
    await page.getByTestId('user-select-search-tpm_pj_owner_user').fill('林悦');
    await expect(page.getByText('林悦', { exact: true }).last()).toBeVisible();
    await shot('P09');
    await page.getByText('林悦', { exact: true }).last().click();
    const newProjectName = '雨岚设计 · 合同协作门户';
    await page.locator('#tpm_pj_name').fill(newProjectName);
    await page.locator('#tpm_pj_customer').fill('雨岚设计');
    await page.locator('#tpm_pj_description').fill('统一合同审批、版本管理与交付归档。（虚构示例数据）');
    await page.locator('#tpm_pj_budget').fill('36000');
    await shot('P11');
    const createResponse = page.waitForResponse(response => response.url().includes('/execute/tpm:create_project') && response.request().method() === 'POST');
    await page.getByTestId('form-btn-submit').click();
    expect((await (await createResponse).json()).data.phaseReached).toBe('completed');
    const newRow = page.getByRole('row').filter({ hasText: newProjectName });
    await expect(newRow).toContainText('林悦');
    await page.reload();
    await expect(newRow).toContainText('雨岚设计');
    await shot('P12');
    await fs.writeFile(path.join(evidence, 'completed-command.json'), JSON.stringify({ request: payload, response: body }, null, 2));
  } finally {
    await fs.writeFile(path.join(evidence, 'browser-network.json'), JSON.stringify(network, null, 2));
  }
});


test('delivery viewer can inspect progress but cannot edit or complete @smoke', async ({ page }) => {
  const fixture = JSON.parse(await fs.readFile(path.join(evidence, 'fixture.json'), 'utf8'));
  await page.goto('/login');
  await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true');
  await page.locator('#identifier').fill(fixture.viewerEmail);
  await page.locator('#password').fill(process.env.ADMIN_PASSWORD!);
  await page.locator('form button[type=submit]').click();
  await page.waitForURL(url => !url.pathname.includes('/login'));
  await page.getByText('项目', { exact: true }).click();
  await expect(page.getByRole('button', { name: '新建项目', exact: true })).toHaveCount(0);
  await page.getByRole('row').filter({ hasText: fixture.projects[0].name }).getByRole('button', { name: /查看|详情/ }).click();
  await expect(page.getByTestId('metric-strip-value-progress')).toHaveText('75');
  await expect(page.getByRole('button', { name: '编辑', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '完成里程碑', exact: true })).toHaveCount(0);
  await page.screenshot({ path: path.join(evidence, 'screenshots', 'P10.png') });
  const matrix = JSON.parse(await fs.readFile(path.join(evidence, 'acceptance-matrix.json'), 'utf8'));
  matrix.find((row: { id: string }) => row.id === 'P10').status = 'captured';
  await fs.writeFile(path.join(evidence, 'acceptance-matrix.json'), JSON.stringify(matrix, null, 2));
});
