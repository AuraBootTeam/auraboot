import { test, expect, type Page, type APIResponse, type Response } from '@playwright/test';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { loginDeliveryMember } from '../../helpers/delivery-session';
import { navigateToMenuByClick } from '../helpers';

const evidence = process.env.AURA_EVIDENCE_DIR;
if (!evidence) throw new Error('Bind the current owned evidence round');
test.use({ locale: 'zh-CN', actionTimeout: 15_000, storageState: { cookies: [], origins: [] } });
test.setTimeout(180_000);
async function data(response: APIResponse | Response) {
  const body = await response.json();
  expect(response.status(), JSON.stringify(body)).toBe(200);
  expect(String(body.code)).toBe('0');
  return body.data;
}
async function shot(page: Page, id: string) {
  await mkdir(join(evidence!, 'screenshots'), { recursive: true });
  await page.screenshot({ path: join(evidence!, 'screenshots', `${id}.png`), fullPage: true });
}
async function projects(page: Page) {
  await navigateToMenuByClick(page, ['项目交付', '项目']);
  await expect(page).toHaveURL(/\/p\/tpm_project$/);
  await expect(page.getByRole('button', { name: '新建项目', exact: true })).toBeVisible();
  await expect(page.locator('main[data-aura-scroll-container="page-content"]')).not.toContainText(
    /Page Unavailable|Internal Server Error/,
  );
}
async function designer(page: Page) {
  await navigateToMenuByClick(page, ['元数据管理', '页面配置']);
  const searched = page.waitForResponse(response => {
    const url = new URL(response.url());
    return url.pathname === '/api/dynamic/page_schema/list' && url.searchParams.get('keyword') === 'tpm_project_form';
  });
  await page.getByTestId('list-search-input').fill('tpm_project_form');
  await page.getByTestId('list-search-input').press('Enter');
  expect((await data(await searched)).records).toHaveLength(1);
  const row = page.getByRole('row').filter({ hasText: 'tpm_project_form' });
  await expect(row).toHaveCount(1);
  await row.getByText('统一设计器', { exact: true }).click();
  await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();
  const pid =
    new URL(page.url()).searchParams.get('pid') ||
    new URL(page.url()).searchParams.get('pageId') ||
    '';
  expect(pid).toMatch(/^[0-9A-Z]{26}$/);
  return pid;
}

