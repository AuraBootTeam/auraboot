import { test, expect, type APIResponse, type Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { loginViaUI } from '../../helpers/wd-fixtures';
import { ensureSidebarExpanded } from '../helpers';

type ApiEnvelope<T> = {
  code?: number | string;
  success?: boolean;
  message?: string;
  msg?: string;
  data?: T;
};

type DecisionFactCatalog = {
  entities?: Array<{
    modelCode?: string;
    facts?: DecisionFact[];
  }>;
  facts?: DecisionFact[];
};

type DecisionFact = {
  path?: string;
  label?: string;
  dataType?: string;
  dictCode?: string;
  allowedValues?: Array<{ value?: unknown; label?: string }>;
};

type DecisionVersion = {
  pid: string;
  status?: string;
  version?: number;
  kind?: string;
  runtimeAdapter?: string;
  contentJson?: DecisionTableModel;
};

type DecisionResult = {
  status?: string;
  matched?: boolean;
  outputs?: Record<string, unknown>;
  matchedRules?: Array<{ ruleId?: string }>;
  traceId?: string;
};

type DecisionTableModel = {
  hitPolicy?: string;
  inputs?: Array<{
    id?: string;
    label?: string;
    scope?: string;
    path?: string;
    dataType?: string;
    allowedValues?: string[];
    valueLabels?: Record<string, string>;
  }>;
  outputs?: Array<{
    id?: string;
    label?: string;
    dataType?: string;
    allowedValues?: string[];
    valueLabels?: Record<string, string>;
  }>;
  rules?: Array<{
    ruleId?: string;
    when?: Record<string, { operator?: string; value?: unknown; feel?: string }>;
    then?: Record<string, unknown>;
  }>;
  defaultOutput?: Record<string, unknown>;
};

type DecisionTableDmnXmlResult = {
  valid?: boolean;
  dmnXml?: string;
  model?: DecisionTableModel;
  errors?: Array<{ code?: string; message?: string }>;
};

test.use({ storageState: { cookies: [], origins: [] } });
test.describe.configure({ mode: 'serial' });
test.setTimeout(120_000);

function isApiSuccess<T>(body: ApiEnvelope<T> | null | undefined): body is ApiEnvelope<T> {
  if (!body) return false;
  if (body.success === false) return false;
  const code = body.code;
  return code === undefined || code === null || String(code) === '0';
}

async function readApi<T>(response: Pick<APIResponse, 'json' | 'text' | 'ok' | 'status' | 'url'>): Promise<T> {
  const body = (await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }))) as ApiEnvelope<T>;
  expect(response.ok(), `HTTP ${response.status()} ${response.url()}: ${JSON.stringify(body)}`).toBe(true);
  expect(isApiSuccess(body), `API failed: ${JSON.stringify(body)}`).toBe(true);
  return body.data as T;
}

async function postApi<T>(page: Page, endpoint: string, data?: unknown): Promise<T> {
  const options = data === undefined ? undefined : { data };
  return readApi<T>(await page.request.post(endpoint, options));
}

async function getApi<T>(page: Page, endpoint: string): Promise<T> {
  return readApi<T>(await page.request.get(endpoint));
}

async function openRuleCenterFromSidebar(page: Page): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const ruleCenterLink = nav
    .locator('a[href="/decision-ops"]')
    .or(nav.getByRole('link', { name: /规则中心|Rule Center|决策中心|DecisionOps/i }))
    .first();

  if (!(await ruleCenterLink.isVisible({ timeout: 1_000 }).catch(() => false))) {
    const parent = nav
      .getByRole('button', { name: /规则中心|Rule Center|决策中心|DecisionOps/i })
      .first();
    await expect(parent).toBeVisible({ timeout: 10_000 });
    await parent.click();
  }

  await expect(ruleCenterLink).toBeVisible({ timeout: 10_000 });
  await ruleCenterLink.scrollIntoViewIfNeeded();
  await ruleCenterLink.click();
  await expect(page).toHaveURL(/\/decision-ops(?:$|\?)/, { timeout: 15_000 });
  await expect(page.getByTestId('decisionops-console')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('strategy-studio')).toBeVisible({ timeout: 15_000 });
}

