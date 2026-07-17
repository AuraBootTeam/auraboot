import { test, expect, type Page, type Response, type TestInfo } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { loginViaUI } from '../../helpers/wd-fixtures';
import {
  clickRowActionByLocator,
  ensureSidebarExpanded,
  waitForDynamicPageLoad,
} from '../helpers';

type ApiEnvelope<T> = {
  code?: number | string;
  success?: boolean;
  message?: string;
  msg?: string;
  data?: T;
};

type FieldPreflightResult = {
  fieldRef: string;
  action: string;
  allowed: boolean;
  blocked: boolean;
  requiresAcknowledgement: boolean;
  message?: string;
  risk?: {
    summary?: string;
    counts?: Record<string, number>;
  };
};

test.use({ storageState: { cookies: [], origins: [] } });
test.setTimeout(90_000);

async function readApi<T>(response: Response): Promise<T> {
  const body = (await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }))) as ApiEnvelope<T>;
  expect(response.ok(), `HTTP ${response.status()} ${response.url()}: ${JSON.stringify(body)}`).toBe(true);
  const code = body.code;
  expect(code === undefined || code === null || String(code) === '0', JSON.stringify(body)).toBe(true);
  expect(body.success === false, JSON.stringify(body)).toBe(false);
  return body.data as T;
}

async function openModelFieldsFromSidebar(page: Page): Promise<void> {
  if (!/\/home(?:$|\?)/.test(page.url())) {
    await page.goto('/home', { waitUntil: 'domcontentloaded' });
  }
  await ensureSidebarExpanded(page);

  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const parent = nav
    .getByRole('button', { name: /规则中心|Rule Center|决策中心|DecisionOps/i })
    .or(nav.getByRole('link', { name: /规则中心|Rule Center|决策中心|DecisionOps/i }))
    .first();
  const modelFieldsLink = nav
    .locator('a[href="/p/decisionops_model_fields"]')
    .or(nav.getByRole('link', { name: /数据模型|Data Model/i }))
    .first();

  if (!(await modelFieldsLink.isVisible({ timeout: 1_000 }).catch(() => false))) {
    await expect(parent).toBeVisible({ timeout: 10_000 });
    await parent.click();
  }

  await expect(modelFieldsLink).toBeVisible({ timeout: 10_000 });
  await modelFieldsLink.scrollIntoViewIfNeeded();
  await modelFieldsLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_model_fields(?:$|\?)/, { timeout: 15_000 });
  await waitForDynamicPageLoad(page);
}

async function runPreflight(
  page: Page,
): Promise<{ request: Record<string, unknown>; data: FieldPreflightResult }> {
  const responsePromise = page.waitForResponse(
    (response) =>
      response.url().includes('/api/decision/fields/preflight') &&
      response.request().method() === 'POST',
    { timeout: 15_000 },
  );
  await page.getByTestId('field-preflight-run').click();
  const response = await responsePromise;
  const request = response.request().postDataJSON() as Record<string, unknown>;
  const data = await readApi<FieldPreflightResult>(response);
  return { request, data };
}

async function capturePanel(
  page: Page,
  testInfo: TestInfo,
  fileName = 'decisionops-field-impact-model-change-preflight.png',
): Promise<void> {
  const panel = page.getByTestId('decision-field-impact');
  await panel.scrollIntoViewIfNeeded();
  await panel.screenshot({
    path: testInfo.outputPath(fileName),
  });
}