test('mature project page is refined with real undo redo, save recovery, publish and runtime readback @critical', async ({
  page,
}, info) => {
  await loginDeliveryMember(page);
  const pid = await designer(page);
  const original = await data(await page.request.get(`/api/pages/${pid}`));
  expect(original.modelCode).toBe('tpm_project');
  await expect(page.getByTestId('designer-publish')).toBeDisabled();
  const withdrawing = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === `/api/pages/${pid}/unpublish` &&
      response.request().method() === 'POST',
  );
  await page.getByTestId('designer-unpublish').click();
  await data(await withdrawing);
  const sectionOrder = () =>
    page
      .locator('[data-testid^="outline-item-"]')
      .evaluateAll((elements) =>
        elements
          .map((element) => element.getAttribute('data-testid'))
          .filter((id) => ['outline-item-outcome', 'outline-item-plan'].includes(id!)),
      );
  await expect.poll(sectionOrder).toEqual(['outline-item-outcome', 'outline-item-plan']);
  const moving = await page.getByTestId('outline-item-plan').boundingBox();
  const target = await page.getByTestId('outline-item-outcome').boundingBox();
  expect(moving).not.toBeNull();
  expect(target).not.toBeNull();
  await page.mouse.move(moving!.x + 16, moving!.y + moving!.height / 2);
  await page.mouse.down();
  await page.mouse.move(target!.x + 16, target!.y + 2, { steps: 12 });
  await page.mouse.up();
  await expect.poll(sectionOrder).toEqual(['outline-item-plan', 'outline-item-outcome']);
  await shot(page, 'PD00-real-outline-reorder');
  await page.getByTestId('designer-undo').click();
  await expect.poll(sectionOrder).toEqual(['outline-item-outcome', 'outline-item-plan']);
  await page.getByTestId('designer-redo').click();
  await expect.poll(sectionOrder).toEqual(['outline-item-plan', 'outline-item-outcome']);
  await page.getByTestId('designer-undo').click();
  await expect.poll(sectionOrder).toEqual(['outline-item-outcome', 'outline-item-plan']);
  const originalDescription = original.blocks.find((block: any) => block.id === 'outcome').fields.find((field: any) => (typeof field === 'string' ? field : field.field) === 'tpm_pj_description');
  const descriptionId = originalDescription.id ?? 'outcome_tpm_pj_description';
  const moveDescription = async (parent: string) => {
    const source = await page.getByTestId(`outline-item-${descriptionId}`).boundingBox();
    const destination = await page.getByTestId(`outline-item-${parent}`).boundingBox();
    expect(source).not.toBeNull(); expect(destination).not.toBeNull();
    await page.mouse.move(source!.x + 16, source!.y + source!.height / 2);
    await page.mouse.down();
    await page.mouse.move(destination!.x + 40, destination!.y + destination!.height / 2, { steps: 14 });
    await page.mouse.up();
    await expect(page.getByTestId(`canvas-block-${parent}`).getByTestId(`canvas-block-${descriptionId}`)).toBeAttached();
  };
  await moveDescription('plan');
  await page.getByTestId(`canvas-block-${descriptionId}`).scrollIntoViewIfNeeded();
  await shot(page, 'PD00b-nested-field-moved');
  const nestingSave = page.waitForResponse(response => new URL(response.url()).pathname === `/api/pages/${pid}` && response.request().method() === 'PUT');
  await page.getByTestId('designer-save').click(); expect((await nestingSave).status()).toBe(200);
  const nested = await data(await page.request.get(`/api/pages/${pid}`));
  const fieldNames = (section: any) => section.fields.map((field: any) => typeof field === 'string' ? field : field.field);
  expect(fieldNames(nested.blocks.find((block: any) => block.id === 'plan'))).toContain('tpm_pj_description');
  expect(fieldNames(nested.blocks.find((block: any) => block.id === 'outcome'))).not.toContain('tpm_pj_description');
  await page.reload();
  await expect(page.getByTestId('canvas-block-plan').getByTestId(`canvas-block-${descriptionId}`)).toBeAttached();
  await page.getByTestId(`canvas-block-${descriptionId}`).scrollIntoViewIfNeeded();
  await shot(page, 'PD00c-nested-persisted-reopened');
  await moveDescription('outcome');
  const originalTitle = original.blocks.find((block: any) => block.id === 'outcome').title;
  const before = typeof originalTitle === 'string' ? originalTitle : originalTitle['zh-CN'];
  const refined = before === '客户与交付背景' ? '客户与项目背景' : '客户与交付背景';
  await page.getByTestId('outline-item-outcome').click();
  const title = page.getByTestId('inspector-field-title');
  await expect(title).toHaveValue(before);
  await page.getByTestId('canvas-block-outcome').scrollIntoViewIfNeeded();
  await expect(page.getByTestId('canvas-block-outcome')).toBeInViewport();
  await shot(page, 'PD01-project-form-designer');
  await title.fill(refined);
  await expect(title).toHaveValue(refined);
  await expect(page.getByTestId('designer-undo')).toBeEnabled();
  await page.getByTestId('designer-undo').click();
  await expect(title).toHaveValue(before);
  await page.getByTestId('designer-redo').click();
  await expect(title).toHaveValue(refined);
  await page.getByTestId('canvas-block-outcome').scrollIntoViewIfNeeded();
  await expect(page.getByTestId('canvas-block-outcome')).toBeInViewport();
  await shot(page, 'PD02-project-form-refined');

  const beforeRejectedSave = await data(await page.request.get(`/api/pages/${pid}`));
  // A transport failure is an explicit UI-state probe, not a real database outage.
  await page.route(`**/api/pages/${pid}`, (route) =>
    route.request().method() === 'PUT'
      ? route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({ code: '503', message: 'Temporary test transport failure' }),
        })
      : route.continue(),
  );
  const rejected = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === `/api/pages/${pid}` &&
      response.request().method() === 'PUT',
  );
  await page.getByTestId('designer-save').click();
  expect((await rejected).status()).toBe(503);
  await expect(title).toHaveValue(refined);
  await expect(page.getByTestId('designer-save')).toBeEnabled();
  const unchanged = await data(await page.request.get(`/api/pages/${pid}`));
  expect(unchanged.blocks).toEqual(beforeRejectedSave.blocks);
  await shot(page, 'PD03-save-failure-retains-draft-injected');
  await page.unroute(`**/api/pages/${pid}`);
  const saving = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === `/api/pages/${pid}` &&
      response.request().method() === 'PUT',
  );
  await page.getByTestId('designer-save').click();
  await data(await saving);
  const saved = await data(await page.request.get(`/api/pages/${pid}`));
  expect(saved.schemaVersion).toBe(4);
  for (const id of ['plan', 'delivery_actions', 'supplement']) {
    expect(saved.blocks.find((block: any) => block.id === id)).toEqual(
      original.blocks.find((block: any) => block.id === id),
    );
  }
  await expect(page.getByTestId('designer-publish')).toBeEnabled();
  expect(saved.blocks.find((block: any) => block.id === 'outcome').title['zh-CN']).toBe(refined);
  expect(
    saved.blocks
      .flatMap((block: any) => block.fields ?? [])
      .map((field: any) => (typeof field === 'string' ? field : field.field)),
  ).toEqual(
    original.blocks
      .flatMap((block: any) => block.fields ?? [])
      .map((field: any) => (typeof field === 'string' ? field : field.field)),
  );
  const publishing = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === `/api/pages/${pid}/publish` &&
      response.request().method() === 'POST',
  );
  await page.getByTestId('designer-publish').click();
  await data(await publishing);
  await page.reload();
  await page.getByTestId('outline-item-outcome').click();
  await expect(page.getByTestId('inspector-field-title')).toHaveValue(refined);
  await shot(page, 'PD04-designer-published-reopened');
  await info.attach('PD-project-page-persisted', {
    body: JSON.stringify({
      before: original,
      after: await data(await page.request.get(`/api/pages/${pid}`)),
    }),
    contentType: 'application/json',
  });
  await projects(page);
  await page.getByRole('button', { name: '新建项目', exact: true }).click();
  await expect(page.getByText(refined, { exact: true })).toBeVisible();
  await expect(page.locator('#tpm_pj_name')).toBeVisible();
  await shot(page, 'PD05-refined-project-form-runtime');
});