function findLeaveTypeFact(catalog: DecisionFactCatalog): DecisionFact | undefined {
  const facts = [
    ...(catalog.facts ?? []),
    ...(catalog.entities ?? []).flatMap((entity) => entity.facts ?? []),
  ];
  return facts.find((fact) => fact.path === 'record.data.wd_req_type' || fact.path === 'data.wd_req_type');
}

async function selectedOptionLabels(page: Page, selectorLabel: string): Promise<string[]> {
  return page.getByLabel(selectorLabel).evaluate((select) =>
    Array.from((select as HTMLSelectElement).selectedOptions).map((option) => option.textContent?.trim() ?? ''),
  );
}

function optionLabel(fact: DecisionFact | undefined, value: string): string {
  const label = fact?.allowedValues?.find((option) => String(option.value) === value)?.label;
  expect(label, `fact option label for ${value}`).toBeTruthy();
  return label!;
}

function escapeXmlAttribute(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

async function deleteAllDecisionTableRows(page: Page): Promise<void> {
  const deleteButtons = page.locator('button[aria-label^="delete-row-"]');
  for (let guard = 0; guard < 20; guard += 1) {
    const count = await deleteButtons.count();
    if (count === 0) return;
    await page.getByLabel('delete-row-0').click();
    await expect(deleteButtons).toHaveCount(count - 1);
  }
  throw new Error('Decision table still has rows after 20 delete attempts');
}

async function configureLeaveTypeRule(
  page: Page,
  operator: 'IN' | 'NOT_IN',
  labels: { annual: string; sick: string },
): Promise<void> {
  await page.getByTestId('strategy-workspace-tab-dmn').click();
  await expect(page.getByTestId('strategy-dmn-panel')).toHaveAttribute('data-active', 'true');
  await page.getByTestId('dt-input-field-picker-0').click();
  await expect(page.getByTestId('dt-input-field-picker-panel-0')).toBeVisible({ timeout: 10_000 });
  await page.getByLabel('input-field-search-0').fill('wd_req_type');
  await expect(page.getByTestId('dt-input-field-picker-panel-0')).toContainText(/请假类型|Leave Type/i);
  await page.getByTestId('dt-input-field-option-0-record-data_wd_req_type').click();
  await expect(page.getByTestId('dt-in-record_data_wd_req_type')).toContainText(/请假类型|Leave Type/i);

  await deleteAllDecisionTableRows(page);
  await page.getByTestId('dt-add-rule').click();
  await page.getByLabel('op-0-record_data_wd_req_type').selectOption(operator);
  await page.getByLabel('val-0-record_data_wd_req_type').selectOption(['annual', 'sick']);
  await page.getByLabel('out-0-route').selectOption('notify');
  await expect(page.getByLabel('op-0-record_data_wd_req_type')).toHaveValue(operator);
  await expect.poll(() => selectedOptionLabels(page, 'val-0-record_data_wd_req_type')).toEqual(
    expect.arrayContaining([labels.annual, labels.sick]),
  );
}

async function saveDraftAndPublishStrategyTable(page: Page): Promise<DecisionVersion> {
  const draftResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/decision/definitions/complaint_sla_deadline/versions'),
  );
  await page.getByTestId('strategy-save-draft').click();
  const draft = await readApi<DecisionVersion>(await draftResponsePromise);
  expect(draft.pid).toBeTruthy();
  await expect(page.getByTestId('strategy-operation-status')).toContainText('草稿已保存', {
    timeout: 15_000,
  });

  const validateResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/decision/versions/${encodeURIComponent(draft.pid)}/validate`),
  );
  const publishResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/decision/versions/${encodeURIComponent(draft.pid)}/publish`),
  );
  await page.getByTestId('strategy-publish').click();
  const validation = await readApi<{ valid?: boolean }>(await validateResponsePromise);
  expect(validation.valid).toBe(true);
  const published = await readApi<DecisionVersion>(await publishResponsePromise);
  expect(String(published.status ?? '')).toMatch(/published/i);
  await expect(page.getByTestId('strategy-operation-status')).toContainText('发布成功', {
    timeout: 15_000,
  });

  const detail = await getApi<DecisionVersion>(
    page,
    `/api/decision/versions/${encodeURIComponent(published.pid)}`,
  );
  expect(detail.contentJson, `published version ${published.pid} should include contentJson`).toBeTruthy();
  return detail;
}