test('DecisionOps model field impact preflights dict, data type, permission, and virtual source changes @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  await openModelFieldsFromSidebar(page);
  await expect(page.locator('thead th, [role="columnheader"]').first()).toBeVisible({
    timeout: 10_000,
  });
  await expect(page.locator('main, [role="main"]').first()).toContainText(/字段路径|Field Path/, {
    timeout: 10_000,
  });

  const targetRow = page
    .locator('tbody tr')
    .filter({ hasText: 'process' })
    .filter({ hasText: 'nodeId' })
    .first();
  await expect(targetRow).toBeVisible({ timeout: 15_000 });
  await clickRowActionByLocator(page, targetRow, 'impact', '影响');

  await expect(page).toHaveURL(/\/p\/decisionops_model_fields_impact(?:$|\?)/, {
    timeout: 15_000,
  });
  await expect(page.getByTestId('decision-field-impact')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByLabel('field-impact-ref')).toHaveValue('process.nodeId');
  await expect(page.getByLabel('field-impact-current-type')).toHaveValue('string');
  await expect(page.getByTestId('field-impact-risk')).toContainText('影响 1 个决策版本', {
    timeout: 15_000,
  });
  await expect(page.getByTestId('field-impact-counts')).toContainText('BPM 流程: 1');
  await expect(page.getByTestId('field-impact-counts')).toContainText('决策版本: 1');
  await expect(page.getByTestId('field-impact-references')).toContainText('BPM 流程');
  await expect(page.getByTestId('field-impact-references')).toContainText('决策版本');

  await page.getByLabel('field-preflight-action').selectOption({ label: '删除字典值' });
  await page.getByLabel('field-impact-dict-code').fill('leave_type');
  await page.getByLabel('field-impact-dict-value').fill('annual');
  const dictBlocked = await runPreflight(page);
  expect(dictBlocked.request).toMatchObject({
    fieldRef: 'process.nodeId',
    action: 'DELETE_DICT_ITEM',
    currentDataType: 'string',
    dictCode: 'leave_type',
    dictValue: 'annual',
    impactAcknowledged: false,
  });
  expect(dictBlocked.data).toMatchObject({
    action: 'DELETE_DICT_ITEM',
    blocked: true,
    requiresAcknowledgement: true,
  });
  await expect(page.getByTestId('field-preflight-result')).toContainText('已阻断');
  await expect(page.getByTestId('field-preflight-result')).toContainText(
    '字段变更需要确认影响面：影响 1 个决策版本',
  );

  await page.getByTestId('field-preflight-ack').check();
  const dictAllowed = await runPreflight(page);
  expect(dictAllowed.data).toMatchObject({
    action: 'DELETE_DICT_ITEM',
    allowed: true,
    blocked: false,
  });
  await expect(page.getByTestId('field-preflight-result')).toContainText('可执行');
  await expect(page.getByTestId('field-preflight-result')).toContainText(
    '确认影响面后可执行字段变更：影响 1 个决策版本',
  );

  await page.getByLabel('field-preflight-action').selectOption({ label: '变更数据类型' });
  await page.getByLabel('field-impact-next-type').fill('decimal');
  await page.getByTestId('field-preflight-ack').uncheck();
  const typeBlocked = await runPreflight(page);
  expect(typeBlocked.request).toMatchObject({
    fieldRef: 'process.nodeId',
    action: 'CHANGE_DATA_TYPE',
    currentDataType: 'string',
    nextDataType: 'decimal',
    impactAcknowledged: false,
  });
  expect(typeBlocked.data).toMatchObject({
    action: 'CHANGE_DATA_TYPE',
    allowed: false,
    blocked: true,
    requiresAcknowledgement: true,
  });
  await expect(page.getByTestId('field-preflight-result')).toContainText('已阻断');
  await expect(page.getByTestId('field-preflight-result')).toContainText(
    '字段变更需要确认影响面：影响 1 个决策版本',
  );

  await page.getByTestId('field-preflight-ack').check();
  const typeAllowed = await runPreflight(page);
  expect(typeAllowed.request).toMatchObject({
    fieldRef: 'process.nodeId',
    action: 'CHANGE_DATA_TYPE',
    currentDataType: 'string',
    nextDataType: 'decimal',
    impactAcknowledged: true,
  });
  expect(typeAllowed.data).toMatchObject({
    action: 'CHANGE_DATA_TYPE',
    allowed: true,
    blocked: false,
  });
  await expect(page.getByTestId('field-preflight-result')).toContainText('可执行');
  await capturePanel(page, testInfo, 'decisionops-field-impact-datatype-preflight.png');

  await page.getByLabel('field-preflight-action').selectOption({ label: '变更字段权限' });
  await page.getByLabel('field-impact-next-permission').fill('manager.visible');
  const permissionAllowed = await runPreflight(page);
  expect(permissionAllowed.request).toMatchObject({
    fieldRef: 'process.nodeId',
    action: 'CHANGE_PERMISSION',
    currentDataType: 'string',
    nextPermission: 'manager.visible',
    impactAcknowledged: true,
  });
  expect(permissionAllowed.data).toMatchObject({
    action: 'CHANGE_PERMISSION',
    allowed: true,
    blocked: false,
  });
  await expect(page.getByTestId('field-preflight-result')).toContainText('可执行');

  await page.getByLabel('field-preflight-action').selectOption({ label: '变更虚拟来源' });
  await page.getByLabel('field-impact-next-source-ref').fill('virtual.leave_request_summary.v2');
  const virtualAllowed = await runPreflight(page);
  expect(virtualAllowed.request).toMatchObject({
    fieldRef: 'process.nodeId',
    action: 'CHANGE_VIRTUAL_SOURCE',
    currentDataType: 'string',
    nextSourceRef: 'virtual.leave_request_summary.v2',
    impactAcknowledged: true,
  });
  expect(virtualAllowed.data).toMatchObject({
    action: 'CHANGE_VIRTUAL_SOURCE',
    allowed: true,
    blocked: false,
  });
  await expect(page.getByTestId('field-preflight-result')).toContainText('可执行');

  const panelText = await page.getByTestId('decision-field-impact').innerText();
  expect(panelText).not.toMatch(
    /Used by|DELETE_DICT_ITEM|CHANGE_PERMISSION|CHANGE_VIRTUAL_SOURCE|DECISION_VERSION|BPM_PROCESS/,
  );
  await capturePanel(page, testInfo);
});