test('mature project creation and editing preserve customer owner dates budget and uploaded evidence @critical', async ({
  page,
}, info) => {
  const identity = await loginDeliveryMember(page);
  await projects(page);
  await expect(page.getByRole('row').filter({ hasText: '华辰制造 · 供应链协同平台' })).toHaveCount(1);
  await expect(page.getByRole('columnheader', { name: /负责人$/ })).toBeVisible();
  await shot(page, 'PJ01-mature-project-list');
  await page.getByRole('button', { name: '新建项目', exact: true }).click();
  const name = `华辰制造 · 服务交付二期 · ${Date.now().toString(36)}`;
  await page.locator('#tpm_pj_name').fill(name);
  await page.getByTestId('select-trigger-tpm_pj_customer_id').click();
  await page.getByRole('option', { name: '华辰制造', exact: true }).click();
  await page.getByTestId('user-select-trigger-tpm_pj_owner_user').click();
  await page.getByTestId(`user-select-option-tpm_pj_owner_user-${identity.user.pid}`).click();
  await page
    .locator('#tpm_pj_description')
    .fill('分阶段交付客户门户、权限配置与供应链协同，按里程碑核对范围和交付材料。');
  await page.locator('#tpm_pj_start_date').fill('2026-11-01');
  await page.locator('#tpm_pj_end_date').fill('2026-12-20');
  await page.locator('#tpm_pj_budget').fill('268000');
  await page.locator('#tpm_pj_risk_summary').fill('等待客户确认接口字段，已安排联合评审。');
  await page.locator('#tpm_pj_next_action').fill('11月5日前完成字段映射评审，负责人林晓。');
  await page.getByTestId('form-section-supplement').locator('summary').click();
  const fileBytes = Buffer.from(
    '华辰制造交付二期\n范围：客户门户、权限、供应链接口\n验收：按里程碑及交付清单核对\n',
  );
  const upload = page.waitForResponse(
    (response) =>
      response.url().includes('/api/file/upload') && response.request().method() === 'POST',
  );
  await page
    .getByTestId('form-field-tpm_pj_files')
    .locator('input[type=file]')
    .setInputFiles({ name: '二期交付范围清单.txt', mimeType: 'text/plain', buffer: fileBytes });
  const file = await data(await upload);
  await expect(page.getByTestId('form-field-tpm_pj_files')).toContainText('二期交付范围清单.txt');
  await page
    .locator('main[data-aura-scroll-container="page-content"]')
    .evaluate((element) => element.scrollTo({ top: 0 }));
  await shot(page, 'PJ02-mature-project-filled');
  await page.getByTestId('form-field-tpm_pj_files').scrollIntoViewIfNeeded();
  await shot(page, 'PJ02-project-evidence-and-notes');
  const creating = page.waitForResponse(
    (response) =>
      response.url().includes('/execute/tpm:create_project') &&
      response.request().method() === 'POST',
  );
  await page.getByTestId('form-btn-submit').click();
  const created = await data(await creating);
  expect(created.phaseReached).toBe('completed');
  const pid = String(created.data.recordPid);
  expect(pid).toMatch(/^[0-9A-Z]{26}$/);
  const row = page.getByRole('row').filter({ has: page.getByText(name, { exact: true }) });
  await expect(row).toBeVisible();
  await row.getByRole('button', { name: /查看|详情/ }).click();
  await expect(page.getByTestId('object-summary')).toContainText('268,000');
  await expect(page.getByTestId('attachment-download-0')).toContainText('二期交付范围清单.txt');
  const downloading = page.waitForEvent('download');
  await page.getByTestId('attachment-download-0').click();
  const download = await downloading;
  expect(await readFile((await download.path())!)).toEqual(fileBytes);
  await shot(page, 'PJ03-mature-project-created');
  await page.getByTestId('toolbar-btn-edit').click();
  await expect(page.locator('#tpm_pj_name')).toHaveValue(name);
  await expect(page.getByTestId('select-trigger-tpm_pj_customer_id')).toContainText('华辰制造');
  await expect(page.getByTestId('user-select-trigger-tpm_pj_owner_user')).toContainText(
    'Admin User',
  );
  await page.locator('#tpm_pj_budget').click();
  await page.locator('#tpm_pj_budget').fill('286000');
  await expect(page.locator('#tpm_pj_budget')).toHaveValue('286000');
  await page.locator('#tpm_pj_budget').press('Tab');
  await expect(page.locator('#tpm_pj_budget')).toHaveValue('286,000.00');
  await page.locator('#tpm_pj_next_action').fill('范围已确认，11月8日开始接口联调。');
  await page
    .locator('main[data-aura-scroll-container="page-content"]')
    .evaluate((element) => element.scrollTo({ top: 0 }));
  await expect(page.getByText('保存成功', { exact: true })).toHaveCount(0);
  await shot(page, 'PJ04-mature-project-edit');
  await expect(page.getByTestId('form-btn-submit')).toHaveCount(0);
  await expect(page.getByTestId('form-btn-update')).toBeVisible();
  const updating = page.waitForResponse(
    (response) =>
      response.url().includes('/execute/tpm:update_project') &&
      response.request().method() === 'POST',
  );
  await page.getByTestId('form-btn-update').click();
  expect((await data(await updating)).phaseReached).toBe('completed');
  await expect(page).toHaveURL(/\/p\/tpm_project$/);
  const reopened = page.getByRole('row').filter({ has: page.getByText(name, { exact: true }) });
  await reopened.getByRole('button', { name: '查看', exact: true }).click();
  await expect(page.getByTestId('object-summary')).toBeVisible();
  await page.reload();
  const persisted = await data(await page.request.get(`/api/dynamic/tpm_project/${pid}`));
  expect(persisted).toMatchObject({
    tpm_pj_name: name,
    tpm_pj_budget: 286000,
    tpm_pj_owner_user: identity.user.pid,
    tpm_pj_start_date: '2026-11-01',
    tpm_pj_end_date: '2026-12-20',
    tpm_pj_next_action: '范围已确认，11月8日开始接口联调。',
  });
  expect(JSON.stringify(persisted.tpm_pj_files)).toContain(String(file.fileId));
  await expect(page.getByTestId('object-summary')).toContainText('286,000');
  await expect(page.getByRole('main')).toContainText('范围已确认，11月8日开始接口联调。');
  const summary = await page.getByTestId('object-summary').locator(':scope > div').evaluateAll(items => items.map(item => item.getBoundingClientRect().top));
  expect(summary).toHaveLength(5); expect(new Set(summary).size).toBe(1);
  await shot(page, 'PJ05-mature-project-reopened');
  await writeFile(
    join(evidence!, 'mature-project-identity.json'),
    JSON.stringify({ pid, name, fileId: file.fileId }, null, 2),
  );
  await info.attach('PJ-persisted-result', {
    body: JSON.stringify(persisted),
    contentType: 'application/json',
  });
});