function tableWithoutDefaultOutput(version: DecisionVersion): DecisionTableModel {
  expect(version.contentJson, `version ${version.pid} should contain a decision table`).toBeTruthy();
  return {
    ...version.contentJson!,
    defaultOutput: {},
  };
}

async function runDraftTable(page: Page, table: DecisionTableModel, leaveType: string): Promise<DecisionResult> {
  return postApi<DecisionResult>(page, '/api/decision/test-run', {
    kind: 'DECISION_TABLE',
    runtimeAdapter: 'PLATFORM_DECISION_TABLE',
    contentJson: table,
    context: {
      record: {
        modelCode: 'wd_leave_request',
        data: {
          wd_req_type: leaveType,
        },
      },
    },
  });
}

function leaveTypeCell(version: DecisionVersion) {
  return version.contentJson?.rules?.[0]?.when?.record_data_wd_req_type;
}

test('Strategy Studio DMN round-trip preserves fact catalog valueLabels @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const catalog = await readApi<DecisionFactCatalog>(await page.request.get('/api/decision/facts/catalog'));
  const leaveTypeFact = findLeaveTypeFact(catalog);
  expect(leaveTypeFact?.dictCode).toBe('wd_leave_type');
  expect(leaveTypeFact?.allowedValues).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ value: 'annual', label: expect.stringMatching(/年假|Annual/i) }),
      expect.objectContaining({ value: 'sick', label: expect.stringMatching(/病假|Sick/i) }),
    ]),
  );
  const annualLabel = optionLabel(leaveTypeFact, 'annual');
  const sickLabel = optionLabel(leaveTypeFact, 'sick');

  await openRuleCenterFromSidebar(page);
  await expect(page.getByTestId('strategy-consumer-summary')).toContainText(/SLA|超时通知/);
  await page.getByTestId('strategy-workspace-tab-dmn').click();
  await expect(page.getByTestId('strategy-dmn-panel')).toHaveAttribute('data-active', 'true');
  await expect(page.getByTestId('strategy-dmn-panel')).toContainText('DMN 决策输出');

  await page.getByTestId('dt-input-field-picker-0').click();
  await expect(page.getByTestId('dt-input-field-picker-panel-0')).toBeVisible({ timeout: 10_000 });
  await page.getByLabel('input-field-search-0').fill('wd_req_type');
  await expect(page.getByTestId('dt-input-field-picker-panel-0')).toContainText(/请假类型|Leave Type/i);
  await page.getByTestId('dt-input-field-option-0-record-data_wd_req_type').click();

  await expect(page.getByTestId('dt-in-record_data_wd_req_type')).toContainText(/请假类型|Leave Type/i);
  await expect(page.getByLabel('input-data-type-0')).toHaveValue(/dict|enum/);

  await deleteAllDecisionTableRows(page);
  await page.getByTestId('dt-add-rule').click();
  await page.getByLabel('op-0-record_data_wd_req_type').selectOption('IN');
  await page.getByLabel('val-0-record_data_wd_req_type').selectOption(['annual', 'sick']);
  await expect.poll(() => selectedOptionLabels(page, 'val-0-record_data_wd_req_type')).toEqual(
    expect.arrayContaining([annualLabel, sickLabel]),
  );

  const roundTripResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/decision/tables/round-trip'),
  );
  await page.getByTestId('dt-roundtrip-dmn').click();
  const roundTrip = await readApi<DecisionTableDmnXmlResult>(await roundTripResponsePromise);
  expect(roundTrip.valid).toBe(true);
  expect(roundTrip.dmnXml).toContain('aura:valueLabels');
  expect(roundTrip.dmnXml).toContain('value="annual"');
  expect(roundTrip.dmnXml).toContain(`label="${escapeXmlAttribute(annualLabel)}"`);
  expect(roundTrip.model?.inputs?.[0]).toEqual(
    expect.objectContaining({
      id: 'record_data_wd_req_type',
      path: 'data.wd_req_type',
      allowedValues: expect.arrayContaining(['annual', 'sick']),
      valueLabels: expect.objectContaining({
        annual: expect.stringMatching(/年假|Annual/i),
        sick: expect.stringMatching(/病假|Sick/i),
      }),
    }),
  );
  expect(roundTrip.model?.rules?.[0]?.when?.record_data_wd_req_type?.value).toEqual(['annual', 'sick']);
  await expect(page.getByTestId('dt-dmn-status')).toContainText('Round-trip 通过', {
    timeout: 15_000,
  });
  await expect.poll(() => selectedOptionLabels(page, 'val-0-record_data_wd_req_type')).toEqual(
    expect.arrayContaining([annualLabel, sickLabel]),
  );
  const exportedDecisionName = roundTrip.dmnXml?.match(/\bname="([^"]+)"/)?.[1] ?? 'wd_manager_approve_sla';

  const exportResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/decision/tables/export-dmn'),
  );
  const downloadPromise = page.waitForEvent('download');
  await page.getByTestId('dt-export-dmn').click();
  const [download, exportResponse] = await Promise.all([downloadPromise, exportResponsePromise]);
  const exported = await readApi<DecisionTableDmnXmlResult>(exportResponse);
  expect(exported.valid).toBe(true);
  expect(exported.dmnXml).toContain('xmlns:aura="https://auraboot.io/schema/dmn/metadata"');
  expect(exported.dmnXml).toContain('aura:valueLabels');
  expect(exported.dmnXml).toContain('value="sick"');
  expect(exported.dmnXml).toContain(`label="${escapeXmlAttribute(sickLabel)}"`);
  const suggested = download.suggestedFilename();
  expect(suggested).toBe(`${exportedDecisionName}.dmn.xml`);
  const downloadedPath = testInfo.outputPath(suggested);
  await download.saveAs(downloadedPath);
  const downloadedXml = await readFile(downloadedPath, 'utf8');
  expect(downloadedXml.length).toBeGreaterThan(100);
  expect(downloadedXml).toContain('<definitions');
  expect(downloadedXml).toContain('<decisionTable');
  expect(downloadedXml).toContain('aura:valueLabels');
  expect(downloadedXml).toContain('value="annual"');
  expect(downloadedXml).toContain(`label="${escapeXmlAttribute(annualLabel)}"`);
  expect(downloadedXml).toContain('value="sick"');
  expect(downloadedXml).toContain(`label="${escapeXmlAttribute(sickLabel)}"`);
  await expect(page.getByTestId('dt-dmn-status')).toContainText('DMN XML 已导出', {
    timeout: 15_000,
  });

  const importResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/decision/tables/import-dmn'),
  );
  await page.getByTestId('dt-import-dmn').click();
  const imported = await readApi<DecisionTableDmnXmlResult>(await importResponsePromise);
  expect(imported.valid).toBe(true);
  expect(imported.model?.inputs?.[0]?.valueLabels).toEqual(
    expect.objectContaining({
      annual: expect.stringMatching(/年假|Annual/i),
      sick: expect.stringMatching(/病假|Sick/i),
    }),
  );
  await expect(page.getByTestId('dt-dmn-status')).toContainText('DMN XML 已导入', {
    timeout: 15_000,
  });
  await expect.poll(() => selectedOptionLabels(page, 'val-0-record_data_wd_req_type')).toEqual(
    expect.arrayContaining([annualLabel, sickLabel]),
  );
  await expect(page.getByTestId('dt-dmn-xml')).toHaveValue(/aura:valueLabels/);

  await page.screenshot({
    path: testInfo.outputPath('strategy-studio-dmn-value-labels.png'),
    fullPage: true,
  });
});

test('Strategy Studio saves, publishes and reloads dict IN and NOT_IN raw arrays @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const catalog = await getApi<DecisionFactCatalog>(
    page,
    '/api/decision/facts/catalog?modelCode=wd_leave_request',
  );
  const leaveTypeFact = findLeaveTypeFact(catalog);
  expect(leaveTypeFact).toMatchObject({
    label: expect.stringMatching(/请假类型|Leave Type/i),
    dictCode: 'wd_leave_type',
  });
  const annualLabel = optionLabel(leaveTypeFact, 'annual');
  const sickLabel = optionLabel(leaveTypeFact, 'sick');

  await openRuleCenterFromSidebar(page);
  await configureLeaveTypeRule(page, 'IN', { annual: annualLabel, sick: sickLabel });
  const inVersion = await saveDraftAndPublishStrategyTable(page);
  expect(inVersion.kind).toBe('DECISION_TABLE');
  expect(inVersion.runtimeAdapter).toBe('PLATFORM_DECISION_TABLE');
  expect(inVersion.contentJson?.inputs?.[0]).toEqual(
    expect.objectContaining({
      id: 'record_data_wd_req_type',
      path: 'data.wd_req_type',
      allowedValues: expect.arrayContaining(['annual', 'sick']),
      valueLabels: expect.objectContaining({
        annual: expect.stringMatching(/年假|Annual/i),
        sick: expect.stringMatching(/病假|Sick/i),
      }),
    }),
  );
  expect(leaveTypeCell(inVersion)).toEqual(
    expect.objectContaining({
      operator: 'IN',
      value: ['annual', 'sick'],
    }),
  );

  const uiEvaluateResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/decision/evaluate'),
  );
  await page.getByTestId('strategy-run-test').click();
  const uiEvaluate = await readApi<DecisionResult>(await uiEvaluateResponsePromise);
  expect(uiEvaluate.matched).toBe(true);
  await expect(page.getByTestId('strategy-operation-status')).toContainText('测试通过', {
    timeout: 15_000,
  });

  const inHit = await runDraftTable(page, tableWithoutDefaultOutput(inVersion), 'annual');
  expect(inHit.status).toBe('MATCHED');
  expect(inHit.matched).toBe(true);
  expect(inHit.outputs?.route).toBe('notify');
  const inMiss = await runDraftTable(page, tableWithoutDefaultOutput(inVersion), 'personal');
  expect(inMiss.status).toBe('NOT_MATCHED');
  expect(inMiss.matched).toBe(false);

  await configureLeaveTypeRule(page, 'NOT_IN', { annual: annualLabel, sick: sickLabel });
  const notInVersion = await saveDraftAndPublishStrategyTable(page);
  expect(leaveTypeCell(notInVersion)).toEqual(
    expect.objectContaining({
      operator: 'NOT_IN',
      value: ['annual', 'sick'],
    }),
  );

  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('strategy-studio')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('strategy-workspace-tab-dmn').click();
  await expect(page.getByTestId('strategy-dmn-panel')).toHaveAttribute('data-active', 'true');
  await expect(page.getByTestId('dt-in-record_data_wd_req_type')).toContainText(/请假类型|Leave Type/i, {
    timeout: 15_000,
  });
  await expect(page.getByLabel('op-0-record_data_wd_req_type')).toHaveValue('NOT_IN');
  await expect.poll(() => selectedOptionLabels(page, 'val-0-record_data_wd_req_type')).toEqual(
    expect.arrayContaining([annualLabel, sickLabel]),
  );

  const notInHit = await runDraftTable(page, tableWithoutDefaultOutput(notInVersion), 'personal');
  expect(notInHit.status).toBe('MATCHED');
  expect(notInHit.matched).toBe(true);
  expect(notInHit.outputs?.route).toBe('notify');
  const notInMiss = await runDraftTable(page, tableWithoutDefaultOutput(notInVersion), 'annual');
  expect(notInMiss.status).toBe('NOT_MATCHED');
  expect(notInMiss.matched).toBe(false);

  await page.getByLabel('op-0-record_data_wd_req_type').scrollIntoViewIfNeeded();
  await page.screenshot({
    path: testInfo.outputPath('strategy-studio-dict-in-not-in-reload.png'),
    fullPage: true,
  });
});
